"""Local, dependency-free engine used for end-to-end testing (mock mode).

Behaviour (see the worker README for the full description):

1. Report progress from ``0`` to ``90`` over ``OPENGPU_MOCK_DURATION_SECONDS``,
   emitting a progress update roughly every two seconds.  The ``progress`` call
   also renews the server-side lease.
2. Produce a *playable* mp4:

   a. if ``ffmpeg`` is on ``PATH``, render a ~3 second ``testsrc`` clip with a
      440 Hz tone (H.264 + AAC, ``yuv420p``) and probe the real
      duration/width/height with ``ffprobe``;
   b. otherwise, if ``OPENGPU_MOCK_SAMPLE_VIDEO`` points at an existing file,
      copy it and probe it;
   c. otherwise write a small placeholder file and log a **WARNING** that the
      file is not playable and the task must not be treated as AT-03 verified.
3. Report ``100`` and return the metadata required by ``complete``.
"""

from __future__ import annotations

import logging
import os
import shutil
import subprocess
import time

from .base import Engine, EngineError, TaskResult, probe_video

log = logging.getLogger("worker.engine.mock")

#: Values requested from ffmpeg.  Used as the fallback metadata when ffprobe is
#: unavailable (the contract asks us to "fall back to the requested values").
MOCK_CLIP_SECONDS = 3.0
MOCK_WIDTH = 1280
MOCK_HEIGHT = 720
MOCK_FRAMERATE = 25
MOCK_FREQUENCY_HZ = 440

#: Progress emitted before the file is produced; the remaining 10% is reserved
#: for file generation + upload, and reported once the artefact exists.
PROGRESS_BEFORE_RENDER = 90

#: How often we report progress while "rendering".
PROGRESS_STEP_SECONDS = 2.0

_FFMPEG_TIMEOUT_SECONDS = 180.0

_PLACEHOLDER_BYTES = (
    b"OPENGPU MOCK PLACEHOLDER - NOT A PLAYABLE MP4\n"
    b"ffmpeg was not found on PATH and OPENGPU_MOCK_SAMPLE_VIDEO was not set.\n"
    b"This file exists only so the upload/complete flow can be exercised.\n"
)


