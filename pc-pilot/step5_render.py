"""Step 5: render each CandidateScript to MP4 via FFmpeg.

Strategy: render each shot independently to a small mp4 (with Ken Burns + fade + caption),
then concat them and overlay BGM in a final pass. Per-shot files live in
workspace/render_cache/<candidate_id>/ for easy debugging.

This avoids one-giant-filter-graph which is hard to debug when even one shot fails.

Output: workspace/candidates/<event_id>/<style>.mp4
"""
from __future__ import annotations

import argparse
import os
import subprocess
import sys
from pathlib import Path

from tqdm import tqdm

from schemas import Asset
# Note: M0's CandidateScript/StoryboardOutput were deleted in v2; step5_render is kept
# only as a library of ffmpeg helpers (render_image_shot, render_video_shot, concat_and_add_bgm,
# find_bgm). step7_render.py is now the production entry point that drives Timeline render.

ROOT = Path(__file__).parent
WORKSPACE = ROOT / "workspace"
ASSETS_BGM_DIR = ROOT / "assets" / "bgm"

OUT_W = int(os.environ.get("PC_PILOT_OUT_W", 1080))
OUT_H = int(os.environ.get("PC_PILOT_OUT_H", 1920))
FPS = int(os.environ.get("PC_PILOT_FPS", 30))
_DEFAULT_FONT_REL = "assets/fonts/msyhbd.ttc"  # bold for caption punch; falls back to msyh
_FALLBACK_FONT_REL = "assets/fonts/msyh.ttc"
_DEFAULT_FONT_ABS = str((Path(__file__).parent / _DEFAULT_FONT_REL).resolve())
FONT_PATH = os.environ.get("PC_PILOT_FONT", _DEFAULT_FONT_REL)
FADE_DUR = 0.35

# Strip emoji from captions: msyh.ttc has no emoji glyphs and drawtext doesn't support
# color emoji rendering anyway. Range covers BMP symbols + supplementary emoji planes.
import re as _re
_EMOJI_RE = _re.compile(
    "["
    "\U0001F000-\U0001FFFF"  # symbols & pictographs
    "\U00002600-\U000027BF"  # misc symbols + dingbats
    "\U0001F1E6-\U0001F1FF"  # regional indicators
    "]+"
)


def escape_drawtext_text(s: str) -> str:
    """Strip emoji + collapse newlines + escape characters drawtext interprets specially.

    drawtext doesn't auto-wrap, so we replace \\n and CR with " · " so director's multi-line
    captions still read OK on a single line.
    """
    s = _EMOJI_RE.sub("", s).strip()
    s = s.replace("\r\n", " · ").replace("\n", " · ").replace("\r", " · ")
    return (
        s.replace("\\", "\\\\")
        .replace(":", "\\:")
        .replace("'", "’")
        .replace("%", "\\%")
    )


def escape_filter_path(p: str) -> str:
    """Normalize a path for ffmpeg filter arg.

    ffmpeg's filter parser treats `:` as key-value separator and `\\` as escape, so
    Windows drive letters need both: forward slashes + escaped colon. We do NOT wrap
    the path in single quotes — the parser inside drawtext handles unquoted paths
    when the colon is properly escaped (single-quoting was unreliable in ffmpeg 8.x).
    """
    return p.replace("\\", "/").replace(":", "\\:")


