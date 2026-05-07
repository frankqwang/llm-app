"""Step 5 (v2): Editor Agent — recall + per-slot VLM curation → Timeline v1.

For each ShotRequest in the DirectorBrief:
  1. Recall (rule-based, ms): quality + person + scene + time-window + CLIP text-image sim → top 8
  2. VLM curate: feed the 8 candidate thumbnails + ShotRequest description, ask VLM to pick 1
  3. Assemble ShotSpec ready for FFmpeg render
"""
from __future__ import annotations

import argparse
import json
import time
from datetime import datetime
from pathlib import Path
from typing import Any, Optional

import numpy as np
from dateutil import parser as dateparser

import schemas
from agent_log import log_call
from perception import clip_emb
from schemas import (
    Asset,
    DirectorBrief,
    Event,
    EventMemory,
    Perception,
    ShotRequest,
    ShotSpec,
    Timeline,
)
from vlm_client import call_vlm_json

ROOT = Path(__file__).parent
W = ROOT / "workspace"
TIMELINE_DIR = W / "timeline"

DEFAULT_RECALL_TOP_K = 8


def _normalize_person_id(raw) -> Optional[str]:
    """Normalize director's person_constraint to bare cluster ID like 'A'/'B'.

    Director may write "person_A", "A", "主角A", "Person A", "主角 B", etc. —
    extract the trailing letter token. Returns None if blank or no person token.
    """
    if not raw or not isinstance(raw, str):
        return None
    s = raw.strip()
    if s.lower() in ("none", "null", "无人物", "", "无", "no_person"):
        return None
    import re as _re

    # Look for the LAST single-letter (or short) token in A-Z range
    m = _re.search(r"[A-Za-z]+\s*$", s)
    if m:
        token = m.group(0).strip().upper()
        # Strip common prefixes  PERSON / 主角
        if token.startswith("PERSON"):
            token = token[len("PERSON"):].lstrip("_- ").upper()
        return token if token else None
    return None


VLM_CURATE_SYSTEM = """你是剪辑师的视觉助手。我会给你：
- 第 1 张图（如果有）= 前一个 shot 实际选的画面（让你判断衔接关系）
- 后 N 张图 = 当前 slot 的 N 个候选（按编号 1..N）

请挑出最符合需求且**与前一个 shot 视觉上能承接**的候选，输出严格 JSON：
{
  "chosen_index": 1-N 之间的整数,
  "rationale": "<=80 字解释为什么选它，以及它和前一个 shot 的视觉关系（color/motion/composition）"
}

判断要素（按重要性）：
1. role 角色 + visual 描述 匹配 slot 需求
2. **与前一个 shot 的视觉关系**：
   - color match（色调延续或刻意反差）
   - motion match（动作方向 / 视线方向延续）
   - composition match（同景别延续 or 反差跳跃）
   - 避免和前一个 shot 视觉上"完全一样"的（连拍冗余）
   - 也避免和前一个 shot "完全无关"的（突兀）
3. 表情自然、构图清晰、光线良好
4. 避免选模糊/废片"""

VLM_CURATE_USER = """【slot 需求】
position: {position}
role: {role}
mood: {mood}
visual: {visual}
person_constraint: {person}
duration_sec: {duration}

【上下文（导演意图）】
title: {title}
tone: {tone}
narrative_arc: {arc}

{prev_shot_hint}请从候选里选 1 张。"""


def load_jsonl(p, model):
    return [model.model_validate_json(l) for l in p.read_text(encoding="utf-8").splitlines() if l.strip()]


def parse_dt(s: Optional[str]) -> Optional[datetime]:
    if not s:
        return None
    try:
        dt = dateparser.parse(s)
        return dt.replace(tzinfo=None) if dt.tzinfo else dt
    except (TypeError, ValueError):
        return None


def representative_image(asset: Asset, perception: Perception) -> Optional[Path]:
    if asset.image_path:
        p = ROOT / asset.image_path
        if p.exists():
            return p
    if perception.shots:
        p = ROOT / perception.shots[0].keyframe_path
        if p.exists():
            return p
    return None


LONG_VIDEO_REUSE_THRESHOLD = 12.0  # videos ≥12s can be reused across multiple shots
# (different trim_start segments). Each reuse counts as one slot of usage.


