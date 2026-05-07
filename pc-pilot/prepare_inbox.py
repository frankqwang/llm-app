"""Prepare pc-pilot/inbox/ from a vivo album sync directory.

Strategy:
  - Walk 图片/相机/, 图片/拼图, and 视频/ in the album
  - For static IMG_*.jpg in 相机/: hardlink to inbox/
  - For IMG_*(动态图)/ directories: hardlink the inner IMG_*.jpg + IMG_*.mp4 to
    inbox/, flattening the structure. step0_ingest.py then auto-pairs them as
    live_photo.
  - For 视频/*.mp4: hardlink (only files within the date window)
  - Skip 其它相册/ subfolders (DJI, screenshots from other apps, e-commerce, etc.)

Date filter: prefer parsing date from filename (vivo always names IMG_YYYYMMDD_HHMMSS
and video_YYYYMMDD_HHMMSS); fall back to file mtime.

Hardlinks (os.link) are used so we don't double the disk usage; both ends must be on
the same volume. Falls back to copy if hardlink fails (different filesystem etc.).
"""
from __future__ import annotations

import argparse
import os
import re
import shutil
from datetime import datetime, timedelta
from pathlib import Path

DEFAULT_ALBUM_ROOT = Path(r"D:\vivoSync\王青的vivo X200 Pro+8dcc5b")
INBOX = Path(__file__).parent / "inbox"

# We only mine these top-level subtrees; everything else (其它相册, database, 拼图) is skipped.
IMAGE_SOURCES = ("图片/相机",)
VIDEO_SOURCES = ("视频",)

IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".heic", ".heif", ".webp"}
VIDEO_EXTS = {".mp4", ".mov", ".m4v"}

# vivo's motion-photo directory: IMG_YYYYMMDD_HHMMSS(动态图)
LIVE_DIR_RE = re.compile(r"^IMG_\d{8}_\d{6}\(动态图\)$")

# Filename → datetime parsers, tried in order. We deliberately do NOT fall back to file
# mtime: vivoSync rewrites mtime on every sync, so mtime is junk for our use.
DT_PATTERN_YMD = re.compile(r"(\d{8})_(\d{6})")  # IMG_20240315_104530, video_20240315_104530
DT_PATTERN_UNIXMS = re.compile(r"(\d{13})")  # Video_1764055109734, 1770824817702.mp4
DT_PATTERN_DASHED = re.compile(r"(\d{4})-(\d{2})-(\d{2}) (\d{2})-(\d{2})-(\d{2})")  # 屏幕录像 2025-11-02 23-12-34

# Junk categories we never want as vlog material regardless of date.
EXCLUDE_NAME_KEYWORDS = ("屏幕", "录屏", "screen", "share_", "idlefish")


def parse_dt_from_name(name: str) -> datetime | None:
    # Strip commas (vivo screen recordings format unix ms with thousand separators).
    normalized = name.replace(",", "")

    m = DT_PATTERN_YMD.search(normalized)
    if m:
        try:
            return datetime.strptime(m.group(1) + m.group(2), "%Y%m%d%H%M%S")
        except ValueError:
            pass

    m = DT_PATTERN_DASHED.search(normalized)
    if m:
        try:
            return datetime(*(int(m.group(i)) for i in range(1, 7)))
        except ValueError:
            pass

    m = DT_PATTERN_UNIXMS.search(normalized)
    if m:
        ms = int(m.group(1))
        # plausibility window: 2001-09 .. 2033-05
        if 1_000_000_000_000 < ms < 2_000_000_000_000:
            try:
                return datetime.fromtimestamp(ms / 1000)
            except (OSError, ValueError):
                pass

    return None


def is_junk_name(name: str) -> bool:
    lname = name.lower()
    return any(kw.lower() in lname for kw in EXCLUDE_NAME_KEYWORDS)


def asset_dt(path: Path) -> datetime | None:
    if is_junk_name(path.name):
        return None
    return parse_dt_from_name(path.name)


