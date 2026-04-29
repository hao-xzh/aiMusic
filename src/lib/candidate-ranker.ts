"use client";

/**
 * 候选打分排序 —— 把多路召回的 Candidate[] 排出 Top N，喂给 Playlist Planner LLM。
 *
 * 不让 LLM 自己排：
 *   1) LLM 看几百首歌的元数据成本高，且会随机改顺序
 *   2) 本地公式可解释、可调参，能跟着行为日志微调
 *
 * 公式：
 *   score = weighted relevance
 *         - recent play/recommendation penalties
 *
 * 各分量来自召回阶段已经算好的 sourceScores，避免重复计算。
 * avoid 命中直接清零（不是降权，是排除）。
 */

import type { Candidate } from "./candidate-recall";
import type { MusicIntent } from "./music-intent";
import type { RecentPlayContext } from "./behavior-log";
import type { RecentRecommendationContext } from "./recommendation-log";
import {
  dedupeSimilarTracks,
  queryAsksForSpecificVersion,
  queryExplicitlyMentionsTrack,
} from "./track-dedupe";
import { passesHardConstraints } from "./tag-recall";

export type RankedCandidate = Candidate & {
  finalScore: number;
  /** 各分量分解 —— 调试 / 调试日志用 */
  breakdown: {
    intent: number;
    tag: number;
    semantic: number;
    taste: number;
    behavior: number;
    transition: number;
    freshness: number;
    recentPlayPenalty: number;
    recentRecommendationPenalty: number;
  };
};

export type RankerOptions = {
  /** 取 Top N 喂给 LLM。默认 100 */
  topN?: number;
  recentPlay?: RecentPlayContext;
  recentRecommendation?: RecentRecommendationContext;
};

const DEFAULT_TOP_N = 100;

const W_INTENT = 0.35;
const W_TASTE = 0.25;
const W_BEHAVIOR = 0.18;
const W_TRANSITION = 0.15;
const W_FRESHNESS = 0.07;

export function rankCandidates(
  candidates: Candidate[],
  intent: MusicIntent,
  options: RankerOptions = {},
): RankedCandidate[] {
  const avoidKeys = intent.musicHints.avoid
    .map((s) => s.toLowerCase().trim())
    .filter(Boolean);

  const ranked: RankedCandidate[] = [];
  for (const c of candidates) {
    // avoid 短路：歌名 / 艺人 / 专辑里出现 avoid 关键字 → 直接剔除
    if (avoidKeys.length > 0 && hitsAvoid(c, avoidKeys)) continue;
    const explicitlyMentioned = queryExplicitlyMentionsTrack(c.track, intent);
    if (
      c.semanticProfile &&
      !explicitlyMentioned &&
      !passesHardConstraints(c.semanticProfile, intent.hardConstraints)
    ) {
      continue;
    }
    if (
      options.recentPlay?.last24hTrackIds.has(c.track.id) &&
      !explicitlyMentioned
    ) {
      continue;
    }

    const intentScore = clamp01(c.sourceScores.text ?? 0);
    const tagScore = clamp01((c.sourceScores.tag ?? 0) / 1.6);
    const semanticScore = clamp01(
      Math.max(c.sourceScores.semantic ?? 0, (c.sourceScores.semantic_broad ?? 0) * 0.65),
    );
    const tasteScore = clamp01(
      Math.max(c.sourceScores.profile ?? 0, (c.sourceScores.audio ?? 0) * 0.7),
    );
    const behaviorScore = clamp01((c.sourceScores.behavior ?? 0) / 1.5);
    const transitionScore = clamp01(c.sourceScores.transition ?? 0);
    // freshness：explore 路命中 = 高新鲜度；text/behavior 命中 = 低新鲜度
    const freshnessScore = clamp01(
      (c.sourceScores.explore ?? 0) -
        (c.sourceScores.behavior ?? 0) * 0.3,
    );

    const weights = rankWeights(intent);
    const baseScore =
      intentScore * W_INTENT +
      tagScore * weights.tag +
      semanticScore * weights.semantic +
      tasteScore * weights.taste +
      behaviorScore * weights.behavior +
      transitionScore * W_TRANSITION +
      freshnessScore * W_FRESHNESS +
      Math.random() * 0.025;

    const recentPlayPenalty = explicitlyMentioned
      ? 0
      : recentPenalty(c.track.id, options.recentPlay, 0.5, 0.25);
    const recentRecommendationPenalty = explicitlyMentioned
      ? 0
      : recentPenalty(c.track.id, options.recentRecommendation, 0.35, 0.15);
    const finalScore =
      baseScore -
      recentPlayPenalty -
      recentRecommendationPenalty;

    console.debug("[recommend-rank]", {
      track: c.track.name,
      baseScore,
      recentPlayPenalty,
      recentRecommendationPenalty,
      finalScore,
      sourceScores: c.sourceScores,
      semantic: c.semanticProfile
        ? {
            language: c.semanticProfile.language.primary,
            region: c.semanticProfile.region.primary,
            genres: c.semanticProfile.style.genres,
            moods: c.semanticProfile.vibe.moods,
            scenes: c.semanticProfile.vibe.scenes,
          }
        : null,
    });

    if (finalScore <= 0) continue;

    ranked.push({
      ...c,
      finalScore,
      breakdown: {
        intent: intentScore,
        tag: tagScore,
        semantic: semanticScore,
        taste: tasteScore,
        behavior: behaviorScore,
        transition: transitionScore,
        freshness: freshnessScore,
        recentPlayPenalty,
        recentRecommendationPenalty,
      },
    });
  }

  ranked.sort((a, b) => b.finalScore - a.finalScore);
  const deduped = queryAsksForSpecificVersion(intent)
    ? ranked
    : dedupeSimilarTracks(ranked);
  return deduped.slice(0, options.topN ?? DEFAULT_TOP_N);
}

