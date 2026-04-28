"use client";

/**
 * Mix Planner —— 接歌怎么接的"硬决策层"。
 *
 * 输入：A（前一首）+ B（下一首）的 TrackAnalysis；可选的 AI 美学判断。
 * 输出：MixPlan 告诉播放引擎：什么时候触发、B 从哪一秒开始播、
 *       重叠多久、要不要 level match、要不要 EQ duck。
 *
 * 关键判断逻辑：
 *   - 触发时机：让 A 的尾奏跟 B 的前奏正好重叠
 *     A 的最后 N 秒是非人声（outroStartS 之后）= 这 N 秒可以重叠
 *     B 的前 M 秒是非人声/非鼓（drumEntry 之前）= 这 M 秒可以做接歌的"床"
 *     重叠时长 = min(N, M)，钳到 [2, 8]
 *   - BPM 对齐：A.bpm ≈ B.bpm 时，重叠取整 phrase（4/8/16 拍）；
 *     不对齐时用上面的 budget 直接钳。
 *   - Level match：A.rmsDb - B.rmsDb，最多 ±6dB。
 *   - EQ duck：BPM 对齐 + 重叠 ≥4s 时打开（重叠期对 outgoing low-shelf -3dB，
 *     防止两首歌中频糊在一起）。
 *
 * 任意一边没有 analysis（首次播 + 还没分析完）→ 退回 AI judgment 给的默认值。
 */

import type { TrackAnalysis } from "./audio-analysis";
import type { TransitionJudgment } from "./transition-judge";
import { previousBeatBoundary } from "./beat-grid";

/**
 * 接歌模式 —— 让播放器一眼分辨"这是什么类型的过渡"，
 * 不要再用 `durationS===0` 这种隐式信号判 hard_cut。
 *
 *   - "hard_cut"：同专辑无缝接 / 戏剧反差。播放器用极短淡化（80ms）+
 *      触发点几乎贴到曲尾（不要提前 0.4s），保留尾音收束。
 *   - "crossfade"：常规 equal-power 慢溶（含 BPM 对齐分支）。
 *   - "short_seam"：A 没干净尾奏，乐句短接，避免盖住最后一句歌词。
 *   - "breath"：silence_breath 真留白（v2 引擎才支持，当前按 short_seam 近似）。
 */
export type MixMode = "hard_cut" | "crossfade" | "short_seam" | "breath";

export type MixPlan = {
  /** 模式：决定播放器走哪条执行路径，比 durationS===0 更稳。 */
  mode: MixMode;
  /** crossfade 触发点：从 A 结束倒数多少秒开始 fade */
  triggerBeforeEndS: number;
  /** B 开始播放前 seek 到的秒数（trim silent intro 之外的额外修剪） */
  inSeekS: number;
  /** 重叠时长 */
  durationS: number;
  /** B 的 gain 调整（dB）—— level match A */
  bGainDb: number;
  /** 重叠期对 outgoing 做 low-shelf duck */
  eqDuck: boolean;
  /** 是否走了 BPM 对齐路径 */
  bpmAligned: boolean;
  /**
   * BPM 对齐时：A 在这个绝对秒点开始 fade-out（对齐到 phrase 边界）。
   * 没对齐就 undefined。仅供日志/未来精确触发使用 ——
   * 当前播放器仍主要依赖 triggerBeforeEndS。
   */
  fromOutStartS?: number;
  /**
   * BPM 对齐时：B 应该从这个绝对秒点开始播（≈ firstBeatS）。
   * 没对齐就 undefined。播放器会用它覆盖默认的 inSeekS，
   * 让 B 的第一拍正好落到 fade-in 起点。
   */
  toInStartS?: number;
  /** 调试用 */
  reason: string;
};

const HARD_CUT_PLAN: MixPlan = {
  mode: "hard_cut",
  triggerBeforeEndS: 0,
  inSeekS: 0,
  durationS: 0,
  bGainDb: 0,
  eqDuck: false,
  bpmAligned: false,
  reason: "hard cut",
};

/**
 * 主入口。两份分析任一缺失就退回 judgment-only 默认。
 */
