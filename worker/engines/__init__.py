"""Engine registry / factory.

Engines are looked up by the ``OPENGPU_ENGINE`` value (``mock`` or ``h3``).
"""

from __future__ import annotations

from typing import TYPE_CHECKING

from .base import (
    NON_RETRYABLE_ERROR_CODES,
    RETRYABLE_ERROR_CODES,
    Engine,
    EngineError,
    ProgressCallback,
    TaskResult,
    probe_video,
    sha256_file,
)
from .h3_engine import H3Engine
from .mock_engine import MockEngine

if TYPE_CHECKING:  # pragma: no cover - typing only
    from config import Config

#: name -> engine class
ENGINE_REGISTRY: dict[str, type[Engine]] = {
    MockEngine.name: MockEngine,
    H3Engine.name: H3Engine,
}

__all__ = [
    "Engine",
    "EngineError",
    "TaskResult",
    "ProgressCallback",
    "ENGINE_REGISTRY",
    "create_engine",
    "available_engines",
    "sha256_file",
    "probe_video",
    "RETRYABLE_ERROR_CODES",
    "NON_RETRYABLE_ERROR_CODES",
]


def available_engines() -> list[str]:
    """Return the registered engine names."""
    return sorted(ENGINE_REGISTRY)


def create_engine(name: str, config: "Config") -> Engine:
    """Instantiate the engine registered under ``name``.

    Raises :class:`ValueError` for an unknown engine (``config.load_config``
    already validates the value, so this is a defensive check).
    """
    key = (name or "").strip().lower()
    try:
        engine_cls = ENGINE_REGISTRY[key]
    except KeyError as exc:
        raise ValueError(
            f"unknown engine {name!r}; available: {', '.join(available_engines())}"
        ) from exc
    return engine_cls(config)
