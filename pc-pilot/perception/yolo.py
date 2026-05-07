"""YOLOv11n object detection wrapper. Used for: person count, document/screen filter, content tagging."""
from __future__ import annotations

import os
from pathlib import Path
from threading import Lock

from ultralytics import YOLO

# Lazy singleton: loading the model takes a couple seconds, we want one per process.
_model = None
_lock = Lock()
_MODEL_NAME = os.environ.get("YOLO_MODEL", "yolo11n.pt")  # auto-downloaded by ultralytics if missing


def _resolve_device() -> str:
    explicit = os.environ.get("YOLO_DEVICE")
    if explicit:
        return explicit
    try:
        import torch

        return "cuda:0" if torch.cuda.is_available() else "cpu"
    except ImportError:
        return "cpu"


_DEVICE = _resolve_device()

# COCO classes that strongly suggest "this is a screenshot/document/UI", not a vlog-worthy moment.
DOCUMENT_CLASSES = {"laptop", "tv", "cell phone", "book", "keyboard", "mouse", "monitor"}


def _get_model() -> YOLO:
    global _model
    with _lock:
        if _model is None:
            _model = YOLO(_MODEL_NAME)
        return _model


def detect(img_path: Path) -> dict:
    """Returns: yolo_classes (unique COCO names), yolo_person_count, is_document (heuristic)."""
    model = _get_model()
    results = model(str(img_path), device=_DEVICE, verbose=False, conf=0.35)
    if not results:
        return {"yolo_classes": [], "yolo_person_count": 0, "is_document": False}

    r = results[0]
    classes = []
    person_count = 0
    document_hits = 0
    total = 0

    if r.boxes is not None and r.names is not None:
        for cls_idx in r.boxes.cls.tolist():
            name = r.names[int(cls_idx)]
            classes.append(name)
            total += 1
            if name == "person":
                person_count += 1
            if name in DOCUMENT_CLASSES:
                document_hits += 1

    is_document = bool(total > 0 and document_hits / total >= 0.6)

    return {
        "yolo_classes": sorted(set(classes)),
        "yolo_person_count": person_count,
        "is_document": is_document,
    }
