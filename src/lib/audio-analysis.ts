"use client";

/**
 * 音频离线分析 —— 算出 BPM / 能量曲线 / 人声段 / 入点出点 等元信息，
 * 喂给 mix-planner 做"DJ 接歌"。
 *
 * 跑在主线程：fetch + decodeAudioData 是浏览器原生（native 速度），
 * 只有 RMS / biquad 两个循环是 JS。整曲 4 分钟 ~150-300ms 跑完。
 *
 * 单首一次永久缓存（cache.setState 'analysis:v3:${id}'）。
 *
 * v1 故意不做 key（调性）—— 客户端跑准确度差，省了。
 * mix-planner 用 BPM + outroStart + drumEntry 已经能做 90% 的"丝滑接"。
 */

import { cache } from "./tauri";

const VERSION = 3 as const;
const KEY_PREFIX = "analysis:v3:";

// 整套分析在 22050Hz 单声道下做。够用、快两倍。
const TARGET_SR = 22050;
// 帧 hop（onset 检测用）—— 22050/256 ≈ 86 fps，对 60-180 BPM 有充足分辨率。
const HOP = 256;

export type TrackAnalysis = {
  version: typeof VERSION;
  trackId: number;
  durationS: number;
  /** 60-200，null 表示太短或检测失败 */
  bpm: number | null;
  /** 0..1，autocorrelation 主峰强度归一化 */
  bpmConfidence: number;
  /** 第一拍位置（秒）。BPM null 时也 null */
  firstBeatS: number | null;
  /** 长度 ≈ floor(durationS) */
  energyPerSec: number[];
  lowEnergyPerSec: number[];
  vocalEnergyPerSec: number[];
  /** 人声概率，0..1，时间平滑过 */
  vocalPerSec: number[];
  /** 第一次大鼓（低频突增）的秒数；纯人声曲可能 null */
  drumEntryS: number | null;
  /** 第一段人声的起点；纯器乐曲 null */
  vocalEntryS: number | null;
  /** 最后一段人声结束 + 1s；纯器乐曲 null */
  outroStartS: number | null;
  /** 整曲平均响度（dBFS，负数）。用于 level match */
  rmsDb: number;
  /** 开头 8 秒平均能量；用于判断 B 的起句是否适合接 A 的尾句 */
  introEnergy: number;
  /** 结尾 8 秒平均能量；用于判断 A 的尾句和 B 的起句能否连起来 */
  outroEnergy: number;
  /** 开头 8 秒低频能量；用于判断 B 是否一进来就很重 */
  introLowEnergy: number;
  /** 结尾 8 秒低频能量；用于判断 A 的收尾是否仍然很满 */
  outroLowEnergy: number;
  /** 开头 8 秒人声密度；高值说明 B 可能直接开唱 */
  introVocalDensity: number;
  /** 结尾 8 秒人声密度；高值说明 A 可能唱到很末尾 */
  outroVocalDensity: number;
};

// ---------- 公共入口 ----------

const inflight = new Map<number, Promise<TrackAnalysis | null>>();

export async function loadAnalysis(trackId: number): Promise<TrackAnalysis | null> {
  try {
    const raw = await cache.getState(KEY_PREFIX + trackId);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as TrackAnalysis;
    if (parsed.version !== VERSION) return null;
    return parsed;
  } catch {
    return null;
  }
}

/**
 * 取分析结果。命中缓存直接返回；否则 fetch + decode + 算 + 缓存。
 *
 * 同一 trackId 同一时刻只跑一份 —— 多个调用者共享同一个 Promise。
 */
export async function getOrAnalyze(
  trackId: number,
  url: string,
): Promise<TrackAnalysis | null> {
  const cached = await loadAnalysis(trackId);
  if (cached) return cached;

  const existing = inflight.get(trackId);
  if (existing) return existing;

  const promise = (async () => {
    try {
      const result = await analyze(trackId, url);
      if (result) {
        cache
          .setState(KEY_PREFIX + trackId, JSON.stringify(result))
          .catch(() => {});
      }
      return result;
    } catch (e) {
      console.debug("[claudio] audio-analysis 失败", e);
      return null;
    } finally {
      inflight.delete(trackId);
    }
  })();
  inflight.set(trackId, promise);
  return promise;
}

// ---------- 主流程 ----------

