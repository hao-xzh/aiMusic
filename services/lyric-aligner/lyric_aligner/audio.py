from __future__ import annotations

import asyncio
import ipaddress
import json
import os
import shutil
import socket
import uuid
import wave
import weakref
from pathlib import Path
from typing import Iterable
from urllib.parse import urljoin, urlparse

import httpx

from .cache import key_hash
from .config import Settings
from .errors import DownloadError, EngineUnavailable


_AUDIO_SUFFIXES = {
    "audio/mpeg": ".mp3",
    "audio/mp3": ".mp3",
    "audio/wav": ".wav",
    "audio/x-wav": ".wav",
    "audio/flac": ".flac",
    "audio/ogg": ".ogg",
    "audio/aac": ".aac",
    "audio/mp4": ".m4a",
    "video/mp4": ".m4a",
}
_DOWNLOAD_LOCKS: weakref.WeakValueDictionary[str, asyncio.Lock] = weakref.WeakValueDictionary()


def _host_is_private(host: str) -> bool:
    try:
        addresses = socket.getaddrinfo(host, None)
    except socket.gaierror as exc:
        raise DownloadError("audio host could not be resolved") from exc
    for family, _, _, _, sockaddr in addresses:
        if family not in (socket.AF_INET, socket.AF_INET6):
            continue
        ip = ipaddress.ip_address(sockaddr[0])
        if ip.is_private or ip.is_loopback or ip.is_link_local or ip.is_multicast or ip.is_reserved:
            return True
    return False


def validate_audio_url(url: str, settings: Settings) -> None:
    parsed = urlparse(url)
    allowed = {"https"}
    if settings.allow_http:
        allowed.add("http")
    if parsed.scheme not in allowed:
        raise DownloadError("audioUrl scheme is not allowed")
    if not parsed.hostname:
        raise DownloadError("audioUrl host is required")
    if not settings.allow_private_hosts and _host_is_private(parsed.hostname):
        raise DownloadError("audioUrl resolves to a private or reserved address")


async def download_audio(url: str, audio_key: str, settings: Settings) -> Path:
    digest = key_hash(audio_key)
    lock = _DOWNLOAD_LOCKS.get(digest)
    if lock is None:
        lock = asyncio.Lock()
        _DOWNLOAD_LOCKS[digest] = lock
    async with lock:
        return await _download_audio_once(url, digest, settings)


async def _download_audio_once(url: str, digest: str, settings: Settings) -> Path:
    validate_audio_url(url, settings)
    assert settings.audio_cache_dir is not None
    cache_dir = settings.audio_cache_dir / digest[:2]
    cache_dir.mkdir(parents=True, exist_ok=True)
    final = cache_dir / f"{digest}.audio"
    meta = cache_dir / f"{digest}.json"
    if final.exists() and meta.exists():
        return final

    headers = {"User-Agent": settings.user_agent}
    if settings.download_referer:
        headers["Referer"] = settings.download_referer

    tmp = final.with_suffix(f".{os.getpid()}.{uuid.uuid4().hex}.tmp")
    size = 0
    content_type = ""
    timeout = httpx.Timeout(settings.download_timeout_seconds)
    current_url = url
    response: httpx.Response | None = None
    try:
        async with httpx.AsyncClient(follow_redirects=False, timeout=timeout) as client:
            for _ in range(settings.max_redirects + 1):
                validate_audio_url(current_url, settings)
                response = await client.send(client.build_request("GET", current_url, headers=headers), stream=True)
                if response.status_code in {301, 302, 303, 307, 308}:
                    location = response.headers.get("location")
                    await response.aclose()
                    response = None
                    if not location:
                        raise DownloadError("audio redirect is missing Location")
                    current_url = urljoin(current_url, location)
                    validate_audio_url(current_url, settings)
                    continue
                break
            else:
                raise DownloadError("audio download exceeded configured max_redirects")

            if response is None:
                raise DownloadError("audio download did not return a response")
            try:
                if response.status_code >= 400:
                    raise DownloadError(f"audio download failed with HTTP {response.status_code}")
                content_length = response.headers.get("content-length")
                if content_length:
                    try:
                        if int(content_length) > settings.max_audio_bytes:
                            raise DownloadError("audio file is larger than configured max_audio_bytes")
                    except ValueError:
                        pass
                content_type = response.headers.get("content-type", "").split(";")[0].strip().lower()
                with tmp.open("wb") as handle:
                    async for chunk in response.aiter_bytes():
                        if not chunk:
                            continue
                        size += len(chunk)
                        if size > settings.max_audio_bytes:
                            raise DownloadError("audio file exceeded configured max_audio_bytes")
                        handle.write(chunk)
                    handle.flush()
                    os.fsync(handle.fileno())
            finally:
                await response.aclose()

        suffix = _AUDIO_SUFFIXES.get(content_type)
        if suffix:
            typed = final.with_suffix(suffix)
            os.replace(tmp, typed)
            os.replace(typed, final)
        else:
            os.replace(tmp, final)

        duration = await probe_duration_seconds(final)
        if duration > settings.max_audio_duration_seconds:
            raise DownloadError("audio duration exceeds configured max_audio_duration_seconds")
        _atomic_json(meta, {"audioKeyHash": digest, "bytes": size, "durationSeconds": duration})
        return final
    except Exception:
        tmp.unlink(missing_ok=True)
        final.unlink(missing_ok=True)
        meta.unlink(missing_ok=True)
        raise


