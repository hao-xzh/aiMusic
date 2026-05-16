"use client";

/**
 * 歌单 pager —— "歌词页"语言。
 *
 *   horizontal（mobile）：焦点封面贴顶 / 左右贴边的方块；用户横滑切歌单，
 *                         邻张默认完全在屏外（peek = 0）。
 *   vertical（desktop）：焦点封面占据列高，上 / 下各一道半透明 sliver
 *                         （厚度 = peek）做"另一张唱片露头"的提示；
 *                         鼠标滚轮 / 触控板纵向滑动切歌单。
 *
 * 实现要点：
 *   - 永远只渲染 prev / focused / next 三张 slot
 *   - vertical（desktop）走 Android cover-flow 的竖向版：上下邻张露出一截，
 *     焦点封面 1.0 scale，邻张 0.86 scale + 轻微 rotateX。
 *   - 拖动 / 滚轮期间 transition 关掉、所有 slot 加上实时 delta；松手判定阈值
 *   - 内置 ResizeObserver 拿到容器尺寸算 cover 边长，调用方不用传 size
 */
import { cdn } from "@/lib/cdn";
import type { PlaylistInfo } from "@/lib/tauri";
import { useEffect, useRef, useState } from "react";

type Orientation = "horizontal" | "vertical";

type Props = {
  playlists: PlaylistInfo[];
  focusIdx: number;
  onChange: (idx: number) => void;
  orientation: Orientation;
  /** 上下 / 左右 sliver 的厚度，0 = 邻张完全藏起来 */
  peek?: number;
  /** 焦点封面的 mask（跟歌词页一样，让封面边缘溶进背景） */
  mask?: string;
  /** mask-composite：多 mask 时传 "intersect"，单 mask 默认 "add" */
  maskComposite?: "intersect" | "add";
};

const SWITCH_EASE = "cubic-bezier(0.22, 0.61, 0.36, 1)";
const SWITCH_MS = 380;
const DRAG_THRESHOLD_PX = 60;
const FLICK_SPEED = 0.5; // px / ms
const WHEEL_THRESHOLD_PX = 72;

