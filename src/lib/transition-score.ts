"use client";

/**
 * 两两曲目接续风险评分 —— smooth-queue 用它做局部 DJ 排序，
 * 数值越大 = 越不适合衔接。
 *
 * 评分维度（加权和）：
 *   - BPM 差距：8bpm 内线性，>15bpm 显著惩罚
 *   - intro/outro 能量差：>0.18 开始惩罚
 *   - 人声互盖：A 尾人声 + B 头人声同时高 → 重罚
 *   - 双侧低频堆叠且 BPM 不同：避免轰头
 *   - 同艺人连排：风格疲劳，加 1.4
 *   - 同专辑：是无缝接候选，减 0.2
 *   - A 尾干净 + B 头干净：减 0.25
 *
 * 缺分析时给中性默认值（0.28），不会强行把"未知"判成低风险。
 */

import type { TrackInfo } from "./tauri";
import type { TrackAnalysis } from "./audio-analysis";

export type ScoredTrack = {
  track: TrackInfo;
  analysis: TrackAnalysis | null;
};

export type ScoreContext = {
  /**
   * 同艺人连排的惩罚力度。不同播放场景需要不同强度：
   *   - 1.4：AI 推荐歌单，要"像电台一样丰富"，强避免同艺人扎堆
   *   - 0.3：用户自己的歌单，只做轻微多样化提示，尊重用户选择
   *   - 0：专辑/艺人精选/演唱会歌单，同艺人是常态
   * 默认 0.3（library 场景）。
   */
  sameArtistPenalty?: number;
};

export function transitionRisk(
  a: ScoredTrack,
  b: ScoredTrack,
  ctx?: ScoreContext,
): number {
  let risk = 0;

  const aBpm = a.analysis?.bpm;
  const bBpm = b.analysis?.bpm;
  if (aBpm && bBpm) {
    const diff = Math.abs(aBpm - bBpm);
    if (diff <= 8) risk += (diff / 8) * 0.35;
    else if (diff <= 15) risk += 0.35 + ((diff - 8) / 7) * 0.75;
    else risk += 1.3 + Math.min(2.2, (diff - 15) / 18);
  } else {
    risk += 0.28;
  }

  const aEnergy = a.analysis?.outroEnergy ?? fallbackMeanEnergy(a.analysis);
  const bEnergy = b.analysis?.introEnergy ?? fallbackMeanEnergy(b.analysis);
  if (aEnergy !== null && bEnergy !== null) {
    const diff = Math.abs(aEnergy - bEnergy);
    if (diff <= 0.18) risk += (diff / 0.18) * 0.35;
    else if (diff <= 0.25) risk += 0.35 + ((diff - 0.18) / 0.07) * 0.7;
    else risk += 1.1 + Math.min(1.8, diff * 3.2);
  } else {
    risk += 0.28;
  }

  if (
    a.analysis &&
    b.analysis &&
    a.analysis.outroVocalDensity > 0.35 &&
    b.analysis.introVocalDensity > 0.28
  ) {
    risk += 1.25;
  }

  if (
    a.analysis &&
    b.analysis &&
    a.analysis.outroLowEnergy > 0.05 &&
    b.analysis.introLowEnergy > 0.05 &&
    Math.abs((aBpm ?? 0) - (bBpm ?? 0)) > 8
  ) {
    risk += 0.75;
  }

  if (
    a.analysis &&
    a.analysis.outroStartS !== null &&
    b.analysis &&
    b.analysis.introVocalDensity < 0.2
  ) {
    risk -= 0.25;
  }

  const sameArtistPenalty = ctx?.sameArtistPenalty ?? 0.3;
  if (sameArtistPenalty > 0 && sameArtist(a.track, b.track)) {
    risk += sameArtistPenalty;
  }
  if (a.track.album?.id && a.track.album.id === b.track.album?.id) risk -= 0.2;

  return risk;
}

/**
 * 顺序漂移惩罚：候选离当前位置越远，惩罚越大。
 * 用来抑制 smooth-queue 把歌单大幅打乱 —— 用户原顺序也应该被尊重。
 */
export function orderDriftPenalty(
  fromIndex: number,
  toIndex: number,
  total: number,
): number {
  const drift = Math.abs(toIndex - fromIndex);
  return Math.min(0.45, (drift / Math.max(1, total)) * 0.5);
}

function sameArtist(a: TrackInfo, b: TrackInfo): boolean {
  const ids = new Set(a.artists.map((x) => x.id));
  return b.artists.some((x) => ids.has(x.id));
}

function fallbackMeanEnergy(analysis: TrackAnalysis | null): number | null {
  if (!analysis) return null;
  return mean(analysis.energyPerSec);
}

function mean(values: number[]): number {
  if (values.length === 0) return 0;
  return values.reduce((sum, value) => sum + value, 0) / values.length;
}
