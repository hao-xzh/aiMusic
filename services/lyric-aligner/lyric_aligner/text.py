from __future__ import annotations

import difflib
import re
import unicodedata
from dataclasses import dataclass

from .schemas import AlignPart, AlignToken, LyricLineInput


_WORD_RE = re.compile(r"(?:[A-Za-z0-9]+(?:['’][A-Za-z0-9]+)*|['’][A-Za-z0-9]+)")
_SILENCE_LABELS = {"", "sil", "sp", "<AP>", "<SP>", "|", "#"}


@dataclass(frozen=True)
class TextToken:
    text: str
    kind: str  # cjk or word


@dataclass(frozen=True)
class SourceTokenSpan:
    text: str
    core_text: str
    start_char: int
    end_char: int


@dataclass(frozen=True)
class TimedText:
    text: str
    start_ms: int
    end_ms: int
    confidence: float = 1.0
    parts: tuple["TimedText", ...] = ()

    @property
    def duration_ms(self) -> int:
        return max(0, self.end_ms - self.start_ms)


def is_cjk_char(ch: str) -> bool:
    code = ord(ch)
    return (
        0x4E00 <= code <= 0x9FFF
        or 0x3400 <= code <= 0x4DBF
        or 0x20000 <= code <= 0x2A6DF
        or 0x2A700 <= code <= 0x2B73F
        or 0x2B740 <= code <= 0x2B81F
        or 0x2B820 <= code <= 0x2CEAF
        or 0xF900 <= code <= 0xFAFF
    )


def tokenize_mixed(text: str) -> list[TextToken]:
    tokens: list[TextToken] = []
    i = 0
    while i < len(text):
        ch = text[i]
        if is_cjk_char(ch):
            tokens.append(TextToken(ch, "cjk"))
            i += 1
            continue
        match = _WORD_RE.match(text, i)
        if match:
            tokens.append(TextToken(match.group(0), "word"))
            i = match.end()
            continue
        i += 1
    return tokens


def language_for_line(text: str) -> str:
    cjk = sum(1 for ch in text if is_cjk_char(ch))
    latin = sum(1 for ch in text if "LATIN" in unicodedata.name(ch, ""))
    if cjk and cjk >= latin:
        return "Chinese"
    return "English"


def normalize_for_match(text: str) -> str:
    return "".join(ch.lower() for ch in text if is_cjk_char(ch) or ch.isalnum())


def build_source_spans(line: LyricLineInput) -> list[SourceTokenSpan]:
    core_ranges = _core_ranges(line.text)
    if not core_ranges:
        return [SourceTokenSpan(line.text, _core_text(line.text), 0, len(line.text))]

    spans = []
    for idx, (core_start, core_end) in enumerate(core_ranges):
        span_start = 0 if idx == 0 else core_start
        span_end = core_ranges[idx + 1][0] if idx + 1 < len(core_ranges) else len(line.text)
        spans.append(SourceTokenSpan(line.text[span_start:span_end], line.text[core_start:core_end], span_start, span_end))
    return spans


def align_timed_items_to_line(
    line: LyricLineInput,
    items: list[TimedText],
    *,
    token_parts: dict[int, list[TimedText]] | None = None,
    allow_parts: bool,
    items_are_parts: bool = False,
) -> tuple[list[AlignToken], float]:
    spans = build_source_spans(line)
    item_idx = 0
    tokens: list[AlignToken] = []
    matched_text = ""
    used_items = 0
    for span_idx, span in enumerate(spans):
        target = normalize_for_match(span.core_text)
        collected: list[TimedText] = []
        collected_norm = ""
        while item_idx < len(items):
            item = items[item_idx]
            item_norm = normalize_for_match(item.text)
            if not item_norm:
                item_idx += 1
                continue
            if not target:
                break
            next_norm = collected_norm + item_norm
            if target.startswith(next_norm) or next_norm.startswith(target) or not collected:
                collected.append(item)
                collected_norm = next_norm
                item_idx += 1
                if collected_norm == target:
                    break
                continue
            break
        matched_text += collected_norm
        if collected:
            used_items += len(collected)
        token = _token_from_collection(
            line,
            span,
            collected,
            token_parts.get(span_idx, []) if token_parts else [],
            allow_parts,
            items_are_parts,
        )
        tokens.append(token)

    expected_norm = normalize_for_match(line.text)
    text_score = difflib.SequenceMatcher(a=expected_norm, b=matched_text).ratio() if expected_norm else 0.0
    usage_score = min(1.0, used_items / max(1, len([item for item in items if normalize_for_match(item.text)])))
    return tokens, round(max(0.0, min(1.0, text_score * 0.75 + usage_score * 0.25)), 3)


def silence_label(label: str) -> bool:
    return label.strip() in _SILENCE_LABELS


def _core_ranges(text: str) -> list[tuple[int, int]]:
    ranges: list[tuple[int, int]] = []
    i = 0
    while i < len(text):
        ch = text[i]
        if is_cjk_char(ch):
            ranges.append((i, i + 1))
            i += 1
            continue
        match = _WORD_RE.match(text, i)
        if match:
            ranges.append((match.start(), match.end()))
            i = match.end()
            continue
        i += 1
    return ranges


