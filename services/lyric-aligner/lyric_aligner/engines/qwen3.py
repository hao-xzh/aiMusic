from __future__ import annotations

import asyncio
import difflib
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from ..audio import extract_window
from ..config import Settings
from ..errors import AlignmentRejected, EngineUnavailable
from ..schemas import AlignLine, LyricLineInput
from ..text import TimedText, align_timed_items_to_line, language_for_line, normalize_for_match
from .base import EngineInfo


@dataclass(frozen=True)
class _PreparedChunk:
    lines: list[LyricLineInput]
    segment: Path
    chunk_start_ms: int
    text: str


class Qwen3ForcedAlignerEngine:
    def __init__(self, settings: Settings):
        self.settings = settings
        self.info = EngineInfo(
            name="qwen3-forced-aligner",
            version="0.3.0",
            model=settings.qwen_model_path,
        )
        self._aligner: Any | None = None
        self._lock = asyncio.Lock()
        self._inference_lock = asyncio.Lock()

    async def align(self, audio_path: Path, lines: list[LyricLineInput], work_dir: Path) -> list[AlignLine]:
        async with self._inference_lock:
            aligner = await self._get_aligner()
            output = []
            chunks, immediate_fallbacks = await self._prepare_chunks(audio_path, lines, work_dir)
            output.extend(immediate_fallbacks)
            batch_size = max(1, self.settings.qwen_batch_size)
            for start in range(0, len(chunks), batch_size):
                output.extend(
                    await self._align_chunk_batch(
                        aligner,
                        chunks[start : start + batch_size],
                        audio_path,
                        work_dir,
                    )
                )
            return sorted(output, key=lambda item: item.index)

    async def _prepare_chunks(
        self,
        audio_path: Path,
        lines: list[LyricLineInput],
        work_dir: Path,
    ) -> tuple[list[_PreparedChunk], list[AlignLine]]:
        chunks: list[_PreparedChunk] = []
        fallbacks: list[AlignLine] = []
        current: list[LyricLineInput] = []

        async def flush() -> None:
            if not current:
                return
            chunk_lines = list(current)
            current.clear()
            chunk_start = max(0, chunk_lines[0].start_ms - self.settings.qwen_window_pad_ms)
            chunk_end = chunk_lines[-1].end_ms + self.settings.qwen_window_pad_ms
            if chunk_end <= chunk_start or chunk_end - chunk_start > self.settings.qwen_max_window_ms:
                fallbacks.extend(_fallback_line(line) for line in chunk_lines)
                return
            segment = work_dir / "qwen3-chunks" / f"chunk-{chunk_lines[0].index}-{chunk_lines[-1].index}.wav"
            try:
                await extract_window(audio_path, segment, chunk_start, chunk_end - chunk_start)
            except Exception:
                fallbacks.extend(_fallback_line(line) for line in chunk_lines)
                return
            chunks.append(_PreparedChunk(lines=chunk_lines, segment=segment, chunk_start_ms=chunk_start, text="\n".join(line.text for line in chunk_lines)))

        for line in lines:
            if line.duration_ms > self.settings.qwen_max_window_ms:
                await flush()
                fallbacks.append(_fallback_line(line))
                continue
            if not current:
                current.append(line)
                continue
            next_span = line.end_ms - current[0].start_ms
            current_span = current[-1].end_ms - current[0].start_ms
            reaches_target = next_span > self.settings.qwen_chunk_target_ms and current_span >= self.settings.qwen_chunk_min_ms
            if next_span > self.settings.qwen_max_window_ms or reaches_target:
                await flush()
            current.append(line)
        await flush()
        return chunks, fallbacks

    async def _align_chunk_batch(self, aligner: Any, batch: list[_PreparedChunk], audio_path: Path, work_dir: Path) -> list[AlignLine]:
        if not batch:
            return []
        try:
            result = await asyncio.to_thread(
                aligner.align,
                audio=[str(item.segment) for item in batch],
                text=[item.text for item in batch],
                language=[language_for_line(item.text) for item in batch],
            )
            per_line = _result_items_for_batch(result, len(batch))
            output = []
            for chunk, items in zip(batch, per_line):
                chunk_lines = _lines_from_chunk(chunk, items, self.settings.max_boundary_slop_ms)
                retry_sources = [
                    source
                    for source, aligned in zip(chunk.lines, chunk_lines)
                    if _line_needs_precise_retry(source, aligned, self.settings.min_line_confidence)
                ]
                if retry_sources:
                    retried = await self._align_chunk_lines_individually(
                        aligner,
                        _PreparedChunk(
                            lines=retry_sources,
                            segment=chunk.segment,
                            chunk_start_ms=chunk.chunk_start_ms,
                            text="\n".join(line.text for line in retry_sources),
                        ),
                        audio_path,
                        work_dir,
                    )
                    retried_by_index = {line.index: line for line in retried}
                    chunk_lines = [retried_by_index.get(line.index, line) for line in chunk_lines]
                output.extend(chunk_lines)
            return output
        except Exception:
            lines: list[AlignLine] = []
            for chunk in batch:
                lines.extend(await self._align_chunk_lines_individually(aligner, chunk, audio_path, work_dir))
            return lines

    async def _align_chunk_lines_individually(
        self,
        aligner: Any,
        chunk: _PreparedChunk,
        audio_path: Path,
        work_dir: Path,
    ) -> list[AlignLine]:
        lines = []
        for line in chunk.lines:
            if line.duration_ms <= 0 or line.duration_ms > self.settings.qwen_max_window_ms:
                lines.append(_fallback_line(line))
                continue
            segment = work_dir / "qwen3-single-fallback" / f"line-{line.index}.wav"
            try:
                await extract_window(audio_path, segment, line.start_ms, line.duration_ms)
                result = await asyncio.to_thread(
                    aligner.align,
                    audio=str(segment),
                    text=line.text,
                    language=language_for_line(line.text),
                )
                lines.append(_line_from_items(line, _result_items(result), line.start_ms, self.settings.max_boundary_slop_ms))
            except Exception:
                lines.append(_fallback_line(line))
        return lines

    async def _get_aligner(self) -> Any:
        if self._aligner is not None:
            return self._aligner
        async with self._lock:
            if self._aligner is not None:
                return self._aligner
            self._aligner = await asyncio.to_thread(self._load_aligner)
            return self._aligner

    def _load_aligner(self) -> Any:
        try:
            import torch
            from qwen_asr import Qwen3ForcedAligner
        except Exception as exc:
            raise EngineUnavailable(
                "Qwen3 engine requires qwen_asr and torch; install the Qwen3-ASR package and model weights"
            ) from exc
        dtype = getattr(torch, self.settings.qwen_dtype, None)
        if dtype is None:
            raise EngineUnavailable(f"unsupported torch dtype: {self.settings.qwen_dtype}")
        aligner = Qwen3ForcedAligner.from_pretrained(
            self.settings.qwen_model_path,
            dtype=dtype,
            device_map=self.settings.qwen_device_map,
        )
        _install_acoustic_subword_tokenizer(aligner)
        return aligner


