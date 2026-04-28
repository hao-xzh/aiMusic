"use client";

import { z } from "zod";

import { parseJsonObjectLike } from "./ai-json";
import { ai, cache, netease, type TrackInfo } from "./tauri";
import { loadAnalysis, type TrackAnalysis } from "./audio-analysis";
import {
  buildRuleBasedSemanticProfile,
  needsSemanticIndex,
  saveTrackSemanticProfile,
  TRACK_SEMANTIC_VERSION,
  type TrackSemanticProfile,
} from "./track-semantic-profile";

export type SemanticIndexProgress = {
  total: number;
  done: number;
  skipped: number;
  failed: number;
};

export type SemanticIndexOptions = {
  concurrency?: number;
  onlyMissing?: boolean;
  onProgress?: (p: SemanticIndexProgress) => void;
};

const LlmSemanticSchema = z.object({
  language: z.object({
    primary: z.string(),
    secondary: z.array(z.string()).optional(),
    confidence: z.number().min(0).max(1).default(0.5),
  }),
  region: z.object({
    primary: z.string(),
    confidence: z.number().min(0).max(1).default(0.5),
  }),
  style: z.object({
    genres: z.array(z.string()).default([]),
    subGenres: z.array(z.string()).default([]),
    styleAnchors: z.array(z.string()).default([]),
  }),
  vibe: z.object({
    moods: z.array(z.string()).default([]),
    scenes: z.array(z.string()).default([]),
    textures: z.array(z.string()).default([]),
    energyWords: z.array(z.string()).default([]),
    tempoFeel: z.array(z.string()).default([]),
  }),
  vocal: z.object({
    type: z.string(),
    delivery: z.array(z.string()).default([]),
  }),
  negativeTags: z.array(z.string()).default([]),
  summary: z.string().default(""),
  embeddingText: z.string().default(""),
  confidence: z.number().min(0).max(1).default(0.5),
});

let inflight: Promise<SemanticIndexProgress> | null = null;

export async function indexTrackSemantics(
  track: TrackInfo,
): Promise<TrackSemanticProfile> {
  const [analysis, lyricsSample] = await Promise.all([
    loadAnalysis(track.id).catch(() => null),
    loadLyricsSample(track.id).catch(() => ""),
  ]);
  const base = buildRuleBasedSemanticProfile(track, analysis, lyricsSample);

  try {
    const llm = await callSemanticLlm(track, analysis, lyricsSample);
    const profile: TrackSemanticProfile = {
      ...base,
      language: {
        primary: coerceLanguage(llm.language.primary, base.language.primary),
        secondary: llm.language.secondary,
        confidence: llm.language.confidence,
      },
      region: {
        primary: coerceRegion(llm.region.primary, base.region.primary),
        confidence: llm.region.confidence,
      },
      style: {
        genres: clean(llm.style.genres).slice(0, 3),
        subGenres: clean(llm.style.subGenres).slice(0, 4),
        styleAnchors: clean(llm.style.styleAnchors).slice(0, 4),
      },
      vibe: {
        moods: clean(llm.vibe.moods).slice(0, 6),
        scenes: clean(llm.vibe.scenes).slice(0, 6),
        textures: clean(llm.vibe.textures).slice(0, 6),
        energyWords: clean(llm.vibe.energyWords).slice(0, 4),
        tempoFeel: clean(llm.vibe.tempoFeel).slice(0, 4),
      },
      vocal: {
        type: coerceVocalType(llm.vocal.type, base.vocal.type),
        delivery: clean(llm.vocal.delivery).slice(0, 4),
      },
      negativeTags: clean(llm.negativeTags).slice(0, 8),
      summary: llm.summary || base.summary,
      embeddingText: llm.embeddingText || base.embeddingText,
      confidence: llm.confidence,
      sources: {
        ...base.sources,
        llm: true,
      },
      updatedAt: Date.now(),
    };
    await saveTrackSemanticProfile(profile);
    return profile;
  } catch (e) {
    console.debug("[claudio] semantic LLM 标注失败，使用规则档案", track.id, e);
    await saveTrackSemanticProfile(base);
    return base;
  }
}

