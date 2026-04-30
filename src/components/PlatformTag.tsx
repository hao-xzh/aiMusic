"use client";

import { useEffect } from "react";

/**
 * 在 <html> 上挂 data-platform，让 globals.css 按平台分支渲染。
 * 旧版逻辑住在 Nav.tsx 里，去 nav 化后迁过来。
 *   - Windows：全局留 32px 顶部，避开 decorum 标题栏 overlay
 *   - Android：状态栏沉浸；env(safe-area-inset-top) 由各页 floating 按钮自己处理
 *   - macOS：traffic-light 由 Tauri overlay 模式处理，不需要前端干预
 */
export function PlatformTag() {
  useEffect(() => {
    if (typeof navigator === "undefined") return;
    const ua = navigator.userAgent;
    if (/Android/i.test(ua)) {
      document.documentElement.dataset.platform = "android";
    } else if (/Windows/i.test(ua)) {
      document.documentElement.dataset.platform = "windows";
    } else if (/Macintosh|Mac OS X/i.test(ua)) {
      // Mac 上 titleBarStyle: "Overlay" 让红黄绿三个原生键贴在窗口左上 ~78x28
      // 透明覆盖。floating 按钮如果不躲开这个区域就会被键盖住。
      document.documentElement.dataset.platform = "mac";
    }
  }, []);
  return null;
}
