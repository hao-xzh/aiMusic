"use client";

import { cache, type TrackInfo } from "./tauri";
import type { TrackAnalysis } from "./audio-analysis";

export const TRACK_SEMANTIC_VERSION = 1 as const;
const KEY_PREFIX = "track_semantic:v1:";

export type TrackLanguage =
  | "english"
  | "mandarin"
  | "cantonese"
  | "japanese"
  | "korean"
  | "instrumental"
  | "mixed"
  | "unknown";

export type TrackRegion =
  | "western"
  | "chinese"
  | "japanese_korean"
  | "other"
  | "unknown";

export type VocalType = "male" | "female" | "duet" | "group" | "instrumental" | "unknown";

export type TrackSemanticProfile = {
  trackId: number;
  version: typeof TRACK_SEMANTIC_VERSION;
  identity: {
    title: string;
    artists: string[];
    album?: string;
    year?: number;
  };
  language: {
    primary: TrackLanguage;
    secondary?: string[];
    confidence: number;
  };
  region: {
    primary: TrackRegion;
    confidence: number;
  };
  style: {
    genres: string[];
    subGenres: string[];
    styleAnchors: string[];
  };
  vibe: {
    moods: string[];
    scenes: string[];
    textures: string[];
    energyWords: string[];
    tempoFeel: string[];
  };
  vocal: {
    type: VocalType;
    delivery: string[];
  };
  era: {
    decade?: "1970s" | "1980s" | "1990s" | "2000s" | "2010s" | "2020s" | "unknown";
    year?: number;
  };
  audioHints: {
    bpm?: number;
    energy?: number;
    danceability?: number;
    acousticness?: number;
    brightness?: number;
  };
  negativeTags: string[];
  summary: string;
  embeddingText: string;
  confidence: number;
  sources: {
    metadata: boolean;
    lyrics: boolean;
    audio: boolean;
    llm: boolean;
    userFeedback: boolean;
  };
  updatedAt: number;
};

export async function getTrackSemanticProfile(
  trackId: number,
): Promise<TrackSemanticProfile | null> {
  try {
    const raw = await cache.getState(KEY_PREFIX + trackId);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as TrackSemanticProfile;
    if (parsed.version !== TRACK_SEMANTIC_VERSION) return null;
    return parsed;
  } catch {
    return null;
  }
}

export async function saveTrackSemanticProfile(
  profile: TrackSemanticProfile,
): Promise<void> {
  await cache.setState(KEY_PREFIX + profile.trackId, JSON.stringify(profile));
}

export async function getSemanticProfiles(
  tracks: TrackInfo[],
  options: { includeRuleBasedFallback?: boolean; analyses?: Map<number, TrackAnalysis> } = {},
): Promise<Map<number, TrackSemanticProfile>> {
  const out = new Map<number, TrackSemanticProfile>();
  await Promise.all(
    tracks.map(async (track) => {
      const cached = await getTrackSemanticProfile(track.id);
      if (cached) {
        out.set(track.id, cached);
        return;
      }
      if (options.includeRuleBasedFallback !== false) {
        out.set(track.id, buildRuleBasedSemanticProfile(track, options.analyses?.get(track.id) ?? null));
      }
    }),
  );
  return out;
}

export async function needsSemanticIndex(track: TrackInfo): Promise<boolean> {
  const cached = await getTrackSemanticProfile(track.id);
  return !cached || cached.version !== TRACK_SEMANTIC_VERSION || !cached.sources.llm;
}