class MockEngine(Engine):
    """Generates a local test clip without any GPU or model."""

    name = "mock"

    def __init__(self, config) -> None:
        super().__init__(config)
        self._duration = max(0.0, float(config.mock_duration_seconds))

    # -- Engine API --------------------------------------------------------
    def generate(self, task, progress_cb) -> TaskResult:
        log.info(
            "taskId=%s mock engine start (simulated duration=%.1fs)",
            getattr(task, "task_id", "?"),
            self._duration,
        )
        progress_cb(0)
        self._simulate_progress(progress_cb)
        result = self._produce(task)
        progress_cb(100)
        log.info(
            "taskId=%s mock engine produced %s (%ss, %sx%s)",
            getattr(task, "task_id", "?"),
            result.file_path,
            result.duration_seconds,
            result.width,
            result.height,
        )
        return result

    # -- internals ---------------------------------------------------------
    def _simulate_progress(self, progress_cb) -> None:
        """Report ``0 -> PROGRESS_BEFORE_RENDER`` over the configured duration."""

        total = self._duration
        if total <= 0:
            progress_cb(PROGRESS_BEFORE_RENDER)
            return

        started = time.monotonic()
        last_reported = -1
        while True:
            elapsed = time.monotonic() - started
            fraction = min(1.0, elapsed / total)
            percent = int(fraction * PROGRESS_BEFORE_RENDER)
            if percent != last_reported:
                progress_cb(percent)
                last_reported = percent
            if fraction >= 1.0:
                break
            remaining = total - elapsed
            time.sleep(max(0.05, min(PROGRESS_STEP_SECONDS, remaining)))

    def _produce(self, task) -> TaskResult:
        task_id = getattr(task, "task_id", "task")
        work_dir = self.config.work_dir
        work_dir.mkdir(parents=True, exist_ok=True)
        output_path = str(work_dir / f"mock_{task_id}.mp4")

        if self._render_with_ffmpeg(output_path):
            return self._result_from_file(output_path, task_id)

        if self._copy_sample(output_path):
            return self._result_from_file(output_path, task_id)

        return self._write_placeholder(output_path, task_id)

    def _render_with_ffmpeg(self, output_path: str) -> bool:
        ffmpeg = shutil.which("ffmpeg")
        if not ffmpeg:
            return False

        command = [
            ffmpeg, "-y",
            "-f", "lavfi", "-i", f"testsrc=size={MOCK_WIDTH}x{MOCK_HEIGHT}:rate={MOCK_FRAMERATE}",
            "-f", "lavfi", "-i", f"sine=frequency={MOCK_FREQUENCY_HZ}",
            "-t", str(MOCK_CLIP_SECONDS),
            "-pix_fmt", "yuv420p",
            "-c:v", "libx264",
            "-c:a", "aac",
            output_path,
        ]
        log.debug("running ffmpeg: %s", " ".join(command))
        try:
            proc = subprocess.run(
                command, capture_output=True, text=True, timeout=_FFMPEG_TIMEOUT_SECONDS
            )
        except (OSError, subprocess.SubprocessError) as exc:
            log.warning("ffmpeg failed to run (%s); falling back to sample/placeholder", exc)
            return False

        if proc.returncode != 0 or not _is_non_empty_file(output_path):
            tail = (proc.stderr or proc.stdout or "").strip().splitlines()[-3:]
            log.warning(
                "ffmpeg exited with %s; falling back to sample/placeholder. stderr tail: %s",
                proc.returncode,
                " | ".join(tail),
            )
            return False
        return True

    def _copy_sample(self, output_path: str) -> bool:
        sample = self.config.mock_sample_video
        if sample is None:
            return False
        if not os.path.isfile(sample):
            log.warning("OPENGPU_MOCK_SAMPLE_VIDEO=%s does not exist; ignoring", sample)
            return False
        try:
            shutil.copyfile(sample, output_path)
        except OSError as exc:
            log.warning("failed to copy sample video %s: %s", sample, exc)
            return False
        log.info("copied sample video %s -> %s", sample, output_path)
        return _is_non_empty_file(output_path)

    def _write_placeholder(self, output_path: str, task_id: str) -> TaskResult:
        with open(output_path, "wb") as handle:
            handle.write(_PLACEHOLDER_BYTES)
        log.warning(
            "taskId=%s MOCK OUTPUT IS NOT A PLAYABLE MP4: neither ffmpeg nor "
            "OPENGPU_MOCK_SAMPLE_VIDEO is available. Wrote a placeholder to %s. "
            "Do NOT treat this task as AT-03 verified.",
            task_id,
            output_path,
        )
        return TaskResult(
            file_path=output_path,
            duration_seconds=MOCK_CLIP_SECONDS,
            width=MOCK_WIDTH,
            height=MOCK_HEIGHT,
            filename=f"mock_{task_id}.mp4",
        )

    def _result_from_file(self, output_path: str, task_id: str) -> TaskResult:
        probe = probe_video(output_path)
        if probe and probe.get("width") and probe.get("height"):
            duration = probe.get("duration_seconds") or MOCK_CLIP_SECONDS
            return TaskResult(
                file_path=output_path,
                duration_seconds=float(duration),
                width=int(probe["width"]),
                height=int(probe["height"]),
                filename=f"mock_{task_id}.mp4",
            )

        log.info(
            "ffprobe unavailable or incomplete for %s; using requested values %sx%s @ %ss",
            output_path, MOCK_WIDTH, MOCK_HEIGHT, MOCK_CLIP_SECONDS,
        )
        return TaskResult(
            file_path=output_path,
            duration_seconds=MOCK_CLIP_SECONDS,
            width=MOCK_WIDTH,
            height=MOCK_HEIGHT,
            filename=f"mock_{task_id}.mp4",
        )


def _is_non_empty_file(path: str) -> bool:
    try:
        return os.path.isfile(path) and os.path.getsize(path) > 0
    except OSError:
        return False