async def probe_duration_seconds(path: Path) -> float:
    if shutil.which("ffprobe") is None:
        return await asyncio.to_thread(_probe_duration_with_pyav, path)
    proc = await asyncio.create_subprocess_exec(
        "ffprobe",
        "-v",
        "error",
        "-show_entries",
        "format=duration",
        "-of",
        "json",
        str(path),
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
    )
    stdout, stderr = await proc.communicate()
    if proc.returncode != 0:
        raise DownloadError(f"ffprobe could not read audio: {stderr.decode('utf-8', 'ignore')[:160]}")
    data = json.loads(stdout.decode("utf-8"))
    try:
        return float(data["format"]["duration"])
    except (KeyError, TypeError, ValueError) as exc:
        raise DownloadError("ffprobe did not return a usable duration") from exc


async def extract_window(source: Path, out_path: Path, start_ms: int, duration_ms: int) -> Path:
    if shutil.which("ffmpeg") is None:
        return await asyncio.to_thread(_extract_window_with_pyav, source, out_path, start_ms, duration_ms)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    tmp = out_path.with_suffix(f".{os.getpid()}.tmp.wav")
    proc = await asyncio.create_subprocess_exec(
        "ffmpeg",
        "-hide_banner",
        "-loglevel",
        "error",
        "-y",
        "-ss",
        f"{start_ms / 1000:.3f}",
        "-t",
        f"{duration_ms / 1000:.3f}",
        "-i",
        str(source),
        "-ac",
        "1",
        "-ar",
        "16000",
        str(tmp),
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
    )
    _, stderr = await proc.communicate()
    if proc.returncode != 0:
        raise EngineUnavailable(f"ffmpeg window extraction failed: {stderr.decode('utf-8', 'ignore')[:160]}")
    os.replace(tmp, out_path)
    return out_path


def _probe_duration_with_pyav(path: Path) -> float:
    try:
        import av  # type: ignore
    except Exception as exc:
        raise EngineUnavailable("ffprobe or PyAV is required to validate audio duration") from exc
    with av.open(str(path)) as container:
        if container.duration is not None:
            return float(container.duration) / float(av.time_base)
        audio_streams = [stream for stream in container.streams if stream.type == "audio"]
        if not audio_streams:
            raise DownloadError("audio file has no audio stream")
        stream = audio_streams[0]
        if stream.duration is not None:
            return float(stream.duration * stream.time_base)
        duration = 0.0
        for frame in container.decode(stream):
            if frame.time is not None:
                duration = max(duration, float(frame.time) + frame.samples / frame.sample_rate)
        if duration <= 0:
            raise DownloadError("PyAV did not return a usable duration")
        return duration


def _extract_window_with_pyav(source: Path, out_path: Path, start_ms: int, duration_ms: int) -> Path:
    try:
        import av  # type: ignore
        import numpy as np  # type: ignore
        from av.audio.resampler import AudioResampler  # type: ignore
    except Exception as exc:
        raise EngineUnavailable("ffmpeg or PyAV with numpy is required to segment audio windows") from exc
    out_path.parent.mkdir(parents=True, exist_ok=True)
    start_sample = max(0, round(start_ms * 16))
    end_sample = max(start_sample, start_sample + round(duration_ms * 16))
    chunks = []
    with av.open(str(source)) as container:
        streams = [stream for stream in container.streams if stream.type == "audio"]
        if not streams:
            raise DownloadError("audio file has no audio stream")
        resampler = AudioResampler(format="s16", layout="mono", rate=16000)
        for frame in container.decode(streams[0]):
            for resampled in resampler.resample(frame):
                data = resampled.to_ndarray()
                chunks.append(data.reshape(-1))
    if chunks:
        audio = np.concatenate(chunks)
    else:
        audio = np.zeros(0, dtype=np.int16)
    clipped = audio[start_sample:end_sample]
    if clipped.size < end_sample - start_sample:
        clipped = np.pad(clipped, (0, end_sample - start_sample - clipped.size))
    tmp = out_path.with_suffix(f".{os.getpid()}.tmp.wav")
    with wave.open(str(tmp), "wb") as handle:
        handle.setnchannels(1)
        handle.setsampwidth(2)
        handle.setframerate(16000)
        handle.writeframes(clipped.astype("<i2", copy=False).tobytes())
    os.replace(tmp, out_path)
    return out_path


async def maybe_separate_vocals(source: Path, settings: Settings, work_dir: Path) -> Path:
    if not settings.enable_demucs:
        return source
    if shutil.which(settings.demucs_cmd) is None:
        raise EngineUnavailable("Demucs is enabled but the demucs command is not available")
    out_root = work_dir / "demucs"
    proc = await asyncio.create_subprocess_exec(
        settings.demucs_cmd,
        "-n",
        settings.demucs_model,
        "--two-stems",
        "vocals",
        "-o",
        str(out_root),
        str(source),
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
    )
    _, stderr = await proc.communicate()
    if proc.returncode != 0:
        raise EngineUnavailable(f"Demucs vocal separation failed: {stderr.decode('utf-8', 'ignore')[:200]}")
    candidates = list(out_root.glob(f"**/{source.stem}/vocals.*")) + list(out_root.glob("**/vocals.*"))
    if not candidates:
        raise EngineUnavailable("Demucs completed but no vocals stem was found")
    return candidates[0]


def _atomic_json(path: Path, payload: dict[str, object]) -> None:
    tmp = path.with_suffix(f".{os.getpid()}.{uuid.uuid4().hex}.tmp")
    with tmp.open("w", encoding="utf-8") as handle:
        json.dump(payload, handle, ensure_ascii=False, separators=(",", ":"))
        handle.flush()
        os.fsync(handle.fileno())
    os.replace(tmp, path)


def cleanup(paths: Iterable[Path]) -> None:
    for path in paths:
        if path.is_dir():
            shutil.rmtree(path, ignore_errors=True)
        else:
            path.unlink(missing_ok=True)
