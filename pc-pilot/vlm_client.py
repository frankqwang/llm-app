"""OpenAI-compatible client wrapper.

Default backend: LM Studio at http://localhost:1234/v1, single model
(Qwen3.5-9B is a unified VLM, so it serves both VLM and text-LLM roles).

Qwen3.5 quirk on LM Studio: the default jinja template forces thinking mode,
which routes the model's reasoning to `reasoning_content` and the FINAL answer
to `content`. So we:
  - give max_tokens enough headroom for both reasoning + answer
  - read `content` first, fall back to extracting JSON from `reasoning_content`
    if the content was empty (token budget exhausted before the model finished)

Override via env: LLM_BASE_URL, LLM_MODEL.
"""
from __future__ import annotations

import base64
import io
import json
import os
import re
import sys
from pathlib import Path
from typing import Any

from openai import OpenAI
from PIL import Image

# Force UTF-8 stdio on Windows so Chinese descriptions/captions print correctly
# (default Windows console codepage is GBK and chokes on emoji + many CJK chars).
if sys.platform == "win32":
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except (AttributeError, OSError):
            pass

# Register HEIF opener so Pillow can read .heic / .heif (Live Photos still images).
try:
    from pillow_heif import register_heif_opener

    register_heif_opener()
except ImportError:
    pass

LLM_BASE_URL = os.environ.get("LLM_BASE_URL", "http://localhost:1234/v1")
LLM_MODEL = os.environ.get("LLM_MODEL", "qwen/qwen3.5-9b")
# VLM and LLM roles point to the same unified model by default.
VLM_BASE_URL = os.environ.get("VLM_BASE_URL", LLM_BASE_URL)
VLM_MODEL = os.environ.get("VLM_MODEL", LLM_MODEL)

API_KEY = os.environ.get("OPENAI_API_KEY", "lm-studio")  # any non-empty string works

_vlm_client = OpenAI(base_url=VLM_BASE_URL, api_key=API_KEY)
_llm_client = OpenAI(base_url=LLM_BASE_URL, api_key=API_KEY)

# vlm reads images so it needs more max_tokens budget than text-only LLM
DEFAULT_VLM_MAX_TOKENS = int(os.environ.get("VLM_MAX_TOKENS", 4096))
DEFAULT_LLM_MAX_TOKENS = int(os.environ.get("LLM_MAX_TOKENS", 8192))

ImageInput = Path | str | Image.Image


def encode_image_to_data_url(src: ImageInput, max_side: int = 1280, quality: int = 85) -> str:
    """Downscale + JPEG encode + base64 -> data URL. Accepts a path or a PIL Image."""
    if isinstance(src, Image.Image):
        img = src
    else:
        img = Image.open(src)
    img = img.convert("RGB")
    if max(img.size) > max_side:
        ratio = max_side / max(img.size)
        new_size = (int(img.width * ratio), int(img.height * ratio))
        img = img.resize(new_size, Image.LANCZOS)
    buf = io.BytesIO()
    img.save(buf, format="JPEG", quality=quality)
    b64 = base64.b64encode(buf.getvalue()).decode("ascii")
    return f"data:image/jpeg;base64,{b64}"


def _inline_refs(schema: dict[str, Any]) -> dict[str, Any]:
    """Inline $defs/$ref into the main schema. LM Studio strict json_schema rejects $ref.

    Pydantic emits $defs whenever a model contains another model (e.g. CandidateScript
    contains list[ShotSpec]); we expand those before sending.
    """
    defs = schema.get("$defs", {})

    def walk(node: Any) -> Any:
        if isinstance(node, dict):
            if "$ref" in node and node["$ref"].startswith("#/$defs/"):
                ref_name = node["$ref"][len("#/$defs/") :]
                if ref_name in defs:
                    return walk(defs[ref_name])
            return {k: walk(v) for k, v in node.items() if k != "$defs"}
        if isinstance(node, list):
            return [walk(x) for x in node]
        return node

    return walk(schema)


