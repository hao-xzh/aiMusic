from __future__ import annotations

import tempfile
from pathlib import Path

from .audio import cleanup, download_audio, maybe_separate_vocals
from .cache import JsonDiskCache, stable_hash
from .config import Settings, settings
from .engines import Qwen3ForcedAlignerEngine, StarsSubprocessEngine
from .errors import AlignmentRejected, EngineUnavailable
from .schemas import AlignRequest, AlignResponse, AlignerInfo
from .validate import response_confidence, validate_lines


class AlignmentService:
    def __init__(self, cfg: Settings = settings):
        self.settings = cfg
        assert cfg.result_cache_dir is not None
        self.cache = JsonDiskCache(cfg.result_cache_dir)
        self._engines = {
            "qwen3": Qwen3ForcedAlignerEngine(cfg),
            "stars": StarsSubprocessEngine(cfg),
        }

    async def align(self, request: AlignRequest) -> AlignResponse:
        engine_name = request.engine or self.settings.default_engine
        engine = self._engines.get(engine_name)
        if engine is None:
            raise EngineUnavailable(f"unknown alignment engine: {engine_name}")

        cache_key = self._cache_key(request, engine_name, engine.info.version)
        cached = self.cache.get(cache_key)
        if cached is not None:
            return AlignResponse.model_validate(cached)

        audio_path = await download_audio(str(request.audio_url), request.audio_key, self.settings)
        assert self.settings.tmp_dir is not None
        work_dir = Path(tempfile.mkdtemp(prefix="align-", dir=str(self.settings.tmp_dir)))
        try:
            # STARS is trained for singing alignment and benefits from an isolated
            # vocal stem. Qwen's general forced aligner is more reliable on the
            # original mix: separation artifacts can erase short rap consonants.
            model_audio = (
                await maybe_separate_vocals(audio_path, self.settings, work_dir)
                if engine_name == "stars"
                else audio_path
            )
            raw_lines = await engine.align(model_audio, request.lines, work_dir)
            lines = validate_lines(request.lines, raw_lines, self.settings)
            confidence = response_confidence(lines)
            if confidence < self.settings.min_response_confidence:
                raise AlignmentRejected("response confidence is below configured minimum")
            response = AlignResponse(
                version=2,
                track_id=request.track_id,
                audio_key=request.audio_key,
                lyric_key=request.lyric_key,
                aligner=AlignerInfo(
                    name=engine.info.name,
                    version=engine.info.version,
                    model=engine.info.model,
                ),
                confidence=confidence,
                lines=lines,
            )
            self.cache.set(cache_key, response.cache_payload())
            return response
        finally:
            cleanup([work_dir])

    def _cache_key(self, request: AlignRequest, engine_name: str, engine_version: str) -> str:
        line_payload = [
            {
                "index": line.index,
                "startMs": line.start_ms,
                "durationMs": line.duration_ms,
                "text": line.text,
                "tokens": [token.model_dump(mode="json", by_alias=True) for token in (line.tokens or [])],
            }
            for line in request.lines
        ]
        return stable_hash(
            {
                "version": request.version,
                "trackId": request.track_id,
                "audioKey": request.audio_key,
                "lyricKey": request.lyric_key,
                "engine": engine_name,
                "engineVersion": engine_version,
                "qwenModelPath": self.settings.qwen_model_path,
                "qwenBatchSize": self.settings.qwen_batch_size,
                "qwenWindowPadMs": self.settings.qwen_window_pad_ms,
                "qwenChunkMinMs": self.settings.qwen_chunk_min_ms,
                "qwenChunkTargetMs": self.settings.qwen_chunk_target_ms,
                "qwenMaxWindowMs": self.settings.qwen_max_window_ms,
                "enableDemucs": self.settings.enable_demucs,
                "lines": line_payload,
            }
        )


alignment_service = AlignmentService()
