"""Frame-level micro-edit: pick the most 'punch' sub-segment of a video clip.

For each candidate video / live-photo, sample N frames at uniform intervals, build a
small contact sheet, and ask the VLM which 1-2 frames are the visual peak (a smile
just breaking, a peak motion, a perfect composition). Translate that to a trim_start
so the rendered shot uses the best window of the clip instead of the first N seconds.

This is what fixes 'we showed Live Photo cover but missed the actual best 0.3s'.
"""
from __future__ import annotations

import math
import subprocess
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

from montage import build_contact_sheet, _load_font, _draw_index_badge

ROOT = Path(__file__).parent.parent


def _ffprobe_duration(video_path: Path) -> float:
    """Returns video duration in seconds, 0 on failure."""
    try:
        r = subprocess.run(
            ["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "default=noprint_wrappers=1:nokey=1", str(video_path)],
            check=True, capture_output=True, timeout=15,
        )
        return float(r.stdout.decode().strip())
    except (subprocess.CalledProcessError, ValueError, subprocess.TimeoutExpired):
        return 0.0


def extract_frames_uniform(video_path: Path, n_frames: int, out_dir: Path) -> list[Path]:
    """Extract n_frames evenly across the video. Returns list of frame jpg paths."""
    out_dir.mkdir(parents=True, exist_ok=True)
    duration = _ffprobe_duration(video_path)
    if duration <= 0:
        return []

    times = [duration * (i + 0.5) / n_frames for i in range(n_frames)]
    paths = []
    for i, t in enumerate(times):
        out = out_dir / f"f{i + 1:02d}.jpg"
        cmd = [
            "ffmpeg", "-y", "-loglevel", "error",
            "-ss", f"{t:.3f}", "-i", str(video_path),
            "-frames:v", "1", "-q:v", "3",
            str(out),
        ]
        try:
            subprocess.run(cmd, check=True, capture_output=True, timeout=15)
            if out.exists() and out.stat().st_size > 0:
                paths.append(out)
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
            continue
    return paths


VLM_FRAME_PICK_SYSTEM = """你是一名视频剪辑师。我会给你一段视频按时间均匀抽出的 N 张关键帧（按编号 1..N，从早到晚）。
请挑出"最有 vlog 感的瞬间"——通常是：
- 表情最自然/最真实的一帧（比如笑容刚绽放、惊讶的瞬间）
- 动作的高潮（不是起势也不是收势，是中段的 peak）
- 构图最干净的瞬间
- 光影最妙的时刻

避免：模糊、闭眼、转脸到一半、构图最差的中间帧

输出严格 JSON：
{
  "best_frame_index": 1-N 之间的整数,
  "rationale": "<=50 字解释为什么这一帧最 punch"
}"""

VLM_FRAME_PICK_USER = "请挑出最 punch 的 1 帧。"


def pick_best_window(
    video_path: Path,
    target_duration_sec: float,
    *,
    n_samples: int = 8,
    cache_dir: Path | None = None,
    excluded_zones: list[tuple[float, float]] | None = None,
) -> float:
    """Returns trim_start so [trim_start, trim_start+target_duration] is the punch window.

    excluded_zones: list of (start, end) seconds already used by other shots — frames
    falling inside any zone are removed from the candidate set so the same long video
    can supply non-overlapping shots.
    """
    from vlm_client import call_vlm_json

    duration = _ffprobe_duration(video_path)
    if duration <= 0 or duration <= target_duration_sec:
        return 0.0

    cache_dir = cache_dir or (ROOT / "workspace" / "frame_picks" / video_path.stem)
    cache_dir.mkdir(parents=True, exist_ok=True)

    # Sample frames uniformly, then drop those that fall inside excluded zones
    frame_times = [duration * (i + 0.5) / n_samples for i in range(n_samples)]
    if excluded_zones:
        def in_zone(t):
            return any(s - 0.3 <= t <= e + 0.3 for s, e in excluded_zones)
        keep_indices = [i for i, t in enumerate(frame_times) if not in_zone(t)]
    else:
        keep_indices = list(range(n_samples))

    if len(keep_indices) < 2:
        # Fall back to first non-excluded position
        for i, t in enumerate(frame_times):
            if not excluded_zones or not any(s - 0.3 <= t <= e + 0.3 for s, e in excluded_zones):
                return max(0.0, min(duration - target_duration_sec, t - target_duration_sec / 2))
        return 0.0

    frames = extract_frames_uniform(video_path, n_samples, cache_dir)
    if len(frames) < 2:
        return 0.0
    # Filter to non-excluded frames (matched by index)
    frames = [frames[i] for i in keep_indices if i < len(frames)]
    frame_times = [frame_times[i] for i in keep_indices]
    if not frames:
        return 0.0

    sheet_path = cache_dir / "frames_sheet.jpg"
    build_contact_sheet(frames, sheet_path, cell_w=420, cell_h=748)

    try:
        raw = call_vlm_json(
            system=VLM_FRAME_PICK_SYSTEM,
            user_text=VLM_FRAME_PICK_USER,
            images=[sheet_path],
            schema=None,
            temperature=0.3,
            max_tokens=300,
        )
    except Exception as e:
        print(f"  ! frame pick failed for {video_path.name}: {e}; falling back to t=0")
        return 0.0

    try:
        idx = int(raw.get("best_frame_index", 1))
    except (TypeError, ValueError):
        idx = 1
    idx = max(1, min(len(frames), idx))

    peak_t = frame_times[idx - 1]  # use the actual time of the chosen (filtered) frame
    raw_trim = peak_t - target_duration_sec / 2
    trim_start = max(0.0, min(max(0.0, duration - target_duration_sec), raw_trim))
    print(f"      VLM picked frame {idx}/{len(frames)} @ peak_t={peak_t:.2f}s; trim={trim_start:.2f}s; rationale: {raw.get('rationale', '')[:60]}")
    return round(trim_start, 3)