def link_one(src: Path, dst: Path, *, force_copy: bool) -> str:
    """Returns one of: 'linked', 'copied', 'exists', 'skip-error'."""
    if dst.exists():
        return "exists"
    try:
        if force_copy:
            shutil.copy2(src, dst)
            return "copied"
        os.link(src, dst)
        return "linked"
    except OSError:
        try:
            shutil.copy2(src, dst)
            return "copied"
        except OSError as e:
            print(f"  ! skip {src.name}: {e}")
            return "skip-error"


def clean_inbox() -> None:
    if not INBOX.exists():
        return
    for p in INBOX.iterdir():
        if p.name == ".gitkeep":
            continue
        if p.is_file() or p.is_symlink():
            p.unlink(missing_ok=True)
        elif p.is_dir():
            shutil.rmtree(p, ignore_errors=True)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--album", type=Path, default=DEFAULT_ALBUM_ROOT, help="album root dir")
    ap.add_argument("--days", type=int, default=30, help="how many days back from today to include")
    ap.add_argument("--max-assets", type=int, default=None, help="cap on linked items (newest-first)")
    ap.add_argument("--clean", action="store_true", help="wipe inbox/ first")
    ap.add_argument("--copy", action="store_true", help="force copy instead of hardlink")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    if not args.album.exists():
        raise SystemExit(f"Album dir not found: {args.album}")

    if args.clean and not args.dry_run:
        clean_inbox()
    INBOX.mkdir(parents=True, exist_ok=True)

    cutoff = datetime.now() - timedelta(days=args.days)
    print(f"[prepare_inbox] window: {cutoff.isoformat()} → now ({args.days}d)")
    print(f"[prepare_inbox] album: {args.album}")
    print(f"[prepare_inbox] inbox: {INBOX}")

    candidates: list[tuple[datetime, Path]] = []

    # --- 1. Static images directly under 图片/相机/ ---
    for sub in IMAGE_SOURCES:
        d = args.album / sub
        if not d.exists():
            continue
        for p in d.iterdir():
            if not p.is_file():
                continue
            if p.suffix.lower() not in IMAGE_EXTS:
                continue
            dt = asset_dt(p)
            if dt and dt >= cutoff:
                candidates.append((dt, p))

    # --- 2. vivo motion-photo directories (each is a folder with IMG_*.jpg + IMG_*.mp4) ---
    for sub in IMAGE_SOURCES:
        d = args.album / sub
        if not d.exists():
            continue
        for live_dir in d.iterdir():
            if not live_dir.is_dir() or not LIVE_DIR_RE.match(live_dir.name):
                continue
            dt = parse_dt_from_name(live_dir.name)
            if not (dt and dt >= cutoff):
                continue
            for inner in live_dir.iterdir():
                if inner.suffix.lower() in IMAGE_EXTS or inner.suffix.lower() in VIDEO_EXTS:
                    candidates.append((dt, inner))

    # --- 3. Standalone videos under 视频/ ---
    for sub in VIDEO_SOURCES:
        d = args.album / sub
        if not d.exists():
            continue
        for p in d.iterdir():
            if not p.is_file():
                continue
            if p.suffix.lower() not in VIDEO_EXTS:
                continue
            dt = asset_dt(p)
            if dt and dt >= cutoff:
                candidates.append((dt, p))

    candidates.sort(key=lambda x: x[0], reverse=True)  # newest first
    if args.max_assets:
        candidates = candidates[: args.max_assets]

    print(f"[prepare_inbox] {len(candidates)} candidates after filter")

    if args.dry_run:
        for dt, p in candidates[:30]:
            print(f"  {dt:%Y-%m-%d %H:%M}  {p}")
        if len(candidates) > 30:
            print(f"  ... ({len(candidates) - 30} more)")
        return

    counters: dict[str, int] = {}
    for _, src in candidates:
        dst = INBOX / src.name
        result = link_one(src, dst, force_copy=args.copy)
        counters[result] = counters.get(result, 0) + 1

    summary = ", ".join(f"{k}={v}" for k, v in sorted(counters.items()))
    print(f"[prepare_inbox] done: {summary}")
    print(f"[prepare_inbox] inbox now contains {sum(1 for _ in INBOX.iterdir())} entries")


if __name__ == "__main__":
    main()
