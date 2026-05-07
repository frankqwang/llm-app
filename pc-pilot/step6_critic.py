"""Step 6 (v2): Critic Agent — review Timeline v1, propose revisions, re-edit problematic shots.

Loop:
  1. LLM critic reads (DirectorBrief + EventMemory + current Timeline) and emits Critique:
     - issues: free-form list of problems
     - revised_requests: list of {shot_order, new_request} to redo specific slots
  2. For each revised_request, call step5_editor's recall+VLM-curate to pick a NEW asset
     (with the previous pick added to the 'used' set so it won't repeat)
  3. Replace the shot in the timeline; bump iteration counter
  4. Repeat up to MAX_ITER=2 times. Save final to <event>_final.json with critique_history
     so the human can see what changed and why.
"""
from __future__ import annotations

import argparse
import time
from pathlib import Path
from typing import Optional

import numpy as np

import schemas
from agent_log import log_call
from schemas import (
    Asset,
    AudienceBrief,
    Critique,
    CritiqueRevision,
    DirectorBrief,
    Event,
    EventMemory,
    Perception,
    ShotRequest,
    ShotSpec,
    Timeline,
)
from step5_editor import build_shot_spec, recall_candidates, vlm_curate
from vlm_client import call_llm_json

ROOT = Path(__file__).parent
W = ROOT / "workspace"
TIMELINE_DIR = W / "timeline"

MAX_ITER = 2


CRITIC_SYSTEM = """你是 vlog 审片人。我会给你 Audience Brief（最终北极星）、导演大纲、事件记忆、和粗剪 timeline。请从**剪辑师 + 第一观众**的双视角审查：

检查清单（按重要性排序）：
1. **HOOK 是否抓人**：开场前 3 秒（前 1-2 个 shot）是否真的执行了 AudienceBrief.hook_strategy？平淡开场 = 一票否决
2. **PAYOFF 是否兑现**：最后 1-2 个 shot 是否真的让观众感受到 AudienceBrief.emotional_payoff？还是流水账式收尾？
3. **POV 一致**：字幕是否符合 AudienceBrief.pov_voice？混用第一/第三人称、过多陈述句都要标
4. **节奏对比**：是否有 ≤0.6s 闪切 + ≥5s 长镜的对比？还是全 1.5-3s 中庸？
5. **avoid_list 雷区**：是否踩了 AudienceBrief.avoid_list 里的任何一条？
6. **意图匹配**：每个 shot 是否真的满足 director 对该 slot 的意图？rationale 里有没有牵强？
7. **重复**：同一人物/场景占比是否过高？某个 person_id 是否霸屏？
8. **冗余**：有没有"差不多内容的两个 shot"挤在一起？

输出严格 JSON：
{
  "issues": ["问题1", "问题2", ...],   // 自然语言罗列发现的问题，每条 1-2 句
  "revised_requests": [
    {
      "shot_order": 7,
      "new_request": {
        "position": 7,
        "role": "transition",
        "mood_target": "松弛、生活化",
        "visual_requirements": "更明确的描述，例如 '室内光线、走廊或客厅、无人物或主角侧影'",
        "duration_sec": 2.0,
        "person_constraint": null
      }
    }
  ]
}

要点：
- 只对真有问题的 shot 提 revised_request；没问题的 shot 不要写
- new_request 的 visual_requirements 要比原 director 给的更**具体/更有指向性**，让 editor 重新召回时能找到更好的图
- revised_requests 可以为空数组（如果 timeline 已经很好）
- 一轮最多修订 3-4 个 shot，避免大改"""

CRITIC_USER_TEMPLATE = """【Audience Brief — 北极星，最重要】
{audience_json}

【导演大纲】
{brief_json}

【事件记忆】
{memory_json}

【当前 timeline (v{iter})】
{timeline_block}

请审查，输出 JSON。"""


def load_jsonl(p, model):
    return [model.model_validate_json(l) for l in p.read_text(encoding="utf-8").splitlines() if l.strip()]


def timeline_block(tl: Timeline, assets: dict[str, Asset]) -> str:
    rows = []
    for s in sorted(tl.shots, key=lambda x: x.order):
        a = assets.get(s.asset_id)
        type_short = a.media_type if a else "?"
        rows.append(
            f"  shot {s.order:2d} {type_short:10s} {s.duration_sec}s {s.ken_burns:10s} "
            f"asset={s.asset_id}\n     rationale: {s.rationale}"
        )
    return "\n".join(rows)


