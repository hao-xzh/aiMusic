"use client";

/**
 * 真 gapless 调度器 —— 把 AudioBuffer 用 AudioBufferSourceNode 排程到
 * AudioContext 时钟上，做到采样级衔接。
 *
 * 旧的 dual-<audio> 架构靠浏览器 timeupdate（~250ms 抖动）和 audio.play()
 * 触发时机切歌，永远做不到 gapless。这里换成：
 *
 *     AudioBuffer (current) ─→ GainNode (cur) ─┐
 *                                                ├→ analyser ─→ destination
 *     AudioBuffer (next)    ─→ GainNode (nxt) ─┘
 *
 * 当前在播 source 一旦创建，就用 ctx.currentTime 锚定开始时间，下一首
 * 通过 nextSource.start(currentEndCtxTime) 提前排好 —— Web Audio 调度
 * 是 sample-precise 的（实现规定 ≤ 1 sample 抖动）。
 *
 * Transition 类型：
 *   - "gapless" / "hardCut"  →  next.start(curEnd)，零叠化、零淡化
 *   - "crossfade"            →  next.start(curEnd - duration)，等功率叠化
 *   - "manualCut"            →  当场停 cur，next 立即起播（短淡入防 click）
 *   - "cold"                 →  没有 cur，next 直接起播（短淡入防 click）
 *
 * 暂停/恢复：用 stop() + 记 offset + 重建 source 实现。不动 ctx 状态，
 * 因为 ctx.suspend/resume 会让我们已经 schedule 的 next 时间漂移。
 *
 * 解码缓存：3 个 AudioBuffer LRU。当前 + 下一个 + 上一个，足够手动 prev
 * 不重新解码。一个 4 分钟 44.1kHz stereo float32 ~80MB，3 个 ~240MB —— 桌面 OK。
 */

import { decodeAndTrim, type DecodeResult } from "./gapless-engine";

export type TransitionMode = "cold" | "gapless" | "hardCut" | "crossfade" | "manualCut";

export type TransitionPlan = {
  mode: TransitionMode;
  /** crossfade 时长 (s)。其它 mode 忽略。 */
  durationS?: number;
  /** incoming 的 dB 调整（level match）。crossfade 用。 */
  bGainDb?: number;
  /** 是否对 outgoing 做低频压一下。crossfade 用。 */
  eqDuck?: boolean;
  /** 入场 seek (s)。跳过 next 的引导。 */
  inSeekS?: number;
};

export type DecodedTrack = {
  buffer: AudioBuffer;
  meta: DecodeResult["meta"];
  trimmedHead: number;
  trimmedTail: number;
};

export type SchedulerEvents = {
  /** 当 next 真正接管成为 current 时触发，trackId 是新 current 的 id */
  ontransition?: (trackId: number) => void;
  /** 当前曲目自然播完且没有排好的 next 时触发 */
  onended?: (trackId: number) => void;
  /** 调度失败 / 解码失败 / scheduleNext 时机已过等 */
  onerror?: (e: Error) => void;
};

type LiveSource = {
  trackId: number;
  buffer: AudioBuffer;
  source: AudioBufferSourceNode;
  gain: GainNode;
  /**
   * source.start(when) 里那个 when —— 注意它**等于** buffer 第 0 个 sample
   * 在 ctx 时钟上的时刻。如果用 fromOffset > 0 起播，buffer[0] 实际上没响过，
   * 但锚点时间仍记成 startCtxTime，currentTime 计算靠 + offset。
   */
  startCtxTime: number;
  /** 从 buffer 内的哪个秒数开始播 */
  startBufferOffset: number;
  /** 这个 source 在 ctx 时钟上预计的结束时刻（buffer 末端） */
  endCtxTime: number;
  /** 用户主动 stop 时设 true，让 onended 知道这是被打断的 */
  stoppedManually: boolean;
};

const FADE_COLD_S = 0.05;
const FADE_MANUAL_CUT_S = 0.04;