def caption_filter(text: str, duration: float = 3.0) -> str | None:
    """小红书 / Reels-style caption: white rounded-look pill + bold black text + slide-up.

    - White semi-opaque box with generous padding gives the "sticker label" feel
    - Black bold text inside reads cleanly against any bg
    - Drop shadow on the box for depth
    - Slide-up + fade-in animation
    """
    if not text:
        return None
    abs_font = ROOT / FONT_PATH if not Path(FONT_PATH).is_absolute() else Path(FONT_PATH)
    if not abs_font.exists():
        fallback = ROOT / _FALLBACK_FONT_REL
        if fallback.exists():
            font_use = _FALLBACK_FONT_REL
        else:
            print(f"  ! font not found at {abs_font}, skipping caption '{text[:20]}'")
            return None
    else:
        font_use = FONT_PATH
    txt = escape_drawtext_text(text)
    if not txt:
        return None
    font = font_use.replace("\\", "/")

    n = len(text)
    if n <= 4:
        fontsize = 84
    elif n <= 8:
        fontsize = 64
    elif n <= 15:
        fontsize = 52
    else:
        fontsize = 40

    fade_in = 0.35
    fade_out = 0.4
    show_start = 0.1
    show_end = max(show_start + fade_in + 0.4, duration - fade_out)
    alpha_expr = (
        f"if(lt(t,{show_start}),0,"
        f"if(lt(t,{show_start + fade_in}),(t-{show_start})/{fade_in},"
        f"if(lt(t,{show_end}),1,"
        f"if(lt(t,{show_end + fade_out}),({show_end + fade_out}-t)/{fade_out},0))))"
    )
    # Slide-up: y starts 25px below final
    y_base = "h-310"
    y_expr = (
        f"if(lt(t,{show_start}),{y_base}+25,"
        f"if(lt(t,{show_start + fade_in}),{y_base}+25-25*((t-{show_start})/{fade_in}),"
        f"{y_base}))"
    )

    # Drop-shadow for the white pill: a darkened box behind, offset down
    shadow_y_expr = y_expr.replace(y_base, f"{y_base}+8")
    shadow_box = (
        f"drawtext=fontfile={font}:text='{txt}':fontsize={fontsize}:"
        f"x=(w-text_w)/2:y='{shadow_y_expr}':"
        f"fontcolor=black@0.0:"   # invisible text — we just want its box as shadow
        f"box=1:boxcolor=black@0.35:boxborderw=32:"
        f"alpha='{alpha_expr}'"
    )
    # The actual sticker: white box + bold black text
    main = (
        f"drawtext=fontfile={font}:text='{txt}':fontsize={fontsize}:"
        f"x=(w-text_w)/2:y='{y_expr}':"
        f"fontcolor=black:"
        f"box=1:boxcolor=white@0.92:boxborderw=32:"
        f"alpha='{alpha_expr}'"
    )
    return f"{shadow_box},{main}"


def ken_burns_filter(direction: str, duration: float) -> str:
    """DEPRECATED in v2.5+ — kept for video shot legacy path. Image shots now use
    compose_with_blur_bg() which produces a blurred-self background + sharp foreground,
    the standard TikTok/Reels treatment for any aspect ratio. Ken Burns motion happens
    on the bg path (subtle slow zoom) to avoid the slideshow feel without distorting fg.
    """
    n_frames = max(1, int(duration * FPS))
    if direction == "static":
        return f"scale={OUT_W}:{OUT_H}:force_original_aspect_ratio=decrease,pad={OUT_W}:{OUT_H}:(ow-iw)/2:(oh-ih)/2:black"
    pre = f"scale={OUT_W * 2}:{OUT_H * 2}:force_original_aspect_ratio=decrease,pad={OUT_W * 2}:{OUT_H * 2}:(ow-iw)/2:(oh-ih)/2:black"
    if direction == "zoom_in":
        zp = f"zoompan=z='min(zoom+0.0015,1.25)':d={n_frames}:s={OUT_W}x{OUT_H}:fps={FPS}"
    elif direction == "zoom_out":
        zp = f"zoompan=z='if(eq(on,0),1.25,max(zoom-0.0015,1.0))':d={n_frames}:s={OUT_W}x{OUT_H}:fps={FPS}"
    elif direction == "pan_left":
        zp = f"zoompan=z=1.15:x='iw-(iw/d)*on':y='ih/2-(ih/zoom/2)':d={n_frames}:s={OUT_W}x{OUT_H}:fps={FPS}"
    elif direction == "pan_right":
        zp = f"zoompan=z=1.15:x='(iw/d)*on':y='ih/2-(ih/zoom/2)':d={n_frames}:s={OUT_W}x{OUT_H}:fps={FPS}"
    elif direction == "pan_up":
        zp = f"zoompan=z=1.15:x='iw/2-(iw/zoom/2)':y='ih-(ih/d)*on':d={n_frames}:s={OUT_W}x{OUT_H}:fps={FPS}"
    elif direction == "pan_down":
        zp = f"zoompan=z=1.15:x='iw/2-(iw/zoom/2)':y='(ih/d)*on':d={n_frames}:s={OUT_W}x{OUT_H}:fps={FPS}"
    else:
        zp = f"scale={OUT_W}:{OUT_H}"
    return f"{pre},{zp}"