async function analyze(
  trackId: number,
  url: string,
): Promise<TrackAnalysis | null> {
  // 1) fetch + decode
  const buf = await fetch(url).then((r) => r.arrayBuffer());
  const ACtx =
    window.AudioContext ??
    (window as unknown as { webkitAudioContext?: typeof AudioContext })
      .webkitAudioContext;
  if (!ACtx) return null;

  // 用一个临时 ctx 解码 —— 不连接 destination，不会发声
  const decodeCtx = new ACtx();
  let audioBuf: AudioBuffer;
  try {
    audioBuf = await decodeCtx.decodeAudioData(buf);
  } finally {
    decodeCtx.close().catch(() => {});
  }

  // 2) 转成 22050Hz 单声道 Float32Array
  const mono = downmixToMono(audioBuf);
  const resampled = resampleLinear(mono, audioBuf.sampleRate, TARGET_SR);
  const durationS = resampled.length / TARGET_SR;
  if (durationS < 8) return null; // 太短不分析

  // 3) 三个频段过 biquad（一次循环跑完）
  const lowBand = applyBiquad(resampled, biquadLowpass(TARGET_SR, 200));
  const vocalBand = applyBiquad(
    applyBiquad(resampled, biquadHighpass(TARGET_SR, 250)),
    biquadLowpass(TARGET_SR, 3500),
  );

  // 4) RMS 曲线（每秒一格）
  const energyPerSec = rmsPerSecond(resampled, TARGET_SR);
  const lowEnergyPerSec = rmsPerSecond(lowBand, TARGET_SR);
  const vocalEnergyPerSec = rmsPerSecond(vocalBand, TARGET_SR);

  const totalSec = energyPerSec.length;

  // 5) 人声概率：vocal / total 比，再时间平滑
  const vocalPerSec = computeVocalProb(
    energyPerSec,
    vocalEnergyPerSec,
    lowEnergyPerSec,
  );

  // 6) 关键点：drumEntry / vocalEntry / outroStart
  const drumEntryS = detectDrumEntry(lowEnergyPerSec);
  const vocalEntryS = detectVocalEntry(vocalPerSec);
  const outroStartS = detectOutroStart(vocalPerSec, totalSec);

  // 7) BPM：低频 onset envelope 自相关
  const { bpm, bpmConfidence, firstBeatS } = detectBpm(lowBand);

  // 8) 整曲 dBFS（用 energy 中位数，不被开头/结尾静音拖偏）
  const rmsDb = medianDb(energyPerSec);
  const introEnergy = meanWindow(energyPerSec, 0, 8);
  const outroEnergy = meanWindow(energyPerSec, Math.max(0, totalSec - 8), totalSec);
  const introLowEnergy = meanWindow(lowEnergyPerSec, 0, 8);
  const outroLowEnergy = meanWindow(lowEnergyPerSec, Math.max(0, totalSec - 8), totalSec);
  const introVocalDensity = meanWindow(vocalPerSec, 0, 8);
  const outroVocalDensity = meanWindow(vocalPerSec, Math.max(0, totalSec - 8), totalSec);

  return {
    version: VERSION,
    trackId,
    durationS,
    bpm,
    bpmConfidence,
    firstBeatS,
    energyPerSec,
    lowEnergyPerSec,
    vocalEnergyPerSec,
    vocalPerSec,
    drumEntryS,
    vocalEntryS,
    outroStartS,
    rmsDb,
    introEnergy,
    outroEnergy,
    introLowEnergy,
    outroLowEnergy,
    introVocalDensity,
    outroVocalDensity,
  };
}

// ---------- 工具：通道降维 + 线性重采样 ----------

function downmixToMono(buf: AudioBuffer): Float32Array {
  const ch = buf.numberOfChannels;
  const len = buf.length;
  const out = new Float32Array(len);
  if (ch === 1) {
    out.set(buf.getChannelData(0));
    return out;
  }
  const data = Array.from({ length: ch }, (_, i) => buf.getChannelData(i));
  for (let i = 0; i < len; i++) {
    let s = 0;
    for (let c = 0; c < ch; c++) s += data[c][i];
    out[i] = s / ch;
  }
  return out;
}

function resampleLinear(
  src: Float32Array,
  fromSr: number,
  toSr: number,
): Float32Array {
  if (fromSr === toSr) return src;
  const ratio = fromSr / toSr;
  const outLen = Math.floor(src.length / ratio);
  const out = new Float32Array(outLen);
  for (let i = 0; i < outLen; i++) {
    const x = i * ratio;
    const x0 = Math.floor(x);
    const frac = x - x0;
    const a = src[x0] ?? 0;
    const b = src[x0 + 1] ?? a;
    out[i] = a + (b - a) * frac;
  }
  return out;
}