export function buildRuleBasedSemanticProfile(
  track: TrackInfo,
  analysis: TrackAnalysis | null = null,
  lyricsSample = "",
): TrackSemanticProfile {
  const title = track.name;
  const artists = track.artists.map((a) => a.name).filter(Boolean);
  const album = track.album?.name ?? undefined;
  const metaText = `${title} ${artists.join(" ")} ${album ?? ""}`;
  const language = detectLanguage(metaText, lyricsSample);
  const region = inferRegion(language, metaText);
  const genres = inferGenres(metaText);
  const subGenres = inferSubGenres(metaText, genres);
  const vocalType = inferVocalType(metaText, language.primary);
  const energy = analysis ? (analysis.introEnergy + analysis.outroEnergy) / 2 : undefined;
  const energyWords = inferEnergyWords(energy);
  const moods = inferMoods(metaText, genres, energy);
  const scenes = inferScenes(metaText, genres, energy);
  const textures = inferTextures(metaText, genres, energy);
  const decade = inferDecade(album ?? title);
  const styleAnchors = inferStyleAnchors(artists, genres, subGenres);
  const negativeTags = inferNegativeTags(genres, energy);
  const summary = summarizeTrack(language.primary, region.primary, genres, moods, scenes, vocalType);
  const embeddingText = [
    title,
    artists.join(" "),
    album ?? "",
    language.primary,
    region.primary,
    ...genres,
    ...subGenres,
    ...styleAnchors,
    ...moods,
    ...scenes,
    ...textures,
    ...energyWords,
    vocalType,
    summary,
    ...negativeTags.map((t) => `不适合${t}`),
  ].filter(Boolean).join(" ");

  return {
    trackId: track.id,
    version: TRACK_SEMANTIC_VERSION,
    identity: {
      title,
      artists,
      album,
      year: decadeToYear(decade),
    },
    language,
    region,
    style: { genres, subGenres, styleAnchors },
    vibe: { moods, scenes, textures, energyWords, tempoFeel: inferTempoFeel(analysis?.bpm ?? null) },
    vocal: { type: vocalType, delivery: inferDelivery(genres, energy) },
    era: { decade },
    audioHints: {
      bpm: analysis?.bpm ?? undefined,
      energy,
    },
    negativeTags,
    summary,
    embeddingText,
    confidence: 0.52,
    sources: {
      metadata: true,
      lyrics: Boolean(lyricsSample),
      audio: Boolean(analysis),
      llm: false,
      userFeedback: false,
    },
    updatedAt: Date.now(),
  };
}

export function semanticTerms(profile: TrackSemanticProfile): string[] {
  return [
    profile.language.primary,
    profile.region.primary,
    ...profile.style.genres,
    ...profile.style.subGenres,
    ...profile.style.styleAnchors,
    ...profile.vibe.moods,
    ...profile.vibe.scenes,
    ...profile.vibe.textures,
    ...profile.vibe.energyWords,
    ...profile.vibe.tempoFeel,
    profile.vocal.type,
    ...profile.vocal.delivery,
    ...profile.negativeTags,
    profile.summary,
    profile.embeddingText,
  ].filter(Boolean);
}

function detectLanguage(metaText: string, lyricsSample: string): TrackSemanticProfile["language"] {
  const text = `${lyricsSample} ${metaText}`;
  const chinese = count(text, /[\u4e00-\u9fff]/g);
  const kana = count(text, /[\u3040-\u30ff]/g);
  const hangul = count(text, /[\uac00-\ud7af]/g);
  const latin = count(text, /[a-zA-Z]/g);
  if (kana > 20) return { primary: "japanese", confidence: 0.85 };
  if (hangul > 20) return { primary: "korean", confidence: 0.85 };
  if (chinese > 12 && latin > chinese * 0.6) return { primary: "mixed", secondary: ["mandarin", "english"], confidence: 0.68 };
  if (chinese > latin * 0.45) return { primary: "mandarin", confidence: 0.72 };
  if (latin > chinese * 2 && latin > 8) return { primary: "english", confidence: 0.68 };
  return { primary: "unknown", confidence: 0.35 };
}

function inferRegion(language: TrackSemanticProfile["language"], metaText: string): TrackSemanticProfile["region"] {
  if (language.primary === "english") return { primary: "western", confidence: 0.68 };
  if (language.primary === "mandarin" || language.primary === "cantonese" || language.primary === "mixed") return { primary: "chinese", confidence: 0.7 };
  if (language.primary === "japanese" || language.primary === "korean") return { primary: "japanese_korean", confidence: 0.72 };
  return { primary: "unknown", confidence: 0.35 };
}

function inferGenres(metaText: string): string[] {
  const t = norm(metaText);
  const genres: string[] = [];
  if (/(r&b|rnb|节奏布鲁斯|neo soul|neosoul|soul)/i.test(t)) genres.push("r&b");
  if (/(soul|灵魂|neo soul|neosoul)/i.test(t)) genres.push("soul");
  if (/(hip.?hop|rap|说唱)/i.test(t)) genres.push("hip-hop");
  if (/(jazz|爵士|bossa|swing)/i.test(t)) genres.push("jazz");
  if (/(folk|民谣|acoustic|unplugged)/i.test(t)) genres.push("folk");
  if (/(edm|dance|club|remix|电子|techno|house|trance)/i.test(t)) genres.push("electronic");
  if (/(rock|摇滚|indie rock|britpop)/i.test(t)) genres.push("rock");
  if (genres.length === 0) genres.push("pop");
  return unique(genres).slice(0, 3);
}

function inferSubGenres(metaText: string, genres: string[]): string[] {
  const t = norm(metaText);
  const out: string[] = [];
  if (genres.includes("r&b")) {
    if (/(alternative|alt r&b|另类)/i.test(t)) out.push("alternative r&b");
    if (/(neo soul|neosoul)/i.test(t)) out.push("neo soul");
    out.push("late-night r&b");
  }
  if (/city pop|城市流行/i.test(t)) out.push("city pop");
  return unique(out).slice(0, 4);
}

