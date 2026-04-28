"use client";

/**
 * Transition Judge —— AI 决定"接歌的美学风格"。
 *
 * 这一层只管美学决定（要硬剪？要慢溶？要呼吸？），不管 BPM / 拍点 /
 * 能量 —— 那些由 mix-planner 基于 audio-analysis 的真实数据来定。
 *
 * AI 只能看到 title + artist + album 文本，没有真音频，强行让它猜 BPM
 * 或拍点会乱猜，所以这里把它的工作收敛成：
 *
 *   - hard_cut：同专辑无缝接 / 戏剧反差（mix-planner 看到 hard_cut 直接 0 时长）
 *   - silence_breath：情绪反差需要"短停顿"（v1 引擎按短重叠近似）
 *   - soft / tight：氛围标签，mix-planner 当弱提示（soft 倾向稍长，tight 默认）
 *
 * durationMs / eqDuck 这两个字段保留，但只在 analysis 缺失时当兜底；
 * 有 analysis 时 mix-planner 会用真实数据覆盖这两个值。
 *
 * 缓存：每对（fromId, toId）一份判断，写到 cache.setState `transition:F:T`。
 * 因为同一对的判断不该变；下次再遇到这个对子直接读缓存，不再调 AI。
 */

import { parseJsonObjectLike } from "./ai-json";
import { ai, cache } from "./tauri";
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
  /**
   * 来源：决定缓存策略。
   *   - "ai"：真 AI 返回。永久缓存。
   *   - "timeout" / "fallback"：兜底默认值。**不写缓存**，不污染下次判断。
   * 不带这个字段时按 ai 处理（向后兼容旧缓存）。
   */
  source?: "ai" | "timeout" | "fallback";
};

export const DEFAULT_JUDGMENT: TransitionJudgment = {
  style: "soft",
  durationMs: 4000,
  eqDuck: false,
  source: "fallback",
};

const JUDGE_TIMEOUT_MS = 6000; // AI 拉太久就不等，用默认

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
 * 主入口。优先读缓存；缓存 miss 才调 AI。
 * 调用方应该在 preload 阶段就发起这个，到 crossfade 触发点之前
 * 通常已经 resolve 完。timeout 兜底返回 DEFAULT_JUDGMENT。
 *
 * audio：可选的真实音频摘要（来自 audio-analysis）。喂给 AI 后判断更准 ——
 * 同一对（A,B）在 audio 信息不同时会缓存到不同 key，互不污染。
 */
export async function judgeTransition(
  from: Track,
  to: Track,
  audio?: TransitionAudioContext,
): Promise<TransitionJudgment> {
  if (!from.neteaseId || !to.neteaseId) return DEFAULT_JUDGMENT;
  const key = cacheKey(from.neteaseId, to.neteaseId, audio);

  // 1) 缓存命中
  try {
    const raw = await cache.getState(key);
    if (raw) {
      const parsed = JSON.parse(raw) as TransitionJudgment;
      if (validJudgment(parsed)) return parsed;
    }
  } catch {
    /* 坏缓存忽略 */
  }

  // 2) AI 判断（带超时）
  const aiPromise = callAi(from, to, audio);
  const timeoutFallback: TransitionJudgment = {
    ...DEFAULT_JUDGMENT,
    source: "timeout",
  };
  const timeoutPromise = new Promise<TransitionJudgment>((resolve) =>
    setTimeout(() => resolve(timeoutFallback), JUDGE_TIMEOUT_MS),
  );
  const judgment = await Promise.race([aiPromise, timeoutPromise]);

  // 3) 缓存：只写真 AI 结果。timeout / fallback 不写 ——
  // 否则一次网络抖动会把"soft 默认"永久钉死，遮住后续真实判断。
  if (judgment.source === "ai") {
    cache.setState(key, JSON.stringify(judgment)).catch(() => {});
  }
  return judgment;
}

