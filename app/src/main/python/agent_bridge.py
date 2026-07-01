from __future__ import annotations

from typing import Any


def run_agent(request: dict[str, Any]) -> dict[str, str]:
    text = str(request.get("text", "")).strip()
    if not text:
        return {
            "status": "error",
            "message": "missing text",
        }

    try:
        import pydantic_ai  # noqa: F401
    except Exception as exc:
        return {
            "status": "error",
            "message": f"pydantic_ai import failed: {exc}",
        }

    return {
        "status": "ok",
        "message": f"agent bridge received: {text}",
    }
