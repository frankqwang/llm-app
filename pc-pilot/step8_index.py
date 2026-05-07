"""Step 8 (v2): HTML index showing the full agentic decision chain.

For each event:
  - Original photos (thumbnail row + contact-sheet preview)
  - EventMemory (storyline / arc / characters / subgroups)
  - DirectorBrief (title / tone / arc / blueprint with captions)
  - Timeline final (rendered MP4 + per-shot rationale)
  - Critique history (issues + revisions across iterations)

So the human sees not just the OUTPUT but the REASONING.
"""
from __future__ import annotations

import json
from pathlib import Path

from jinja2 import Template

import schemas
from schemas import Asset, DirectorBrief, Event, EventMemory, Timeline

ROOT = Path(__file__).parent
W = ROOT / "workspace"


TEMPLATE = """<!DOCTYPE html>
<html lang="zh">
<head>
<meta charset="utf-8">
<title>pc-pilot v2 — Agentic vlog candidates</title>
<style>
  * { box-sizing: border-box; }
  body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif;
         background:#0e0e10; color:#e8e8e8; margin:0; padding:24px; line-height:1.55; }
  h1 { color:#fff; border-bottom:2px solid #333; padding-bottom:10px; margin-top:0; }
  h2 { color:#5c8df6; margin-top:8px; font-size:1.1em; }
  details.event { background:#18181c; border-radius:10px; padding:18px 22px; margin-bottom:28px; border:1px solid #2a2a30; }
  details.event > summary { cursor:pointer; font-size:1.3em; font-weight:bold; padding:6px 0; color:#fff; }
  .event-grid { display:grid; grid-template-columns: 320px 1fr; gap:24px; margin-top:14px; }
  @media (max-width:1100px) { .event-grid { grid-template-columns: 1fr; } }
  .col-video { }
  .col-info { }
  .col-video video { width:320px; max-width:100%; border-radius:6px; background:#000; }
  .meta-strip { color:#888; font-size:0.88em; margin:6px 0 14px; }
  .badge { display:inline-block; background:#2a2a35; color:#bbb; padding:2px 8px; border-radius:3px;
           font-size:0.78em; margin:1px 4px 1px 0; border:1px solid #3a3a45; }
  .badge-style { background:#2d4a8e; color:#fff; }
  .badge-warm { background:#7a4422; color:#ffd; }
  .badge-cool { background:#234d6f; color:#dfe; }
  .badge-vibrant { background:#7a5e22; color:#ffd; }
  .badge-muted { background:#444; color:#ccc; }
  .badge-cinematic_teal_orange { background:#1a4a4a; color:#ffe; }
  .badge-vintage { background:#5a3a3a; color:#fdd; }
  .section { margin-top:14px; padding-top:12px; border-top:1px dashed #2a2a30; }
  .label { color:#888; font-size:0.8em; text-transform:uppercase; letter-spacing:1px; }
  .quote { color:#ddd; padding:8px 14px; background:#222; border-left:3px solid #5c8df6; margin:6px 0; border-radius:0 4px 4px 0; }
  .arc-list { margin:6px 0; padding-left:0; list-style:none; counter-reset:beat; }
  .arc-list li { padding:6px 0 6px 32px; position:relative; counter-increment:beat; color:#ccc; }
  .arc-list li::before { content:counter(beat); position:absolute; left:0; top:6px; width:22px; height:22px;
                          background:#5c8df6; color:#fff; border-radius:50%; text-align:center;
                          font-size:0.78em; line-height:22px; font-weight:bold; }
  .blueprint { display:grid; grid-template-columns:repeat(auto-fill,minmax(220px,1fr)); gap:8px; margin-top:8px; }
  .slot { background:#1f1f25; padding:9px 11px; border-radius:5px; font-size:0.83em; border:1px solid #2a2a30; }
  .slot-head { color:#fff; font-weight:bold; margin-bottom:4px; }
  .slot-cap { color:#5c8df6; font-style:italic; margin:4px 0; min-height:1em; }
  .slot-meta { color:#888; font-size:0.75em; }
  .timeline-shots { display:grid; grid-template-columns:repeat(auto-fill,minmax(180px,1fr)); gap:8px; margin-top:10px; }
  .shot-card { background:#1c1c22; border-radius:5px; padding:7px; font-size:0.78em; }
  .shot-card img { width:100%; height:130px; object-fit:cover; border-radius:3px; background:#000; }
  .shot-cap { color:#fff; font-weight:bold; margin-top:4px; min-height:1em; }
  .shot-rat { color:#999; margin-top:3px; line-height:1.3; }
  .critique { background:#2a1f1f; padding:10px 14px; border-left:3px solid #c66; margin:6px 0; border-radius:0 4px 4px 0; }
  .crit-iter { color:#fa9; font-weight:bold; }
  .crit-issue { color:#ddd; margin:4px 0 4px 14px; padding-left:8px; border-left:2px solid #644; }
  .crit-rev { color:#9c9; margin:4px 0 4px 14px; padding-left:8px; border-left:2px solid #464; font-size:0.85em; }
  .contact-sheet-preview { margin-top:8px; }
  .contact-sheet-preview img { max-width:100%; max-height:400px; border-radius:4px; border:1px solid #2a2a30; }
  .character { color:#ddd; margin:4px 0; padding-left:14px; border-left:2px solid #5c8df6; }
  .subgroup { color:#999; font-size:0.85em; margin:3px 0; }
  .subgroup .indices { color:#5c8df6; font-family:monospace; }
</style>
</head>
<body>
<h1>pc-pilot v2 — Agentic vlog candidates</h1>
<p class="meta-strip">{{ events|length }} 事件 · 看到的是 AI 的完整决策链：浏览 → 导演 → 剪辑 → 审片</p>

{% for ev in events %}
<details class="event" {% if loop.first %}open{% endif %}>
  <summary>{{ ev.event_id }} — {{ ev.title }}</summary>

  <div class="event-grid">
    <div class="col-video">
      {% if ev.mp4 %}
      <video controls preload="metadata" src="{{ ev.mp4 }}"></video>
      {% else %}
      <div style="width:320px;height:570px;background:#000;display:flex;align-items:center;justify-content:center;color:#666;border-radius:6px;">未渲染</div>
      {% endif %}
      <div class="meta-strip" style="margin-top:8px;">
        <span class="badge badge-style">{{ ev.tone[:32] }}{% if ev.tone|length > 32 %}…{% endif %}</span>
        <span class="badge badge-{{ ev.color_grade }}">{{ ev.color_grade }}</span><br>
        <span class="badge">{{ ev.shot_count }} shots</span>
        <span class="badge">{{ '%.1f'|format(ev.duration) }}s</span>
        <span class="badge">{{ ev.captions_count }} 字幕</span>
        {% if ev.summary_hint %}<br><span style="color:#777;font-size:0.85em;">{{ ev.summary_hint }}</span>{% endif %}
      </div>

      {% if ev.contact_sheet %}
      <div class="contact-sheet-preview">
        <div class="label">VLM 看到的素材网格</div>
        <a href="{{ ev.contact_sheet }}" target="_blank"><img src="{{ ev.contact_sheet }}" loading="lazy"></a>
      </div>
      {% endif %}
    </div>

    <div class="col-info">
      {% if ev.memory %}
      <div class="section">
        <h2>🧠 EventMemory — VLM 一次性浏览（{{ ev.memory.key_moments|length }} 个 key moment）</h2>
        <div class="quote">{{ ev.memory.storyline_summary }}</div>
        <div class="label">情绪曲线</div>
        <div style="margin:4px 0 10px;">{{ ev.memory.emotional_arc }}</div>
        <div class="label">人物</div>
        {% for c in ev.memory.characters_observed %}<div class="character">{{ c }}</div>{% endfor %}
        {% if ev.memory.notable_subgroups %}
        <div class="label" style="margin-top:8px;">连拍/重复组</div>
        {% for sg in ev.memory.notable_subgroups %}
          <div class="subgroup"><span class="indices">[{{ sg.indices|join(',') }}]</span> {{ sg.label }}</div>
        {% endfor %}
        {% endif %}
      </div>
      {% endif %}

      {% if ev.brief %}
      <div class="section">
        <h2>🎬 DirectorBrief — 导演大纲</h2>
        <div class="label">叙事弧线</div>
        <ol class="arc-list">{% for beat in ev.brief.narrative_arc %}<li>{{ beat }}</li>{% endfor %}</ol>
        <div class="label" style="margin-top:8px;">分镜蓝图（{{ ev.brief.shot_blueprint|length }} 个 slot，导演自定字幕/运镜/转场）</div>
        <div class="blueprint">
          {% for s in ev.brief.shot_blueprint %}
          <div class="slot">
            <div class="slot-head">#{{ s.position }} {{ s.role }} · {{ '%.1f'|format(s.duration_sec) }}s</div>
            {% if s.caption_text %}<div class="slot-cap">"{{ s.caption_text }}"</div>{% endif %}
            <div class="slot-meta">
              <b>{{ s.mood_target }}</b> · {{ s.visual_requirements }}<br>
              {% if s.ken_burns_hint %}<span class="badge">KB:{{ s.ken_burns_hint }}</span>{% endif %}
              {% if s.transition_in_hint %}<span class="badge">T:{{ s.transition_in_hint }}</span>{% endif %}
              {% if s.person_constraint %}<span class="badge">人:{{ s.person_constraint }}</span>{% endif %}
            </div>
          </div>
          {% endfor %}
        </div>
      </div>
      {% endif %}

      {% if ev.timeline %}
      <div class="section">
        <h2>✂️ Timeline — Editor 的实际选择</h2>
        <div class="timeline-shots">
          {% for s in ev.timeline.shots %}
          <div class="shot-card">
            {% if s.thumb %}<img src="{{ s.thumb }}" loading="lazy">{% endif %}
            {% if s.caption %}<div class="shot-cap">"{{ s.caption }}"</div>{% else %}<div class="shot-cap">&nbsp;</div>{% endif %}
            <div class="slot-meta">
              #{{ s.order }} {{ s.media_type }} · {{ '%.1f'|format(s.duration_sec) }}s<br>
              <span class="badge">KB:{{ s.ken_burns }}</span>
              <span class="badge">T:{{ s.transition_in }}</span>
            </div>
            <div class="shot-rat">{{ s.rationale[:200] }}</div>
          </div>
          {% endfor %}
        </div>
      </div>
      {% endif %}

      {% if ev.critique_history %}
      <div class="section">
        <h2>🔍 Critique 审片历史 ({{ ev.critique_history|length }} 轮)</h2>
        {% for c in ev.critique_history %}
        <div class="critique">
          <div class="crit-iter">Iteration {{ c.iteration }} — {{ c.issues|length }} issues, {{ c.revised_requests|length }} revisions</div>
          {% for issue in c.issues %}<div class="crit-issue">⚠ {{ issue }}</div>{% endfor %}
          {% for r in c.revised_requests %}<div class="crit-rev">↻ shot #{{ r.shot_order }} → 重选: {{ r.new_request.visual_requirements }}</div>{% endfor %}
        </div>
        {% endfor %}
      </div>
      {% endif %}
    </div>
  </div>
</details>
{% endfor %}
</body>
</html>
"""


