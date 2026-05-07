"""Step 3 (v2): event-level VLM browse via contact sheet → EventMemory.

Replaces M0's step3_label.py — we no longer ask the VLM to write a per-asset report.
Instead we hand it ONE numbered contact sheet per event and ask for a holistic story
read. Outputs cached to workspace/event_memory/<event_id>.json.
"""
from __future__ import annotations

import argparse
import json
import time
from pathlib import Path

import schemas  # forces UTF-8 stdio
from agent_log import log_call
from montage import build_contact_sheet
from schemas import Asset, Event, EventMemory, KeyMoment, Perception, SubGroup
from vlm_client import call_vlm_json

ROOT = Path(__file__).parent
W = ROOT / "workspace"
EVENT_MEM_DIR = W / "event_memory"
SHEETS_DIR = W / "agent_logs"  # contact sheets live here for inspection


SYSTEM = """你是剪辑导演的助手。我会给你一张缩略图网格，按时间从左上到右下排列。这是一段事件的全部素材。
请用整体视角浏览，输出严格 JSON：
{
  "storyline_summary": "300字内中文，讲清楚发生了什么、人物、地点、情绪",
  "key_moments": [{"image_index": 1, "why": "..."}],   // 3-5 个最值得记住的画面
  "emotional_arc": "情绪曲线描述，例如 '平静→兴奋→温情→宁静'",
  "characters_observed": ["主角A的特征描述", "主角B的特征描述"],
  "visual_style_signals": "光线/构图/场景多样性观察 → 适合什么剪法",
  "notable_subgroups": [{"indices": [3,4,5], "label": "连拍若干张父子户外自拍"}]
}

不要逐张分析，要整体把握。重点是抓住"故事弧线"和"记忆点"。image_index 必须 1-based 引用网格里的序号。"""

USER = "请浏览这批素材，输出事件级理解。"


def load_jsonl(p, model):
    return [model.model_validate_json(l) for l in p.read_text(encoding="utf-8").splitlines() if l.strip()]


def representative_image(asset: Asset, perception: Perception) -> Path | None:
    if asset.image_path:
        p = ROOT / asset.image_path
        if p.exists():
            return p
    if perception.shots:
        p = ROOT / perception.shots[0].keyframe_path
        if p.exists():
            return p
    return None


def browse_event(event: Event, assets: dict[str, Asset], perceptions: dict[str, Perception]) -> EventMemory:
    """Build contact sheet and ask VLM for an EventMemory."""
    chosen: list[tuple[str, Path]] = []
    for aid in event.asset_ids:
        p = perceptions.get(aid)
        a = assets.get(aid)
        if not p or not a or p.is_junk:
            continue
        rep = representative_image(a, p)
        if rep:
            chosen.append((aid, rep))

    if not chosen:
        raise RuntimeError(f"event {event.event_id} has no usable assets")

    asset_ids = [aid for aid, _ in chosen]
    img_paths = [p for _, p in chosen]

    sheet_path, cols, rows = build_contact_sheet(
        img_paths,
        SHEETS_DIR / f"{event.event_id}_contact_sheet.jpg",
        cell_w=360,
        cell_h=640,
    )
    print(f"[step3] {event.event_id}: contact sheet {cols}x{rows} ({len(asset_ids)} cells) -> {sheet_path}")

    t0 = time.time()
    raw = call_vlm_json(
        system=SYSTEM,
        user_text=USER,
        images=[sheet_path],
        schema=None,  # don't force schema yet - lenient parsing tolerates VLM idiosyncrasies
        temperature=0.4,
        max_tokens=4096,
    )
    dt = time.time() - t0
    log_call(
        step="step3_montage",
        event_id=event.event_id,
        system=SYSTEM,
        user_text=USER,
        images=[str(sheet_path.relative_to(ROOT))],
        response=raw,
        latency_sec=dt,
        extra={"n_assets": len(asset_ids), "grid": f"{cols}x{rows}"},
    )

    # Resolve image_index -> asset_id in key_moments and notable_subgroups
    key_moments = []
    for km in raw.get("key_moments", []):
        try:
            idx = int(km["image_index"])
            if 1 <= idx <= len(asset_ids):
                key_moments.append(KeyMoment(image_index=idx, asset_id=asset_ids[idx - 1], why=km.get("why", "")))
        except (KeyError, TypeError, ValueError):
            continue

    subgroups = []
    for sg in raw.get("notable_subgroups", []):
        try:
            indices = [int(i) for i in sg.get("indices", []) if 1 <= int(i) <= len(asset_ids)]
            if indices:
                subgroups.append(SubGroup(indices=indices, label=sg.get("label", "")))
        except (TypeError, ValueError):
            continue

    return EventMemory(
        event_id=event.event_id,
        storyline_summary=raw.get("storyline_summary", ""),
        key_moments=key_moments,
        emotional_arc=raw.get("emotional_arc", ""),
        characters_observed=raw.get("characters_observed", []),
        visual_style_signals=raw.get("visual_style_signals", ""),
        notable_subgroups=subgroups,
    )


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--event", default=None, help="only one event by ID")
    parser.add_argument("--force", action="store_true", help="ignore cache")
    args = parser.parse_args()

    EVENT_MEM_DIR.mkdir(parents=True, exist_ok=True)

    assets = {a.asset_id: a for a in load_jsonl(W / "assets.jsonl", Asset)}
    perceptions = {p.asset_id: p for p in load_jsonl(W / "perception.jsonl", Perception)}
    events = load_jsonl(W / "events.jsonl", Event)
    if args.event:
        events = [e for e in events if e.event_id == args.event]

    print(f"[step3] browsing {len(events)} events")
    for ev in events:
        out = EVENT_MEM_DIR / f"{ev.event_id}.json"
        if out.exists() and not args.force:
            print(f"[step3] {ev.event_id}: cached -> {out}")
            continue
        try:
            mem = browse_event(ev, assets, perceptions)
            out.write_text(mem.model_dump_json(indent=2), encoding="utf-8")
            print(f"[step3] {ev.event_id}: {len(mem.key_moments)} key_moments, "
                  f"{len(mem.notable_subgroups)} subgroups, {len(mem.characters_observed)} characters -> {out}")
        except Exception as e:
            print(f"[step3] {ev.event_id} FAILED: {type(e).__name__}: {e}")


if __name__ == "__main__":
    main()
