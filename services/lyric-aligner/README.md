# Claudio Lyric Aligner

FastAPI service for Claudio lyric forced alignment. It is intentionally isolated under `services/lyric-aligner/` and does not depend on Android source code.

## What Is Implemented

- `POST /v1/align` protocol `version=2`.
- Safe audio download with scheme, per-redirect DNS/private-address validation, byte-size and duration gates.
- Audio cache keyed by `audioKey`; result cache keyed by `audioKey`, `lyricKey`, request lines, engine and model config. Signed `audioUrl` is never written to result cache.
- Optional `ALIGNER_API_TOKEN` Bearer authentication for `POST /v1/align`.
- Optional Demucs vocal separation for the STARS singing-alignment path. The Qwen fallback keeps the original mix because separation artifacts can erase short consonants in fast vocals.
- Official Qwen3 ForcedAligner adapter using `qwen_asr.Qwen3ForcedAligner.from_pretrained(...)`; it groups consecutive lyric lines into roughly 45-90s chunks by original line boundaries, with no default padding.
- STARS subprocess adapter contract using official-style `metadata.json`, checkpoint/config/phset arguments, official `output.json` parsing, and `words`/`phones` TextGrid tier fallback. It fails clearly when the phoneme command or required paths are not configured.
- Output text reconstruction is strict: `lines[].tokens[].text` concatenates to `line.text`; `token.parts` may be empty, but when present it concatenates to the token text. Spaces and punctuation are merged into neighboring pronunciation tokens.
- Qwen3 normally returns word/character timestamps. For long English words, this service inserts conservative orthographic subword boundaries into the same forced-alignment text without adding spaces or changing the lyric. Positive, monotonic acoustic boundaries become `parts`; zero-duration or overlapping candidates keep the whole word and do not create synthetic timing. STARS uses the phones tier/durations when its CUDA runtime is configured.
- Post-alignment validation for monotonic timestamps, small-overlap/boundary normalization, line window coverage, boundary slop and low-confidence policy.

Primary upstream references checked while implementing:

- Qwen3-ASR official repo and ForcedAligner example: `https://github.com/QwenLM/Qwen3-ASR`
- Qwen3 ForcedAligner model card: `https://huggingface.co/Qwen/Qwen3-ForcedAligner-0.6B`
- STARS official repo: `https://github.com/gwx314/STARS`

## Request

```json
{
  "version": 2,
  "trackId": "netease:123",
  "audioUrl": "https://signed.example/audio.m4a",
  "audioKey": "sha256-or-provider-cache-key",
  "lyricKey": "lyric-version-key",
  "lines": [
    {
      "index": 0,
      "startMs": 12000,
      "durationMs": 3600,
      "text": "welcome to 北京!",
      "tokens": [
        {"text": "welcome "},
        {"text": "to "},
        {"text": "北"},
        {"text": "京!"}
      ]
    }
  ]
}
```

Optional line `tokens` can be included by the client. If present, `tokens[].text` must concatenate exactly to `line.text`; old line-level `parts` are rejected. Prior token segmentation is included in the result-cache key, but output spans are canonicalized again from `line.text` using CJK characters plus English words/contractions, so fragmented YRC tokens such as `It` / `’` / `s ` do not leak into the response.

## Response

```json
{
  "version": 2,
  "trackId": "netease:123",
  "audioKey": "sha256-or-provider-cache-key",
  "lyricKey": "lyric-version-key",
  "aligner": {
    "name": "qwen3-forced-aligner",
    "version": "0.3.0",
    "model": "Qwen/Qwen3-ForcedAligner-0.6B"
  },
  "confidence": 0.86,
  "lines": [
    {
      "index": 0,
      "text": "welcome to 北京!",
      "confidence": 0.86,
      "tokens": [
        {
          "text": "welcome ",
          "startMs": 12080,
          "durationMs": 620,
          "confidence": 0.86,
          "parts": []
        },
        {
          "text": "to ",
          "startMs": 12820,
          "durationMs": 260,
          "confidence": 0.86,
          "parts": []
        },
        {
          "text": "北",
          "startMs": 13220,
          "durationMs": 300,
          "confidence": 0.86,
          "parts": []
        },
        {
          "text": "京!",
          "startMs": 13540,
          "durationMs": 360,
          "confidence": 0.86,
          "parts": []
        }
      ]
    }
  ]
}
```