def compose_with_blur_bg(duration: float, ken_burns: str, input_label: str = "0:v") -> str:
    """Returns the OPENING segment of a filter_complex chain that takes [{input_label}]
    (an image or video stream) and produces [base] = a 1080x1920 frame with:
      - blurred, slightly darkened, slightly desaturated copy of the image as background
      - sharp original (with aspect preserved) centered on top
      - subtle slow zoom on the BG to add life without ruining the fg

    input_label: stream label to consume. Default "0:v" (raw video). For slow-motion
    we prepend a setpts filter and pass its output label here.
    """
    n_frames = max(1, int(duration * FPS))

    # Background: cover crop + blur + slight darken + very slow zoom (1.0 → 1.06)
    bg_chain = (
        f"scale={OUT_W}:{OUT_H}:force_original_aspect_ratio=increase,"
        f"crop={OUT_W}:{OUT_H},"
        f"boxblur=24:3,"
        f"eq=brightness=-0.08:saturation=0.85,"
        f"zoompan=z='min(zoom+0.00015*{FPS}/30,1.06)':d={n_frames}:s={OUT_W}x{OUT_H}:fps={FPS}"
    )

    # Foreground: fit-to-output, no padding, leave aspect alone. Optional gentle KB zoom.
    if ken_burns == "static":
        fg_chain = f"scale={OUT_W}:{OUT_H}:force_original_aspect_ratio=decrease,setsar=1"
    elif ken_burns == "zoom_in":
        # Pre-scale 1.4x of fit, then zoompan within that to gently push in
        fg_chain = (
            f"scale={int(OUT_W * 1.4)}:{int(OUT_H * 1.4)}:force_original_aspect_ratio=decrease,"
            f"setsar=1"
        )
    elif ken_burns == "zoom_out":
        fg_chain = (
            f"scale={int(OUT_W * 1.4)}:{int(OUT_H * 1.4)}:force_original_aspect_ratio=decrease,"
            f"setsar=1"
        )
    elif ken_burns in ("pan_left", "pan_right", "pan_up", "pan_down"):
        fg_chain = (
            f"scale={int(OUT_W * 1.25)}:{int(OUT_H * 1.25)}:force_original_aspect_ratio=decrease,"
            f"setsar=1"
        )
    else:
        fg_chain = f"scale={OUT_W}:{OUT_H}:force_original_aspect_ratio=decrease,setsar=1"

    return (
        f"[{input_label}]split=2[bgsrc][fgsrc];"
        f"[bgsrc]{bg_chain}[bg];"
        f"[fgsrc]{fg_chain}[fg];"
        f"[bg][fg]overlay=(W-w)/2:(H-h)/2[base]"
    )


def fade_filter(duration: float) -> str:
    fade_out_st = max(0.0, duration - FADE_DUR)
    return f"fade=t=in:st=0:d={FADE_DUR},fade=t=out:st={fade_out_st}:d={FADE_DUR}"


# xfade transition mapping (our schema name → ffmpeg xfade transition name)
XFADE_MAP = {
    "fade": "fade",
    "crossfade": "fade",
    "fadeblack": "fadeblack",
    "fadewhite": "fadewhite",        # 闪白：高光/惊艳/章节切换
    "slideleft": "slideleft",
    "slideright": "slideright",
    "slideup": "slideup",
    "circleopen": "circleopen",
    "circleclose": "circleclose",
    "wipeleft": "wipeleft",
    "wiperight": "wiperight",
    "zoomin": "zoomin",              # 冲击 zoom，配合 hook
    "smoothleft": "smoothleft",
    "smoothright": "smoothright",
}
XFADE_DEFAULT_DUR = 0.85   # generous default for breathy transitions
# Per-transition tuning. Without BGM beat-alignment, longer fades feel more pro.
XFADE_DUR_OVERRIDE = {
    "zoomin": 0.55,
    "fadewhite": 0.4,
    "fadeblack": 1.0,
    "circleopen": 0.7,
    "circleclose": 0.7,
    "smoothleft": 0.75,
    "smoothright": 0.75,
}
SOFT_CUT_DUR = 0.4   # was 0.18 — bumped so even "cut" intent gets visible breath


# Color grading: per-tone ffmpeg filter chain. Applied in render_*_shot before caption.
COLOR_GRADE_FILTERS = {
    "neutral": "",
    "warm": "colorbalance=rs=0.06:bs=-0.06,eq=saturation=1.06:contrast=1.04",
    "cool": "colorbalance=rs=-0.05:bs=0.07,eq=saturation=0.95:contrast=1.05",
    "vibrant": "eq=saturation=1.2:contrast=1.05",
    "muted": "eq=saturation=0.78:contrast=1.02",
    "cinematic_teal_orange": "colorbalance=rs=0.08:gs=-0.03:bs=-0.05:rh=-0.05:gh=0.0:bh=0.08,eq=contrast=1.08:saturation=1.05",
    "vintage": "curves=preset=vintage,eq=saturation=0.85:contrast=0.95",
}