def _result_items(result: Any) -> list[Any]:
    if isinstance(result, list) and result:
        first = result[0]
        if hasattr(first, "items"):
            return list(first.items)
        return list(first)
    return []


def _result_items_for_batch(result: Any, expected_count: int) -> list[list[Any]]:
    if isinstance(result, list) and len(result) == expected_count:
        return [_items_from_result_item(item) for item in result]
    if expected_count == 1:
        return [_result_items(result)]
    raise AlignmentRejected("Qwen3 batch result count does not match request count")


def _items_from_result_item(item: Any) -> list[Any]:
    if hasattr(item, "items"):
        return list(item.items)
    if isinstance(item, list):
        return item
    return []


def _line_from_items(
    line: LyricLineInput,
    items: list[Any],
    window_start_ms: int,
    max_boundary_slop_ms: int,
) -> AlignLine:
    items_for_line: list[TimedText] = []
    last_start = -1
    zero_duration = 0
    for item in items:
        text = str(getattr(item, "text", "")).strip()
        if not text:
            continue
        start_ms = window_start_ms + round(float(getattr(item, "start_time", 0.0)) * 1000)
        end_ms = window_start_ms + round(float(getattr(item, "end_time", 0.0)) * 1000)
        start_ms = max(0, start_ms)
        duration_ms = max(0, end_ms - start_ms)
        if duration_ms == 0:
            zero_duration += 1
        if start_ms < last_start:
            start_ms = last_start
        last_start = start_ms
        items_for_line.append(TimedText(text=text, start_ms=start_ms, end_ms=start_ms + duration_ms, confidence=1.0))

    tokens, match_confidence = align_timed_items_to_line(
        line,
        items_for_line,
        allow_parts=True,
        items_are_parts=True,
    )
    tokens = _merge_zero_or_overlap_clusters(tokens)
    confidence = min(match_confidence, _confidence(line, tokens, zero_duration, max_boundary_slop_ms))
    return AlignLine(index=line.index, text=line.text, confidence=confidence, tokens=tokens)


