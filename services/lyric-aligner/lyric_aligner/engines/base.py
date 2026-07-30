from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Protocol

from ..schemas import AlignLine, LyricLineInput


@dataclass(frozen=True)
class EngineInfo:
    name: str
    version: str
    model: str


class AlignmentEngine(Protocol):
    info: EngineInfo

    async def align(self, audio_path: Path, lines: list[LyricLineInput], work_dir: Path) -> list[AlignLine]:
        ...
