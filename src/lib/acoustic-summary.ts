"use client";

/**
 * 把一组曲目的 AudioFeatures 压成一个**人话短摘要**，喂给 AI prompt。
 *
 * 为什么不直接给 AI 看 200 行 raw features：
 *   - token 浪费
 *   - 模型读完 200 行 RMS 数字根本记不住
 *   - 我们要的是"用户口味的物理特征"，是 mean / 分布 / 主导段，不是单首
 *
 * 输出例子：
 *   声学指纹（基于 187 首已分析曲目）：
 *   - BPM 中位 102，区间分布 慢(<90) 31% / 中(90-130) 45% / 快(>130) 24%
 *   - 平均响度 −14 dBFS，动态范围 8.2 dB（轻度压缩，主流流行/独立水平）
 *   - 音色：暗 32% / 中 41% / 亮 27%
 *   - 头尾静默均值 1.8s / 2.4s（普遍带 fade-in/out）
 */

import type { AudioFeatures } from "./tauri";

export type AcousticSummary = {
  /** 实际有 features 的曲目数 */
  analyzed: number;
  /** 喂给 AI 的人话块（多行，已格式化） */
  promptBlock: string;
  /** 数值指标 —— 程序里别处也能用 */
  metrics: AcousticMetrics;
};

export type AcousticMetrics = {
  bpmMedian: number | null;
  bpmMean: number | null;
  bpmDistribution: { slow: number; mid: number; fast: number }; // 0..1 比例
  rmsDbMean: number;
  dynamicRangeDbMean: number;
  centroidMean: number;
  centroidDistribution: { dark: number; neutral: number; bright: number };
  headSilenceMean: number;
  tailSilenceMean: number;
};

const EMPTY_METRICS: AcousticMetrics = {
  bpmMedian: null,
  bpmMean: null,
  bpmDistribution: { slow: 0, mid: 0, fast: 0 },
  rmsDbMean: 0,
  dynamicRangeDbMean: 0,
  centroidMean: 0,
  centroidDistribution: { dark: 0, neutral: 0, bright: 0 },
  headSilenceMean: 0,
  tailSilenceMean: 0,
};

/**
 * 压缩。features 里的 null 项（没分析过）会被忽略。
 */
export function summarizeAcoustics(
  features: (AudioFeatures | null)[],
): AcousticSummary {
  const valid = features.filter((f): f is AudioFeatures => f !== null);
  if (valid.length === 0) {
    return { analyzed: 0, promptBlock: "", metrics: EMPTY_METRICS };
  }

  // BPM 分布 —— 只统计算出来的（confidence > 0.3）
  const bpms = valid
    .filter((f) => f.bpm !== null && f.bpmConfidence > 0.3)
    .map((f) => f.bpm!) as number[];
  const bpmMedian = bpms.length > 0 ? median(bpms) : null;
  const bpmMean = bpms.length > 0 ? mean(bpms) : null;
  const slow = bpms.filter((b) => b < 90).length;
  const mid = bpms.filter((b) => b >= 90 && b <= 130).length;
  const fast = bpms.filter((b) => b > 130).length;
  const bpmDistribution = bpms.length
    ? { slow: slow / bpms.length, mid: mid / bpms.length, fast: fast / bpms.length }
    : { slow: 0, mid: 0, fast: 0 };

  const rmsDbMean = mean(valid.map((f) => f.rmsDb));
  const dynamicRangeDbMean = mean(valid.map((f) => f.dynamicRangeDb));

  const centroids = valid.map((f) => f.spectralCentroidHz);
  const centroidMean = mean(centroids);
  const dark = centroids.filter((c) => c < 1500).length;
  const bright = centroids.filter((c) => c > 3000).length;
  const neutral = centroids.length - dark - bright;
  const centroidDistribution = {
    dark: dark / centroids.length,
    neutral: neutral / centroids.length,
    bright: bright / centroids.length,
  };

  const headSilenceMean = mean(valid.map((f) => f.headSilenceS));
  const tailSilenceMean = mean(valid.map((f) => f.tailSilenceS));

  const metrics: AcousticMetrics = {
    bpmMedian,
    bpmMean,
    bpmDistribution,
    rmsDbMean,
    dynamicRangeDbMean,
    centroidMean,
    centroidDistribution,
    headSilenceMean,
    tailSilenceMean,
  };

  // 拼一段人话给 AI。简洁 + 数字优先
  const lines: string[] = [];
  lines.push(`声学指纹（基于 ${valid.length} 首已分析曲目）：`);
  if (bpmMedian !== null) {
    lines.push(
      `- BPM 中位 ${bpmMedian.toFixed(0)}，分布 慢(<90) ${pct(bpmDistribution.slow)} ` +
        `/ 中(90-130) ${pct(bpmDistribution.mid)} / 快(>130) ${pct(bpmDistribution.fast)}`,
    );
  } else {
    lines.push(`- BPM：大部分曲目 BPM 检测置信度低（可能是 ambient / 民谣 / 抽象类）`);
  }
  lines.push(
    `- 平均响度 ${rmsDbMean.toFixed(1)} dBFS，` +
      `动态范围 ${dynamicRangeDbMean.toFixed(1)} dB（${dynamicLabel(dynamicRangeDbMean)}）`,
  );
  lines.push(
    `- 音色：暗(<1.5kHz) ${pct(centroidDistribution.dark)} ` +
      `/ 中 ${pct(centroidDistribution.neutral)} / 亮(>3kHz) ${pct(centroidDistribution.bright)} ` +
      `· 谱重心均值 ${(centroidMean / 1000).toFixed(1)}kHz`,
  );
  if (headSilenceMean > 0.5 || tailSilenceMean > 0.5) {
    lines.push(
      `- 头/尾静默均值 ${headSilenceMean.toFixed(1)}s / ${tailSilenceMean.toFixed(1)}s` +
        (headSilenceMean > 1.5 ? "（普遍带 fade-in）" : ""),
    );
  }

  return {
    analyzed: valid.length,
    promptBlock: lines.join("\n"),
    metrics,
  };
}

function mean(xs: number[]): number {
  if (xs.length === 0) return 0;
  return xs.reduce((a, b) => a + b, 0) / xs.length;
}

function median(xs: number[]): number {
  const sorted = [...xs].sort((a, b) => a - b);
  const mid = Math.floor(sorted.length / 2);
  return sorted.length % 2 === 0
    ? (sorted[mid - 1] + sorted[mid]) / 2
    : sorted[mid];
}

function pct(x: number): string {
  return `${Math.round(x * 100)}%`;
}

function dynamicLabel(dr: number): string {
  if (dr < 6) return "高度压缩，主流商业流行";
  if (dr < 10) return "轻度压缩，主流流行/独立";
  if (dr < 14) return "保留动态，独立/民谣常见";
  return "动态宽广，古典/原声/氛围常见";
}
