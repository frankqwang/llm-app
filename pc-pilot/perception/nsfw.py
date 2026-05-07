"""NSFW image classifier using HuggingFace transformers + Falconsai/nsfw_image_detection.

Lightweight ViT classifier (~300MB). Returns score 0-1 where higher = more likely NSFW.
Used in step1_perceive to gate explicit content out of the candidate pool entirely.
"""
from __future__ import annotations

import os
from pathlib import Path
from threading import Lock

import torch
from PIL import Image

try:
    from pillow_heif import register_heif_opener

    register_heif_opener()
except ImportError:
    pass

_MODEL = os.environ.get("NSFW_MODEL", "Falconsai/nsfw_image_detection")
_state: dict = {}
_lock = Lock()
NSFW_THRESHOLD = float(os.environ.get("NSFW_THRESHOLD", 0.6))


def _get_pipeline():
    global _state
    with _lock:
        if "pipe" not in _state:
            from transformers import pipeline

            device = 0 if torch.cuda.is_available() else -1
            _state["pipe"] = pipeline("image-classification", model=_MODEL, device=device)
        return _state["pipe"]


def score(img_path: Path) -> float:
    """Return NSFW probability in [0, 1]. 0 on failure (don't false-positive)."""
    try:
        pipe = _get_pipeline()
        img = Image.open(img_path).convert("RGB")
        results = pipe(img)
        # results: [{"label": "nsfw"|"normal", "score": float}, ...]
        for r in results:
            if r.get("label", "").lower() == "nsfw":
                return float(r["score"])
        return 0.0
    except Exception as e:
        print(f"  ! nsfw scoring failed for {img_path.name}: {type(e).__name__}: {e}")
        return 0.0
