"""MiniMax H3 adapter.

.. warning::

   **M0 STUB.**  This engine must only be relied upon *after* the MiniMax H3
   single-machine deployment validation has passed.  Until then it is a thin,
   contract-correct wrapper around a user supplied command template; the output
   resolution / frame rate / clip length below are **unverified placeholders**.

The engine runs ``OPENGPU_H3_COMMAND`` as a command template that supports two
placeholders:

``{prompt}``
    The task prompt (``TaskAssignment.prompt``).
``{output}``
    Absolute path the engine must write the mp4 to.

Command execution choice
------------------------

The template is parsed into an **argv list** and executed with
``subprocess.run(argv, shell=False)`` on every platform:

* On Windows the template is parsed with ``shlex.split(..., posix=False)`` so
  that backslashes in paths such as ``C:\\tools\\h3.exe`` survive; a pair of
  surrounding quotes on a token is stripped afterwards.
* On POSIX the template is parsed with ``shlex.split(..., posix=True)``.

We deliberately do **not** use ``shell=True``: the prompt is user controlled,
so routing it through a shell would allow command injection.  Passing it as a
single argv element is safe.  The trade-off is that the template may only
describe a plain command (no ``&&``, pipes or shell redirection); wrap complex
setups in a small launcher script instead.

Failure mapping
---------------

stdout/stderr are captured and inspected to classify common failures:

===================  =====================================================
error code           trigger
===================  =====================================================
``CUDA_OOM``         CUDA / torch "out of memory" messages (显存不足)
``DISK_FULL``        "No space left on device" / "disk full"
``MODEL_LOAD_FAILED``model/checkpoint loading errors (also a missing binary)
``TIMEOUT``          the process exceeded ``OPENGPU_H3_TIMEOUT``
``INFERENCE_FAILED`` any other non-zero exit / missing output
``CONFIG_ERROR``     ``OPENGPU_H3_COMMAND`` missing or unparsable
===================  =====================================================
"""

from __future__ import annotations

import logging
import os
import shlex
import subprocess

from .base import Engine, EngineError, TaskResult, probe_video

log = logging.getLogger("worker.engine.h3")

# ---------------------------------------------------------------------------
# M0 STUB - UNVERIFIED PLACEHOLDERS.
#
# The values below are *not* measured.  They must be replaced with the real
# output width/height/duration that the MiniMax H3 single-machine deployment
# validation (milestone M0) produces.  Until that validation passes, results
# produced by this engine must NOT be considered AT-03 verified.
# ---------------------------------------------------------------------------
PLACEHOLDER_WIDTH = 1280
PLACEHOLDER_HEIGHT = 720
PLACEHOLDER_DURATION_SECONDS = 5.0
PLACEHOLDER_CONTENT_TYPE = "video/mp4"

#: Tail length (characters) of stdout/stderr kept for error messages.
_LOG_TAIL_CHARS = 2000

# Failure signatures, checked in order.
_CUDA_OOM_SIGNATURES = (
    "cuda out of memory",
    "cuda_error_out_of_memory",
    "torch.cuda.outofmemoryerror",
    "out of memory",
    "outofmemoryerror",
    "oom",
    "显存不足",
)
_DISK_FULL_SIGNATURES = (
    "no space left on device",
    "disk full",
    "errno 28",
    "not enough space",
    "磁盘空间不足",
)
_MODEL_LOAD_SIGNATURES = (
    "failed to load model",
    "error loading model",
    "cannot load model",
    "checkpoint",
    "safetensors",
    "modelloaderror",
    "no such file or directory",
    "unexpected key",
    "missing key",
)


