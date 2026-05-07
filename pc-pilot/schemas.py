"""Shared Pydantic schemas for the pc-pilot pipeline.

Pipeline data flow:
  step0 -> Asset
  step1 -> Perception (joins onto Asset by asset_id)
  step2 -> Event (groups Asset by event_id)
  step3 -> PhotoLabel (VLM deep tags, only for shortlist after perception filter)
  step4 -> StyleRecommendation + StoryboardOutput (CandidateScript list)
  step5 -> MP4 files
"""
from __future__ import annotations

import sys
from typing import Literal, Optional

from pydantic import BaseModel, Field

# Force UTF-8 stdio on Windows so Chinese prints don't get GBK-mangled.
# schemas.py is imported by every step, so this single block fixes them all.
if sys.platform == "win32":
    for _s in (sys.stdout, sys.stderr):
        try:
            _s.reconfigure(encoding="utf-8", errors="replace")
        except (AttributeError, OSError):
            pass

MediaType = Literal["image", "video", "live_photo"]


# ---------- step0: Ingest ----------

class Asset(BaseModel):
    """A single piece of source media. One row per file (Live Photo = one Asset with both fields)."""

    asset_id: str = Field(description="Stable ID, typically filename stem")
    media_type: MediaType
    image_path: Optional[str] = Field(default=None, description="Relative to pc-pilot/")
    video_path: Optional[str] = Field(default=None, description="Relative to pc-pilot/. For live_photo, the .mov sibling")

    width: int
    height: int
    duration_sec: Optional[float] = Field(default=None, description="None for static images")

    exif_datetime: Optional[str] = Field(default=None, description="ISO 8601")
    exif_gps: Optional[tuple[float, float]] = Field(default=None, description="(lat, lon)")
    exif_camera: Optional[str] = None


# ---------- step1: Perception ----------

class FaceInstance(BaseModel):
    """One detected face on one asset, after global DBSCAN clustering."""
    bbox: tuple[float, float, float, float] = Field(description="(x1, y1, x2, y2) normalized 0-1")
    person_id: Optional[str] = Field(default=None, description="Cluster label, e.g. 'A'. None if low-confidence/noise.")
    det_score: float
    embedding_norm: float = Field(description="L2 norm, sanity check")


class VideoShot(BaseModel):
    """One shot inside a video/live_photo, from PySceneDetect."""
    shot_idx: int
    start_sec: float
    end_sec: float
    keyframe_path: str = Field(description="Path to the extracted keyframe JPG, relative to pc-pilot/")


class Perception(BaseModel):
    """All non-VLM signals for one asset. Joined with Asset by asset_id."""

    asset_id: str

    # Quality
    blur_score: float = Field(description="Laplacian variance; lower = blurrier")
    exposure: Literal["under", "ok", "over"]
    is_solid_color: bool = Field(description="Almost-uniform image (lens cap, dark accidental shot)")
    is_screenshot: bool = Field(description="Heuristic: aspect ratio + UI text detection")
    is_document: bool = Field(description="YOLO sees mostly paper/text/screen")
    nsfw_score: float = Field(default=0.0, ge=0.0, le=1.0, description="NSFW classifier confidence (0-1); >=0.6 treated as NSFW")
    is_nsfw: bool = Field(default=False, description="True when NSFW classifier crosses threshold")
    is_junk: bool = Field(description="Combined gate: blur OR solid OR screenshot OR document OR NSFW")

    # YOLO
    yolo_classes: list[str] = Field(default_factory=list, description="Unique COCO class names detected")
    yolo_person_count: int = 0

    # Face
    faces: list[FaceInstance] = Field(default_factory=list)

    # CLIP/SigLIP embedding (stored separately as numpy memmap; here we keep just a hash for lookup)
    clip_embedding_idx: Optional[int] = Field(default=None, description="Row index into workspace/clip_embeddings.npy")

    # Video shots (only for video / live_photo)
    shots: list[VideoShot] = Field(default_factory=list)


# ---------- step2: Event Segmentation ----------

class Event(BaseModel):
    """A coherent event: same time window + (optionally) same place + visually similar."""

    event_id: str = Field(description="Stable ID like 'evt_001'")
    asset_ids: list[str]
    start_datetime: Optional[str]
    end_datetime: Optional[str]
    duration_hours: float
    location_label: Optional[str] = Field(default=None, description="Coarse from GPS reverse geocode if available; else None")
    person_ids_present: list[str] = Field(default_factory=list, description="Unique person_ids appearing in this event")
    asset_count: int
    image_count: int
    video_count: int

    summary_hint: str = Field(default="", description="Human-readable hint, e.g. '2024-12-30 → 2025-01-02, ~80 photos, 3 people'")


# ---------- step3: Event-level Memory (montage browse) ----------

class KeyMoment(BaseModel):
    image_index: int = Field(ge=1, description="1-based position in the contact sheet")
    asset_id: str = Field(default="", description="resolved post-VLM by step3")
    why: str


class SubGroup(BaseModel):
    indices: list[int] = Field(description="1-based positions belonging to this group (e.g. burst shots)")
    label: str = Field(description="e.g. '连拍 5 张父子户外自拍'")


class EventMemory(BaseModel):
    """One-shot VLM browse over a contact sheet of all event assets. Replaces M0's per-asset PhotoLabel storm."""
    event_id: str
    storyline_summary: str = Field(description="<=300字 Chinese; the story the event tells")
    key_moments: list[KeyMoment]
    emotional_arc: str
    characters_observed: list[str]
    visual_style_signals: str
    notable_subgroups: list[SubGroup] = Field(default_factory=list)