def recall_candidates(
    request: ShotRequest,
    event: Event,
    assets: dict[str, Asset],
    perceptions: dict[str, Perception],
    clip_embs: Optional[np.ndarray],
    asset_to_clip_idx: dict[str, int],
    used_aids: set[str],
    n_blueprint: int,
    top_k: int = DEFAULT_RECALL_TOP_K,
) -> list[tuple[str, Path]]:
    """Rank event assets by relevance to the ShotRequest, return top_k (asset_id, img_path).

    Long videos (≥12s) are allowed to be picked multiple times — each pick will get a
    different trim_start via frame_scorer, effectively treating the long clip as a
    multi-shot source (e.g. a 25s zoo video can supply both 'establishing' and 'action'
    shots). We track segment usage in `used_aids` keyed by raw asset_id; long-video
    reuse is gated by frame_scorer picking a non-overlapping window.
    """
    # Time-window: position p out of N → expected location p/N along event time axis
    times: dict[str, datetime] = {}
    for aid in event.asset_ids:
        a = assets.get(aid)
        if a:
            dt = parse_dt(a.exif_datetime)
            if dt:
                times[aid] = dt
    t_sorted = sorted(times.values()) if times else []
    if t_sorted:
        t_min, t_max = t_sorted[0], t_sorted[-1]
        span = (t_max - t_min).total_seconds() or 1.0
        target_frac = (request.position - 0.5) / max(1, n_blueprint)
        target_t = t_min.timestamp() + target_frac * span

    # Encode visual_requirements with CLIP text encoder for similarity ranking
    text_emb = None
    if clip_embs is not None and request.visual_requirements:
        try:
            text_emb = clip_emb.embed_text(request.visual_requirements)
        except Exception as e:
            print(f"  ! clip text embed failed for slot {request.position}: {e}")

    scored: list[tuple[float, str, Path]] = []
    LONG_SHOT_THRESHOLD = 3.5
    MAX_STRETCH = 1.5
    for aid in event.asset_ids:
        # used_aids exclusion — but ALLOW long videos to be reused (different trim_start)
        if aid in used_aids:
            a_check = assets.get(aid)
            if not a_check or (a_check.duration_sec or 0) < LONG_VIDEO_REUSE_THRESHOLD:
                continue
            # else: long video, allow re-recall (frame_scorer will pick different segment)
        p = perceptions.get(aid)
        a = assets.get(aid)
        if not p or not a or p.is_junk:
            continue
        # HARD-EXCLUDE clips that even at 1.5x stretch can't cover most of the slot.
        # Allowing these in resulted in shots being silently capped to e.g. 1.7s when
        # director asked for 4s, breaking the planned rhythm. Better to leave the slot
        # to a longer clip or shrink the slot at planning time.
        if a.media_type in ("video", "live_photo") and a.duration_sec:
            renderable = a.duration_sec * MAX_STRETCH
            if renderable < request.duration_sec * 0.8:
                continue  # this clip can never fill the slot — exclude
        # Static images can be any length, no exclusion needed.

        # person constraint (soft for normal IDs, hard for "无人物")
        person_ids_here = {fc.person_id for fc in p.faces if fc.person_id}
        if request.person_constraint == "无人物":
            if person_ids_here:
                continue  # hard exclude when no-people requested
        # NORMALIZE: director may write "person_A", "A", "主角A", "Person A" — strip
        # to bare token and compare case-insensitive against the cluster IDs (A/B/C).
        constraint_norm = _normalize_person_id(request.person_constraint)
        if constraint_norm and constraint_norm not in {pid.upper() for pid in person_ids_here}:
            pass  # don't hard-drop; just no person bonus added below

        rep = representative_image(a, p)
        if not rep:
            continue

        score = 0.0

        # CLIP similarity
        if text_emb is not None and a.asset_id in asset_to_clip_idx:
            idx = asset_to_clip_idx[a.asset_id]
            if 0 <= idx < clip_embs.shape[0]:
                score += float(np.dot(text_emb, clip_embs[idx])) * 2.0  # weight: dominant signal

        # Time proximity (only if we have a target time and asset has time)
        if t_sorted and aid in times:
            asset_t = times[aid].timestamp()
            time_diff = abs(asset_t - target_t) / span
            score += max(0.0, 1.0 - time_diff) * 0.8

        # Quality / preference bonuses
        score += min(p.blur_score, 500) / 500 * 0.3
        if request.role == "portrait" and p.faces:
            score += 0.3
        if a.media_type in ("video", "live_photo") and request.role in ("action", "climax"):
            score += 0.3
        # person bonus (normalized comparison)
        if constraint_norm and constraint_norm in {pid.upper() for pid in person_ids_here}:
            score += 0.6
        # Duration matching: prefer clips that can SUSTAIN the requested duration.
        # We no longer hard-exclude (would leave slots unfilled), but score heavily
        # toward clips with the right length so longer slots don't end up on 1.4s
        # Live Photos.
        if a.duration_sec:
            shortfall = request.duration_sec - a.duration_sec
            if shortfall > 1.5:
                score -= 0.6
            elif shortfall > 0.5:
                score -= 0.3
            elif shortfall <= 0:
                score += 0.2  # bonus when clip is at least as long as requested
        # Static images: penalize for non-closing slots (we want motion continuity)
        if a.media_type == "image" and request.role != "closing":
            score -= 0.3

        scored.append((score, aid, rep))

    scored.sort(reverse=True)
    return [(aid, rep) for _, aid, rep in scored[:top_k]]


