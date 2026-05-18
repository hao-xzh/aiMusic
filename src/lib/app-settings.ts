"use client";

/**
 * 全局轻量偏好（外观、视效…）。
 *
 * 跟播放状态分开：这里只放"刷一下立刻生效、不需要进 player reducer"的开关。
 * 用 localStorage 持久化；同一标签页内多个组件之间通过自定义事件互相同步，
 * 这样设置页改了之后，AdaptiveDotField 会立刻重渲染。
 */

import { useCallback, useEffect, useState } from "react";

const STORAGE_KEY = "app_settings_v1";
const CHANGE_EVENT = "claudio:app-settings-changed";

export type AppSettings = {
  /** 隐藏全屏点阵纹理（封面模糊底层不受影响） */
  hideDotPattern: boolean;
  /** 隐藏右下角 AI 宠物圆球 / 绳子，仅保留封面短提示 */
  hideAiPetOrb: boolean;
  /** 允许 Claudio 主动给短旁白 / 封面提示 */
  aiNarration: boolean;
  /** 启动后按当下时段主动规划一段播放 session */
  smartSessionPlanner: boolean;
  /** 工作时段无播放时主动安排低打扰电台 */
  workdayAutoplay: boolean;
  /** 午休时段自动压低能量 */
  lunchRelaxMode: boolean;
  /** 深夜自动降低推荐节奏 */
  lateNightCalmMode: boolean;
  /** 长期执行的自定义电台规则 */
  promptedRadioRule: string;
};

const DEFAULTS: AppSettings = {
  hideDotPattern: false,
  hideAiPetOrb: true,
  aiNarration: false,
  smartSessionPlanner: true,
  workdayAutoplay: true,
  lunchRelaxMode: false,
  lateNightCalmMode: true,
  promptedRadioRule: "",
};

export function getAppSettingsSnapshot(): AppSettings {
  if (typeof window === "undefined") return DEFAULTS;
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    if (!raw) return DEFAULTS;
    const parsed = JSON.parse(raw);
    return { ...DEFAULTS, ...parsed };
  } catch {
    return DEFAULTS;
  }
}

function writeToStorage(next: AppSettings) {
  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(next));
  } catch {
    // localStorage 满了 / 被禁用，吞掉 —— 设置只是体感偏好，丢就丢
  }
  window.dispatchEvent(new CustomEvent(CHANGE_EVENT));
}

export function useAppSettings(): [AppSettings, (patch: Partial<AppSettings>) => void] {
  // SSR / 首帧用默认值，挂载后再读 localStorage，避免 hydration mismatch
  const [settings, setSettings] = useState<AppSettings>(DEFAULTS);

  useEffect(() => {
    setSettings(getAppSettingsSnapshot());
    const sync = () => setSettings(getAppSettingsSnapshot());
    window.addEventListener(CHANGE_EVENT, sync);
    // 跨标签页（多窗口 Tauri 场景）也同步一下
    window.addEventListener("storage", sync);
    return () => {
      window.removeEventListener(CHANGE_EVENT, sync);
      window.removeEventListener("storage", sync);
    };
  }, []);

  const update = useCallback((patch: Partial<AppSettings>) => {
    const next = { ...getAppSettingsSnapshot(), ...patch };
    writeToStorage(next);
    setSettings(next);
  }, []);

  return [settings, update];
}
