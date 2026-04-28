"use client";

import type { MusicIntent } from "./music-intent";
import type { TrackInfo } from "./tauri";
import { passesHardConstraints, type CandidateHit } from "./tag-recall";
import { semanticTerms, type TrackSemanticProfile } from "./track-semantic-profile";

export function recallBySemantics(
  intent: MusicIntent,
  query: string,
  library: TrackInfo[],
  profiles: Map<number, TrackSemanticProfile>,
): CandidateHit[] {
  const queryTerms = buildQueryTerms(intent, query);
  if (queryTerms.length === 0) return [];

  const out: CandidateHit[] = [];
  for (const track of library) {
    const profile = profiles.get(track.id);
    if (!profile) continue;
    if (!passesHardConstraints(profile, intent.hardConstraints)) continue;
    const haystack = semanticTerms(profile).join(" ").toLowerCase();
    let score = 0;
    for (const term of queryTerms) {
      if (haystack.includes(term)) score += 1;
    }
    score = score / Math.max(1, queryTerms.length);
    if (profile.sources.llm) score += 0.08;
    if (score > 0.08) out.push({ track, score: Math.min(1.2, score) });
  }

  out.sort((a, b) => b.score - a.score);
  return out.slice(0, 180);
}

export function recallBroadSemanticCandidates(
  intent: MusicIntent,
  library: TrackInfo[],
  profiles: Map<number, TrackSemanticProfile>,
): CandidateHit[] {
  const needsOpenSurface =
    intent.softPreferences.qualityWords.length > 0 ||
    intent.references.styles.length > 0 ||
    intent.references.artists.length > 0 ||
    intent.softPreferences.moods.length > 0 ||
    intent.softPreferences.scenes.length > 0 ||
    intent.hardConstraints.genres.length > 0 ||
    intent.hardConstraints.regions.length > 0 ||
    intent.hardConstraints.languages.length > 0;
  if (!needsOpenSurface) return [];

  const out: CandidateHit[] = [];
  for (const track of library) {
    const profile = profiles.get(track.id);
    if (!profile) continue;
    if (!passesHardConstraints(profile, intent.hardConstraints)) continue;
    const completeness =
      (profile.sources.llm ? 0.35 : 0) +
      Math.min(0.25, profile.confidence * 0.25) +
      Math.min(0.2, profile.style.genres.length * 0.08) +
      Math.min(0.2, (profile.vibe.moods.length + profile.vibe.scenes.length) * 0.04);
    out.push({ track, score: 0.18 + completeness + Math.random() * 0.08 });
  }

  out.sort((a, b) => b.score - a.score);
  return out.slice(0, 90);
}

function buildQueryTerms(intent: MusicIntent, query: string): string[] {
  const raw = [
    query,
    ...intent.hardConstraints.genres,
    ...intent.hardConstraints.subGenres,
    ...intent.hardConstraints.regions,
    ...intent.hardConstraints.languages,
    ...intent.hardConstraints.vocalTypes,
    ...intent.softPreferences.moods,
    ...intent.softPreferences.scenes,
    ...intent.softPreferences.textures,
    ...intent.softPreferences.qualityWords,
    intent.softPreferences.energy,
    intent.softPreferences.tempoFeel,
    intent.emotionalGoal.direction ?? "",
    ...intent.references.styles,
  ].join(" ").toLowerCase();

  const mapped = raw
    .replace(/欧美/g, "western english")
    .replace(/英文|英语/g, "english")
    .replace(/国语|中文|华语/g, "mandarin chinese")
    .replace(/粤语/g, "cantonese")
    .replace(/日语|日本/g, "japanese")
    .replace(/韩语|韩国/g, "korean")
    .replace(/rnb|节奏布鲁斯/g, "r&b")
    .replace(/写代码|编程/g, "coding focus")
    .replace(/晚上|夜里|深夜|夜晚/g, "night late-night")
    .replace(/下雨|雨天/g, "rainy melancholic atmospheric")
    .replace(/松弛/g, "chill smooth")
    .replace(/高级/g, "sophisticated alternative neo soul")
    .replace(/不吵|不要太吵|别太吵/g, "calm soft minimal");

  return [...new Set(
    mapped
      .split(/[^a-z0-9&\u4e00-\u9fff-]+/i)
      .map((s) => s.trim())
      .filter((s) => s.length >= 2 && !STOPWORDS.has(s)),
  )].slice(0, 28);
}

const STOPWORDS = new Set([
  "来点",
  "听听",
  "想听",
  "不要",
  "一点",
  "some",
  "music",
  "song",
  "any",
]);
