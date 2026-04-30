"use client";

/**
 * 封面边缘色采样 + 明暗判断 —— 让歌词页 / 歌单页 / 任何"以封面为氛围"的页面
 * 共用同一套配色逻辑：
 *
 *   useCoverEdgeColors(url)  ：拿到顶 / 底 / 右三边的平均 RGB，前端按布局取需要的边
 *   computeTone(rgb)         ：粗暴算亮度 → "dark" 或 "light"，决定文字色
 *   pickFg / pickFgDim       ：根据 tone 直接给一对前景色
 *
 * 跨域：claudio-cdn:// scheme 的 Rust handler 已经放了 ACAO:*，crossOrigin=anonymous
 * 后 canvas 不会 taint，可以 getImageData。
 */

import { useEffect, useState } from "react";

export type EdgeColors = { top: string | null; bottom: string | null; right: string | null };

export function useCoverEdgeColors(url: string | null): EdgeColors {
  const [colors, setColors] = useState<EdgeColors>({ top: null, bottom: null, right: null });

  useEffect(() => {
    if (!url) {
      setColors({ top: null, bottom: null, right: null });
      return;
    }
    let cancelled = false;
    const img = new Image();
    img.crossOrigin = "anonymous";
    img.onload = () => {
      if (cancelled) return;
      try {
        const W = 32,
          H = 32;
        const canvas = document.createElement("canvas");
        canvas.width = W;
        canvas.height = H;
        const ctx = canvas.getContext("2d");
        if (!ctx) return;
        ctx.drawImage(img, 0, 0, W, H);
        const sample = (yStart: number, yEnd: number) => {
          const data = ctx.getImageData(0, yStart, W, yEnd - yStart).data;
          let r = 0,
            g = 0,
            b = 0,
            n = 0;
          for (let i = 0; i < data.length; i += 4) {
            r += data[i]!;
            g += data[i + 1]!;
            b += data[i + 2]!;
            n++;
          }
          if (n === 0) return null;
          return `${Math.round(r / n)}, ${Math.round(g / n)}, ${Math.round(b / n)}`;
        };
        const top = sample(0, 5);
        const bottom = sample(H - 5, H);
        const sampleRight = () => {
          let r = 0,
            g = 0,
            b = 0,
            n = 0;
          const data = ctx.getImageData(W - 5, 0, 5, H).data;
          for (let i = 0; i < data.length; i += 4) {
            r += data[i]!;
            g += data[i + 1]!;
            b += data[i + 2]!;
            n++;
          }
          if (n === 0) return null;
          return `${Math.round(r / n)}, ${Math.round(g / n)}, ${Math.round(b / n)}`;
        };
        const right = sampleRight();
        if (cancelled) return;
        setColors({ top, bottom, right });
      } catch (e) {
        console.debug("[claudio] cover edge color sample failed", e);
      }
    };
    img.onerror = () => {};
    img.src = url;
    return () => {
      cancelled = true;
    };
  }, [url]);

  return colors;
}

export function computeTone(rgbStr: string | null): "light" | "dark" {
  if (!rgbStr) return "light";
  const parts = rgbStr.split(",").map((s) => parseInt(s.trim(), 10));
  if (parts.length < 3 || parts.some((n) => !Number.isFinite(n))) return "light";
  const [r, g, b] = parts as [number, number, number];
  const luma = 0.299 * r + 0.587 * g + 0.114 * b;
  // 145 是经验阈值：luma > 145 = bg 偏亮，文字用深色
  return luma > 145 ? "dark" : "light";
}

/** 主前景色（满 alpha 用） */
export function pickFg(tone: "light" | "dark"): string {
  return tone === "dark" ? "rgba(0, 0, 0, 0.92)" : "rgba(255, 255, 255, 0.96)";
}

/** 次级前景色（副标 / 时长等弱化文字） */
export function pickFgDim(tone: "light" | "dark"): string {
  return tone === "dark" ? "rgba(0, 0, 0, 0.55)" : "rgba(255, 255, 255, 0.62)";
}