def color_filter(color_grade: str) -> str:
    return COLOR_GRADE_FILTERS.get(color_grade, "")


def run_ffmpeg(args: list[str]) -> None:
    try:
        # cwd=ROOT so relative paths in filter args (e.g. fontfile=assets/fonts/msyh.ttc)
        # resolve correctly without exposing Windows drive-letter colons to ffmpeg's parser.
        subprocess.run(args, check=True, capture_output=True, cwd=str(ROOT))
    except subprocess.CalledProcessError as e:
        sys.stderr.write(f"\nffmpeg cmd failed:\n  {' '.join(args)}\n")
        sys.stderr.write(f"stderr: {e.stderr.decode('utf-8', errors='replace')}\n")
        raise


def _polish_chain(duration: float, color_grade: str, caption: str) -> str:
    """Post-base polish: color grade + film grain + vignette + fade + caption.

    Grain + vignette = the 'shot on a real camera' Reels/小红书 vibe that pure
    digital ffmpeg output lacks. Subtle (low intensity) so it's not gimmicky.
    """
    short_fade = 0.1
    fade_out_st = max(0.0, duration - short_fade)
    parts = []
    color = color_filter(color_grade)
    if color:
        parts.append(color)
    # Film grain — low strength, looks "shot on phone with character"
    parts.append("noise=alls=8:allf=t")
    # Vignette — soft darkening at edges
    parts.append(f"vignette=PI/4.5:mode=forward")
    # Internal fade for xfade handoff
    parts.append(f"fade=t=in:st=0:d={short_fade},fade=t=out:st={fade_out_st}:d={short_fade}")
    cap = caption_filter(caption, duration)
    if cap:
        parts.append(cap)
    return ",".join(parts)


def render_image_shot(image_path: Path, duration: float, ken_burns: str, caption: str, out_path: Path, color_grade: str = "neutral") -> tuple[Path, float]:
    """Image shot with blurred-self bg + sharp centered fg + grain + vignette.

    Returns (out_path, actual_duration). Image render always honors requested duration.
    """
    base_chain = compose_with_blur_bg(duration, ken_burns)
    polish = _polish_chain(duration, color_grade, caption)
    full_filter = f"{base_chain};[base]{polish}[v]"

    run_ffmpeg([
        "ffmpeg", "-y", "-loglevel", "error",
        "-loop", "1",
        "-i", str(image_path),
        "-filter_complex", full_filter,
        "-map", "[v]",
        "-t", f"{duration}",
        "-r", str(FPS),
        "-pix_fmt", "yuv420p",
        "-c:v", "libx264",
        "-preset", "veryfast",
        "-crf", "22",
        "-an",
        str(out_path),
    ])
    return out_path, duration


def _ffprobe_duration(video_path: Path) -> float:
    """Return clip length in seconds; 0 on failure."""
    try:
        r = subprocess.run(
            ["ffprobe", "-v", "error", "-show_entries", "format=duration",
             "-of", "default=noprint_wrappers=1:nokey=1", str(video_path)],
            check=True, capture_output=True, timeout=10,
        )
        return float(r.stdout.decode().strip())
    except (subprocess.CalledProcessError, ValueError, subprocess.TimeoutExpired, FileNotFoundError):
        return 0.0


def _ffprobe_fps(video_path: Path) -> float:
    """Return source frame rate; 30 on failure (safe default)."""
    try:
        r = subprocess.run(
            ["ffprobe", "-v", "error", "-select_streams", "v:0",
             "-show_entries", "stream=r_frame_rate",
             "-of", "default=noprint_wrappers=1:nokey=1", str(video_path)],
            check=True, capture_output=True, timeout=10,
        )
        s = r.stdout.decode().strip()
        if "/" in s:
            num, den = s.split("/")
            d = float(den)
            return float(num) / d if d else 30.0
        return float(s) if s else 30.0
    except (subprocess.CalledProcessError, ValueError, subprocess.TimeoutExpired, FileNotFoundError):
        return 30.0


MAX_SLOWMO_NORMAL = 1.5    # 30fps source → 1.5x is the visual ceiling
MAX_SLOWMO_HIGHFPS = 2.5   # 60+ fps source can take 2.5x without looking choppy
HIGHFPS_THRESHOLD = 48     # treat >=48fps as "filmed for slow motion"


