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
import { normalizeArtistKeyForLog } from "./recommendation-log";
import type { TrackInfo } from "./tauri";
import {
  dedupeSimilarTracks,
  queryAsksForSpecificVersion,
  queryExplicitlyMentionsTrack,
} from "./track-dedupe";
import { passesHardConstraints } from "./tag-recall";

export type RankedCandidate = Candidate & {
  finalScore: number;
  /**
   * 分位 bucket(0..3,0 最高分位)。
   * 续杯端按 bucket 跨段抽样,保证"主调 + 冷门"始终混合,而不是把最热的都抽完
   * 再去碰边缘候选。
   */
  bucket: 0 | 1 | 2 | 3;
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
    artistFatiguePenalty: number;
  };
};

export type RankerOptions = {
  /** 取 Top N 喂给 LLM。默认 100 */
  topN?: number;
  recentPlay?: RecentPlayContext;
  recentRecommendation?: RecentRecommendationContext;
};

const DEFAULT_TOP_N = 100;

// 权重思路（v2,2026-05 重构):
//   - INTENT(用户当前句子直接点的)=0.30 不变,点名了就该听到
//   - TASTE(画像派生)从 0.25 提到 0.40,**这一档现在不止 artist** ——
//     内部走 max(profile_tags, acoustic, profile/artist*0.5, audio*0.7)。
//     profile_tags(风格/情绪/年代/文化) 和 acoustic(BPM/响度/音色)替代
//     原本"全靠 topArtists"的 taste 路径,所以 Taylor 粉也能听到 Phoebe
//     Bridgers / Lorde / Mitski 这类口味邻居。
//   - TRANSITION / FRESHNESS / BEHAVIOR 保持小权重
const W_INTENT = 0.30;
const W_TASTE = 0.40;
const W_BEHAVIOR = 0.10;
const W_TRANSITION = 0.13;
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
    if (
      options.recentRecommendation?.last24hTrackIds.has(c.track.id) &&
      !explicitlyMentioned
    ) {
      continue;
    }

    const intentScore = clamp01(c.sourceScores.text ?? 0);
    const tagScore = clamp01((c.sourceScores.tag ?? 0) / 1.6);
    const semanticScore = clamp01(
      Math.max(c.sourceScores.semantic ?? 0, (c.sourceScores.semantic_broad ?? 0) * 0.65),
    );
    // taste 内部:profile_tags(风格维度)和 acoustic(声学维度)是主信号,
    // profile/artist 降到 0.5×,audio 0.7×。这就是"贴画像不贴艺人"的落点。
    const tasteScore = clamp01(
      Math.max(
        (c.sourceScores.profile_tags ?? 0) / 1.2,
        (c.sourceScores.acoustic ?? 0) * 0.9,
        (c.sourceScores.profile ?? 0) * 0.5,
        (c.sourceScores.audio ?? 0) * 0.7,
      ),
    );
    const behaviorScore = clamp01((c.sourceScores.behavior ?? 0) / 1.5);
    const transitionScore = clamp01(c.sourceScores.transition ?? 0);
    // freshness：explore 路命中 = 高新鲜度；text/behavior 命中 = 低新鲜度
    const freshnessScore = clamp01(
      (c.sourceScores.explore ?? 0) -
        (c.sourceScores.behavior ?? 0) * 0.3,
    );

    const weights = rankWeights(intent);
    // 随机扰动 0.025 → 0.08:同 query 重复触发时,让"几乎同分"的歌互相错位。
    // 配合下方的分位 bucket 洗牌,共同破"顺序固定"的体验。
    const baseScore =
      intentScore * W_INTENT +
      tagScore * weights.tag +
      semanticScore * weights.semantic +
      tasteScore * weights.taste +
      behaviorScore * weights.behavior +
      transitionScore * W_TRANSITION +
      freshnessScore * W_FRESHNESS +
      Math.random() * 0.08;

    const recentPlayPenalty = explicitlyMentioned
      ? 0
      : recentPenalty(c.track.id, options.recentPlay, 0.5, 0.25);
    const recentRecommendationPenalty = explicitlyMentioned
      ? 0
      : recentPenalty(c.track.id, options.recentRecommendation, 0.35, 0.15);
    // artist-level fatigue:近 24h / 7d 这个艺人被推过几次,推太多就降权。
    // 这是修"自由推荐总是同一个歌手"的核心 —— 即使艺人在 topArtists 里得高分,
    // 连续推 5-10 次后会被强制压下去,让池子换换人。
    // explicitlyMentioned 跳过(用户主动点的就别 fatigue 自己点的人)。
    const artistFatiguePenalty = explicitlyMentioned
      ? 0
      : computeArtistFatigue(c.track, options.recentRecommendation);
    const finalScore =
      baseScore -
      recentPlayPenalty -
      recentRecommendationPenalty -
      artistFatiguePenalty;

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
      bucket: 0, // 占位,排序后按分位重写
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
        artistFatiguePenalty,
      },
    });
  }

  ranked.sort((a, b) => b.finalScore - a.finalScore);
  const deduped = queryAsksForSpecificVersion(intent)
    ? ranked
    : dedupeSimilarTracks(ranked);

  // 分位 bucket 洗牌:把 ranked 切成 4 段(top25 / 25-50 / 50-75 / 75-100),
  // 每段内 Fisher-Yates 洗牌,段间保留优先级。这样同 query 重复触发时,顺序
  // 在每段内都会变,但"最贴口味"的歌仍然排在前面。
  // queryAsksForSpecificVersion 时不洗 —— 用户点名了具体版本,排序应稳定。
  if (!queryAsksForSpecificVersion(intent) && deduped.length > 8) {
    shuffleByBuckets(deduped);
  } else {
    // 至少给每首歌打上 bucket 标签,续杯端要用
    for (let i = 0; i < deduped.length; i++) {
      deduped[i].bucket = pickBucket(i, deduped.length);
    }
  }

  return deduped.slice(0, options.topN ?? DEFAULT_TOP_N);
}