def load_jsonl(p, model):
    return [model.model_validate_json(l) for l in p.read_text(encoding="utf-8").splitlines() if l.strip()]


def thumb_for(asset: Asset) -> str | None:
    """Return relative path (from workspace/index.html) to a thumbnail."""
    if asset.image_path and asset.image_path.lower().endswith((".jpg", ".jpeg", ".png", ".webp")):
        return Path("..", asset.image_path).as_posix()
    kf = W / "keyframes" / f"{asset.asset_id}_s00.jpg"
    if kf.exists():
        return Path("keyframes", kf.name).as_posix()
    return None


def main():
    assets = {a.asset_id: a for a in load_jsonl(W / "assets.jsonl", Asset)}
    events = load_jsonl(W / "events.jsonl", Event)

    rendered = []
    for ev in events:
        mp4 = W / "candidates_v2" / f"{ev.event_id}.mp4"
        memory_path = W / "event_memory" / f"{ev.event_id}.json"
        brief_path = W / "director" / f"{ev.event_id}.json"
        timeline_path = W / "timeline" / f"{ev.event_id}_final.json"
        if not timeline_path.exists():
            timeline_path = W / "timeline" / f"{ev.event_id}_v1.json"
        contact = W / "agent_logs" / f"{ev.event_id}_contact_sheet.jpg"

        memory = EventMemory.model_validate_json(memory_path.read_text(encoding="utf-8")) if memory_path.exists() else None
        brief = DirectorBrief.model_validate_json(brief_path.read_text(encoding="utf-8")) if brief_path.exists() else None
        tl = Timeline.model_validate_json(timeline_path.read_text(encoding="utf-8")) if timeline_path.exists() else None

        if not (memory or brief or tl):
            continue

        # decorate timeline shots with thumbs
        tl_view = None
        if tl:
            shots_view = []
            for s in tl.shots:
                a = assets.get(s.asset_id)
                shots_view.append({**s.model_dump(), "thumb": thumb_for(a) if a else None})
            tl_view = {**tl.model_dump(), "shots": shots_view}

        captions_count = sum(1 for s in (tl.shots if tl else []) if s.caption)

        rendered.append({
            "event_id": ev.event_id,
            "summary_hint": ev.summary_hint,
            "title": (tl.title if tl else (brief.title if brief else ev.event_id)),
            "tone": (tl.tone if tl else (brief.tone if brief else "")),
            "color_grade": (tl.color_grade if tl else (brief.color_grade if brief else "neutral")),
            "shot_count": len(tl.shots) if tl else 0,
            "duration": tl.total_duration_sec if tl else 0.0,
            "captions_count": captions_count,
            "mp4": mp4.relative_to(W).as_posix() if mp4.exists() else None,
            "contact_sheet": contact.relative_to(W).as_posix() if contact.exists() else None,
            "memory": memory.model_dump() if memory else None,
            "brief": brief.model_dump() if brief else None,
            "timeline": tl_view,
            "critique_history": [c.model_dump() for c in (tl.critique_history if tl else [])],
        })

    html = Template(TEMPLATE).render(events=rendered)
    out = W / "index_v2.html"
    out.write_text(html, encoding="utf-8")
    print(f"[step8] rendered {len(rendered)} events -> {out}")
    print(f"        Open in browser: {out.absolute().as_uri()}")


if __name__ == "__main__":
    main()
