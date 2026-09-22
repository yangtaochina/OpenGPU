"""HTTP client for the 智由 platform **worker** API.

This module wraps :class:`requests.Session` and implements the exact contract
from ``docs/API.md``:

* ``Authorization: Bearer <worker token>`` on every call,
* the ``{code, message, data}`` envelope is unwrapped; a non-zero ``code``
  raises :class:`ApiError`,
* ``204 No Content`` on ``claim`` means "no task available" and maps to
  ``None``,
* ``code == 40910`` (lease expired) maps to :class:`LeaseExpiredError` so the
  main loop can abandon the task silently instead of reporting ``fail``.

Connect timeout is 10s.  The read timeout for the long-poll ``claim`` call is
``waitSeconds + 15`` as required by the contract.
"""

from __future__ import annotations

import logging
import os
import random
import time
from dataclasses import dataclass, field
from typing import Any

import requests

log = logging.getLogger("worker.api")

#: Business code returned by the server when a lease is no longer valid.
CODE_LEASE_EXPIRED = 40910
#: Business code returned when the worker token is rejected.
CODE_UNAUTHORIZED = 40100
#: Business code returned when the worker has no permission (e.g. disabled).
CODE_FORBIDDEN = 40300

DEFAULT_CONNECT_TIMEOUT = 10.0
DEFAULT_READ_TIMEOUT = 30.0
MAX_BACKOFF_SECONDS = 8.0


class ApiError(Exception):
    """Raised for network failures and non-zero business codes."""

    def __init__(
        self,
        code: int,
        message: str,
        http_status: int | None = None,
        payload: Any = None,
    ) -> None:
        super().__init__(f"[{code}] {message} (HTTP {http_status})")
        self.code = code
        self.message = message
        self.http_status = http_status
        self.payload = payload


class LeaseExpiredError(ApiError):
    """The lease is gone/expired (HTTP 409 + code 40910).

    The main loop must treat this as "abandon this task silently" and must
    **not** call ``fail`` for it.
    """


class AuthError(ApiError):
    """The worker token was rejected (HTTP 401 + code 40100)."""


@dataclass
class TaskAssignment:
    """Payload returned by ``POST /api/worker/tasks/claim``."""

    task_id: str
    attempt_id: str
    attempt_no: int
    prompt: str
    lease_id: str
    lease_expires_at: str | None = None
    raw: dict = field(default_factory=dict)

    @classmethod
    def from_dict(cls, data: dict) -> "TaskAssignment":
        if not isinstance(data, dict):
            raise ApiError(-1, f"claim returned an unexpected payload: {data!r}")
        missing = [k for k in ("taskId", "leaseId", "prompt") if not data.get(k)]
        if missing:
            raise ApiError(-1, f"claim payload is missing fields: {', '.join(missing)}")
        return cls(
            task_id=str(data["taskId"]),
            attempt_id=str(data.get("attemptId") or ""),
            attempt_no=int(data.get("attemptNo") or 0),
            prompt=str(data.get("prompt") or ""),
            lease_id=str(data["leaseId"]),
            lease_expires_at=data.get("leaseExpiresAt"),
            raw=data,
        )


