"""BPM + onset (beat) detection for an audio file.

Wraps librosa for two purposes:
  1. Estimate global tempo (BPM) — fed to step5_editor so director knows the music's pace
  2. Get onset times (seconds) — fed to step7_render so cuts can snap to actual beats

Output JSON cached at workspace/bgm/<event_id>_beats.json so we don't re-run librosa
every render. librosa's beat tracker is reasonably fast (<5s for 60s audio) but caching
keeps the pipeline snappy.
"""
from __future__ import annotations

import json
from pathlib import Path
from typing import Optional


def analyze_audio(audio_path: Path) -> dict:
    """Return {bpm, beats_sec, downbeats_sec, duration_sec}.

    - bpm: estimated tempo (float, ~60-180 typical)
    - beats_sec: list of beat onsets in seconds
    - downbeats_sec: subset of beats that are downbeats (every 4 beats by default)
    - duration_sec: total audio length
    """
    import librosa
    import numpy as np

    y, sr = librosa.load(str(audio_path), sr=None, mono=True)
    duration = float(librosa.get_duration(y=y, sr=sr))

    tempo, beat_frames = librosa.beat.beat_track(y=y, sr=sr, units="frames")
    beats_sec = librosa.frames_to_time(beat_frames, sr=sr).tolist()

    # Heuristic downbeats: every 4th beat (assumes 4/4 time signature)
    downbeats_sec = beats_sec[::4]

    return {
        "bpm": float(tempo[0]) if hasattr(tempo, "__iter__") else float(tempo),
        "beats_sec": [round(t, 3) for t in beats_sec],
        "downbeats_sec": [round(t, 3) for t in downbeats_sec],
        "duration_sec": round(duration, 3),
    }


def cached_analyze(audio_path: Path, cache_path: Optional[Path] = None) -> dict:
    """Analyze with on-disk cache. cache_path defaults to <audio_path>.beats.json."""
    if cache_path is None:
        cache_path = audio_path.with_suffix(audio_path.suffix + ".beats.json")
    if cache_path.exists():
        try:
            return json.loads(cache_path.read_text(encoding="utf-8"))
        except (json.JSONDecodeError, OSError):
            pass
    result = analyze_audio(audio_path)
    cache_path.parent.mkdir(parents=True, exist_ok=True)
    cache_path.write_text(json.dumps(result, indent=2), encoding="utf-8")
    return result


def snap_to_nearest_beat(time_sec: float, beats_sec: list[float], max_offset: float = 0.25) -> float:
    """Snap a target time to the nearest beat within max_offset seconds. Returns
    the original time if no beat is within range."""
    if not beats_sec:
        return time_sec
    nearest = min(beats_sec, key=lambda b: abs(b - time_sec))
    if abs(nearest - time_sec) <= max_offset:
        return nearest
    return time_sec


if __name__ == "__main__":
    import sys
    import schemas  # forces UTF-8 stdio on Windows

    if len(sys.argv) < 2:
        print("usage: python audio_beats.py <audio_file.mp3>")
        sys.exit(1)
    p = Path(sys.argv[1])
    info = cached_analyze(p)
    print(f"bpm: {info['bpm']:.1f}")
    print(f"duration: {info['duration_sec']:.1f}s")
    print(f"beats: {len(info['beats_sec'])} (first 8: {info['beats_sec'][:8]})")
    print(f"downbeats: {len(info['downbeats_sec'])}")
