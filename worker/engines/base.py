"""Engine abstraction shared by every generation backend.

An *engine* is responsible for turning a claimed :class:`TaskAssignment` into a
local video file plus its metadata.  Everything else (upload, ``complete`` /
``fail`` reporting, lease renewal) is handled by the main loop in ``worker.py``.
"""

from __future__ import annotations

import abc
import hashlib
import json
import logging
import shutil
import subprocess
from dataclasses import dataclass
from typing import TYPE_CHECKING, Any, Callable, Optional

if TYPE_CHECKING:  # pragma: no cover - import used only for typing
    from config import Config

log = logging.getLogger("worker.engine")

#: Error codes that the main loop is allowed to report with ``retryable=true``.
RETRYABLE_ERROR_CODES = frozenset(
    {
        "CUDA_OOM",
        "DISK_FULL",
        "TIMEOUT",
        "INFERENCE_FAILED",
        "UPLOAD_FAILED",
        "UNKNOWN",
    }
)

#: Error codes that indicate a configuration problem; never retried.
NON_RETRYABLE_ERROR_CODES = frozenset(
    {
        "MODEL_LOAD_FAILED",
        "CONFIG_ERROR",
        "FILE_TOO_LARGE",
        "INVALID_PROMPT",
    }
)

#: Signature of the progress callback passed into :meth:`Engine.generate`.
#: ``progress_cb(percent)`` with ``percent`` in ``0..100``.
ProgressCallback = Callable[[int], None]


@dataclass
class TaskResult:
    """A generated artefact and the metadata required by ``complete``."""

    file_path: str
    duration_seconds: float
    width: int
    height: int
    content_type: str = "video/mp4"
    filename: str = "output.mp4"

    def __post_init__(self) -> None:
        if not self.filename:
            self.filename = "output.mp4"


class EngineError(Exception):
    """Raised by an engine when generation fails.

    ``code`` is reported to the platform as ``errorCode`` and should be one of
    the codes documented in ``docs/API.md`` / the worker README.  ``retryable``
    is derived from ``code`` unless explicitly provided.
    """

    def __init__(self, code: str, message: str, retryable: bool | None = None) -> None:
        super().__init__(f"[{code}] {message}")
        self.code = code
        self.message = message
        if retryable is None:
            retryable = code in RETRYABLE_ERROR_CODES
        self.retryable = bool(retryable)


class Engine(abc.ABC):
    """Base class for all generation engines."""

    #: Registry key / log name.
    name = "base"

    def __init__(self, config: "Config") -> None:
        self.config = config

    @abc.abstractmethod
    def generate(self, task: Any, progress_cb: ProgressCallback) -> TaskResult:
        """Generate a video for ``task`` and return a :class:`TaskResult`.

        Implementations may call ``progress_cb`` as often as they like; the
        callback also renews the server-side lease.  Implementations should
        raise :class:`EngineError` on failure and must let a
        ``LeaseExpiredError`` raised by ``progress_cb`` propagate.
        """

    def close(self) -> None:
        """Release resources.  Called once on shutdown."""


# ---------------------------------------------------------------------------
# Shared helpers
# ---------------------------------------------------------------------------
def sha256_file(path: str, chunk_size: int = 1024 * 1024) -> str:
    """Return the lowercase hex SHA-256 of a file (streamed, low memory)."""
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        while True:
            chunk = handle.read(chunk_size)
            if not chunk:
                break
            digest.update(chunk)
    return digest.hexdigest()


def probe_video(path: str, timeout: float = 20.0) -> Optional[dict]:
    """Probe a video with ``ffprobe`` when it is available.

    Returns ``{"duration_seconds": float|None, "width": int|None,
    "height": int|None}`` or ``None`` when ``ffprobe`` is missing or fails.
    """

    ffprobe = shutil.which("ffprobe")
    if not ffprobe:
        return None

    command = [
        ffprobe,
        "-v", "error",
        "-select_streams", "v:0",
        "-show_entries", "stream=width,height:format=duration",
        "-of", "json",
        path,
    ]
    try:
        proc = subprocess.run(
            command, capture_output=True, text=True, timeout=timeout
        )
    except (OSError, subprocess.SubprocessError):
        return None
    if proc.returncode != 0:
        return None

    try:
        payload = json.loads(proc.stdout or "{}")
    except ValueError:
        return None

    width = height = None
    streams = payload.get("streams") or []
    if streams:
        stream = streams[0]
        try:
            width = int(stream["width"])
            height = int(stream["height"])
        except (KeyError, TypeError, ValueError):
            width = height = None

    duration = None
    try:
        duration = float((payload.get("format") or {}).get("duration"))
    except (TypeError, ValueError):
        duration = None

    if width is None and height is None and duration is None:
        return None
    return {"duration_seconds": duration, "width": width, "height": height}