def _lines_from_chunk(
    chunk: _PreparedChunk,
    items: list[Any],
    max_boundary_slop_ms: int,
) -> list[AlignLine]:
    timed_items = _timed_items_from_qwen(items, chunk.chunk_start_ms)
    cursor = 0
    output = []
    for line in chunk.lines:
        line_items, cursor = _consume_items_for_line(line, timed_items, cursor)
        output.append(_line_from_timed_items(line, line_items, max_boundary_slop_ms))
    return output


def _timed_items_from_qwen(items: list[Any], offset_ms: int) -> list[TimedText]:
    timed_items: list[TimedText] = []
    last_start = -1
    for item in items:
        text = str(getattr(item, "text", "")).strip()
        if not text:
            continue
        start_ms = offset_ms + round(float(getattr(item, "start_time", 0.0)) * 1000)
        end_ms = offset_ms + round(float(getattr(item, "end_time", 0.0)) * 1000)
        start_ms = max(0, start_ms)
        if start_ms < last_start:
            start_ms = last_start
        last_start = start_ms
        timed_items.append(TimedText(text=text, start_ms=start_ms, end_ms=max(start_ms, end_ms), confidence=1.0))
    return timed_items


def _consume_items_for_line(line: LyricLineInput, items: list[TimedText], cursor: int) -> tuple[list[TimedText], int]:
    original_cursor = cursor
    expected = normalize_for_match(line.text)
    collected = []
    collected_norm = ""
    while cursor < len(items):
        item = items[cursor]
        item_norm = normalize_for_match(item.text)
        if not item_norm:
            cursor += 1
            continue
        next_norm = collected_norm + item_norm
        if expected.startswith(next_norm) or next_norm.startswith(expected) or not collected:
            collected.append(item)
            collected_norm = next_norm
            cursor += 1
            if collected_norm == expected:
                break
            continue
        break
    if collected_norm != expected:
        return [], original_cursor
    return collected, cursor


def _line_from_timed_items(
    line: LyricLineInput,
    items_for_line: list[TimedText],
    max_boundary_slop_ms: int,
) -> AlignLine:
    zero_duration = sum(1 for item in items_for_line if item.duration_ms <= 0)
    tokens, match_confidence = align_timed_items_to_line(
        line,
        items_for_line,
        allow_parts=True,
        items_are_parts=True,
    )
    tokens = _merge_zero_or_overlap_clusters(tokens)
    confidence = min(match_confidence, _confidence(line, tokens, zero_duration, max_boundary_slop_ms))
    return AlignLine(index=line.index, text=line.text, confidence=confidence, tokens=tokens)


def _fallback_line(line: LyricLineInput) -> AlignLine:
    tokens, _ = align_timed_items_to_line(line, [], allow_parts=False)
    return AlignLine(index=line.index, text=line.text, confidence=0.0, tokens=tokens)


_ASCII_WORD_RE = re.compile(r"^[A-Za-z']+$")
_SUNG_G_DROPPING_RE = re.compile(r"in['’]?$", re.IGNORECASE)


def _install_acoustic_subword_tokenizer(aligner: Any) -> None:
    try:
        import pyphen
    except ImportError as exc:
        raise EngineUnavailable("Qwen3 acoustic subword timing requires pyphen") from exc

    processor = aligner.aligner_processor
    hyphenator = pyphen.Pyphen(lang="en_US", left=2, right=2)

    def tokenize_space_lang(text: str) -> list[str]:
        tokens: list[str] = []
        for raw_segment in text.split():
            cleaned = processor.clean_token(raw_segment)
            if not cleaned:
                continue
            for segment in processor.split_segment_with_chinese(cleaned):
                tokens.extend(_english_acoustic_pieces(segment, raw_segment, hyphenator))
        return tokens

    processor.tokenize_space_lang = tokenize_space_lang


