"use client";

/**
 * 启动后 8 秒自动续跑库音频分析。
 *
 * - 库为空：no-op
 * - 已经在跑：no-op（startBackgroundAnalysis 内部单例）
 * - 已分析的歌：内部 cache 命中跳过，所以"全分析完了"重启 app 不会重复劳动
 *
 * 8 秒延迟是给应用启动让路：让 Netease 登录态、AdaptiveDotField、PlayerProvider
 * 等先吃带宽 + CPU。
 */

import { useEffect } from "react";
import { loadLibrary } from "@/lib/library";
import { startBackgroundAnalysis } from "@/lib/analysis-progress";

const START_DELAY_MS = 8000;

export function AnalysisAutoResume() {
  useEffect(() => {
    let alive = true;
    const t = window.setTimeout(async () => {
      if (!alive) return;
      try {
        const lib = await loadLibrary();
        if (!alive || lib.length === 0) return;
        void startBackgroundAnalysis(lib);
      } catch (e) {
        console.debug("[claudio] 自动续跑分析失败", e);
      }
    }, START_DELAY_MS);
    return () => {
      alive = false;
      window.clearTimeout(t);
    };
  }, []);

  return null;
}
