"use client";

/**
 * 用户"已蒸馏的库"—— 所有进过 taste profile 的歌单里去重的曲目。
 *
 * AI 宠物排歌单从这个池子里挑（不再去 Netease 库外搜）。
 *
 * 内存里 memo 一份（同一 session 不重复读 sqlite），10 分钟过期。
 */

import { cache, type TrackInfo } from "./tauri";
import { loadTasteProfile } from "./taste-profile";

let memo: { at: number; tracks: TrackInfo[] } | null = null;
const MEMO_TTL_MS = 10 * 60 * 1000;

export async function loadLibrary(): Promise<TrackInfo[]> {
  if (memo && Date.now() - memo.at < MEMO_TTL_MS) return memo.tracks;

  const profile = await loadTasteProfile();
  if (!profile || profile.sourcePlaylistIds.length === 0) {
    memo = { at: Date.now(), tracks: [] };
    return [];
  }

  const seen = new Set<number>();
  const out: TrackInfo[] = [];
  for (const pid of profile.sourcePlaylistIds) {
    const detail = await cache.getPlaylistDetail(pid).catch(() => null);
    if (!detail) continue;
    for (const t of detail.tracks) {
      if (seen.has(t.id)) continue;
      seen.add(t.id);
      out.push(t);
    }
  }
  memo = { at: Date.now(), tracks: out };
  return out;
}

/**
 * 库太大时（>800）做采样：靠前 60% 取最近常听的，
 * 后 40% 随机抽。喂 prompt 时既保证 AI 看到"你常听的"，
 * 也留尾巴让它能挑冷门 cut。
 *
 * 当前没接 behavior-log 的频次统计 —— v1 直接随机采样足够；
 * 以后接上再加权排序。
 */
export function sampleForPrompt(library: TrackInfo[], limit = 800): TrackInfo[] {
  if (library.length <= limit) return library;
  const out: TrackInfo[] = [];
  const used = new Set<number>();
  while (out.length < limit) {
    const idx = Math.floor(Math.random() * library.length);
    if (used.has(idx)) continue;
    used.add(idx);
    out.push(library[idx]);
  }
  return out;
}

export function clearLibraryMemo(): void {
  memo = null;
}
