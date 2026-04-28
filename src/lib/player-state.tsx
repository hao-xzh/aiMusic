"use client";

/**
 * 全局前端播放状态 v2 —— dual-deck 架构带 crossfade。
 *
 * 旧版单 audio 切歌是硬剪（src 一变 buffer 还没 ready 就 play），听感是
 * 「咔哒-静音 200ms-嗒」。新架构两路 <audio> + WebAudio gain ramp：
 *
 *     audio A → srcA → gainA ─┐
 *                              ├→ analyser → destination
 *     audio B → srcB → gainB ─┘
 *
 * 任何时刻只有一路是 active（gain≈1），另一路是 preload 或 fade-out。
 * 切歌：load 到 inactive，等 canplay → 等功率 crossfade（fade-out cos²，fade-in sin²）
 * → 完成后 swap active。
 *
 * 接近曲尾自动 next 时：
 *   - duration - PRELOAD_LEAD_S：开始预拉下一首到 inactive deck
 *   - duration - AUTO_CROSSFADE_S：如果预加载好就 kick crossfade
 *
 * 公共 API（usePlayer().playNetease/toggle/next/prev/...）跟旧版完全一致。
 */

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import { ai, cache, netease, type TrackInfo } from "./tauri";
import { cdn } from "./cdn";
import { parseLrc, type LrcLine } from "./lrc";
import {
  judgeTransition,
  DEFAULT_JUDGMENT,
  type TransitionJudgment,
} from "./transition-judge";
import { getOrAnalyze, loadAnalysis } from "./audio-analysis";
import { planMix, type MixPlan } from "./mix-planner";
import { smoothQueue } from "./smooth-queue";
import { logBehavior } from "./behavior-log";

// ---- 持久化键 ----
const LAST_TRACK_KEY = "last_track";
const LAST_POSITION_KEY = "last_position_sec";
const LAST_QUEUE_KEY = "last_queue";

// ---- crossfade 参数 ----
//
// 这一组参数是 Apple Music seamless 体感的关键：
//   - PRELOAD_LEAD_S 必须远大于任何 crossfade 时长 + 缓冲拉取时间，
//     否则 crossfade 触发时下一首还没 canplay，会出"接的时候顿一下"。
//   - 配合 audio.preload="auto"，从 PRELOAD_LEAD_S 那一刻起整首文件就在后台流入。
const PRELOAD_LEAD_S = 28;          // 28s 提前预拉下一首（覆盖最差 5G/弱网）
const AUTO_CROSSFADE_S = 4;         // 自动 crossfade 默认时长
const MANUAL_CROSSFADE_S = 0.45;    // 手动 next/prev：极短，"听不到咔"
const COLD_FADEIN_S = 0.25;         // 第一首 fade-in

// ---- 静音尾巴检测 ----
const SILENT_RMS_THRESHOLD = 0.012;

// ---- 静音前奏修剪 ----
//
// Apple Music 的 seamless 还有一招：检测下一首前奏是否有 1-3s 静音，
// crossfade 之前先 seek 过去。这样 fade-in 落到的是真音乐起始而不是空气。
const INTRO_TRIM_MAX_S = 3;       // 最多剪掉前 3s 静音
const INTRO_RMS_THRESHOLD = 0.018;

export type Track = {
  id: string;
  title: string;
  artist: string;
  album?: string;
  cover?: string;
  durationSec: number;
  neteaseId?: number;
};

export type LyricTrack = {
  lines: LrcLine[];
  instrumental: boolean;
  uncollected: boolean;
};

export type PlayerState = {
  current: Track | null;
  queue: Track[];
  isPlaying: boolean;
  positionSec: number;
  aiStatus: string;
  error: string | null;
  lyric: LyricTrack | null;
  /** 用户在招呼气泡选/输入的心情，session 内有效 */
  mood: string | null;
};

export type PlayNeteaseOptions = {
  /**
   * 是否对 contextQueue 跑 smooth-queue 重排（贪心局部 DJ 排序）。
   * 默认 true：任意歌单进入电台都做一次平滑。
   * pet-agent / 专辑播放等已经在自己上层重排过、或不希望被重排的入口应该传 false。
   */
  smooth?: boolean;
  /**
   * smooth=true 时使用的重排意图：
   *   - "library"（默认）：用户自己的歌单，同艺人惩罚轻
   *   - "discovery"：库外推荐 / 算法生成，强多样化
   * 见 src/lib/smooth-queue.ts。
   */
  smoothMode?: "library" | "discovery";
};

export type PlayerAPI = PlayerState & {
  playNetease: (
    t: TrackInfo,
    contextQueue?: TrackInfo[],
    opts?: PlayNeteaseOptions,
  ) => Promise<void>;
  pause: () => void;
  resume: () => void;
  toggle: () => void;
  next: () => void;
  prev: () => void;
  seek: (sec: number) => void;
  setAiStatus: (s: string) => void;
  setMood: (m: string | null) => void;
  /** 用户对当前曲目的正向反馈（不切歌） */
  like: () => void;
  /** 负向反馈：跳下一首 */
  dislike: () => void;
};

const PlayerCtx = createContext<PlayerAPI | null>(null);

export function usePlayer(): PlayerAPI {
  const v = useContext(PlayerCtx);
  if (!v) throw new Error("usePlayer must be used within PlayerProvider");
  return v;
}

function neteaseToTrack(t: TrackInfo): Track {
  return {
    id: `n:${t.id}`,
    title: t.name,
    artist: t.artists.map((a) => a.name).join(" / ") || "未知艺人",
    album: t.album?.name ?? undefined,
    cover: t.album?.picUrl ?? undefined,
    durationSec: Math.max(1, Math.round(t.durationMs / 1000)),
    neteaseId: t.id,
  };
}

// 等功率 crossfade 曲线生成 —— sin²(fade-in) + cos²(fade-out) = 1，
// 不像线性那样在中段会有"音量塌陷"。
function buildFadeCurves(N = 64): { fadeIn: Float32Array; fadeOut: Float32Array } {
  const fadeIn = new Float32Array(N);
  const fadeOut = new Float32Array(N);
  for (let i = 0; i < N; i++) {
    const k = i / (N - 1);
    const s = Math.sin((k * Math.PI) / 2);
    const c = Math.cos((k * Math.PI) / 2);
    fadeIn[i] = s * s;
    fadeOut[i] = c * c;
  }
  return { fadeIn, fadeOut };
}

function clamp(x: number, lo: number, hi: number): number {
  return x < lo ? lo : x > hi ? hi : x;
}

function transitionPairKey(fromId: number, toId: number): string {
  return `${fromId}:${toId}`;
}

function debugMixPlan(from: Track | null, to: Track, plan: MixPlan): void {
  console.debug("[mix-plan]", {
    from: from?.title ?? "—",
    to: to.title,
    type: plan.mode,
    durationS: plan.durationS,
    triggerBeforeEndS: plan.triggerBeforeEndS,
    inSeekS: plan.inSeekS,
    bpmAligned: plan.bpmAligned,
    bGainDb: plan.bGainDb,
    eqDuck: plan.eqDuck,
    reasonBits: plan.reason.split(" · "),
  });
}

function mediaHasPlayableSource(audio: HTMLAudioElement): boolean {
  return Boolean(audio.src) && audio.readyState >= HTMLMediaElement.HAVE_METADATA;
}

type DeckId = "A" | "B";

