"""Step 1: run all 4 perception tools over every asset, then cluster faces globally.

Pipeline:
  for each asset:
    - if has video: detect shots + extract keyframes
    - pick a representative image (still photo, or first shot keyframe for video-only)
    - run quality + YOLO + face detect/embed on representative
  batch:
    - CLIP embeddings for all representatives (saves to clip_embeddings.npy)
    - global DBSCAN on all face embeddings -> assigns person_id
  write perception.jsonl

Note on GPU: step1 loads YOLO + InsightFace + CLIP simultaneously (~2-3 GB).
You should NOT have vLLM running at the same time on a 16 GB card. Run order:
  step0 -> step1 (vLLM off) -> step2 -> [start vLLM] -> step3 -> step4 -> step5 -> step6
"""
from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
from tqdm import tqdm

from perception import clip_emb, face, nsfw, quality, scene, yolo
from schemas import Asset, FaceInstance, Perception

ROOT = Path(__file__).parent
WORKSPACE = ROOT / "workspace"


def perceive_one(asset: Asset) -> tuple[Perception, list[dict], Path | None]:
    """Returns (Perception, face records for global clustering, representative image path for CLIP).

    face records: list of {"asset_id", "bbox_norm", "det_score", "embedding"}.
    """
    shots = []
    if asset.video_path:
        shots = scene.detect_shots(asset.asset_id, ROOT / asset.video_path)

    # Representative image for quality/YOLO/face/CLIP
    rep_img: Path | None = None
    if asset.image_path:
        rep_img = ROOT / asset.image_path
    elif shots:
        rep_img = ROOT / shots[0].keyframe_path

    if rep_img is None or not rep_img.exists():
        return (
            Perception(
                asset_id=asset.asset_id,
                blur_score=0.0,
                exposure="ok",
                is_solid_color=False,
                is_screenshot=False,
                is_document=False,
                is_junk=True,
                shots=shots,
            ),
            [],
            None,
        )

    q = quality.analyze(rep_img)
    y = yolo.detect(rep_img)
    face_recs_raw = face.detect_and_embed(rep_img)
    face_recs = [{"asset_id": asset.asset_id, **fr} for fr in face_recs_raw]

    nsfw_score = nsfw.score(rep_img)
    is_nsfw = nsfw_score >= nsfw.NSFW_THRESHOLD

    is_junk = bool(
        q["blur_score"] < quality.BLUR_THRESHOLD
        or q["is_solid_color"]
        or q["is_screenshot"]
        or y["is_document"]
        or is_nsfw
    )

    perception = Perception(
        asset_id=asset.asset_id,
        blur_score=q["blur_score"],
        exposure=q["exposure"],
        is_solid_color=q["is_solid_color"],
        is_screenshot=q["is_screenshot"],
        is_document=y["is_document"],
        nsfw_score=nsfw_score,
        is_nsfw=is_nsfw,
        is_junk=is_junk,
        yolo_classes=y["yolo_classes"],
        yolo_person_count=y["yolo_person_count"],
        faces=[],
        clip_embedding_idx=None,
        shots=shots,
    )
    return perception, face_recs, rep_img


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--limit", type=int, default=None, help="only perceive first N assets")
    parser.add_argument(
        "--skip",
        nargs="*",
        default=[],
        choices=["yolo", "face", "clip", "scene"],
        help="skip selected perception components (for debugging or smaller GPU)",
    )
    args = parser.parse_args()

    assets_path = WORKSPACE / "assets.jsonl"
    if not assets_path.exists():
        raise SystemExit("workspace/assets.jsonl missing. Run step0_ingest.py first.")

    assets: list[Asset] = []
    with assets_path.open("r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                assets.append(Asset.model_validate_json(line))
    if args.limit:
        assets = assets[: args.limit]

    print(f"[step1] perceiving {len(assets)} assets (skip={args.skip})")

    perceptions: list[Perception] = []
    all_face_recs: list[dict] = []
    rep_paths: list[Path] = []
    asset_to_clip_idx: dict[str, int] = {}

    for a in tqdm(assets, desc="per-asset"):
        try:
            p, face_recs, rep = perceive_one(a)
        except Exception as e:
            print(f"  ! perceive failed for {a.asset_id}: {type(e).__name__}: {e}")
            continue

        perceptions.append(p)
        if "face" not in args.skip:
            all_face_recs.extend(face_recs)
        if "clip" not in args.skip and rep is not None:
            asset_to_clip_idx[a.asset_id] = len(rep_paths)
            rep_paths.append(rep)

    # Batch CLIP embeddings.
    if "clip" not in args.skip and rep_paths:
        print(f"[step1] CLIP embedding {len(rep_paths)} assets")
        embs = clip_emb.embed_batch(rep_paths)
        np.save(WORKSPACE / "clip_embeddings.npy", embs)
        for p in perceptions:
            idx = asset_to_clip_idx.get(p.asset_id, -1)
            if idx >= 0:
                p.clip_embedding_idx = idx

    # Global face clustering.
    if "face" not in args.skip and all_face_recs:
        print(f"[step1] clustering {len(all_face_recs)} faces (DBSCAN, cosine)")
        records_for_clustering = [(r["asset_id"], r) for r in all_face_recs]
        person_ids = face.cluster_faces(records_for_clustering)

        face_map: dict[str, list[FaceInstance]] = {}
        for i, (aid, rec) in enumerate(records_for_clustering):
            face_map.setdefault(aid, []).append(
                FaceInstance(
                    bbox=rec["bbox_norm"],
                    person_id=person_ids[i],
                    det_score=rec["det_score"],
                    embedding_norm=float(np.linalg.norm(rec["embedding"])),
                )
            )
        for p in perceptions:
            p.faces = face_map.get(p.asset_id, [])

    out = WORKSPACE / "perception.jsonl"
    with out.open("w", encoding="utf-8") as f:
        for p in perceptions:
            f.write(p.model_dump_json() + "\n")

    n_junk = sum(1 for p in perceptions if p.is_junk)
    n_with_video = sum(1 for p in perceptions if p.shots)
    n_with_faces = sum(1 for p in perceptions if p.faces)
    persons = {fc.person_id for p in perceptions for fc in p.faces if fc.person_id}
    print(
        f"[step1] {len(perceptions)} perceptions -> {out}\n"
        f"        junk={n_junk}/{len(perceptions)}  video_with_shots={n_with_video}\n"
        f"        with_faces={n_with_faces}  unique_persons={len(persons)} ({sorted(persons)})"
    )


if __name__ == "__main__":
    main()