class AudienceBrief(BaseModel):
    """The 'why this vlog matters' brief, written BEFORE the director plans shots.

    Forces the agent to think about emotional payoff and audience experience first,
    not jump straight into shot selection. Inspired by how human editors first ask
    'what feeling do I want the viewer to walk away with?'
    """
    event_id: str
    emotional_payoff: str = Field(description="观众看完应该感受到什么？1-2句话，具体的情绪/记忆/共鸣点")
    hook_strategy: str = Field(description="开场前 3 秒怎么抓人？悬念 / 反差 / 冲击画面 / 问句字幕 / 直接情绪")
    audience_persona: str = Field(description="这个 vlog 是给谁看的？（自己回看 / 给家人看 / 发朋友圈 / 发抖音/小红书）")
    pacing_guidance: str = Field(description="节奏建议：哪段快剪、哪段长镜、哪里留白")
    pov_voice: Literal["first_person", "second_person", "neutral"] = Field(description="字幕语气：第一人称（我/我们）、第二人称（你）、中性陈述")
    avoid_list: list[str] = Field(default_factory=list, description="明确要避免的（陈词滥调、过多字幕、形式化叙事）")


# ---------- step4: Director Brief ----------

class StyleDef(BaseModel):
    """Loaded from styles/*.yaml. In v2 these are HINTS to the director, not constraints."""
    name: str
    display_name: str
    target_duration_sec: float
    shot_pace: Literal["slow", "medium", "fast"]
    suitable_when: str
    prompt_hints: str


ShotRole = Literal["opening", "establishing", "portrait", "action", "climax", "transition", "closing"]


TransitionKind = Literal["cut", "fade", "crossfade", "fadeblack", "fadewhite", "slideleft", "slideright", "slideup", "circleopen", "circleclose", "wipeleft", "wiperight", "zoomin", "smoothleft", "smoothright"]
KenBurnsKind = Literal["zoom_in", "zoom_out", "pan_left", "pan_right", "pan_up", "pan_down", "static"]
ColorGrade = Literal["neutral", "warm", "cool", "vibrant", "muted", "cinematic_teal_orange", "vintage"]


class ShotRequest(BaseModel):
    """A slot in the director's blueprint. Editor will fill it via recall + VLM curation."""
    position: int = Field(ge=1)
    role: ShotRole
    mood_target: str
    visual_requirements: str = Field(description="自由文本描述：例如 '傍晚 / 户外 / 主角入镜 / 大景别 / 表情自然'")
    duration_sec: float = Field(ge=0.3, le=15.0, description="Vlog 节奏需要强对比：闪切 0.3-0.6s + 长镜 5-15s 都允许")
    person_constraint: Optional[str] = Field(default=None, description="None / person_id like 'A' / '无人物'")

    # NEW: Director makes creative decisions about caption + camera move + transition
    caption_text: str = Field(default="", description="<=15字中文字幕，留空表示这个镜头不要字幕。建议只在关键节点配字幕（开场/章节标题/情绪锚点/收尾），避免每镜都写。")
    ken_burns_hint: Optional[KenBurnsKind] = Field(default=None, description="可选的运镜偏好；为 None 时由 editor 按节奏决定")
    transition_in_hint: Optional[TransitionKind] = Field(default=None, description="进入此 shot 的转场类型；None 时 editor 决定")


class DirectorBrief(BaseModel):
    """Director's plan for one event's vlog. Free-form tone, NOT bound to 7 fixed style names."""
    event_id: str
    title: str
    tagline: Optional[str] = None
    target_duration_sec: float
    tone: str = Field(description="自由文本，例如 '黄昏漫游+父子温情'，不必对应风格库")
    narrative_arc: list[str] = Field(description="按顺序的 beat 描述，例如 ['开场远景', '主角入场', '关键互动', '情绪高潮', '余韵收尾']")
    color_grade: ColorGrade = Field(default="neutral", description="整片调色风格")
    shot_blueprint: list[ShotRequest]


# ---------- step5/step7: ShotSpec (rendered) ----------

class ShotSpec(BaseModel):
    """One concrete shot ready for FFmpeg render. Produced by step5_editor."""
    asset_id: str
    media_type: MediaType
    order: int = Field(ge=1)
    duration_sec: float = Field(ge=0.3, le=15.0, description="Vlog 节奏需要强对比：闪切 0.3-0.6s + 长镜 5-15s 都允许")

    # For video / live_photo: which sub-range to use (None = use whole video clipped to duration_sec)
    video_trim_start: Optional[float] = None
    video_trim_end: Optional[float] = None

    caption: str = Field(default="", description="<=20 char Chinese subtitle, empty for none")
    ken_burns: KenBurnsKind = "zoom_in"
    transition_in: TransitionKind = "crossfade"
    rationale: str = Field(description="Why this shot was selected and placed here")


# ---------- step6: Critic ----------

class CritiqueRevision(BaseModel):
    shot_order: int = Field(ge=1, description="1-based order of the shot in current Timeline.shots to replace")
    new_request: ShotRequest


class Critique(BaseModel):
    iteration: int = Field(ge=1)
    issues: list[str]
    revised_requests: list[CritiqueRevision] = Field(default_factory=list)


# ---------- step5/6: Timeline (rolling state) ----------

class Timeline(BaseModel):
    """Rolling timeline for one event. step5 produces v1, step6 may revise once or twice."""
    event_id: str
    director_brief_path: str
    title: str
    tagline: Optional[str] = None
    tone: str
    color_grade: ColorGrade = "neutral"
    total_duration_sec: float
    shots: list[ShotSpec]
    critique_history: list[Critique] = Field(default_factory=list)
    final: bool = False
