"""Step 6b: AI-compose BGM via ACE-Step (separate venv) → save to assets/bgm/<event_id>.mp3.

Strategy:
  - Read DirectorBrief.tone + AudienceBrief.pacing_guidance + Timeline.total_duration_sec
  - Translate into an ACE-Step caption (English, instrumental, vlog-friendly)
  - Choose BPM by tone (slow/medium/fast)
  - Call ACE-Step via gradio_client (its UI exposes a /predict endpoint)
  - Save resulting audio to assets/bgm/<event_id>.mp3
  - Run audio_beats.py to extract BPM + beat onsets, cache to assets/bgm/<event_id>.beats.json
  - Skip if already exists (idempotent — re-runs reuse cached BGM)

Prerequisite: ACE-Step server running (start_gradio_ui.bat in D:\dev\llm-app\ACE-Step-1.5)
              defaults to http://127.0.0.1:7860
"""
from __future__ import annotations

import argparse
import os
from pathlib import Path

import schemas  # forces UTF-8 stdio
from audio_beats import cached_analyze
from schemas import AudienceBrief, DirectorBrief, Timeline

ROOT = Path(__file__).parent
W = ROOT / "workspace"
BGM_DIR = ROOT / "assets" / "bgm"

ACESTEP_URL = os.environ.get("ACESTEP_URL", "http://127.0.0.1:7860")


# Map free-form tone keywords to ACE-Step caption ingredients
TONE_TEMPLATES = {
    "warm": "warm acoustic instrumental, soft piano and finger-picked guitar, gentle pads, no vocals, ambient texture, mellow uplifting feel",
    "cool": "lo-fi instrumental, mellow electric piano, downtempo beat, atmospheric synths, nostalgic mood, no vocals",
    "vibrant": "upbeat indie pop instrumental, bright synth arpeggios, punchy drums, layered guitars, energetic and playful, no vocals",
    "muted": "minimal piano instrumental, sparse arrangement, contemplative mood, soft strings, no vocals",
    "cinematic_teal_orange": "cinematic instrumental, swelling strings, soft ambient pads, subtle piano, emotional and uplifting, no vocals",
    "vintage": "vintage instrumental, mellotron and tape hiss, light drum brushes, nostalgic 70s feel, no vocals",
    "neutral": "soft instrumental background music, gentle piano and acoustic guitar, mellow rhythm, no vocals",
}

PACE_BPM = {
    "slow": 70,
    "medium": 95,
    "fast": 120,
}


def _infer_pace(tone: str, pacing_hint: str) -> str:
    """Pick slow/medium/fast from textual hints."""
    text = f"{tone} {pacing_hint}".lower()
    if any(k in text for k in ["快剪", "快节奏", "high-energy", "fast", "energetic", "vibrant", "punchy", "炸点"]):
        return "fast"
    if any(k in text for k in ["留白", "宁静", "慢镜", "ambient", "slow", "contemplative", "mellow", "cinematic"]):
        return "slow"
    return "medium"


def build_caption(brief: DirectorBrief, audience: AudienceBrief) -> tuple[str, int]:
    """Translate Director + Audience briefs into an ACE-Step caption + BPM target."""
    base = TONE_TEMPLATES.get(brief.color_grade, TONE_TEMPLATES["neutral"])
    pace = _infer_pace(brief.tone, audience.pacing_guidance)
    bpm = PACE_BPM[pace]
    # Append director intent in plain English-ish (ACE-Step accepts mixed prompts)
    caption = f"{base}. Mood: {brief.tone}. Use this for a personal vlog, no lyrics, instrumental only."
    return caption, bpm


def call_acestep(caption: str, bpm: int, duration: float, out_path: Path) -> bool:
    """Call ACE-Step gradio server to generate an instrumental track. Returns True on success."""
    try:
        from gradio_client import Client
    except ImportError:
        print("[step6b] gradio_client not installed; run 'uv add gradio-client' in pc-pilot")
        return False

    print(f"[step6b] connecting to ACE-Step at {ACESTEP_URL}")
    try:
        client = Client(ACESTEP_URL)
    except Exception as e:
        print(f"[step6b] cannot connect to ACE-Step: {e}")
        print(f"  → start it first: cd D:/dev/llm-app/ACE-Step-1.5 && start_gradio_ui.bat")
        return False

    # NOTE: ACE-Step's gradio API signature must be discovered with client.view_api()
    # — we call this opportunistically and adapt. For now: try common endpoint names.
    try:
        # Try predict() with known kwargs
        result = client.predict(
            caption,         # text prompt
            "[Instrumental]",  # lyrics — empty/instrumental-only
            duration,        # duration_sec
            bpm,             # bpm
            api_name="/predict",
        )
        # result expected to be a path (str) to the generated audio
        if isinstance(result, (tuple, list)):
            audio_src = result[0] if result else None
        else:
            audio_src = result
        if audio_src and Path(audio_src).exists():
            import shutil
            shutil.copy2(audio_src, out_path)
            print(f"[step6b] BGM saved → {out_path}")
            return True
        print(f"[step6b] ACE-Step returned but no audio file found: {result}")
        return False
    except Exception as e:
        print(f"[step6b] ACE-Step call failed: {type(e).__name__}: {e}")
        print(f"  → run client.view_api() in a Python REPL to inspect actual signature")
        return False


def compose_for_event(event_id: str) -> Path | None:
    director_path = W / "director" / f"{event_id}.json"
    audience_path = W / "audience" / f"{event_id}.json"
    timeline_path = W / "timeline" / f"{event_id}_final.json"
    if not timeline_path.exists():
        timeline_path = W / "timeline" / f"{event_id}_v1.json"

    if not all(p.exists() for p in (director_path, audience_path, timeline_path)):
        print(f"[step6b] {event_id}: missing director/audience/timeline; run earlier steps first")
        return None

    brief = DirectorBrief.model_validate_json(director_path.read_text(encoding="utf-8"))
    audience = AudienceBrief.model_validate_json(audience_path.read_text(encoding="utf-8"))
    tl = Timeline.model_validate_json(timeline_path.read_text(encoding="utf-8"))

    out_path = BGM_DIR / f"{event_id}.mp3"
    BGM_DIR.mkdir(parents=True, exist_ok=True)

    if out_path.exists():
        print(f"[step6b] {event_id}: cached BGM exists -> {out_path}")
    else:
        caption, bpm = build_caption(brief, audience)
        # Add a small head/tail margin so the BGM never runs short of the video
        target_dur = max(20.0, tl.total_duration_sec + 2.0)
        print(f"[step6b] {event_id}: composing {target_dur:.0f}s @ {bpm} BPM")
        print(f"           caption: {caption[:120]}...")
        if not call_acestep(caption, bpm, target_dur, out_path):
            return None

    # Analyze beats for downstream snapping
    beats_info = cached_analyze(out_path)
    print(f"[step6b] {event_id}: detected BPM={beats_info['bpm']:.1f}, "
          f"{len(beats_info['beats_sec'])} beats over {beats_info['duration_sec']:.1f}s")
    return out_path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--event", default=None, help="only one event")
    args = parser.parse_args()

    if args.event:
        event_ids = [args.event]
    else:
        from schemas import Event
        events_path = W / "events.jsonl"
        events = [Event.model_validate_json(l) for l in events_path.read_text(encoding="utf-8").splitlines() if l.strip()]
        event_ids = [e.event_id for e in events]

    for eid in event_ids:
        compose_for_event(eid)


if __name__ == "__main__":
    main()
