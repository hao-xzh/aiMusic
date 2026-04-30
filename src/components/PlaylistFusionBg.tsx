"use client";

/**
 * 整页氛围背景 —— "封面即背景"。
 *
 * 借鉴 Apple Music：当前选中歌单封面经过重模糊 + 升饱和铺满整屏，作为整个
 * 页面的视觉基底。Hero 的清晰封面和这层背景 *是同一张图*，所以 hero 边缘
 * 与背景之间没有色彩断层 —— 这是整个"hero/list 融合"的根本机制。
 *
 * 切换封面时旧层不立刻 unmount，叠在新层底下做 600ms cross-fade。
 */
import { cdn } from "@/lib/cdn";
import { useEffect, useRef, useState } from "react";

type Layer = { url: string; key: number; entered: boolean };

export function PlaylistFusionBg({ src }: { src: string | null }) {
  const [layers, setLayers] = useState<Layer[]>([]);
  const keyRef = useRef(0);

  useEffect(() => {
    if (!src) return;
    keyRef.current += 1;
    const k = keyRef.current;
    setLayers((prev) => [
      ...prev.slice(-1).map((l) => ({ ...l })),
      { url: src, key: k, entered: false },
    ]);
    // 下一帧把 entered 置 true，触发 opacity 0→1 过渡
    const r1 = requestAnimationFrame(() => {
      requestAnimationFrame(() => {
        setLayers((prev) => prev.map((l) => (l.key === k ? { ...l, entered: true } : l)));
      });
    });
    // 旧层 fade 完即可丢
    const t = window.setTimeout(() => {
      setLayers((prev) => prev.filter((l) => l.key === k));
    }, 750);
    return () => {
      cancelAnimationFrame(r1);
      clearTimeout(t);
    };
  }, [src]);

  return (
    <div
      aria-hidden
      style={{
        position: "fixed",
        inset: 0,
        zIndex: 0,
        pointerEvents: "none",
        overflow: "hidden",
        // 没有任何封面时给一个很淡的暖色基调，避免纯黑空白
        background: "radial-gradient(120% 80% at 50% 30%, rgba(40,46,72,0.6), rgba(8,10,18,1))",
      }}
    >
      {layers.map((l) => (
        // eslint-disable-next-line @next/next/no-img-element
        <img
          key={l.key}
          src={cdn(l.url)}
          alt=""
          draggable={false}
          style={{
            position: "absolute",
            // 略微外溢 + 放大，模糊后边缘不会出现"虚化白边"
            top: "-15%",
            left: "-15%",
            width: "130%",
            height: "130%",
            objectFit: "cover",
            // blur 加重到 90px：跟歌词页同款，让背景颜色更均匀，
            // 跟 cover 边缘采样的 tone 算出来的 fg 在整片区域都对得上
            filter: "blur(90px) saturate(1.4)",
            transform: "scale(1.08)",
            opacity: l.entered ? 1 : 0,
            transition: "opacity 600ms cubic-bezier(0.22, 0.61, 0.36, 1)",
            willChange: "opacity",
          }}
        />
      ))}
      {/* 不再叠"上轻下重"的纯黑 scrim —— 它会让列表区背景跟 cover 边缘色脱钩，
          tone() 算出来的文字颜色就读不准。改成轻微均匀的暗色（保持夜间整体克制），
          整片背景仍以模糊 cover 主导，跟歌词页（PlayerCard ImmersiveLyrics）的
          做法一致：同一张图模糊版铺满，文字颜色靠 tone 算。 */}
      <div
        style={{
          position: "absolute",
          inset: 0,
          background: "rgba(0,0,0,0.18)",
        }}
      />
    </div>
  );
}