function inferVocalType(metaText: string, language: TrackLanguage): VocalType {
  const t = norm(metaText);
  if (/instrumental|纯音乐|伴奏/.test(t) || language === "instrumental") return "instrumental";
  if (/feat\.|with|duet|合唱|\/|&/.test(t)) return "duet";
  return "unknown";
}

function inferEnergyWords(energy: number | undefined): string[] {
  if (energy === undefined) return [];
  if (energy < 0.22) return ["low"];
  if (energy < 0.42) return ["mid-low"];
  if (energy < 0.62) return ["mid"];
  return ["high"];
}

function inferMoods(metaText: string, genres: string[], energy: number | undefined): string[] {
  const t = norm(metaText);
  const out: string[] = [];
  if (genres.includes("r&b")) out.push("chill", "sensual", "smooth");
  if (/night|moon|雨|rain|深夜|lonely|blue/i.test(t)) out.push("melancholic", "night");
  if (energy !== undefined && energy < 0.35) out.push("calm");
  if (energy !== undefined && energy > 0.62) out.push("energetic");
  return unique(out).slice(0, 6);
}

function inferScenes(metaText: string, genres: string[], energy: number | undefined): string[] {
  const t = norm(metaText);
  const out: string[] = [];
  if (genres.includes("r&b")) out.push("night", "city walk");
  if (/drive|road|car|开车/.test(t)) out.push("driving");
  if (/rain|雨/.test(t)) out.push("rainy day");
  if (energy !== undefined && energy < 0.38) out.push("coding", "focus");
  return unique(out).slice(0, 6);
}

function inferTextures(metaText: string, genres: string[], energy: number | undefined): string[] {
  const out: string[] = [];
  if (genres.includes("r&b")) out.push("smooth", "warm");
  if (energy !== undefined && energy < 0.35) out.push("soft", "minimal");
  if (/acoustic|民谣|unplugged/i.test(metaText)) out.push("acoustic");
  return unique(out).slice(0, 6);
}

function inferTempoFeel(bpm: number | null): string[] {
  if (!bpm) return [];
  if (bpm < 82) return ["slow"];
  if (bpm < 116) return ["medium"];
  return ["fast"];
}

function inferDelivery(genres: string[], energy: number | undefined): string[] {
  const out: string[] = [];
  if (genres.includes("r&b")) out.push("soft", "breathy");
  if (genres.includes("hip-hop")) out.push("rap");
  if (energy !== undefined && energy < 0.35) out.push("gentle");
  return unique(out).slice(0, 4);
}

function inferNegativeTags(genres: string[], energy: number | undefined): string[] {
  const out: string[] = [];
  if (energy !== undefined && energy > 0.62) out.push("noisy", "aggressive");
  if (genres.includes("electronic")) out.push("party");
  if (genres.includes("hip-hop")) out.push("rap-heavy");
  return unique(out).slice(0, 6);
}

function inferStyleAnchors(artists: string[], genres: string[], subGenres: string[]): string[] {
  const first = artists[0];
  const out = [...subGenres];
  if (first && genres.includes("r&b")) out.push(`${first}-like`);
  return unique(out).slice(0, 4);
}

function inferDecade(text: string): TrackSemanticProfile["era"]["decade"] {
  const m = text.match(/\b(19[7-9]\d|20[0-2]\d)\b/);
  if (!m) return "unknown";
  const y = Number(m[1]);
  if (y < 1980) return "1970s";
  if (y < 1990) return "1980s";
  if (y < 2000) return "1990s";
  if (y < 2010) return "2000s";
  if (y < 2020) return "2010s";
  return "2020s";
}

function summarizeTrack(
  lang: TrackLanguage,
  region: TrackRegion,
  genres: string[],
  moods: string[],
  scenes: string[],
  vocalType: VocalType,
): string {
  return `${lang}/${region} ${genres.join(",")} ${moods.slice(0, 2).join(",")} ${scenes.slice(0, 2).join(",")} ${vocalType}`.trim();
}

function decadeToYear(decade: TrackSemanticProfile["era"]["decade"]): number | undefined {
  if (!decade || decade === "unknown") return undefined;
  return Number(decade.slice(0, 4));
}

function count(text: string, re: RegExp): number {
  return text.match(re)?.length ?? 0;
}

function norm(text: string): string {
  return text.toLowerCase();
}

function unique(arr: string[]): string[] {
  return [...new Set(arr.map((s) => s.trim()).filter(Boolean))];
}