def critique(
    tl: Timeline,
    brief: DirectorBrief,
    memory: EventMemory,
    audience: AudienceBrief,
    iteration: int,
    assets: dict[str, Asset],
) -> Critique:
    user = CRITIC_USER_TEMPLATE.format(
        audience_json=audience.model_dump_json(indent=2),
        brief_json=brief.model_dump_json(indent=2),
        memory_json=memory.model_dump_json(indent=2),
        iter=iteration,
        timeline_block=timeline_block(tl, assets),
    )
    schema = Critique.model_json_schema()

    t0 = time.time()
    raw = call_llm_json(
        system=CRITIC_SYSTEM,
        user_text=user,
        schema=schema,
        schema_name="Critique",
        temperature=0.4,
        max_tokens=4096,
    )
    dt = time.time() - t0
    log_call(
        step="step6_critic",
        event_id=tl.event_id,
        system=CRITIC_SYSTEM,
        user_text=user,
        response=raw,
        latency_sec=dt,
        extra={"iteration": iteration},
    )
    raw["iteration"] = iteration
    # Tolerate revised_requests being malformed: filter to valid ones
    revs = []
    for r in raw.get("revised_requests", []):
        try:
            revs.append(CritiqueRevision.model_validate(r))
        except Exception as e:
            print(f"  ! critic returned malformed revision, skipping: {e}")
    return Critique(iteration=iteration, issues=raw.get("issues", []), revised_requests=revs)


def apply_revisions(
    tl: Timeline,
    crit: Critique,
    event: Event,
    brief: DirectorBrief,
    assets: dict[str, Asset],
    perceptions: dict[str, Perception],
    clip_embs: Optional[np.ndarray],
    asset_to_clip_idx: dict[str, int],
) -> Timeline:
    """For each revision, re-recall + re-curate that shot. Used set excludes ALL current asset_ids."""
    if not crit.revised_requests:
        return tl

    used = {s.asset_id for s in tl.shots}  # keep ALL current picks (incl. old) in used
    new_shots: dict[int, ShotSpec] = {s.order: s for s in tl.shots}

    for rev in crit.revised_requests:
        order = rev.shot_order
        old = new_shots.get(order)
        if not old:
            print(f"  ! critic referenced shot_order {order} not in timeline, skipping")
            continue
        # Critical: do NOT discard old.asset_id — keeping it in used forces recall to
        # find a DIFFERENT asset. If no different candidate qualifies, we keep original.
        candidates = recall_candidates(
            rev.new_request, event, assets, perceptions, clip_embs, asset_to_clip_idx, used,
            len(brief.shot_blueprint), top_k=12  # widen recall on revisions
        )
        if not candidates:
            print(f"  ! shot {order}: no DIFFERENT candidates after revised recall, keeping original")
            continue
        try:
            asset_id, rationale = vlm_curate(rev.new_request, candidates, brief, event.event_id)
        except Exception as e:
            asset_id, rationale = candidates[0][0], f"vlm curate failed ({e}); fell back to top recall"
        if asset_id == old.asset_id:
            print(f"  ! shot {order}: curate returned same asset, skipping")
            continue
        used.discard(old.asset_id)
        used.add(asset_id)
        # Preserve director's original caption/KB/transition unless critic explicitly overrode them
        merged_request = rev.new_request
        if not merged_request.caption_text and old.caption:
            merged_request = merged_request.model_copy(update={"caption_text": old.caption})
        if not merged_request.ken_burns_hint:
            merged_request = merged_request.model_copy(update={"ken_burns_hint": old.ken_burns})
        if not merged_request.transition_in_hint:
            merged_request = merged_request.model_copy(update={"transition_in_hint": old.transition_in})
        # Compute excluded zones from OTHER shots already using same long-video asset
        existing_segments = []
        for other_order, other_shot in new_shots.items():
            if other_order != order and other_shot.asset_id == asset_id and other_shot.video_trim_start is not None:
                existing_segments.append((
                    other_shot.video_trim_start,
                    other_shot.video_trim_start + other_shot.duration_sec,
                ))
        new_shots[order] = build_shot_spec(
            merged_request, asset_id, f"[critic-revised] {rationale}",
            assets[asset_id], excluded_zones=existing_segments,
        )
        print(f"  [critic] shot {order}: {old.asset_id} → {asset_id}")

    revised = tl.model_copy(update={
        "shots": [new_shots[o] for o in sorted(new_shots)],
        "total_duration_sec": sum(new_shots[o].duration_sec for o in new_shots),
    })
    return revised


