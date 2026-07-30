from __future__ import annotations

from pathlib import Path

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_prefix="ALIGNER_", extra="ignore")

    data_dir: Path = Field(default=Path("./data"))
    audio_cache_dir: Path | None = None
    result_cache_dir: Path | None = None
    tmp_dir: Path | None = None

    default_engine: str = "qwen3"
    api_token: str | None = None
    qwen_model_path: str = "Qwen/Qwen3-ForcedAligner-0.6B"
    qwen_device_map: str = "cuda:0"
    qwen_dtype: str = "bfloat16"
    qwen_max_window_ms: int = 300_000
    qwen_window_pad_ms: int = 0
    qwen_batch_size: int = 2
    qwen_chunk_min_ms: int = 45_000
    qwen_chunk_target_ms: int = 90_000

    stars_repo_dir: Path | None = None
    stars_inference_cmd: str | None = None
    stars_checkpoint: Path | None = None
    stars_config: Path | None = None
    stars_phset: Path | None = None
    stars_phoneme_cmd: str | None = None
    stars_cuda_visible_devices: str | None = None

    download_timeout_seconds: float = 20.0
    max_redirects: int = 5
    max_audio_bytes: int = 80 * 1024 * 1024
    max_audio_duration_seconds: float = 600.0
    allow_http: bool = False
    allow_private_hosts: bool = False
    download_referer: str | None = None
    user_agent: str = "ClaudioLyricAligner/0.1"

    enable_demucs: bool = False
    demucs_cmd: str = "demucs"
    demucs_model: str = "htdemucs"

    min_line_confidence: float = 0.55
    min_response_confidence: float = 0.55
    low_confidence_policy: str = "mark"  # mark or drop
    max_token_gap_ms: int = 2_000
    max_boundary_slop_ms: int = 650

    def resolve_dirs(self) -> None:
        self.data_dir.mkdir(parents=True, exist_ok=True)
        if self.audio_cache_dir is None:
            self.audio_cache_dir = self.data_dir / "audio"
        if self.result_cache_dir is None:
            self.result_cache_dir = self.data_dir / "results"
        if self.tmp_dir is None:
            self.tmp_dir = self.data_dir / "tmp"
        self.audio_cache_dir.mkdir(parents=True, exist_ok=True)
        self.result_cache_dir.mkdir(parents=True, exist_ok=True)
        self.tmp_dir.mkdir(parents=True, exist_ok=True)


settings = Settings()
settings.resolve_dirs()
