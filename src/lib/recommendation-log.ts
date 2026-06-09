"use client";

import type { TrackInfo } from "./tauri";
import { cache } from "./tauri";

const KEY = "recommendation_log_v1";
const MAX_EVENTS = 800;
const DAY_S = 24 * 3600;

export type RecommendationSource = "pet" | "auto" | "radio" | "search";

export type RecommendationEvent = {
  trackId: number;
  ts: number;
  source: RecommendationSource;
  /**
   * 推荐时的主艺人名 —— v2 新增,用来做 artist-level fatigue。
   * 老事件没这个字段,读取时按缺失处理。
   */
  artist?: string;
};

export type RecentRecommendationContext = {
  last24hTrackIds: Set<number>;
  last7dTrackIds: Set<number>;
  /** 近 24h 每个艺人(normalize 后)被推荐的次数 */
  last24hArtistCounts: Map<string, number>;
  /** 近 7d 同上 */
  last7dArtistCounts: Map<string, number>;
};

/**
 * artist key:跟 track-dedupe/pet-agent 的 normalizeArtistKey 等价 —— 全部小写,
 * 去掉常见标点。不引入 track-dedupe 的依赖(那边还涉及 song title 规范),
 * 这里只关心 artist,保持本模块自包含。
 */
export function normalizeArtistKeyForLog(name: string): string {
  return name
    .toLowerCase()
    .replace(/[\s'"`·・\-－—_,，。.、!?！？]+/g, "");
}

let buffer: RecommendationEvent[] | null = null;
let writeTimer: number | null = null;

async function ensureBuffer(): Promise<RecommendationEvent[]> {
  if (buffer) return buffer;
  try {
    const raw = await cache.getState(KEY);
    if (raw) {
      const parsed = JSON.parse(raw);
      if (Array.isArray(parsed)) {
        buffer = parsed as RecommendationEvent[];
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
      console.debug("[pipo] recommendation_log 落盘失败", e);
    });
  }, 50);
}

/**
 * 落事件 —— 支持两种入参形式:
 *   - number[] 旧调用:不带艺人,fatigue 算不到只能按 trackId 推断不出来
 *   - TrackInfo[] 推荐用形式:把主艺人写进事件,后续可以按 artist 聚合
 *
 * 两种形式并存是为了不破坏现有 caller。新代码请尽量传 TrackInfo[]。
 */
export async function logRecommendations(
  tracks: number[] | TrackInfo[],
  source: RecommendationSource,
): Promise<void> {
  if (tracks.length === 0) return;
  const buf = await ensureBuffer();
  const now = Math.floor(Date.now() / 1000);
  const seen = new Set<number>();
  for (const item of tracks) {
    if (typeof item === "number") {
      if (seen.has(item)) continue;
      seen.add(item);
      buf.push({ trackId: item, ts: now, source });
    } else {
      if (seen.has(item.id)) continue;
      seen.add(item.id);
      const artist = item.artists[0]?.name;
      buf.push({ trackId: item.id, ts: now, source, artist });
    }
  }
  scheduleFlush();
}

export async function readRecommendationLog(): Promise<RecommendationEvent[]> {
  return ensureBuffer().then((b) => b.slice());
}

export async function readRecentRecommendationContext(): Promise<RecentRecommendationContext> {
  const events = await readRecommendationLog();
  const now = Math.floor(Date.now() / 1000);
  const last24hTrackIds = new Set<number>();
  const last7dTrackIds = new Set<number>();
  const last24hArtistCounts = new Map<string, number>();
  const last7dArtistCounts = new Map<string, number>();

  for (const ev of events) {
    const ageS = now - ev.ts;
    const in7d = ageS <= 7 * DAY_S;
    const in24h = ageS <= DAY_S;
    if (in24h) last24hTrackIds.add(ev.trackId);
    if (in7d) last7dTrackIds.add(ev.trackId);

    if (!ev.artist) continue; // 老事件没 artist 字段,跳过 artist 聚合
    const key = normalizeArtistKeyForLog(ev.artist);
    if (!key) continue;
    if (in24h) {
      last24hArtistCounts.set(key, (last24hArtistCounts.get(key) ?? 0) + 1);
    }
    if (in7d) {
      last7dArtistCounts.set(key, (last7dArtistCounts.get(key) ?? 0) + 1);
    }
  }

  return {
    last24hTrackIds,
    last7dTrackIds,
    last24hArtistCounts,
    last7dArtistCounts,
  };
}