export class AudioScheduler {
  private ctx: AudioContext;
  private out: AudioNode;

  private current: LiveSource | null = null;
  private next: { source: LiveSource; mode: TransitionMode } | null = null;

  private paused = false;
  /** 暂停时记录"如果 resume 应该从 buffer 里哪秒接着播" */
  private pausedAtBufferOffset = 0;

  private events: SchedulerEvents = {};

  /** 用来做"是否已经排过 next"的精度判定，避免重复 schedule。 */
  scheduledNextTrackId: number | null = null;

  constructor(ctx: AudioContext, out: AudioNode) {
    this.ctx = ctx;
    this.out = out;
  }

  setEvents(e: SchedulerEvents) {
    this.events = e;
  }

  // ============================================================
  // 时钟 / 状态
  // ============================================================

  /** buffer 内的当前播放秒数 */
  getPositionSec(): number {
    if (!this.current) return 0;
    if (this.paused) return this.pausedAtBufferOffset;
    const elapsed = this.ctx.currentTime - this.current.startCtxTime;
    const pos = this.current.startBufferOffset + Math.max(0, elapsed);
    return Math.min(pos, this.current.buffer.duration);
  }

  /** 当前 buffer 的总长度（trim 后的真实音乐时长） */
  getDurationSec(): number {
    if (!this.current) return 0;
    return this.current.buffer.duration;
  }

  getCurrentTrackId(): number | null {
    return this.current?.trackId ?? null;
  }

  getIsPlaying(): boolean {
    return !!this.current && !this.paused;
  }

  /** 距离当前曲目结束还有多少秒（不算已 schedule 的 next）。null 表示没在播。 */
  getRemainingSec(): number | null {
    if (!this.current || this.paused) return null;
    const r = this.current.endCtxTime - this.ctx.currentTime;
    return r > 0 ? r : 0;
  }

  // ============================================================
  // 起播 / 切歌
  // ============================================================

  /**
   * 用 transition.mode 决定的方式起播一首曲目。
   *
   *   cold       —— 没有 current，直接淡入起播
   *   manualCut  —— 当场停 current，next 立即起播
   *   gapless / hardCut / crossfade
   *              —— 不应该走这里！这些是用 scheduleNext 提前排好的；
   *                 之所以接受是因为有时来不及（preload 失败）必须当场切，
   *                 此时退化成 manualCut。
   */
  play(opts: {
    trackId: number;
    buffer: AudioBuffer;
    transition: TransitionPlan;
    fromOffset?: number;
  }): void {
    const fromOffset = opts.fromOffset ?? 0;

    // 已经排好的 next 失效（用户跳歌了）
    this.cancelNext();

    const mode = opts.transition.mode;
    if (mode === "cold") {
      this.startCurrent(opts.trackId, opts.buffer, fromOffset, FADE_COLD_S);
      return;
    }

    // 其它情况：当场停 current，淡入新 current
    if (this.current) {
      this.stopCurrent({ short: true });
    }
    this.startCurrent(opts.trackId, opts.buffer, fromOffset, FADE_MANUAL_CUT_S);
  }

