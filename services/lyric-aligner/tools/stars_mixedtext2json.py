#!/usr/bin/env python3
from __future__ import annotations

import argparse
import ast
import json
import subprocess
import sys
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser(description="Wrap STARS mixedtext2phoneme.py and emit JSON.")
    parser.add_argument("--stars-script", required=True, help="Path to official STARS scripts/mixedtext2phoneme.py")
    parser.add_argument("--text", required=True)
    parser.add_argument("--latin_lang", default="english")
    parser.add_argument("--use_tone", action="store_true")
    parser.add_argument("--with_phsep", action="store_true")
    args = parser.parse_args()

    script = Path(args.stars_script)
    cmd = [
        sys.executable,
        str(script),
        "--text",
        args.text,
        "--latin_lang",
        args.latin_lang,
    ]
    if args.use_tone:
        cmd.append("--use_tone")
    if args.with_phsep:
        cmd.append("--with_phsep")
    proc = subprocess.run(cmd, cwd=str(script.parent.parent), text=True, capture_output=True, check=False)
    if proc.returncode != 0:
        sys.stderr.write(proc.stderr)
        return proc.returncode

    payload = {}
    for line in proc.stdout.splitlines():
        stripped = line.strip()
        if stripped.startswith("words:"):
            payload["words"] = ast.literal_eval(stripped.split(":", 1)[1].strip())
        elif stripped.startswith("phs:"):
            payload["ph"] = ast.literal_eval(stripped.split(":", 1)[1].strip())
        elif stripped.startswith("ph2word:"):
            payload["ph2words"] = ast.literal_eval(stripped.split(":", 1)[1].strip())
    if not {"words", "ph", "ph2words"} <= set(payload):
        sys.stderr.write("official mixedtext2phoneme.py output did not contain words/phs/ph2word\n")
        return 2
    print(json.dumps(payload, ensure_ascii=False, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
