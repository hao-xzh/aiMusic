"use client";

/**
 * 节拍/小节/phrase 边界工具。
 *
 * mix-planner 在 BPM 对齐分支里用它把 A 的 fade-out 起点吸到 phrase
 * 边界（默认 16 拍 = 4 小节），并把 B 的入点对到 firstBeatS，
 * 让两首歌的强拍尽量重合。
 *
 * 所有函数对异常输入（非有限值、bpm<=0）都直接返回原 timeS，
 * 不抛错 —— 调用方可以无脑 fallback。
 */

export function beatLengthS(bpm: number): number {
  return 60 / bpm;
}

export function phraseLengthS(bpm: number, phraseBeats = 16): number {
  return beatLengthS(bpm) * phraseBeats;
}

function isValid(timeS: number, firstBeatS: number, bpm: number): boolean {
  return (
    Number.isFinite(timeS) &&
    Number.isFinite(firstBeatS) &&
    Number.isFinite(bpm) &&
    bpm > 0
  );
}

/** 离 timeS 最近的 phrase 边界（可能在 timeS 之前或之后）。 */
export function nearestBeatBoundary(
  timeS: number,
  firstBeatS: number,
  bpm: number,
  phraseBeats = 16,
): number {
  if (!isValid(timeS, firstBeatS, bpm)) return timeS;
  const phraseS = phraseLengthS(bpm, phraseBeats);
  const n = Math.round((timeS - firstBeatS) / phraseS);
  return firstBeatS + n * phraseS;
}

/**
 * timeS 之前（含等于）的最近 phrase 边界。
 *
 * mix-planner 用这个找 A 的 fade-out 起点 —— 必须 ≤ rawOutStart，
 * 否则重叠时长会被压缩到比 plan 预算还小，听感会被截短。
 */
export function previousBeatBoundary(
  timeS: number,
  firstBeatS: number,
  bpm: number,
  phraseBeats = 16,
): number {
  if (!isValid(timeS, firstBeatS, bpm)) return timeS;
  const phraseS = phraseLengthS(bpm, phraseBeats);
  const n = Math.floor((timeS - firstBeatS) / phraseS);
  return firstBeatS + n * phraseS;
}

/** timeS 之后（含等于）的最近 phrase 边界。 */
export function nextBeatBoundary(
  timeS: number,
  firstBeatS: number,
  bpm: number,
  phraseBeats = 16,
): number {
  if (!isValid(timeS, firstBeatS, bpm)) return timeS;
  const phraseS = phraseLengthS(bpm, phraseBeats);
  const n = Math.ceil((timeS - firstBeatS) / phraseS);
  return firstBeatS + n * phraseS;
}
