"""OpenGPU GPU Worker — main loop / entrypoint.

Run with::

    python worker.py

The process is intentionally long-running and defensive: a single failed HTTP
request never kills the loop.  Lifecycle:

1. ``POST /api/workers/register`` once at startup.
2. A background daemon thread sends ``POST /api/workers/heartbeat`` every
   ``OPENGPU_HEARTBEAT_INTERVAL`` seconds with ``IDLE`` / ``BUSY`` (and the
   ``currentTaskId`` while a task runs, which renews the lease).
3. While idle the main thread long-polls ``POST /api/worker/tasks/claim``.
4. On a claim it runs the configured engine, reporting progress through
   ``POST /api/worker/tasks/{taskId}/progress`` (which also renews the lease).
5. It uploads the artefact via ``/api/worker/files/presign`` +
   ``/api/worker/files`` and finishes with ``.../complete`` or ``.../fail``.

A :class:`~api_client.LeaseExpiredError` means the server no longer considers
us the owner of the task: the task is abandoned silently and ``fail`` is never
called for it.
"""

from __future__ import annotations

import functools
import logging
import os
import signal
import sys
import threading
import time
from datetime import datetime, timezone

from api_client import (
    ApiClient,
    ApiError,
    AuthError,
    LeaseExpiredError,
    TaskAssignment,
)
from config import BASE_DIR, Config, ConfigError, load_config, load_dotenv, mask_token
from engines import EngineError, create_engine, sha256_file

log = logging.getLogger("worker")

REGISTER_MAX_ATTEMPTS = 5
REGISTER_BACKOFF_MAX = 15.0
LOOP_BACKOFF_MAX = 30.0
HEARTBEAT_JOIN_TIMEOUT = 5.0
#: Server-side upper bound on a single prompt (docs/API.md 3).
MAX_ERROR_MESSAGE_CHARS = 1000


# ---------------------------------------------------------------------------
# Logging
# ---------------------------------------------------------------------------
class _IsoFormatter(logging.Formatter):
    """Log formatter with local ISO-8601 timestamps including the offset."""

    def formatTime(self, record, datefmt=None):  # noqa: N802 - logging API
        moment = datetime.fromtimestamp(record.created, tz=timezone.utc).astimezone()
        return moment.isoformat(timespec="seconds")


def setup_logging(level: str = "INFO") -> None:
    # Windows consoles default to a legacy code page; never let an unmappable
    # character raise UnicodeEncodeError and kill the logging call.
    try:
        sys.stdout.reconfigure(errors="replace")
    except (AttributeError, OSError):
        pass

    handler = logging.StreamHandler(stream=sys.stdout)
    handler.setFormatter(
        _IsoFormatter("%(asctime)s %(levelname)-7s %(name)s: %(message)s")
    )
    root = logging.getLogger()
    root.handlers.clear()
    root.addHandler(handler)
    root.setLevel(getattr(logging, (level or "INFO").upper(), logging.INFO))
    # requests/urllib3 are chatty at DEBUG; keep them one notch quieter.
    logging.getLogger("urllib3").setLevel(logging.WARNING)


# ---------------------------------------------------------------------------
# Heartbeat thread
# ---------------------------------------------------------------------------
class HeartbeatThread(threading.Thread):
    """Daemon thread that keeps the node ONLINE/BUSY and renews the lease."""

    def __init__(self, api: ApiClient, config: Config, stop_event: threading.Event) -> None:
        super().__init__(name="opengpu-heartbeat", daemon=True)
        self._api = api
        self._config = config
        self._stop_event = stop_event
        self._lock = threading.Lock()
        self._wake = threading.Event()
        self._status = "IDLE"
        self._current_task_id: str | None = None
        self._interval = max(5, int(config.heartbeat_interval))

    # -- state -------------------------------------------------------------
    def set_idle(self) -> None:
        with self._lock:
            changed = self._status != "IDLE" or self._current_task_id is not None
            self._status = "IDLE"
            self._current_task_id = None
        if changed:
            self._wake.set()

    def set_busy(self, task_id: str) -> None:
        with self._lock:
            changed = self._status != "BUSY" or self._current_task_id != task_id
            self._status = "BUSY"
            self._current_task_id = task_id
        if changed:
            self._wake.set()

    def wake(self) -> None:
        """Ask the thread to send a heartbeat immediately."""
        self._wake.set()

    def _snapshot(self) -> tuple[str, str | None, int]:
        with self._lock:
            return self._status, self._current_task_id, self._interval

    # -- thread body -------------------------------------------------------
    def run(self) -> None:
        log.info("heartbeat thread started (interval=%ss)", self._interval)
        while not self._stop_event.is_set():
            status, task_id, interval = self._snapshot()
            try:
                data = self._api.heartbeat(status, task_id)
                if isinstance(data, dict):
                    server_interval = data.get("heartbeatIntervalSeconds")
                    if isinstance(server_interval, (int, float)) and server_interval >= 5:
                        with self._lock:
                            self._interval = int(server_interval)
                        interval = int(server_interval)
            except LeaseExpiredError as exc:
                # A heartbeat should not produce this, but if it does the task
                # is no longer ours; the main loop will notice on next progress.
                log.warning("heartbeat reported an expired lease: %s", exc)
            except AuthError as exc:
                log.error("heartbeat rejected (%s); check OPENGPU_WORKER_TOKEN", exc)
            except ApiError as exc:
                log.warning("heartbeat failed (will retry): %s", exc)
            except Exception:  # pragma: no cover - never kill the daemon
                log.exception("unexpected heartbeat error")

            self._wake.wait(timeout=interval)
            self._wake.clear()

        # Best effort final IDLE heartbeat so the node does not linger as BUSY.
        try:
            self._api.heartbeat("IDLE", None, retries=1)
        except Exception:
            pass
        log.info("heartbeat thread stopped")


