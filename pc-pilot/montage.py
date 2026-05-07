"""Contact sheet (montage) generator for event-level VLM browsing.

Lays out N images in a square-ish grid with each cell numbered (1..N) so a VLM can
reference specific images by index in its analysis. Lives at module-level so both
step3_montage and downstream debug tooling can call it.
"""
from __future__ import annotations

import math
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

try:
    from pillow_heif import register_heif_opener

    register_heif_opener()
except ImportError:
    pass


def _load_font(size: int) -> ImageFont.ImageFont:
    """Best-effort CJK-capable font for index overlays."""
    candidates = [
        Path(__file__).parent / "assets" / "fonts" / "msyh.ttc",
        Path("C:/Windows/Fonts/msyh.ttc"),
        Path("C:/Windows/Fonts/arial.ttf"),
        Path("/System/Library/Fonts/PingFang.ttc"),
        Path("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"),
    ]
    for p in candidates:
        if p.exists():
            try:
                return ImageFont.truetype(str(p), size)
            except OSError:
                continue
    return ImageFont.load_default()


def _draw_index_badge(img: Image.Image, idx: int, badge_size: int = 64) -> None:
    """Overlay a numbered badge in the top-left corner of the image."""
    draw = ImageDraw.Draw(img, "RGBA")
    pad = badge_size // 6
    # Black rounded rect background
    draw.rounded_rectangle(
        (pad, pad, pad + badge_size, pad + badge_size),
        radius=badge_size // 4,
        fill=(0, 0, 0, 200),
    )
    font = _load_font(int(badge_size * 0.65))
    text = str(idx)
    bbox = draw.textbbox((0, 0), text, font=font)
    tw, th = bbox[2] - bbox[0], bbox[3] - bbox[1]
    tx = pad + (badge_size - tw) // 2 - bbox[0]
    ty = pad + (badge_size - th) // 2 - bbox[1]
    draw.text((tx, ty), text, font=font, fill=(255, 255, 255, 255))


def _resize_to_cell(img: Image.Image, cell_w: int, cell_h: int, bg=(20, 20, 20)) -> Image.Image:
    """Letterbox-resize an image into a fixed cell while preserving aspect ratio."""
    img = img.convert("RGB")
    iw, ih = img.size
    scale = min(cell_w / iw, cell_h / ih)
    nw, nh = max(1, int(iw * scale)), max(1, int(ih * scale))
    resized = img.resize((nw, nh), Image.LANCZOS)
    cell = Image.new("RGB", (cell_w, cell_h), bg)
    cell.paste(resized, ((cell_w - nw) // 2, (cell_h - nh) // 2))
    return cell


def build_contact_sheet(
    image_paths: list[Path],
    out_path: Path,
    *,
    cell_w: int = 360,
    cell_h: int = 640,
    max_side: int = 4096,
    gap: int = 8,
    bg=(20, 20, 20),
) -> tuple[Path, int, int]:
    """Render image_paths into one contact sheet with numbered cells.

    Returns (out_path, cols, rows). Cell numbering is 1-based, row-major (left→right, top→bottom).

    If the resulting sheet would exceed max_side on either dimension, cell sizes are
    auto-scaled down to fit. Caller can split into multiple sheets if needed.
    """
    n = len(image_paths)
    if n == 0:
        raise ValueError("no images to render")

    cols = math.ceil(math.sqrt(n))
    rows = math.ceil(n / cols)

    # Auto-scale cells if total exceeds max_side.
    total_w = cols * cell_w + (cols + 1) * gap
    total_h = rows * cell_h + (rows + 1) * gap
    scale = min(1.0, max_side / total_w, max_side / total_h)
    if scale < 1.0:
        cell_w = max(160, int(cell_w * scale))
        cell_h = max(284, int(cell_h * scale))
        total_w = cols * cell_w + (cols + 1) * gap
        total_h = rows * cell_h + (rows + 1) * gap

    sheet = Image.new("RGB", (total_w, total_h), bg)
    badge_size = max(36, cell_w // 5)

    for i, p in enumerate(image_paths):
        try:
            src = Image.open(p)
        except Exception as e:
            print(f"  ! contact_sheet: skip {p.name}: {type(e).__name__}: {e}")
            cell = Image.new("RGB", (cell_w, cell_h), (60, 0, 0))
        else:
            cell = _resize_to_cell(src, cell_w, cell_h, bg=bg)
        _draw_index_badge(cell, i + 1, badge_size=badge_size)
        col = i % cols
        row = i // cols
        x = gap + col * (cell_w + gap)
        y = gap + row * (cell_h + gap)
        sheet.paste(cell, (x, y))

    out_path.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(out_path, format="JPEG", quality=88)
    return out_path, cols, rows


def build_contact_sheets_chunked(
    image_paths: list[Path],
    out_dir: Path,
    name_prefix: str,
    *,
    max_per_sheet: int = 36,
    **kwargs,
) -> list[tuple[Path, list[int]]]:
    """If N > max_per_sheet, split into multiple sheets. Returns [(sheet_path, [global_indices])].

    global_indices are 1-based positions in the original image_paths list, so the VLM's
    per-sheet local 1..K refers to these.
    """
    out_dir.mkdir(parents=True, exist_ok=True)
    out: list[tuple[Path, list[int]]] = []
    for i in range(0, len(image_paths), max_per_sheet):
        chunk = image_paths[i : i + max_per_sheet]
        global_idx = list(range(i + 1, i + 1 + len(chunk)))
        sheet_path = out_dir / f"{name_prefix}_part{i // max_per_sheet + 1:02d}.jpg"
        build_contact_sheet(chunk, sheet_path, **kwargs)
        out.append((sheet_path, global_idx))
    return out
