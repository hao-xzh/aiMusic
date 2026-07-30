FROM nvidia/cuda:12.4.1-cudnn-devel-ubuntu22.04

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ffmpeg \
        git \
        python3 \
        python3-pip \
        python3-venv \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY requirements.txt .
RUN python3 -m pip install --upgrade pip \
    && python3 -m pip install -r requirements.txt

COPY lyric_aligner ./lyric_aligner
COPY tools ./tools
COPY .env.example ./.env.example

EXPOSE 8080
CMD ["uvicorn", "lyric_aligner.main:app", "--host", "0.0.0.0", "--port", "8080", "--no-access-log"]