def _english_acoustic_pieces(segment: str, raw_segment: str, hyphenator: Any) -> list[str]:
    if len(segment) < 5 or not _ASCII_WORD_RE.fullmatch(segment):
        return [segment]
    leading_apostrophe = "'" if segment.startswith("'") else ""
    trailing_apostrophe = "'" if segment.endswith("'") else ""
    letters = segment.strip("'")
    if len(letters) < 5 or not letters.isalpha():
        return [segment]

    pieces = hyphenator.inserted(letters, hyphen="\u0000").split("\u0000")
    raw_has_sung_apostrophe = bool(_SUNG_G_DROPPING_RE.search(raw_segment))
    if len(pieces) <= 1 and raw_has_sung_apostrophe and letters.lower().endswith("in"):
        expanded = hyphenator.inserted(letters + "g", hyphen="\u0000").split("\u0000")
        if len(expanded) > 1 and expanded[-1].lower().endswith("g"):
            expanded[-1] = expanded[-1][:-1]
            pieces = expanded

    if len(pieces) <= 1 or any(not piece for piece in pieces) or "".join(pieces) != letters:
        return [segment]
    pieces[0] = leading_apostrophe + pieces[0]
    pieces[-1] += trailing_apostrophe
    return pieces


def _merge_zero_or_overlap_clusters(tokens: list[Any]) -> list[Any]:
    if not tokens:
        return []
    merged = []
    cluster = [tokens[0]]
    for token in tokens[1:]:
        prev = cluster[-1]
        prev_end = prev.start_ms + prev.duration_ms
        severe_overlap = token.start_ms < prev_end - 20
        same_zero_point = token.start_ms == prev.start_ms and (token.duration_ms <= 0 or prev.duration_ms <= 0)
        if token.duration_ms <= 0 or prev.duration_ms <= 0 or severe_overlap or same_zero_point:
            cluster.append(token)
        else:
            merged.append(_merge_cluster(cluster))
            cluster = [token]
    merged.append(_merge_cluster(cluster))
    return merged


def _merge_cluster(cluster: list[Any]) -> Any:
    if len(cluster) == 1:
        return cluster[0]
    start_ms = min(token.start_ms for token in cluster)
    end_ms = max(token.start_ms + token.duration_ms for token in cluster)
    confidence = min(token.confidence for token in cluster)
    return cluster[0].model_copy(
        update={
            "text": "".join(token.text for token in cluster),
            "start_ms": start_ms,
            "duration_ms": max(0, end_ms - start_ms),
            "confidence": confidence,
            "parts": [],
        }
    )


def _line_needs_precise_retry(
    source: LyricLineInput,
    line: AlignLine,
    min_confidence: float,
) -> bool:
    return (
        line.confidence < min_confidence
        or not line.tokens
        or any(token.duration_ms <= 0 for token in line.tokens)
        or any(
            token.start_ms >= source.end_ms
            or token.start_ms + token.duration_ms <= source.start_ms
            for token in line.tokens
        )
    )


def _confidence(
    line: LyricLineInput,
    tokens: list[Any],
    zero_duration_count: int,
    max_boundary_slop_ms: int,
) -> float:
    if not tokens:
        return 0.0
    expected = normalize_for_match(line.text)
    actual = normalize_for_match("".join(token.text for token in tokens))
    text_score = difflib.SequenceMatcher(a=expected, b=actual).ratio() if expected else 0.0
    monotonic = all(
        token.start_ms + token.duration_ms <= nxt.start_ms + max_boundary_slop_ms
        for token, nxt in zip(tokens, tokens[1:])
    )
    first = tokens[0].start_ms
    last = max(token.start_ms + token.duration_ms for token in tokens)
    left_slop = abs(first - line.start_ms)
    right_slop = abs(last - line.end_ms)
    boundary_score = 1.0 - min(1.0, max(left_slop, right_slop) / max(1, line.duration_ms))
    zero_penalty = min(0.35, zero_duration_count * 0.08)
    score = text_score * 0.62 + boundary_score * 0.28 + (0.10 if monotonic else 0.0) - zero_penalty
    return round(max(0.0, min(1.0, score)), 3)
