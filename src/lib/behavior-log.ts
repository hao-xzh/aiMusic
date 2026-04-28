"use client";

/**
 * 听歌行为日志 —— 给画像做"近期反馈"的输入。
 *
 * 思路：
 *   - 每次曲目转场都打一条记录：completed（自动播完）/ skipped（早期跳过）/ manual_cut（中途切走）
 *   - 写到 cache 一份 JSON 数组（capped 500 条，老的丢掉），不打扰播放主路径
 *   - 后续画像增量更新时，把这份日志压成"近期偏好提示"喂给 AI ——
 *     "用户最近反复跳过 X 风格的，多听了 Y 风格的"
 *
 * 这一份日志是 Provider-agnostic 的纯 JSON，跟画像一样能跨 AI provider 流转。
 */

import { cache } from "./tauri";

const KEY = "behavior_log_v1";
const MAX_EVENTS = 500;
const DAY_S = 24 * 3600;

export type BehaviorEventKind =
  | "completed"
  | "skipped"
  | "manual_cut"
  | "liked"      // 用户点了喜欢（不切歌，纯正向信号）
  | "disliked";  // 用户点了不喜欢（伴随切歌；负反馈信号）

export type BehaviorEvent = {
  trackId: number;
  title: string;
  artist: string;
  ts: number; // unix seconds
  kind: BehaviorEventKind;
  /** 切走时的播放位置（秒）—— completed 时无意义留 undefined */
  positionSec?: number;
  /** 曲目总时长（秒） */
  durationSec?: number;
};

export type RecentPlayContext = {
  last24hTrackIds: Set<number>;
  last7dTrackIds: Set<number>;
};

// ---- 内存缓冲 + 节流写盘 ----
//
// 一首歌结束就立刻写盘其实没必要 —— 几个 setState 的工夫就能积累几条事件。
// 用 50ms 防抖把多条压一次写盘，避免和 timeupdate 抢 SQLite 锁。
let buffer: BehaviorEvent[] | null = null;
let writeTimer: number | null = null;

async function ensureBuffer(): Promise<BehaviorEvent[]> {
  if (buffer) return buffer;
  try {
    const raw = await cache.getState(KEY);
    if (raw) {
      const parsed = JSON.parse(raw);
      if (Array.isArray(parsed)) {
        buffer = parsed as BehaviorEvent[];
        return buffer;
      }
    }
  } catch {
    /* 坏缓存忽略 */
  }
  buffer = [];
  return buffer;
}

function scheduleFlush() {
  if (writeTimer != null) window.clearTimeout(writeTimer);
  writeTimer = window.setTimeout(() => {
    writeTimer = null;
    if (!buffer) return;
    const trimmed = buffer.slice(-MAX_EVENTS);
    if (trimmed.length !== buffer.length) buffer = trimmed;
    cache.setState(KEY, JSON.stringify(buffer)).catch((e) => {
      console.debug("[claudio] behavior_log 落盘失败", e);
    });
  }, 50);
}

export async function logBehavior(ev: Omit<BehaviorEvent, "ts">): Promise<void> {
  const buf = await ensureBuffer();
  buf.push({ ...ev, ts: Math.floor(Date.now() / 1000) });
  scheduleFlush();
}

export async function readBehaviorLog(): Promise<BehaviorEvent[]> {
  return ensureBuffer().then((b) => b.slice());
}

export async function clearBehaviorLog(): Promise<void> {
  buffer = [];
  await cache.setState(KEY, "[]").catch(() => {});
}

export async function readRecentPlayContext(): Promise<RecentPlayContext> {
  const events = await readBehaviorLog();
  const now = Math.floor(Date.now() / 1000);
  const last24hTrackIds = new Set<number>();
  const last7dTrackIds = new Set<number>();

  for (const ev of events) {
    const ageS = now - ev.ts;
    if (ageS > 7 * DAY_S) continue;
    if (ageS <= DAY_S) last24hTrackIds.add(ev.trackId);
    last7dTrackIds.add(ev.trackId);
  }

  return { last24hTrackIds, last7dTrackIds };
}

// ---- 派生统计：用于 UI 显示 + 画像 prompt 上下文 ----

export type BehaviorStats = {
  total: number;
  completed: number;
  skipped: number;
  manualCuts: number;
  /** 完成率（completed / total）—— 整体接受度 */
  completionRate: number;
  /** 高跳过艺人（skipped 占比 ≥ 50% 且至少出现 3 次） */
  skipHotArtists: string[];
  /** 高完成艺人（completed 占比 ≥ 70% 且至少出现 3 次） */
  loveArtists: string[];
};

export function summarize(events: BehaviorEvent[]): BehaviorStats {
  const total = events.length;
  const completed = events.filter((e) => e.kind === "completed").length;
  const skipped = events.filter((e) => e.kind === "skipped").length;
  const manualCuts = events.filter((e) => e.kind === "manual_cut").length;

  // 按艺人聚合
  const byArtist = new Map<string, { total: number; completed: number; skipped: number }>();
  for (const e of events) {
    const k = e.artist;
    const a = byArtist.get(k) ?? { total: 0, completed: 0, skipped: 0 };
    a.total++;
    if (e.kind === "completed") a.completed++;
    if (e.kind === "skipped") a.skipped++;
    byArtist.set(k, a);
  }

  const skipHotArtists: string[] = [];
  const loveArtists: string[] = [];
  for (const [name, s] of byArtist.entries()) {
    if (s.total < 3) continue;
    if (s.skipped / s.total >= 0.5) skipHotArtists.push(name);
    if (s.completed / s.total >= 0.7) loveArtists.push(name);
  }

  return {
    total,
    completed,
    skipped,
    manualCuts,
    completionRate: total > 0 ? completed / total : 0,
    skipHotArtists,
    loveArtists,
  };
}