// ---------- Biquad（Audio EQ Cookbook by Robert Bristow-Johnson） ----------

type BiquadCoefs = {
  b0: number;
  b1: number;
  b2: number;
  a1: number;
  a2: number;
};

function biquadLowpass(sr: number, fc: number, q = 0.707): BiquadCoefs {
  const w0 = (2 * Math.PI * fc) / sr;
  const cosw = Math.cos(w0);
  const sinw = Math.sin(w0);
  const alpha = sinw / (2 * q);
  const a0 = 1 + alpha;
  return {
    b0: ((1 - cosw) / 2) / a0,
    b1: (1 - cosw) / a0,
    b2: ((1 - cosw) / 2) / a0,
    a1: (-2 * cosw) / a0,
    a2: (1 - alpha) / a0,
  };
}

function biquadHighpass(sr: number, fc: number, q = 0.707): BiquadCoefs {
  const w0 = (2 * Math.PI * fc) / sr;
  const cosw = Math.cos(w0);
  const sinw = Math.sin(w0);
  const alpha = sinw / (2 * q);
  const a0 = 1 + alpha;
  return {
    b0: ((1 + cosw) / 2) / a0,
    b1: (-(1 + cosw)) / a0,
    b2: ((1 + cosw) / 2) / a0,
    a1: (-2 * cosw) / a0,
    a2: (1 - alpha) / a0,
  };
}

function applyBiquad(src: Float32Array, c: BiquadCoefs): Float32Array {
  const out = new Float32Array(src.length);
  let x1 = 0,
    x2 = 0,
    y1 = 0,
    y2 = 0;
  for (let i = 0; i < src.length; i++) {
    const x0 = src[i];
    const y0 = c.b0 * x0 + c.b1 * x1 + c.b2 * x2 - c.a1 * y1 - c.a2 * y2;
    out[i] = y0;
    x2 = x1;
    x1 = x0;
    y2 = y1;
    y1 = y0;
  }
  return out;
}

// ---------- RMS per second ----------

function rmsPerSecond(samples: Float32Array, sr: number): number[] {
  const totalSec = Math.floor(samples.length / sr);
  const out = new Array<number>(totalSec);
  for (let s = 0; s < totalSec; s++) {
    let sum = 0;
    const start = s * sr;
    const end = start + sr;
    for (let i = start; i < end; i++) {
      const v = samples[i];
      sum += v * v;
    }
    out[s] = Math.sqrt(sum / sr);
  }
  return out;
}

// ---------- Vocal probability ----------

function computeVocalProb(
  total: number[],
  vocal: number[],
  low: number[],
): number[] {
  const n = total.length;
  const raw = new Array<number>(n);
  for (let i = 0; i < n; i++) {
    const t = total[i] + 1e-6;
    // 人声段比"全频中人声带占比 - 低频鼓占比" —— 鼓多时压低
    const v = vocal[i] / t;
    const l = low[i] / t;
    raw[i] = clamp01(v * 1.4 - l * 0.6 - 0.12);
  }
  // 时间平滑：±2s 中位数过一遍，去毛刺
  return medianFilter(raw, 5);
}

function clamp01(x: number): number {
  return x < 0 ? 0 : x > 1 ? 1 : x;
}

function medianFilter(arr: number[], window: number): number[] {
  const half = Math.floor(window / 2);
  const n = arr.length;
  const out = new Array<number>(n);
  for (let i = 0; i < n; i++) {
    const a = Math.max(0, i - half);
    const b = Math.min(n, i + half + 1);
    const slice = arr.slice(a, b).sort((x, y) => x - y);
    out[i] = slice[Math.floor(slice.length / 2)];
  }
  return out;
}

// ---------- 关键点检测 ----------

function detectDrumEntry(lowPerSec: number[]): number | null {
  if (lowPerSec.length < 10) return null;
  // baseline：前 5 秒中位数
  const baseline = median(lowPerSec.slice(0, Math.min(5, lowPerSec.length)));
  const threshold = Math.max(baseline * 1.4, 0.02);
  const SUSTAIN = 2;
  let run = 0;
  for (let i = 0; i < lowPerSec.length; i++) {
    if (lowPerSec[i] >= threshold) {
      run++;
      if (run >= SUSTAIN) return i - SUSTAIN + 1;
    } else {
      run = 0;
    }
  }
  return null;
}