# ---------------------------------------------------------------------------
# Worker runtime
# ---------------------------------------------------------------------------
class WorkerRuntime:
    """Owns the API client, the engine and the main claim/execute loop."""

    def __init__(self, config: Config) -> None:
        self.config = config
        self.api = ApiClient(
            config.api_base,
            config.worker_token,
            connect_timeout=config.connect_timeout,
            user_agent=f"opengpu-worker/{config.worker_version}",
        )
        self.engine = create_engine(config.engine, config)
        self.stop_event = threading.Event()
        self.heartbeat = HeartbeatThread(self.api, config, self.stop_event)
        self._current_task: TaskAssignment | None = None

    # -- shutdown ----------------------------------------------------------
    def request_stop(self, signum=None, frame=None) -> None:
        if not self.stop_event.is_set():
            name = "signal" if signum is None else f"signal {signum}"
            log.info("shutdown requested (%s); finishing the current step", name)
        self.stop_event.set()
        self.heartbeat.wake()

    def shutdown(self) -> None:
        self.stop_event.set()
        self.heartbeat.wake()
        if self.heartbeat.is_alive():
            self.heartbeat.join(timeout=HEARTBEAT_JOIN_TIMEOUT)
        try:
            self.engine.close()
        except Exception:
            log.exception("error closing engine")
        self.api.close()
        log.info("worker stopped")

    # -- startup -----------------------------------------------------------
    def _register(self) -> None:
        cfg = self.config
        last_error: Exception | None = None
        for attempt in range(1, REGISTER_MAX_ATTEMPTS + 1):
            try:
                view = self.api.register(
                    name=cfg.worker_name,
                    gpu_model=cfg.gpu_model,
                    vram_mb=cfg.vram_mb,
                    worker_version=cfg.worker_version,
                    model_version=cfg.model_version,
                    gpu_tier=cfg.gpu_tier,
                    max_duration_seconds=cfg.max_duration_seconds,
                    supported_resolutions=cfg.supported_resolutions,
                )
                view = view or {}
                log.info(
                    "registered worker id=%s name=%s status=%s runtimeStatus=%s "
                    "vram=%sMB tier=%s maxDuration=%ss",
                    view.get("id"), view.get("name"), view.get("status"),
                    view.get("runtimeStatus"), view.get("vramMb"),
                    view.get("gpuTier"), view.get("maxDurationSeconds"),
                )
                return
            except AuthError as exc:
                raise SystemExit(
                    f"worker token was rejected by the server ({exc}). "
                    f"Verify OPENGPU_WORKER_TOKEN (currently {cfg.masked_token})."
                ) from exc
            except ApiError as exc:
                last_error = exc
                if exc.http_status is not None and 400 <= exc.http_status < 500:
                    # e.g. 40000 validation error: retrying will not help.
                    raise SystemExit(f"registration rejected: {exc}") from exc
                delay = min(REGISTER_BACKOFF_MAX, 0.5 * (2 ** (attempt - 1)))
                if attempt < REGISTER_MAX_ATTEMPTS:
                    log.warning(
                        "registration failed (attempt %d/%d): %s; retrying in %.1fs",
                        attempt, REGISTER_MAX_ATTEMPTS, exc, delay,
                    )
                    self._interruptible_sleep(delay)
                else:
                    log.warning(
                        "registration failed (attempt %d/%d): %s",
                        attempt, REGISTER_MAX_ATTEMPTS, exc,
                    )
        raise SystemExit(f"could not register the worker after {REGISTER_MAX_ATTEMPTS} attempts: {last_error}")

    def _interruptible_sleep(self, seconds: float) -> None:
        """Sleep but wake up promptly on shutdown."""
        deadline = time.monotonic() + seconds
        while not self.stop_event.is_set():
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                return
            time.sleep(min(0.5, remaining))

    # -- main loop ---------------------------------------------------------
    def run(self) -> None:
        self._register()
        self.heartbeat.start()

        backoff = 1.0
        while not self.stop_event.is_set():
            self.heartbeat.set_idle()
            try:
                payload = self.api.claim(self.config.poll_wait_seconds)
            except LeaseExpiredError:
                # Not expected on claim; treat as a no-op.
                continue
            except AuthError as exc:
                log.error("claim rejected (%s); check OPENGPU_WORKER_TOKEN", exc)
                self._interruptible_sleep(min(LOOP_BACKOFF_MAX, backoff))
                backoff = min(LOOP_BACKOFF_MAX, backoff * 2)
                continue
            except ApiError as exc:
                if exc.http_status == 403:
                    # Disabled worker (AT-09): keep heartbeating, but do not spin.
                    log.warning("claim forbidden (node disabled?): %s", exc)
                    self._interruptible_sleep(LOOP_BACKOFF_MAX)
                    continue
                log.warning("claim failed: %s", exc)
                self._interruptible_sleep(min(LOOP_BACKOFF_MAX, backoff))
                backoff = min(LOOP_BACKOFF_MAX, backoff * 2)
                continue
            except Exception:
                log.exception("unexpected error while claiming a task")
                self._interruptible_sleep(min(LOOP_BACKOFF_MAX, backoff))
                backoff = min(LOOP_BACKOFF_MAX, backoff * 2)
                continue

            backoff = 1.0
            if payload is None:
                continue  # 204 No Content -> no task, poll again

            try:
                task = TaskAssignment.from_dict(payload)
            except ApiError as exc:
                log.error("ignoring malformed claim payload: %s", exc)
                continue

            try:
                self._execute(task)
            except LeaseExpiredError:
                log.warning(
                    "taskId=%s lease expired while working; abandoning the task "
                    "(no fail reported)", task.task_id,
                )
            except Exception:
                log.exception("taskId=%s unexpected error while executing", task.task_id)

        log.info("main loop stopped")

    # -- task execution ----------------------------------------------------
    def _execute(self, task: TaskAssignment) -> None:
        self._current_task = task
        self.heartbeat.set_busy(task.task_id)
        log.info(
            "taskId=%s attemptNo=%s claimed prompt=%r",
            task.task_id, task.attempt_no, _shorten(task.prompt, 80),
        )
        try:
            self._run_task(task)
        finally:
            self._current_task = None
            self.heartbeat.set_idle()

    def _run_task(self, task: TaskAssignment) -> None:
        self.config.work_dir.mkdir(parents=True, exist_ok=True)
        progress_cb = functools.partial(self._on_progress, task)

        try:
            result = self.engine.generate(task, progress_cb)
        except LeaseExpiredError:
            raise
        except EngineError as exc:
            log.error(
                "taskId=%s engine failed code=%s retryable=%s: %s",
                task.task_id, exc.code, exc.retryable, exc.message,
            )
            self._report_failure(task, exc.code, exc.message, exc.retryable)
            return
        except Exception as exc:
            log.exception("taskId=%s engine raised an unexpected error", task.task_id)
            self._report_failure(task, "INFERENCE_FAILED", f"unexpected engine error: {exc}", True)
            return

        try:
            file_key, file_size, checksum = self._upload(task, result)
        except LeaseExpiredError:
            raise
        except EngineError as exc:
            log.error("taskId=%s upload rejected: %s", task.task_id, exc.message)
            self._report_failure(task, exc.code, exc.message, exc.retryable)
            return
        except ApiError as exc:
            log.error("taskId=%s upload failed: %s", task.task_id, exc)
            self._report_failure(task, "UPLOAD_FAILED", f"upload failed: {exc}", True)
            return

        try:
            response = self.api.complete(
                task.task_id,
                lease_id=task.lease_id,
                file_key=file_key,
                file_size=file_size,
                checksum=checksum,
                duration_seconds=result.duration_seconds,
                width=result.width,
                height=result.height,
            )
            response = response or {}
            log.info(
                "taskId=%s completed status=%s resultId=%s fileUrl=%s",
                task.task_id, response.get("status"), response.get("resultId"),
                response.get("fileUrl"),
            )
        except LeaseExpiredError:
            raise
        except ApiError as exc:
            # e.g. 40920 (file missing / checksum mismatch): the task must not
            # reach SUCCEEDED.  We do not call fail here; the server lease
            # reaper returns it to QUEUED.
            log.error(
                "taskId=%s complete failed (%s); leaving the task for lease recovery",
                task.task_id, exc,
            )

    # -- progress / upload / fail -----------------------------------------
    def _on_progress(self, task: TaskAssignment, percent) -> None:
        try:
            value = int(percent)
        except (TypeError, ValueError):
            return
        value = max(0, min(100, value))
        try:
            self.api.progress(task.task_id, task.lease_id, value)
            log.debug("taskId=%s progress=%d%%", task.task_id, value)
        except LeaseExpiredError:
            log.warning("taskId=%s lease expired while reporting progress", task.task_id)
            raise
        except ApiError as exc:
            # Progress is best-effort: a transient failure must not abort a run.
            log.warning("taskId=%s progress report failed (continuing): %s", task.task_id, exc)

    def _upload(self, task: TaskAssignment, result) -> tuple[str, int, str]:
        path = result.file_path
        if not os.path.isfile(path):
            raise EngineError("INFERENCE_FAILED", f"engine produced no file at {path}", True)
        file_size = os.path.getsize(path)
        if file_size <= 0:
            raise EngineError("INFERENCE_FAILED", f"engine output is empty: {path}", True)

        local_max = self.config.max_upload_bytes
        if file_size > local_max:
            raise EngineError(
                "FILE_TOO_LARGE",
                f"output is {file_size} bytes which exceeds OPENGPU_MAX_UPLOAD_BYTES={local_max}",
                False,
            )

        checksum = sha256_file(path)
        filename = result.filename or os.path.basename(path)
        presign = self.api.presign(
            filename=filename,
            content_type=result.content_type,
            size_bytes=file_size,
        ) or {}
        file_key = presign.get("fileKey")
        if not file_key:
            raise EngineError("UPLOAD_FAILED", "presign response did not contain fileKey", True)

        server_max = presign.get("maxSizeBytes")
        if isinstance(server_max, (int, float)) and file_size > int(server_max):
            raise EngineError(
                "FILE_TOO_LARGE",
                f"output is {file_size} bytes which exceeds the server limit {int(server_max)}",
                False,
            )

        uploaded = self.api.upload(
            file_key=file_key,
            file_path=path,
            content_type=result.content_type,
        ) or {}

        final_key = uploaded.get("fileKey") or file_key
        final_size = int(uploaded.get("size") or file_size)
        final_checksum = uploaded.get("checksum") or checksum
        log.info(
            "taskId=%s uploaded fileKey=%s size=%s checksum=%s",
            task.task_id, final_key, final_size, _shorten(final_checksum, 16),
        )
        return final_key, final_size, final_checksum

    def _report_failure(self, task: TaskAssignment, code: str, message: str, retryable: bool) -> None:
        try:
            response = self.api.fail(
                task.task_id,
                lease_id=task.lease_id,
                error_code=code,
                error_message=_shorten(message, MAX_ERROR_MESSAGE_CHARS),
                retryable=retryable,
            )
            response = response or {}
            log.warning(
                "taskId=%s reported failure code=%s retryable=%s -> status=%s retryCount=%s",
                task.task_id, code, retryable, response.get("status"), response.get("retryCount"),
            )
        except LeaseExpiredError:
            log.warning(
                "taskId=%s lease expired before fail could be reported; abandoning",
                task.task_id,
            )
        except ApiError as exc:
            log.error("taskId=%s could not report failure: %s", task.task_id, exc)


