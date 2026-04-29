"use client";

/**
 * 库音频分析进度的全局发布订阅 —— 蒸馏页跑分析、设置页看进度、layout 启动时
 * 自动续跑，三个地方共享一份状态，避免重复 fetch + decode。
 *
 * analyzeLibrary 本身已经是单例（inflight Promise），这里只是把进度回调升级成
 * 全局可观察的 store + 一个 React hook。
 */

import { useEffect, useState } from "react";
import type { TrackInfo } from "./tauri";
import {
  analyzeLibrary,
  isAnalysisInFlight,
  type BatchProgress,
} from "./library-analysis";

export type AnalysisState = {
  running: boolean;
  total: number;
  done: number;
  skipped: number;
  failed: number;
  /** 上次跑完的时间戳；空表示从未跑过 */
  lastFinishedAt: number | null;
};

const initial: AnalysisState = {
  running: false,
  total: 0,
  done: 0,
  skipped: 0,
  failed: 0,
  lastFinishedAt: null,
};

let state: AnalysisState = initial;
const listeners = new Set<(s: AnalysisState) => void>();

function emit() {
  for (const l of listeners) l(state);
}

function setState(patch: Partial<AnalysisState>) {
  state = { ...state, ...patch };
  emit();
}

export function subscribe(cb: (s: AnalysisState) => void): () => void {
  listeners.add(cb);
  cb(state);
  return () => {
    listeners.delete(cb);
  };
}

export function useAnalysisProgress(): AnalysisState {
  const [s, setS] = useState<AnalysisState>(state);
  useEffect(() => subscribe(setS), []);
  return s;
}

/** 把进度归零到 initial —— "清空历史"按钮调一下，UI 别还显示老的 N/M */
export function resetAnalysisState(): void {
  state = { ...initial };
  emit();
}

export function getAnalysisState(): AnalysisState {
  return state;
}

/**
 * 启动（或继续）整库音频分析。
 *
 * - 已经在跑：直接返回 in-flight promise，不重复启动
 * - 库是空的：no-op
 * - 已分析的歌：内部 cache 命中，跳过
 *
 * UI 层订阅 useAnalysisProgress() 看进度。
 */
export async function startBackgroundAnalysis(
  library: TrackInfo[],
): Promise<void> {
  if (library.length === 0) return;
  if (isAnalysisInFlight()) return;

  setState({
    running: true,
    total: library.length,
    done: 0,
    skipped: 0,
    failed: 0,
  });

  try {
    await analyzeLibrary(library, {
      concurrency: 3,
      onProgress: (p: BatchProgress) => {
        setState({
          running: true,
          total: p.total,
          done: p.done,
          skipped: p.skipped,
          failed: p.failed,
        });
      },
    });
    setState({ running: false, lastFinishedAt: Date.now() });
  } catch (e) {
    console.warn("[claudio] 库音频分析中断", e);
    setState({ running: false });
  }
}