export function planMix(
  a: TrackAnalysis | null,
  b: TrackAnalysis | null,
  judgment: TransitionJudgment,
): MixPlan {
  // AI 说硬剪就硬剪 —— 这种通常是同专辑无缝接 / 戏剧反差
  if (judgment.style === "hard_cut") return HARD_CUT_PLAN;

  // 任一缺分析 → 乐句短接。没有音频分析时不知道上一首最后一句歌词在哪，
  // 但仍要给用户听得到的重叠时间，避免像普通切歌。
  if (!a || !b) {
    const aiDur = judgment.durationMs / 1000;
    const dur =
      judgment.style === "silence_breath"
        ? clamp(aiDur > 0 ? aiDur * 0.7 : 1.8, 1.2, 2.4)
        : clamp(aiDur > 0 ? aiDur * 0.75 : 2.4, 1.8, 3.2);
    return {
      mode: judgment.style === "silence_breath" ? "breath" : "short_seam",
      triggerBeforeEndS: dur,
      inSeekS: 0,
      durationS: dur,
      bGainDb: 0,
      eqDuck: judgment.eqDuck,
      bpmAligned: false,
      reason: `no analysis, force ${dur.toFixed(1)}s (style=${judgment.style})`,
    };
  }

  // ---- 有分析：硬决策 ----

  // 1) A 尾奏长度（outroStartS 到曲尾）
  const aOutro = pickOutroProfile(a);

  // 2) B 引导长度（0 → drumEntry / vocalEntry，谁先到算谁）
  // 如果 B 引导太长（>12s），seek 到引导点前 4s 入歌，避免空白尾巴拖太久。
  const bIntroPoint = pickIntroPoint(b);
  let inSeekS = 0;
  if (bIntroPoint > 12) {
    inSeekS = Math.max(0, bIntroPoint - 4);
  }
  const bIntroFromSeek = Math.max(0, bIntroPoint - inSeekS);

  // 3) 重叠预算
  // 没有引导（rap/电子直接进鼓 → bIntroPoint=0）→ 用默认 4s
  const bBudget = bIntroFromSeek > 0.5 ? bIntroFromSeek : 4;
  const overlapBudget = Math.min(aOutro.budgetS, bBudget);
  const energyDelta = Math.abs(a.outroEnergy - b.introEnergy);
  const vocalClash = a.outroVocalDensity * b.introVocalDensity;
  let durationS = clamp(overlapBudget, 1.2, 8);
  let triggerBeforeEndS = durationS;
  let bpmAligned = false;
  let fromOutStartS: number | undefined;
  let toInStartS: number | undefined;
  let reasonBits: string[] = [];

  // BPM 对齐能否启用 —— 只是"候选"，真对齐放到所有 duration 修正之后做。
  // 顺序很重要：如果先对齐再让 vocalClash 把 durationS 砍短，
  // phrase 长度就被破坏了，fromOutStartS 跟实际重叠对不上。
  const bpmCandidate =
    aOutro.kind === "blend" &&
    a.bpm !== null &&
    b.bpm !== null &&
    a.bpmConfidence > 0.25 &&
    b.bpmConfidence > 0.25 &&
    Math.abs(a.bpm - b.bpm) < 8
      ? a.bpm
      : null;

  // 4) AI 判断的 style 微调：
  //   soft → 倾向更长（拉到 budget 上限）
  //   tight → 倾向当前值（已经短）
  //   silence_breath → 极短 + 加 inSeekS 偏移制造呼吸（v1 暂不实现 silence，按 tight 处理）
  if (judgment.style === "soft") {
    const bonus = energyDelta <= 0.12 && vocalClash <= 0.08 ? 1 : 0.35;
    durationS = Math.min(overlapBudget, durationS + bonus);
    reasonBits.push("soft 拉长");
  }

  if (energyDelta > 0.22) {
    durationS = Math.max(1.4, durationS * 0.78);
    reasonBits.push("尾头能量差大，短接");
  }

  if (vocalClash > 0.16) {
    durationS = Math.min(durationS, 1.6);
    reasonBits.push("高人声冲突");
  } else if (vocalClash > 0.08) {
    durationS = Math.min(durationS, 2.4);
    reasonBits.push("中等人声冲突");
  }

  // 5) BPM 对齐：用最终 durationS 找最大可容纳的 phrase 长度，
  // 然后把 fade-out 起点吸到 phrase 边界。
  // 必须在所有 duration 修正之后做 —— 否则 vocalClash/energyDelta
  // 之类的修正会把已经吸过的 phrase 长度再砍短，破坏对齐。
  if (bpmCandidate !== null) {
    const beatS = 60 / bpmCandidate;
    const candidates = [beatS * 4, beatS * 8, beatS * 16];
    // 严格不超过当前 durationS（不再允许 +0.5 容差），保证不爆 budget
    const fit = candidates.filter((p) => p <= durationS);
    if (fit.length > 0 && durationS >= beatS * 4 - 0.05) {
      // 在能塞下的 phrase 里选最长的（最接近 durationS）
      durationS = fit.reduce((best, p) => (p > best ? p : best));
      bpmAligned = true;
      reasonBits.push(`BPM≈${Math.round(bpmCandidate)} 对齐 ${Math.round(durationS / beatS)} 拍`);

      // 把 A 的 fade-out 起点吸到 phrase 边界，B 的入点对到 firstBeatS。
      // 用 previousBeatBoundary 而不是 nearestBeatBoundary —— 保证
      // fade-out 起点不会比 rawOutStart 还晚（否则会压缩重叠时间）。
      if (a.firstBeatS !== null && Number.isFinite(a.durationS)) {
        const rawOutStart = a.durationS - durationS;
        // 限制在 outroStartS 之后 —— 别把 fade-out 拉到尾奏开始之前的人声区
        const minOutStart = a.outroStartS ?? Math.max(0, a.durationS - aOutro.budgetS);
        const aligned = previousBeatBoundary(rawOutStart, a.firstBeatS, bpmCandidate, 16);
        fromOutStartS = Math.max(minOutStart, aligned);
      }
      if (b.firstBeatS !== null && b.firstBeatS > 0.05) {
        toInStartS = b.firstBeatS;
      }
    }
  }

  // 6) 触发时机：
  // - phrase_seam：等上一首基本落地，只做乐句短接，不盖最后一句歌词。
  // - bpmAligned：让 fade-out 真正落在 phrase 边界（用 fromOutStartS 倒推）。
  //   不再用 durationS 当 trigger —— 那只是"重叠多久"，跟"什么时候开始"是两件事。
  // - 其它（普通 blend）：fade-out 持续多久，就在曲尾倒数多久触发。
  if (aOutro.kind === "phrase_seam") {
    const bStartsBusy = b.introVocalDensity > 0.25 || b.introLowEnergy > 0.045;
    durationS = bStartsBusy ? 1.4 : 2.2;
    triggerBeforeEndS = durationS;
    reasonBits.push("乐句短接");
  } else if (bpmAligned && fromOutStartS !== undefined) {
    triggerBeforeEndS = Math.max(0.25, a.durationS - fromOutStartS);
  } else {
    triggerBeforeEndS = durationS;
  }

  // 7) Level match
  // 上限收紧到 +2dB：incoming 增益超过 +2dB 容易削波 / 听感刺耳，
  // 而且转场结束后还有 ramp-back 到 1 的恢复，强提升意义不大。
  // 下限保留到 -4dB：当 A 尾比 B 头响很多时仍然要适度压低 B。
  const bGainDb = clamp(energyDb(a.outroEnergy) - energyDb(b.introEnergy), -4, 2);
  if (Math.abs(bGainDb) > 0.5) reasonBits.push(`level match ${bGainDb.toFixed(1)}dB`);

  // 8) EQ duck
  const eqDuck = (bpmAligned && durationS >= 4) || judgment.eqDuck;
  if (eqDuck) reasonBits.push("EQ duck");

  if (reasonBits.length === 0) reasonBits.push(`重叠 ${durationS.toFixed(1)}s`);

  // 9) 节拍对齐时，B 的入点优先用 firstBeatS（toInStartS），
  // 而不是默认的 inSeekS（B 引导超长才设的偏移）。
  const finalInSeekS = toInStartS !== undefined ? toInStartS : inSeekS;

  // mode 推导：从最终决策反推语义。phrase_seam 一律是 short_seam；
  // 其它都是 crossfade。AI 的 silence_breath 由 judge 直接给（在最上面分支处理）。
  const mode: MixMode =
    aOutro.kind === "phrase_seam" ? "short_seam" : "crossfade";

  return {
    mode,
    triggerBeforeEndS,
    inSeekS: finalInSeekS,
    durationS,
    bGainDb,
    eqDuck,
    bpmAligned,
    fromOutStartS,
    toInStartS,
    reason: reasonBits.join(" · "),
  };
}

