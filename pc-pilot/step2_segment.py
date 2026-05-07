"""Step 2: segment all assets into events using EXIF time + GPS proximity.

Algorithm:
  1. Sort assets by exif_datetime ascending. Assets without a date are dropped from
     event-based output (they go to a fallback bucket "evt_undated").
  2. Walk in order, start a new event when EITHER:
       - time gap > GAP_HOURS since previous asset, OR
       - GPS distance > GPS_KM_THRESHOLD (when both endpoints have GPS)
  3. Merge events with < MIN_EVENT_SIZE assets into the previous event.
  4. Aggregate per-event metadata (person_ids, counts, time span, etc.).

Output: workspace/events.jsonl, one Event per row.
"""
from __future__ import annotations

import argparse
import math
from datetime import datetime
from pathlib import Path

from dateutil import parser as dateparser

from schemas import Asset, Event, Perception

ROOT = Path(__file__).parent
WORKSPACE = ROOT / "workspace"

GAP_HOURS = 8.0   # bumped from 3 — same-day shoots (e.g. zoo trip + dinner) shouldn't split
GPS_KM_THRESHOLD = 50.0
MIN_EVENT_SIZE = 4


def haversine_km(p1: tuple[float, float], p2: tuple[float, float]) -> float:
    lat1, lon1 = math.radians(p1[0]), math.radians(p1[1])
    lat2, lon2 = math.radians(p2[0]), math.radians(p2[1])
    dlat = lat2 - lat1
    dlon = lon2 - lon1
    a = math.sin(dlat / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin(dlon / 2) ** 2
    return 2 * 6371.0 * math.asin(math.sqrt(a))


def parse_dt(s: str | None) -> datetime | None:
    if not s:
        return None
    try:
        dt = dateparser.parse(s)
        # Normalize to naive: image EXIF is naive, video container metadata is often
        # tz-aware (UTC). Mixing them in subtractions raises TypeError.
        if dt.tzinfo is not None:
            dt = dt.replace(tzinfo=None)
        return dt
    except (ValueError, TypeError):
        return None


def segment(assets: list[Asset]) -> list[list[Asset]]:
    """Split assets (already sorted by datetime) into raw event groups."""
    if not assets:
        return []

    groups: list[list[Asset]] = [[assets[0]]]
    for cur in assets[1:]:
        prev = groups[-1][-1]

        gap_hours = float("inf")
        prev_dt, cur_dt = parse_dt(prev.exif_datetime), parse_dt(cur.exif_datetime)
        if prev_dt and cur_dt:
            gap_hours = (cur_dt - prev_dt).total_seconds() / 3600

        gps_km = 0.0
        if prev.exif_gps and cur.exif_gps:
            gps_km = haversine_km(prev.exif_gps, cur.exif_gps)

        if gap_hours > GAP_HOURS or gps_km > GPS_KM_THRESHOLD:
            groups.append([cur])
        else:
            groups[-1].append(cur)

    return groups


MAX_EVENT_SPAN_HOURS = 72.0  # don't merge events spanning more than this


def _group_span_hours(group: list[Asset]) -> float:
    """Return time span (hours) of a group, considering EXIF datetimes."""
    dts = [parse_dt(a.exif_datetime) for a in group]
    dts = [d for d in dts if d]
    if len(dts) < 2:
        return 0.0
    return (max(dts) - min(dts)).total_seconds() / 3600


def merge_small(groups: list[list[Asset]]) -> list[list[Asset]]:
    """Merge events with fewer than MIN_EVENT_SIZE assets into the previous event,
    BUT only when the merged span doesn't blow past MAX_EVENT_SPAN_HOURS. Otherwise
    keep the small group as its own event (better than gluing 2025-06 onto 2026-04)."""
    if not groups:
        return groups
    out: list[list[Asset]] = [groups[0]]
    for g in groups[1:]:
        if len(g) < MIN_EVENT_SIZE and out:
            merged_span = _group_span_hours(out[-1] + g)
            if merged_span <= MAX_EVENT_SPAN_HOURS:
                out[-1].extend(g)
                continue
        out.append(g)
    if len(out) >= 2 and len(out[0]) < MIN_EVENT_SIZE:
        merged_span = _group_span_hours(out[0] + out[1])
        if merged_span <= MAX_EVENT_SPAN_HOURS:
            out[1] = out[0] + out[1]
            out = out[1:]
    return out


def build_event(idx: int, group: list[Asset], perceptions: dict[str, Perception]) -> Event:
    assert group
    start_dt = group[0].exif_datetime
    end_dt = group[-1].exif_datetime
    s, e = parse_dt(start_dt), parse_dt(end_dt)
    duration_h = (e - s).total_seconds() / 3600 if (s and e) else 0.0

    n_img = sum(1 for a in group if a.media_type == "image")
    n_vid = sum(1 for a in group if a.media_type in ("video", "live_photo"))

    persons: set[str] = set()
    for a in group:
        p = perceptions.get(a.asset_id)
        if p:
            for fc in p.faces:
                if fc.person_id:
                    persons.add(fc.person_id)

    hint_parts = []
    if start_dt and end_dt:
        if start_dt[:10] == end_dt[:10]:
            hint_parts.append(start_dt[:10])
        else:
            hint_parts.append(f"{start_dt[:10]} → {end_dt[:10]}")
    hint_parts.append(f"{len(group)} 素材 (img={n_img}, vid={n_vid})")
    if persons:
        hint_parts.append(f"{len(persons)} 人物")
    if duration_h:
        hint_parts.append(f"{duration_h:.1f}h")

    return Event(
        event_id=f"evt_{idx:03d}",
        asset_ids=[a.asset_id for a in group],
        start_datetime=start_dt,
        end_datetime=end_dt,
        duration_hours=duration_h,
        location_label=None,
        person_ids_present=sorted(persons),
        asset_count=len(group),
        image_count=n_img,
        video_count=n_vid,
        summary_hint=" | ".join(hint_parts),
    )


def main() -> None:
    global GAP_HOURS, GPS_KM_THRESHOLD, MIN_EVENT_SIZE

    parser = argparse.ArgumentParser()
    parser.add_argument("--gap-hours", type=float, default=GAP_HOURS)
    parser.add_argument("--gps-km", type=float, default=GPS_KM_THRESHOLD)
    parser.add_argument("--min-size", type=int, default=MIN_EVENT_SIZE)
    args = parser.parse_args()

    GAP_HOURS = args.gap_hours
    GPS_KM_THRESHOLD = args.gps_km
    MIN_EVENT_SIZE = args.min_size

    assets_path = WORKSPACE / "assets.jsonl"
    if not assets_path.exists():
        raise SystemExit("workspace/assets.jsonl missing. Run step0_ingest.py first.")
    perception_path = WORKSPACE / "perception.jsonl"
    if not perception_path.exists():
        raise SystemExit("workspace/perception.jsonl missing. Run step1_perceive.py first.")

    assets: list[Asset] = []
    with assets_path.open("r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                assets.append(Asset.model_validate_json(line))

    perceptions: dict[str, Perception] = {}
    with perception_path.open("r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                p = Perception.model_validate_json(line)
                perceptions[p.asset_id] = p

    dated = [a for a in assets if a.exif_datetime]
    undated = [a for a in assets if not a.exif_datetime]
    dated.sort(key=lambda a: a.exif_datetime or "")

    groups = merge_small(segment(dated))

    events = [build_event(i + 1, g, perceptions) for i, g in enumerate(groups)]

    if undated:
        # Fallback bucket so they're not lost
        events.append(
            build_event(len(events) + 1, undated, perceptions)
        )
        events[-1] = events[-1].model_copy(update={"event_id": "evt_undated", "summary_hint": f"{len(undated)} 无时间元数据"})

    out = WORKSPACE / "events.jsonl"
    with out.open("w", encoding="utf-8") as f:
        for e in events:
            f.write(e.model_dump_json() + "\n")

    print(f"[step2] {len(events)} events -> {out}")
    for e in events:
        print(f"        {e.event_id}: {e.summary_hint}")


if __name__ == "__main__":
    main()
