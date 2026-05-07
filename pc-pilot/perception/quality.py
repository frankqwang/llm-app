"""Cheap quality signals: blur, exposure, solid-color, screenshot heuristic."""
from __future__ import annotations

from pathlib import Path
from typing import Literal

import cv2
import numpy as np

BLUR_THRESHOLD = 80.0  # Laplacian variance below this = blurry
EXPOSURE_DARK = 50  # mean L below = under
EXPOSURE_BRIGHT = 205  # mean L above = over
SOLID_COLOR_STD = 8.0  # pixel std below this = nearly uniform image


def analyze(img_path: Path) -> dict:
    """Read once, return dict with: blur_score, exposure, is_solid_color, is_screenshot.

    Uses a small downscaled copy (max 512px) for speed.
    """
    img = cv2.imread(str(img_path), cv2.IMREAD_COLOR)
    if img is None:
        # Pillow fallback for HEIC etc.
        from PIL import Image

        try:
            from pillow_heif import register_heif_opener

            register_heif_opener()
        except ImportError:
            pass
        pil = Image.open(img_path).convert("RGB")
        img = cv2.cvtColor(np.array(pil), cv2.COLOR_RGB2BGR)

    h, w = img.shape[:2]
    if max(h, w) > 512:
        scale = 512 / max(h, w)
        img = cv2.resize(img, (int(w * scale), int(h * scale)))

    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)

    # Blur: variance of Laplacian
    blur_score = float(cv2.Laplacian(gray, cv2.CV_64F).var())

    # Exposure: mean luminance
    mean_l = float(gray.mean())
    if mean_l < EXPOSURE_DARK:
        exposure: Literal["under", "ok", "over"] = "under"
    elif mean_l > EXPOSURE_BRIGHT:
        exposure = "over"
    else:
        exposure = "ok"

    # Solid color: std across all channels small
    is_solid_color = float(img.std()) < SOLID_COLOR_STD

    # Screenshot heuristic: extreme aspect ratio + low texture variance
    aspect = w / h if h > 0 else 1.0
    is_phone_aspect = aspect < 0.55 or aspect > 1.85
    edge_density = float(cv2.Canny(gray, 50, 150).mean())
    is_screenshot = bool(is_phone_aspect and edge_density < 6.0)

    return {
        "blur_score": blur_score,
        "exposure": exposure,
        "is_solid_color": bool(is_solid_color),
        "is_screenshot": is_screenshot,
    }
