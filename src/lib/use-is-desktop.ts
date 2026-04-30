"use client";

import { useEffect, useState } from "react";

/** 720px 是手机/桌面分界 —— 跟 PlayerCard 的沉浸式布局阈值一致。 */
export function useIsDesktop(): boolean {
  const [isD, setIsD] = useState(false);
  useEffect(() => {
    const update = () => setIsD(window.innerWidth >= 720);
    update();
    window.addEventListener("resize", update);
    return () => window.removeEventListener("resize", update);
  }, []);
  return isD;
}