export function PlayerProvider({ children }: { children: React.ReactNode }) {
  // ---- 双 deck 资源 ----
  const audioARef = useRef<HTMLAudioElement | null>(null);
  const audioBRef = useRef<HTMLAudioElement | null>(null);
  const ctxRef = useRef<AudioContext | null>(null);
  // 全局混音 analyser（visualization 用，看混音后的整体振幅）
  const analyserRef = useRef<AnalyserNode | null>(null);
  // 每 deck 自己的 pre-analyser —— 在 gain 之前测，能独立看到"这首本身"是不是静音了。
  // 用来做"静音尾巴检测"：曲子还没到 audio.duration 但实际声音已经没了，提前 crossfade。
  const preAnalyserARef = useRef<AnalyserNode | null>(null);
  const preAnalyserBRef = useRef<AnalyserNode | null>(null);
  const gainARef = useRef<GainNode | null>(null);
  const gainBRef = useRef<GainNode | null>(null);
  // src node 持引用是 createMediaElementSource 的硬要求 —— GC 掉就静音
  const srcARef = useRef<MediaElementAudioSourceNode | null>(null);
  const srcBRef = useRef<MediaElementAudioSourceNode | null>(null);
  const rafRef = useRef<number | null>(null);
  // 每 deck 累计连续静音帧：本轮只进调试日志，不再直接触发自动切歌。
  const silentFramesARef = useRef<number>(0);
  const silentFramesBRef = useRef<number>(0);
  // 每 deck 现在装着哪一首（neteaseId）—— 用来 gate timeupdate：
  // 只有当 deck 的歌 == state.current 时才让它更新 positionSec。
  // 防止切歌瞬间旧 deck 的 timeupdate 把进度写成"上一首的位置"。
  const deckTrackIdRef = useRef<{ A: number | null; B: number | null }>({
    A: null,
    B: null,
  });

  // 哪一路是"用户感知的当前歌"
  const activeDeckRef = useRef<DeckId>("A");
  // inactive deck 上预加载好了的曲目 neteaseId + deck（自动 crossfade 时校对）
  const preloadedNeteaseIdRef = useRef<number | null>(null);
  const preloadedDeckRef = useRef<DeckId | null>(null);
  const preloadingNeteaseIdRef = useRef<number | null>(null);
  // 自动 crossfade 是否已经触发（每首歌只触发一次）
  const autoCrossfadeKickedRef = useRef<boolean>(false);
  // 当前对的 AI 过歌判断（preload 阶段一起拉）；触发 crossfade 时取出来用
  const pendingJudgmentRef = useRef<TransitionJudgment | null>(null);
  // 当前对的 mix plan（基于两首 analysis 算出来）—— 决定真正的接歌细节
  const pendingMixPlanRef = useRef<MixPlan | null>(null);
  const pendingPairKeyRef = useRef<string | null>(null);
  const preloadGenerationRef = useRef<number>(0);
  // 当前 active 曲目的"目标完成时间"（秒）—— 给 timeupdate 判断用
  const fadeCurvesRef = useRef<{ fadeIn: Float32Array; fadeOut: Float32Array } | null>(null);
  // 每 deck 自己的 EQ duck biquad（low-shelf @600Hz）—— crossfade 时压一下中频
  const duckFilterARef = useRef<BiquadFilterNode | null>(null);
  const duckFilterBRef = useRef<BiquadFilterNode | null>(null);

  const [state, setState] = useState<PlayerState>({
    current: null,
    queue: [],
    isPlaying: false,
    positionSec: 0,
    aiStatus: "待机",
    error: null,
    lyric: null,
    mood: null,
  });

  const stateRef = useRef(state);
  stateRef.current = state;

  // ---- 工具：按 deckId 拿对应 ref ----
  const getAudio = useCallback((d: DeckId) => (d === "A" ? audioARef.current : audioBRef.current), []);
  const getGain = useCallback((d: DeckId) => (d === "A" ? gainARef.current : gainBRef.current), []);
  const otherDeck = (d: DeckId): DeckId => (d === "A" ? "B" : "A");

  // ---- 懒创建整个 WebAudio 图（两路都接上） ----
  const ensureAnalyser = useCallback(() => {
    if (analyserRef.current) return;
    const aA = audioARef.current;
    const aB = audioBRef.current;
    if (!aA || !aB) return;
    try {
      const ACtx =
        window.AudioContext ??
        ((window as unknown as { webkitAudioContext?: typeof AudioContext })
          .webkitAudioContext);
      if (!ACtx) return;
      const ctx = new ACtx();
      const analyser = ctx.createAnalyser();
      analyser.fftSize = 512;
      analyser.smoothingTimeConstant = 0.72;
      analyser.connect(ctx.destination);

      // 每 deck 自己的 preAnalyser，安在 src 和 gain 中间。
      // 信号链：src -> preAnalyser -> gain -> mixAnalyser -> destination
      // preAnalyser 看到的是"这首本身的强度"，不被 gain ramp 影响 ——
      // 用来检测静音尾巴。
      const preA = ctx.createAnalyser();
      preA.fftSize = 512;
      preA.smoothingTimeConstant = 0.6;
      const preB = ctx.createAnalyser();
      preB.fftSize = 512;
      preB.smoothingTimeConstant = 0.6;

      // 每 deck 自己的 EQ duck low-shelf —— 默认 0dB（透明），crossfade 时被压
      const duckA = ctx.createBiquadFilter();
      duckA.type = "lowshelf";
      duckA.frequency.value = 600;
      duckA.gain.value = 0;
      const duckB = ctx.createBiquadFilter();
      duckB.type = "lowshelf";
      duckB.frequency.value = 600;
      duckB.gain.value = 0;

      const srcA = ctx.createMediaElementSource(aA);
      const gainA = ctx.createGain();
      gainA.gain.value = 1;
      srcA.connect(preA).connect(gainA).connect(duckA).connect(analyser);

      const srcB = ctx.createMediaElementSource(aB);
      const gainB = ctx.createGain();
      gainB.gain.value = 0;
      srcB.connect(preB).connect(gainB).connect(duckB).connect(analyser);

      ctxRef.current = ctx;
      analyserRef.current = analyser;
      preAnalyserARef.current = preA;
      preAnalyserBRef.current = preB;
      srcARef.current = srcA;
      srcBRef.current = srcB;
      gainARef.current = gainA;
      gainBRef.current = gainB;
      duckFilterARef.current = duckA;
      duckFilterBRef.current = duckB;
      fadeCurvesRef.current = buildFadeCurves();

      // RAF 读全局 RMS 写到 __claudioAmp（DotField/Waveform 消费）+
      // 同时给两 deck 的 pre-analyser 累计静音帧计数器。
      const mixBuf: Uint8Array<ArrayBuffer> = new Uint8Array(analyser.fftSize);
      const preBufA: Uint8Array<ArrayBuffer> = new Uint8Array(preA.fftSize);
      const preBufB: Uint8Array<ArrayBuffer> = new Uint8Array(preB.fftSize);
      const rms = (a: AnalyserNode, buf: Uint8Array<ArrayBuffer>) => {
        a.getByteTimeDomainData(buf);
        let sumSq = 0;
        for (let i = 0; i < buf.length; i++) {
          const v = (buf[i] - 128) / 128;
          sumSq += v * v;
        }
        return Math.sqrt(sumSq / buf.length);
      };
      const tick = () => {
        const mixA = analyserRef.current;
        const a = preAnalyserARef.current;
        const b = preAnalyserBRef.current;
        if (!mixA || !a || !b) return;
        const mix = rms(mixA, mixBuf);
        const amp = Math.max(0.15, Math.min(0.95, 0.25 + mix * 3.2));
        (window as unknown as { __claudioAmp?: number }).__claudioAmp = amp;

        // 各 deck 静音帧累计 —— 只有"在播"的 deck 才记
        const audioA = audioARef.current;
        const audioB = audioBRef.current;
        if (audioA && !audioA.paused) {
          silentFramesARef.current = rms(a, preBufA) < SILENT_RMS_THRESHOLD
            ? silentFramesARef.current + 1
            : 0;
        } else {
          silentFramesARef.current = 0;
        }
        if (audioB && !audioB.paused) {
          silentFramesBRef.current = rms(b, preBufB) < SILENT_RMS_THRESHOLD
            ? silentFramesBRef.current + 1
            : 0;
        } else {
          silentFramesBRef.current = 0;
        }

        rafRef.current = requestAnimationFrame(tick);
      };
      rafRef.current = requestAnimationFrame(tick);
    } catch (e) {
      console.warn("[claudio] AnalyserNode 创建失败，波形会停在底噪", e);
    }
  }, []);

  // ---- crossfade：from active → to ----
  // opts.bGainDb：incoming 的 gain 调整（dB），用来 level match
  // opts.eqDuck：在 outgoing 上做一段 low-shelf -3dB，重叠期不糊
  // 如果 ctx 还没起来（未触发用户手势），就直接硬切 gain。
  const crossfade = useCallback(
    (
      toDeck: DeckId,
      durationS: number,
      opts?: { bGainDb?: number; eqDuck?: boolean },
    ) => {
      const ctx = ctxRef.current;
      const curves = fadeCurvesRef.current;
      const fromDeck = activeDeckRef.current;
      if (toDeck === fromDeck) return;

      const fromGain = getGain(fromDeck);
      const toGain = getGain(toDeck);
      const fromAudio = getAudio(fromDeck);
      const fromDuck = fromDeck === "A" ? duckFilterARef.current : duckFilterBRef.current;
      const toDuck = toDeck === "A" ? duckFilterARef.current : duckFilterBRef.current;

      const targetTo = Math.pow(10, (opts?.bGainDb ?? 0) / 20);
      const eqDuck = opts?.eqDuck ?? false;

      if (!ctx || !curves || !fromGain || !toGain) {
        // 兜底：没 WebAudio 就硬切
        if (fromGain) fromGain.gain.value = 0;
        if (toGain) toGain.gain.value = targetTo;
        if (fromAudio) try { fromAudio.pause(); } catch {}
        activeDeckRef.current = toDeck;
        return;
      }

      const t = ctx.currentTime;
      const dur = Math.max(0.05, durationS);

      console.debug(
        `[claudio mix] crossfade ${fromDeck}→${toDeck} dur=${dur.toFixed(2)}s ` +
          `bGainDb=${(opts?.bGainDb ?? 0).toFixed(1)} eqDuck=${eqDuck} ` +
          `targetTo=${targetTo.toFixed(2)}`,
      );

      // 缩放 fade 曲线：fade-out 从当前值降到 0；fade-in 从 0 升到 targetTo
      const fromCur = fromGain.gain.value;
      const fadeOutScaled = new Float32Array(curves.fadeOut.length);
      for (let i = 0; i < curves.fadeOut.length; i++) {
        fadeOutScaled[i] = curves.fadeOut[i] * fromCur;
      }
      const fadeInScaled = new Float32Array(curves.fadeIn.length);
      for (let i = 0; i < curves.fadeIn.length; i++) {
        fadeInScaled[i] = curves.fadeIn[i] * targetTo;
      }

      try {
        fromGain.gain.cancelScheduledValues(t);
        toGain.gain.cancelScheduledValues(t);
        // 起点夯住当前值，避免 setValueCurveAtTime 从历史插值跳变
        fromGain.gain.setValueAtTime(fromGain.gain.value, t);
        toGain.gain.setValueAtTime(toGain.gain.value, t);
        fromGain.gain.setValueCurveAtTime(fadeOutScaled, t, dur);
        toGain.gain.setValueCurveAtTime(fadeInScaled, t, dur);

        // EQ duck：双向低频避让 —— 减少重叠期 bass/kick 互相堆叠，
        // 防止"轰头感"和低频糊。
        //   outgoing：现在饱满，先压 -4dB，重叠尾部回到 0
        //   incoming：先 -4dB 进来，重叠后段（80% 时）回到正常
        // 净效果：重叠中段两边低频都偏弱，新歌的低频是"逐渐浮现"，
        // 不是一上来就和老歌的 kick 撞在一起。
        if (eqDuck) {
          if (fromDuck) {
            fromDuck.gain.cancelScheduledValues(t);
            fromDuck.gain.setValueAtTime(fromDuck.gain.value, t);
            fromDuck.gain.linearRampToValueAtTime(-4, t + dur * 0.45);
            fromDuck.gain.linearRampToValueAtTime(0, t + dur);
          }
          if (toDuck) {
            toDuck.gain.cancelScheduledValues(t);
            toDuck.gain.setValueAtTime(-4, t);
            toDuck.gain.linearRampToValueAtTime(-4, t + dur * 0.2);
            toDuck.gain.linearRampToValueAtTime(0, t + dur * 0.8);
          }
        }
      } catch (e) {
        // 极少数情况曲线时间太短被抛 InvalidState；回退硬切
        console.debug("[claudio] gain ramp 失败，回退硬切", e);
        fromGain.gain.value = 0;
        toGain.gain.value = targetTo;
      }

      activeDeckRef.current = toDeck;

      // fade-out 完成后暂停旧 deck，省 CPU + 不浪费带宽
      window.setTimeout(() => {
        const a = getAudio(fromDeck);
        if (!a) return;
        // 只有当还是"非 active"才 pause —— 防止用户在 crossfade 中快速切回
        if (activeDeckRef.current !== fromDeck) {
          try { a.pause(); } catch {}
        }
      }, dur * 1000 + 80);

      // bGainDb 只是 level match 用的"转场期临时增益"，不应该让下一首
      // 整首都比标准音量低（或高）。crossfade 结束后把 incoming gain
      // 平滑拉回 1，避免持续影响。dur*1000+120 留点余量给 setValueCurveAtTime
      // 真正落到 targetTo。
      if (Math.abs(targetTo - 1) > 0.01) {
        window.setTimeout(() => {
          const ctxNow = ctxRef.current;
          const g = getGain(toDeck);
          // 用户已经又切走了：当前 active 不是这个 deck，就别动它
          if (!ctxNow || !g || activeDeckRef.current !== toDeck) return;
          try {
            const t2 = ctxNow.currentTime;
            g.gain.cancelScheduledValues(t2);
            g.gain.setValueAtTime(g.gain.value, t2);
            g.gain.linearRampToValueAtTime(1, t2 + 2.5);
          } catch (e) {
            console.debug("[claudio] bGainDb 恢复失败，忽略", e);
          }
        }, dur * 1000 + 120);
      }
    },
    [getAudio, getGain],
  );

  // 拉直链（带短期复用空间，不缓存 —— 直链 6h 过期，存到全局没意义）
  const fetchUrl = useCallback(async (neteaseId: number): Promise<string | null> => {
    const urls = await netease.songUrls([neteaseId]);
    const url = urls[0]?.url;
    return url ? cdn(url) : null;
  }, []);

  // ---- 在指定 deck 上预加载曲目（不开始播放，gain 保持 0） ----
  // seamless 的关键：
  //   1) audio.preload = "auto" 让浏览器从 src 一上去就完整流入文件
  //   2) 等到 `canplay` 而不只是 `loadedmetadata` —— canplay 表示已经
  //      buffer 够够立刻无延迟开播。loadedmetadata 只够拿 duration，
  //      crossfade 时仍可能 stall 一下。
  //   3) 修剪静音前奏：扫前 3s 看 RMS，如果开头是静音就 seek 过去。
  //      这样 crossfade 落点是"真有音乐"而不是空气。
  // 同时并行触发 AI 过歌判断（不阻塞预加载）。
  const preloadOnDeck = useCallback(
    async (deck: DeckId, track: Track, fromTrack: Track | null): Promise<boolean> => {
      const audio = getAudio(deck);
      const gain = getGain(deck);
      if (!audio || !track.neteaseId) return false;
      const preloadGeneration = preloadGenerationRef.current;
      const pairKey =
        fromTrack?.neteaseId && fromTrack.neteaseId !== track.neteaseId
          ? transitionPairKey(fromTrack.neteaseId, track.neteaseId)
          : null;

      if (
        fromTrack &&
        fromTrack.neteaseId &&
        fromTrack.neteaseId !== track.neteaseId
      ) {
        const fromId = fromTrack.neteaseId;
        pendingPairKeyRef.current = pairKey;
        pendingJudgmentRef.current = null;
        pendingMixPlanRef.current = null;
        // 先读 A 的 analysis（cache 命中即返回，~ms 级），把它喂给 AI
        // judge —— AI 不再只看歌名瞎猜 BPM/能量。
        // B 的 analysis 还在背景跑，这里先 null，等 fetchUrl 之后才补回。
        loadAnalysis(fromId)
          .then(async (aAnalysis) => {
            if (
              preloadGenerationRef.current !== preloadGeneration ||
              pendingPairKeyRef.current !== pairKey
            ) {
              return;
            }
            const judgment = await judgeTransition(fromTrack, track, {
              from: aAnalysis,
            }).catch(() => null);
            if (
              preloadGenerationRef.current !== preloadGeneration ||
              pendingPairKeyRef.current !== pairKey
            ) {
              return;
            }
            pendingJudgmentRef.current = judgment ?? null;
            const plan = planMix(
              aAnalysis,
              null,
              judgment ?? DEFAULT_JUDGMENT,
            );
            pendingMixPlanRef.current = plan;
            debugMixPlan(fromTrack, track, plan);
          })
          .catch(() => {});
      }

      try {
        const url = await fetchUrl(track.neteaseId);
        if (!url) return false;

        // B 的 analysis 在背景跑；完成后回填 mix plan（带上 A 的真分析）
        const fromIdForPlan = fromTrack?.neteaseId;
        void getOrAnalyze(track.neteaseId, url).then(async (bAnalysis) => {
          if (!bAnalysis || !fromIdForPlan) return;
          const aAnalysis = await loadAnalysis(fromIdForPlan);
          if (
            preloadGenerationRef.current !== preloadGeneration ||
            pendingPairKeyRef.current !== pairKey
          ) {
            return;
          }
          const judgment = pendingJudgmentRef.current ?? DEFAULT_JUDGMENT;
          const plan = planMix(aAnalysis, bAnalysis, judgment);
          pendingMixPlanRef.current = plan;
          debugMixPlan(fromTrack, track, plan);
        });

        if (gain) {
          try { gain.gain.cancelScheduledValues(0); } catch {}
          gain.gain.value = 0;
        }
        audio.preload = "auto";
        audio.src = url;
        audio.load();
        // 标记这 deck 现在装的曲目 id，让 timeupdate 知道"我是不是当前歌"
        deckTrackIdRef.current[deck] = track.neteaseId;

        // 等 canplay 而不是 loadedmetadata —— canplay 才意味着
        // 浏览器认为可以"立刻无延迟开播"。
        await new Promise<void>((resolve) => {
          const done = () => {
            audio.removeEventListener("canplay", done);
            audio.removeEventListener("error", done);
            resolve();
          };
          audio.addEventListener("canplay", done);
          audio.addEventListener("error", done);
          setTimeout(done, 9000); // 弱网兜底
        });

        // 静音前奏修剪：在 gain=0 下 play 一小段，监测 pre-analyser，
        // 找到第一个 RMS > 阈值的时间点，停下来 seek 过去再 pause。
        await trimSilentIntro(audio, deck);

        if (
          preloadGenerationRef.current !== preloadGeneration ||
          (fromTrack?.neteaseId && stateRef.current.current?.neteaseId !== fromTrack.neteaseId)
        ) {
          return false;
        }
        preloadedNeteaseIdRef.current = track.neteaseId;
        preloadedDeckRef.current = deck;
        return true;
      } catch (e) {
        console.debug("[claudio] preload 失败", e);
        return false;
      }
    },
    [getAudio, getGain, fetchUrl],
  );

  // 扫描前奏静音：在 gain=0 下偷偷过一遍前 INTRO_TRIM_MAX_S 秒，
  // 监测 deck 自己的 preAnalyser，找到第一个非静音点，seek 到那里再 pause。
  // 用 ctx.currentTime 代替 setTimeout，避免主线程抖动影响精度。
  const trimSilentIntro = useCallback(
    async (audio: HTMLAudioElement, deck: DeckId) => {
      const pre = deck === "A" ? preAnalyserARef.current : preAnalyserBRef.current;
      if (!pre) return;
      try {
        audio.currentTime = 0;
        await audio.play();
        const buf = new Uint8Array(pre.fftSize) as Uint8Array<ArrayBuffer>;
        const startedAt = performance.now();
        let cutAt = 0;
        while (performance.now() - startedAt < INTRO_TRIM_MAX_S * 1000) {
          pre.getByteTimeDomainData(buf);
          let sumSq = 0;
          for (let i = 0; i < buf.length; i++) {
            const v = (buf[i] - 128) / 128;
            sumSq += v * v;
          }
          const r = Math.sqrt(sumSq / buf.length);
          if (r > INTRO_RMS_THRESHOLD) {
            cutAt = audio.currentTime;
            break;
          }
          await new Promise((res) => requestAnimationFrame(res));
        }
        audio.pause();
        // 静音段稍微回退 80ms 以保住第一个微弱 attack
        const safeStart = Math.max(0, cutAt - 0.08);
        try { audio.currentTime = safeStart; } catch {}
      } catch (e) {
        console.debug("[claudio] intro trim 失败，忽略", e);
        try { audio.pause(); } catch {}
      }
    },
    [],
  );

  // ---- 核心播放管线 ----
  //
  // playTrack 负责：
  //   1) 把 Track 安到 state.current（乐观 isPlaying=true）
  //   2) 决定 deck —— 如果 inactive 上有匹配的预加载就直接复用，省一次拉链
  //   3) 否则在 inactive 上拉直链 + load
  //   4) crossfade 到 inactive
  //   5) 后台 fetch 歌词 + DJ 旁白（不阻塞）
  const playTrack = useCallback(
    async (
      track: Track,
      opts?: {
        resumeFrom?: number;
        manualCut?: boolean;
        /** 自动 crossfade 路径里调方拿好的 AI 判断；不传就走默认 4s soft */
        judgment?: TransitionJudgment;
        /** 自动 crossfade 路径里 mix-planner 算好的接歌计划 */
        mixPlan?: MixPlan;
      },
    ) => {
      if (!track.neteaseId) {
        setState((s) => ({ ...s, error: "曲目没有网易云 id，无法播放" }));
        return;
      }
      const neteaseId = track.neteaseId;
      const resumeFrom = opts?.resumeFrom ?? 0;
      const manual = opts?.manualCut !== false; // 默认手动剪
      // 任何 playTrack 调用都意味着"用户/系统期望在听" —— 后台→前台续播认这个
      userIntendedPlayingRef.current = true;
      autoTransitionHoldUntilRef.current = Date.now() + 1000;

      // 切歌前记一笔旧曲目的行为日志（除非是同一首循环 / 冷启动恢复）
      const prior = stateRef.current.current;
      const priorAudio = getAudio(activeDeckRef.current);
      if (
        prior &&
        prior.neteaseId &&
        prior.neteaseId !== neteaseId &&
        priorAudio &&
        priorAudio.src
      ) {
        const pos = priorAudio.currentTime || 0;
        const dur = Number.isFinite(priorAudio.duration) ? priorAudio.duration : prior.durationSec;
        const ratio = dur > 0 ? pos / dur : 0;
        let kind: "completed" | "skipped" | "manual_cut";
        if (!manual) {
          kind = "completed";
        } else if (ratio < 0.3) {
          kind = "skipped";
        } else {
          kind = "manual_cut";
        }
        void logBehavior({
          trackId: prior.neteaseId,
          title: prior.title,
          artist: prior.artist,
          kind,
          positionSec: pos,
          durationSec: dur,
        });
      }

      ensureAnalyser();
      const ctx = ctxRef.current;
      if (ctx?.state === "suspended") {
        try { await ctx.resume(); } catch {}
      }

      const fromDeck = activeDeckRef.current;
      const toDeck = otherDeck(fromDeck);
      const toAudio = getAudio(toDeck);
      const toGain = getGain(toDeck);
      if (!toAudio) return;

      // 起歌词请求（不阻塞播放）
      const lyricPromise = (async () => {
        try {
          const cached = await cache.getLyric(neteaseId);
          if (cached) return cached;
        } catch (e) {
          console.warn("[claudio] cache.getLyric 失败，降级走网络", e);
        }
        try {
          const fresh = await netease.songLyric(neteaseId);
          cache.saveLyric(neteaseId, fresh).catch(() => {});
          return fresh;
        } catch (e) {
          console.warn("[claudio] 歌词拉取失败，静默降级", e);
          return null;
        }
      })();
      const failedLyric = { lyric: "", instrumental: false, uncollected: true };

      try {
        // 命中预加载就直接用（省一次 url 拉取 + buffer 时间）
        const preloaded =
          preloadedNeteaseIdRef.current === neteaseId &&
          preloadedDeckRef.current === toDeck &&
          deckTrackIdRef.current[toDeck] === neteaseId &&
          mediaHasPlayableSource(toAudio);
        if (!preloaded) {
          preloadedNeteaseIdRef.current = null;
          preloadedDeckRef.current = null;
          const url = await fetchUrl(neteaseId);
          if (!url) {
            autoCrossfadeKickedRef.current = false;
            setState((s) => ({
              ...s,
              error: "这首歌拿不到直链（可能需要 VIP 或已下架）",
              isPlaying: s.current?.neteaseId === neteaseId ? false : s.isPlaying,
              aiStatus: s.current?.neteaseId === neteaseId ? "—" : s.aiStatus,
            }));
            return;
          }
          // 起这首的 analysis（背景跑，不阻塞）—— 下次接歌时 mix plan 能用
          void getOrAnalyze(neteaseId, url);

          if (toGain) {
            try { toGain.gain.cancelScheduledValues(0); } catch {}
            toGain.gain.value = 0;
          }
          toAudio.src = url;
          toAudio.load();
          // 重置 currentTime —— 浏览器 src 重置理应自动归零，但有些 codec
          // 需要显式触发；不显式置一下偶尔会保留旧 deck 的播放位置。
          try { toAudio.currentTime = 0; } catch {}
        }
        // 标记这 deck 现在装的是哪一首 —— gate 旧 deck 的 timeupdate
        deckTrackIdRef.current[toDeck] = neteaseId;

        // resumeFrom seek
        if (resumeFrom > 0) {
          const onMeta = () => {
            try { toAudio.currentTime = resumeFrom; } catch {}
            toAudio.removeEventListener("loadedmetadata", onMeta);
          };
          toAudio.addEventListener("loadedmetadata", onMeta);
        }

        // mix plan 给的 inSeekS：B 引导太长时跳过去一点
        const inSeekS = opts?.mixPlan?.inSeekS ?? 0;
        if (inSeekS > 0 && resumeFrom <= 0) {
          try {
            if (toAudio.currentTime < inSeekS) toAudio.currentTime = inSeekS;
          } catch {}
        }

        await toAudio.play();
        if (deckTrackIdRef.current[toDeck] !== neteaseId) {
          try { toAudio.pause(); } catch {}
          throw new Error("目标 deck 曲目校验失败，已取消本次切歌");
        }

        setState((s) => ({
          ...s,
          current: track,
          positionSec: resumeFrom,
          isPlaying: true,
          error: null,
          aiStatus: "正在取直链…",
          lyric: null,
        }));
        // 切歌 → 重置 lastKnownPos 到新曲目的起点 ——
        // 否则下面的"反向跳变检测"会以为新曲目从 0 开始是异常
        lastKnownPositionRef.current = resumeFrom;
        // 切歌也算一次有意 seek，避免新 deck 的 onTime 误判
        userSeekedRef.current = true;
        setTimeout(() => { userSeekedRef.current = false; }, 500);
        cache.setState(LAST_TRACK_KEY, JSON.stringify(track)).catch(() => {});
        cache.setState(LAST_POSITION_KEY, "0").catch(() => {});

        // 第一首歌（fromDeck 没在播）走 cold fade-in，避免突然全音量
        const fromAudio = getAudio(fromDeck);
        const isCold = !fromAudio?.src || fromAudio.paused;
        if (isCold) {
          crossfade(toDeck, COLD_FADEIN_S);
        } else if (manual) {
          crossfade(toDeck, MANUAL_CROSSFADE_S);
        } else {
          // 自动 crossfade：优先用 mix plan，没有就用 AI judgment 兜底
          const plan = opts?.mixPlan ?? pendingMixPlanRef.current;
          if (plan) {
            // mode 派发：hard_cut 走 80ms 极短淡化（保留尾音收束 + 防爆音），
            // 其它都按 plan.durationS 走（已经经过 mix-planner 的所有限幅）。
            const HARD_CUT_DUR = 0.08;
            const autoDurationS =
              plan.mode === "hard_cut"
                ? HARD_CUT_DUR
                : Math.max(0.6, plan.durationS);
            console.debug(
              `[claudio mix-plan] from=${prior?.title ?? "—"} to=${track.title} ` +
                `mode=${plan.mode} dur=${autoDurationS.toFixed(2)}s ` +
                `bGainDb=${plan.bGainDb.toFixed(1)} eqDuck=${plan.eqDuck} ` +
                `bpmAligned=${plan.bpmAligned} reason="${plan.reason}"`,
            );
            crossfade(toDeck, autoDurationS, {
              bGainDb: plan.bGainDb,
              eqDuck: plan.eqDuck,
            });
          } else {
            const j = opts?.judgment ?? pendingJudgmentRef.current ?? DEFAULT_JUDGMENT;
            crossfade(toDeck, clamp(j.durationMs / 1000, 1.4, 2.8), {
              eqDuck: j.eqDuck,
            });
          }
        }

        // 切完了：清掉预加载标记，重置 auto-crossfade trigger
        preloadedNeteaseIdRef.current = null;
        preloadedDeckRef.current = null;
        preloadingNeteaseIdRef.current = null;
        autoCrossfadeKickedRef.current = false;
        pendingMixPlanRef.current = null;
        pendingJudgmentRef.current = null;
        pendingPairKeyRef.current = null;

        // DJ 旁白：先查缓存，命中直接用；miss 再调 AI 一次性写好缓存。
        // 同一首歌反复播放只调一次 AI，省成本 + 弱网下不会卡在"写旁白…"。
        const djKey = `dj_intro:v1:${neteaseId}`;
        const cachedDjLine = await cache.getState(djKey).catch(() => null);
        if (cachedDjLine) {
          setState((s) => {
            if (s.current?.neteaseId !== neteaseId) return s;
            return { ...s, isPlaying: true, aiStatus: cachedDjLine };
          });
        } else {
          setState((s) => ({ ...s, isPlaying: true, aiStatus: "正在写旁白…" }));
          ai
            .chat({
              system:
                "你是 Claudio 的深夜电台 DJ。说话干净克制，不煽情，不用感叹号，不加引号。",
              user: `为《${track.title}》— ${track.artist} 写一句 12 到 20 字的开场白，像刚按下导播键那种克制的旁白。只输出这一句话，别加其他东西。`,
              temperature: 0.85,
              maxTokens: 80,
            })
            .then((line) => {
              const clean = line
                .trim()
                .replace(/^[「『"'《]+|[」』"'》]+$/g, "")
                .replace(/[。!！]+$/g, "")
                .slice(0, 40);
              if (!clean) return;
              cache.setState(djKey, clean).catch(() => {});
              setState((s) => {
                if (s.current?.neteaseId !== neteaseId) return s;
                return { ...s, aiStatus: clean };
              });
            })
            .catch((e) => {
              console.debug("[claudio] DJ 旁白失败，静默", e);
              setState((s) => {
                if (s.current?.neteaseId !== neteaseId) return s;
                if (s.aiStatus !== "正在写旁白…") return s;
                return { ...s, aiStatus: "播放中" };
              });
            });
        }
      } catch (e) {
        const isAbort =
          (e instanceof DOMException && e.name === "AbortError") ||
          (e instanceof Error && /aborted|interrupted by a call to pause/i.test(e.message));
        if (isAbort) {
          console.debug("[claudio] 旧 play() 被切歌打断，静默", e);
          return;
        }
        autoCrossfadeKickedRef.current = false;
        setState((s) => {
          if (s.current?.neteaseId !== neteaseId) return s;
          return {
            ...s,
            error: e instanceof Error ? e.message : String(e),
            isPlaying: false,
            aiStatus: "—",
          };
        });
        return;
      }

      // 歌词 settle
      const data = (await lyricPromise) ?? failedLyric;
      setState((s) => {
        if (s.current?.neteaseId !== neteaseId) return s;
        return {
          ...s,
          lyric: {
            lines: parseLrc(data.lyric),
            instrumental: data.instrumental,
            uncollected: data.uncollected,
          },
        };
      });
    },
    [ensureAnalyser, getAudio, getGain, crossfade, fetchUrl],
  );

  const playNetease = useCallback(
    async (
      t: TrackInfo,
      contextQueue?: TrackInfo[],
      opts?: PlayNeteaseOptions,
    ) => {
      if (contextQueue) {
        preloadGenerationRef.current++;
        preloadedNeteaseIdRef.current = null;
        preloadedDeckRef.current = null;
        preloadingNeteaseIdRef.current = null;
        pendingJudgmentRef.current = null;
        pendingMixPlanRef.current = null;
        pendingPairKeyRef.current = null;

        // 默认对队列做一次平滑重排（user 点的 t 永远是起点）。
        // smoothQueue 在分析覆盖不足或 ≤2 首时会原样返回，安全。
        let queueSource = contextQueue;
        if (opts?.smooth !== false && contextQueue.length > 2) {
          try {
            queueSource = await smoothQueue(contextQueue, {
              startTrackId: t.id,
              mode: opts?.smoothMode ?? "library",
            });
          } catch (e) {
            console.debug("[claudio] smoothQueue 失败，回退原顺序", e);
          }
        }

        const q = queueSource.map(neteaseToTrack);
        setState((s) => ({ ...s, queue: q }));
        cache.setState(LAST_QUEUE_KEY, JSON.stringify(q)).catch(() => {});
      }
      await playTrack(neteaseToTrack(t));
    },
    [playTrack],
  );

  const tryAutoTransition = useCallback(
    (deck: DeckId, audio: HTMLAudioElement) => {
      if (!userIntendedPlayingRef.current) return;
      if (Date.now() < autoTransitionHoldUntilRef.current) return;

      const { queue, current } = stateRef.current;
      if (!current || queue.length <= 1) return;

      const i = queue.findIndex((t) => t.id === current.id);
      const nextTrack = i >= 0 ? queue[(i + 1) % queue.length] ?? null : null;
      if (!nextTrack?.neteaseId || autoCrossfadeKickedRef.current) return;

      const mediaDuration = audio.duration;
      const duration =
        Number.isFinite(mediaDuration) && mediaDuration > 10
          ? mediaDuration
          : current.durationSec;
      if (!Number.isFinite(duration) || duration <= 10) return;

      const remain = duration - audio.currentTime;
      if (!Number.isFinite(remain) || remain <= 0) return;

      const judgment = pendingJudgmentRef.current ?? DEFAULT_JUDGMENT;
      const mixPlan = pendingMixPlanRef.current;
      const plannedTriggerSec = mixPlan
        ? mixPlan.triggerBeforeEndS
        : judgment.durationMs / 1000;
      // hard_cut 专项：不要用 max(0.3, x+0.15) 的全局下限，否则同专辑无缝接
      // 会被提前 0.4s 触发，截掉 A 的尾音收束。hard_cut 应该贴近曲尾。
      const isHardCut = mixPlan?.mode === "hard_cut";
      let triggerAt: number;
      if (isHardCut) {
        // 留 60ms 给 timeupdate 抖动 + crossfade 的 80ms 短淡化启动
        triggerAt = 0.06;
      } else {
        const triggerSec = mixPlan
          ? Math.max(0.25, plannedTriggerSec)
          : clamp(plannedTriggerSec > 0 ? plannedTriggerSec : 2.2, 1.4, 2.8);
        triggerAt = Math.max(0.3, triggerSec + 0.15);
      }

      if (
        preloadedNeteaseIdRef.current !== nextTrack.neteaseId &&
        preloadingNeteaseIdRef.current !== nextTrack.neteaseId &&
        remain < PRELOAD_LEAD_S &&
        remain > triggerAt + 0.5
      ) {
        const inactive = otherDeck(deck);
        preloadingNeteaseIdRef.current = nextTrack.neteaseId;
        void preloadOnDeck(inactive, nextTrack, current).finally(() => {
          if (preloadingNeteaseIdRef.current === nextTrack.neteaseId) {
            preloadingNeteaseIdRef.current = null;
          }
        });
      }

      // 静音尾巴本轮先禁用：真实歌曲的安静桥段太容易被误判成曲尾。
      // 仍保留 silentFrames/progressed 到日志里，方便之后做更安全的 94%+ 尾静音策略。
      const silentFrames = deck === "A" ? silentFramesARef.current : silentFramesBRef.current;
      const progressed = audio.currentTime / duration;
      const silentTail = false;

      if (remain <= triggerAt && remain > 0.05) {
        autoCrossfadeKickedRef.current = true;
        console.debug("[auto-transition]", {
          from: current.title,
          to: nextTrack.title,
          remain,
          triggerAt,
          durationS: mixPlan?.durationS,
          type: mixPlan?.mode,
          reason: mixPlan?.reason,
          progressed,
          silentFrames,
          silentTail,
          judgment: `${judgment.style}/${judgment.durationMs}ms`,
        });
        void playTrack(nextTrack, {
          manualCut: false,
          judgment,
          mixPlan: mixPlan ?? undefined,
        });
      }
    },
    [playTrack, preloadOnDeck],
  );

  // ---- pause / resume / toggle / seek 都对 active deck 操作 ----
  const pause = useCallback(() => {
    const a = getAudio(activeDeckRef.current);
    a?.pause();
    userIntendedPlayingRef.current = false;
    autoTransitionHoldUntilRef.current = Number.POSITIVE_INFINITY;
    preloadGenerationRef.current++;
    preloadedNeteaseIdRef.current = null;
    preloadedDeckRef.current = null;
    preloadingNeteaseIdRef.current = null;
    pendingJudgmentRef.current = null;
    pendingMixPlanRef.current = null;
    pendingPairKeyRef.current = null;
  }, [getAudio]);

  const resume = useCallback(async () => {
    const a = getAudio(activeDeckRef.current);
    if (!a || !a.src) return;
    try {
      userIntendedPlayingRef.current = true;
      // 长暂停后 WebView 可能刚恢复时 currentTime/duration 不稳定。
      // 给它一个短暂稳定期，避免 watchdog 一恢复就连环触发自动切歌。
      autoTransitionHoldUntilRef.current = Date.now() + 3000;
      if (ctxRef.current?.state === "suspended") {
        await ctxRef.current.resume();
      }
      await a.play();
    } catch (e) {
      userIntendedPlayingRef.current = false;
      console.warn("[claudio] resume 失败", e);
    }
  }, [getAudio]);

  const toggle = useCallback(() => {
    const a = getAudio(activeDeckRef.current);
    if (!a) return;
    if (a.paused) {
      if (a.src) {
        void resume();
        return;
      }
      const cur = stateRef.current.current;
      if (cur) {
        autoTransitionHoldUntilRef.current = Date.now() + 3000;
        void playTrack(cur, { resumeFrom: stateRef.current.positionSec });
      }
    } else {
      pause();
    }
  }, [getAudio, pause, resume, playTrack]);

  const seek = useCallback((sec: number) => {
    const a = getAudio(activeDeckRef.current);
    if (!a) return;
    userSeekedRef.current = true;
    // 短暂保留标记 —— 让接下来一两次 timeupdate 跳过反向跳变检测，
    // 避免用户 seek 回前面被误判成 OS 重置
    setTimeout(() => { userSeekedRef.current = false; }, 200);
    a.currentTime = Math.max(0, sec);
  }, [getAudio]);

  // ---- next / prev：走 queue（用户选中的歌单） ----
  const next = useCallback(() => {
    const { current, queue } = stateRef.current;
    if (!current) return;
    if (queue.length === 0) {
      void playTrack(current, { manualCut: true });
      return;
    }
    const i = queue.findIndex((t) => t.id === current.id);
    const nxt = queue[(i + 1) % queue.length];
    void playTrack(nxt ?? current, { manualCut: true });
  }, [playTrack]);

  const prev = useCallback(() => {
    const { current, queue } = stateRef.current;
    if (!current) return;
    if (queue.length === 0) {
      void playTrack(current, { manualCut: true });
      return;
    }
    const i = queue.findIndex((t) => t.id === current.id);
    const p = queue[(i - 1 + queue.length) % queue.length];
    void playTrack(p ?? current, { manualCut: true });
  }, [playTrack]);

  const setAiStatus = useCallback((aiStatus: string) => {
    setState((s) => ({ ...s, aiStatus }));
  }, []);

  const setMood = useCallback((m: string | null) => {
    setState((s) => ({ ...s, mood: m }));
  }, []);

  const like = useCallback(() => {
    const cur = stateRef.current.current;
    if (!cur || !cur.neteaseId) return;
    const a = getAudio(activeDeckRef.current);
    void logBehavior({
      trackId: cur.neteaseId,
      title: cur.title,
      artist: cur.artist,
      kind: "liked",
      positionSec: a?.currentTime ?? 0,
      durationSec: a?.duration ?? cur.durationSec,
    });
    setState((s) => ({ ...s, aiStatus: "记住了，你喜欢这首" }));
  }, [getAudio]);

  const dislike = useCallback(() => {
    const cur = stateRef.current.current;
    if (!cur || !cur.neteaseId) return;
    const a = getAudio(activeDeckRef.current);
    void logBehavior({
      trackId: cur.neteaseId,
      title: cur.title,
      artist: cur.artist,
      kind: "disliked",
      positionSec: a?.currentTime ?? 0,
      durationSec: a?.duration ?? cur.durationSec,
    });
    next();
  }, [getAudio, next]);

  // ---- 上次播放状态恢复 ----
  const lastPosWriteRef = useRef<number>(0);
  useEffect(() => {
    let alive = true;
    (async () => {
      try {
        const [trackJson, posStr, queueJson] = await Promise.all([
          cache.getState(LAST_TRACK_KEY),
          cache.getState(LAST_POSITION_KEY),
          cache.getState(LAST_QUEUE_KEY),
        ]);
        if (!alive) return;
        if (!trackJson) return;
        const track = JSON.parse(trackJson) as Track;
        const pos = posStr ? Number(posStr) : 0;
        let queue: Track[] = [];
        if (queueJson) {
          try {
            const parsed = JSON.parse(queueJson);
            if (Array.isArray(parsed)) queue = parsed as Track[];
          } catch (e) {
            console.debug("[claudio] last_queue 解析失败，留空", e);
          }
        }
        setState((s) => ({
          ...s,
          current: track,
          queue,
          positionSec: Number.isFinite(pos) ? pos : 0,
          aiStatus: "已从上次回来 —— 按 ▶ 继续",
        }));
      } catch (e) {
        console.debug("[claudio] 跳过上次播放恢复", e);
      }
    })();
    return () => {
      alive = false;
    };
  }, []);

  // ---- audio 事件 → state ----
  // 两路 deck 都注册同一组监听，但只有 active deck 的事件会更新 state。
  // 这样 inactive deck 在 crossfade 期间触发的 timeupdate / pause 不会污染 UI。
  useEffect(() => {
    const audioA = audioARef.current;
    const audioB = audioBRef.current;
    if (!audioA || !audioB) return;

    const handlersFor = (deck: DeckId) => {
      const audio = deck === "A" ? audioA : audioB;

      const isActive = () => activeDeckRef.current === deck;

      const onTime = () => {
        if (!isActive()) return;
        const myTrackId = deckTrackIdRef.current[deck];
        const curTrackId = stateRef.current.current?.neteaseId ?? null;
        if (myTrackId !== curTrackId) return;

        const now = audio.currentTime;
        const last = lastKnownPositionRef.current;

        // 反向跳变检测：用户没主动 seek、还在听、但 currentTime 突然
        // 回退超过 1.5s ——  WKWebView 内部把音频 reset 回 0 了。
        // 当场 seek 回最后已知位置，状态也不更新，跟没发生过一样。
        if (
          userIntendedPlayingRef.current &&
          !userSeekedRef.current &&
          last > 2 &&
          now < last - 1.5
        ) {
          try { audio.currentTime = last; } catch {}
          return;
        }

        if (Number.isFinite(now)) {
          lastKnownPositionRef.current = now;
        }
        setState((s) =>
          s.positionSec === now ? s : { ...s, positionSec: now },
        );
        const tNow = Date.now();
        if (tNow - lastPosWriteRef.current > 2000) {
          lastPosWriteRef.current = tNow;
          cache.setState(LAST_POSITION_KEY, String(audio.currentTime)).catch(() => {});
          // 顺手刷一次系统 Now Playing 进度条
          if (typeof navigator !== "undefined" && "mediaSession" in navigator) {
            const dur = Number.isFinite(audio.duration)
              ? audio.duration
              : stateRef.current.current?.durationSec ?? 0;
            if (dur > 0) {
              try {
                navigator.mediaSession.setPositionState?.({
                  duration: dur,
                  position: Math.min(audio.currentTime, dur),
                  playbackRate: 1,
                });
              } catch {}
            }
          }
        }

        // ---- 自动 crossfade 触发：临近曲尾 ----
        tryAutoTransition(deck, audio);
      };

      const onPlay = () => {
        if (!isActive()) return;
        setState((s) => ({ ...s, isPlaying: true }));
      };
      const onPause = () => {
        if (!isActive()) return;

        // 核心设计：暂停 / 播放只应该由用户控制。
        // 如果用户的意图还是"在听"（userIntendedPlayingRef === true），
        // 但 audio 被 OS / WKWebView 偷偷 pause 了 —— 我们当没发生过，
        // 立刻 play() 拉回去，state.isPlaying 也不更新。
        // 只有用户真正点了 pause（intent 变 false）这条事件才被认。
        if (userIntendedPlayingRef.current && audio.src) {
          // 万一 OS 顺手把 currentTime 重置回 0 —— seek 回最后已知位置
          if (audio.currentTime < 0.5 && lastKnownPositionRef.current > 1.5) {
            try { audio.currentTime = lastKnownPositionRef.current; } catch {}
          }
          audio.play().catch((e) => {
            console.debug("[claudio] OS-induced pause 自动续播失败", e);
          });
          return;
        }

        // 用户真的暂停了：写位置 + 同步 state
        setState((s) => ({ ...s, isPlaying: false }));
        (window as unknown as { __claudioAmp?: number }).__claudioAmp = 0;
        if (audio.src) {
          cache.setState(LAST_POSITION_KEY, String(audio.currentTime)).catch(() => {});
        }
      };
      const onEnded = () => {
        if (!isActive()) return;
        // 自动 crossfade 应该已经在 4s 前触发了；走到 ended 说明：
        //  - 队列只有 1 首循环（playTrack 里走 manual cut 重播）
        //  - 或者 crossfade 因为某种原因没 kick
        if (autoCrossfadeKickedRef.current) {
          // 已经在 crossfade 中，让它跑完
          return;
        }
        const { current, queue } = stateRef.current;
        if (current && queue.length > 1) {
          const i = queue.findIndex((t) => t.id === current.id);
          const nxt = queue[(i + 1) % queue.length];
          if (nxt) {
            void playTrack(nxt, { manualCut: false });
            return;
          }
        }
        setState((s) => ({ ...s, isPlaying: false, aiStatus: "播完了" }));
      };
      const onError = () => {
        if (!isActive()) return;
        setState((s) => ({
          ...s,
          isPlaying: false,
          error: "音频加载失败（可能链路被墙 / 直链过期）",
          aiStatus: "—",
        }));
      };

      audio.addEventListener("timeupdate", onTime);
      audio.addEventListener("play", onPlay);
      audio.addEventListener("pause", onPause);
      audio.addEventListener("ended", onEnded);
      audio.addEventListener("error", onError);

      return () => {
        audio.removeEventListener("timeupdate", onTime);
        audio.removeEventListener("play", onPlay);
        audio.removeEventListener("pause", onPause);
        audio.removeEventListener("ended", onEnded);
        audio.removeEventListener("error", onError);
      };
    };

    const cleanA = handlersFor("A");
    const cleanB = handlersFor("B");
    return () => {
      cleanA();
      cleanB();
    };
  }, [playTrack, tryAutoTransition]);

  // timeupdate 在某些 WebView / 流式音频上可能不够稳定；用轻量 watchdog
  // 补一层 250ms 检查，保证自动队列一定能在曲尾前触发 overlap。
  useEffect(() => {
    const timer = window.setInterval(() => {
      const deck = activeDeckRef.current;
      const audio = getAudio(deck);
      if (!audio || audio.paused || !audio.src) return;
      tryAutoTransition(deck, audio);
    }, 250);
    return () => window.clearInterval(timer);
  }, [getAudio, tryAutoTransition]);

  // ---- 前后台切换守门员 ----
  //
  // WKWebView 把窗口扔后台 / 失焦时会主动 suspend AudioContext + pause <audio>，
  // 前台/获焦时又会按它自己的逻辑乱触发，跟我们的状态对不上 → 你看到的
  // "音乐停了 / 进度变 0"。
  //
  // 对策三层（任一触发都能恢复）：
  //   A) 用户意图 ref：只跟"用户主动点过 play/pause"走，不被 OS pause 污染
  //   B) 最后已知位置 ref：onTime 持续更新，前台时直接拿来 seek
  //   C) 三种事件都听：visibilitychange + window focus + window pageshow
  //      因为 WKWebView 上 visibilitychange 对"Cmd-Tab 切走再回来"经常不报，
  //      只在 Cmd-H/最小化时报；focus 事件覆盖前者漏报的场景。
  const userIntendedPlayingRef = useRef<boolean>(false);
  const autoTransitionHoldUntilRef = useRef<number>(0);
  const lastKnownPositionRef = useRef<number>(0);
  // 用户主动 seek 的标记 —— 标记期间 onTime 跳过"反向跳变检测"，
  // 否则用户从 2:00 seek 回 0:30 会被误判成 OS 抽风
  const userSeekedRef = useRef<boolean>(false);

  const restoreFromBackground = useCallback(() => {
    const audio = getAudio(activeDeckRef.current);
    const ctx = ctxRef.current;
    if (ctx && ctx.state === "suspended") {
      ctx.resume().catch(() => {});
    }
    if (!audio) return;

    // 1) 位置被重置 → 拿最后已知位置 seek 回去
    const known = lastKnownPositionRef.current;
    if (known > 1.5 && audio.currentTime < 0.5) {
      try { audio.currentTime = known; } catch {}
    }
    // 2) 用户原本在听但被 OS 暂停 → 续播
    if (userIntendedPlayingRef.current && audio.paused && audio.src) {
      audio.play().catch((e) => {
        console.debug("[claudio] focus resume play 失败", e);
      });
    }
    // 3) 同步 state 到真值
    setState((s) => {
      const realPlaying = !audio.paused;
      const realPos = Number.isFinite(audio.currentTime)
        ? audio.currentTime
        : s.positionSec;
      if (s.isPlaying === realPlaying && Math.abs(s.positionSec - realPos) < 0.5) {
        return s;
      }
      return { ...s, isPlaying: realPlaying, positionSec: realPos };
    });
  }, [getAudio]);

  const snapshotForBackground = useCallback(() => {
    const audio = getAudio(activeDeckRef.current);
    if (!audio || !audio.src) return;
    // 这里不更新 userIntendedPlayingRef —— 它只跟"用户点击"走，不能被 OS 干扰
    if (Number.isFinite(audio.currentTime)) {
      lastKnownPositionRef.current = audio.currentTime;
    }
    cache.setState(LAST_POSITION_KEY, String(audio.currentTime)).catch(() => {});
  }, [getAudio]);

  useEffect(() => {
    if (typeof document === "undefined") return;
    const onVisibility = () => {
      if (document.visibilityState === "visible") restoreFromBackground();
      else snapshotForBackground();
    };
    const onFocus = () => restoreFromBackground();
    const onBlur = () => snapshotForBackground();
    const onPageShow = () => restoreFromBackground();

    document.addEventListener("visibilitychange", onVisibility);
    window.addEventListener("focus", onFocus);
    window.addEventListener("blur", onBlur);
    window.addEventListener("pageshow", onPageShow);
    return () => {
      document.removeEventListener("visibilitychange", onVisibility);
      window.removeEventListener("focus", onFocus);
      window.removeEventListener("blur", onBlur);
      window.removeEventListener("pageshow", onPageShow);
    };
  }, [restoreFromBackground, snapshotForBackground]);

  // ---- MediaSession：耳机 / 系统媒体键 ----
  //
  // 把当前曲目的元信息和操作 handler 注册给浏览器的 MediaSession API，
  // 系统/耳机自动接管"单击=播放暂停 / 双击=下一首 / 三击=上一首" ——
  // 跟 Apple Music、网易云是同一套机制（macOS Now Playing widget、
  // 蓝牙耳机 button、Touch Bar 都走它）。
  //
  // 注意：artwork 用 claudio-cdn 自定义 scheme，OS Now Playing UI 在 WKWebView
  // 同一进程内可以解析；不行就退化成只显示文字，不影响按键控制。

  // 1) 元数据：曲目变化时重新设
  useEffect(() => {
    if (typeof navigator === "undefined" || !("mediaSession" in navigator)) return;
    const ms = navigator.mediaSession;
    if (!state.current) {
      ms.metadata = null;
      return;
    }
    try {
      ms.metadata = new MediaMetadata({
        title: state.current.title,
        artist: state.current.artist,
        album: state.current.album ?? "",
        artwork: state.current.cover
          ? [
              { src: cdn(state.current.cover), sizes: "300x300", type: "image/jpeg" },
            ]
          : [],
      });
    } catch (e) {
      console.debug("[claudio] mediaSession metadata 失败", e);
    }
  }, [state.current]);

  // 2) 操作 handler：注册一次，复用稳定的 callback
  useEffect(() => {
    if (typeof navigator === "undefined" || !("mediaSession" in navigator)) return;
    const ms = navigator.mediaSession;
    const set = (name: MediaSessionAction, h: ((d?: MediaSessionActionDetails) => void) | null) => {
      try {
        ms.setActionHandler(name, h);
      } catch (e) {
        // 某些 action 在某些 webview 不被支持，setActionHandler 抛 NotSupported
        console.debug(`[claudio] mediaSession action ${name} 不支持`, e);
      }
    };

    set("play", () => void resume());
    set("pause", () => pause());
    // nexttrack / previoustrack 是耳机双击/三击的标准映射
    set("nexttrack", () => next());
    set("previoustrack", () => prev());
    set("seekto", (d) => {
      if (d && typeof d.seekTime === "number") seek(d.seekTime);
    });
    // 一些耳机会发 stop（罕见），当成 pause 处理
    set("stop", () => pause());

    return () => {
      set("play", null);
      set("pause", null);
      set("nexttrack", null);
      set("previoustrack", null);
      set("seekto", null);
      set("stop", null);
    };
  }, [resume, pause, next, prev, seek]);

  // 3) playbackState 跟随 isPlaying（系统 Now Playing 的播放/暂停图标用这个）
  useEffect(() => {
    if (typeof navigator === "undefined" || !("mediaSession" in navigator)) return;
    navigator.mediaSession.playbackState = state.isPlaying ? "playing" : "paused";
  }, [state.isPlaying]);

  // 4) 位置/总时长 ——  系统进度条要这个；用 ref 节流，避免每 250ms 触发 setState
  //    仅在曲目切换时调一次（duration 变了）；后续用 timeupdate 内的逻辑同步。
  //    实际位置同步在 onTime 里直接调 setPositionState，不放在 effect 里走 React 一圈。
  useEffect(() => {
    if (typeof navigator === "undefined" || !("mediaSession" in navigator)) return;
    if (!state.current || !state.current.durationSec) return;
    try {
      navigator.mediaSession.setPositionState?.({
        duration: state.current.durationSec,
        position: Math.min(state.positionSec, state.current.durationSec),
        playbackRate: 1,
      });
    } catch (e) {
      console.debug("[claudio] mediaSession setPositionState 失败", e);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [state.current?.id]);

  // ---- 卸载清理 ----
  useEffect(() => {
    return () => {
      if (rafRef.current != null) cancelAnimationFrame(rafRef.current);
      (window as unknown as { __claudioAmp?: number }).__claudioAmp = 0;
    };
  }, []);

  const api = useMemo<PlayerAPI>(
    () => ({
      ...state,
      playNetease,
      pause,
      resume,
      toggle,
      next,
      prev,
      seek,
      setAiStatus,
      setMood,
      like,
      dislike,
    }),
    [
      state,
      playNetease,
      pause,
      resume,
      toggle,
      next,
      prev,
      seek,
      setAiStatus,
      setMood,
      like,
      dislike,
    ],
  );

  return (
    <PlayerCtx.Provider value={api}>
      {/*
        两路 audio：A 默认 active，B 默认 preload/inactive。
        crossOrigin="anonymous"：claudio-cdn 代理给的是 ACAO=*，不会被 tainted，
        MediaElementSource 能拿到真实 PCM 喂 analyser。
      */}
      {/*
        preload="auto" 是 seamless 的核心：
        从 src 一上去浏览器就开始拉整个文件（带 Range 透传），
        crossfade 触发那一刻 buffer 已经足够，不会出现 stall。
      */}
      <audio ref={audioARef} preload="auto" crossOrigin="anonymous" style={{ display: "none" }} />
      <audio ref={audioBRef} preload="auto" crossOrigin="anonymous" style={{ display: "none" }} />
      {children}
    </PlayerCtx.Provider>
  );
}
