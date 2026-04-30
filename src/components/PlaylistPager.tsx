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
 *   - prev 在轴上 translate(-size)，next 在 translate(+size) —— 切换时直接
 *     改 focusIdx，slots 走 380ms transition 平滑滑入
 *   - 拖动期间 transition 关掉、所有 slot 加上实时 drag delta；松手判定阈值
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
  // 鼠标滚轮防抖
  const wheelLockRef = useRef<number | null>(null);

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

  // ---- 触摸 ----
  const onTouchStart = (e: React.TouchEvent) => {
    const t = e.touches[0]!;
    startRef.current = {
      p: horizontal ? t.clientX : t.clientY,
      t: Date.now(),
    };
    setDrag({ active: true, delta: 0 });
  };
  const onTouchMove = (e: React.TouchEvent) => {
    if (!drag.active) return;
    const t = e.touches[0]!;
    const cur = horizontal ? t.clientX : t.clientY;
    setDrag({ active: true, delta: cur - startRef.current.p });
  };
  const onTouchEnd = () => {
    if (!drag.active) return;
    const dt = Math.max(1, Date.now() - startRef.current.t);
    const speed = Math.abs(drag.delta) / dt;
    const triggered = Math.abs(drag.delta) > DRAG_THRESHOLD_PX || speed > FLICK_SPEED;
    if (triggered) {
      if (drag.delta < 0 && next) onChange(focusIdx + 1);
      else if (drag.delta > 0 && prev) onChange(focusIdx - 1);
    }
    setDrag({ active: false, delta: 0 });
  };

  // ---- 鼠标滚轮（vertical 模式才接管） ----
  const onWheel = (e: React.WheelEvent) => {
    if (horizontal) return;
    if (wheelLockRef.current !== null) return;
    if (Math.abs(e.deltaY) < 20) return;
    e.preventDefault();
    if (e.deltaY > 0 && next) onChange(focusIdx + 1);
    else if (e.deltaY < 0 && prev) onChange(focusIdx - 1);
    wheelLockRef.current = window.setTimeout(() => {
      wheelLockRef.current = null;
    }, SWITCH_MS + 30);
  };

  useEffect(() => {
    return () => {
      if (wheelLockRef.current !== null) {
        clearTimeout(wheelLockRef.current);
      }
    };
  }, []);

  // 每个 slot 的 translate（轴向上的位置）
  const trans = (offset: -1 | 0 | 1): string => {
    const base = offset * size;
    const dragDelta = drag.active ? drag.delta : 0;
    const total = base + dragDelta;
    return horizontal ? `translateX(${total}px)` : `translateY(${total}px)`;
  };

  // 透明度：拖动时按位移给"邻张渐显"反馈
  const opa = (offset: -1 | 0 | 1): number => {
    if (!drag.active) return offset === 0 ? 1 : peek > 0 ? 0.34 : 0;
    const t = Math.max(-1, Math.min(1, drag.delta / size));
    if (offset === 0) return Math.max(0.5, 1 - Math.abs(t) * 0.4);
    if ((offset === -1 && t > 0) || (offset === 1 && t < 0)) {
      // 朝邻张拖时它渐显
      return 0.32 + Math.abs(t) * 0.6;
    }
    return Math.max(0.05, 0.32 - Math.abs(t) * 0.27);
  };

  return (
    <div
      ref={containerRef}
      onTouchStart={onTouchStart}
      onTouchMove={onTouchMove}
      onTouchEnd={onTouchEnd}
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
      }}
    >
      {prev && (
        <Slot
          playlist={prev}
          translate={trans(-1)}
          opacity={opa(-1)}
          size={size}
          isFocused={false}
          dragging={drag.active}
          mask={mask}
          maskComposite={maskComposite}
          onClick={() => onChange(focusIdx - 1)}
        />
      )}
      {focused && (
        <Slot
          playlist={focused}
          translate={trans(0)}
          opacity={opa(0)}
          size={size}
          isFocused
          dragging={drag.active}
          mask={mask}
          maskComposite={maskComposite}
        />
      )}
      {next && (
        <Slot
          playlist={next}
          translate={trans(1)}
          opacity={opa(1)}
          size={size}
          isFocused={false}
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
  translate,
  opacity,
  size,
  isFocused,
  dragging,
  mask,
  maskComposite,
  onClick,
}: {
  playlist: PlaylistInfo;
  translate: string;
  opacity: number;
  size: number;
  isFocused: boolean;
  dragging: boolean;
  mask?: string;
  maskComposite?: "intersect" | "add";
  onClick?: () => void;
}) {
  const cover = playlist.coverImgUrl;
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
        transform: `${translate} ${isFocused ? "" : "scale(0.92)"}`,
        opacity,
        transition: dragging
          ? "none"
          : `transform ${SWITCH_MS}ms ${SWITCH_EASE}, opacity 320ms ease`,
        cursor: isFocused ? "default" : "pointer",
        willChange: "transform, opacity",
        zIndex: isFocused ? 2 : 1,
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
              ? "none"
              : "0 12px 30px rgba(0,0,0,0.5), 0 4px 10px rgba(0,0,0,0.32)",
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