function pickIntroPoint(b: TrackAnalysis): number {
  const candidates = [b.drumEntryS, b.vocalEntryS].filter(
    (x): x is number => x !== null,
  );
  if (candidates.length === 0) return 0;
  return Math.min(...candidates);
}

type OutroProfile = {
  kind: "blend" | "phrase_seam";
  budgetS: number;
};

function pickOutroProfile(a: TrackAnalysis): OutroProfile {
  if (a.outroStartS !== null) {
    return {
      kind: "blend",
      budgetS: clamp(a.durationS - a.outroStartS, 2.5, 10),
    };
  }
  // 没有明确干净尾奏，但尾部不是特别满时，也允许普通流行歌做轻度 blend。
  if (a.outroVocalDensity < 0.42 && a.outroEnergy < 0.55) {
    return {
      kind: "blend",
      budgetS: 3.2,
    };
  }
  // 没有人声的器乐/氛围曲，可以用最后一小段余音做长一点的混合。
  if (a.vocalEntryS === null) {
    return {
      kind: "blend",
      budgetS: clamp(a.durationS * 0.08, 2.5, 8),
    };
  }
  // 有人声但没检测到干净尾奏：多半是唱到结尾。只给一个短接窗口，
  // 让下一首像下一句旋律一样进来，而不是把两首歌叠在一起。
  return {
    kind: "phrase_seam",
    budgetS: 2.0,
  };
}

function energyDb(energy: number): number {
  if (energy < 1e-5) return -60;
  return 20 * Math.log10(energy);
}

function clamp(x: number, lo: number, hi: number): number {
  return x < lo ? lo : x > hi ? hi : x;
}