def _build_response_format(schema: dict[str, Any] | None, name: str = "Output") -> dict[str, Any] | None:
    if schema is None:
        return None
    # NOT strict: Pydantic schemas don't satisfy OpenAI strict requirements
    # (additionalProperties everywhere, all fields required, etc.). Lenient mode + our
    # own Pydantic re-validation downstream gives the same safety with fewer rejections.
    return {
        "type": "json_schema",
        "json_schema": {
            "name": name,
            "schema": _inline_refs(schema),
        },
    }


_JSON_OBJ_RE = re.compile(r"\{[\s\S]*\}", re.MULTILINE)


def _extract_json(text: str) -> str:
    """Best-effort: pull the largest balanced {...} substring from a free-form reply."""
    m = _JSON_OBJ_RE.search(text)
    if m:
        return m.group(0)
    return text


_DEBUG_DIR = Path(__file__).parent / "workspace" / "debug"


def _save_debug(label: str, body: str) -> Path:
    """Persist a raw model response so we can diagnose JSON parse failures."""
    _DEBUG_DIR.mkdir(parents=True, exist_ok=True)
    import time

    p = _DEBUG_DIR / f"{int(time.time() * 1000)}_{label}.txt"
    p.write_text(body, encoding="utf-8")
    return p


def _parse_response_message(msg: Any) -> dict[str, Any]:
    """Read content first; if empty (LM Studio thinking-mode quirk where final JSON gets
    cut off because token budget went into reasoning), salvage from reasoning_content."""
    content = (getattr(msg, "content", None) or "").strip()
    reasoning = (getattr(msg, "reasoning_content", None) or "").strip()

    if content:
        try:
            return json.loads(content)
        except json.JSONDecodeError:
            try:
                return json.loads(_extract_json(content))
            except json.JSONDecodeError as e:
                dbg = _save_debug("content_parse_fail", content)
                raise ValueError(
                    f"content failed to parse as JSON ({e.msg} at line {e.lineno} col {e.colno}); raw saved to {dbg}"
                ) from e

    if reasoning:
        try:
            return json.loads(_extract_json(reasoning))
        except json.JSONDecodeError as e:
            dbg = _save_debug("reasoning_parse_fail", reasoning)
            raise ValueError(
                f"reasoning_content failed to parse as JSON ({e.msg}); raw saved to {dbg}"
            ) from e

    raise ValueError("Empty response: no content and no reasoning_content")


def call_vlm_json(
    system: str,
    user_text: str,
    images: list[ImageInput],
    *,
    schema: dict[str, Any] | None = None,
    schema_name: str = "VlmOutput",
    temperature: float = 0.3,
    max_tokens: int | None = None,
) -> dict[str, Any]:
    """Call the VLM with N images and force JSON output (OpenAI standard json_schema)."""
    content: list[dict[str, Any]] = [{"type": "text", "text": user_text}]
    for img in images:
        content.append({"type": "image_url", "image_url": {"url": encode_image_to_data_url(img)}})

    kwargs: dict[str, Any] = {
        "model": VLM_MODEL,
        "messages": [
            {"role": "system", "content": system},
            {"role": "user", "content": content},
        ],
        "temperature": temperature,
        "max_tokens": max_tokens or DEFAULT_VLM_MAX_TOKENS,
    }
    rf = _build_response_format(schema, schema_name)
    if rf:
        kwargs["response_format"] = rf

    resp = _vlm_client.chat.completions.create(**kwargs)
    return _parse_response_message(resp.choices[0].message)


def call_llm_json(
    system: str,
    user_text: str,
    *,
    schema: dict[str, Any] | None = None,
    schema_name: str = "LlmOutput",
    temperature: float = 0.7,
    max_tokens: int | None = None,
) -> dict[str, Any]:
    """Text-only LLM call with forced JSON output."""
    kwargs: dict[str, Any] = {
        "model": LLM_MODEL,
        "messages": [
            {"role": "system", "content": system},
            {"role": "user", "content": user_text},
        ],
        "temperature": temperature,
        "max_tokens": max_tokens or DEFAULT_LLM_MAX_TOKENS,
    }
    rf = _build_response_format(schema, schema_name)
    if rf:
        kwargs["response_format"] = rf

    resp = _llm_client.chat.completions.create(**kwargs)
    return _parse_response_message(resp.choices[0].message)
