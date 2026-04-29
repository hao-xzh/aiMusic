"use client";

/**
 * 全局前端播放状态 v3 —— 采样级 gapless 引擎。
 *
 * 旧版（v2）用 dual-<audio> + crossfade，靠 timeupdate 触发切歌，永远做不到
 * "上一首最后一个采样接下一首第一个采样"。v3 改用 Web Audio：
 *
 *     AudioBuffer (current) ─→ GainNode ─┐
 *                                          ├→ analyser ─→ destination
 *     AudioBuffer (next)    ─→ GainNode ─┘
 *
 * 全部 scheduling 走 ctx.currentTime（sample-precise），下一首 ~25s 前就解码 +
 * 排程好，gapless 模式起播时间贴在上一首 buffer 末样本结束的瞬间。
 *
 * 编码器 padding（mp3 LAME / aac iTunSMPB）由 gapless-engine.ts 在解码后剥掉。
 *
 * 实现拆成三层：
 *   - gapless-engine.ts   元数据解析 + AudioBuffer trim
 *   - audio-scheduler.ts  AudioContext 时钟调度 + LRU buffer 缓存
 *   - player-state.tsx    队列、UI 状态、mediaSession、AI 旁白、行为日志
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
import { audio, cache, netease, wrapAudioUrl, type TrackInfo } from "./tauri";
import { cdn } from "./cdn";
import { parseLrc, type LrcLine } from "./lrc";
import { parseYrc, type YrcLine } from "./yrc";
import {
  judgeTransition,
  DEFAULT_JUDGMENT,
  type TransitionJudgment,
} from "./transition-judge";
import { getOrAnalyze, loadAnalysis, mergeWithNative } from "./audio-analysis";
import { planMix, type MixPlan } from "./mix-planner";
import { smoothQueue } from "./smooth-queue";
import { logBehavior } from "./behavior-log";
import {
  AudioScheduler,
  TrackBufferCache,
  type TransitionPlan,
} from "./audio-scheduler";

// ---- 持久化键 ----
const LAST_TRACK_KEY = "last_track";
const LAST_POSITION_KEY = "last_position_sec";
const LAST_QUEUE_KEY = "last_queue";

// ---- 调度参数 ----
//
// 提前多久开始解码 + 排下一首。MP3 解码 ~150-300ms + fetch 弱网最长 ~10s，
// 留 25s 余量保证排程时下一首 buffer 已经 ready。
const PRELOAD_LEAD_S = 999_999;

// 手动 next/prev 的短淡入淡出（防 click）。gapless 自动接歌不走这个。
const MANUAL_CROSSFADE_S = 0.4;
const PLAYBACK_LEVELS = ["lossless", "exhigh"] as const;
type PlaybackLevel = (typeof PLAYBACK_LEVELS)[number];

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
  /** 逐字时间块（如果网易云返回了 yrc）。空数组 = 没有逐字数据，前端走插值 fallback */
  yrcLines: YrcLine[];
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

type TrackPlaybackState = {
  current: Track | null;
  positionSec: number;
  isPlaying: boolean;
  error: string | null;
  aiStatus: string;
  lyric: LyricTrack | null;
};