# ---------------------------------------------------------------------------
# Helpers / entrypoint
# ---------------------------------------------------------------------------
def _shorten(value, limit: int) -> str:
    text = "" if value is None else str(value)
    if len(text) <= limit:
        return text
    return text[: max(0, limit - 3)] + "..."


def install_signal_handlers(runtime: WorkerRuntime) -> None:
    """Install graceful-shutdown handlers for SIGINT/SIGTERM (and SIGBREAK)."""
    for name in ("SIGINT", "SIGTERM", "SIGBREAK"):
        sig = getattr(signal, name, None)
        if sig is None:
            continue
        try:
            signal.signal(sig, runtime.request_stop)
        except (ValueError, OSError):  # not the main thread / unsupported
            pass


def main(argv=None) -> int:
    # Load worker/.env (if present) before reading the environment.
    load_dotenv(BASE_DIR / ".env")

    try:
        config = load_config()
    except ConfigError as exc:
        print(f"configuration error: {exc}", file=sys.stderr)
        return 2

    setup_logging(config.log_level)
    log.info(
        "OpenGPU worker starting version=%s pid=%s",
        config.worker_version, os.getpid(),
    )
    log.info("configuration: %s", config.describe())

    runtime = WorkerRuntime(config)
    install_signal_handlers(runtime)

    exit_code = 0
    try:
        runtime.run()
    except KeyboardInterrupt:
        log.info("interrupted by user")
    except SystemExit:
        raise
    except Exception:
        log.exception("fatal error in the worker main loop")
        exit_code = 1
    finally:
        runtime.shutdown()
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
