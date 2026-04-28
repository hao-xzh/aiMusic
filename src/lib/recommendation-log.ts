"use client";

import { cache } from "./tauri";

const KEY = "recommendation_log_v1";
const MAX_EVENTS = 800;
const DAY_S = 24 * 3600;

export type RecommendationSource = "pet" | "auto" | "radio" | "search";

export type RecommendationEvent = {
  trackId: number;
  ts: number;
  source: RecommendationSource;
};

export type RecentRecommendationContext = {
  last24hTrackIds: Set<number>;
  last7dTrackIds: Set<number>;
};

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
      console.debug("[claudio] recommendation_log 落盘失败", e);
    });
  }, 50);
}

export async function logRecommendations(
  trackIds: number[],
  source: RecommendationSource,
): Promise<void> {
  if (trackIds.length === 0) return;
  const buf = await ensureBuffer();
  const now = Math.floor(Date.now() / 1000);
  const seen = new Set<number>();
  for (const trackId of trackIds) {
    if (seen.has(trackId)) continue;
    seen.add(trackId);
    buf.push({ trackId, ts: now, source });
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

  for (const ev of events) {
    const ageS = now - ev.ts;
    if (ageS <= DAY_S) last24hTrackIds.add(ev.trackId);
    if (ageS <= 7 * DAY_S) last7dTrackIds.add(ev.trackId);
  }

  return { last24hTrackIds, last7dTrackIds };
}