export function PlaylistPager({
  playlists,
  focusIdx,
  onChange,
  orientation,
  peek = 0,
  mask,
  maskComposite,
}: Props) {
  const horizontal = orientation === "horizontal";
  const containerRef = useRef<HTMLDivElement>(null);
  const [{ w, h }, setBox] = useState({ w: 0, h: 0 });
  // 拖动状态
  const [drag, setDrag] = useState<{ active: boolean; delta: number }>({
    active: false,
    delta: 0,
  });
  const startRef = useRef<{ p: number; t: number }>({ p: 0, t: 0 });
  const pointerIdRef = useRef<number | null>(null);
  // 鼠标滚轮累积 + 防抖
  const wheelAccumRef = useRef(0);
  const wheelLockRef = useRef<number | null>(null);
  const wheelResetRef = useRef<number | null>(null);

  // 自测自身尺寸：焦点封面边长 = 横向 → 容器宽；竖向 → 容器高 - 2*peek
  useEffect(() => {
    const el = containerRef.current;
    if (!el) return;
    const update = () => {
      const r = el.getBoundingClientRect();
      setBox({ w: r.width, h: r.height });
    };
    update();
    const ro = new ResizeObserver(update);
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  // 焦点 slot 边长。最小 100 防止初次渲染 0 / 0 时 transform 全是 0 看起来诡异
  const size = horizontal ? Math.max(100, w) : Math.max(100, h - 2 * peek);

  const focused = playlists[focusIdx] ?? null;
  const prev = focusIdx > 0 ? playlists[focusIdx - 1] : null;
  const next = focusIdx < playlists.length - 1 ? playlists[focusIdx + 1] : null;
  const axisStep = horizontal ? size : size * 0.76;
  const threshold = Math.max(DRAG_THRESHOLD_PX, Math.min(120, axisStep * 0.22));

  const finishDrag = (delta: number, dt: number) => {
    const speed = Math.abs(delta) / Math.max(1, dt);
    const triggered = Math.abs(delta) > threshold || speed > FLICK_SPEED;
    if (triggered) {
      if (delta < 0 && next) onChange(focusIdx + 1);
      else if (delta > 0 && prev) onChange(focusIdx - 1);
    }
    setDrag({ active: false, delta: 0 });
  };

  // ---- 鼠标 / 触摸拖动 ----
  const onPointerDown = (e: React.PointerEvent<HTMLDivElement>) => {
    if (e.pointerType === "mouse" && e.button !== 0) return;
    pointerIdRef.current = e.pointerId;
    e.currentTarget.setPointerCapture(e.pointerId);
    startRef.current = {
      p: horizontal ? e.clientX : e.clientY,
      t: Date.now(),
    };
    setDrag({ active: true, delta: 0 });
  };
  const onPointerMove = (e: React.PointerEvent<HTMLDivElement>) => {
    if (!drag.active || pointerIdRef.current !== e.pointerId) return;
    const cur = horizontal ? e.clientX : e.clientY;
    setDrag({ active: true, delta: cur - startRef.current.p });
  };
  const onPointerUp = (e: React.PointerEvent<HTMLDivElement>) => {
    if (!drag.active || pointerIdRef.current !== e.pointerId) return;
    const dt = Date.now() - startRef.current.t;
    pointerIdRef.current = null;
    try {
      e.currentTarget.releasePointerCapture(e.pointerId);
    } catch {
      // 已被系统释放时忽略。
    }
    finishDrag(drag.delta, dt);
  };
  const onPointerCancel = (e: React.PointerEvent<HTMLDivElement>) => {
    if (pointerIdRef.current === e.pointerId) pointerIdRef.current = null;
    setDrag({ active: false, delta: 0 });
  };

  // ---- 鼠标滚轮（vertical 模式才接管） ----
  const onWheel = (e: React.WheelEvent) => {
    if (horizontal) return;
    if (wheelLockRef.current !== null) return;
    if (Math.abs(e.deltaY) < 2) return;
    e.preventDefault();
    wheelAccumRef.current += e.deltaY;
    const capped = Math.max(-threshold, Math.min(threshold, -wheelAccumRef.current));
    setDrag({ active: true, delta: capped });

    if (wheelResetRef.current !== null) clearTimeout(wheelResetRef.current);
    const reached = Math.abs(wheelAccumRef.current) >= WHEEL_THRESHOLD_PX;
    if (reached) {
      const direction = wheelAccumRef.current > 0 ? 1 : -1;
      const canMove = direction > 0 ? !!next : !!prev;
      if (canMove) {
        wheelLockRef.current = window.setTimeout(() => {
          wheelLockRef.current = null;
        }, SWITCH_MS + 80);
        window.setTimeout(() => {
          onChange(focusIdx + direction);
          setDrag({ active: false, delta: 0 });
          wheelAccumRef.current = 0;
        }, 70);
      } else {
        wheelResetRef.current = window.setTimeout(() => {
          wheelAccumRef.current = 0;
          setDrag({ active: false, delta: 0 });
        }, 130);
      }
      return;
    }

    wheelResetRef.current = window.setTimeout(() => {
      wheelAccumRef.current = 0;
      setDrag({ active: false, delta: 0 });
    }, 140);
  };

  useEffect(() => {
    return () => {
      if (wheelLockRef.current !== null) {
        clearTimeout(wheelLockRef.current);
      }
      if (wheelResetRef.current !== null) {
        clearTimeout(wheelResetRef.current);
      }
    };
  }, []);

  const visualOffset = (offset: -1 | 0 | 1): number => {
    const dragUnit = drag.active ? drag.delta / axisStep : 0;
    return offset + dragUnit;
  };

  return (
    <div
      ref={containerRef}
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={onPointerUp}
      onPointerCancel={onPointerCancel}
      onWheel={onWheel}
      style={{
        position: "relative",
        width: "100%",
        height: "100%",
        // 横向：纵向 pan-y 让外层列表能滚；横向交给我们
        // 纵向：none —— wheel 听我们的
        touchAction: horizontal ? "pan-y" : "none",
        overflow: "hidden",
        userSelect: "none",
        cursor: drag.active ? "grabbing" : "grab",
        perspective: horizontal ? "1200px" : "900px",
      }}
    >
      {prev && (
        <Slot
          playlist={prev}
          visualOffset={visualOffset(-1)}
          axisStep={axisStep}
          size={size}
          isFocused={false}
          horizontal={horizontal}
          dragging={drag.active}
          mask={mask}
          maskComposite={maskComposite}
          onClick={() => onChange(focusIdx - 1)}
        />
      )}
      {focused && (
        <Slot
          playlist={focused}
          visualOffset={visualOffset(0)}
          axisStep={axisStep}
          size={size}
          isFocused
          horizontal={horizontal}
          dragging={drag.active}
          mask={mask}
          maskComposite={maskComposite}
        />
      )}
      {next && (
        <Slot
          playlist={next}
          visualOffset={visualOffset(1)}
          axisStep={axisStep}
          size={size}
          isFocused={false}
          horizontal={horizontal}
          dragging={drag.active}
          mask={mask}
          maskComposite={maskComposite}
          onClick={() => onChange(focusIdx + 1)}
        />
      )}
    </div>
  );
}

function Slot({
  playlist,
  visualOffset,
  axisStep,
  size,
  isFocused,
  horizontal,
  dragging,
  mask,
  maskComposite,
  onClick,
}: {
  playlist: PlaylistInfo;
  visualOffset: number;
  axisStep: number;
  size: number;
  isFocused: boolean;
  horizontal: boolean;
  dragging: boolean;
  mask?: string;
  maskComposite?: "intersect" | "add";
  onClick?: () => void;
}) {
  const cover = playlist.coverImgUrl;
  const absOffset = Math.min(1, Math.abs(visualOffset));
  const translatePx = visualOffset * axisStep;
  const translate = horizontal
    ? `translate3d(${translatePx}px, 0, 0)`
    : `translate3d(0, ${translatePx}px, 0)`;
  const scale = 1 - absOffset * 0.14;
  const opacity = 1 - absOffset * 0.4;
  const rotate = horizontal
    ? `rotateY(${visualOffset * 24}deg)`
    : `rotateX(${-visualOffset * 18}deg)`;
  // 焦点封面用调用方给的 mask（跟歌词页一样的 4 向 / 底部渐隐）。
  // 邻张 / 拖动中的非焦点 slot 不上 mask —— 它需要边界清楚才像"另一张唱片"
  const slotMask = isFocused ? mask : undefined;
  return (
    <div
      onClick={isFocused ? undefined : onClick}
      aria-label={playlist.name}
      title={playlist.name}
      style={{
        position: "absolute",
        top: "50%",
        left: "50%",
        width: size,
        height: size,
        marginTop: -size / 2,
        marginLeft: -size / 2,
        transform: `${translate} ${rotate} scale(${scale})`,
        opacity,
        transition: dragging
          ? "none"
          : `transform ${SWITCH_MS}ms ${SWITCH_EASE}, opacity 320ms ease`,
        cursor: isFocused ? "default" : "pointer",
        willChange: "transform, opacity",
        zIndex: Math.round(10 - absOffset * 4),
        // mask 挂在外层 div，而不是 img 上 —— 这样 borderRadius 跟 mask 共存不会冲突
        ...(slotMask
          ? {
              maskImage: slotMask,
              WebkitMaskImage: slotMask,
              ...(maskComposite === "intersect"
                ? {
                    maskComposite: "intersect" as const,
                    WebkitMaskComposite: "source-in" as const,
                  }
                : {}),
            }
          : {}),
      }}
    >
      {cover ? (
        // eslint-disable-next-line @next/next/no-img-element
        <img
          src={cdn(cover)}
          alt={playlist.name}
          draggable={false}
          style={{
            width: "100%",
            height: "100%",
            objectFit: "cover",
            display: "block",
            userSelect: "none",
            borderRadius: isFocused ? 0 : 12,
            boxShadow: isFocused
              ? "0 24px 80px rgba(0,0,0,0.34), 0 0 0 1px rgba(255,255,255,0.08)"
              : "0 16px 44px rgba(0,0,0,0.46), 0 4px 12px rgba(0,0,0,0.28)",
          }}
        />
      ) : (
        <div
          style={{
            width: "100%",
            height: "100%",
            background:
              "linear-gradient(135deg, rgba(255,255,255,0.06), rgba(255,255,255,0.02))",
            borderRadius: isFocused ? 0 : 12,
          }}
        />
      )}
    </div>
  );
}