class ApiClient:
    """Thin, contract-aware wrapper around the platform worker endpoints."""

    def __init__(
        self,
        base_url: str,
        token: str,
        *,
        connect_timeout: float = DEFAULT_CONNECT_TIMEOUT,
        user_agent: str = "opengpu-worker/0.1.0",
    ) -> None:
        self.base_url = base_url.rstrip("/")
        self._token = token
        self.connect_timeout = connect_timeout
        self.user_agent = user_agent

        self._session = requests.Session()
        # Keep the pool small; one process talks to one backend.
        adapter = requests.adapters.HTTPAdapter(
            pool_connections=4,
            pool_maxsize=8,
            max_retries=0,  # retries are implemented here so we control the policy
        )
        self._session.mount("http://", adapter)
        self._session.mount("https://", adapter)
        self._session.headers.update(
            {
                "Authorization": f"Bearer {token}",
                "Accept": "application/json",
                "User-Agent": user_agent,
            }
        )

    # -- lifecycle ---------------------------------------------------------
    def close(self) -> None:
        try:
            self._session.close()
        except Exception:  # pragma: no cover - defensive
            pass

    # -- low level ---------------------------------------------------------
    def _url(self, path: str) -> str:
        return f"{self.base_url}{path}"

    def _timeout(self, read_seconds: float) -> tuple[float, float]:
        return (self.connect_timeout, float(read_seconds))

    @staticmethod
    def _sleep_backoff(attempt: int) -> None:
        delay = min(MAX_BACKOFF_SECONDS, 0.5 * (2 ** attempt)) + random.uniform(0.0, 0.5)
        time.sleep(delay)

    @staticmethod
    def _is_retryable_exception(exc: requests.exceptions.RequestException) -> bool:
        # Connection errors and timeouts are worth retrying; a read timeout on
        # a long-poll is *not* retried here because the server already waited.
        return isinstance(
            exc,
            (
                requests.exceptions.ConnectionError,
                requests.exceptions.ConnectTimeout,
                requests.exceptions.ChunkedEncodingError,
            ),
        )

    def _unwrap(self, response: requests.Response) -> Any:
        """Turn an HTTP response into the envelope ``data`` (or ``None``)."""

        status = response.status_code
        try:
            payload = response.json()
        except ValueError:
            payload = None

        if isinstance(payload, dict) and "code" in payload:
            code = payload.get("code")
            message = payload.get("message") or ""
            if code == 0:
                return payload.get("data")
            if code == CODE_LEASE_EXPIRED or (status == 409 and code == CODE_LEASE_EXPIRED):
                raise LeaseExpiredError(code, message or "lease expired", status, payload)
            if code == CODE_UNAUTHORIZED:
                raise AuthError(code, message or "unauthorized", status, payload)
            raise ApiError(code, message or "request failed", status, payload)

        if response.ok:
            # Envelope-less success (defensive; contract says this should not
            # happen except for 204 / file downloads which we handle above).
            return payload

        snippet = (response.text or "")[:300]
        raise ApiError(-1, f"HTTP {status}: {snippet}", status, payload)

    def _request(
        self,
        method: str,
        path: str,
        *,
        json_body: dict | None = None,
        data: dict | None = None,
        files: dict | None = None,
        read_timeout: float = DEFAULT_READ_TIMEOUT,
        retries: int = 0,
        retry_5xx: bool = True,
    ) -> Any:
        """Perform a request, unwrap the envelope and apply the retry policy.

        ``retries`` is the number of *additional* attempts.  Retries happen on
        connection errors and (when ``retry_5xx`` is set) on 5xx responses.
        4xx responses are never retried.
        """

        url = self._url(path)
        timeout = self._timeout(read_timeout)
        last_exc: Exception | None = None

        for attempt in range(retries + 1):
            try:
                response = self._session.request(
                    method,
                    url,
                    json=json_body,
                    data=data,
                    files=files,
                    timeout=timeout,
                )
            except requests.exceptions.RequestException as exc:
                last_exc = exc
                if attempt < retries and self._is_retryable_exception(exc):
                    log.warning(
                        "%s %s failed (%s), retrying in backoff (attempt %d/%d)",
                        method, path, exc.__class__.__name__, attempt + 1, retries,
                    )
                    self._sleep_backoff(attempt)
                    continue
                raise ApiError(-1, f"network error calling {method} {path}: {exc}") from exc

            if response.status_code == 204:
                return None

            if response.status_code >= 500 and retry_5xx and attempt < retries:
                log.warning(
                    "%s %s returned HTTP %d, retrying (attempt %d/%d)",
                    method, path, response.status_code, attempt + 1, retries,
                )
                self._sleep_backoff(attempt)
                continue

            return self._unwrap(response)

        raise ApiError(-1, f"request to {method} {path} failed after {retries + 1} attempts: {last_exc}")

    # -- worker endpoints --------------------------------------------------
    def register(
        self,
        *,
        name: str,
        gpu_model: str,
        vram_mb: int,
        worker_version: str,
        model_version: str,
        gpu_tier: str = "",
        max_duration_seconds: int = 0,
        supported_resolutions: str = "",
        retries: int = 1,
    ) -> dict:
        """``POST /api/workers/register`` -> ``WorkerView``.

        Sends the node's **capability declaration** so the platform can match
        tasks against it (see docs/API.md §5 and §7):

        * ``vramMb``               → hard constraint for任务匹配（0 = 未上报）
        * ``gpuTier``              → optional; the server derives it from vram when omitted
        * ``maxDurationSeconds``   → 0/omitted means unlimited
        * ``supportedResolutions`` → informational only

        ``retries`` is kept low because ``WorkerRuntime._register`` already
        retries the whole registration with its own backoff; retrying in both
        layers would multiply the startup delay when the backend is down.
        """
        body = {
            "name": name,
            "gpuModel": gpu_model,
            "vramMb": vram_mb,
            "workerVersion": worker_version,
            "modelVersion": model_version,
            "gpuTier": gpu_tier or None,
            "maxDurationSeconds": max_duration_seconds,
            "supportedResolutions": supported_resolutions or None,
        }
        return self._request("POST", "/api/workers/register", json_body=body, retries=retries)

    def heartbeat(
        self,
        status: str,
        current_task_id: str | None = None,
        *,
        retries: int = 3,
    ) -> dict:
        """``POST /api/workers/heartbeat``.

        ``status`` is ``IDLE`` or ``BUSY``.  When ``BUSY`` the server uses
        ``currentTaskId`` to renew the task lease.
        """
        body = {"status": status, "currentTaskId": current_task_id}
        return self._request(
            "POST", "/api/workers/heartbeat", json_body=body, retries=retries
        )

    def claim(self, wait_seconds: int, *, retries: int = 2) -> dict | None:
        """``POST /api/worker/tasks/claim`` (long poll).

        Returns the raw ``TaskAssignment`` dict, or ``None`` when the server
        answers ``204 No Content``.  The read timeout is deliberately
        ``waitSeconds + 15`` so the connection outlives the server-side wait.
        """
        body = {"waitSeconds": int(wait_seconds)}
        read_timeout = max(DEFAULT_READ_TIMEOUT, int(wait_seconds) + 15)
        return self._request(
            "POST",
            "/api/worker/tasks/claim",
            json_body=body,
            read_timeout=read_timeout,
            retries=retries,
        )

    def progress(
        self, task_id: str, lease_id: str, progress: int, *, retries: int = 2
    ) -> dict:
        """``POST /api/worker/tasks/{taskId}/progress`` — also renews the lease."""
        body = {"leaseId": lease_id, "progress": int(progress)}
        return self._request(
            "POST",
            f"/api/worker/tasks/{task_id}/progress",
            json_body=body,
            read_timeout=15.0,
            retries=retries,
        )

    def complete(
        self,
        task_id: str,
        *,
        lease_id: str,
        file_key: str,
        file_size: int,
        checksum: str,
        duration_seconds: float,
        width: int,
        height: int,
        retries: int = 3,
    ) -> dict:
        """``POST /api/worker/tasks/{taskId}/complete`` (idempotent server side).

        The contract allows a handful of retries because a repeated ``complete``
        with the same lease returns the same result (AT-06).
        """
        body = {
            "leaseId": lease_id,
            "fileKey": file_key,
            "fileSize": int(file_size),
            "checksum": checksum,
            "durationSeconds": float(duration_seconds),
            "width": int(width),
            "height": int(height),
        }
        return self._request(
            "POST",
            f"/api/worker/tasks/{task_id}/complete",
            json_body=body,
            read_timeout=30.0,
            retries=retries,
        )

    def fail(
        self,
        task_id: str,
        *,
        lease_id: str,
        error_code: str,
        error_message: str,
        retryable: bool,
        retries: int = 3,
    ) -> dict:
        """``POST /api/worker/tasks/{taskId}/fail``."""
        body = {
            "leaseId": lease_id,
            "errorCode": error_code,
            "errorMessage": error_message,
            "retryable": bool(retryable),
        }
        return self._request(
            "POST",
            f"/api/worker/tasks/{task_id}/fail",
            json_body=body,
            read_timeout=20.0,
            retries=retries,
        )

    def presign(
        self,
        *,
        filename: str,
        content_type: str,
        size_bytes: int,
        retries: int = 2,
    ) -> dict:
        """``POST /api/worker/files/presign`` -> upload instructions."""
        body = {
            "filename": filename,
            "contentType": content_type,
            "sizeBytes": int(size_bytes),
        }
        return self._request(
            "POST", "/api/worker/files/presign", json_body=body, read_timeout=15.0, retries=retries
        )

    def upload(
        self,
        *,
        file_key: str,
        file_path: str,
        content_type: str = "video/mp4",
        retries: int = 2,
    ) -> dict:
        """``POST /api/worker/files`` multipart upload (field names ``fileKey``/``file``).

        The file is re-opened on every attempt because the multipart body is
        consumed by the first try.
        """
        filename = os.path.basename(file_path)
        last_exc: ApiError | None = None

        for attempt in range(retries + 1):
            try:
                with open(file_path, "rb") as handle:
                    files = {"file": (filename, handle, content_type)}
                    data = {"fileKey": file_key}
                    return self._request(
                        "POST",
                        "/api/worker/files",
                        data=data,
                        files=files,
                        read_timeout=600.0,
                        retries=0,  # handled by this loop so the file can be reopened
                    )
            except LeaseExpiredError:
                raise
            except ApiError as exc:
                last_exc = exc
                if attempt < retries and (exc.http_status is None or exc.http_status >= 500):
                    log.warning(
                        "upload of %s failed (%s), retrying (attempt %d/%d)",
                        file_key, exc, attempt + 1, retries,
                    )
                    self._sleep_backoff(attempt)
                    continue
                raise

        raise last_exc or ApiError(-1, "upload failed")
