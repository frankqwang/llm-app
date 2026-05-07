"""Step 4 (v2): Director Agent — turns EventMemory into a DirectorBrief.

Free-form tone (NOT bound to 7 fixed style names). Emits an ordered shot_blueprint
where each ShotRequest declares what the editor needs to find, NOT which asset to use.
The actual asset selection happens in step5_editor (recall + VLM curation).
"""
from __future__ import annotations

import argparse
import json
import time
from pathlib import Path

import yaml

import schemas  # forces UTF-8 stdio
from agent_log import log_call
from schemas import AudienceBrief, DirectorBrief, Event, EventMemory, ShotRequest, StyleDef
from vlm_client import call_llm_json

ROOT = Path(__file__).parent
W = ROOT / "workspace"
DIRECTOR_DIR = W / "director"
STYLES_DIR = ROOT / "styles"


SYSTEM = """你是一名 vlog 剪辑导演。你已经看过这一段事件的所有素材 + 拿到了创作策划写的 Audience Brief（"想让观众感受到什么"）。
现在请围绕 Audience Brief 的 emotional_payoff、hook_strategy、pov_voice，写一份"导演大纲"——包括叙事结构、镜头需求、字幕、运镜、转场、调色。

**Vlog DNA（必须遵循）**：
- **不是纪录片，是 vlog**：第一/二人称视角，像跟朋友讲今天发生的事
- **开场前 3 秒必须 hook**（按 AudienceBrief.hook_strategy 执行）。平淡开场=作品死掉
- **少而精原则（关键）**：
  - shot 总数 **6-9 个**（不超过 10）。**画面太短/太多会让观众累**
  - 默认 shot duration **2.5-3.5 秒**。普通生活照、人物特写、日常画面都在这个区间
  - **!! 严格下限：除 hook 外，每个 shot 至少 1.8s !!** 1.0-1.5s 的快速跳切看起来像"PPT 焦虑感"，**不要这么写**
  - **闪切（hook）**：**只有 1 个**，0.5-0.8s。位于第 1 位（opening role）。其他 shot 都不要 < 1.8s
  - **CLOSING 必须有重量（关键）**：
    - 收尾必须是 emotional payoff 的视觉兑现，不能是事件中随机一张平凡画面
    - 至少 3 秒，让观众回味
    - 内容要贴 AudienceBrief.emotional_payoff（比如要传递"安全感"，收尾镜头里就要有"主角眼神放松"或"两人同框的距离"）
    - **避免**：背影侧脸但没情绪信号 / 不知所云的转身瞬间 / 跟前面没承接的乱入镜头
  - **长镜（≥5s）必须有意义**：只用在以下场景，**不要随便给普通画面 5s+**：
    - 真正的"空镜头"（日落 / 海浪 / 人物背影远景 / 城市灯光延时）
    - 情绪发酵的人物特写（对视、沉思、流泪、深情瞬间）
    - 收尾留白（黑屏前最后一镜的静止呼吸）
    - 配字幕的"内心独白" shot（字幕需要时间读）
    - 长视频原素材本身有动作发展（比如骑滑板车的连续动作）
  - 整片**最多 1-2 个长镜**，集中在 climax / closing / 关键 emotional anchor 处
  - **不要给普通自拍 / 食物特写 / 走路镜头 6s+** —— 那叫卡顿不叫呼吸
- **!! shot duration 总和必须接近 target_duration_sec !!**（误差 ±10% 内）。LLM 数学不好，写完后请自己加一遍验证。target 一般 30-50s，shot 6-9 个所以平均 4-6s。
- **字幕必须用 AudienceBrief.pov_voice 指定的语气**，避免陈词滥调（"美好的一天"、"温馨瞬间"）
- **emotional payoff 一定要在收尾兑现**：最后 1-2 个 shot 必须呼应 AudienceBrief.emotional_payoff
- **避免 AudienceBrief.avoid_list 里列的所有问题**
- **慎用 cut + 视觉跳跃**：
  - cut 整片最多 **1 个**（用在 hook 后的第二镜，配合"惊喜揭示"）
  - 默认转场：**crossfade / smoothleft / smoothright / fade** —— 带 0.6-1s 过渡，呼吸感
  - 章节切换用 fadeblack（1s）—— 给观众"翻页"喘息
  - **避免相邻 shot 内容跳跃过大**：比如室内→室外→车内 三连切，观众会晕。同一段保持 2-3 个相似场景再切换
  - shot N 和 shot N+1 应该在主题/人物/场景上有**至少一项延续**

输出严格 JSON DirectorBrief：
{
  "title": "中文标题（<=15 字）",
  "tagline": "副标题（可选，<=20 字，可为 null）",
  "target_duration_sec": 数字（一般 25-90 秒）,
  "tone": "自由文本，描述整体情绪和质感，例如 '黄昏漫游+父子温情'。**不要被风格库锁死**，可以混合或自创。",
  "narrative_arc": ["beat 1", "beat 2", ...],   // 4-8 个 beat
  "color_grade": "neutral|warm|cool|vibrant|muted|cinematic_teal_orange|vintage",
  "shot_blueprint": [
    {
      "position": 1,
      "role": "opening|establishing|portrait|action|climax|transition|closing",
      "mood_target": "情绪标签",
      "visual_requirements": "自由描述",
      "duration_sec": 0.4-8.0,
      "person_constraint": null/"person_id"/"无人物",
      "caption_text": "字幕文案（<=15字）；不需要字幕则留空",
      "ken_burns_hint": "zoom_in|zoom_out|pan_left|pan_right|pan_up|pan_down|static",
      "transition_in_hint": "cut|fade|crossfade|fadeblack|fadewhite|slideleft|slideright|slideup|circleopen|circleclose|wipeleft|wiperight|zoomin|smoothleft|smoothright"
    }
  ]
}

字幕规则（**关键，决定 vlog 感 — 这一段不要敷衍，写完前自己数一遍**）：

【硬性数量要求】**整片必须 ≥ 4 个 caption_text 非空**。少于 4 个直接当不及格，宁可多不可少。9-shot 一般 4-6 个最舒服。

【每个 caption_text 必须是真字幕，不能用 "(no caption)" 或留空当占位】

【4 个**必填**位置（即使你想偷懒也要写满）】：
  1. **shot 1 (opening/hook)**：开场点题字幕，4-8 字，例 "周末/动物园"、"那天天气真好"、"哇，这也太吵了"
  2. **中段某一镜（章节切换 / 情绪锚点）**：6-12 字内心独白，例 "这一刻好像突然懂了"、"夕阳真美"
  3. **climax 或 payoff 前一镜**：情绪 punchline，例 "我在身后兜底"、"陪伴最久的，是平凡日子"
  4. **closing 收尾镜**：5-10 字感慨，例 "晚安，今天"、"这就够了"、"明天见"

【其他可选位置】：章节切换处可加 1-2 个（如从户外到室内："回家路上"），但不要每张都加（拥挤）

【风格要求】：
- 严格按 AudienceBrief.pov_voice（first_person 用"我/我们"，second_person 用"你"）
- 避免陈词滥调（"美好的一天"、"温馨瞬间"、"happy moment"）
- 鼓励**有点反常或诗意**的句子（"把童年浪费在路边"比"快乐的童年"好 10 倍）

**最后自检**：写完 shot_blueprint 后，数一遍 caption_text 非空数量。少于 4 → 重写。

运镜规则（ken_burns_hint）：
- 慢节奏镜头（>3s）：zoom_in / zoom_out / pan_*
- 快节奏镜头（<2s）：static
- portrait/特写：static 或细微 zoom_in
- establishing/远景：pan_* 增加空间感
- climax 高潮：zoom_in 强调
- closing 收尾：zoom_out 远去

调色规则（color_grade）：
- warm：暖色基调（黄昏 / 父子温情 / 治愈系 / 日常生活）
- cool：冷色基调（夜景 / 城市 / 宁静 / 沉思）
- vibrant：高饱和（动物园 / 美食 / 童趣 / 派对）
- muted：低饱和、文艺感（咖啡馆 / 阴天 / 内省）
- cinematic_teal_orange：电影感大片调色（旅行 / 风景 / 戏剧）
- vintage：复古胶片（怀旧 / 年代感 / 婚礼）
- neutral：不调色（纪实 / 中性 / 默认）

转场规则（transition_in_hint）：
- 第一个 shot：transition_in 不重要（默认 fade）
- 同一场景内连续 shots：crossfade
- 情绪相同主题切换：crossfade
- 章节切换 / 时空跳跃：fadeblack 或 circleopen
- **高潮镜头进入：zoomin（冲击感，vlog 必备爆点动作）**
- **章节标题 / "哇"瞬间：fadewhite（闪白，刺激短暂的视觉重置）**
- 节奏快剪 / collage 段：cut（硬切）
- 滑动型（slideleft/right/up）：表示空间移动（如车窗外景切换）
- smoothleft/right：柔和滑入，比 slide 更克制，适合温情段
- wipeleft/wiperight：纪录片感的章节切

要求：
1. 镜头总数 ≤ 15
2. shot_blueprint 描述的是"我需要什么样的镜头 + 怎么呈现"
3. 风格库仅作灵感参考，不是必须遵循的 7 选 1
4. tone 可以是单词、短语、句子，鼓励原创
"""