def _core_text(text: str) -> str:
    pieces = [text[start:end] for start, end in _core_ranges(text)]
    return "".join(pieces)


def _token_from_collection(
    line: LyricLineInput,
    span: SourceTokenSpan,
    collected: list[TimedText],
    phone_parts: list[TimedText],
    allow_parts: bool,
    items_are_parts: bool,
) -> AlignToken:
    if collected:
        start_ms = min(item.start_ms for item in collected)
        end_ms = max(item.end_ms for item in collected)
        confidence = min(item.confidence for item in collected)
    else:
        ratio_start = span.start_char / max(1, len(line.text))
        ratio_end = span.end_char / max(1, len(line.text))
        start_ms = line.start_ms + round(line.duration_ms * ratio_start)
        end_ms = line.start_ms + round(line.duration_ms * ratio_end)
        confidence = 0.25
    start_ms = max(0, start_ms)
    end_ms = max(start_ms, end_ms)
    if allow_parts and items_are_parts:
        parts = _parts_from_text_items(span.text, collected, start_ms, end_ms, confidence)
    elif allow_parts:
        parts = _parts_from_phones(span.text, phone_parts, start_ms, end_ms, confidence)
    else:
        parts = []
    return AlignToken(
        text=span.text,
        start_ms=start_ms,
        duration_ms=end_ms - start_ms,
        confidence=max(0.0, min(1.0, confidence)),
        parts=parts,
    )


def _parts_from_phones(
    token_text: str,
    phones: list[TimedText],
    token_start_ms: int,
    token_end_ms: int,
    confidence: float,
) -> list[AlignPart]:
    phones = [phone for phone in phones if not silence_label(phone.text) and phone.end_ms > phone.start_ms]
    if not phones:
        return []
    chunks = _split_text_for_phone_count(token_text, len(phones))
    if len(chunks) != len(phones):
        return []
    parts: list[AlignPart] = []
    for idx, (chunk, phone) in enumerate(zip(chunks, phones)):
        start = max(token_start_ms, phone.start_ms)
        end = min(token_end_ms, phone.end_ms)
        if idx == 0:
            start = token_start_ms
        if idx == len(chunks) - 1:
            end = token_end_ms
        if end < start:
            end = start
        parts.append(AlignPart(text=chunk, start_ms=start, duration_ms=end - start, confidence=confidence))
    if "".join(part.text for part in parts) != token_text:
        return []
    return parts


def _parts_from_text_items(
    token_text: str,
    items: list[TimedText],
    token_start_ms: int,
    token_end_ms: int,
    confidence: float,
) -> list[AlignPart]:
    pieces = [item for item in items if normalize_for_match(item.text)]
    if len(pieces) <= 1:
        return []
    normalized_pieces = [normalize_for_match(item.text) for item in pieces]
    if "".join(normalized_pieces) != normalize_for_match(token_text):
        return []

    normalized_boundaries = [
        index + 1
        for index, char in enumerate(token_text)
        if is_cjk_char(char) or char.isalnum()
    ]
    text_chunks: list[str] = []
    normalized_cursor = 0
    text_cursor = 0
    for piece in normalized_pieces[:-1]:
        normalized_cursor += len(piece)
        if normalized_cursor <= 0 or normalized_cursor >= len(normalized_boundaries):
            return []
        text_end = normalized_boundaries[normalized_cursor - 1]
        text_chunks.append(token_text[text_cursor:text_end])
        text_cursor = text_end
    text_chunks.append(token_text[text_cursor:])
    if any(not chunk for chunk in text_chunks) or "".join(text_chunks) != token_text:
        return []

    parts: list[AlignPart] = []
    previous_end = token_start_ms
    for index, (text_chunk, item) in enumerate(zip(text_chunks, pieces)):
        start_ms = max(token_start_ms, item.start_ms)
        end_ms = min(token_end_ms, item.end_ms)
        if index == 0:
            start_ms = token_start_ms
        if index == len(pieces) - 1:
            end_ms = token_end_ms
        if start_ms < previous_end or end_ms <= start_ms:
            return []
        parts.append(
            AlignPart(
                text=text_chunk,
                start_ms=start_ms,
                duration_ms=end_ms - start_ms,
                confidence=confidence,
            )
        )
        previous_end = end_ms
    return parts


def _split_text_for_phone_count(text: str, count: int) -> list[str]:
    if count <= 0:
        return []
    if count == 1:
        return [text]
    core_ranges = _core_ranges(text)
    if not core_ranges:
        return [text]
    core_start = core_ranges[0][0]
    core_end = core_ranges[-1][1]
    prefix = text[:core_start]
    core = text[core_start:core_end]
    suffix = text[core_end:]
    if len(core) < count:
        return [text]
    base = _split_evenly_by_chars(core, count)
    base[0] = prefix + base[0]
    base[-1] = base[-1] + suffix
    return base


def _split_evenly_by_chars(text: str, count: int) -> list[str]:
    chunks: list[str] = []
    last = 0
    for idx in range(1, count):
        boundary = round(len(text) * idx / count)
        boundary = min(len(text), max(last + 1, boundary))
        chunks.append(text[last:boundary])
        last = boundary
    chunks.append(text[last:])
    return [chunk for chunk in chunks if chunk]
