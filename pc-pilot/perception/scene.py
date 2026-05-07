"""Video shot detection (PySceneDetect) + keyframe extraction.

For each video / live_photo asset:
  1. Detect shot boundaries via content-aware comparison
  2. For each shot, save 1 keyframe (mid-frame) as JPG to workspace/keyframes/<asset_id>_s<idx>.jpg
  3. Return list of VideoShot dicts

Live Photos are short (~3 seconds) so usually 1 shot. Long videos can have many shots.
"""
from __future__ import annotations

import subprocess
from pathlib import Path

from scenedetect import detect as sd_detect, ContentDetector

from schemas import VideoShot

ROOT = Path(__file__).parent.parent
KEYFRAMES_DIR = ROOT / "workspace" / "keyframes"


def _save_keyframe(video_path: Path, time_sec: float, out_path: Path) -> bool:
    """Use ffmpeg to extract a single frame at the given time."""
    out_path.parent.mkdir(parents=True, exist_ok=True)
    # -ss before -i is fast (keyframe seek); accurate enough for vlog purposes.
    cmd = [
        "ffmpeg",
        "-y",
        "-loglevel",
        "error",
        "-ss",
        f"{time_sec:.3f}",
        "-i",
        str(video_path),
        "-frames:v",
        "1",
        "-q:v",
        "3",
        str(out_path),
    ]
    try:
        subprocess.run(cmd, check=True, capture_output=True, timeout=30)
        return out_path.exists() and out_path.stat().st_size > 0
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as e:
        print(f"  ! keyframe extract failed for {video_path.name} @ {time_sec}s: {e}")
        return False


def detect_shots(asset_id: str, video_path: Path, *, threshold: float = 27.0) -> list[VideoShot]:
    """Returns list of VideoShot for this video. Empty list if detection fails."""
    try:
        scenes = sd_detect(str(video_path), ContentDetector(threshold=threshold))
    except Exception as e:
        print(f"  ! scenedetect failed for {video_path.name}: {type(e).__name__}: {e}")
        return []

    if not scenes:
        # Single-shot video: synthesize one shot covering the whole duration.
        try:
            import av

            container = av.open(str(video_path))
            duration = float(container.duration / 1_000_000) if container.duration else 0.0
            container.close()
        except Exception:
            duration = 0.0
        scenes = [(_TC(0.0), _TC(duration))]

    out: list[VideoShot] = []
    for idx, (start_tc, end_tc) in enumerate(scenes):
        start = start_tc.get_seconds() if hasattr(start_tc, "get_seconds") else float(start_tc)
        end = end_tc.get_seconds() if hasattr(end_tc, "get_seconds") else float(end_tc)
        mid = (start + end) / 2.0
        kf_path = KEYFRAMES_DIR / f"{asset_id}_s{idx:02d}.jpg"
        if _save_keyframe(video_path, mid, kf_path):
            out.append(
                VideoShot(
                    shot_idx=idx,
                    start_sec=start,
                    end_sec=end,
                    keyframe_path=str(kf_path.relative_to(ROOT)),
                )
            )

    return out


class _TC:
    """Tiny stand-in for FrameTimecode when scenedetect returns nothing."""

    def __init__(self, sec: float):
        self._s = sec

    def get_seconds(self) -> float:
        return self._s
