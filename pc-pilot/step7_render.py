"""Step 7 (v2): Render Timeline → MP4. Reuses step5_render machinery, just feeds it
   from workspace/timeline/<event>_v1.json (or _final.json once critic exists).

Outputs to workspace/candidates_v2/<event_id>.mp4 (latest)
+ workspace/candidates_v2/archive/<event_id>__YYYYMMDD_HHMMSS.mp4 (each iteration)
so you can compare across runs and never lose a previous take.
"""
from __future__ import annotations

import argparse
import shutil
import time
from datetime import datetime
from pathlib import Path

import schemas
from schemas import Asset, ShotSpec, Timeline
from step5_render import (
    concat_with_xfade,
    find_bgm,
    find_bgm_for_event,
    render_image_shot,
    render_video_shot,
)

ROOT = Path(__file__).parent
W = ROOT / "workspace"


def _archive_previous(out_path: Path) -> Path | None:
    """If out_path already has a previous render, move it to archive/ with a timestamp."""
    if not out_path.exists():
        return None
    archive_dir = out_path.parent / "archive"
    archive_dir.mkdir(parents=True, exist_ok=True)
    # Use file's mtime as the archive stamp (when it was rendered, not now)
    stamp = datetime.fromtimestamp(out_path.stat().st_mtime).strftime("%Y%m%d_%H%M%S")
    archive_path = archive_dir / f"{out_path.stem}__{stamp}{out_path.suffix}"
    # If a file with that exact stamp already exists, append a counter
    n = 1
    while archive_path.exists():
        archive_path = archive_dir / f"{out_path.stem}__{stamp}_{n}{out_path.suffix}"
        n += 1
    shutil.move(str(out_path), str(archive_path))
    return archive_path


def render_timeline(tl: Timeline, assets: dict[str, Asset], out_dir: Path) -> Path | None:
    cache_dir = W / "render_cache_v2" / tl.event_id
    cache_dir.mkdir(parents=True, exist_ok=True)

    shot_paths: list[Path] = []
    shot_durations: list[float] = []   # ACTUAL rendered duration per shot — fed to xfade
    transitions: list[str] = []
    captions_used: list[str] = []
    actual_shots: list[ShotSpec] = []  # mutated copies with actual durations for caller to persist
    sorted_shots = sorted(tl.shots, key=lambda x: x.order)
    for s in sorted_shots:
        a = assets.get(s.asset_id)
        if not a:
            print(f"  ! shot {s.order}: asset {s.asset_id} missing, skip")
            continue
        out = cache_dir / f"shot_{s.order:03d}.mp4"
        try:
            if s.media_type == "image" and a.image_path:
                _, actual_dur = render_image_shot(ROOT / a.image_path, s.duration_sec, s.ken_burns, s.caption, out, color_grade=tl.color_grade)
            elif s.media_type in ("video", "live_photo") and a.video_path:
                trim_start = s.video_trim_start or 0.0
                _, actual_dur = render_video_shot(ROOT / a.video_path, trim_start, s.duration_sec, s.caption, out, color_grade=tl.color_grade)
            elif a.image_path:
                _, actual_dur = render_image_shot(ROOT / a.image_path, s.duration_sec, s.ken_burns, s.caption, out, color_grade=tl.color_grade)
            else:
                continue
            shot_paths.append(out)
            shot_durations.append(actual_dur)
            transitions.append(s.transition_in)
            captions_used.append(s.caption)
            # Persist actual duration onto a copy for the caller to write back to timeline
            actual_shots.append(s.model_copy(update={"duration_sec": round(actual_dur, 2)}))
        except Exception as e:
            print(f"  ! shot {s.order} ({a.asset_id}) failed: {type(e).__name__}: {e}")
            continue

    if not shot_paths:
        return None

    # Per-event BGM (composed by step6b_compose_bgm) → fall back to color_grade preset → fall back to any
    bgm = find_bgm_for_event(tl.event_id, tl.color_grade)
    if bgm:
        print(f"        bgm: {bgm.name}")
    out_path = out_dir / f"{tl.event_id}.mp4"
    out_path.parent.mkdir(parents=True, exist_ok=True)
    archived = _archive_previous(out_path)
    if archived:
        print(f"        archived previous → {archived.name}")
    concat_with_xfade(shot_paths, shot_durations, transitions, bgm, out_path)
    captioned = sum(1 for c in captions_used if c)
    plan_total = sum(s.duration_sec for s in sorted_shots)
    actual_total = sum(shot_durations)
    print(f"        captions: {captioned}/{len(captions_used)} | transitions: {transitions[1:]}")
    print(f"        plan={plan_total:.1f}s actual={actual_total:.1f}s (delta {plan_total - actual_total:+.1f}s)")

    # Write updated timeline reflecting actual rendered durations
    updated_tl = tl.model_copy(update={
        "shots": actual_shots,
        "total_duration_sec": round(actual_total, 2),
    })
    timeline_path = W / "timeline" / f"{tl.event_id}_final.json"
    if timeline_path.exists():
        timeline_path.write_text(updated_tl.model_dump_json(indent=2), encoding="utf-8")

    return out_path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--event", default=None)
    parser.add_argument("--final", action="store_true", help="render _final.json instead of _v1.json")
    args = parser.parse_args()

    out_dir = W / "candidates_v2"
    out_dir.mkdir(parents=True, exist_ok=True)
    suffix = "_final" if args.final else "_v1"

    assets = {a.asset_id: a for a in (
        Asset.model_validate_json(line)
        for line in (W / "assets.jsonl").read_text(encoding="utf-8").splitlines()
        if line.strip()
    )}

    tl_files = sorted((W / "timeline").glob(f"*{suffix}.json"))
    if args.event:
        tl_files = [f for f in tl_files if f.stem.startswith(args.event)]
    if not tl_files:
        raise SystemExit(f"no timeline files matching *{suffix}.json")

    n_done = 0
    for tf in tl_files:
        tl = Timeline.model_validate_json(tf.read_text(encoding="utf-8"))
        print(f"[step7] rendering {tl.event_id} ({len(tl.shots)} shots, {tl.total_duration_sec:.1f}s, '{tl.title}')")
        try:
            final = render_timeline(tl, assets, out_dir)
            if final:
                n_done += 1
                print(f"        -> {final}")
        except Exception as e:
            print(f"  ! failed: {type(e).__name__}: {e}")

    print(f"[step7] rendered {n_done} timelines")


if __name__ == "__main__":
    main()