class H3Engine(Engine):
    """MiniMax H3 adapter (M0 stub)."""

    name = "h3"

    def __init__(self, config) -> None:
        super().__init__(config)
        self._timeout = int(config.h3_timeout)
        self._command_template = (config.h3_command or "").strip()

    # -- Engine API --------------------------------------------------------
    def generate(self, task, progress_cb) -> TaskResult:
        task_id = getattr(task, "task_id", "task")
        prompt = getattr(task, "prompt", "") or ""

        log.warning(
            "taskId=%s H3 engine is an M0 STUB; output metadata uses unverified "
            "placeholders until the MiniMax H3 deployment validation passes",
            task_id,
        )

        work_dir = self.config.work_dir
        work_dir.mkdir(parents=True, exist_ok=True)
        output_path = str(work_dir / f"h3_{task_id}.mp4")

        argv = self._build_argv(prompt, output_path)
        progress_cb(0)

        log.info("taskId=%s running H3 command: %s", task_id, _display_argv(argv))
        try:
            proc = subprocess.run(
                argv,
                capture_output=True,
                text=True,
                timeout=self._timeout,
                cwd=str(work_dir),
            )
        except subprocess.TimeoutExpired as exc:
            raise EngineError(
                "TIMEOUT",
                f"MiniMax H3 inference exceeded OPENGPU_H3_TIMEOUT={self._timeout}s",
                retryable=True,
            ) from exc
        except FileNotFoundError as exc:
            raise EngineError(
                "MODEL_LOAD_FAILED",
                f"H3 command executable not found: {argv[0]!r}. "
                "Check OPENGPU_H3_COMMAND.",
                retryable=False,
            ) from exc
        except OSError as exc:
            raise EngineError(
                "INFERENCE_FAILED",
                f"failed to start H3 command {argv[0]!r}: {exc}",
                retryable=True,
            ) from exc

        stdout = proc.stdout or ""
        stderr = proc.stderr or ""
        combined = f"{stdout}\n{stderr}"

        if proc.returncode != 0:
            code = _classify_failure(combined)
            raise EngineError(
                code,
                f"H3 command exited with {proc.returncode}: {_tail(stderr or stdout)}",
                retryable=code in ("CUDA_OOM", "DISK_FULL", "TIMEOUT", "INFERENCE_FAILED"),
            )

        if not _is_non_empty_file(output_path):
            # Some launchers print a CUDA OOM but still exit 0.
            code = _classify_failure(combined) or "INFERENCE_FAILED"
            raise EngineError(
                code,
                f"H3 reported success but produced no output at {output_path}: "
                f"{_tail(stderr or stdout)}",
                retryable=code in ("CUDA_OOM", "DISK_FULL", "TIMEOUT", "INFERENCE_FAILED"),
            )

        progress_cb(100)
        return self._build_result(output_path, task_id)

    # -- internals ---------------------------------------------------------
    def _build_argv(self, prompt: str, output_path: str) -> list[str]:
        if not self._command_template:
            raise EngineError(
                "CONFIG_ERROR",
                "OPENGPU_H3_COMMAND is not configured. Set it to the H3 command "
                "template, e.g. \"python infer.py --prompt {prompt} --out {output}\".",
                retryable=False,
            )

        # posix=False on Windows keeps backslashes intact (see module docstring).
        try:
            tokens = shlex.split(self._command_template, posix=(os.name != "nt"))
        except ValueError as exc:
            raise EngineError(
                "CONFIG_ERROR",
                f"cannot parse OPENGPU_H3_COMMAND: {exc}",
                retryable=False,
            ) from exc

        if not tokens:
            raise EngineError(
                "CONFIG_ERROR", "OPENGPU_H3_COMMAND is empty", retryable=False
            )

        argv: list[str] = []
        for token in tokens:
            token = _strip_surrounding_quotes(token)
            try:
                argv.append(token.format(prompt=prompt, output=output_path))
            except (KeyError, IndexError, ValueError) as exc:
                raise EngineError(
                    "CONFIG_ERROR",
                    f"invalid placeholder in OPENGPU_H3_COMMAND token {token!r}: {exc}",
                    retryable=False,
                ) from exc
        return argv

    def _build_result(self, output_path: str, task_id: str) -> TaskResult:
        probe = probe_video(output_path)
        if probe and probe.get("width") and probe.get("height"):
            duration = probe.get("duration_seconds") or PLACEHOLDER_DURATION_SECONDS
            log.info(
                "taskId=%s H3 output probed: %sx%s @ %ss",
                task_id, probe["width"], probe["height"], duration,
            )
            return TaskResult(
                file_path=output_path,
                duration_seconds=float(duration),
                width=int(probe["width"]),
                height=int(probe["height"]),
                content_type=PLACEHOLDER_CONTENT_TYPE,
                filename=f"h3_{task_id}.mp4",
            )

        # M0 STUB fallback: these numbers are placeholders, not measurements.
        log.warning(
            "taskId=%s ffprobe unavailable; reporting unverified placeholder "
            "metadata %sx%s @ %ss",
            task_id, PLACEHOLDER_WIDTH, PLACEHOLDER_HEIGHT, PLACEHOLDER_DURATION_SECONDS,
        )
        return TaskResult(
            file_path=output_path,
            duration_seconds=PLACEHOLDER_DURATION_SECONDS,
            width=PLACEHOLDER_WIDTH,
            height=PLACEHOLDER_HEIGHT,
            content_type=PLACEHOLDER_CONTENT_TYPE,
            filename=f"h3_{task_id}.mp4",
        )


# ---------------------------------------------------------------------------
# Module helpers
# ---------------------------------------------------------------------------
def _classify_failure(text: str) -> str:
    lowered = (text or "").lower()
    if any(sig in lowered for sig in _CUDA_OOM_SIGNATURES):
        return "CUDA_OOM"
    if any(sig in lowered for sig in _DISK_FULL_SIGNATURES):
        return "DISK_FULL"
    if any(sig in lowered for sig in _MODEL_LOAD_SIGNATURES):
        return "MODEL_LOAD_FAILED"
    return "INFERENCE_FAILED"


def _strip_surrounding_quotes(token: str) -> str:
    if len(token) >= 2 and token[0] == token[-1] and token[0] in ("'", '"'):
        return token[1:-1]
    return token


def _tail(text: str, limit: int = _LOG_TAIL_CHARS) -> str:
    text = (text or "").strip()
    if len(text) <= limit:
        return text
    return "..." + text[-limit:]


def _display_argv(argv: list[str]) -> str:
    """Render argv for logs without dumping an entire prompt."""
    rendered = []
    for token in argv:
        if len(token) > 60:
            rendered.append(token[:57] + "...")
        else:
            rendered.append(token)
    return " ".join(rendered)


def _is_non_empty_file(path: str) -> bool:
    try:
        return os.path.isfile(path) and os.path.getsize(path) > 0
    except OSError:
        return False