USER_TEMPLATE = """【Audience Brief — 这是创作的 NORTH STAR】
{audience_json}

【事件记忆】
{memory_json}

【事件元数据】
event_id: {event_id}
{summary_hint}
人物: {persons}
时间跨度: {duration_h:.1f}h

【素材时长分布（重要 — 决定你能写多长的 shot）】
{asset_stats}

【风格库（hint，不约束）】
{styles_block}

请围绕 Audience Brief 输出 DirectorBrief。**根据素材时长分布合理写 shot duration**——
比如只有几个 ≥5s 的真视频时，长镜数量就要少；如果几乎全是 1.5-3s 的 Live Photo，
就**默认每个 shot 1.5-3s**，整片 25-40s 即可，不要硬凑 60s。"""


def load_jsonl(p, model):
    return [model.model_validate_json(l) for l in p.read_text(encoding="utf-8").splitlines() if l.strip()]


def load_styles() -> list[StyleDef]:
    styles = []
    for path in sorted(STYLES_DIR.glob("*.yaml")):
        styles.append(StyleDef(**yaml.safe_load(path.read_text(encoding="utf-8"))))
    return styles


def styles_block(styles: list[StyleDef]) -> str:
    return "\n".join(f"- {s.name} ({s.display_name}, ~{s.target_duration_sec}s {s.shot_pace}): {s.suitable_when.strip().splitlines()[0]}"
                     for s in styles)


