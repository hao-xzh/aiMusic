"use client";

import { useEffect, useRef } from "react";

/**
 * Waveform —— 图片里那条竖线波形条。
 * 读全局 window.__claudioAmp (0~1) 反映音乐大小；
 * 没音乐时有轻微的"呼吸"作为底噪，让界面不死。
 */
export function Waveform({
  bars = 64,
  height = 56,
  color = "#9be3c6",
  gap = 3,
  className,
  style,
}: {
  bars?: number;
  height?: number;
  color?: string;
  gap?: number;
  className?: string;
  style?: React.CSSProperties;
}) {
  const ref = useRef<HTMLCanvasElement | null>(null);
  const seeds = useRef<number[]>([]);

  // 为每个 bar 固定一个随机相位
  if (seeds.current.length !== bars) {
    seeds.current = Array.from({ length: bars }, () => Math.random() * Math.PI * 2);
  }

  useEffect(() => {
    const canvas = ref.current;
    if (!canvas) return;
    const ctx = canvas.getContext("2d");
    if (!ctx) return;

    const dpr = Math.max(1, Math.min(2, window.devicePixelRatio || 1));
    const resize = () => {
      const rect = canvas.getBoundingClientRect();
      canvas.width = Math.floor(rect.width * dpr);
      canvas.height = Math.floor(rect.height * dpr);
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    };
    resize();
    const ro = new ResizeObserver(resize);
    ro.observe(canvas);

    let raf = 0;
    let t = 0;
    const draw = () => {
      t += 1 / 60;
      const rect = canvas.getBoundingClientRect();
      const w = rect.width;
      const h = rect.height;
      ctx.clearRect(0, 0, w, h);

      const amp =
        typeof (window as any).__claudioAmp === "number"
          ? Math.max(0, Math.min(1, (window as any).__claudioAmp))
          : 0;

      const barW = Math.max(2, (w - gap * (bars - 1)) / bars);
      for (let i = 0; i < bars; i++) {
        const phase = seeds.current[i];
        // 基础呼吸 + 振幅响应 + 边缘衰减（中间高，两头低，像图里那样）
        const edge = 1 - Math.pow((i / (bars - 1) - 0.5) * 2, 2) * 0.4;
        const base = 0.18 + 0.22 * (0.5 + 0.5 * Math.sin(t * 2.4 + phase));
        const ampPart = amp * (0.6 + 0.4 * Math.sin(t * 6 + phase));
        const v = Math.min(1, (base + ampPart) * edge);
        const barH = Math.max(2, v * h);
        const x = i * (barW + gap);
        const y = (h - barH) / 2;

        ctx.fillStyle = color;
        ctx.globalAlpha = 0.5 + v * 0.5;
        roundRect(ctx, x, y, barW, barH, Math.min(barW, 4) / 2);
        ctx.fill();
      }
      ctx.globalAlpha = 1;
      raf = requestAnimationFrame(draw);
    };
    raf = requestAnimationFrame(draw);

    return () => {
      cancelAnimationFrame(raf);
      ro.disconnect();
    };
  }, [bars, color, gap]);

  return (
    <canvas
      ref={ref}
      className={className}
      style={{ width: "100%", height, display: "block", ...style }}
    />
  );
}

function roundRect(
  ctx: CanvasRenderingContext2D,
  x: number,
  y: number,
  w: number,
  h: number,
  r: number
) {
  ctx.beginPath();
  ctx.moveTo(x + r, y);
  ctx.arcTo(x + w, y, x + w, y + h, r);
  ctx.arcTo(x + w, y + h, x, y + h, r);
  ctx.arcTo(x, y + h, x, y, r);
  ctx.arcTo(x, y, x + w, y, r);
  ctx.closePath();
}
