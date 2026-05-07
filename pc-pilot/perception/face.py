"""Face detection + embedding via InsightFace, then global DBSCAN clustering for stable person_ids.

Two-phase API:
  1. detect_and_embed(asset_id, img_path) -> per-asset list of (bbox, det_score, embedding)
  2. cluster(all_records) -> assigns person_id to each face by cosine DBSCAN

person_id is alphabetic ('A', 'B', 'C', ...) ranked by cluster size (most frequent person = 'A').
Faces in noise (DBSCAN label = -1) get person_id = None.
"""
from __future__ import annotations

import os
from pathlib import Path
from threading import Lock

import cv2
import numpy as np
from sklearn.cluster import DBSCAN

# Lazy singleton.
_app = None
_lock = Lock()
_MODEL_NAME = os.environ.get("INSIGHTFACE_MODEL", "buffalo_l")


def _resolve_providers() -> tuple[list[str], int]:
    """Return (providers list, ctx_id). Auto-fallback to CPU when CUDA isn't actually usable."""
    explicit = os.environ.get("INSIGHTFACE_PROVIDER")
    if explicit:
        return [explicit, "CPUExecutionProvider"], 0
    cuda_ok = False
    try:
        import torch

        cuda_ok = torch.cuda.is_available()
    except ImportError:
        pass
    if cuda_ok:
        try:
            import onnxruntime as ort

            if "CUDAExecutionProvider" in ort.get_available_providers():
                return ["CUDAExecutionProvider", "CPUExecutionProvider"], 0
        except ImportError:
            pass
    return ["CPUExecutionProvider"], -1


def _get_app():
    global _app
    with _lock:
        if _app is None:
            from insightface.app import FaceAnalysis

            providers, ctx_id = _resolve_providers()
            _app = FaceAnalysis(name=_MODEL_NAME, providers=providers)
            _app.prepare(ctx_id=ctx_id, det_size=(640, 640))
        return _app


def detect_and_embed(img_path: Path) -> list[dict]:
    """Returns list of {bbox_norm, det_score, embedding (np.ndarray, L2-normalized)}.

    bbox_norm: (x1, y1, x2, y2) normalized to [0, 1].
    """
    img = cv2.imread(str(img_path), cv2.IMREAD_COLOR)
    if img is None:
        from PIL import Image

        try:
            from pillow_heif import register_heif_opener

            register_heif_opener()
        except ImportError:
            pass
        pil = Image.open(img_path).convert("RGB")
        img = cv2.cvtColor(np.array(pil), cv2.COLOR_RGB2BGR)

    h, w = img.shape[:2]
    app = _get_app()
    faces = app.get(img)

    out = []
    for f in faces:
        x1, y1, x2, y2 = f.bbox
        bbox_norm = (
            float(max(0, x1) / w),
            float(max(0, y1) / h),
            float(min(w, x2) / w),
            float(min(h, y2) / h),
        )
        emb = f.normed_embedding.astype(np.float32)  # already L2-normalized
        out.append(
            {
                "bbox_norm": bbox_norm,
                "det_score": float(f.det_score),
                "embedding": emb,
            }
        )
    return out


def cluster_faces(
    records: list[tuple[str, dict]],
    *,
    eps: float = 0.4,
    min_samples: int = 3,
) -> dict[int, str | None]:
    """Cluster faces globally by cosine distance.

    Args:
        records: list of (asset_id, face_dict) where face_dict has 'embedding'.
                 The list index becomes the key in the returned dict.
        eps: DBSCAN eps in cosine distance space (1 - cosine_similarity).
             0.4 ~ cosine sim 0.6, reasonable for buffalo_l embeddings.
        min_samples: min cluster size; faces with cluster size below this are noise.

    Returns:
        {record_index: person_id_str_or_None}.
        person_id is 'A', 'B', 'C', ... ranked by cluster size descending.
    """
    if not records:
        return {}

    embeddings = np.stack([r[1]["embedding"] for r in records])
    # Cosine distance = 1 - cosine_similarity. Embeddings are already L2-normalized,
    # so cosine_similarity = dot product, distance = 1 - dot.
    # DBSCAN with metric='cosine' computes this internally.
    db = DBSCAN(eps=eps, min_samples=min_samples, metric="cosine", n_jobs=-1)
    labels = db.fit_predict(embeddings)

    # Rank clusters by size (excluding noise label -1)
    unique, counts = np.unique(labels[labels >= 0], return_counts=True)
    order = np.argsort(-counts)  # descending
    label_to_pid: dict[int, str] = {}
    for rank, idx in enumerate(order):
        cluster_label = int(unique[idx])
        # 'A', 'B', ..., 'Z', 'AA', 'AB' (rarely needed)
        if rank < 26:
            pid = chr(ord("A") + rank)
        else:
            pid = chr(ord("A") + (rank // 26) - 1) + chr(ord("A") + (rank % 26))
        label_to_pid[cluster_label] = pid

    return {i: label_to_pid.get(int(lbl)) for i, lbl in enumerate(labels)}