async function callAi(
  from: Track,
  to: Track,
  audio?: TransitionAudioContext,
): Promise<TransitionJudgment> {
  const fromDesc = `${from.title} — ${from.artist}${from.album ? ` · ${from.album}` : ""}`;
  const toDesc = `${to.title} — ${to.artist}${to.album ? ` · ${to.album}` : ""}`;
  const sameAlbum = from.album && to.album && from.album === to.album;
  const audioBlock = describeAudioContext(audio);

  const user =
    `两首相邻播放的歌：\n` +
    `上一首：${fromDesc}\n` +
    `下一首：${toDesc}\n` +
    (sameAlbum ? `（这两首是同一张专辑的相邻曲目）\n` : `（不同专辑）\n`) +
    audioBlock +
    `\n任务：你只决定接歌的"美学风格"。BPM / 拍点 / 时长由我后端的音频分析算，不要你猜。\n` +
    `\n**默认就是 soft**。绝大多数普通听歌场景都应该慢溶接 —— 这是用户最想要的"丝滑"。\n` +
    `只有以下少数情况才考虑别的：\n` +
    `  - tight：两首明显都是同一种舞曲/电子流派、想"咬"得紧一点 —— 不超过 5% 的对子\n` +
    `  - hard_cut：**仅限同专辑相邻曲目**（很多专辑设计就是无缝接，加 fade 反而破坏）—— 不同专辑绝对不要 hard_cut\n` +
    `  - silence_breath：极强情绪反差（比如哀伤抒情→打鸡血舞曲）—— 不超过 2% 的对子\n` +
    `\n返回结构：\n` +
    `  style: "soft" | "tight" | "hard_cut" | "silence_breath"\n` +
    `  durationMs: 仅在分析缺失时做兜底，soft=4500，tight=4000，hard_cut=0，silence_breath=900\n` +
    `  eqDuck: 仅做提示，true/false\n` +
    `  rationale: ≤20 字理由\n` +
    `严格只输出一行 JSON：{"style":"soft","durationMs":4500,"eqDuck":false,"rationale":"..."}`;

  try {
    const raw = await ai.chat({
      system:
        "你是 Claudio 的接歌美学顾问。只决定 style。**默认就是 soft 慢溶**——这是用户想要的丝滑感。tight / hard_cut / silence_breath 都是少数特例。hard_cut 仅限同一专辑相邻曲目。BPM / 拍点不要你猜。只输出 JSON。",
      user,
      temperature: 0.2,
      maxTokens: 200,
    });
    const parsed = parseJsonObject(raw);
    if (parsed && validJudgment(parsed)) {
      // 后端兜底：AI 误把不同专辑判成 hard_cut → 改回 soft
      if (parsed.style === "hard_cut" && !sameAlbum) {
        console.debug("[claudio] judgeTransition：拒绝跨专辑 hard_cut，改为 soft");
        return {
          ...parsed,
          style: "soft",
          durationMs: Math.max(parsed.durationMs, 4500),
          source: "ai",
        };
      }
      return { ...parsed, source: "ai" };
    }
  } catch (e) {
    console.debug("[claudio] judgeTransition AI 失败，用默认", e);
  }
  return { ...DEFAULT_JUDGMENT, source: "fallback" };
}

/**
 * 把 TrackAnalysis 摘要成几行文本喂给 AI。
 * 只放对"接歌美学决策"有用的字段：BPM、能量、人声密度、是否有干净尾奏。
 * 故意不放完整曲线（没必要，只会拉爆 prompt 长度 / 注意力）。
 */
function describeAudioContext(audio?: TransitionAudioContext): string {
  if (!audio || (!audio.from && !audio.to)) return "";
  const lines: string[] = ["", "音频分析（真实数据，参考用，BPM 别猜）："];
  if (audio.from) {
    const a = audio.from;
    const bpm = a.bpm ? `BPM≈${Math.round(a.bpm)}` : "BPM=未知";
    const cleanOutro =
      a.outroStartS !== null
        ? `有干净尾奏(从 ${a.outroStartS.toFixed(0)}s)`
        : "无干净尾奏（唱到尾）";
    lines.push(
      `  上一首：${bpm}，尾段能量=${a.outroEnergy.toFixed(2)}，` +
        `尾段人声密度=${a.outroVocalDensity.toFixed(2)}，${cleanOutro}`,
    );
  }
  if (audio.to) {
    const b = audio.to;
    const bpm = b.bpm ? `BPM≈${Math.round(b.bpm)}` : "BPM=未知";
    const drumIn = b.drumEntryS !== null ? `鼓点进入 ${b.drumEntryS.toFixed(0)}s` : "无鼓";
    const vocalIn =
      b.vocalEntryS !== null ? `人声进入 ${b.vocalEntryS.toFixed(0)}s` : "纯器乐";
    lines.push(
      `  下一首：${bpm}，开头能量=${b.introEnergy.toFixed(2)}，` +
        `开头人声密度=${b.introVocalDensity.toFixed(2)}，${drumIn}，${vocalIn}`,
    );
  }
  return lines.join("\n") + "\n";
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

function parseJsonObject(raw: string): TransitionJudgment | null {
  return parseJsonObjectLike(raw) as TransitionJudgment | null;
}
