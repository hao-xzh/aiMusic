"use client";

import type { HardConstraints, MusicIntent } from "./music-intent";
import type { TrackInfo } from "./tauri";
import type { TrackSemanticProfile } from "./track-semantic-profile";

export type CandidateHit = { track: TrackInfo; score: number };

export function recallByTags(
  intent: MusicIntent,
  library: TrackInfo[],
  profiles: Map<number, TrackSemanticProfile>,
): CandidateHit[] {
  const out: CandidateHit[] = [];

  for (const track of library) {
    const profile = profiles.get(track.id);
    if (!profile) continue;
    if (!passesHardConstraints(profile, intent.hardConstraints)) continue;

    let score = 0;
    score += matchArray(intent.hardConstraints.languages, [profile.language.primary]) * 0.8;
    score += matchArray(intent.hardConstraints.regions, [profile.region.primary]) * 0.7;
    score += matchArray(intent.hardConstraints.genres, profile.style.genres) * 0.7;
    score += matchArray(intent.hardConstraints.subGenres, profile.style.subGenres) * 0.55;
    score += matchArray(intent.hardConstraints.vocalTypes, [profile.vocal.type]) * 0.5;
    score += matchArray(intent.softPreferences.moods, profile.vibe.moods) * 0.28;
    score += matchArray(intent.softPreferences.scenes, profile.vibe.scenes) * 0.28;
    score += matchArray(intent.softPreferences.textures, profile.vibe.textures) * 0.2;
    score += energyFit(intent.softPreferences.energy, profile.vibe.energyWords) * 0.28;
    score += tempoFit(intent.softPreferences.tempoFeel, profile.vibe.tempoFeel) * 0.18;

    const hasExplicitTagRequest =
      intent.hardConstraints.languages.length > 0 ||
      intent.hardConstraints.regions.length > 0 ||
      intent.hardConstraints.genres.length > 0 ||
      intent.hardConstraints.subGenres.length > 0 ||
      intent.hardConstraints.vocalTypes.length > 0 ||
      intent.softPreferences.moods.length > 0 ||
      intent.softPreferences.scenes.length > 0 ||
      intent.softPreferences.textures.length > 0 ||
      intent.softPreferences.energy !== "any" ||
      intent.softPreferences.tempoFeel !== "any";

    if (hasExplicitTagRequest && score > 0) {
      out.push({ track, score: Math.min(1.8, score) });
    }
  }

  out.sort((a, b) => b.score - a.score);
  return out.slice(0, 220);
}

export function passesHardConstraints(
  profile: TrackSemanticProfile,
  c: HardConstraints,
): boolean {
  if (c.languages.length && !includesAny(c.languages, [profile.language.primary])) return false;
  if (c.regions.length && !includesAny(c.regions, [profile.region.primary])) return false;
  if (c.genres.length && !includesAny(c.genres, profile.style.genres)) return false;
  if (c.subGenres.length && !includesAny(c.subGenres, profile.style.subGenres)) return false;
  if (c.vocalTypes.length && !includesAny(c.vocalTypes, [profile.vocal.type])) return false;

  if (includesAny(c.excludeLanguages, [profile.language.primary])) return false;
  if (includesAny(c.excludeRegions, [profile.region.primary])) return false;
  if (includesAny(c.excludeGenres, [...profile.style.genres, ...profile.style.subGenres])) return false;
  if (includesAny(c.excludeVocalTypes, [profile.vocal.type])) return false;
  if (includesAny(c.excludeTags, [
    ...profile.negativeTags,
    ...profile.vibe.moods,
    ...profile.vibe.scenes,
    ...profile.vibe.textures,
    ...profile.style.genres,
    ...profile.style.subGenres,
  ])) {
    return false;
  }

  return true;
}

function matchArray(needles: string[], haystack: string[]): number {
  if (needles.length === 0) return 0;
  let hit = 0;
  for (const n of needles) {
    if (includesAny([n], haystack)) hit++;
  }
  return hit / needles.length;
}

function includesAny(needles: string[], haystack: string[]): boolean {
  const hs = haystack.map(normalize);
  return needles.map(normalize).some((n) => hs.some((h) => h === n || h.includes(n) || n.includes(h)));
}

function energyFit(intentEnergy: MusicIntent["softPreferences"]["energy"], words: string[]): number {
  if (intentEnergy === "any") return 0;
  const normalized = words.map(normalize);
  if (intentEnergy === "mid_low") return normalized.includes("mid-low") || normalized.includes("low") ? 1 : 0;
  if (intentEnergy === "mid_high") return normalized.includes("mid") || normalized.includes("high") ? 1 : 0;
  return normalized.includes(normalize(intentEnergy)) ? 1 : 0;
}

function tempoFit(intentTempo: MusicIntent["softPreferences"]["tempoFeel"], words: string[]): number {
  if (intentTempo === "any") return 0;
  return words.map(normalize).includes(normalize(intentTempo)) ? 1 : 0;
}

function normalize(value: string): string {
  return value.toLowerCase().replace(/\s+/g, "").replace(/节奏布鲁斯|rnb/g, "r&b");
}
