"""CLIP / SigLIP image embeddings. Used downstream for: dedup, visual clustering, event segmentation.

Embeddings are L2-normalized so cosine similarity = dot product. Stored in workspace/clip_embeddings.npy
as a float32 (N, dim) matrix; the index in this matrix is recorded in Perception.clip_embedding_idx.
"""
from __future__ import annotations

import os
from pathlib import Path
from threading import Lock

import numpy as np
import torch
from PIL import Image

try:
    from pillow_heif import register_heif_opener

    register_heif_opener()
except ImportError:
    pass

# Lazy singleton.
_state: dict = {}
_lock = Lock()
_MODEL = os.environ.get("CLIP_MODEL", "ViT-SO400M-14-SigLIP-384")  # SigLIP shape-optimized
_PRETRAINED = os.environ.get("CLIP_PRETRAINED", "webli")
_DEVICE = "cuda:0" if torch.cuda.is_available() else "cpu"


def _get_model():
    global _state
    with _lock:
        if not _state:
            import open_clip

            model, _, preprocess = open_clip.create_model_and_transforms(
                _MODEL, pretrained=_PRETRAINED, device=_DEVICE
            )
            model.eval()
            _state["model"] = model
            _state["preprocess"] = preprocess
            with torch.no_grad():
                # warm-up + sniff embedding dim
                dummy = torch.zeros(1, 3, 384, 384, device=_DEVICE)
                _state["dim"] = int(model.encode_image(dummy).shape[-1])
        return _state["model"], _state["preprocess"]


def embed(img_path: Path) -> np.ndarray:
    """Returns a single L2-normalized embedding vector (np.float32, shape (dim,))."""
    model, preprocess = _get_model()
    img = Image.open(img_path).convert("RGB")
    tensor = preprocess(img).unsqueeze(0).to(_DEVICE)
    with torch.no_grad():
        feat = model.encode_image(tensor)
        feat = feat / feat.norm(dim=-1, keepdim=True)
    return feat.cpu().numpy().astype(np.float32)[0]


def embed_batch(img_paths: list[Path], batch_size: int = 16) -> np.ndarray:
    """Vectorized: returns (N, dim) float32, L2-normalized, in input order."""
    model, preprocess = _get_model()
    out = []
    for i in range(0, len(img_paths), batch_size):
        batch_paths = img_paths[i : i + batch_size]
        tensors = []
        for p in batch_paths:
            try:
                img = Image.open(p).convert("RGB")
                tensors.append(preprocess(img))
            except Exception as e:
                print(f"  ! clip skip {p.name}: {type(e).__name__}: {e}")
                tensors.append(torch.zeros(3, 384, 384))  # placeholder; downstream filter handles
        batch = torch.stack(tensors).to(_DEVICE)
        with torch.no_grad():
            feat = model.encode_image(batch)
            feat = feat / feat.norm(dim=-1, keepdim=True)
        out.append(feat.cpu().numpy().astype(np.float32))
    return np.concatenate(out, axis=0)


def _get_tokenizer():
    if "tokenizer" not in _state:
        import open_clip

        _state["tokenizer"] = open_clip.get_tokenizer(_MODEL)
    return _state["tokenizer"]


def embed_text(text: str | list[str]) -> np.ndarray:
    """Encode text via SigLIP/CLIP text encoder. Returns L2-normalized embedding(s).

    Used by step5_editor to compute text-image similarity for shot recall.
    Single str -> shape (dim,). List[str] -> shape (N, dim).
    """
    model, _ = _get_model()
    tokenizer = _get_tokenizer()
    is_single = isinstance(text, str)
    texts = [text] if is_single else list(text)
    tokens = tokenizer(texts).to(_DEVICE)
    with torch.no_grad():
        feat = model.encode_text(tokens)
        feat = feat / feat.norm(dim=-1, keepdim=True)
    arr = feat.cpu().numpy().astype(np.float32)
    return arr[0] if is_single else arr
