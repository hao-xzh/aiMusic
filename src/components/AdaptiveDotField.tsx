"use client";

/**
 * AdaptiveDotField —— 当前曲目封面驱动的全屏背景。
 *
 * 两层叠加：
 *   1) 底层：模糊封面铺满视口 —— blur(70px) + saturate，再叠一层暗化渐变，
 *      让前景文字（标题/歌词）始终读得清。
 *   2) 上层：DotField 用近白色点阵做纹理，在彩色背景上做轻浮的"颗粒感"。
 *
 * 之前是把封面主色提取出来喂给点阵，现在反过来 —— 颜色全部交给底层封面，
 * 点阵不再随色变化，就是固定的浅色叠加纹理。
 */

import { useEffect, useState } from "react";
import { usePlayer } from "@/lib/player-state";
import { useAppSettings } from "@/lib/app-settings";
import { cdn } from "@/lib/cdn";
import { DotField } from "./DotField";

// 点阵颜色：固定近白色（带一点蓝调跟整体冷色保持一致）
const DOT_COLOR = "#e9efff";

// 切歌时封面交叉淡入淡出的时长，太快有"咔"地一跳，太慢又拖
const COVER_FADE_MS = 800;

export function AdaptiveDotField() {
  const { current, isPlaying } = usePlayer();
  const [{ hideDotPattern }] = useAppSettings();
  const cover = current?.cover ?? null;
  // 没在播 → 点阵 fade-out 后停渲染。包括"无 current"和"current 暂停"两种情况。
  const dotsActive = !!current && isPlaying;

  // 两个 cover slot 做交叉淡入：A 显示中，B 在 fade-in 切歌时把图加载到 B，
  // B 加载完淡入到 1，A 同时淡出到 0，下一次切歌再换回 A。
  const [slotA, setSlotA] = useState<string | null>(null);
  const [slotB, setSlotB] = useState<string | null>(null);
  const [activeSlot, setActiveSlot] = useState<"A" | "B">("A");

  useEffect(() => {
    if (!cover) return;
    const url = cdn(cover);
    if (activeSlot === "A") {
      setSlotB(url);
      // 等浏览器把新图解码进 GPU 一帧，再切 active —— 避免黑闪
      const id = requestAnimationFrame(() => setActiveSlot("B"));
      return () => cancelAnimationFrame(id);
    } else {
      setSlotA(url);
      const id = requestAnimationFrame(() => setActiveSlot("A"));
      return () => cancelAnimationFrame(id);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [cover]);

  return (
    <>
      <CoverSlot url={slotA} visible={activeSlot === "A" && !!cover} />
      <CoverSlot url={slotB} visible={activeSlot === "B" && !!cover} />
      {/* 暗化 + 渐变压底，确保白点和上层文字始终清晰 */}
      <div style={overlayStyle} aria-hidden />
      {!hideDotPattern && <DotField color={DOT_COLOR} playing={dotsActive} />}
    </>
  );
}

function CoverSlot({ url, visible }: { url: string | null; visible: boolean }) {
  if (!url) return null;
  return (
    <div
      aria-hidden
      style={{
        position: "fixed",
        // -80 让 blur 有足够外溢空间，不在视口边缘出现透明环
        inset: -80,
        zIndex: 0,
        backgroundImage: `url(${url})`,
        backgroundSize: "cover",
        backgroundPosition: "center",
        // blur(70) + saturate(1.3) 够柔但不糊；scale 让边缘充满 inset:-80 的扩展区
        filter: "blur(70px) saturate(1.3)",
        transform: "scale(1.1)",
        transformOrigin: "center",
        opacity: visible ? 0.78 : 0,
        transition: `opacity ${COVER_FADE_MS}ms ease`,
        pointerEvents: "none",
      }}
    />
  );
}

const overlayStyle: React.CSSProperties = {
  position: "fixed",
  inset: 0,
  zIndex: 0,
  // 顶部稍亮、底部更暗：海报感 + 让标题/封面卡的下半部分更稳
  background:
    "linear-gradient(180deg, rgba(5,7,14,0.45) 0%, rgba(5,7,14,0.62) 100%)",
  pointerEvents: "none",
};
