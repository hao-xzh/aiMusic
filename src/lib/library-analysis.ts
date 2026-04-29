"use client";

/**
 * 批量预分析库 —— 把整个蒸馏库的歌全部跑一遍 audio-analysis，
 * 让宠物排歌单时一开口就有真 BPM/energy 可用。
 *
 * 节流：同一时刻最多 N 个并行（fetch + decode 都吃带宽和 CPU）。
 * 进度回调：UI 层订阅，能显示"已分析 12/247"。
 *
 * 已缓存的歌跳过；不带 url 的（拉直链失败）跳过。
 *
 * 全局单例：同一会话只允许一个 batch in-flight，重复调直接返回当前 promise。
 */

import type { TrackInfo } from "./tauri";
import { audio, netease } from "./tauri";
import { cdn } from "./cdn";
import { loadAnalysis, getOrAnalyze } from "./audio-analysis";

export type BatchProgress = {
  total: number;
  done: number;
  skipped: number;     // 已缓存
  failed: number;
};

export type BatchOptions = {
  /** 同时跑几个分析（默认 3 —— 太高 fetch + decode 吃光带宽） */
  concurrency?: number;
  /** 取直链时一批拉几个 id（Netease 接口支持批量） */
  urlBatch?: number;
  /** 进度回调 */
  onProgress?: (p: BatchProgress) => void;
  /** 中止信号 */
  signal?: AbortSignal;
};

let inflight: Promise<BatchProgress> | null = null;

export async function analyzeLibrary(
  library: TrackInfo[],
  opts: BatchOptions = {},
): Promise<BatchProgress> {
  if (inflight) return inflight;

  const concurrency = opts.concurrency ?? 3;
  const urlBatch = opts.urlBatch ?? 30;
  const progress: BatchProgress = {
    total: library.length,
    done: 0,
    skipped: 0,
    failed: 0,
  };

  inflight = (async () => {
    try {
      // 1) 先按 id 把已缓存的剔掉（避免无谓拉直链）
      const pending: TrackInfo[] = [];
      for (const t of library) {
        if (opts.signal?.aborted) break;
        const cached = await loadAnalysis(t.id);
        if (cached) {
          progress.skipped++;
          progress.done++;
          opts.onProgress?.({ ...progress });
          continue;
        }
        pending.push(t);
      }

      // 2) 分块批量取直链。
      //
      // 关键决策：
      //   - 音质用 `exhigh`（320kbps mp3）而不是 lossless：Windows WebView2
      //     的 decodeAudioData 解 FLAC 不可靠（实测 5440 首全挂），mp3 各平台都稳
      //   - URL 走 `cdn(u.url)` 而不是 `wrapAudioUrl(...)`：库扫描**不写**
      //     播放缓存。否则 VIP 用户后续手动播放时 cache 命中拿到 mp3 而不是
      //     lossless，是隐式音质回退。播放缓存只由实际播放路径填充
      //   - 副作用：扫描完没预热到磁盘缓存，跟 phase A 之前一样，第一次手动
      //     播放还是要现拉。但音质零回退
      const idToUrl = new Map<number, string>();
      const idToRaw = new Map<number, string>();
      for (let i = 0; i < pending.length; i += urlBatch) {
        if (opts.signal?.aborted) break;
        const slice = pending.slice(i, i + urlBatch);
        try {
          const urls = await netease.songUrls(slice.map((t) => t.id), "exhigh");
          for (const u of urls) {
            if (u.url) {
              idToUrl.set(u.id, cdn(u.url)); // JS 端走 claudio-cdn://（referer 代理，无字节缓存）
              idToRaw.set(u.id, u.url);       // 给 native cmd 用，方便它直接拉上游
            }
          }
        } catch (e) {
          console.debug("[claudio] batch songUrls 失败", e);
        }
      }

      // 3) 并发 N 个跑分析
      let cursor = 0;
      const workers: Promise<void>[] = [];
      for (let w = 0; w < concurrency; w++) {
        workers.push(
          (async () => {
            while (true) {
              if (opts.signal?.aborted) return;
              const idx = cursor++;
              if (idx >= pending.length) return;
              const t = pending[idx];
              const url = idToUrl.get(t.id);
              if (!url) {
                progress.failed++;
                progress.done++;
                opts.onProgress?.({ ...progress });
                continue;
              }
              const rawUrl = idToRaw.get(t.id) ?? url;
              // JS 和 native 各自跑各自的，互不影响：一个失败不拖累另一个的进度。
              // 只有两边都失败 / 拉不到 url 时才计 failed。
              const [jsOk, nativeOk] = await Promise.all([
                (async () => {
                  try {
                    const a = await getOrAnalyze(t.id, url);
                    return !!a;
                  } catch (e) {
                    console.debug("[claudio] JS analyze 失败", t.id, e);
                    return false;
                  }
                })(),
                (async () => {
                  try {
                    // cacheBytes=false：分析完字节就扔，不污染播放缓存
                    await audio.getFeatures(t.id, rawUrl, false);
                    return true;
                  } catch (e) {
                    console.debug("[claudio] native features 失败", t.id, e);
                    return false;
                  }
                })(),
              ]);
              if (!jsOk && !nativeOk) progress.failed++;
              progress.done++;
              opts.onProgress?.({ ...progress });
            }
          })(),
        );
      }
      await Promise.all(workers);
      return progress;
    } finally {
      inflight = null;
    }
  })();

  return inflight;
}

export function isAnalysisInFlight(): boolean {
  return inflight !== null;
}
