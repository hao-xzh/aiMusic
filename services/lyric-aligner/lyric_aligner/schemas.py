from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, HttpUrl, field_validator, model_validator


def to_camel(value: str) -> str:
    head, *tail = value.split("_")
    return head + "".join(part[:1].upper() + part[1:] for part in tail)


class CamelModel(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="forbid",
    )


class LyricPartInput(CamelModel):
    text: str = Field(min_length=1)
    start_ms: int | None = Field(default=None, ge=0)
    duration_ms: int | None = Field(default=None, ge=0)


class LyricTokenInput(CamelModel):
    text: str = Field(min_length=1)
    start_ms: int | None = Field(default=None, ge=0)
    duration_ms: int | None = Field(default=None, ge=0)
    parts: list[LyricPartInput] | None = None

    @model_validator(mode="after")
    def parts_reconstruct_token(self) -> "LyricTokenInput":
        if self.parts is not None and "".join(part.text for part in self.parts) != self.text:
            raise ValueError("tokens[].parts text must reconstruct token text exactly")
        return self


class LyricLineInput(CamelModel):
    index: int = Field(ge=0)
    start_ms: int = Field(ge=0)
    duration_ms: int = Field(gt=0)
    text: str = Field(min_length=1)
    tokens: list[LyricTokenInput] | None = None

    @property
    def end_ms(self) -> int:
        return self.start_ms + self.duration_ms

    @model_validator(mode="after")
    def tokens_reconstruct_line(self) -> "LyricLineInput":
        if self.tokens is not None and "".join(token.text for token in self.tokens) != self.text:
            raise ValueError("lines[].tokens text must reconstruct line text exactly")
        return self


class AlignRequest(CamelModel):
    version: Literal[2]
    track_id: str = Field(min_length=1, max_length=200)
    audio_url: HttpUrl
    audio_key: str = Field(min_length=1, max_length=300)
    lyric_key: str = Field(min_length=1, max_length=300)
    lines: list[LyricLineInput] = Field(min_length=1, max_length=500)
    engine: str | None = Field(default=None, max_length=32)

    @field_validator("audio_key", "lyric_key", "track_id")
    @classmethod
    def no_control_chars(cls, value: str) -> str:
        if any(ord(ch) < 32 for ch in value):
            raise ValueError("control characters are not allowed")
        return value

    @model_validator(mode="after")
    def ensure_sorted_lines(self) -> "AlignRequest":
        indexes = [line.index for line in self.lines]
        if len(set(indexes)) != len(indexes):
            raise ValueError("line indexes must be unique")
        starts = [line.start_ms for line in self.lines]
        if starts != sorted(starts):
            raise ValueError("lines must be sorted by startMs")
        return self


class AlignerInfo(CamelModel):
    name: str
    version: str
    model: str


class AlignPart(CamelModel):
    text: str
    start_ms: int
    duration_ms: int
    confidence: float = Field(ge=0.0, le=1.0)


class AlignToken(CamelModel):
    text: str
    start_ms: int
    duration_ms: int
    confidence: float = Field(ge=0.0, le=1.0)
    parts: list[AlignPart] = Field(default_factory=list)

    @model_validator(mode="after")
    def parts_reconstruct_token(self) -> "AlignToken":
        if self.parts and "".join(part.text for part in self.parts) != self.text:
            raise ValueError("parts text must reconstruct token text")
        return self


class AlignLine(CamelModel):
    index: int
    text: str
    confidence: float = Field(ge=0.0, le=1.0)
    tokens: list[AlignToken]

    @model_validator(mode="after")
    def tokens_reconstruct_line(self) -> "AlignLine":
        if "".join(token.text for token in self.tokens) != self.text:
            raise ValueError("tokens text must reconstruct line text exactly")
        return self


class AlignResponse(CamelModel):
    version: Literal[2]
    track_id: str
    audio_key: str
    lyric_key: str
    aligner: AlignerInfo
    confidence: float = Field(ge=0.0, le=1.0)
    lines: list[AlignLine]

    def cache_payload(self) -> dict[str, Any]:
        return self.model_dump(mode="json", by_alias=True)
