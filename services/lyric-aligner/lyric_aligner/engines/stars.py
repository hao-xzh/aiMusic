from __future__ import annotations

import asyncio
import json
import os
import re
import shlex
from pathlib import Path
from typing import Any

from ..audio import extract_window
from ..config import Settings
from ..errors import AlignmentRejected, EngineUnavailable
from ..schemas import AlignLine, LyricLineInput
from ..text import (
    TimedText,
    align_timed_items_to_line,
    build_source_spans,
    normalize_for_match,
    silence_label,
)
from .base import EngineInfo


class StarsSubprocessEngine:
    def __init__(self, settings: Settings):
        self.settings = settings
        self.info = EngineInfo(
            name="stars-subprocess",
            version="0.2.0",
            model=str(settings.stars_checkpoint or "unconfigured"),
        )

    async def align(self, audio_path: Path, lines: list[LyricLineInput], work_dir: Path) -> list[AlignLine]:
        self._ensure_configured()
        segments_dir = work_dir / "stars-segments"
        out_dir = work_dir / "stars-output"
        metadata_path = work_dir / "stars-metadata.json"
        entries: list[dict[str, Any]] = []
        entry_to_line: dict[str, LyricLineInput] = {}

        for line in lines:
            segment = segments_dir / f"line-{line.index}.wav"
            await extract_window(audio_path, segment, line.start_ms, line.duration_ms)
            words, ph, ph2words = await self._phonemes_for_line(line.text)
            if not ph or not ph2words or len(ph) != len(ph2words):
                raise AlignmentRejected(f"STARS phoneme metadata is invalid for line {line.index}")
            item_name = f"line-{line.index}"
            entries.append(
                {
                    "item_name": item_name,
                    "wav_fn": str(segment),
                    "word": words,
                    "ph": ph,
                    "ph2words": ph2words,
                }
            )
            entry_to_line[item_name] = line

        metadata_path.write_text(json.dumps(entries, ensure_ascii=False, indent=2), encoding="utf-8")
        await self._run_inference(metadata_path, out_dir)
        return self._parse_output(out_dir, entry_to_line)

    def _ensure_configured(self) -> None:
        missing = []
        for name, value in (
            ("ALIGNER_STARS_REPO_DIR", self.settings.stars_repo_dir),
            ("ALIGNER_STARS_CHECKPOINT", self.settings.stars_checkpoint),
            ("ALIGNER_STARS_CONFIG", self.settings.stars_config),
            ("ALIGNER_STARS_PHSET", self.settings.stars_phset),
        ):
            if not value:
                missing.append(name)
        if not self.settings.stars_inference_cmd:
            missing.append("ALIGNER_STARS_INFERENCE_CMD")
        if not self.settings.stars_phoneme_cmd:
            missing.append("ALIGNER_STARS_PHONEME_CMD")
        if missing:
            raise EngineUnavailable("STARS engine is not configured: " + ", ".join(missing))
        for path in (
            self.settings.stars_repo_dir,
            self.settings.stars_checkpoint,
            self.settings.stars_config,
            self.settings.stars_phset,
        ):
            assert path is not None
            if not path.exists():
                raise EngineUnavailable(f"STARS path does not exist: {path}")

    async def _phonemes_for_line(self, text: str) -> tuple[list[str], list[str], list[int]]:
        assert self.settings.stars_phoneme_cmd is not None
        args = shlex.split(self.settings.stars_phoneme_cmd) + ["--text", text, "--with_phsep"]
        proc = await asyncio.create_subprocess_exec(
            *args,
            cwd=str(self.settings.stars_repo_dir),
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
        stdout, stderr = await proc.communicate()
        if proc.returncode != 0:
            raise EngineUnavailable(f"STARS phoneme conversion failed: {stderr.decode('utf-8', 'ignore')[:240]}")
        raw = stdout.decode("utf-8", "ignore").strip()
        data: Any
        try:
            data = json.loads(raw)
        except json.JSONDecodeError:
            data = _parse_official_mixedtext_stdout(raw)
        if isinstance(data, dict):
            words = data.get("words") or data.get("word") or data.get("tokens")
            ph = data.get("ph") or data.get("phones") or data.get("phonemes")
            ph2words = data.get("ph2words") or data.get("ph2word") or data.get("phone_to_words")
        else:
            words = None
            ph = None
            ph2words = None
        if not isinstance(words, list) or not isinstance(ph, list) or not isinstance(ph2words, list):
            raise AlignmentRejected("STARS phoneme command must output words, ph and ph2words")
        return [str(item) for item in words], [str(item) for item in ph], [int(item) for item in ph2words]

    async def _run_inference(self, metadata_path: Path, out_dir: Path) -> None:
        assert self.settings.stars_inference_cmd is not None
        assert self.settings.stars_checkpoint is not None
        assert self.settings.stars_config is not None
        assert self.settings.stars_phset is not None
        out_dir.mkdir(parents=True, exist_ok=True)
        args = shlex.split(self.settings.stars_inference_cmd) + [
            "--ckpt",
            str(self.settings.stars_checkpoint),
            "--config",
            str(self.settings.stars_config),
            "--phset",
            str(self.settings.stars_phset),
            "-o",
            str(out_dir),
            "--metadata",
            str(metadata_path),
        ]
        env = os.environ.copy()
        if self.settings.stars_cuda_visible_devices is not None:
            env["CUDA_VISIBLE_DEVICES"] = self.settings.stars_cuda_visible_devices
        proc = await asyncio.create_subprocess_exec(
            *args,
            cwd=str(self.settings.stars_repo_dir),
            env=env,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
        _, stderr = await proc.communicate()
        if proc.returncode != 0:
            raise EngineUnavailable(f"STARS inference failed: {stderr.decode('utf-8', 'ignore')[:300]}")

    def _parse_output(
        self,
        out_dir: Path,
        entry_to_line: dict[str, LyricLineInput],
    ) -> list[AlignLine]:
        lines: list[AlignLine] = []
        output_json = out_dir / "output.json"
        output_by_name: dict[str, Any] = {}
        if output_json.exists():
            data = json.loads(output_json.read_text(encoding="utf-8"))
            if isinstance(data, list):
                output_by_name = {str(item.get("item_name")): item for item in data if isinstance(item, dict)}
        for item_name, line in entry_to_line.items():
            tg_candidates = list(out_dir.glob(f"**/{item_name}*.TextGrid")) + list(out_dir.glob(f"**/{item_name}*.textgrid"))
            if item_name in output_by_name:
                lines.append(_parse_stars_result(output_by_name[item_name], line))
            elif tg_candidates:
                lines.append(_parse_textgrid(tg_candidates[0], line))
            else:
                raise AlignmentRejected(f"STARS output for line {line.index} was not found")
        return sorted(lines, key=lambda item: item.index)


def _parse_official_mixedtext_stdout(raw: str) -> dict[str, Any]:
    import ast

    payload: dict[str, Any] = {}
    for line in raw.splitlines():
        stripped = line.strip()
        if stripped.startswith("words:"):
            payload["words"] = ast.literal_eval(stripped.split(":", 1)[1].strip())
        elif stripped.startswith("phs:"):
            payload["ph"] = ast.literal_eval(stripped.split(":", 1)[1].strip())
        elif stripped.startswith("ph2word:"):
            payload["ph2words"] = ast.literal_eval(stripped.split(":", 1)[1].strip())
    return payload


def _parse_stars_result(data: dict[str, Any], line: LyricLineInput) -> AlignLine:
    word_items = _items_from_durations(data.get("word_list"), data.get("word_durs"), line.start_ms, 0.80)
    phone_items = _items_from_durations(data.get("ph_list"), data.get("ph_durs"), line.start_ms, 0.82)
    token_parts = _phone_parts_by_span(line, word_items, phone_items)
    tokens, match_confidence = align_timed_items_to_line(
        line,
        word_items,
        token_parts=token_parts,
        allow_parts=True,
    )
    return AlignLine(index=line.index, text=line.text, confidence=min(match_confidence, _stars_confidence(tokens)), tokens=tokens)


def _parse_textgrid(path: Path, line: LyricLineInput) -> AlignLine:
    body = path.read_text(encoding="utf-8", errors="ignore")
    word_items = _timed_from_tier(_extract_tier(body, "words"), line.start_ms, 0.76)
    phone_items = _timed_from_tier(_extract_tier(body, "phones"), line.start_ms, 0.78)
    token_parts = _phone_parts_by_span(line, word_items, phone_items)
    tokens, match_confidence = align_timed_items_to_line(
        line,
        word_items,
        token_parts=token_parts,
        allow_parts=True,
    )
    return AlignLine(index=line.index, text=line.text, confidence=min(match_confidence, _stars_confidence(tokens)), tokens=tokens)


def _items_from_durations(labels: Any, durations: Any, offset_ms: int, confidence: float) -> list[TimedText]:
    if not isinstance(labels, list) or not isinstance(durations, list):
        raise AlignmentRejected("STARS output.json is missing aligned labels or durations")
    cursor_ms = offset_ms
    items: list[TimedText] = []
    for label, duration in zip(labels, durations):
        duration_ms = max(0, round(float(duration) * 1000))
        text = str(label)
        if not silence_label(text):
            items.append(TimedText(text=text, start_ms=cursor_ms, end_ms=cursor_ms + duration_ms, confidence=confidence))
        cursor_ms += duration_ms
    return items


def _extract_tier(body: str, tier_name: str) -> list[tuple[float, float, str]]:
    tier_re = re.compile(
        r"item \[\d+\]:\s*class = \"IntervalTier\"\s*name = \"" + re.escape(tier_name) + r"\"(?P<body>.*?)(?=\n\s*item \[\d+\]:|\Z)",
        re.S,
    )
    match = tier_re.search(body)
    if not match:
        return []
    return [
        (float(start), float(end), label)
        for start, end, label in re.findall(
            r"xmin\s*=\s*([0-9.]+)\s*xmax\s*=\s*([0-9.]+)\s*text\s*=\s*\"([^\"]*)\"",
            match.group("body"),
            re.S,
        )
    ]


def _timed_from_tier(intervals: list[tuple[float, float, str]], offset_ms: int, confidence: float) -> list[TimedText]:
    items = []
    for start_s, end_s, label in intervals:
        if silence_label(label):
            continue
        items.append(
            TimedText(
                text=label.strip(),
                start_ms=offset_ms + round(start_s * 1000),
                end_ms=offset_ms + round(end_s * 1000),
                confidence=confidence,
            )
        )
    return items


def _phone_parts_by_span(
    line: LyricLineInput,
    word_items: list[TimedText],
    phone_items: list[TimedText],
) -> dict[int, list[TimedText]]:
    spans = build_source_spans(line)
    parts: dict[int, list[TimedText]] = {}
    item_idx = 0
    for span_idx, span in enumerate(spans):
        target = normalize_for_match(span.core_text)
        collected = []
        collected_norm = ""
        while item_idx < len(word_items):
            item_norm = normalize_for_match(word_items[item_idx].text)
            if not target:
                break
            next_norm = collected_norm + item_norm
            if target.startswith(next_norm) or next_norm.startswith(target) or not collected:
                collected.append(word_items[item_idx])
                collected_norm = next_norm
                item_idx += 1
                if collected_norm == target:
                    break
                continue
            break
        if not collected:
            continue
        start = min(item.start_ms for item in collected)
        end = max(item.end_ms for item in collected)
        parts[span_idx] = [phone for phone in phone_items if phone.start_ms < end and phone.end_ms > start]
    return parts

def _stars_confidence(tokens: list[Any]) -> float:
    if not tokens:
        return 0.0
    zero = sum(1 for token in tokens if token.duration_ms <= 0)
    return round(max(0.0, min(1.0, 0.78 - zero * 0.08)), 3)