  /**
   * 提前排下一首，让 Web Audio 时钟在精确的 ctx 时刻接管。
   * 必须在 current 还在播时调用。重复 schedule 同一首是 no-op。
   */
  scheduleNext(opts: {
    trackId: number;
    buffer: AudioBuffer;
    transition: TransitionPlan;
  }): void {
    const cur = this.current;
    if (!cur || this.paused) {
      // 没在播 → 退化成立即播（cold）
      this.play({
        trackId: opts.trackId,
        buffer: opts.buffer,
        transition: { ...opts.transition, mode: "cold" },
      });
      return;
    }

    if (this.scheduledNextTrackId === opts.trackId && this.next) return;

    // 旧的 next 不要了
    if (this.next) {
      this.killSource(this.next.source);
      this.next = null;
    }

    const mode = opts.transition.mode;
    const inSeek = Math.max(0, opts.transition.inSeekS ?? 0);
    const targetGain = dbToGain(opts.transition.bGainDb ?? 0);

    let scheduledStart: number;
    if (mode === "crossfade") {
      const dur = Math.max(0.05, opts.transition.durationS ?? 1.5);
      scheduledStart = cur.endCtxTime - dur;
      // 太晚 —— current 已经接近结束，crossfade 起点已经过去，退化成 hardCut
      if (scheduledStart <= this.ctx.currentTime + 0.02) {
        scheduledStart = cur.endCtxTime;
      }
    } else {
      // gapless / hardCut: 上一首 buffer 末样本结束的瞬间起 next
      scheduledStart = cur.endCtxTime;
      // 防止排到过去（罕见，调度漂移）
      if (scheduledStart < this.ctx.currentTime + 0.005) {
        scheduledStart = this.ctx.currentTime + 0.02;
      }
    }

    const live = this.createSource(opts.trackId, opts.buffer, inSeek);
    // gain 初始：crossfade 从 0 → targetGain；gapless/hardCut 直接 targetGain
    const t = scheduledStart;
    if (mode === "crossfade") {
      const dur = Math.max(0.05, opts.transition.durationS ?? 1.5);
      // incoming 等功率上升：sin²(t)
      live.gain.gain.setValueAtTime(0, t);
      // setValueCurveAtTime 用 sin² 曲线
      const N = 64;
      const fadeIn = new Float32Array(N);
      for (let i = 0; i < N; i++) {
        const k = i / (N - 1);
        const s = Math.sin((k * Math.PI) / 2);
        fadeIn[i] = s * s * targetGain;
      }
      try {
        live.gain.gain.setValueCurveAtTime(fadeIn, t, dur);
      } catch (e) {
        // setValueCurveAtTime 偶尔会被 ctx 拒绝（duration 太短/起点冲突），硬切
        live.gain.gain.setValueAtTime(targetGain, t);
      }

      // outgoing 等功率衰减：cos²(t)
      const fadeOut = new Float32Array(N);
      for (let i = 0; i < N; i++) {
        const k = i / (N - 1);
        const c = Math.cos((k * Math.PI) / 2);
        fadeOut[i] = c * c;
      }
      try {
        cur.gain.gain.cancelScheduledValues(t);
        cur.gain.gain.setValueAtTime(cur.gain.gain.value, t);
        cur.gain.gain.setValueCurveAtTime(fadeOut, t, dur);
      } catch {}
    } else {
      // gapless / hardCut: 不做任何 ramp，sample-accurate 接上
      live.gain.gain.setValueAtTime(targetGain, t);
    }

    live.source.start(t, inSeek);
    live.startCtxTime = t;
    live.endCtxTime = t + (live.buffer.duration - inSeek);

    // 排个 timer 在 next 真正接管时把 current 替换 + 触发 ontransition。
    // 这只是状态标记；声音切换不依赖这个 timer，依赖的是 Web Audio 时钟。
    const switchAt = mode === "crossfade"
      ? cur.endCtxTime // crossfade 完成时（cur buffer 走完）
      : t;             // gapless/hardCut: next 起点
    const delayMs = Math.max(0, (switchAt - this.ctx.currentTime) * 1000);
    const swapTimer = window.setTimeout(() => {
      // 还得检查这个 next 是不是仍然有效（没被 cancelNext 干掉）
      if (this.next?.source !== live) return;
      // current 让它尾巴自然走完（buffer 已经播过尾），不主动 stop
      this.current = live;
      this.next = null;
      this.scheduledNextTrackId = null;
      this.events.ontransition?.(live.trackId);
    }, delayMs);
    // 记下 timer 给 cancelNext 用
    (live as any).__swapTimer = swapTimer;

    // source 自然播完时如果它正好是 current（我们没排再下一首），通知上层
    live.source.onended = () => {
      if (live.stoppedManually) return;
      if (this.current === live && !this.next) {
        this.events.onended?.(live.trackId);
      }
    };

    this.next = { source: live, mode };
    this.scheduledNextTrackId = opts.trackId;
  }

