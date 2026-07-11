"use client";

import { useEffect } from "react";
import { detectPlatformInfo } from "@/lib/use-platform";

/**
 * 在 <html> 上挂 data-platform，让 globals.css 按平台分支渲染。
 * 旧版逻辑住在 Nav.tsx 里，去 nav 化后迁过来。
 *   - Windows Tauri：全局留 32px 顶部，避开 decorum 标题栏 overlay
 *   - Android：状态栏沉浸；env(safe-area-inset-top) 由各页 floating 按钮自己处理
 *   - macOS Tauri：traffic-light 由原生 overlay 处理，普通浏览器不注入标题栏留白
 */
export function PlatformTag() {
  useEffect(() => {
    const { platform, runtime } = detectPlatformInfo();
    document.documentElement.dataset.platform = platform ?? "unknown";
    document.documentElement.dataset.runtime = runtime;
  }, []);
  return null;
}