def vlm_curate(
    request: ShotRequest,
    candidates: list[tuple[str, Path]],
    brief: DirectorBrief,
    event_id: str,
    previous_shot_path: Path | None = None,
) -> tuple[str, str]:
    """Show VLM the candidate thumbnails (with previous shot for continuity) and pick one."""
    if len(candidates) == 1:
        return candidates[0][0], "only candidate after recall"

    # If previous shot provided, prepend it to images list. VLM should treat it as
    # context (not a candidate) per the system prompt.
    images = []
    prev_hint = ""
    if previous_shot_path and previous_shot_path.exists():
        images.append(previous_shot_path)
        prev_hint = "【前一个 shot（仅作衔接参考，不在候选中）已附在第 1 张图】\n\n"
    images.extend(p for _, p in candidates)

    user = VLM_CURATE_USER.format(
        position=request.position,
        role=request.role,
        mood=request.mood_target,
        visual=request.visual_requirements,
        person=request.person_constraint or "无约束",
        duration=request.duration_sec,
        title=brief.title,
        tone=brief.tone,
        arc=" → ".join(brief.narrative_arc),
        n=len(candidates),
        prev_shot_hint=prev_hint,
    )

    t0 = time.time()
    raw = call_vlm_json(
        system=VLM_CURATE_SYSTEM,
        user_text=user,
        images=images,
        schema=None,
        temperature=0.3,
        max_tokens=512,
    )
    dt = time.time() - t0
    log_call(
        step="step5_curate",
        event_id=event_id,
        system=VLM_CURATE_SYSTEM,
        user_text=user,
        images=[str(p.relative_to(ROOT)) for p in images],
        response=raw,
        latency_sec=dt,
        extra={"slot": request.position, "candidate_aids": [aid for aid, _ in candidates]},
    )

    chosen_idx = raw.get("chosen_index", 1)
    try:
        chosen_idx = int(chosen_idx)
    except (TypeError, ValueError):
        chosen_idx = 1
    chosen_idx = max(1, min(len(candidates), chosen_idx))
    return candidates[chosen_idx - 1][0], raw.get("rationale", "")


def _auto_ken_burns(role: str, duration: float, media_type: str) -> str:
    """Editor's fallback Ken Burns picker when director didn't hint."""
    if media_type in ("video", "live_photo"):
        return "static"  # videos already have native motion
    if duration < 1.6:
        return "static"  # too short for visible KB
    by_role = {
        "opening": "zoom_in",
        "establishing": "pan_right",
        "portrait": "zoom_in",
        "action": "static",
        "climax": "zoom_in",
        "transition": "pan_left",
        "closing": "zoom_out",
    }
    return by_role.get(role, "zoom_in")


def _auto_transition(role: str, position: int) -> str:
    """Editor's fallback transition picker when director didn't hint."""
    if position == 1:
        return "fade"
    by_role = {
        "opening": "fade",
        "establishing": "crossfade",
        "portrait": "crossfade",
        "action": "cut",
        "climax": "fade",
        "transition": "fadeblack",
        "closing": "fade",
    }
    return by_role.get(role, "crossfade")


_PUNCH_ROLES = {"opening", "climax", "action"}


