"""Centralized logging for every Agent (LLM/VLM) call in the v2 pipeline.

Every step3-step6 invocation should log: step name, event_id, prompt, response, latency.
Logs go to workspace/agent_logs/<step>_<event>_<timestamp>.json so failures can be inspected.
"""
from __future__ import annotations

import json
import time
from pathlib import Path
from typing import Any

ROOT = Path(__file__).parent
LOG_DIR = ROOT / "workspace" / "agent_logs"


def log_call(
    step: str,
    event_id: str,
    *,
    system: str,
    user_text: str,
    images: list[str] | None = None,
    response: dict | str,
    latency_sec: float,
    extra: dict[str, Any] | None = None,
) -> Path:
    """Persist one Agent call. images is a list of paths (relative or absolute)."""
    LOG_DIR.mkdir(parents=True, exist_ok=True)
    ts = int(time.time() * 1000)
    path = LOG_DIR / f"{step}_{event_id}_{ts}.json"
    payload = {
        "ts_ms": ts,
        "step": step,
        "event_id": event_id,
        "latency_sec": round(latency_sec, 2),
        "system": system,
        "user_text": user_text,
        "images": images or [],
        "response": response,
    }
    if extra:
        payload["extra"] = extra
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    return path
