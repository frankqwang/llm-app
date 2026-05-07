"""Step 0: scan inbox/, identify each asset's media type, pair Live Photos, extract EXIF.

A Live Photo on iOS is a HEIC + MOV pair sharing a basename (IMG_1234.HEIC + IMG_1234.MOV).
On Android, motion photos embed video inside the JPEG/HEIC; we treat them as still images
(M0 doesn't extract embedded motion - left for a later iteration).

Output: workspace/assets.jsonl (one Asset per row, ordered by EXIF datetime ascending).
"""
from __future__ import annotations

import argparse
import json
from datetime import datetime
from pathlib import Path

import av
from PIL import ExifTags, Image
from tqdm import tqdm

try:
    from pillow_heif import register_heif_opener

    register_heif_opener()
except ImportError:
    pass

from schemas import Asset

ROOT = Path(__file__).parent
INBOX = ROOT / "inbox"
WORKSPACE = ROOT / "workspace"

IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".webp", ".heic", ".heif"}
VIDEO_EXTS = {".mp4", ".mov", ".m4v", ".avi", ".mkv"}


def _dms_to_deg(dms, ref: str) -> float:
    deg = float(dms[0]) + float(dms[1]) / 60 + float(dms[2]) / 3600
    return -deg if ref in ("S", "W") else deg


def read_image_meta(path: Path) -> tuple[int, int, str | None, tuple[float, float] | None, str | None]:
    """Return (width, height, iso_datetime, gps, camera_model)."""
    img = Image.open(path)
    width, height = img.size
    exif_raw = getattr(img, "_getexif", lambda: None)() or {}
    exif = {ExifTags.TAGS.get(k, k): v for k, v in exif_raw.items()}

    dt = None
    raw_dt = exif.get("DateTimeOriginal") or exif.get("DateTime")
    if raw_dt:
        try:
            dt = datetime.strptime(raw_dt, "%Y:%m:%d %H:%M:%S").isoformat()
        except (ValueError, TypeError):
            dt = None

    gps = None
    gps_raw = exif.get("GPSInfo")
    if gps_raw:
        try:
            tags = {ExifTags.GPSTAGS.get(k, k): v for k, v in gps_raw.items()}
            lat = _dms_to_deg(tags["GPSLatitude"], tags.get("GPSLatitudeRef", "N"))
            lon = _dms_to_deg(tags["GPSLongitude"], tags.get("GPSLongitudeRef", "E"))
            gps = (lat, lon)
        except (KeyError, TypeError, ValueError):
            gps = None

    camera = exif.get("Model")
    if camera:
        camera = str(camera).strip()

    return width, height, dt, gps, camera


def read_video_meta(path: Path) -> tuple[int, int, float, str | None, tuple[float, float] | None]:
    """Return (width, height, duration_sec, iso_datetime, gps)."""
    container = av.open(str(path))
    try:
        stream = next(s for s in container.streams if s.type == "video")
        width = stream.width
        height = stream.height
        duration = float(container.duration / 1_000_000) if container.duration else 0.0

        # Container metadata: creation_time is usually ISO 8601 with Z or offset
        dt = None
        ct = container.metadata.get("creation_time")
        if ct:
            try:
                dt = datetime.fromisoformat(ct.replace("Z", "+00:00")).isoformat()
            except ValueError:
                dt = None

        # GPS in MOV/MP4 is usually in com.apple.quicktime.location.ISO6709 etc.
        # Best-effort parse: ISO 6709 like "+12.3456-098.7654/"
        gps = None
        loc = container.metadata.get("location") or container.metadata.get("com.apple.quicktime.location.ISO6709")
        if loc:
            try:
                # crude split on signs
                s = loc.replace("/", "")
                # find second sign as separator
                signs = [i for i, c in enumerate(s) if c in "+-" and i > 0]
                if signs:
                    sep = signs[0]
                    lat = float(s[:sep])
                    lon = float(s[sep:])
                    gps = (lat, lon)
            except (ValueError, IndexError):
                gps = None

        return width, height, duration, dt, gps
    finally:
        container.close()


