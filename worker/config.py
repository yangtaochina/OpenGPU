"""Environment-based configuration for the OpenGPU GPU Worker.

The worker reads *all* of its configuration from environment variables so the
same code can run in CI, on a developer box and on a GPU node.  A tiny,
dependency-free ``.env`` loader is provided as a convenience; it never
overrides values that are already present in the real environment.

The only third-party dependency of this worker is ``requests``.
"""

from __future__ import annotations

import os
import socket
from dataclasses import dataclass
from pathlib import Path

# Directory that contains this file (the ``worker/`` directory).  Relative
# paths in the configuration are resolved against it so that ``run.bat`` /
# ``run.sh`` behave the same no matter where they are invoked from.
BASE_DIR = Path(__file__).resolve().parent

DEFAULT_API_BASE = "http://localhost:8080"
DEFAULT_WORKER_VERSION = "0.1.0"
DEFAULT_MODEL_VERSION = "MiniMax-H3"
DEFAULT_HEARTBEAT_INTERVAL = 20
DEFAULT_POLL_WAIT_SECONDS = 20
MAX_POLL_WAIT_SECONDS = 30
DEFAULT_WORK_DIR = "./work"
DEFAULT_ENGINE = "mock"
DEFAULT_MOCK_DURATION_SECONDS = 6
DEFAULT_H3_TIMEOUT = 3600
DEFAULT_MAX_UPLOAD_BYTES = 2 * 1024 * 1024 * 1024  # 2 GiB, matches server config
DEFAULT_CONNECT_TIMEOUT = 10.0
DEFAULT_LOG_LEVEL = "INFO"

VALID_ENGINES = ("mock", "h3")


class ConfigError(Exception):
    """Raised when the environment does not describe a usable worker."""


# ---------------------------------------------------------------------------
# Minimal .env loader
# ---------------------------------------------------------------------------
def load_dotenv(path: os.PathLike | str | None = None, *, override: bool = False) -> bool:
    """Load ``KEY=VALUE`` pairs from a ``.env`` file into ``os.environ``.

    This intentionally supports only the simple subset needed by this project:

    * blank lines and lines starting with ``#`` are ignored,
    * an optional leading ``export `` is stripped,
    * the value is split on the *first* ``=`` only,
    * surrounding single or double quotes are removed.

    Existing environment variables are preserved unless ``override`` is true,
    so a real environment always wins over the file.  Returns ``True`` when a
    file was found and parsed.
    """

    if path is None:
        path = BASE_DIR / ".env"
    env_path = Path(path)
    if not env_path.is_file():
        return False

    try:
        raw_lines = env_path.read_text(encoding="utf-8").splitlines()
    except OSError:
        return False

    for line in raw_lines:
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("export "):
            line = line[len("export "):].strip()
        if "=" not in line:
            continue
        key, _, value = line.partition("=")
        key = key.strip()
        if not key:
            continue
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in ("'", '"'):
            value = value[1:-1]
        if override or key not in os.environ:
            os.environ[key] = value
    return True


# ---------------------------------------------------------------------------
# Small typed accessors
# ---------------------------------------------------------------------------
def _env_str(name: str, default: str = "") -> str:
    value = os.environ.get(name)
    if value is None:
        return default
    return value.strip()


def _env_optional(name: str) -> str | None:
    value = os.environ.get(name)
    if value is None:
        return None
    value = value.strip()
    return value or None


def _env_int(name: str, default: int) -> int:
    value = _env_optional(name)
    if value is None:
        return default
    try:
        return int(value)
    except ValueError as exc:
        raise ConfigError(f"{name} must be an integer, got {value!r}") from exc


def _env_float(name: str, default: float) -> float:
    value = _env_optional(name)
    if value is None:
        return default
    try:
        return float(value)
    except ValueError as exc:
        raise ConfigError(f"{name} must be a number, got {value!r}") from exc


def _env_bool(name: str, default: bool = False) -> bool:
    value = _env_optional(name)
    if value is None:
        return default
    return value.lower() in ("1", "true", "yes", "on")


# ---------------------------------------------------------------------------
# Configuration object
# ---------------------------------------------------------------------------
@dataclass
class Config:
    """Fully resolved worker configuration."""

    api_base: str
    worker_token: str
    worker_name: str
    gpu_model: str
    vram_mb: int
    gpu_tier: str
    max_duration_seconds: int
    supported_resolutions: str
    worker_version: str
    model_version: str
    heartbeat_interval: int
    poll_wait_seconds: int
    work_dir: Path
    engine: str
    mock_duration_seconds: float
    mock_sample_video: Path | None
    h3_command: str
    h3_timeout: int
    max_upload_bytes: int
    connect_timeout: float
    log_level: str

    @property
    def masked_token(self) -> str:
        return mask_token(self.worker_token)

    def describe(self) -> str:
        """Human readable summary that never contains the full token."""
        return (
            f"api_base={self.api_base} worker_name={self.worker_name} "
            f"engine={self.engine} gpu_model={self.gpu_model} vram_mb={self.vram_mb} "
            f"gpu_tier={self.gpu_tier or 'auto'} max_duration={self.max_duration_seconds or 'unlimited'}s "
            f"supported_resolutions={self.supported_resolutions or 'unspecified'} "
            f"worker_version={self.worker_version} model_version={self.model_version} "
            f"heartbeat_interval={self.heartbeat_interval}s poll_wait={self.poll_wait_seconds}s "
            f"work_dir={self.work_dir} token={self.masked_token}"
        )


def mask_token(token: str | None) -> str:
    """Mask a worker token for logging: keep at most the first 12 characters.

    The API contract (docs/API.md 1.3) requires that the full token never ends
    up in logs.  ``wkr_<workerId>_<secret>`` is long enough that 12 characters
    only expose the ``wkr_`` prefix plus a few characters of the worker id.
    """

    if not token:
        return "<none>"
    if len(token) <= 12:
        return token[:4] + "***"
    return token[:12] + "***"