function clamp01(x: number): number {
  return x < 0 ? 0 : x > 1 ? 1 : x;
}

function hitsAvoid(c: Candidate, avoidKeys: string[]): boolean {
  const haystack = [
    c.track.name.toLowerCase(),
    c.track.artists.map((a) => a.name.toLowerCase()).join(" "),
    c.track.album?.name.toLowerCase() ?? "",
  ].join(" ");
  return avoidKeys.some((k) => haystack.includes(k));
}

function recentPenalty(
  trackId: number,
  context: RecentPlayContext | RecentRecommendationContext | undefined,
  last24hPenalty: number,
  last7dPenalty: number,
): number {
  if (!context) return 0;
  if (context.last24hTrackIds.has(trackId)) return last24hPenalty;
  if (context.last7dTrackIds.has(trackId)) return last7dPenalty;
  return 0;
}

function rankWeights(intent: MusicIntent): {
  tag: number;
  semantic: number;
  taste: number;
  behavior: number;
} {
  const hardCount =
    intent.hardConstraints.languages.length +
    intent.hardConstraints.regions.length +
    intent.hardConstraints.genres.length +
    intent.hardConstraints.subGenres.length +
    intent.hardConstraints.vocalTypes.length +
    intent.hardConstraints.excludeLanguages.length +
    intent.hardConstraints.excludeGenres.length +
    intent.hardConstraints.excludeTags.length;
  const vibeCount =
    intent.softPreferences.moods.length +
    intent.softPreferences.scenes.length +
    intent.softPreferences.textures.length +
    (intent.softPreferences.energy === "any" ? 0 : 1);

  if (hardCount > 0) {
    return { tag: 0.34, semantic: 0.24, taste: 0.12, behavior: 0.06 };
  }
  if (vibeCount > 0) {
    return { tag: 0.18, semantic: 0.34, taste: 0.16, behavior: 0.08 };
  }
  return { tag: 0.08, semantic: 0.16, taste: W_TASTE, behavior: W_BEHAVIOR };
}