def build_shot_spec(
    request: ShotRequest,
    asset_id: str,
    rationale: str,
    asset: Asset,
    excluded_zones: list[tuple[float, float]] | None = None,
) -> ShotSpec:
    """Construct concrete ShotSpec with two safety nets:
    1. Live Photo videos are typically 2.5-3.5s. If director wants a longer shot,
       fall back to rendering as still image (with Ken Burns) so we don't FREEZE on
       the last video frame for seconds.
    2. Real videos shorter than requested duration: cap shot duration to clip length.
    """
    actual_media = asset.media_type
    actual_dur = request.duration_sec
    notes = []

    # PRINCIPLE: motion continuity. Slow-mo up to 1.5x for 30fps sources is the
    # comfort ceiling; higher feels choppy. (Renderer extends to 2.5x ONLY if source
    # is ≥48fps native, which is rare for phone Live Photos.) Cap actual_dur here so
    # editor and render agree on the constraint.
    MAX_STRETCH = 1.5
    if asset.media_type in ("video", "live_photo") and asset.duration_sec:
        clip_len = asset.duration_sec
        max_renderable = clip_len * MAX_STRETCH
        if actual_dur > max_renderable:
            new_dur = round(max_renderable, 2)
            notes.append(f"{asset.media_type} capped {actual_dur:.1f}s→{new_dur:.1f}s (clip {clip_len:.1f}s × {MAX_STRETCH}x)")
            actual_dur = new_dur

    ken_burns = request.ken_burns_hint or _auto_ken_burns(request.role, actual_dur, actual_media)
    transition_in = request.transition_in_hint or _auto_transition(request.role, request.position)

    trim_start = None
    if actual_media in ("video", "live_photo") and asset.video_path:
        if request.role in _PUNCH_ROLES and asset.duration_sec and asset.duration_sec > actual_dur + 0.5:
            try:
                from perception.frame_scorer import pick_best_window

                video_abs = (Path(__file__).parent / asset.video_path).resolve()
                ex = excluded_zones or []
                print(f"  → micro-edit slot {request.position} ({request.role}, {asset.duration_sec:.1f}s clip, {len(ex)} excluded zones): scanning frames...")
                trim_start = pick_best_window(video_abs, actual_dur, excluded_zones=ex)
                print(f"    chose trim_start={trim_start:.3f}s")
                if trim_start > 0:
                    notes.append(f"micro-edit trim={trim_start:.2f}s")
            except Exception as e:
                print(f"  ! frame_scorer failed for {asset_id}: {type(e).__name__}: {e}")
                trim_start = 0.0
        else:
            trim_start = 0.0

    if notes:
        rationale = f"{rationale} [{' | '.join(notes)}]"
        print(f"    slot {request.position}: {' | '.join(notes)}")

    return ShotSpec(
        asset_id=asset_id,
        media_type=actual_media,
        order=request.position,
        duration_sec=actual_dur,
        video_trim_start=trim_start,
        video_trim_end=None,
        caption=request.caption_text or "",
        ken_burns=ken_burns,
        transition_in=transition_in,
        rationale=rationale,
    )


_PER_SHOT_HARD_CAP = 8.0  # no single shot stays on screen longer than this (image OR video)
_LONG_SHOT_ROLES = {"establishing", "climax", "closing"}  # only these can use the cap, others 5s


def _normalize_durations(shots: list[ShotSpec], target_total: float) -> list[ShotSpec]:
    """Bring total duration in line, but ONLY scale DOWN — never up.

    Scaling up uniformly was producing 14s static-image freezes when live_photo shots
    were capped at clip length and the rest of the duration "budget" was shoved onto
    image shots. Instead:
      - if sum > target * 1.15: scale ALL down proportionally
      - if sum < target: accept the shorter video (better than a freeze)
      - regardless: clamp each individual shot to a per-role hard cap so no single shot
        exceeds 8s (5s if it's not a designated long-shot role)
    """
    if not shots:
        return shots
    total = sum(s.duration_sec for s in shots)
    if total <= 0:
        return shots
    out: list[ShotSpec] = []

    # Step 1: per-shot cap by media type.
    # - Static image: 4s default (5s only for last/closing shot — needs breath room)
    # - Video / live_photo: motion sustains attention; cap 5s normal, 7s for designated
    #   long-shot (director duration ≥5 — already a "long shot" intent)
    last_order = max(s.order for s in shots)
    for s in shots:
        is_last = s.order == last_order
        if s.media_type == "image":
            max_dur = 5.0 if is_last else 4.0
        else:
            max_dur = 7.0 if s.duration_sec >= 5.0 else 5.0
        new_dur = min(s.duration_sec, max_dur)
        if new_dur != s.duration_sec:
            print(f"  → clamping shot {s.order} ({s.media_type}) {s.duration_sec:.1f}s → {new_dur:.1f}s")
        out.append(s.model_copy(update={"duration_sec": round(new_dur, 2)}))

    # Step 2: scale down only (never up)
    new_total = sum(s.duration_sec for s in out)
    if new_total > target_total * 1.15:
        ratio = target_total / new_total
        print(f"  → scaling DOWN: {new_total:.1f}s → {target_total:.1f}s (ratio {ratio:.2f})")
        out = [s.model_copy(update={"duration_sec": round(max(0.3, s.duration_sec * ratio), 2)}) for s in out]
    elif new_total < target_total * 0.85:
        print(f"  → total {new_total:.1f}s is short of target {target_total:.1f}s; accepting (no upscale to avoid freeze)")
    return out


