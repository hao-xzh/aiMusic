"use client";

import { useEffect, useRef } from "react";

/**
 * DotField v4 —— 整页游动的粒子流。
 *
 * 模型：300 个圆形粒子撒满视口，每帧根据一个 2D flow field（多频正弦叠加）
 * 决定每个粒子的目标速度，粒子顺着 flow 缓缓飘。粒子不绑死任何锚点 ——
 * 真的会从屏幕一端漂到另一端，到边缘 wrap 回去。
 *
 * 视觉关键：
 *   - 粒径 power-law 分布（多数 1px 像糖粉，少数 ~3px 当锚点）
 *   - flow field 由位置 + 时间多频混合 → 形成缓慢漩涡，自然非周期
 *   - 边缘 alpha 羽化 vignette，wrap 时不会"咔"地一跳
 *   - 没在播：fade-out 后整帧 skip
 *
 * 音乐驱动：
 *   - amp 推流速（baseSpeed × (1 + amp * 1.8)）
 *   - amp 推半径放大（× 1.0..1.4）
 *   - amp 推亮度（baseAlpha + amp * 0.3）
 *
 * 性能：300 圆 × arc/fill ≈ 1ms。每粒每帧 6 次 sin/cos —— 可接受。
 */

export type DotFieldProps = {
  /** 是否在播。false → fade-out 后整帧 skip */
  playing?: boolean;
  /** 粒子数。手机可调到 180-220 */
  count?: number;
  /** 基础颜色 */
  color?: string;
  className?: string;
};

type Particle = {
  x: number;
  y: number;
  vx: number;
  vy: number;
  /** 基础半径 */
  baseR: number;
  /** 个体速度倍率（让 flow 上有快有慢） */
  speedMul: number;
  /** 个体相位偏移，避免视觉同步 */
  phase: number;
};

const TAU = Math.PI * 2;

