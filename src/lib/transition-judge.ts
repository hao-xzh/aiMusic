"use client";

/**
 * Transition Judge —— 播放链路的本地接歌风格判断。
 *
 * 这一层只管美学决定（要硬剪？要慢溶？要呼吸？），不管 BPM / 拍点 /
 * 能量 —— 那些由 mix-planner 基于 audio-analysis 的真实数据来定。
 *
 * 历史版本会在这里请求 AI 判断美学风格。现在播放链路不再主动请求 AI，
 * 而是用本地音频分析决定：
 *
 *   - hard_cut：同专辑无缝接 / 戏剧反差（mix-planner 看到 hard_cut 直接 0 时长）
 *   - silence_breath：情绪反差需要"短停顿"（v1 引擎按短重叠近似）
 *   - soft / tight：氛围标签，mix-planner 当弱提示（soft 倾向稍长，tight 默认）
 *
 * durationMs / eqDuck 这两个字段保留，但只在 analysis 缺失时当兜底；
 * 有 analysis 时 mix-planner 会用真实数据覆盖这两个值。
 *
 * 缓存：每对（fromId, toId）一份历史判断，写在 cache.setState `transition:F:T`。
 * 新版本只在音频分析缺失时读取历史缓存，不写入，避免听歌时产生模型请求。
 */

import { cache } from "./tauri";
import type { Track } from "./player-state";
import type { TrackAnalysis } from "./audio-analysis";

export type TransitionStyle = "soft" | "tight" | "hard_cut" | "silence_breath";

/**
 * 喂给 AI 美学判断的真实音频摘要。
 * AI 用它代替"靠歌名瞎猜 BPM"，但仍只输出 style，BPM/拍点交给 mix-planner。
 *
 * 任何字段可空：A 端通常已分析（preload 阶段缓存命中），
 * B 端在 preload 之前可能还没分析完，传 null 不要紧。
 */
export type TransitionAudioContext = {
  from?: TrackAnalysis | null;
  to?: TrackAnalysis | null;
};

export type TransitionJudgment = {
  style: TransitionStyle;
  durationMs: number;
  /** v2 才用：EQ ducking。v1 先记下 AI 的意图，引擎暂不实现 */
  eqDuck: boolean;
  /** AI 给出的简短理由 —— 调试用 */
  rationale?: string;
  /** 来源：只做调试展示；播放链路不会因为它写缓存或请求 AI。 */
  source?: "ai" | "local" | "timeout" | "fallback";
};

export const DEFAULT_JUDGMENT: TransitionJudgment = {
  style: "soft",
  durationMs: 4000,
  eqDuck: false,
  source: "fallback",
};

/**
 * v2 加了音频摘要。v1 缓存值是只看歌名做的判断，价值低，让它过期失效。
 * 不同 audio context 的判断也分开缓存（aOnly/full/none），避免互相覆盖。
 */
function cacheKey(
  fromId: number,
  toId: number,
  ctx: TransitionAudioContext | undefined,
): string {
  let suffix = "none";
  if (ctx?.from && ctx?.to) suffix = "full";
  else if (ctx?.from) suffix = "aOnly";
  else if (ctx?.to) suffix = "bOnly";
  return `transition:v2:${fromId}:${toId}:${suffix}`;
}

/**
 * 主入口。优先用本地音频分析判断；分析缺失时才读历史缓存。
 *
 * 播放链路必须是零 AI 请求：用户只是在听歌时，不应该按相邻歌曲对持续消耗额度。
 */
export async function judgeTransition(
  from: Track,
  to: Track,
  audio?: TransitionAudioContext,
): Promise<TransitionJudgment> {
  if (!from.neteaseId || !to.neteaseId) return DEFAULT_JUDGMENT;

  const localJudgment = judgeFromAnalysis(from, to, audio?.from ?? null, audio?.to ?? null);
  if (localJudgment) return localJudgment;

  const key = cacheKey(from.neteaseId, to.neteaseId, audio);

  try {
    const raw = await cache.getState(key);
    if (raw) {
      const parsed = JSON.parse(raw) as TransitionJudgment;
      if (validJudgment(parsed)) return parsed;
    }
  } catch {
    /* 坏缓存忽略 */
  }

  return DEFAULT_JUDGMENT;
}

function judgeFromAnalysis(
  from: Track,
  to: Track,
  a: TrackAnalysis | null,
  b: TrackAnalysis | null,
): TransitionJudgment | null {
  if (!a || !b) return null;

  const fromAlbum = from.album;
  const toAlbum = to.album;
  const sameAlbum =
    fromAlbum !== undefined &&
    toAlbum !== undefined &&
    normalizeText(fromAlbum) === normalizeText(toAlbum);
  const energyDelta = Math.abs(a.outroEnergy - b.introEnergy);
  const vocalClash = a.outroVocalDensity * b.introVocalDensity;
  const bpmDelta =
    a.bpm !== null && b.bpm !== null ? Math.abs(a.bpm - b.bpm) : Number.POSITIVE_INFINITY;
  const bpmReliable =
    a.bpm !== null &&
    b.bpm !== null &&
    a.bpmConfidence > 0.25 &&
    b.bpmConfidence > 0.25;
  const hasCleanOutro =
    a.outroStartS !== null ||
    (a.outroVocalDensity < 0.2 && a.outroLowEnergy < 0.05);
  const hasPlayableIntro =
    b.vocalEntryS === null ||
    b.vocalEntryS > 1.2 ||
    b.introVocalDensity < 0.22;

  if (
    sameAlbum &&
    hasCleanOutro &&
    hasPlayableIntro &&
    energyDelta <= 0.24 &&
    vocalClash <= 0.08
  ) {
    return {
      style: "hard_cut",
      durationMs: 0,
      eqDuck: false,
      rationale: "同专辑边界干净",
      source: "local",
    };
  }

  if (energyDelta >= 0.34 || vocalClash >= 0.2) {
    return {
      style: "silence_breath",
      durationMs: 900,
      eqDuck: false,
      rationale: "能量或人声反差大",
      source: "local",
    };
  }

  if (
    bpmReliable &&
    bpmDelta <= 7 &&
    energyDelta <= 0.22 &&
    vocalClash <= 0.12
  ) {
    return {
      style: "tight",
      durationMs: 3200,
      eqDuck: true,
      rationale: `BPM 接近 ${Math.round(bpmDelta)}`,
      source: "local",
    };
  }

  return {
    style: "soft",
    durationMs: energyDelta <= 0.16 && vocalClash <= 0.08 ? 4800 : 3600,
    eqDuck: false,
    rationale: "默认慢溶",
    source: "local",
  };
}

function normalizeText(value: string): string {
  return value.trim().toLowerCase();
}

function validJudgment(x: unknown): x is TransitionJudgment {
  if (!x || typeof x !== "object") return false;
  const j = x as Record<string, unknown>;
  if (typeof j.style !== "string") return false;
  if (!["soft", "tight", "hard_cut", "silence_breath"].includes(j.style as string)) return false;
  if (typeof j.durationMs !== "number") return false;
  if (j.durationMs < 0 || j.durationMs > 12000) return false;
  if (typeof j.eqDuck !== "boolean") return false;
  return true;
}