  cancelNext(): void {
    if (!this.next) {
      this.scheduledNextTrackId = null;
      return;
    }
    this.killSource(this.next.source);
    // 如果 cur 的 gain 已经被 schedule 了 fade-out，撤销
    if (this.current) {
      try {
        const g = this.current.gain.gain;
        g.cancelScheduledValues(this.ctx.currentTime);
        g.setValueAtTime(1, this.ctx.currentTime);
      } catch {}
    }
    this.next = null;
    this.scheduledNextTrackId = null;
  }

  // ============================================================
  // 暂停 / 恢复 / Seek
  // ============================================================

  pause(): void {
    if (!this.current || this.paused) return;
    const offset = this.getPositionSec();
    this.pausedAtBufferOffset = offset;
    this.paused = true;
    // 停掉 source（不是 ctx），这样已经排好的 next 也不会"从过去"被触发
    this.cancelNext();
    this.stopCurrentSourceOnly();
  }

  resume(): void {
    if (!this.paused || !this.current) return;
    const trackId = this.current.trackId;
    const buffer = this.current.buffer;
    this.paused = false;
    this.startCurrent(trackId, buffer, this.pausedAtBufferOffset, FADE_MANUAL_CUT_S);
  }

  seek(positionSec: number): void {
    if (!this.current) return;
    const trackId = this.current.trackId;
    const buffer = this.current.buffer;
    const target = Math.max(0, Math.min(positionSec, buffer.duration - 0.05));
    this.cancelNext();
    this.stopCurrentSourceOnly();
    this.paused = false;
    this.startCurrent(trackId, buffer, target, FADE_MANUAL_CUT_S);
  }

  /** 完全停下，不保留任何状态（用于 unmount） */
  destroy(): void {
    this.cancelNext();
    if (this.current) {
      this.stopCurrent({ short: true });
    }
    this.current = null;
    this.next = null;
  }

  // ============================================================
  // 内部
  // ============================================================

  private startCurrent(
    trackId: number,
    buffer: AudioBuffer,
    fromOffset: number,
    fadeInS: number,
  ): void {
    const live = this.createSource(trackId, buffer, fromOffset);
    const t = this.ctx.currentTime + 0.005;
    // 短淡入防 click
    live.gain.gain.setValueAtTime(0, t);
    live.gain.gain.linearRampToValueAtTime(1, t + fadeInS);
    live.source.start(t, fromOffset);
    live.startCtxTime = t;
    live.endCtxTime = t + (buffer.duration - fromOffset);
    live.source.onended = () => {
      if (live.stoppedManually) return;
      if (this.current === live && !this.next) {
        this.events.onended?.(live.trackId);
      }
    };
    this.current = live;
    this.paused = false;
    this.pausedAtBufferOffset = 0;
  }

  private createSource(trackId: number, buffer: AudioBuffer, fromOffset: number): LiveSource {
    const source = this.ctx.createBufferSource();
    source.buffer = buffer;
    const gain = this.ctx.createGain();
    gain.gain.value = 0;
    source.connect(gain).connect(this.out);
    return {
      trackId,
      buffer,
      source,
      gain,
      startCtxTime: 0, // 由 caller 在 start 之后写
      startBufferOffset: fromOffset,
      endCtxTime: 0, // 同上
      stoppedManually: false,
    };
  }

  private stopCurrent(opts: { short: boolean }): void {
    if (!this.current) return;
    const cur = this.current;
    const t = this.ctx.currentTime;
    if (opts.short) {
      // 短淡出防 click，然后 stop
      try {
        cur.gain.gain.cancelScheduledValues(t);
        cur.gain.gain.setValueAtTime(cur.gain.gain.value, t);
        cur.gain.gain.linearRampToValueAtTime(0, t + 0.025);
      } catch {}
      cur.stoppedManually = true;
      try { cur.source.stop(t + 0.03); } catch {}
    } else {
      cur.stoppedManually = true;
      try { cur.source.stop(t); } catch {}
    }
    try { cur.source.disconnect(); } catch {}
    try { cur.gain.disconnect(); } catch {}
    this.current = null;
  }