export type PlayNeteaseOptions = {
  smooth?: boolean;
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
  like: () => void;
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

function clamp(x: number, lo: number, hi: number): number {
  return x < lo ? lo : x > hi ? hi : x;
}

function playbackStateForTrack(
  track: Track,
  positionSec: number,
  aiStatus: string,
): TrackPlaybackState {
  return {
    current: track,
    positionSec,
    isPlaying: true,
    error: null,
    aiStatus,
    lyric: null,
  };
}

export function PlayerProvider({ children }: { children: React.ReactNode }) {
  // ---- WebAudio + 调度器（懒创建，第一次用户 gesture 才 new） ----
  const ctxRef = useRef<AudioContext | null>(null);
  const analyserRef = useRef<AnalyserNode | null>(null);
  const masterGainRef = useRef<GainNode | null>(null);
  const schedulerRef = useRef<AudioScheduler | null>(null);
  const bufferCacheRef = useRef<TrackBufferCache | null>(null);
  const rafRef = useRef<number | null>(null);

  // ---- 用户意图 + 最后已知位置 ----
  // OS / 切后台都不能改这两个。只有用户 click 算意图变更。
  const userIntendedPlayingRef = useRef<boolean>(false);
  const lastKnownPositionRef = useRef<number>(0);

  // ---- 当前 pair (current → next) 的 AI 判断 + mix plan ----
  const pendingJudgmentRef = useRef<TransitionJudgment | null>(null);
  const pendingMixPlanRef = useRef<MixPlan | null>(null);
  /** 当前已经"排好调度"的下一首 trackId。null 表示没排。 */
  const scheduledNextIdRef = useRef<number | null>(null);
  /** 正在排程中（fetch + decode + analysis 进行中） */
  const schedulingForRef = useRef<number | null>(null);
  /** 每次 playNetease / 用户切歌都 ++，老的 in-flight schedule 看到不一致就 abort */
  const generationRef = useRef<number>(0);
  /** 直接播放请求准备中时，旧曲目不能再自动排/跳下一首，避免 UI 与真实意图错位。 */
  const pendingPlaybackGenRef = useRef<number | null>(null);
  /** 已经触发过 prefetch 的 trackId 集 —— 避免同一首反复发请求 */
  const prefetchedSetRef = useRef<Set<number>>(new Set());
  /** 当前正在播放的 audio-cache URL；给提前排下一首时补算当前歌分析用。 */
  const currentAudioUrlRef = useRef<string | null>(null);

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

  // ============================================================
  // 懒创建 WebAudio 图
  // ============================================================
  const ensureAudio = useCallback(() => {
    if (ctxRef.current) return;
    const ACtx =
      window.AudioContext ??
      ((window as unknown as { webkitAudioContext?: typeof AudioContext })
        .webkitAudioContext);
    if (!ACtx) {
      console.warn("[claudio] 浏览器不支持 AudioContext");
      return;
    }
    const ctx = new ACtx();
    const analyser = ctx.createAnalyser();
    analyser.fftSize = 512;
    analyser.smoothingTimeConstant = 0.72;
    const masterGain = ctx.createGain();
    masterGain.gain.value = 1;
    masterGain.connect(analyser).connect(ctx.destination);

    const scheduler = new AudioScheduler(ctx, masterGain);
    const bufferCache = new TrackBufferCache(ctx, 3);

    scheduler.setEvents({
      ontransition: (trackId) => onSchedulerTransition(trackId),
      onended: (trackId) => onSchedulerEnded(trackId),
    });

    ctxRef.current = ctx;
    analyserRef.current = analyser;
    masterGainRef.current = masterGain;
    schedulerRef.current = scheduler;
    bufferCacheRef.current = bufferCache;

    startRafTick();
  }, []);

  // ============================================================
  // RAF tick：位置同步 + 可视化幅度 + 自动排下一首
  // ============================================================
  const lastPosWriteRef = useRef<number>(0);
  const lastMediaSessionWriteRef = useRef<number>(0);

  const startRafTick = useCallback(() => {
    if (rafRef.current != null) return;
    const analyser = analyserRef.current;
    if (!analyser) return;
    const buf = new Uint8Array(analyser.fftSize) as Uint8Array<ArrayBuffer>;

    const tick = () => {
      const sched = schedulerRef.current;
      const an = analyserRef.current;
      if (!sched || !an) return;

      // 可视化幅度
      an.getByteTimeDomainData(buf);
      let sumSq = 0;
      for (let i = 0; i < buf.length; i++) {
        const v = (buf[i]! - 128) / 128;
        sumSq += v * v;
      }
      const mix = Math.sqrt(sumSq / buf.length);
      const amp = clamp(0.25 + mix * 3.2, 0.15, 0.95);
      (window as unknown as { __claudioAmp?: number }).__claudioAmp = amp;

      // 位置同步
      if (sched.getIsPlaying()) {
        const pos = sched.getPositionSec();
        const dur = sched.getDurationSec();
        lastKnownPositionRef.current = pos;
        setState((s) => (Math.abs(s.positionSec - pos) < 0.05 ? s : { ...s, positionSec: pos }));

        const tNow = Date.now();
        if (tNow - lastPosWriteRef.current > 2000) {
          lastPosWriteRef.current = tNow;
          cache.setState(LAST_POSITION_KEY, String(pos)).catch(() => {});
        }
        if (tNow - lastMediaSessionWriteRef.current > 1000) {
          lastMediaSessionWriteRef.current = tNow;
          if (typeof navigator !== "undefined" && "mediaSession" in navigator && dur > 0) {
            try {
              navigator.mediaSession.setPositionState?.({
                duration: dur,
                position: Math.min(pos, dur),
                playbackRate: 1,
              });
            } catch {}
          }
        }

        // 临近曲尾 → 排下一首
        const remain = sched.getRemainingSec() ?? Infinity;
        if (remain < PRELOAD_LEAD_S && userIntendedPlayingRef.current) {
          maybeScheduleNext();
        }
      }

      rafRef.current = requestAnimationFrame(tick);
    };
    rafRef.current = requestAnimationFrame(tick);
  }, []);

  // ============================================================
  // URL 拉链
  // ============================================================
  //
  // 按音质优先级链逐档 fallback。黑胶 SVIP / VIP 能拿到的最高音质因专辑授权
  // 不同 ——  请求 jymaster 时，如果该专辑没母带版会返回 null url，自动降到
  // 下一档。普通用户全部层级都失败时落到 exhigh 320kbps mp3。
  //
  // 各档参考：
  //   - hires      24-bit/96kHz Hi-Res FLAC（黑胶 SVIP / 部分黑胶 VIP）
  //   - lossless   16-bit/44.1kHz CD 级 FLAC（黑胶 VIP 全量）
  //   - exhigh     320kbps mp3（全用户兜底）
  //
  // 串行: lossless → exhigh。
  //
  // 跳过 hires —— 99% 专辑没母带版,网易云 API 对 hires 请求会默默降级返回 mp3 url,
  // 之前的逻辑误以为拿到 hires 就用,结果用户听到 320kbps mp3 的沙沙噪声。
  //
  // 不并行 —— songUrls 只是拿 URL metadata(~1KB JSON),lossless/exhigh API 响应速度
  // 几乎一样;并行除了 VIP 用户偶尔遇到没 lossless 的歌时省一个 RTT,其它 99% 时间
  // 是浪费 API quota(exhigh 结果会被丢弃)。VIP 用户的 lossless 命中率本身就高,
  // 串行简单稳。
  const fetchUrl = useCallback(async (
    neteaseId: number,
    preferredLevels: readonly PlaybackLevel[] = PLAYBACK_LEVELS,
  ): Promise<{ url: string; level: PlaybackLevel } | null> => {
    for (const level of preferredLevels) {
      try {
        const urls = await netease.songUrls([neteaseId], level);
        const u = urls[0];
        if (u?.url) {
          console.debug("[claudio quality]", {
            id: neteaseId,
            level,
            br: u.br,
            sizeMB: (u.size / 1024 / 1024).toFixed(2),
          });
          // 走 audio cache scheme：命中本地直接读，miss 才拉网络 + 落盘 LRU
          return { url: wrapAudioUrl(neteaseId, u.url), level };
        }
      } catch (e) {
        console.debug(`[claudio] ${level} 失败`, e);
      }
    }
    return null;
  }, []);

  const decodeForPlayback = useCallback(async (
    trackId: number,
    cacheRef: TrackBufferCache,
  ): Promise<{ decoded: Awaited<ReturnType<TrackBufferCache["ensure"]>>; url: string; level: PlaybackLevel } | null> => {
    const tried: PlaybackLevel[] = [];
    for (const level of PLAYBACK_LEVELS) {
      const fetched = await fetchUrl(trackId, [level]);
      if (!fetched) continue;
      tried.push(level);
      try {
        const decoded = await cacheRef.ensure(trackId, fetched.url);
        return { decoded, url: fetched.url, level: fetched.level };
      } catch (e) {
        console.warn(`[claudio] decode failed at ${level}, fallback`, trackId, e);
        await audio.clearCacheEntry(trackId).catch(() => {});
        if (level === "exhigh") throw e;
      }
    }
    console.debug("[claudio] no playable url", trackId, tried);
    return null;
  }, [fetchUrl]);

  // ============================================================
  // 排下一首：fetch + decode + AI judgment + mix plan + scheduleNext
  // ============================================================
  const maybeScheduleNext = useCallback(async () => {
    const sched = schedulerRef.current;
    const cacheRef = bufferCacheRef.current;
    if (!sched || !cacheRef) return;
    if (pendingPlaybackGenRef.current != null) return;

    const { current, queue } = stateRef.current;
    if (!current || queue.length <= 1) return;

    const i = queue.findIndex((t) => t.id === current.id);
    if (i < 0) return;
    const nxt = queue[(i + 1) % queue.length];
    if (!nxt?.neteaseId || nxt.id === current.id) return;

    // 已经排好或正在排了 → skip
    if (scheduledNextIdRef.current === nxt.neteaseId) return;
    if (schedulingForRef.current === nxt.neteaseId) return;
    schedulingForRef.current = nxt.neteaseId;

    const gen = generationRef.current;

    try {
      // 解码下一首
      const playback = await decodeForPlayback(nxt.neteaseId, cacheRef);
      if (!playback) {
        console.debug("[claudio] next track 拿不到直链", nxt.title);
        schedulingForRef.current = null;
        return;
      }
      const { url, decoded } = playback;
      // 后台分析（mix-planner / 本地接歌判断用）
      const nextAnalysisPromise = getOrAnalyze(nxt.neteaseId, url);

      // 期间用户可能跳歌了 → 看 generation
      if (gen !== generationRef.current) return;

      // 本地接歌判断 + mix plan
      const fromId = current.neteaseId;
      const fromAnalysisPromise = fromId
        ? loadAnalysis(fromId).then((cached) => {
            if (cached || !currentAudioUrlRef.current) return cached;
            return getOrAnalyze(fromId, currentAudioUrlRef.current);
          }).catch(() => null)
        : Promise.resolve(null);
      const [fromAnalysisRaw, toAnalysisRaw, fromNative, toNative] = await Promise.all([
        fromAnalysisPromise,
        nextAnalysisPromise.catch(() => null),
        // native features：仅查缓存，没有就 null（由后续 audio.getFeatures 异步填）
        fromId ? audio.getCachedFeatures(fromId).catch(() => null) : Promise.resolve(null),
        audio.getCachedFeatures(nxt.neteaseId).catch(() => null),
      ]);
      // 把 Symphonia 算出的更准 BPM/RMS/能量合并进 JS analysis
      const fromAnalysis = fromAnalysisRaw
        ? mergeWithNative(fromAnalysisRaw, fromNative)
        : null;
      const toAnalysis = toAnalysisRaw
        ? mergeWithNative(toAnalysisRaw, toNative)
        : null;
      // 后台抓一发 next 的 native features —— 当前这次 plan 拿不到没事，
      // 下一次切歌时缓存就有了
      if (toNative === null) {
        void audio.getFeatures(nxt.neteaseId, url).catch(() => {});
      }
      const judgment =
        (await judgeTransition(current, nxt, { from: fromAnalysis, to: toAnalysis }).catch(() => null)) ??
        DEFAULT_JUDGMENT;
      if (gen !== generationRef.current) return;
      const plan = planMix(fromAnalysis, toAnalysis, judgment);

      pendingJudgmentRef.current = judgment;
      pendingMixPlanRef.current = plan;

      // mode 映射：
      //   hard_cut → "gapless"     采样级零叠化（同专辑、连续录音）
      //   crossfade / short_seam / breath → "crossfade"  保留 AI 决定的时长
      const transition: TransitionPlan =
        plan.mode === "hard_cut"
          ? {
              mode: "gapless",
              bGainDb: plan.bGainDb,
              inSeekS: plan.inSeekS,
            }
          : {
              mode: "crossfade",
              durationS: clamp(plan.durationS, 0.6, 6),
              bGainDb: plan.bGainDb,
              eqDuck: plan.eqDuck,
              inSeekS: plan.inSeekS,
            };

      console.debug("[claudio mix-plan]", {
        from: current.title,
        to: nxt.title,
        mode: transition.mode,
        durationS: transition.durationS,
        bGainDb: transition.bGainDb,
        eqDuck: transition.eqDuck,
        reason: plan.reason,
      });

      sched.scheduleNext({
        trackId: nxt.neteaseId,
        buffer: decoded.buffer,
        transition,
      });
      scheduledNextIdRef.current = nxt.neteaseId;
    } catch (e) {
      console.debug("[claudio] schedule next 失败", e);
    } finally {
      if (schedulingForRef.current === nxt.neteaseId) {
        schedulingForRef.current = null;
      }
    }
  }, [decodeForPlayback]);

  // ============================================================
  // 预测预取：把队列里 i+2 / i+3 的字节灌进磁盘缓存
  // ============================================================
  //
  // maybeScheduleNext 已经在播 N 时把 N+1 解码好了；这里再多走一步，把 N+2 / N+3
  // 的原始字节（mp3/flac）拉进 Rust 端磁盘缓存，但不解码、不占内存。
  //
  // 命中检测交给 Rust 端的 audio_prefetch（已缓存就立刻返回 true）。
  // 同一 session 同一首只触发一次（prefetchedSetRef 去重），队列变更时清掉，
  // 不浪费用户带宽在他不会听的歌上。
  const prefetchAhead = useCallback(async () => {
    const { current, queue } = stateRef.current;
    if (!current || queue.length < 3) return;
    const i = queue.findIndex((t) => t.id === current.id);
    if (i < 0) return;

    for (let offset = 2; offset <= 3; offset++) {
      const t = queue[(i + offset) % queue.length];
      if (!t?.neteaseId || t.id === current.id) continue;
      if (prefetchedSetRef.current.has(t.neteaseId)) continue;
      prefetchedSetRef.current.add(t.neteaseId);

      // 不 await —— 串行节流交给 Rust 端 cdn_client 的连接池
      void (async () => {
        try {
          const urls = await netease.songUrls([t.neteaseId!], "lossless");
          const u = urls[0];
          // VIP 限制时退到 exhigh
          const finalUrl = u?.url
            ? u.url
            : (await netease.songUrls([t.neteaseId!], "exhigh"))[0]?.url;
          if (!finalUrl) return;
          const hit = await audio.prefetch(t.neteaseId!, finalUrl);
          console.debug("[claudio] prefetch", t.neteaseId, hit ? "(hit)" : "(stored)");
        } catch (e) {
          console.debug("[claudio] prefetch failed", t.neteaseId, e);
          // 失败时把这一项从已触发集移除，下次还能再试
          prefetchedSetRef.current.delete(t.neteaseId!);
        }
      })();
    }
  }, []);

  // ============================================================
  // 调度器事件：next 接管成 current / 队列播完
  // ============================================================
  const onSchedulerTransition = useCallback((trackId: number) => {
    // 把 state.current 切到 next，记一笔旧曲目 completed 行为
    const prior = stateRef.current.current;
    const newCur = stateRef.current.queue.find((t) => t.neteaseId === trackId);
    if (!newCur) return;

    if (prior && prior.neteaseId && prior.neteaseId !== trackId) {
      void logBehavior({
        trackId: prior.neteaseId,
        title: prior.title,
        artist: prior.artist,
        kind: "completed",
        positionSec: prior.durationSec,
        durationSec: prior.durationSec,
      });
    }

    setState((s) => ({
      ...s,
      ...playbackStateForTrack(newCur, 0, "播放中"),
    }));
    cache.setState(LAST_TRACK_KEY, JSON.stringify(newCur)).catch(() => {});
    cache.setState(LAST_POSITION_KEY, "0").catch(() => {});

    scheduledNextIdRef.current = null;
    pendingMixPlanRef.current = null;
    pendingJudgmentRef.current = null;

    // 拉新曲目歌词 + DJ 旁白
    void loadLyricFor(newCur);
    void loadDjLineFor(newCur);
  }, []);

  const onSchedulerEnded = useCallback((trackId: number) => {
    // 队列没下一首了或单曲循环
    if (pendingPlaybackGenRef.current != null) {
      setState((s) => ({ ...s, isPlaying: false, aiStatus: "正在准备下一首…" }));
      return;
    }
    const { current, queue } = stateRef.current;
    if (!current) return;
    if (queue.length > 1) {
      // 队列还有歌但没排上 —— 通常因为 fetch / decode 失败。退化到立即播下一首
      const i = queue.findIndex((t) => t.id === current.id);
      const nxt = queue[(i + 1) % queue.length];
      if (nxt && nxt.id !== current.id) {
        void playTrackInternal(nxt, { manualCut: false });
        return;
      }
    }
    setState((s) => ({ ...s, isPlaying: false, aiStatus: "播完了" }));
    userIntendedPlayingRef.current = false;
  }, []);

  // ============================================================
  // 歌词 + DJ 旁白（异步，不阻塞播放）
  // ============================================================
  const loadLyricFor = useCallback(async (track: Track) => {
    if (!track.neteaseId) return;
    const neteaseId = track.neteaseId;
    const fail = { lyric: "", yrc: null, instrumental: false, uncollected: true };
    let data;
    try {
      const cached = await cache.getLyric(neteaseId);
      data = cached ?? (await netease.songLyric(neteaseId));
      if (!cached && data) cache.saveLyric(neteaseId, data).catch(() => {});
    } catch (e) {
      console.warn("[claudio] 歌词拉取失败", e);
      data = fail;
    }
    setState((s) => {
      if (s.current?.neteaseId !== neteaseId) return s;
      return {
        ...s,
        lyric: {
          lines: parseLrc(data!.lyric),
          yrcLines: parseYrc(data!.yrc ?? null),
          instrumental: data!.instrumental,
          uncollected: data!.uncollected,
        },
      };
    });
  }, []);

  const loadDjLineFor = useCallback(async (track: Track) => {
    if (!track.neteaseId) return;
    const neteaseId = track.neteaseId;
    const djKey = `dj_intro:v1:${neteaseId}`;
    try {
      const cached = await cache.getState(djKey);
      if (cached) {
        setState((s) => {
          if (s.current?.neteaseId !== neteaseId) return s;
          return { ...s, aiStatus: cached };
        });
        return;
      }
    } catch {}

    // 播放链路不再主动请求 AI，避免听歌时按歌曲数持续消耗模型额度。
    // 已有缓存旁白仍可展示；没有缓存就保持普通播放态。
    setState((s) => {
      if (s.current?.neteaseId !== neteaseId) return s;
      return { ...s, aiStatus: "播放中" };
    });
  }, []);

  // ============================================================
  // 核心播放管线
  // ============================================================
  const playTrackInternal = useCallback(
    async (
      track: Track,
      opts?: { resumeFrom?: number; manualCut?: boolean },
    ) => {
      if (!track.neteaseId) {
        setState((s) => ({ ...s, error: "曲目没有网易云 id，无法播放" }));
        return;
      }
      const neteaseId = track.neteaseId;
      const resumeFrom = opts?.resumeFrom ?? 0;
      const manual = opts?.manualCut !== false;

      userIntendedPlayingRef.current = true;
      generationRef.current++;
      const gen = generationRef.current;
      pendingPlaybackGenRef.current = gen;
      scheduledNextIdRef.current = null;
      schedulingForRef.current = null;
      pendingJudgmentRef.current = null;
      pendingMixPlanRef.current = null;

      ensureAudio();
      const ctx = ctxRef.current;
      const sched = schedulerRef.current;
      const bufCache = bufferCacheRef.current;
      if (!ctx || !sched || !bufCache) {
        setState((s) => ({ ...s, error: "音频引擎初始化失败" }));
        if (pendingPlaybackGenRef.current === gen) pendingPlaybackGenRef.current = null;
        return;
      }
      if (ctx.state === "suspended") {
        try { await ctx.resume(); } catch {}
      }

      // 切歌前记一笔旧曲目的行为日志（除非是同一首循环 / 冷启动恢复）
      const prior = stateRef.current.current;
      if (
        prior &&
        prior.neteaseId &&
        prior.neteaseId !== neteaseId &&
        sched.getCurrentTrackId() === prior.neteaseId
      ) {
        const pos = sched.getPositionSec();
        const dur = sched.getDurationSec() || prior.durationSec;
        const ratio = dur > 0 ? pos / dur : 0;
        const kind: "completed" | "skipped" | "manual_cut" = !manual
          ? "completed"
          : ratio < 0.3
            ? "skipped"
            : "manual_cut";
        void logBehavior({
          trackId: prior.neteaseId,
          title: prior.title,
          artist: prior.artist,
          kind,
          positionSec: pos,
          durationSec: dur,
        });
      }

      const schedulerTrackId = sched.getCurrentTrackId();
      const isReplacingAudibleTrack =
        prior?.neteaseId != null &&
        schedulerTrackId === prior.neteaseId &&
        prior.neteaseId !== neteaseId;

      setState((s) => ({
        ...s,
        error: null,
        aiStatus: isReplacingAudibleTrack ? "正在准备下一首…" : "正在解码…",
        isPlaying: isReplacingAudibleTrack ? s.isPlaying : false,
      }));

      try {
        const playback = await decodeForPlayback(neteaseId, bufCache);
        if (!playback) {
          setState((s) => ({
            ...s,
            error: "这首歌拿不到直链（可能需要 VIP 或已下架）",
            isPlaying: isReplacingAudibleTrack ? s.isPlaying : false,
            aiStatus: "—",
          }));
          if (!isReplacingAudibleTrack) userIntendedPlayingRef.current = false;
          if (pendingPlaybackGenRef.current === gen) pendingPlaybackGenRef.current = null;
          return;
        }
        const { url, decoded } = playback;
        // 期间用户切歌了
        if (gen !== generationRef.current) {
          if (pendingPlaybackGenRef.current === gen) pendingPlaybackGenRef.current = null;
          return;
        }

        const isCold = sched.getCurrentTrackId() == null;
        sched.play({
          trackId: neteaseId,
          buffer: decoded.buffer,
          transition: isCold
            ? { mode: "cold" }
            : { mode: "manualCut", durationS: MANUAL_CROSSFADE_S },
          fromOffset: resumeFrom,
        });
        currentAudioUrlRef.current = url;

        setState((s) => {
          if (gen !== generationRef.current) return s;
          return {
            ...s,
            ...playbackStateForTrack(track, resumeFrom, "播放中"),
          };
        });
        lastKnownPositionRef.current = resumeFrom;
        cache.setState(LAST_TRACK_KEY, JSON.stringify(track)).catch(() => {});
        cache.setState(LAST_POSITION_KEY, String(resumeFrom)).catch(() => {});
        if (pendingPlaybackGenRef.current === gen) pendingPlaybackGenRef.current = null;

        // 起播之后再做 JS 分析，避免手动切歌时同一首歌被 fetch 两遍拖慢首响。
        window.setTimeout(() => {
          if (gen === generationRef.current) void getOrAnalyze(neteaseId, url);
        }, 0);

        // 异步歌词 + DJ 旁白
        void loadLyricFor(track);
        void loadDjLineFor(track);
      } catch (e) {
        console.debug("[claudio] playTrack 失败", e);
        setState((s) => {
          if (gen !== generationRef.current) return s;
          return {
            ...s,
            error: e instanceof Error ? e.message : String(e),
            isPlaying: isReplacingAudibleTrack ? s.isPlaying : false,
            aiStatus: "—",
          };
        });
        if (!isReplacingAudibleTrack) userIntendedPlayingRef.current = false;
        if (pendingPlaybackGenRef.current === gen) pendingPlaybackGenRef.current = null;
      }
    },
    [ensureAudio, decodeForPlayback, loadLyricFor, loadDjLineFor],
  );

  // ============================================================
  // 公开 API
  // ============================================================

  const playNetease = useCallback(
    async (
      t: TrackInfo,
      contextQueue?: TrackInfo[],
      opts?: PlayNeteaseOptions,
    ) => {
      if (contextQueue) {
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
      await playTrackInternal(neteaseToTrack(t));
    },
    [playTrackInternal],
  );

  const pause = useCallback(() => {
    userIntendedPlayingRef.current = false;
    generationRef.current++;
    pendingPlaybackGenRef.current = null;
    scheduledNextIdRef.current = null;
    schedulingForRef.current = null;
    pendingJudgmentRef.current = null;
    pendingMixPlanRef.current = null;
    schedulerRef.current?.pause();
    setState((s) => ({ ...s, isPlaying: false }));
    (window as unknown as { __claudioAmp?: number }).__claudioAmp = 0;
  }, []);

  const resume = useCallback(async () => {
    const sched = schedulerRef.current;
    const ctx = ctxRef.current;
    if (!sched || !ctx) {
      // 还没起 ctx：从持久化的 last_track 恢复
      const cur = stateRef.current.current;
      if (cur) {
        await playTrackInternal(cur, { resumeFrom: stateRef.current.positionSec });
      }
      return;
    }
    userIntendedPlayingRef.current = true;
    if (ctx.state === "suspended") {
      try { await ctx.resume(); } catch {}
    }
    if (sched.getCurrentTrackId() != null) {
      sched.resume();
      setState((s) => ({ ...s, isPlaying: true }));
    } else {
      // scheduler 没载入任何 buffer（冷启动恢复后第一次 resume）
      const cur = stateRef.current.current;
      if (cur) await playTrackInternal(cur, { resumeFrom: stateRef.current.positionSec });
    }
  }, [playTrackInternal]);

  const toggle = useCallback(() => {
    if (stateRef.current.isPlaying) pause();
    else void resume();
  }, [pause, resume]);

  const seek = useCallback((sec: number) => {
    // seek 会让 scheduler 内部 cancelNext —— 同步清掉 player-state 这边的标记，
    // 否则 rAF 检查会以为下一首"已经排好"，不会重新走预解码 + scheduleNext。
    scheduledNextIdRef.current = null;
    schedulingForRef.current = null;
    pendingPlaybackGenRef.current = null;
    pendingJudgmentRef.current = null;
    pendingMixPlanRef.current = null;
    schedulerRef.current?.seek(Math.max(0, sec));
  }, []);

  const next = useCallback(() => {
    const { current, queue } = stateRef.current;
    if (!current) return;
    if (queue.length === 0) {
      void playTrackInternal(current, { manualCut: true });
      return;
    }
    const i = queue.findIndex((t) => t.id === current.id);
    const nxt = queue[(i + 1) % queue.length];
    void playTrackInternal(nxt ?? current, { manualCut: true });
  }, [playTrackInternal]);

  const prev = useCallback(() => {
    const { current, queue } = stateRef.current;
    if (!current) return;
    if (queue.length === 0) {
      void playTrackInternal(current, { manualCut: true });
      return;
    }
    const i = queue.findIndex((t) => t.id === current.id);
    const p = queue[(i - 1 + queue.length) % queue.length];
    void playTrackInternal(p ?? current, { manualCut: true });
  }, [playTrackInternal]);

  const setAiStatus = useCallback((aiStatus: string) => {
    setState((s) => ({ ...s, aiStatus }));
  }, []);

  const setMood = useCallback((m: string | null) => {
    setState((s) => ({ ...s, mood: m }));
  }, []);

  const like = useCallback(() => {
    const cur = stateRef.current.current;
    if (!cur || !cur.neteaseId) return;
    const sched = schedulerRef.current;
    void logBehavior({
      trackId: cur.neteaseId,
      title: cur.title,
      artist: cur.artist,
      kind: "liked",
      positionSec: sched?.getPositionSec() ?? 0,
      durationSec: sched?.getDurationSec() || cur.durationSec,
    });
    setState((s) => ({ ...s, aiStatus: "记住了，你喜欢这首" }));
  }, []);

  const dislike = useCallback(() => {
    const cur = stateRef.current.current;
    if (!cur || !cur.neteaseId) return;
    const sched = schedulerRef.current;
    void logBehavior({
      trackId: cur.neteaseId,
      title: cur.title,
      artist: cur.artist,
      kind: "disliked",
      positionSec: sched?.getPositionSec() ?? 0,
      durationSec: sched?.getDurationSec() || cur.durationSec,
    });
    next();
  }, [next]);

  // ============================================================
  // 上次播放状态恢复（不自动起播，等用户点 ▶）
  // ============================================================
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
        lastKnownPositionRef.current = Number.isFinite(pos) ? pos : 0;
      } catch (e) {
        console.debug("[claudio] 跳过上次播放恢复", e);
      }
    })();
    return () => {
      alive = false;
    };
  }, []);

  // ============================================================
  // 前后台切换：ctx 可能被 OS suspend，focus 时 resume
  // ============================================================
  useEffect(() => {
    if (typeof document === "undefined") return;

    const restore = () => {
      const ctx = ctxRef.current;
      if (ctx && ctx.state === "suspended" && userIntendedPlayingRef.current) {
        ctx.resume().catch(() => {});
      }
    };
    const snapshot = () => {
      const sched = schedulerRef.current;
      if (sched) {
        const pos = sched.getPositionSec();
        if (Number.isFinite(pos)) {
          lastKnownPositionRef.current = pos;
          cache.setState(LAST_POSITION_KEY, String(pos)).catch(() => {});
        }
      }
    };

    const onVisibility = () => {
      if (document.visibilityState === "visible") restore();
      else snapshot();
    };
    document.addEventListener("visibilitychange", onVisibility);
    window.addEventListener("focus", restore);
    window.addEventListener("blur", snapshot);
    window.addEventListener("pageshow", restore);
    return () => {
      document.removeEventListener("visibilitychange", onVisibility);
      window.removeEventListener("focus", restore);
      window.removeEventListener("blur", snapshot);
      window.removeEventListener("pageshow", restore);
    };
  }, []);

  // ============================================================
  // MediaSession：耳机 / 系统媒体键
  // ============================================================
  // 当前曲目变了 → 队列后面再多 prefetch 两首字节进盘
  // 不依赖 isPlaying，单纯跟着 current 走；用户在 UI 里翻队列时也会触发预热
  useEffect(() => {
    if (!state.current) return;
    void prefetchAhead();
  }, [state.current?.id, prefetchAhead]);

  // 队列整体换了 → 清掉 prefetch 去重集（之前的歌不再相关）
  useEffect(() => {
    prefetchedSetRef.current.clear();
    // 第一首 prefetch 由 current 那个 effect 触发；这里只重置
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [state.queue.length, state.queue[0]?.id]);

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
          ? [{ src: cdn(state.current.cover), sizes: "300x300", type: "image/jpeg" }]
          : [],
      });
    } catch (e) {
      console.debug("[claudio] mediaSession metadata 失败", e);
    }
  }, [state.current]);

  useEffect(() => {
    if (typeof navigator === "undefined" || !("mediaSession" in navigator)) return;
    const ms = navigator.mediaSession;
    const set = (
      name: MediaSessionAction,
      h: ((d?: MediaSessionActionDetails) => void) | null,
    ) => {
      try {
        ms.setActionHandler(name, h);
      } catch (e) {
        console.debug(`[claudio] mediaSession action ${name} 不支持`, e);
      }
    };
    set("play", () => void resume());
    set("pause", () => pause());
    set("nexttrack", () => next());
    set("previoustrack", () => prev());
    set("seekto", (d) => {
      if (d && typeof d.seekTime === "number") seek(d.seekTime);
    });
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

  useEffect(() => {
    if (typeof navigator === "undefined" || !("mediaSession" in navigator)) return;
    navigator.mediaSession.playbackState = state.isPlaying ? "playing" : "paused";
  }, [state.isPlaying]);

  // ============================================================
  // 卸载清理
  // ============================================================
  useEffect(() => {
    return () => {
      if (rafRef.current != null) cancelAnimationFrame(rafRef.current);
      schedulerRef.current?.destroy();
      bufferCacheRef.current?.clear();
      ctxRef.current?.close().catch(() => {});
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
    [state, playNetease, pause, resume, toggle, next, prev, seek, setAiStatus, setMood, like, dislike],
  );

  return <PlayerCtx.Provider value={api}>{children}</PlayerCtx.Provider>;
}
