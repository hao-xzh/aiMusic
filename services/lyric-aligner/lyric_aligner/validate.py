from __future__ import annotations

import difflib

from .config import Settings
from .errors import AlignmentRejected
from .schemas import AlignLine, AlignToken, LyricLineInput
from .text import normalize_for_match


def validate_lines(
    source_lines: list[LyricLineInput],
    aligned_lines: list[AlignLine],
    settings: Settings,
) -> list[AlignLine]:
    by_index = {line.index: line for line in source_lines}
    output: list[AlignLine] = []
    for aligned in aligned_lines:
        source = by_index.get(aligned.index)
        if source is None:
            continue
        scored = _score_line(source, aligned, settings)
        if not _has_strictly_positive_durations(scored):
            continue
        if scored.confidence < settings.min_line_confidence and settings.low_confidence_policy == "drop":
            continue
        output.append(scored)
    if not output:
        raise AlignmentRejected("all aligned lines were rejected by confidence/window validation")
    return sorted(output, key=lambda item: item.index)


def response_confidence(lines: list[AlignLine]) -> float:
    if not lines:
        return 0.0
    token_count = sum(max(1, len(line.tokens)) for line in lines)
    weighted = sum(line.confidence * max(1, len(line.tokens)) for line in lines)
    return round(max(0.0, min(1.0, weighted / token_count)), 3)


def _score_line(source: LyricLineInput, aligned: AlignLine, settings: Settings) -> AlignLine:
    if not aligned.tokens:
        return aligned.model_copy(update={"confidence": 0.0})
    normalized_tokens = _monotonicize_tokens(source, aligned.tokens, settings.max_boundary_slop_ms)
    if not normalized_tokens or not _has_strictly_positive_durations(
        AlignLine(index=aligned.index, text=aligned.text, confidence=aligned.confidence, tokens=normalized_tokens)
    ):
        return aligned.model_copy(update={"confidence": 0.0, "tokens": normalized_tokens})
    penalties = 0.0
    if not _is_monotonic(normalized_tokens, settings.max_token_gap_ms):
        penalties += 0.35
    penalties += _window_penalty(source, normalized_tokens, settings.max_boundary_slop_ms)
    penalties += _coverage_penalty(source, normalized_tokens)
    confidence = round(max(0.0, min(1.0, aligned.confidence - penalties)), 3)
    adjusted_tokens = []
    for token in normalized_tokens:
        token_confidence = min(token.confidence, confidence)
        parts = [part.model_copy(update={"confidence": min(part.confidence, token_confidence)}) for part in token.parts]
        adjusted_tokens.append(token.model_copy(update={"confidence": token_confidence, "parts": parts}))
    return AlignLine(index=aligned.index, text=aligned.text, confidence=confidence, tokens=adjusted_tokens)


def _monotonicize_tokens(source: LyricLineInput, tokens: list[AlignToken], boundary_slop_ms: int) -> list[AlignToken]:
    normalized: list[AlignToken] = []
    for token in tokens:
        start = token.start_ms
        end = token.start_ms + token.duration_ms
        if start < source.start_ms and source.start_ms - start <= boundary_slop_ms:
            start = source.start_ms
        if end > source.end_ms and end - source.end_ms <= boundary_slop_ms:
            end = source.end_ms
        if normalized:
            prev = normalized[-1]
            prev_end = prev.start_ms + prev.duration_ms
            overlap = prev_end - start
            if 0 < overlap <= boundary_slop_ms:
                if start >= prev.start_ms:
                    normalized[-1] = _resize_token(prev, prev.start_ms, start)
                else:
                    start = prev_end
        if end < start:
            end = start
        normalized.append(_resize_token(token, start, end))
    return normalized


def _resize_token(token: AlignToken, start_ms: int, end_ms: int) -> AlignToken:
    parts = []
    for part in token.parts:
        part_start = min(max(part.start_ms, start_ms), end_ms)
        part_end = min(max(part.start_ms + part.duration_ms, part_start), end_ms)
        parts.append(part.model_copy(update={"start_ms": part_start, "duration_ms": part_end - part_start}))
    return token.model_copy(update={"start_ms": start_ms, "duration_ms": max(0, end_ms - start_ms), "parts": parts})


def _has_strictly_positive_durations(line: AlignLine) -> bool:
    for token in line.tokens:
        if token.duration_ms <= 0:
            return False
        for part in token.parts:
            if part.duration_ms <= 0:
                return False
    return True


def _is_monotonic(tokens: list[AlignToken], max_gap_ms: int) -> bool:
    for token, nxt in zip(tokens, tokens[1:]):
        token_end = token.start_ms + token.duration_ms
        if nxt.start_ms < token.start_ms:
            return False
        if nxt.start_ms - token_end > max_gap_ms:
            return False
    return True


def _window_penalty(source: LyricLineInput, tokens: list[AlignToken], max_boundary_slop_ms: int) -> float:
    first = min(token.start_ms for token in tokens)
    last = max(token.start_ms + token.duration_ms for token in tokens)
    if first < source.start_ms - max_boundary_slop_ms or last > source.end_ms + max_boundary_slop_ms:
        return 0.20
    line_span = max(1, source.duration_ms)
    covered = max(0, min(last, source.end_ms) - max(first, source.start_ms))
    coverage = covered / line_span
    if coverage < 0.35:
        return 0.25
    if coverage < 0.55:
        return 0.12
    return 0.0


def _coverage_penalty(source: LyricLineInput, tokens: list[AlignToken]) -> float:
    expected = normalize_for_match(source.text)
    actual = normalize_for_match("".join(token.text for token in tokens))
    if not expected:
        return 0.2
    ratio = difflib.SequenceMatcher(a=expected, b=actual).ratio()
    if ratio < 0.45:
        return 0.30
    if ratio < 0.70:
        return 0.15
    return 0.0