function pickBucket(rank: number, total: number): 0 | 1 | 2 | 3 {
  const ratio = rank / Math.max(1, total);
  if (ratio < 0.25) return 0;
  if (ratio < 0.5) return 1;
  if (ratio < 0.75) return 2;
  return 3;
}

function shuffleByBuckets(items: RankedCandidate[]): void {
  // 先标记每首歌的 bucket(按原排序)
  for (let i = 0; i < items.length; i++) {
    items[i].bucket = pickBucket(i, items.length);
  }
  // 按 bucket 切片,段内洗牌,再拼回去(原地修改)
  const groups: RankedCandidate[][] = [[], [], [], []];
  for (const it of items) {
    groups[it.bucket].push(it);
  }
  for (const g of groups) fisherYates(g);
  let idx = 0;
  for (const g of groups) {
    for (const it of g) items[idx++] = it;
  }
}

function fisherYates<T>(arr: T[]): void {
  for (let i = arr.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [arr[i], arr[j]] = [arr[j], arr[i]];
  }
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

/**
 * artist-level fatigue:近期某艺人被推过太多次时降权。
 *
 * 阈值 v2(2026-05 放宽):
 *   - 24h ≥ 8 次  → -0.15 (轻扣,提示该换换人但不强制)
 *   - 24h ≥ 15 次 → 再 -0.20,累计 -0.35
 *   - 7d  ≥ 30 次 → 再 -0.15,累计 -0.50
 *
 * 之前 24h ≥5 -0.30 太狠 —— 用户喜欢 Taylor 的话,听 5 首就把后续 Taylor 压
 * 下去会导致"浮上来的歌偏冷门"。放宽到 ≥8 起步、扣得也更轻,让"用户爱听
 * 的艺人继续推"空间更大,fatigue 仍能挡住"连推 20 首同人"的极端情况。
 */
function computeArtistFatigue(
  track: TrackInfo,
  ctx: RecentRecommendationContext | undefined,
): number {
  if (!ctx) return 0;
  const key = normalizeArtistKeyForLog(track.artists[0]?.name ?? "");
  if (!key) return 0;
  const c24 = ctx.last24hArtistCounts.get(key) ?? 0;
  const c7 = ctx.last7dArtistCounts.get(key) ?? 0;
  let penalty = 0;
  if (c24 >= 8) penalty += 0.15;
  if (c24 >= 15) penalty += 0.20;
  if (c7 >= 30) penalty += 0.15;
  return penalty;
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
    // 用户明确点了硬标签:tag 路主导,taste 给一点(让画像维度也参与)
    return { tag: 0.34, semantic: 0.22, taste: 0.18, behavior: 0.06 };
  }
  if (vibeCount > 0) {
    // 用户给了氛围词:semantic + taste 平分(taste 现在内部就是画像 tags + acoustic)
    return { tag: 0.16, semantic: 0.30, taste: 0.30, behavior: 0.08 };
  }
  // 自由推荐:taste 当家(它内部是 profile_tags/acoustic/artist/audio)
  return { tag: 0.08, semantic: 0.14, taste: W_TASTE, behavior: W_BEHAVIOR };
}
