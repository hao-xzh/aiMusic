"use client";

import { useEffect, useRef } from "react";

/**
 * DotText —— 任意文字 → 点阵渲染
 *
 * 原理：用离屏 canvas 把文字画一遍，按网格取样，像素亮度超过阈值就在主 canvas 上画一个点。
 * 同时支持 hover 起伏。
 */

export type DotTextProps = {
  text: string;
  fontSize?: number;
  /** 采样间隔（小 = 点更密、更清晰） */
  grid?: number;
  dotRadius?: number;
  color?: string;
  /** 是否发光（推荐 true，像电台 LED） */
  glow?: boolean;
  font?: string;
  hoverRipple?: boolean;
  className?: string;
  style?: React.CSSProperties;
};

export function DotText({
  text,
  fontSize = 96,
  grid = 6,
  dotRadius = 2.4,
  color = "#9be3c6", // Claudio 招牌薄荷绿
  glow = true,
  font = '"SF Pro Display", "Inter", "Helvetica Neue", Arial, sans-serif',
  hoverRipple = true,
  className,
  style,
}: DotTextProps) {
  const ref = useRef<HTMLCanvasElement | null>(null);
  const stateRef = useRef({ mx: -9999, my: -9999, t: 0 });

  useEffect(() => {
    const canvas = ref.current;
    if (!canvas) return;
    const ctx = canvas.getContext("2d");
    if (!ctx) return;

    const dpr = Math.max(1, Math.min(2, window.devicePixelRatio || 1));
    const fontSpec = `800 ${fontSize}px ${font}`;

    // 先量文本尺寸
    const measureCtx = document.createElement("canvas").getContext("2d")!;
    measureCtx.font = fontSpec;
    const m = measureCtx.measureText(text);
    const padX = fontSize * 0.3;
    const padY = fontSize * 0.4;
    const cssW = Math.ceil(m.width + padX * 2);
    const cssH = Math.ceil(fontSize * 1.25 + padY * 0.5);

    canvas.style.width = `${cssW}px`;
    canvas.style.height = `${cssH}px`;
    canvas.width = Math.floor(cssW * dpr);
    canvas.height = Math.floor(cssH * dpr);
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);

    // 离屏画一遍文字
    const off = document.createElement("canvas");
    off.width = Math.floor(cssW * dpr);
    off.height = Math.floor(cssH * dpr);
    const offCtx = off.getContext("2d", { willReadFrequently: true })!;
    offCtx.setTransform(dpr, 0, 0, dpr, 0, 0);
    offCtx.fillStyle = "#fff";
    offCtx.font = fontSpec;
    offCtx.textBaseline = "middle";
    offCtx.fillText(text, padX, cssH / 2);

    const img = offCtx.getImageData(0, 0, off.width, off.height);
    const pixels = img.data;

    // 采样：哪些格子该亮
    type Cell = { x: number; y: number; lit: boolean };
    const cells: Cell[] = [];
    for (let y = grid / 2; y < cssH; y += grid) {
      for (let x = grid / 2; x < cssW; x += grid) {
        const sx = Math.floor(x * dpr);
        const sy = Math.floor(y * dpr);
        const idx = (sy * off.width + sx) * 4;
        const alpha = pixels[idx + 3];
        cells.push({ x, y, lit: alpha > 100 });
      }
    }

    // 鼠标事件
    const onMove = (e: MouseEvent) => {
      const rect = canvas.getBoundingClientRect();
      stateRef.current.mx = e.clientX - rect.left;
      stateRef.current.my = e.clientY - rect.top;
    };
    const onLeave = () => {
      stateRef.current.mx = -9999;
      stateRef.current.my = -9999;
    };
    if (hoverRipple) {
      canvas.addEventListener("mousemove", onMove);
      canvas.addEventListener("mouseleave", onLeave);
    }

    let raf = 0;
    const draw = () => {
      stateRef.current.t += 1 / 60;
      const t = stateRef.current.t;
      ctx.clearRect(0, 0, cssW, cssH);

      // 读音频振幅，让文字跟着音乐呼吸
      const amp =
        typeof (window as any).__claudioAmp === "number"
          ? Math.max(0, Math.min(1, (window as any).__claudioAmp))
          : 0;

      const { mx, my } = stateRef.current;
      const R = 120;
      const R2 = R * R;

      // Pass 1: 如果需要发光，先画一层软光晕（透明度低、模糊大）
      if (glow) {
        ctx.save();
        ctx.shadowColor = color;
        ctx.shadowBlur = 16 + amp * 10;
        for (const c of cells) {
          if (!c.lit) continue;
          ctx.globalAlpha = 0.55 + amp * 0.25;
          ctx.fillStyle = color;
          ctx.beginPath();
          ctx.arc(c.x, c.y, dotRadius * 0.9, 0, Math.PI * 2);
          ctx.fill();
        }
        ctx.restore();
      }

      // Pass 2: 清晰点层（no-shadow，保证可读性）
      for (const c of cells) {
        if (!c.lit) continue;

        let mouse = 0;
        if (hoverRipple) {
          const dx = c.x - mx;
          const dy = c.y - my;
          const d2 = dx * dx + dy * dy;
          if (d2 < R2) {
            const f = 1 - d2 / R2;
            mouse = f * f;
          }
        }

        const breath = 0.12 * Math.sin(t * 1.2 + (c.x + c.y) * 0.02);
        const r = dotRadius * (1 + mouse * 1.8 + breath * 0.3 + amp * 0.35);
        const a = Math.min(1, 0.92 + mouse * 0.08);

        ctx.globalAlpha = a;
        ctx.fillStyle = color;
        ctx.beginPath();
        ctx.arc(c.x, c.y, r, 0, Math.PI * 2);
        ctx.fill();
      }
      ctx.globalAlpha = 1;

      raf = requestAnimationFrame(draw);
    };
    raf = requestAnimationFrame(draw);

    return () => {
      cancelAnimationFrame(raf);
      canvas.removeEventListener("mousemove", onMove);
      canvas.removeEventListener("mouseleave", onLeave);
    };
  }, [text, fontSize, grid, dotRadius, color, glow, font, hoverRipple]);

  return <canvas ref={ref} className={className} style={style} />;
}
