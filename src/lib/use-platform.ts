"use client";

import { useEffect, useState } from "react";

export type Platform = "android" | "windows" | "mac" | null;

/** 跟 PlatformTag 写到 <html> 的 data-platform 同步。SSR 阶段返回 null。 */
export function usePlatform(): Platform {
  const [p, setP] = useState<Platform>(null);
  useEffect(() => {
    const v = document.documentElement.dataset.platform;
    if (v === "android" || v === "windows" || v === "mac") setP(v);
  }, []);
  return p;
}