def render_video_shot(
    video_path: Path,
    trim_start: float,
    duration: float,
    caption: str,
    out_path: Path,
    color_grade: str = "neutral",
) -> tuple[Path, float]:
    """Video shot with smart duration handling:
      - clip ≥ requested duration → play normal
      - clip < requested duration → SLOW-MO (setpts) up to 2.5x to preserve motion
      - clip needs > 2.5x stretch → cap output at clip * 2.5x

    This is what real editors do for short Live Photos: stretch them into 'cinematic'
    moments rather than freeze the last frame or cut prematurely. Returns (out_path,
    actual_rendered_duration).
    """
    real_len = _ffprobe_duration(video_path)
    available = max(0.1, real_len - trim_start - 0.05) if real_len > 0 else duration

    # Check source fps — high-fps clips (e.g. 60/120fps slo-mo footage) can be stretched
    # more aggressively because they have extra frames to slow down with native quality.
    src_fps = _ffprobe_fps(video_path)
    max_slowmo = MAX_SLOWMO_HIGHFPS if src_fps >= HIGHFPS_THRESHOLD else MAX_SLOWMO_NORMAL

    if duration <= available + 0.05:
        actual_duration = duration
        setpts_factor = 1.0
    else:
        stretch = duration / available
        if stretch > max_slowmo:
            actual_duration = available * max_slowmo
            setpts_factor = max_slowmo
            print(f"    → {video_path.name}: capped at {actual_duration:.2f}s ({max_slowmo}x max, src {src_fps:.0f}fps)")
        else:
            actual_duration = duration
            setpts_factor = stretch
            print(f"    → {video_path.name}: slow-mo {setpts_factor:.2f}x to fill {actual_duration:.2f}s (src {src_fps:.0f}fps)")

    # Slow-mo path: setpts stretches PTS, then we use minterpolate (blend mode) to
    # synthesize in-between frames so the output stays at FPS instead of dropping
    # effective framerate. Blend mode is fast and stable; mci was crashing on some
    # clips. At 1.8x stretch with blend, motion looks slightly soft but smooth.
    if setpts_factor != 1.0:
        target_fps = int(FPS)
        prefix = (
            f"[0:v]setpts={setpts_factor:.4f}*PTS,"
            f"minterpolate=fps={target_fps}:mi_mode=blend[v_speed]"
        )
        base_chain = compose_with_blur_bg(actual_duration, ken_burns="static", input_label="v_speed")
        base_chain = f"{prefix};{base_chain}"
    else:
        base_chain = compose_with_blur_bg(actual_duration, ken_burns="static")

    polish = _polish_chain(actual_duration, color_grade, caption)
    full_filter = f"{base_chain};[base]{polish}[v]"

    run_ffmpeg([
        "ffmpeg", "-y", "-loglevel", "error",
        "-ss", f"{trim_start}",
        "-i", str(video_path),
        "-filter_complex", full_filter,
        "-map", "[v]",
        "-t", f"{actual_duration}",
        "-r", str(FPS),
        "-pix_fmt", "yuv420p",
        "-c:v", "libx264",
        "-preset", "veryfast",
        "-crf", "22",
        "-an",
        str(out_path),
    ])
    return out_path, actual_duration


def concat_and_add_bgm(
    shot_paths: list[Path],
    bgm_path: Path | None,
    out_path: Path,
    total_duration: float,
) -> None:
    """Legacy concat (no per-shot xfade). Kept for backwards compat with step7_render's
    older code path. New code should use concat_with_xfade for real transitions."""
    list_file = out_path.with_suffix(".txt")
    list_file.write_text("\n".join(f"file '{p.as_posix()}'" for p in shot_paths), encoding="utf-8")

    if bgm_path and bgm_path.exists():
        bgm_fade_st = max(0.0, total_duration - 1.5)
        run_ffmpeg([
            "ffmpeg", "-y", "-loglevel", "error",
            "-f", "concat", "-safe", "0", "-i", str(list_file),
            "-stream_loop", "-1", "-i", str(bgm_path),
            "-map", "0:v", "-map", "1:a",
            "-c:v", "copy",
            "-c:a", "aac", "-b:a", "128k",
            "-af", f"afade=t=in:st=0:d=0.5,afade=t=out:st={bgm_fade_st}:d=1.5",
            "-shortest",
            str(out_path),
        ])
    else:
        run_ffmpeg([
            "ffmpeg", "-y", "-loglevel", "error",
            "-f", "concat", "-safe", "0", "-i", str(list_file),
            "-c:v", "copy",
            "-an",
            str(out_path),
        ])

    list_file.unlink(missing_ok=True)


