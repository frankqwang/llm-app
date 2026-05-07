"""Step 3b: Audience Brief — write the 'why this vlog matters' before any shot planning.

Inspired by how good human editors first ask 'what feeling do I want the viewer to walk
away with?' before they touch the timeline. The DirectorBrief in step4 will use this
as a north star, instead of jumping straight into shot blueprints.

Inputs: EventMemory + Event metadata
Output: workspace/audience/<event_id>.json (AudienceBrief)
"""
from __future__ import annotations

import argparse
import time
from pathlib import Path

import schemas
from agent_log import log_call
from schemas import AudienceBrief, Event, EventMemory
from vlm_client import call_llm_json

ROOT = Path(__file__).parent
W = ROOT / "workspace"
AUDIENCE_DIR = W / "audience"


SYSTEM = """你是一名 vlog 创作策划。我会给你一段事件的"观后感"和元数据。
**先不要谈剪辑技巧、不要选风格、不要列分镜**。先回答一个根本问题：

> 这个 vlog 想让观众感受到什么？

然后由这个出发，定义：
- **emotional_payoff**: 观众看完心里留下什么？1-2 句话，要具体（不是 "感动"，而是 "想起小时候和爸爸出门那种什么都不用想的安心" 这种）
- **hook_strategy**: 开场前 3 秒怎么抓人？vlog 不是纪录片，开头平淡就废了。具体方案：悬念画面 / 反差强烈的两个镜头 / 一句问句字幕 / 一个情绪冲击的特写
- **audience_persona**: 这个 vlog 是给谁看的？自己回看 / 给家人看 / 发朋友圈 / 发抖音小红书 / 发 b 站
- **pacing_guidance**: 哪段必须快剪（炸点密集），哪段必须长镜（留情绪空间），哪里要绝对留白（黑屏/无字幕）
- **pov_voice**: 字幕语气是 "first_person"（我/我们）、"second_person"（你）、还是 "neutral"（陈述）。vlog 一般避免 neutral
- **avoid_list**: 明确要避免什么。常见雷区："过多字幕"、"形式化叙事（开头-发展-高潮-结尾）"、"陈词滥调（'美好的一天'）"、"配乐和画面不搭"

输出严格 JSON AudienceBrief。要犀利、要具体、要有判断，不要套话。"""

USER_TEMPLATE = """【事件记忆】
{memory_json}

【事件元数据】
event_id: {event_id}
{summary_hint}
人物: {persons}
时长跨度: {duration_h:.1f}h

请以 vlog 创作策划的视角，输出 AudienceBrief。"""


def load_jsonl(p, model):
    return [model.model_validate_json(l) for l in p.read_text(encoding="utf-8").splitlines() if l.strip()]


def write_audience(event: Event, memory: EventMemory) -> AudienceBrief:
    user = USER_TEMPLATE.format(
        memory_json=memory.model_dump_json(indent=2),
        event_id=event.event_id,
        summary_hint=event.summary_hint,
        persons=", ".join(event.person_ids_present) or "无",
        duration_h=event.duration_hours,
    )
    schema = AudienceBrief.model_json_schema()

    t0 = time.time()
    raw = call_llm_json(
        system=SYSTEM,
        user_text=user,
        schema=schema,
        schema_name="AudienceBrief",
        temperature=0.85,  # higher for creative brief
        max_tokens=4096,
    )
    dt = time.time() - t0
    log_call(
        step="step3b_audience",
        event_id=event.event_id,
        system=SYSTEM,
        user_text=user,
        response=raw,
        latency_sec=dt,
    )
    raw["event_id"] = event.event_id
    return AudienceBrief.model_validate(raw)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--event", default=None)
    parser.add_argument("--force", action="store_true")
    args = parser.parse_args()

    AUDIENCE_DIR.mkdir(parents=True, exist_ok=True)
    events = load_jsonl(W / "events.jsonl", Event)
    if args.event:
        events = [e for e in events if e.event_id == args.event]

    for ev in events:
        out = AUDIENCE_DIR / f"{ev.event_id}.json"
        if out.exists() and not args.force:
            print(f"[step3b] {ev.event_id}: cached -> {out}")
            continue
        mem_path = W / "event_memory" / f"{ev.event_id}.json"
        if not mem_path.exists():
            print(f"[step3b] {ev.event_id}: no event_memory; run step3_montage first")
            continue
        memory = EventMemory.model_validate_json(mem_path.read_text(encoding="utf-8"))
        try:
            ab = write_audience(ev, memory)
            out.write_text(ab.model_dump_json(indent=2), encoding="utf-8")
            print(f"[step3b] {ev.event_id}: '{ab.emotional_payoff[:50]}...' pov={ab.pov_voice} -> {out}")
        except Exception as e:
            print(f"[step3b] {ev.event_id} FAILED: {type(e).__name__}: {e}")


if __name__ == "__main__":
    main()
