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
 *   - 同专辑：是无缝接候选，减 0.2
 *   - A 尾干净 + B 头干净：减 0.25
 *   - fit score：把"能不能像一句乐句接上另一句乐句"作为正向奖励
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
   * 同艺人连排的惩罚力度。接歌匹配默认不惩罚同艺人：
   * 如果两首歌能接上，就让它们接上。
   */
  sameArtistPenalty?: number;
};

export type TransitionFit = {
  /** 0..1，越高越适合接在一起。 */
  score: number;
  /** 简短原因，调试/未来 UI 展示用。 */
  reason: string;
  /** 这对歌最适合的本地接法。 */
  style: "hard_cut" | "tight" | "soft" | "silence_breath";
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

  const fit = transitionFitScore(a, b);
  risk -= fit.score * 0.85;

  const sameArtistPenalty = ctx?.sameArtistPenalty ?? 0;
  if (sameArtistPenalty > 0 && sameArtist(a.track, b.track)) {
    risk += sameArtistPenalty;
  }
  if (sameAlbum(a.track, b.track)) risk -= 0.2;

  return Math.max(-1.2, risk);
}

export function transitionFitScore(a: ScoredTrack, b: ScoredTrack): TransitionFit {
  const aa = a.analysis;
  const bb = b.analysis;
  if (!aa || !bb) {
    return { score: 0.25, style: "soft", reason: "缺少音频分析" };
  }

  const bpmDelta =
    aa.bpm !== null && bb.bpm !== null
      ? Math.abs(aa.bpm - bb.bpm)
      : Number.POSITIVE_INFINITY;
  const bpmReliable =
    aa.bpm !== null &&
    bb.bpm !== null &&
    aa.bpmConfidence > 0.25 &&
    bb.bpmConfidence > 0.25;
  const energyDelta = Math.abs(aa.outroEnergy - bb.introEnergy);
  const vocalClash = aa.outroVocalDensity * bb.introVocalDensity;
  const lowClash = aa.outroLowEnergy * bb.introLowEnergy;
  const cleanOutro =
    aa.outroStartS !== null ||
    (aa.outroVocalDensity < 0.22 && aa.outroLowEnergy < 0.052);
  const playableIntro =
    bb.vocalEntryS === null ||
    bb.vocalEntryS > 1.2 ||
    bb.introVocalDensity < 0.22;
  const introBed =
    (bb.drumEntryS !== null && bb.drumEntryS > 1.4) ||
    (bb.vocalEntryS !== null && bb.vocalEntryS > 1.8) ||
    bb.introVocalDensity < 0.18;

  if (
    sameAlbum(a.track, b.track) &&
    cleanOutro &&
    playableIntro &&
    energyDelta <= 0.24 &&
    vocalClash <= 0.08
  ) {
    return { score: 0.98, style: "hard_cut", reason: "同专辑边界干净" };
  }

  if (energyDelta >= 0.34 || vocalClash >= 0.2) {
    const penalty = clamp01((energyDelta - 0.25) * 1.4 + vocalClash * 1.8);
    return {
      score: clamp01(0.42 - penalty * 0.25),
      style: "silence_breath",
      reason: "反差大，适合留白",
    };
  }

  let score = 0.28;
  const reasons: string[] = [];

  if (bpmReliable) {
    if (bpmDelta <= 4) {
      score += 0.3;
      reasons.push("BPM 很近");
    } else if (bpmDelta <= 8) {
      score += 0.2;
      reasons.push("BPM 接近");
    } else if (bpmDelta <= 14) {
      score += 0.08;
    } else {
      score -= 0.18;
    }
  }

  if (energyDelta <= 0.1) {
    score += 0.2;
    reasons.push("能量贴合");
  } else if (energyDelta <= 0.2) {
    score += 0.12;
  } else if (energyDelta > 0.28) {
    score -= 0.15;
  }

  if (cleanOutro && playableIntro) {
    score += 0.18;
    reasons.push("尾头干净");
  } else if (cleanOutro || playableIntro) {
    score += 0.08;
  }

  if (introBed) {
    score += 0.1;
    reasons.push("前奏可铺底");
  }

  if (vocalClash <= 0.06) score += 0.12;
  else if (vocalClash > 0.14) score -= 0.2;

  if (lowClash > 0.004 && (!bpmReliable || bpmDelta > 8)) score -= 0.12;

  const style =
    bpmReliable && bpmDelta <= 8 && energyDelta <= 0.22 && vocalClash <= 0.12
      ? "tight"
      : "soft";

  return {
    score: clamp01(score),
    style,
    reason: reasons.length > 0 ? reasons.join(" / ") : "普通慢溶",
  };
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

function sameAlbum(a: TrackInfo, b: TrackInfo): boolean {
  if (a.album?.id && b.album?.id) return a.album.id === b.album.id;
  const an = a.album?.name?.trim().toLowerCase();
  const bn = b.album?.name?.trim().toLowerCase();
  return Boolean(an && bn && an === bn);
}

function clamp01(x: number): number {
  return x < 0 ? 0 : x > 1 ? 1 : x;
}

function fallbackMeanEnergy(analysis: TrackAnalysis | null): number | null {
  if (!analysis) return null;
  return mean(analysis.energyPerSec);
}

function mean(values: number[]): number {
  if (values.length === 0) return 0;
  return values.reduce((sum, value) => sum + value, 0) / values.length;
}