def concat_with_xfade(
    shot_paths: list[Path],
    shot_durations: list[float],
    transitions: list[str],
    bgm_path: Path | None,
    out_path: Path,
) -> None:
    """Build an xfade chain so each shot transitions into the next with director-specified style.

    transitions[i] = how shot i fades in from shot i-1. transitions[0] is unused.
    Total output duration = sum(durations) - sum(xfade_durations between consecutive shots).
    """
    n = len(shot_paths)
    if n == 0:
        raise ValueError("no shots to concat")

    # Build inputs
    inputs = []
    for p in shot_paths:
        inputs.extend(["-i", str(p)])
    bgm_idx = n
    if bgm_path and bgm_path.exists():
        inputs.extend(["-stream_loop", "-1", "-i", str(bgm_path)])

    # Build xfade chain
    if n == 1:
        filter_chain = "[0:v]null[v]"
        total_dur = shot_durations[0]
    else:
        chain_parts = []
        prev_label = "0:v"
        cur_offset = shot_durations[0]  # duration of first segment
        for i in range(1, n):
            t = transitions[i] if i < len(transitions) else "fade"
            if t == "cut":
                xf, xf_dur = "fade", SOFT_CUT_DUR  # soft cut: micro-fade for breath
            else:
                xf = XFADE_MAP.get(t, "fade")
                xf_dur = XFADE_DUR_OVERRIDE.get(xf, XFADE_DEFAULT_DUR)
            offset = max(0.01, cur_offset - xf_dur)
            out_lbl = "v" if i == n - 1 else f"v{i}"
            chain_parts.append(
                f"[{prev_label}][{i}:v]xfade=transition={xf}:duration={xf_dur}:offset={offset:.3f}[{out_lbl}]"
            )
            prev_label = out_lbl
            cur_offset = offset + xf_dur + (shot_durations[i] - xf_dur)
        filter_chain = ";".join(chain_parts)
        total_dur = cur_offset

    # Add BGM with fade in/out
    if bgm_path and bgm_path.exists():
        bgm_fade_st = max(0.0, total_dur - 1.5)
        full_filter = (
            f"{filter_chain};"
            f"[{bgm_idx}:a]afade=t=in:st=0:d=0.5,afade=t=out:st={bgm_fade_st}:d=1.5,"
            f"atrim=duration={total_dur:.3f}[a]"
        )
        cmd = [
            "ffmpeg", "-y", "-loglevel", "error",
            *inputs,
            "-filter_complex", full_filter,
            "-map", "[v]", "-map", "[a]",
            "-c:v", "libx264", "-preset", "veryfast", "-crf", "22",
            "-pix_fmt", "yuv420p",
            "-c:a", "aac", "-b:a", "128k",
            str(out_path),
        ]
    else:
        cmd = [
            "ffmpeg", "-y", "-loglevel", "error",
            *inputs,
            "-filter_complex", filter_chain,
            "-map", "[v]",
            "-c:v", "libx264", "-preset", "veryfast", "-crf", "22",
            "-pix_fmt", "yuv420p",
            "-an",
            str(out_path),
        ]
    run_ffmpeg(cmd)


def find_bgm(style_name: str) -> Path | None:
    """Look for a BGM file: first match `<style_name>.mp3` (event_id or style),
    else fall back to the first mp3 in assets/bgm/."""
    candidates: list[Path] = []
    for ext in ("*.mp3", "*.m4a", "*.aac", "*.wav"):
        candidates.extend(ASSETS_BGM_DIR.glob(ext))
    if not candidates:
        return None
    # Exact match by stem (e.g. "evt_004.mp3" or "warm.mp3")
    for c in candidates:
        if c.stem.lower() == style_name.lower():
            return c
    return sorted(candidates)[0]


def find_bgm_for_event(event_id: str, color_grade: str = "neutral") -> Path | None:
    """Per-event BGM lookup: prefer assets/bgm/<event_id>.mp3 (composed by step6b),
    then color_grade match, then any mp3."""
    direct = ASSETS_BGM_DIR / f"{event_id}.mp3"
    if direct.exists():
        return direct
    return find_bgm(color_grade)


# M0's render_candidate / main() were removed in v2 — step7_render.py is the new
# entry point that consumes Timeline (the v2 schema). This module now only exports
# render helpers used by both step7_render and any future renderer.


if __name__ == "__main__":
    main()
