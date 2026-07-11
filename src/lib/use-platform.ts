"use client";

import { useEffect, useState } from "react";

export type Platform = "android" | "windows" | "mac" | "ios" | "linux" | "unknown" | null;
export type AppRuntime = "tauri" | "browser";

export type PlatformInfo = {
  platform: Platform;
  runtime: AppRuntime | null;
};

export function detectPlatformInfo(): {
  platform: Exclude<Platform, null>;
  runtime: AppRuntime;
} {
  if (typeof window === "undefined" || typeof navigator === "undefined") {
    return { platform: "unknown", runtime: "browser" };
  }
  const runtime: AppRuntime = "__TAURI_INTERNALS__" in window ? "tauri" : "browser";
  const nav = navigator as Navigator & { userAgentData?: { platform?: string } };
  const platformHint = nav.userAgentData?.platform ?? navigator.platform ?? "";
  const ua = navigator.userAgent ?? "";
  const signal = `${platformHint} ${ua}`;
  if (/Android/i.test(signal)) return { platform: "android", runtime };
  if (/Windows|Win32|Win64/i.test(signal)) return { platform: "windows", runtime };
  if (/iPhone|iPad|iPod/i.test(signal)) return { platform: "ios", runtime };
  if (/Macintosh|Mac OS X|MacIntel/i.test(signal)) return { platform: "mac", runtime };
  if (/Linux/i.test(signal)) return { platform: "linux", runtime };
  return { platform: "unknown", runtime };
}

export function usePlatformInfo(): PlatformInfo {
  const [info, setInfo] = useState<PlatformInfo>({ platform: null, runtime: null });
  useEffect(() => setInfo(detectPlatformInfo()), []);
  return info;
}

/** 跟 PlatformTag 写到 <html> 的 data-platform 同步。SSR 阶段返回 null。 */
export function usePlatform(): Platform {
  return usePlatformInfo().platform;
}