Low-confidence lines are either returned with low `confidence` (`ALIGNER_LOW_CONFIDENCE_POLICY=mark`) or omitted (`drop`). Android should keep its fallback when a line is missing or below its threshold.

## Run Locally

```bash
cd /Volumes/soft/Claudio/services/lyric-aligner
python3 -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env
uvicorn lyric_aligner.main:app --host 0.0.0.0 --port 8080 --no-access-log
```

`ffmpeg` and `ffprobe` are preferred. If they are unavailable, the service tries PyAV for duration probing and mono 16 kHz wav window extraction. The default Qwen3 config expects CUDA and bfloat16. For CPU-only smoke startup, set:

```bash
ALIGNER_QWEN_DEVICE_MAP=cpu
ALIGNER_QWEN_DTYPE=float32
```

CPU alignment with a 0.6B audio model is expected to be slow.

Qwen chunking defaults:

```bash
ALIGNER_QWEN_WINDOW_PAD_MS=0
ALIGNER_QWEN_BATCH_SIZE=2
ALIGNER_QWEN_CHUNK_MIN_MS=45000
ALIGNER_QWEN_CHUNK_TARGET_MS=90000
```

English subword candidates use Pyphen hyphenation plus a dropped-`g` singing form such as `makin'`. Candidate spelling only decides where timestamp markers may be requested; Qwen still decides the acoustic boundaries. A word gets `parts` only when every returned part has a positive, monotonic duration and reconstructs the exact display text.

Do not use line-window padding as a drift fix: it can assign leading words to pre-line silence. If LRC drift needs tolerance, add a separate vocal/VAD or multi-candidate scoring stage.

## Docker

```bash
cd /Volumes/soft/Claudio/services/lyric-aligner
docker build -t claudio-lyric-aligner .
docker run --gpus all --env-file .env -p 8080:8080 -v "$PWD/data:/app/data" claudio-lyric-aligner
```

The Dockerfile installs CUDA base libraries and Python dependencies. It does not bundle model weights. Hugging Face or local model caches must be mounted/configured according to your deployment policy.

## STARS Engine

Set `ALIGNER_DEFAULT_ENGINE=stars` or pass `"engine":"stars"` in the request.

Required environment:

- `ALIGNER_STARS_REPO_DIR`: official STARS checkout.
- `ALIGNER_STARS_INFERENCE_CMD`: normally `python inference/stars.py`.
- `ALIGNER_STARS_CHECKPOINT`: matching checkpoint directory.
- `ALIGNER_STARS_CONFIG`: matching YAML config.
- `ALIGNER_STARS_PHSET`: matching phone set JSON.
- `ALIGNER_STARS_PHONEME_CMD`: command that converts text to `{"words":[...],"ph":[...],"ph2words":[...]}`. The adapter calls it as `<cmd> --text <line> --with_phsep`.

Official `scripts/mixedtext2phoneme.py` prints human-readable stdout instead of JSON. Use the included wrapper:

```bash
ALIGNER_STARS_PHONEME_CMD='python /app/tools/stars_mixedtext2json.py --stars-script /opt/STARS/scripts/mixedtext2phoneme.py'
```

The adapter writes per-line mono 16 kHz wav segments and a STARS `metadata.json` with `item_name`, `wav_fn`, `word`, `ph`, and `ph2words`, then runs:

```bash
python inference/stars.py --ckpt <ckpt> --config <config> --phset <phset> -o <output-dir> --metadata <metadata.json>
```

It parses official `output.json` first (`word_list`, `word_durs`, `ph_list`, `ph_durs`). If that is absent, it reads Praat `*.TextGrid` and only consumes the `words` and `phones` tiers. Technique tiers are ignored so they cannot be mixed into lyric tokens.

## Security And Logging

- Start uvicorn with `--no-access-log`; application errors never include `audioUrl`.
- Set `ALIGNER_API_TOKEN` to require `Authorization: Bearer <token>` for `POST /v1/align`.
- Cache metadata stores only hashed audio keys, byte size and duration.
- Result cache stores the response payload only: no signed URL, no request headers.
- HTTP URLs and private/reserved hosts are blocked by default, including every redirect target.

## License Boundary

This service code is project-local. Qwen3-ASR/Qwen3-ForcedAligner is distributed by Qwen under its upstream license, currently documented as Apache-2.0 in the official repo/model card. STARS has its own repository terms and includes a consent/copyright disclaimer. Demucs and any separation model weights have separate licenses. Production deployment must verify these upstream licenses and model-weight terms before serving users.