def _asset_duration_stats(event: Event, assets: dict) -> str:
    """Bucket assets by media type and clip duration to inform Director's shot-length planning."""
    images = videos = lives = 0
    long_clips = 0   # ≥5s real videos that can sustain long shots
    short_clips = 0  # <3s clips that can only do quick beats
    for aid in event.asset_ids:
        a = assets.get(aid)
        if not a:
            continue
        if a.media_type == "image":
            images += 1
            continue
        dur = a.duration_sec or 0
        if a.media_type == "video":
            videos += 1
            if dur >= 5.0: long_clips += 1
            elif dur < 3.0: short_clips += 1
        elif a.media_type == "live_photo":
            lives += 1
            short_clips += 1  # Live Photos always short (~2-3s)
    lines = [
        f"  - 静态图: {images}",
        f"  - Live Photo (clips ~2-3s): {lives}",
        f"  - 视频片段: {videos} (其中 ≥5s 长片 {long_clips} 个, <3s 短片 {short_clips - lives} 个)",
        f"  - 总可用 ≥5s 镜头数: {long_clips + images} (image 可任意长但单镜建议 ≤6s)",
    ]
    return "\n".join(lines)


def direct(event: Event, memory: EventMemory, audience: AudienceBrief, styles: list[StyleDef], assets: dict) -> DirectorBrief:
    user = USER_TEMPLATE.format(
        audience_json=audience.model_dump_json(indent=2),
        memory_json=memory.model_dump_json(indent=2),
        event_id=event.event_id,
        summary_hint=event.summary_hint,
        persons=", ".join(event.person_ids_present) or "无",
        duration_h=event.duration_hours,
        asset_stats=_asset_duration_stats(event, assets),
        styles_block=styles_block(styles),
    )
    schema = DirectorBrief.model_json_schema()

    t0 = time.time()
    raw = call_llm_json(
        system=SYSTEM,
        user_text=user,
        schema=schema,
        schema_name="DirectorBrief",
        temperature=0.7,
        max_tokens=8192,
    )
    dt = time.time() - t0
    log_call(
        step="step4_director",
        event_id=event.event_id,
        system=SYSTEM,
        user_text=user,
        response=raw,
        latency_sec=dt,
    )
    raw["event_id"] = event.event_id
    return DirectorBrief.model_validate(raw)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--event", default=None)
    parser.add_argument("--force", action="store_true")
    args = parser.parse_args()

    DIRECTOR_DIR.mkdir(parents=True, exist_ok=True)
    events = load_jsonl(W / "events.jsonl", Event)
    if args.event:
        events = [e for e in events if e.event_id == args.event]
    styles = load_styles()
    from schemas import Asset

    assets = {a.asset_id: a for a in load_jsonl(W / "assets.jsonl", Asset)}
    print(f"[step4] {len(events)} events; style hints: {[s.name for s in styles]}")

    for ev in events:
        out = DIRECTOR_DIR / f"{ev.event_id}.json"
        if out.exists() and not args.force:
            print(f"[step4] {ev.event_id}: cached -> {out}")
            continue
        mem_path = W / "event_memory" / f"{ev.event_id}.json"
        aud_path = W / "audience" / f"{ev.event_id}.json"
        if not mem_path.exists():
            print(f"[step4] {ev.event_id}: no event_memory; run step3_montage first")
            continue
        if not aud_path.exists():
            print(f"[step4] {ev.event_id}: no audience brief; run step3b_audience first")
            continue
        memory = EventMemory.model_validate_json(mem_path.read_text(encoding="utf-8"))
        audience = AudienceBrief.model_validate_json(aud_path.read_text(encoding="utf-8"))

        try:
            brief = direct(ev, memory, audience, styles, assets)
            out.write_text(brief.model_dump_json(indent=2), encoding="utf-8")
            print(f"[step4] {ev.event_id}: '{brief.title}' tone='{brief.tone[:30]}' "
                  f"{len(brief.shot_blueprint)} shots / {brief.target_duration_sec:.0f}s -> {out}")
        except Exception as e:
            print(f"[step4] {ev.event_id} FAILED: {type(e).__name__}: {e}")


if __name__ == "__main__":
    main()