export async function indexLibrarySemantics(
  tracks: TrackInfo[],
  options: SemanticIndexOptions = {},
): Promise<SemanticIndexProgress> {
  if (inflight) return inflight;

  const concurrency = options.concurrency ?? 2;
  const progress: SemanticIndexProgress = {
    total: tracks.length,
    done: 0,
    skipped: 0,
    failed: 0,
  };

  inflight = (async () => {
    try {
      const pending: TrackInfo[] = [];
      for (const track of tracks) {
        if (options.onlyMissing !== false && !(await needsSemanticIndex(track))) {
          progress.skipped++;
          progress.done++;
          options.onProgress?.({ ...progress });
          continue;
        }
        pending.push(track);
      }

      let cursor = 0;
      await Promise.all(
        Array.from({ length: concurrency }, async () => {
          while (cursor < pending.length) {
            const track = pending[cursor++];
            try {
              await indexTrackSemantics(track);
            } catch (e) {
              console.debug("[claudio] semantic index 单首失败", track.id, e);
              progress.failed++;
            }
            progress.done++;
            options.onProgress?.({ ...progress });
          }
        }),
      );
      return progress;
    } finally {
      inflight = null;
    }
  })();

  return inflight;
}

async function loadLyricsSample(trackId: number): Promise<string> {
  const cached = await cache.getLyric(trackId).catch(() => null);
  const lyricData = cached ?? (await netease.songLyric(trackId).catch(() => null));
  if (!lyricData?.lyric) return "";
  if (!cached) cache.saveLyric(trackId, lyricData).catch(() => {});
  return lyricData.lyric
    .replace(/\[[^\]]+\]/g, "")
    .split(/\n+/)
    .map((line) => line.trim())
    .filter(Boolean)
    .slice(0, 20)
    .join(" / ")
    .slice(0, 900);
}

async function callSemanticLlm(
  track: TrackInfo,
  analysis: TrackAnalysis | null,
  lyricsSample: string,
): Promise<z.infer<typeof LlmSemanticSchema>> {
  const artist = track.artists.map((a) => a.name).join(" / ") || "未知";
  const user =
    `请为这首歌生成单曲语义档案，只描述歌曲本身，不要写用户口味。\n` +
    `输入：\n` +
    JSON.stringify(
      {
        title: track.name,
        artists: track.artists.map((a) => a.name),
        album: track.album?.name ?? null,
        lyricsSample,
        audioHints: analysis
          ? {
              bpm: analysis.bpm,
              energy: (analysis.introEnergy + analysis.outroEnergy) / 2,
              introVocalDensity: analysis.introVocalDensity,
              outroVocalDensity: analysis.outroVocalDensity,
            }
          : null,
      },
      null,
      0,
    ) +
    `\n要求：\n` +
    `1) 不确定就 unknown 并降低 confidence。\n` +
    `2) genre 最多 3 个，subGenre 最多 4 个，标签用短英文小写，如 r&b / soul / pop / hip-hop。\n` +
    `3) moods/scenes/textures 用短标签，如 chill/night/coding/smooth/warm。\n` +
    `4) negativeTags 表示不适合的场景或气质，如 noisy/aggressive/party/cheesy。\n` +
    `5) embeddingText 要混合中文和英文标签，适合自然语言检索。\n` +
    `严格只输出 JSON。`;

  const raw = await ai.chat({
    system:
      `你是 Claudio 的单曲语义标注器。只输出 JSON，不要解释。当前歌曲：${track.name} - ${artist}`,
    user,
    temperature: 0.2,
    maxTokens: 900,
  });
  const json = extractJsonObject(raw);
  const parsed = LlmSemanticSchema.safeParse(json);
  if (!parsed.success) throw new Error("semantic profile JSON invalid");
  return parsed.data;
}

function extractJsonObject(raw: string): unknown {
  return parseJsonObjectLike(raw);
}

function clean(values: string[]): string[] {
  return [...new Set(values.map((v) => v.toLowerCase().trim()).filter(Boolean))];
}

function coerceLanguage(value: string, fallback: TrackSemanticProfile["language"]["primary"]) {
  const allowed = ["english", "mandarin", "cantonese", "japanese", "korean", "instrumental", "mixed", "unknown"] as const;
  return allowed.includes(value as (typeof allowed)[number]) ? value as (typeof allowed)[number] : fallback;
}

function coerceRegion(value: string, fallback: TrackSemanticProfile["region"]["primary"]) {
  const allowed = ["western", "chinese", "japanese_korean", "other", "unknown"] as const;
  return allowed.includes(value as (typeof allowed)[number]) ? value as (typeof allowed)[number] : fallback;
}

function coerceVocalType(value: string, fallback: TrackSemanticProfile["vocal"]["type"]) {
  const allowed = ["male", "female", "duet", "group", "instrumental", "unknown"] as const;
  return allowed.includes(value as (typeof allowed)[number]) ? value as (typeof allowed)[number] : fallback;
}