function detectVocalEntry(vocalPerSec: number[]): number | null {
  const SUSTAIN = 2;
  let run = 0;
  for (let i = 0; i < vocalPerSec.length; i++) {
    if (vocalPerSec[i] >= 0.5) {
      run++;
      if (run >= SUSTAIN) return i - SUSTAIN + 1;
    } else {
      run = 0;
    }
  }
  return null;
}

function detectOutroStart(
  vocalPerSec: number[],
  totalSec: number,
): number | null {
  // 反向扫，找最后一段连续 ≥0.5 的人声块结束
  let lastVocalEnd = -1;
  let inVocal = false;
  for (let i = 0; i < vocalPerSec.length; i++) {
    if (vocalPerSec[i] >= 0.5) {
      inVocal = true;
    } else if (inVocal) {
      lastVocalEnd = i;
      inVocal = false;
    }
  }
  // 人声一直唱到文件尾：这类歌没有干净尾奏，不能硬造一个 2s outro，
  // 否则播放器会在最后一句歌词上盖下一首，听起来很生硬。
  if (inVocal) return null;
  if (lastVocalEnd < 0) return null;

  if (totalSec - lastVocalEnd < 2.5) return null;

  // 尾奏起点 = 人声结束后 1s 的位置；不能比曲尾还晚
  const start = Math.min(lastVocalEnd + 1, totalSec - 2);
  return start > 0 ? start : null;
}

function median(arr: number[]): number {
  if (arr.length === 0) return 0;
  const s = [...arr].sort((a, b) => a - b);
  return s[Math.floor(s.length / 2)];
}

function meanWindow(arr: number[], start: number, end: number): number {
  const a = Math.max(0, Math.floor(start));
  const b = Math.min(arr.length, Math.ceil(end));
  if (b <= a) return 0;
  let sum = 0;
  for (let i = a; i < b; i++) sum += arr[i];
  return sum / (b - a);
}

function medianDb(energy: number[]): number {
  const m = median(energy);
  if (m < 1e-5) return -60;
  return 20 * Math.log10(m);
}

// ---------- BPM 检测 ----------

function detectBpm(
  lowBand: Float32Array,
): { bpm: number | null; bpmConfidence: number; firstBeatS: number | null } {
  // 1) 低频信号的 onset envelope：每帧（hop=256）的 RMS 一阶差分（正部分）
  const fps = TARGET_SR / HOP;
  const frames = Math.floor(lowBand.length / HOP);
  if (frames < fps * 8) {
    return { bpm: null, bpmConfidence: 0, firstBeatS: null };
  }
  const env = new Float32Array(frames);
  let prev = 0;
  for (let f = 0; f < frames; f++) {
    let sum = 0;
    const start = f * HOP;
    for (let i = 0; i < HOP; i++) {
      const v = lowBand[start + i];
      sum += v * v;
    }
    const rms = Math.sqrt(sum / HOP);
    const diff = rms - prev;
    env[f] = diff > 0 ? diff : 0;
    prev = rms;
  }

  // 2) 自相关：lag 范围对应 60-180 BPM
  // beats per second = bpm/60；frames per beat = fps / bps = fps * 60 / bpm
  const minLag = Math.floor((fps * 60) / 180);
  const maxLag = Math.ceil((fps * 60) / 60);
  let bestLag = -1;
  let bestScore = 0;
  let totalScore = 0;
  for (let lag = minLag; lag <= maxLag; lag++) {
    let acc = 0;
    for (let i = 0; i + lag < frames; i++) {
      acc += env[i] * env[i + lag];
    }
    totalScore += acc;
    if (acc > bestScore) {
      bestScore = acc;
      bestLag = lag;
    }
  }
  if (bestLag < 0 || totalScore <= 0) {
    return { bpm: null, bpmConfidence: 0, firstBeatS: null };
  }
  const bpm = (fps * 60) / bestLag;
  const meanScore = totalScore / (maxLag - minLag + 1);
  const confidence = clamp01((bestScore / (meanScore || 1) - 1) / 3);

  // 3) firstBeatS：在前 6 秒的 onset envelope 里找最大值的位置
  const searchEnd = Math.min(frames, Math.floor(fps * 6));
  let peakF = 0;
  let peakV = 0;
  for (let f = 0; f < searchEnd; f++) {
    if (env[f] > peakV) {
      peakV = env[f];
      peakF = f;
    }
  }
  const firstBeatS = peakF / fps;

  return { bpm, bpmConfidence: confidence, firstBeatS };
}