def discover(inbox: Path) -> list[Asset]:
    """Walk inbox, pair Live Photos, build Asset list."""
    files = sorted(p for p in inbox.rglob("*") if p.is_file())

    # Group by stem to find Live Photo pairs (HEIC + MOV with same basename)
    by_stem: dict[str, list[Path]] = {}
    for p in files:
        if p.suffix.lower() in IMAGE_EXTS or p.suffix.lower() in VIDEO_EXTS:
            by_stem.setdefault(p.stem, []).append(p)

    assets: list[Asset] = []
    for stem, paths in by_stem.items():
        imgs = [p for p in paths if p.suffix.lower() in IMAGE_EXTS]
        vids = [p for p in paths if p.suffix.lower() in VIDEO_EXTS]

        if imgs and vids:
            # Live Photo pair: still + motion
            img = imgs[0]
            vid = vids[0]
            try:
                w, h, dt, gps, cam = read_image_meta(img)
                _, _, dur, vdt, vgps = read_video_meta(vid)
                assets.append(
                    Asset(
                        asset_id=stem,
                        media_type="live_photo",
                        image_path=str(img.relative_to(ROOT)),
                        video_path=str(vid.relative_to(ROOT)),
                        width=w,
                        height=h,
                        duration_sec=dur,
                        exif_datetime=dt or vdt,
                        exif_gps=gps or vgps,
                        exif_camera=cam,
                    )
                )
            except Exception as e:
                print(f"  ! skip live pair {stem}: {type(e).__name__}: {e}")
        elif imgs:
            img = imgs[0]
            try:
                w, h, dt, gps, cam = read_image_meta(img)
                assets.append(
                    Asset(
                        asset_id=stem,
                        media_type="image",
                        image_path=str(img.relative_to(ROOT)),
                        width=w,
                        height=h,
                        exif_datetime=dt,
                        exif_gps=gps,
                        exif_camera=cam,
                    )
                )
            except Exception as e:
                print(f"  ! skip image {img.name}: {type(e).__name__}: {e}")
        elif vids:
            vid = vids[0]
            try:
                w, h, dur, dt, gps = read_video_meta(vid)
                assets.append(
                    Asset(
                        asset_id=stem,
                        media_type="video",
                        video_path=str(vid.relative_to(ROOT)),
                        width=w,
                        height=h,
                        duration_sec=dur,
                        exif_datetime=dt,
                        exif_gps=gps,
                    )
                )
            except Exception as e:
                print(f"  ! skip video {vid.name}: {type(e).__name__}: {e}")

    # Sort by EXIF time ascending (None goes last so they're easy to find)
    assets.sort(key=lambda a: (a.exif_datetime is None, a.exif_datetime or ""))
    return assets


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--limit", type=int, default=None, help="only ingest first N assets (smoke test)")
    args = parser.parse_args()

    WORKSPACE.mkdir(parents=True, exist_ok=True)

    print(f"[step0] scanning {INBOX}/")
    assets = list(tqdm(discover(INBOX), desc="ingest"))
    if args.limit:
        assets = assets[: args.limit]

    if not assets:
        raise SystemExit(f"No supported files in {INBOX}/. Drop photos/videos and re-run.")

    out = WORKSPACE / "assets.jsonl"
    with out.open("w", encoding="utf-8") as f:
        for a in assets:
            f.write(a.model_dump_json() + "\n")

    n_img = sum(1 for a in assets if a.media_type == "image")
    n_vid = sum(1 for a in assets if a.media_type == "video")
    n_live = sum(1 for a in assets if a.media_type == "live_photo")
    n_dated = sum(1 for a in assets if a.exif_datetime)
    n_gps = sum(1 for a in assets if a.exif_gps)
    print(
        f"[step0] {len(assets)} assets -> {out}\n"
        f"        image={n_img}  video={n_vid}  live_photo={n_live}\n"
        f"        with_datetime={n_dated}/{len(assets)}  with_gps={n_gps}/{len(assets)}"
    )


if __name__ == "__main__":
    main()