def edit_event(
    event: Event,
    brief: DirectorBrief,
    assets: dict[str, Asset],
    perceptions: dict[str, Perception],
    clip_embs: Optional[np.ndarray],
    asset_to_clip_idx: dict[str, int],
) -> Timeline:
    used: set[str] = set()
    # Track which time-segments of long videos are already used so we don't re-pick
    # the same window when the long video is selected for multiple shots.
    long_video_used_segments: dict[str, list[tuple[float, float]]] = {}
    shots: list[ShotSpec] = []
    n_blueprint = len(brief.shot_blueprint)
    prev_path: Optional[Path] = None

    for req in sorted(brief.shot_blueprint, key=lambda r: r.position):
        candidates = recall_candidates(
            req, event, assets, perceptions, clip_embs, asset_to_clip_idx, used, n_blueprint
        )
        if not candidates:
            print(f"  ! slot {req.position} ({req.role}): no candidates after recall, skipping")
            continue
        try:
            asset_id, rationale = vlm_curate(req, candidates, brief, event.event_id, previous_shot_path=prev_path)
        except Exception as e:
            asset_id, rationale = candidates[0][0], f"vlm curate failed ({e}); fell back to top recall"
        used.add(asset_id)
        excluded = long_video_used_segments.get(asset_id, [])
        spec = build_shot_spec(req, asset_id, rationale, assets[asset_id], excluded_zones=excluded)
        shots.append(spec)
        # Record the time window we just consumed (for any next slot picking same long video)
        if spec.video_trim_start is not None and spec.media_type in ("video", "live_photo"):
            seg = (spec.video_trim_start, spec.video_trim_start + spec.duration_sec)
            long_video_used_segments.setdefault(asset_id, []).append(seg)
        chosen_asset = assets[asset_id]
        chosen_perc = perceptions.get(asset_id)
        prev_path = representative_image(chosen_asset, chosen_perc) if chosen_perc else None

    shots = _normalize_durations(shots, brief.target_duration_sec)
    total = sum(s.duration_sec for s in shots)
    return Timeline(
        event_id=event.event_id,
        director_brief_path=str((W / "director" / f"{event.event_id}.json").relative_to(ROOT)),
        title=brief.title,
        tagline=brief.tagline,
        tone=brief.tone,
        color_grade=brief.color_grade,
        total_duration_sec=total,
        shots=shots,
    )


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--event", default=None)
    parser.add_argument("--force", action="store_true")
    args = parser.parse_args()

    TIMELINE_DIR.mkdir(parents=True, exist_ok=True)
    events = load_jsonl(W / "events.jsonl", Event)
    if args.event:
        events = [e for e in events if e.event_id == args.event]

    assets = {a.asset_id: a for a in load_jsonl(W / "assets.jsonl", Asset)}
    perceptions = {p.asset_id: p for p in load_jsonl(W / "perception.jsonl", Perception)}
    asset_to_clip_idx = {p.asset_id: p.clip_embedding_idx for p in perceptions.values() if p.clip_embedding_idx is not None}
    clip_embs_path = W / "clip_embeddings.npy"
    clip_embs = np.load(clip_embs_path) if clip_embs_path.exists() else None
    if clip_embs is None:
        print("[step5] WARN: clip_embeddings.npy missing; recall will skip CLIP similarity")

    for ev in events:
        out = TIMELINE_DIR / f"{ev.event_id}_v1.json"
        if out.exists() and not args.force:
            print(f"[step5] {ev.event_id}: cached -> {out}")
            continue
        brief_path = W / "director" / f"{ev.event_id}.json"
        if not brief_path.exists():
            print(f"[step5] {ev.event_id}: no director brief; run step4_director first")
            continue
        brief = DirectorBrief.model_validate_json(brief_path.read_text(encoding="utf-8"))

        print(f"[step5] {ev.event_id}: editing {len(brief.shot_blueprint)} slots ('{brief.title}')")
        try:
            tl = edit_event(ev, brief, assets, perceptions, clip_embs, asset_to_clip_idx)
            out.write_text(tl.model_dump_json(indent=2), encoding="utf-8")
            print(f"[step5] {ev.event_id}: timeline v1 with {len(tl.shots)} shots ({tl.total_duration_sec:.1f}s) -> {out}")
        except Exception as e:
            print(f"[step5] {ev.event_id} FAILED: {type(e).__name__}: {e}")


if __name__ == "__main__":
    main()