export function DotField({
  playing = true,
  count = 300,
  color = "#e9efff",
  className,
}: DotFieldProps) {
  const ref = useRef<HTMLCanvasElement | null>(null);
  const rafRef = useRef<number | null>(null);

  const playingRef = useRef(playing);
  useEffect(() => {
    playingRef.current = playing;
  }, [playing]);

  useEffect(() => {
    const canvas = ref.current;
    if (!canvas) return;
    const ctx = canvas.getContext("2d", { alpha: true });
    if (!ctx) return;

    let width = 0;
    let height = 0;
    let particles: Particle[] = [];
    const dpr = 1;

    const resize = () => {
      const rect = canvas.getBoundingClientRect();
      const newW = rect.width;
      const newH = rect.height;
      const wasEmpty = particles.length === 0;
      width = newW;
      height = newH;
      canvas.width = Math.floor(width * dpr);
      canvas.height = Math.floor(height * dpr);
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      // resize 时不重生成粒子（粒子会自行漂到新区域），
      // 只有第一次或宽高变零→非零时才初始化
      if (wasEmpty && width > 0 && height > 0) {
        particles = generateParticles(width, height, count);
      }
    };
    resize();

    const ro = new ResizeObserver(resize);
    ro.observe(canvas);

    const [baseR, baseG, baseB] = parseRGB(color);
    const fillCache = new Map<number, string>();
    const pickFill = (a: number) => {
      const q = Math.min(15, Math.max(0, Math.round(a * 15)));
      let s = fillCache.get(q);
      if (!s) {
        s = `rgba(${baseR},${baseG},${baseB},${(q / 15).toFixed(3)})`;
        fillCache.set(q, s);
      }
      return s;
    };

    let t = 0;
    let lastTs = performance.now();
    let visAlpha = 0;
    let amp = 0;

    const draw = (now: number) => {
      const dt = Math.min(0.05, (now - lastTs) / 1000);
      lastTs = now;
      t += dt;

      const target = playingRef.current ? 1 : 0;
      visAlpha += (target - visAlpha) * Math.min(1, dt * 4);

      const rawAmp =
        (window as unknown as { __claudioAmp?: number }).__claudioAmp ?? 0;
      amp += (rawAmp - amp) * Math.min(1, dt * 7);

      if (visAlpha < 0.005 || width <= 0 || height <= 0) {
        ctx.clearRect(0, 0, width, height);
        rafRef.current = requestAnimationFrame(draw);
        return;
      }

      ctx.clearRect(0, 0, width, height);

      // 流速基线（px/s）；amp 把它推到 ~3 倍
      const baseSpeed = 22 * (1 + amp * 1.8);
      const radiusMul = 1 + amp * 0.4;
      const baseAlpha = 0.42;

      // flow field 时间频率（rad/s）—— 越小越缓
      const tA = t * 0.22;
      const tB = t * 0.18;
      const tC = t * 0.13;

      // vignette 软边距：离边缘 < FEATHER 的粒子 alpha 衰减到 0
      const FEATHER = 60;

      for (let i = 0; i < particles.length; i++) {
        const p = particles[i];

        // ---- flow field 角度 ----
        // 三层不同空间频率 + 时间频率的 sin/cos 叠加，
        // 类似 curl noise 的廉价替代品 —— 给出平滑的 2D 漩涡场
        const angle =
          Math.sin(p.x * 0.0036 + tA + p.phase) +
          Math.cos(p.y * 0.0042 + tB) * 1.1 +
          Math.sin((p.x + p.y) * 0.0021 + tC + p.phase * 0.5) * 0.8;

        const targetSpeed = baseSpeed * p.speedMul;
        const targetVx = Math.cos(angle) * targetSpeed;
        const targetVy = Math.sin(angle) * targetSpeed;

        // 速度平滑：避免 flow 角度突变导致急转弯
        p.vx += (targetVx - p.vx) * 0.06;
        p.vy += (targetVy - p.vy) * 0.06;

        p.x += p.vx * dt;
        p.y += p.vy * dt;

        // wrap：飘出屏幕一边从对面出来（vignette 让边缘 alpha=0，wrap 不可见）
        if (p.x < -10) p.x = width + 10;
        else if (p.x > width + 10) p.x = -10;
        if (p.y < -10) p.y = height + 10;
        else if (p.y > height + 10) p.y = -10;

        // 边缘 alpha 衰减
        const edgeFx = Math.min(p.x, width - p.x);
        const edgeFy = Math.min(p.y, height - p.y);
        const edgeF = Math.min(edgeFx, edgeFy);
        const edgeAlpha = edgeF >= FEATHER ? 1 : Math.max(0, edgeF / FEATHER);

        const r = p.baseR * radiusMul;
        if (r < 0.2 || edgeAlpha < 0.02) continue;

        const alpha = visAlpha * edgeAlpha * (baseAlpha + amp * 0.3);
        ctx.fillStyle = pickFill(alpha > 1 ? 1 : alpha);
        ctx.beginPath();
        ctx.arc(p.x, p.y, r, 0, TAU);
        ctx.fill();
      }

      rafRef.current = requestAnimationFrame(draw);
    };
    rafRef.current = requestAnimationFrame(draw);

    return () => {
      if (rafRef.current) cancelAnimationFrame(rafRef.current);
      ro.disconnect();
    };
  }, [count, color]);

  return (
    <canvas
      ref={ref}
      className={className}
      style={{
        position: "fixed",
        inset: 0,
        width: "100%",
        height: "100%",
        zIndex: 0,
        pointerEvents: "none",
      }}
    />
  );
}

// ---------- 粒子生成 ----------

/**
 * 撒满整个视口（不再聚在中心）。
 * 粒径 power-law：u^3 让多数粒子小、少数粒子大。
 * 个体速度倍率 0.6..1.4：flow 上有快慢分层。
 */
function generateParticles(
  width: number,
  height: number,
  count: number,
): Particle[] {
  if (width <= 0 || height <= 0) return [];
  const out: Particle[] = [];
  for (let i = 0; i < count; i++) {
    const u = Math.random();
    const baseR = 0.7 + Math.pow(u, 3) * 2.6; // ~0.7..3.3
    out.push({
      x: Math.random() * width,
      y: Math.random() * height,
      vx: 0,
      vy: 0,
      baseR,
      speedMul: 0.6 + Math.random() * 0.8,
      phase: Math.random() * TAU,
    });
  }
  return out;
}

// ---------- 颜色解析 ----------

function parseRGB(input: string): [number, number, number] {
  const fallback: [number, number, number] = [233, 239, 255];
  const s = input.trim().toLowerCase();
  if (/^#[0-9a-f]{3}$/.test(s)) {
    return [
      parseInt(s[1] + s[1], 16),
      parseInt(s[2] + s[2], 16),
      parseInt(s[3] + s[3], 16),
    ];
  }
  if (/^#[0-9a-f]{6}([0-9a-f]{2})?$/.test(s)) {
    return [
      parseInt(s.slice(1, 3), 16),
      parseInt(s.slice(3, 5), 16),
      parseInt(s.slice(5, 7), 16),
    ];
  }
  const m = s.match(
    /^rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)(?:\s*,\s*[\d.]+)?\s*\)$/,
  );
  if (m) {
    return [Number(m[1]) | 0, Number(m[2]) | 0, Number(m[3]) | 0];
  }
  return fallback;
}