def _v1_looks_good_enough(tl: Timeline, brief: DirectorBrief) -> tuple[bool, str]:
    """Heuristic: skip critic round when v1 already passes basic quality bars.

    Saves ~50-70s per event when the editor + director did their job. We still write
    the file as <event>_final.json (just by copying v1) so downstream is unaffected.
    """
    n_shots = len(tl.shots)
    n_planned = len(brief.shot_blueprint)
    captions = sum(1 for s in tl.shots if s.caption)
    duration_ratio = tl.total_duration_sec / max(brief.target_duration_sec, 0.01)

    issues = []
    if n_shots < n_planned * 0.85:
        issues.append(f"shot count {n_shots}/{n_planned} (missed slots)")
    if captions < 3:
        issues.append(f"only {captions} captions")
    if duration_ratio < 0.65:
        issues.append(f"duration {tl.total_duration_sec:.1f}s / target {brief.target_duration_sec:.1f}s = {duration_ratio:.0%} (too short)")
    elif duration_ratio > 1.25:
        issues.append(f"duration {tl.total_duration_sec:.1f}s / target {brief.target_duration_sec:.1f}s = {duration_ratio:.0%} (too long)")
    # Asset uniqueness — same asset reused in adjacent shots is bad
    for i in range(len(tl.shots) - 1):
        if tl.shots[i].asset_id == tl.shots[i + 1].asset_id:
            issues.append(f"shots #{tl.shots[i].order}-{tl.shots[i+1].order} same asset")
            break

    if not issues:
        return True, "all quality bars met"
    return False, "; ".join(issues)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--event", default=None)
    parser.add_argument("--max-iter", type=int, default=MAX_ITER)
    parser.add_argument("--force", action="store_true")
    parser.add_argument("--always", action="store_true", help="don't skip critic even when v1 looks good")
    args = parser.parse_args()

    events = load_jsonl(W / "events.jsonl", Event)
    if args.event:
        events = [e for e in events if e.event_id == args.event]
    assets = {a.asset_id: a for a in load_jsonl(W / "assets.jsonl", Asset)}
    perceptions = {p.asset_id: p for p in load_jsonl(W / "perception.jsonl", Perception)}
    asset_to_clip_idx = {p.asset_id: p.clip_embedding_idx for p in perceptions.values() if p.clip_embedding_idx is not None}
    clip_embs_path = W / "clip_embeddings.npy"
    clip_embs = np.load(clip_embs_path) if clip_embs_path.exists() else None

    for ev in events:
        v1_path = TIMELINE_DIR / f"{ev.event_id}_v1.json"
        final_path = TIMELINE_DIR / f"{ev.event_id}_final.json"
        if final_path.exists() and not args.force:
            print(f"[step6] {ev.event_id}: cached final -> {final_path}")
            continue
        if not v1_path.exists():
            print(f"[step6] {ev.event_id}: no v1 timeline; run step5_editor first")
            continue
        brief_path = W / "director" / f"{ev.event_id}.json"
        memory_path = W / "event_memory" / f"{ev.event_id}.json"
        audience_path = W / "audience" / f"{ev.event_id}.json"
        if not (brief_path.exists() and memory_path.exists() and audience_path.exists()):
            print(f"[step6] {ev.event_id}: missing brief / memory / audience")
            continue

        tl = Timeline.model_validate_json(v1_path.read_text(encoding="utf-8"))
        brief = DirectorBrief.model_validate_json(brief_path.read_text(encoding="utf-8"))
        memory = EventMemory.model_validate_json(memory_path.read_text(encoding="utf-8"))
        audience = AudienceBrief.model_validate_json(audience_path.read_text(encoding="utf-8"))

        # Skip critic if v1 is good enough — saves ~50-70s/event
        if not args.always:
            ok, reason = _v1_looks_good_enough(tl, brief)
            if ok:
                tl = tl.model_copy(update={"final": True})
                final_path.write_text(tl.model_dump_json(indent=2), encoding="utf-8")
                print(f"[step6] {ev.event_id}: ✓ {reason} — skipping critic, copied v1 → final_path")
                continue
            else:
                print(f"[step6] {ev.event_id}: needs critic ({reason})")

        for it in range(1, args.max_iter + 1):
            print(f"[step6] {ev.event_id}: critique iter {it}")
            crit = critique(tl, brief, memory, audience, it, assets)
            tl = tl.model_copy(update={"critique_history": tl.critique_history + [crit]})
            print(f"  [critic] {len(crit.issues)} issues, {len(crit.revised_requests)} revisions:")
            for issue in crit.issues:
                print(f"    - {issue}")
            if not crit.revised_requests:
                print(f"  [critic] no revisions requested; stopping early")
                break
            tl = apply_revisions(tl, crit, ev, brief, assets, perceptions, clip_embs, asset_to_clip_idx)

        tl = tl.model_copy(update={"final": True})
        final_path.write_text(tl.model_dump_json(indent=2), encoding="utf-8")
        print(f"[step6] {ev.event_id}: final timeline ({len(tl.shots)} shots, {tl.total_duration_sec:.1f}s) -> {final_path}")


if __name__ == "__main__":
    main()