  private stopCurrentSourceOnly(): void {
    if (!this.current) return;
    const cur = this.current;
    cur.stoppedManually = true;
    try { cur.source.stop(); } catch {}
    try { cur.source.disconnect(); } catch {}
    try { cur.gain.disconnect(); } catch {}
    // 不清 this.current —— resume/seek 会复用 buffer + trackId
  }

  private killSource(live: LiveSource): void {
    live.stoppedManually = true;
    try { live.source.stop(); } catch {}
    try { live.source.disconnect(); } catch {}
    try { live.gain.disconnect(); } catch {}
    const t = (live as any).__swapTimer as number | undefined;
    if (t != null) window.clearTimeout(t);
  }
}

function dbToGain(db: number): number {
  return Math.pow(10, db / 20);
}

// ============================================================
// 解码缓存
// ============================================================

/**
 * 3 槽 LRU AudioBuffer 缓存。key 是 trackId，value 是已 trim 好的 buffer。
 *
 * 同一 trackId 并发 ensure 只跑一份解码（promise 复用）。
 */
export class TrackBufferCache {
  private ctx: BaseAudioContext;
  private capacity: number;
  private order: number[] = [];
  private map = new Map<number, DecodedTrack>();
  private inflight = new Map<number, Promise<DecodedTrack>>();

  constructor(ctx: BaseAudioContext, capacity = 3) {
    this.ctx = ctx;
    this.capacity = capacity;
  }

  get(trackId: number): DecodedTrack | undefined {
    const v = this.map.get(trackId);
    if (v) this.touch(trackId);
    return v;
  }

  /**
   * 解码（如果还没缓存）。url 是必须的，因为 cache miss 时要 fetch。
   * 同一 trackId 并发只 fetch 一次。
   */
  ensure(trackId: number, url: string): Promise<DecodedTrack> {
    const cached = this.map.get(trackId);
    if (cached) {
      this.touch(trackId);
      return Promise.resolve(cached);
    }
    const inflight = this.inflight.get(trackId);
    if (inflight) return inflight;

    const p = (async () => {
      const ab = await fetch(url).then((r) => r.arrayBuffer());
      const result = await decodeAndTrim(ab, this.ctx);
      const decoded: DecodedTrack = {
        buffer: result.buffer,
        meta: result.meta,
        trimmedHead: result.trimmedHead,
        trimmedTail: result.trimmedTail,
      };
      this.put(trackId, decoded);
      console.debug("[gapless] decoded", {
        trackId,
        durationS: decoded.buffer.duration.toFixed(3),
        head: decoded.trimmedHead,
        tail: decoded.trimmedTail,
        source: decoded.meta.source,
        autoTrimmed: result.decoderAutoTrimmed,
      });
      return decoded;
    })()
      .catch((e) => {
        console.warn("[gapless] decode failed", trackId, e);
        throw e;
      })
      .finally(() => {
        this.inflight.delete(trackId);
      });

    this.inflight.set(trackId, p);
    return p;
  }

  private put(trackId: number, v: DecodedTrack): void {
    if (this.map.has(trackId)) {
      this.touch(trackId);
      this.map.set(trackId, v);
      return;
    }
    while (this.order.length >= this.capacity) {
      const evict = this.order.shift();
      if (evict != null) this.map.delete(evict);
    }
    this.order.push(trackId);
    this.map.set(trackId, v);
  }

  private touch(trackId: number): void {
    const i = this.order.indexOf(trackId);
    if (i >= 0) this.order.splice(i, 1);
    this.order.push(trackId);
  }

  has(trackId: number): boolean {
    return this.map.has(trackId);
  }

  clear(): void {
    this.map.clear();
    this.order = [];
    this.inflight.clear();
  }
}
