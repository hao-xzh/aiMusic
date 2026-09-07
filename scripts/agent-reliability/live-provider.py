#!/usr/bin/env python3
"""One-shot DeepSeek tool-call bridge for the JVM live reliability probe.

The bridge reads one JSON request from stdin and writes one sanitized JSON result
to stdout.  It deliberately never logs credentials, authorization headers, or a
provider response body on failure.
"""
from __future__ import annotations

import json
import os
import socket
import sys
import urllib.error
import urllib.request


BASE_URL = "https://api.deepseek.com/chat/completions"
MODEL = "deepseek-v4-flash"
TEMPERATURE = 0.15
MAX_TOKENS = 1400


def fail(kind: str) -> None:
    sys.stdout.write(json.dumps({"ok": False, "error": kind}, ensure_ascii=False) + "\n")
    sys.stdout.flush()


def main() -> int:
    try:
        request = json.loads(sys.stdin.read())
        messages = request["messages"]
        tools = request["tools"]
        timeout_seconds = float(request.get("timeoutSeconds", 35))
    except (KeyError, TypeError, ValueError, json.JSONDecodeError):
        fail("invalid_live_provider_request")
        return 2

    key = os.environ.get("DEEPSEEK_API_KEY", "").strip()
    if not key:
        fail("missing_deepseek_api_key")
        return 2

    payload = {
        "model": MODEL,
        "messages": messages,
        "tools": tools,
        "tool_choice": "auto",
        "temperature": TEMPERATURE,
        "max_tokens": MAX_TOKENS,
        "stream": False,
        "thinking": {"type": "disabled"},
    }
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    http_request = urllib.request.Request(
        BASE_URL,
        data=body,
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {key}",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(http_request, timeout=timeout_seconds) as response:
            decoded = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        fail(f"provider_http_{error.code}")
        return 3
    except (urllib.error.URLError, socket.timeout, TimeoutError):
        fail("provider_network_or_timeout")
        return 3
    except (UnicodeDecodeError, json.JSONDecodeError):
        fail("provider_invalid_json")
        return 3

    try:
        assistant = decoded["choices"][0]["message"]
    except (KeyError, IndexError, TypeError):
        fail("provider_missing_assistant_message")
        return 3
    if not isinstance(assistant, dict):
        fail("provider_invalid_assistant_message")
        return 3

    usage = decoded.get("usage") if isinstance(decoded, dict) else None
    cache_usage = {
        "prompt_tokens": usage.get("prompt_tokens") if isinstance(usage, dict) else None,
        "completion_tokens": usage.get("completion_tokens") if isinstance(usage, dict) else None,
        "total_tokens": usage.get("total_tokens") if isinstance(usage, dict) else None,
        "prompt_cache_hit_tokens": usage.get("prompt_cache_hit_tokens") if isinstance(usage, dict) else None,
        "prompt_cache_miss_tokens": usage.get("prompt_cache_miss_tokens") if isinstance(usage, dict) else None,
    }
    # The Kotlin loop needs the assistant message verbatim to preserve native
    # function-call parsing.  Only cache-token counters are retained from usage;
    # the key/header and unrelated provider envelope are never persisted.
    sys.stdout.write(json.dumps(
        {"ok": True, "assistant": assistant, "usage": cache_usage}, ensure_ascii=False
    ) + "\n")
    sys.stdout.flush()
    return 0


if __name__ == "__main__":
    sys.exit(main())
