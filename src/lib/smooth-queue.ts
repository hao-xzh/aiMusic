"use client";

/**
 * 平滑歌单排序 —— 任意歌单都能用，不再只服务 AI 宠物。
 *
 * 算法：贪心局部排序。
 *   1) 取定一首"起点"（用户点的那首；没指定就用第一首）—— 不变。
 *   2) 每一步从剩余候选里挑接续风险 + 顺序漂移惩罚最低的那首接上。
 *   3) 依次填完。
 *
 * 设计取舍：
 *   - 不重排起点：用户主动点哪首就从哪首开始，不能违背用户意图。
 *   - 顺序漂移惩罚：避免把第 50 首歌拽到第 2 首位置 —— 用户原顺序
 *     也是一种意图，平滑不能凌驾于此。
 *   - 分析数据 < 3 首时直接原样返回：盲排比不排还差。
 *
 * 调用入口（任何"播一组歌"的地方都应该过一遍）：
 *   - playNetease(t, contextQueue)：默认开启平滑（可关）
 *   - pet-agent 的 AI 推荐：已经有专门的 smoothResolvedTracks，
 *     现在直接复用本模块。
 */

import { loadAnalysis } from "./audio-analysis";
import type { TrackInfo } from "./tauri";
import {
  transitionRisk,
  orderDriftPenalty,
  type ScoredTrack,
  type ScoreContext,
} from "./transition-score";

type EnrichedTrack = ScoredTrack & {
  originalIndex: number;
};

/**
 * 重排"意图"。决定 transition-score 里同艺人惩罚的强度。
 *
 *   - "discovery"：AI 推荐歌单 / 库外发现，同艺人惩罚 1.4，
 *      像随机电台一样追求多样。
 *   - "library"（默认）：用户自己的歌单，0.3 轻微多样化，尊重原构成。
 *      用户精选周杰伦不会被打散。
 *
 * 专辑播放：直接 `smooth: false` 跳过本模块即可，不需要单独 mode。
 */
export type SmoothMode = "discovery" | "library";

export type SmoothQueueOptions = {
  /** 把哪一首作为起点（默认第一首）。这一首不会被移动。 */
  startTrackId?: number;
  /**
   * 不论分析够不够都强制重排。默认 false —— 分析覆盖太低时
   * 直接退回原顺序，避免盲排。
   */
  force?: boolean;
  /** 重排意图。默认 "library"。 */
  mode?: SmoothMode;
};

function scoreContextFor(mode: SmoothMode | undefined): ScoreContext {
  switch (mode) {
    case "discovery":
      return { sameArtistPenalty: 1.4 };
    case "library":
    default:
      return { sameArtistPenalty: 0.3 };
  }
}

/**
 * 主入口。返回新顺序的 TrackInfo[]；输入 ≤2 首或分析覆盖不足时
 * 直接返回原数组（同一引用）。
 */
export async function smoothQueue(
  tracks: TrackInfo[],
  options: SmoothQueueOptions = {},
): Promise<TrackInfo[]> {
  if (tracks.length <= 2) return tracks;

  const enriched = await Promise.all(
    tracks.map(async (track, originalIndex): Promise<EnrichedTrack> => ({
      track,
      analysis: await loadAnalysis(track.id),
      originalIndex,
    })),
  );

  // 分析覆盖不足时不强排：贪心+缺分析的 fallback 只会按"未知"乱排。
  const analyzedCount = enriched.filter((x) => x.analysis).length;
  if (!options.force && analyzedCount < 3) return tracks;

  // 起点：用户指定的；没指定就用第一首
  const startIndex = options.startTrackId
    ? Math.max(
        0,
        enriched.findIndex((x) => x.track.id === options.startTrackId),
      )
    : 0;

  const ordered: EnrichedTrack[] = [enriched[startIndex]];
  const remaining = enriched.filter((_, i) => i !== startIndex);
  const scoreCtx = scoreContextFor(options.mode);

  while (remaining.length > 0) {
    const current = ordered[ordered.length - 1];
    let bestIndex = 0;
    let bestScore = Number.POSITIVE_INFINITY;

    for (let i = 0; i < remaining.length; i++) {
      const candidate = remaining[i];
      const score =
        transitionRisk(current, candidate, scoreCtx) +
        orderDriftPenalty(
          current.originalIndex,
          candidate.originalIndex,
          tracks.length,
        );
      if (score < bestScore) {
        bestScore = score;
        bestIndex = i;
      }
    }

    const [next] = remaining.splice(bestIndex, 1);
    ordered.push(next);
  }

  return ordered.map((x) => x.track);
}