def _resolve_path(value: str) -> Path:
    path = Path(value).expanduser()
    if not path.is_absolute():
        path = (BASE_DIR / path).resolve()
    return path


def load_config() -> Config:
    """Build a :class:`Config` from the current environment.

    Raises :class:`ConfigError` when a required value is missing or invalid.
    """

    api_base = _env_str("OPENGPU_API_BASE", DEFAULT_API_BASE).rstrip("/")
    if not api_base:
        raise ConfigError("OPENGPU_API_BASE must not be empty")
    if not (api_base.startswith("http://") or api_base.startswith("https://")):
        raise ConfigError(f"OPENGPU_API_BASE must be an http(s) URL, got {api_base!r}")

    worker_token = _env_str("OPENGPU_WORKER_TOKEN")
    if not worker_token:
        raise ConfigError(
            "OPENGPU_WORKER_TOKEN is required. Create a worker in the admin page/API "
            "and copy the one-time token (format wkr_<uuid>_<secret>)."
        )
    if not worker_token.startswith("wkr_"):
        raise ConfigError(
            "OPENGPU_WORKER_TOKEN must have the format wkr_<workerId>_<secret> "
            f"(got {mask_token(worker_token)})"
        )

    worker_name = _env_str("OPENGPU_WORKER_NAME") or socket.gethostname() or "opengpu-worker"

    engine = _env_str("OPENGPU_ENGINE", DEFAULT_ENGINE).lower() or DEFAULT_ENGINE
    if engine not in VALID_ENGINES:
        raise ConfigError(
            f"OPENGPU_ENGINE must be one of {', '.join(VALID_ENGINES)}, got {engine!r}"
        )

    poll_wait = _env_int("OPENGPU_POLL_WAIT_SECONDS", DEFAULT_POLL_WAIT_SECONDS)
    if poll_wait < 0:
        poll_wait = 0
    if poll_wait > MAX_POLL_WAIT_SECONDS:
        poll_wait = MAX_POLL_WAIT_SECONDS

    heartbeat_interval = _env_int("OPENGPU_HEARTBEAT_INTERVAL", DEFAULT_HEARTBEAT_INTERVAL)
    if heartbeat_interval < 5:
        heartbeat_interval = 5

    mock_duration = _env_float("OPENGPU_MOCK_DURATION_SECONDS", DEFAULT_MOCK_DURATION_SECONDS)
    if mock_duration < 0:
        mock_duration = 0.0

    sample_video_raw = _env_optional("OPENGPU_MOCK_SAMPLE_VIDEO")
    sample_video = _resolve_path(sample_video_raw) if sample_video_raw else None

    h3_timeout = _env_int("OPENGPU_H3_TIMEOUT", DEFAULT_H3_TIMEOUT)
    if h3_timeout <= 0:
        raise ConfigError("OPENGPU_H3_TIMEOUT must be a positive number of seconds")

    max_upload = _env_int("OPENGPU_MAX_UPLOAD_BYTES", DEFAULT_MAX_UPLOAD_BYTES)
    if max_upload <= 0:
        raise ConfigError("OPENGPU_MAX_UPLOAD_BYTES must be positive")

    log_level = (_env_str("OPENGPU_LOG_LEVEL", DEFAULT_LOG_LEVEL) or DEFAULT_LOG_LEVEL).upper()

    # 显卡能力：平台据此匹配视频要求。
    # vram_mb 为 0 表示「未上报显存」，此时只能领取不限定硬件要求的任务。
    vram_mb = _env_int("OPENGPU_VRAM_MB", 0)
    if vram_mb < 0:
        raise ConfigError("OPENGPU_VRAM_MB must not be negative")
    gpu_tier = (_env_str("OPENGPU_GPU_TIER", "") or "").strip().upper()
    if gpu_tier and gpu_tier not in ("ENTRY", "STANDARD", "PRO", "ULTRA"):
        raise ConfigError("OPENGPU_GPU_TIER must be one of ENTRY, STANDARD, PRO, ULTRA")
    max_duration = _env_int("OPENGPU_MAX_DURATION_SECONDS", 0)
    if max_duration < 0:
        raise ConfigError("OPENGPU_MAX_DURATION_SECONDS must not be negative")

    return Config(
        api_base=api_base,
        worker_token=worker_token,
        worker_name=worker_name,
        gpu_model=_env_str("OPENGPU_GPU_MODEL", "unknown"),
        vram_mb=vram_mb,
        gpu_tier=gpu_tier,
        max_duration_seconds=max_duration,
        supported_resolutions=_env_str("OPENGPU_SUPPORTED_RESOLUTIONS", "") or "",
        worker_version=_env_str("OPENGPU_WORKER_VERSION", DEFAULT_WORKER_VERSION) or DEFAULT_WORKER_VERSION,
        model_version=_env_str("OPENGPU_MODEL_VERSION", DEFAULT_MODEL_VERSION) or DEFAULT_MODEL_VERSION,
        heartbeat_interval=heartbeat_interval,
        poll_wait_seconds=poll_wait,
        work_dir=_resolve_path(_env_str("OPENGPU_WORK_DIR", DEFAULT_WORK_DIR) or DEFAULT_WORK_DIR),
        engine=engine,
        mock_duration_seconds=mock_duration,
        mock_sample_video=sample_video,
        h3_command=_env_str("OPENGPU_H3_COMMAND"),
        h3_timeout=h3_timeout,
        max_upload_bytes=max_upload,
        connect_timeout=DEFAULT_CONNECT_TIMEOUT,
        log_level=log_level,
    )
