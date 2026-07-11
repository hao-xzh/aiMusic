"use client";

/**
 * Pipo 主播放卡片 v6 —— Apple Music / Shopify 极简风 + 沉浸式歌词覆盖层。
 *
 * 设计原则：
 *   - 默认（compact）布局完全保持 v5 的样子 —— 单列、海报、控制条、3 行歌词带。
 *   - 点歌词带 → FLIP 动画把封面放大到屏幕上半部、铺满，下方接一大块沉浸式歌词。
 *     点封面 → 反向 FLIP 回 compact，原始页面不掉一帧。
 *
 * 关于性能：
 *   - 逐字 yrc 的"当前行"必须每帧重渲（计算 char wipe 进度），但其他行的 props
 *     稳定。所有 lyric row 组件都包了 React.memo，只把 positionSec 传给当前行；
 *     非当前行靠 memo bail-out，每帧不参与 reconcile。
 *   - compact 动画走 transform / opacity；桌面 Apple 歌词模式会额外做行高 /
 *     padding 过渡，复刻 current line 撑开列表的拉伸感。
 */

import { Waveform } from "./Waveform";
import { AiCoverCaption } from "./AiCoverCaption";
import { AppIcon } from "./AppIcon";
import { usePlayer, type PlayerAPI } from "@/lib/player-state";
import { type LrcLine } from "@/lib/lrc";
import { charProgress, type YrcChar, type YrcLine } from "@/lib/yrc";
import { cdn } from "@/lib/cdn";
import { useCoverEdgeColors } from "@/lib/cover-color";
import Link from "next/link";
import React, { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { createPortal } from "react-dom";

// 兼容导出（page.tsx 仍 import）
export type PlayerMode = "compact";
export function usePlayerMode(): PlayerMode {
  return "compact";
}

function useViewportWidth(defaultWidth = 1180): number {
  const [width, setWidth] = useState(() =>
    typeof window === "undefined" ? defaultWidth : window.innerWidth || defaultWidth,
  );
  useEffect(() => {
    const update = () => setWidth(window.innerWidth || defaultWidth);
    update();
    window.addEventListener("resize", update);
    return () => window.removeEventListener("resize", update);
  }, [defaultWidth]);
  return width;
}

function appleLyricMetrics(viewportWidth: number): {
  fontPx: number;
  lineHeight: number;
  lineBoxPx: number;
  marginBottomPx: number;
} {
  if (viewportWidth >= 2000) {
    return { fontPx: 84, lineHeight: 1.1904761905, lineBoxPx: 100, marginBottomPx: 58 };
  }
  if (viewportWidth >= 1680) {
    return { fontPx: 62, lineHeight: 1.1935483871, lineBoxPx: 74, marginBottomPx: 40 };
  }
  if (viewportWidth >= 1320) {
    return { fontPx: 48, lineHeight: 1.2083333333, lineBoxPx: 58, marginBottomPx: 32 };
  }
  return { fontPx: 38, lineHeight: 1.2105263158, lineBoxPx: 46, marginBottomPx: 26 };
}

export function appleArtworkRadiusCss(): string {
  return "clamp(8px, 0.72vw, 18px)";
}

export function cssUrl(url: string): string {
  return `url("${url.replace(/"/g, '\\"')}")`;
}

export function appleBackdropBaseRgb(
  topRgb: string,
  seamRgb: string,
  leftRgb: string,
  rightRgb: string,
): string {
  const colors = [parseRgbTriplet(topRgb), parseRgbTriplet(seamRgb), parseRgbTriplet(leftRgb), parseRgbTriplet(rightRgb)];
  const average = [0, 1, 2].map((channel) =>
    colors.reduce((sum, color) => sum + color[channel], 0) / colors.length,
  );
  const lifted = average.map((value, channel) => {
    const floor = channel === 0 ? 18 : channel === 1 ? 18 : 24;
    return Math.max(floor, Math.min(96, value));
  });
  return lifted.map((value) => Math.round(value)).join(", ");
}

function parseRgbTriplet(value: string): [number, number, number] {
  const parts = value.split(",").map((part) => Number.parseFloat(part.trim()));
  return [
    Number.isFinite(parts[0]) ? parts[0] : 8,
    Number.isFinite(parts[1]) ? parts[1] : 10,
    Number.isFinite(parts[2]) ? parts[2] : 18,
  ];
}

// ---------- compact 设计 token（v5 沿用） ----------
const COVER_SIZE = "min(clamp(220px, 86vw, 400px), 50vh)";
const TITLE_FS = "clamp(17px, 4vw, 22px)";
const SUBTITLE_FS = "clamp(12px, 3.2vw, 14px)";
const LYRIC_BOX_H = "clamp(116px, 15vh, 150px)";
const LYRIC_ROW_H = "clamp(26px, 3.6vh, 32px)";
const LYRIC_ACTIVE_FS = "clamp(16px, 4.2vw, 19px)";
const LYRIC_DIM_FS = "clamp(11px, 2.8vw, 13px)";
const COVER_TRANSITION_MS = 720;

// ---------- immersive 设计 token ----------
//
// 封面正方形，固定边长不变形。尺寸自适应：
//   - 手机宽（min() 卡 100vw 上）：cover_w == 100vw → 顶部 / 左右贴满屏幕
//   - 桌面宽（被 60vh / 480px 卡住）：居中显示，四周让屏幕级毛玻璃透出来
//
// 整体氛围靠：桌面全屏不透黑底 + 封面自身 mask 溶解。封面溶解是用户要求的
// runtime 组合效果，不写入 Apple evidence 常量表。
const IMMERSIVE_COVER_W = "min(100vw, 78vh, 700px)";
// 公式：cover_w == 100vw 时取 0；cover_w 比 100vw 小时给 32px 的顶部留白
const IMMERSIVE_COVER_TOP = `clamp(0px, calc(100vw - ${IMMERSIVE_COVER_W}), 32px)`;
const IMMERSIVE_COVER_LEFT = `calc((100vw - ${IMMERSIVE_COVER_W}) / 2)`;
// 封面边缘"色彩晕开"：纵向 + 横向两层 mask 组合（mask-composite source-in）：
//   - 纵向：顶部 5% / 底部 30% 渐隐 → 上下沿都溶进 bg
//   - 横向：左右 7% 渐隐 → 桌面宽下封面侧边溶进侧边毛玻璃
const IMMERSIVE_COVER_FADE_MASK_V =
  "linear-gradient(180deg, " +
  "rgba(0,0,0,0) 0%, rgba(0,0,0,0.55) 2%, #000 5%, " +
  "#000 50%, " +
  "rgba(0,0,0,0.95) 60%, " +
  "rgba(0,0,0,0.82) 70%, " +
  "rgba(0,0,0,0.6) 80%, " +
  "rgba(0,0,0,0.32) 90%, " +
  "rgba(0,0,0,0.1) 96%, " +
  "rgba(0,0,0,0) 100%)";
const IMMERSIVE_COVER_FADE_MASK_H =
  "linear-gradient(90deg, " +
  "rgba(0,0,0,0) 0%, rgba(0,0,0,0.5) 3%, #000 8%, " +
  "#000 92%, rgba(0,0,0,0.5) 97%, rgba(0,0,0,0) 100%)";
// Apple Music 风：所有行同字号同字重，激活态靠对比度（opacity 1 vs 0.32）和
// 逐字 wipe 来呈现，不靠 fontSize / fontWeight 切换。配合大行距 + 大留白。
const IMMERSIVE_LYRIC_ROW_H = "clamp(68px, 8.4vh, 106px)";
const IMMERSIVE_ACTIVE_FS = "28px";
const IMMERSIVE_DIM_FS = IMMERSIVE_ACTIVE_FS;
const IMMERSIVE_ROWS = 7;
const FLIP_DURATION_MS = 620;
const FLIP_EASE = "cubic-bezier(0.32, 0.72, 0, 1)";
const APPLE_LYRIC_FILTER_MS = 250;
const APPLE_LYRIC_LAYOUT_MS = 400;
// 350ms remains the nominal Apple cut duration and the desktop-path marker.
// The actual movement is a retained-velocity spring shared with Android.
const APPLE_LYRIC_SCROLL_MS = 350;
const APPLE_LYRIC_SCROLL_SPRING_STIFFNESS = 140;
const APPLE_LYRIC_SCROLL_SPRING_DAMPING = 24;
const APPLE_LYRIC_SCROLL_SPRING_POSITION_EPS_PX = 0.5;
const APPLE_LYRIC_SCROLL_SPRING_VELOCITY_EPS_PX_S = 5;
const APPLE_LYRIC_SCROLL_SPRING_MAX_FRAME_SEC = 0.032;
const APPLE_LYRIC_SCROLL_SPRING_SUBSTEP_SEC = 0.008;
const APPLE_LYRIC_SPATIAL_FOCUS_SPAN_ROWS = 1.15;
const APPLE_LYRIC_TOP_OFFSET_PX = 75;
const APPLE_LYRIC_SCROLL_TOP_MARGIN_PX = 55;
const APPLE_LYRIC_FULLSCREEN_OFFSET_RATIO = 0.4;
const APPLE_LYRIC_VISUAL_CURRENT_FALLBACK_MS = 56;
const APPLE_DESKTOP_LAYOUT_MIN_WIDTH_PX = 720;
const APPLE_LYRIC_IDLE_MASK =
  "linear-gradient(180deg, transparent, #000 80px, #000 50%, transparent)";
const APPLE_LYRIC_LIST_EASE = "cubic-bezier(0.45, 0, 0.55, 1)";
const APPLE_LYRIC_SPRING_LINE_TRANSITION =
  "padding 0.1s ease-in-out, height 0.4s linear, margin-top 0.4s linear";
const APPLE_LYRIC_COLOR_TRANSITION = "color 0.1s";
const APPLE_LYRIC_TOKEN_COLOR_TRANSITION = "color 0.1s";
const APPLE_LYRIC_FADE_OUT_ANIMATION = "appleLyricFadeOut";
const APPLE_LYRIC_COLLAPSIBLE_EXPAND_ANIMATION = "appleLyricHeightExpand";
const APPLE_LYRIC_COLLAPSIBLE_COLLAPSE_ANIMATION = "appleLyricHeightCollapse";
const APPLE_LYRIC_COLLAPSIBLE_MS = 300;
const APPLE_LYRIC_ROW_FILTER_TRANSITION = "filter 250ms linear";
const APPLE_LYRIC_INACTIVE_ROW_FILTER = "blur(var(--inactive-gaussian-blur, 0px))";
const APPLE_LYRIC_CURRENT_SCALE = 1.05;
const APPLE_LYRIC_CURRENT_PADDING_BLOCK_PX = 12;
const APPLE_LYRIC_LINE_OVERBLEED = "5%";
const APPLE_LYRIC_LINE_HEIGHT = 1.2142857143;
const APPLE_LYRIC_BG_FONT_PX = 14;
const APPLE_LYRIC_SECONDARY_FONT_RATIO_SMALL = 0.54;
const APPLE_LYRIC_SECONDARY_FONT_RATIO_MEDIUM = 0.42;
const APPLE_LYRIC_SUPPLEMENTARY_FONT_RATIO_SMALL = 0.64;
const APPLE_LYRIC_SUPPLEMENTARY_FONT_RATIO_MEDIUM = 0.5;
const APPLE_SUPPLEMENTARY_REVEAL_MS = 600;
const APPLE_SUPPLEMENTARY_FORCE_SCROLL_DELAY_MS = APPLE_SUPPLEMENTARY_REVEAL_MS + 150;
const APPLE_CURRENT_SYLLABLE_CLIP = "inset(0.5px 0.75px 0.5px 0.75px)";
const APPLE_CURRENT_SYLLABLE_MARGIN = "-0.5px -0.75px";
const APPLE_CURRENT_SYLLABLE_PADDING = "0.5px 0.75px";
export const APPLE_LYRIC_FONT_FAMILY =
  '-apple-system, BlinkMacSystemFont, "Apple Color Emoji", "SF Pro", "PingFang SC", "PingFang HK", "PingFang TC", "SF Pro Icons", "Helvetica Neue", Helvetica, Arial, sans-serif';
export const APPLE_LYRIC_BG_COLOR = "#090a0f";
export const APPLE_DESKTOP_BACKDROP_ARTWORK_FILTER =
  "blur(150px) saturate(1.35) brightness(0.32)";
// Apple Music fullscreen lyrics uses the artwork-derived nowPlayingBackdropBG
// as an opaque field. The full-cover blur stays mounted as a transition-safe
// fallback, but desktop parity keeps it visually disabled; depth comes from
// the local artwork radiosity layer instead of a noisy full-screen poster.
export const APPLE_DESKTOP_BACKDROP_ARTWORK_OPACITY = 0;
export const APPLE_DESKTOP_BACKDROP_VEIL = "transparent";
export const APPLE_DESKTOP_LYRIC_COLUMN_VEIL = "transparent";
export const APPLE_DESKTOP_COVER_HALO_OPACITY = 0.4;
const APPLE_LYRIC_SUPPLEMENTARY_REVEAL_BOX_PX = 50;
const APPLE_LYRIC_TOKEN_SUPPLEMENTARY_BOX_PX = 24;
const APPLE_LYRIC_SUPPLEMENTARY_MARGIN_TOP_EM = 0.2;
export const APPLE_DESKTOP_LYRIC_COLUMN_VEIL_BLEED = "max(24px, 5vw)";
const IMMERSIVE_BACKDROP_ARTWORK_FILTER = "blur(96px) saturate(0.66) brightness(0.14)";
const IMMERSIVE_BACKDROP_ARTWORK_OPACITY = 0;
const IMMERSIVE_BACKDROP_VEIL = "rgb(0, 0, 0)";
const IMMERSIVE_COVER_HALO_OPACITY = 0;
// Apple fullscreen timed lyrics overrides the primary sweep to 1 / .35.
// Static current-line copy still follows the systemPrimary-onDark family.
const APPLE_TIMED_GRADIENT_ACTIVE_ALPHA = 1;
const APPLE_TIMED_GRADIENT_UNSUNG_ALPHA = 0.35;
const APPLE_BG_VOCAL_GRADIENT_ACTIVE_ALPHA = 0.35;
const APPLE_BG_VOCAL_GRADIENT_UNSUNG_ALPHA = 0.175;
const APPLE_INACTIVE_LINE_ALPHA = 0.25;
const APPLE_LYRIC_CURRENT_LOOKAHEAD_SEC = 0.25;
const APPLE_WORD_LIFT_DELAY_SEC = 0.1;
const APPLE_REGULAR_WORD_LIFT_PX = 2;
const APPLE_SWEEP_LEAD_PERCENT = 20;
const APPLE_SWEEP_TRAVEL_PERCENT = 120;
const APPLE_INTERLUDE_MIN_GAP_SEC = 9;
const APPLE_INTERLUDE_DOT_COUNT = 3;
const APPLE_INTERLUDE_DOT_SIZE_EM = 10 / 22;
const APPLE_INTERLUDE_DOT_GAP_RATIO = 0.5;
const APPLE_INTERLUDE_DOT_INACTIVE_ALPHA = 0.3;
const APPLE_INTERLUDE_HEARTBEAT_SEC = 5;
const APPLE_INTERLUDE_HEARTBEAT_PEAK_SCALE = 1.2;
const APPLE_INTERLUDE_ENDING_SEC = 1.5;
const APPLE_INTERLUDE_END_HEARTBEAT_SEC = 1;
const APPLE_INTERLUDE_END_SCALE_START = 1.1;
const APPLE_INTERLUDE_END_SCALE_PEAK = 1.4;
export const APPLE_DESKTOP_ARTWORK_SHADOW =
  "0 4px 10px rgba(0,0,0,.1)";
export const APPLE_DESKTOP_ARTWORK_RADIOSITY_SHADOW =
  "0 20px 25px rgba(0,0,0,.1), 0 10px 25px rgba(0,0,0,.1)";
export const APPLE_DESKTOP_ARTWORK_RADIOSITY_FILTER =
  "blur(20px) saturate(2) drop-shadow(0 20px 25px rgba(0,0,0,.1)) drop-shadow(0 10px 25px rgba(0,0,0,.1))";
// 关闭走另一组参数：用户离场时希望"先动起来 → 落到位"的爽快感，
// 跟开场的"慢慢settle"不一样。
//   - 时长更短（看起来更有"撤退感"，避免回拽感）
//   - 曲线起步更陡（cubic-bezier 第一控制点 y 提到 0.04），
//     再衔接长尾减速（第三、四控制点保持平滑），整体像 iOS modal dismiss
const CLOSE_DURATION_MS = 540;
const CLOSE_EASE = "cubic-bezier(0.6, 0.04, 0.22, 1)";

// Android-native ImmersiveLyrics 的时钟 / 行焦点参数，桌面端保持同一套节奏。
const LYRIC_SWEEP_VISUAL_LEAD_SEC = 0.045;
const LYRIC_WORD_LINE_FOCUS_LEAD_SEC = 0.095;
const LYRIC_WORD_SCROLL_LOOKAHEAD_SEC = 0.17;
const LYRIC_LINE_FOCUS_LEAD_SEC = 0.22;

const SMOOTH_POSITION_SEEK_RESET_SEC = 1.5;
const SMOOTH_POSITION_FRAME_RESET_SEC = 1.5;
const SMOOTH_POSITION_BACKWARD_RESET_SEC = 0.3;
const SMOOTH_POSITION_OUTPUT_STALE_SEC = 0.36;
const SMOOTH_POSITION_START_GUARD_SEC = 0.9;
const SMOOTH_POSITION_FOLLOW_ALPHA = 0.68;

const COMPANION_LYRIC_LEAD_SEC = 0.45;

// Android slow-emphasis 通道的桌面复刻：慢词才启用轻 glow / scale / float。
const EMP_MIN_DURATION_SEC = 0.8;
const APPLE_EMP_MIN_DURATION_SEC = 1.0;
const EMP_PEAK_SCALE = 0.05;
const EMP_GLOW_OPACITY = 0.4;
const EMP_GLOW_BLUR_PX = 10;
const REGULAR_WORD_LIFT_PX = 0.95;
const WORD_CONTINUITY_MAX_GAP_SEC = 0.11;
const WORD_CONTINUITY_HANDOFF_SEC = 0.16;
const WORD_CONTINUITY_HANDOFF_FRACTION = 0.76;
const WORD_CONTINUITY_ATTACK_SEC = 0.01;
const WORD_CONTINUITY_LIFT_CARRY = 0.4;
const WORD_FLOAT_EASE_BLEND = 0.3;


// ============== 主组件 ==============

export function PlayerCard() {
  const player = usePlayer();
  const [lyricMode, setLyricMode] = useState<"compact" | "immersive">("compact");
  // immersiveActive：覆盖整个 opening / open / closing 三阶段。
  //   - lyricMode 在用户点击 onClose 那一刻就翻回 "compact"，
  //     但 ImmersiveLyrics 内部还在跑 closing 动画 ~540ms。
  //   - 只看 lyricMode 的话，关闭动画期间 CompactPlayer 已经被认为"该显示了"，
  //     底下封面立刻跟正在收缩的 immersive 封面同框，出现"两张封面"重影。
  // ImmersiveLyrics 通过 onActiveChange 在 phase != "closed" 时上报 true，
  // 真正卸载（phase = "closed"）时才上报 false。CompactPlayer 据此把封面
  // / 歌词带在整个动画期间隐藏，避免重影。
  const [immersiveActive, setImmersiveActive] = useState(false);
  const compactCoverRef = useRef<HTMLDivElement>(null);
  const compactLyricRef = useRef<HTMLDivElement>(null);

  const sourceCoverRect = useCallback(
    () => compactCoverRef.current?.getBoundingClientRect() ?? null,
    [],
  );
  const sourceLyricRect = useCallback(
    () => compactLyricRef.current?.getBoundingClientRect() ?? null,
    [],
  );

  const enterImmersive = useCallback(() => setLyricMode("immersive"), []);
  const exitImmersive = useCallback(() => setLyricMode("compact"), []);

  return (
    <>
      <CompactPlayer
        player={player}
        compactCoverRef={compactCoverRef}
        compactLyricRef={compactLyricRef}
        onLyricClick={enterImmersive}
        immersiveActive={immersiveActive}
      />
      <ImmersiveLyrics
        open={lyricMode === "immersive"}
        sourceCoverRect={sourceCoverRect}
        sourceLyricRect={sourceLyricRect}
        player={player}
        onClose={exitImmersive}
        onActiveChange={setImmersiveActive}
      />
    </>
  );
}

// ============== compact 布局（v5 原样） ==============

function CompactPlayer({
  player,
  compactCoverRef,
  compactLyricRef,
  onLyricClick,
  immersiveActive,
}: {
  player: PlayerAPI;
  compactCoverRef: React.RefObject<HTMLDivElement | null>;
  compactLyricRef: React.RefObject<HTMLDivElement | null>;
  onLyricClick: () => void;
  immersiveActive: boolean;
}) {
  const { current, isPlaying, positionSec, toggle, next, prev, error, lyric } = player;

  const hasTrack = current !== null;
  const duration = current?.durationSec ?? 0;
  const pct = hasTrack && duration > 0 ? Math.min(1, Math.max(0, positionSec / duration)) : 0;
  const cover = current?.cover;

  return (
    <div style={shell}>
      <div style={contentLayer}>
        <div style={trackColumn}>
          <CoverBox
            cover={cover}
            isPlaying={isPlaying}
            ref={compactCoverRef}
            hidden={immersiveActive}
          />

          {current ? (
            <div style={titleBlock}>
              <div style={titleTextCol}>
                <div style={titleStyle} title={current.title}>
                  {current.title}
                </div>
                <div style={subtitleStyle}>
                  {current.artist + (current.album ? ` · ${current.album}` : "")}
                </div>
              </div>
            </div>
          ) : (
            // 没歌时不出占位文字 —— 整页留给封面留白和控件，跟"沉浸感拉满"一致。
            // 底栏的曲库入口已经隐含“去挑一首”的行为入口。
            <div style={{ height: 8 }} />
          )}

          <div style={progressWrap}>
            <ProgressBar pct={pct} />
            <div style={timeRow}>
              <span style={mono}>{fmt(positionSec)}</span>
              <span style={{ ...mono, color: "rgba(233,239,255,0.42)" }}>
                -{fmt(Math.max(0, duration - positionSec))}
              </span>
            </div>
          </div>

          <Controls
            isPlaying={isPlaying}
            hasTrack={hasTrack}
            onPrev={prev}
            onToggle={toggle}
            onNext={next}
          />

          {/* 四入口底栏使用独立等分轨道；整体宽度与播放控件一致，保证左右边界、
              图标中心和纵向节奏稳定，不把四个入口硬套进三键播放控件的列线。 */}
          <div
            ref={compactLyricRef}
            className="platform-nav-rail"
            style={{
              marginTop: "clamp(22px, 3.4vh, 32px)",
              display: "grid",
              gridTemplateColumns: "repeat(4, 1fr)",
              placeItems: "center",
              width: "min(100%, 320px)",
              alignSelf: "center",
              padding: "7px 8px",
              opacity: immersiveActive ? 0 : 1,
              transition: "opacity 200ms ease",
            }}
          >
            <button
              onClick={hasTrack ? onLyricClick : undefined}
              disabled={!hasTrack}
              aria-label="歌词"
              title="歌词"
              className="platform-icon-button"
              style={{
                ...navIcon,
                opacity: hasTrack ? 0.82 : 0.32,
                cursor: hasTrack ? "pointer" : "not-allowed",
              }}
            >
              <LyricsIcon />
            </button>
            <Link href="/distill" aria-label="我的歌单" title="我的歌单" style={navIcon} className="platform-icon-button">
              <ListIcon />
            </Link>
            <Link href="/export" aria-label="曲库导出" title="曲库导出" style={navIcon} className="platform-icon-button">
              <NavDownloadIcon />
            </Link>
            <Link href="/settings" aria-label="设置" title="设置" style={navIcon} className="platform-icon-button">
              <NavGearIcon />
            </Link>
          </div>
        </div>

        {error && <div style={errorBar}>{error}</div>}
      </div>
    </div>
  );
}

// ============== 全景歌词（沉浸式）覆盖层 ==============
//
// 设计核心：桌面与移动端各自遵循对应的沉浸式语言。
//   1. 桌面歌词使用封面采样出的不透明纯色场，不再把整张封面放大模糊铺底。
//   2. 移动端保留同源封面的重模糊与 mask 融合，避免上下画面出现色彩断层。
//   3. 歌词层都不再另铺黑块，文字直接落在统一的背景色场之上。
//
// 元素的尺寸/位置变化：
//   - 封面 / 歌词容器都用 width/height/left/top 过渡（不是 transform: scale），
//     img 用 object-fit: cover；尺寸变了画面只裁切，绝不变形。
//   - 入场：从 compact 海报位 / 歌词带位的 rect 直接动到 immersive 自然位。
//   - 退场：反向动回去。
//   - 控件（暂停 / 下一首）独立淡入并显著缩小，营造"内容容器变大、控件让位"
//     的视觉层级。
//
// 性能：所有动画走 width/height/top/left/opacity，浏览器对这些属性都有自己的
// 加速通道；元素总共 5-6 个，逐字渲染只在当前行（已 React.memo）。Tauri 自带
// WebKit 跑得轻松。

export function ImmersiveLyrics({
  open,
  sourceCoverRect,
  sourceLyricRect,
  player,
  onClose,
  onActiveChange,
}: {
  open: boolean;
  sourceCoverRect: () => DOMRect | null;
  sourceLyricRect: () => DOMRect | null;
  player: PlayerAPI;
  onClose: () => void;
  // 上报"沉浸式覆盖层是否在屏（含动画过程）"。
  // true: phase ∈ {opening, open, closing}；false: phase = closed（已 unmount）
  onActiveChange?: (active: boolean) => void;
}) {
  const [phase, setPhase] = useState<"closed" | "opening" | "open" | "closing">(
    "closed",
  );
  const containerRef = useRef<HTMLDivElement>(null);
  const backdropRef = useRef<HTMLDivElement>(null);
  const coverHaloRef = useRef<HTMLDivElement>(null);
  const coverRef = useRef<HTMLDivElement>(null);
  const lyricRef = useRef<HTMLDivElement>(null);
  const titleBarRef = useRef<HTMLDivElement>(null);

  // 采样封面边缘颜色：背景层、frost 用这俩颜色染色，封面渐隐时
  // 边缘溶到的颜色 == 背景同位置颜色 → 接缝处颜色 100% 连续，不再有色彩断层
  const coverUrlRaw = player.current?.cover ? cdn(player.current.cover) : null;
  const edgeColors = useCoverEdgeColors(coverUrlRaw);

  // 720px 以上保持 Apple 两列；这类横向窗口切成上下结构会让封面吃掉纵向空间。
  const viewportWidth = useViewportWidth();
  const isDesktop = viewportWidth >= APPLE_DESKTOP_LAYOUT_MIN_WIDTH_PX;

  // 接缝 / 顶部 / 右侧采样色 fallback
  const seamRgb = edgeColors.bottom ?? "8, 10, 18";
  const topRgb = edgeColors.top ?? "8, 10, 18";
  const leftRgb = edgeColors.left ?? topRgb;
  const rightRgb = edgeColors.right ?? seamRgb;

  // 桌面和移动沉浸层现在都锁到不透明黑底，歌词颜色也固定走白色系。
  // 这样不会再因为封面采样色偏亮，把实际黑底上的歌词算成黑字，也不会让
  // 忙碌封面纹理透到歌词背后。
  const fgColor = "rgb(255, 255, 255)";
  const fgDimColor = "rgba(255, 255, 255, 0.32)";
  // active 行里"未唱"字符的颜色：跟主色同源，仅压低 alpha 到中等亮度。
  // 不能直接用 fgDimColor —— 那个是给非 active 行用的过暗值，
  // 当 active 行里大部分字符还没唱到时（如刚切到下一句），会让整行看起来像
  // 灰乎乎的旁白。Apple Music 的处理：active 行未唱字符保持中亮度，
  // 已唱字符全亮，逐字 wipe 表现为 "中亮 → 满亮" 的渐变。
  const fgUnsungColor = "rgba(255, 255, 255, 0.62)";

  // 一次性算出整套布局：cover/title/lyric 位置、mask 方向、bg gradient
  const layout = useMemo(
    () => computeLayout(isDesktop, topRgb, seamRgb, leftRgb, rightRgb, fgColor, fgDimColor),
    [isDesktop, topRgb, seamRgb, leftRgb, rightRgb, fgColor, fgDimColor],
  );
  const lyricFgColor = isDesktop
    ? `rgba(255, 255, 255, ${APPLE_TIMED_GRADIENT_ACTIVE_ALPHA})`
    : layout.fgColor;
  const lyricFgDimColor = isDesktop
    ? `rgba(255, 255, 255, ${APPLE_INACTIVE_LINE_ALPHA})`
    : layout.fgDimColor;
  const lyricFgUnsungColor = isDesktop
    ? `rgba(255, 255, 255, ${APPLE_TIMED_GRADIENT_UNSUNG_ALPHA})`
    : fgUnsungColor;
  const chromeFgColor = isDesktop ? "rgba(255, 255, 255, 0.85)" : layout.fgColor;
  const chromeFgDimColor = isDesktop ? "rgba(255, 255, 255, 0.55)" : layout.fgDimColor;

  useEffect(() => {
    if (open) setPhase((p) => (p === "open" ? "open" : "opening"));
    else setPhase((p) => (p === "closed" ? "closed" : "closing"));
  }, [open]);

  // 给外部组件（AiPet 等）一个全局信号：歌词页是否打开 / 即将打开。
  // AiPet 据此在歌词页期间从"挂封面"切到"屏幕右下角"，并跑移动动画。
  // 用 body dataset 是为了不引入额外 store / context —— pet 是 layout 级，
  // PlayerCard 也是；两者间最轻的传播渠道就是 DOM。
  useEffect(() => {
    const isOpen = phase === "opening" || phase === "open";
    if (isOpen) {
      document.body.dataset.claudioImmersive = "1";
    } else {
      delete document.body.dataset.claudioImmersive;
    }
    return () => {
      delete document.body.dataset.claudioImmersive;
    };
  }, [phase]);

  // 上报"覆盖层是否在屏"给 PlayerCard，让它在动画期间隐藏 CompactPlayer 的
  // 封面 / 歌词带，避免下层和正在收缩的上层封面同时显出形成重影。
  // 注意：closing 也算 active —— 用户点击关闭后 lyricMode 立刻变 compact，
  // 但实际动画还要跑 ~540ms，这段时间下层封面必须保持隐藏。
  useEffect(() => {
    onActiveChange?.(phase !== "closed");
  }, [phase, onActiveChange]);

  // 入场
  useLayoutEffect(() => {
    if (phase !== "opening") return;
    const ctx = getRefs();
    if (!ctx) return;
    const { backdrop, coverHalo, cover, lyric, titleBar } = ctx;

    const srcCover = sourceCoverRect();
    const srcLyric = sourceLyricRect();

    // 自然 immersive 目标尺寸 —— 跟下面 render 时不带 inline width/height 时
    // CSS 默认状态保持一致
    const target = computeImmersiveTargets(layout);

    // 没源 rect 就退化成纯淡入；移动端走 FLIP（120Hz 设备 hold 得住）
    if (!srcCover) {
      applyTargetImmediate(cover, target.cover);
      if (coverHalo) applyTargetImmediate(coverHalo, target.cover);
      applyTargetImmediate(lyric, target.lyric);
      backdrop.style.transition = "none";
      backdrop.style.opacity = "1";
      if (titleBar) {
        titleBar.style.opacity = "0";
        void titleBar.offsetWidth;
        titleBar.style.transition = `opacity 360ms ${FLIP_EASE}`;
        titleBar.style.opacity = "1";
      }
      const t = window.setTimeout(() => setPhase("open"), 420);
      return () => window.clearTimeout(t);
    }

    // 起始：封面 / 歌词都瞬移到 compact rect，背景透明，标题条隐藏
    applyRectImmediate(cover, srcCover);
    if (coverHalo) applyRectImmediate(coverHalo, srcCover);
    cover.style.borderRadius = "12px";
    if (layout.isDesktop) {
      applyTargetImmediate(lyric, target.lyric);
    } else if (srcLyric) {
      applyRectImmediate(lyric, srcLyric);
    } else {
      // 没拿到歌词 rect 就让歌词从封面下沿冒出
      applyRectImmediate(lyric, {
        left: srcCover.left,
        top: srcCover.bottom + 12,
        width: srcCover.width,
        height: 100,
      });
    }
    lyric.style.opacity = "0.0";
    backdrop.style.transition = "none";
    backdrop.style.opacity = "1";
    if (titleBar) {
      titleBar.style.transition = "none";
      titleBar.style.opacity = "0";
      titleBar.style.transform = "translate3d(0, 8px, 0)";
    }
    void cover.offsetWidth;

    // 下一帧动到自然 immersive 目标
    requestAnimationFrame(() => {
      const t = transitionFor(["top", "left", "width", "height", "border-radius", "opacity"]);
      if (coverHalo) {
        coverHalo.style.transition = transitionFor(["top", "left", "width", "height", "opacity"]);
        applyTargetCss(coverHalo, target.cover);
      }
      cover.style.transition = t;
      applyTargetCss(cover, target.cover);
      cover.style.borderRadius = layout.isDesktop ? appleArtworkRadiusCss() : "0px";

      if (layout.isDesktop) {
        lyric.style.transition =
          `opacity ${Math.round(FLIP_DURATION_MS * 0.55)}ms ${FLIP_EASE} ` +
          `${Math.round(FLIP_DURATION_MS * 0.12)}ms`;
      } else {
        lyric.style.transition = t;
        applyTargetCss(lyric, target.lyric);
      }
      lyric.style.opacity = "1";

      backdrop.style.transition = "none";
      backdrop.style.opacity = "1";

      if (titleBar) {
        // 标题条比封面晚 ~100ms 进场，给容器先到位的层级感
        titleBar.style.transition =
          `opacity ${Math.round(FLIP_DURATION_MS * 0.7)}ms ${FLIP_EASE} ${Math.round(FLIP_DURATION_MS * 0.18)}ms, ` +
          `transform ${Math.round(FLIP_DURATION_MS * 0.7)}ms ${FLIP_EASE} ${Math.round(FLIP_DURATION_MS * 0.18)}ms`;
        titleBar.style.opacity = "1";
        titleBar.style.transform = "translate3d(0, 0, 0)";
      }
    });

    const onEnd = (e: TransitionEvent) => {
      if (e.target !== cover) return;
      if (e.propertyName !== "width" && e.propertyName !== "height") return;
      cover.removeEventListener("transitionend", onEnd);
      setPhase("open");
    };
    cover.addEventListener("transitionend", onEnd);
    const fallback = window.setTimeout(() => setPhase("open"), FLIP_DURATION_MS + 140);
    return () => {
      cover.removeEventListener("transitionend", onEnd);
      window.clearTimeout(fallback);
    };
    // 写入闭包用到的 helpers
    function getRefs() {
      const backdrop = backdropRef.current;
      const coverHalo = coverHaloRef.current;
      const cover = coverRef.current;
      const lyric = lyricRef.current;
      if (!backdrop || !cover || !lyric) return null;
      return { backdrop, coverHalo, cover, lyric, titleBar: titleBarRef.current };
    }
  }, [phase, sourceCoverRect, sourceLyricRect, layout]);

  // 退场
  useLayoutEffect(() => {
    if (phase !== "closing") return;
    const backdrop = backdropRef.current;
    const coverHalo = coverHaloRef.current;
    const cover = coverRef.current;
    const lyric = lyricRef.current;
    const titleBar = titleBarRef.current;
    if (!backdrop || !cover || !lyric) {
      setPhase("closed");
      return;
    }

    const srcCover = sourceCoverRect();
    const srcLyric = sourceLyricRect();

    // 没源 rect 就纯淡出；移动端正常走 FLIP
    if (!srcCover) {
      backdrop.style.transition = `opacity 280ms ${CLOSE_EASE}`;
      backdrop.style.opacity = "0";
      if (coverHalo) {
        coverHalo.style.transition = `opacity 280ms ${CLOSE_EASE}`;
        coverHalo.style.opacity = "0";
      }
      cover.style.transition = `opacity 280ms ${CLOSE_EASE}`;
      cover.style.opacity = "0";
      lyric.style.transition = `opacity 280ms ${CLOSE_EASE}`;
      lyric.style.opacity = "0";
      const t = window.setTimeout(() => setPhase("closed"), 320);
      return () => window.clearTimeout(t);
    }

    // close transition：cover 用全程，lyric 略快淡掉，backdrop 比 cover 提前
    // ~25% 完成 → 后半段直接显出底层播放页，关闭感更"轻"
    const t = transitionFor(
      ["top", "left", "width", "height", "border-radius", "opacity"],
      CLOSE_DURATION_MS,
      CLOSE_EASE,
    );

    cover.style.transition = t;
    if (coverHalo) {
      coverHalo.style.transition = transitionFor(
        ["top", "left", "width", "height", "opacity"],
        CLOSE_DURATION_MS,
        CLOSE_EASE,
      );
      applyRectCss(coverHalo, srcCover);
      coverHalo.style.opacity = "0";
    }
    applyRectCss(cover, srcCover);
    cover.style.borderRadius = "12px";

    if (srcLyric && !layout.isDesktop) {
      // 歌词位置过渡比 cover 短一截 + 自身 opacity 在 ~60% 时淡掉，
      // 不让长行歌词拖到最后还压在画面上
      lyric.style.transition =
        `top ${Math.round(CLOSE_DURATION_MS * 0.85)}ms ${CLOSE_EASE}, ` +
        `left ${Math.round(CLOSE_DURATION_MS * 0.85)}ms ${CLOSE_EASE}, ` +
        `width ${Math.round(CLOSE_DURATION_MS * 0.85)}ms ${CLOSE_EASE}, ` +
        `height ${Math.round(CLOSE_DURATION_MS * 0.85)}ms ${CLOSE_EASE}, ` +
        `opacity ${Math.round(CLOSE_DURATION_MS * 0.55)}ms ${CLOSE_EASE}`;
      applyRectCss(lyric, srcLyric);
    } else if (layout.isDesktop) {
      lyric.style.transition = `opacity ${Math.round(CLOSE_DURATION_MS * 0.45)}ms ${CLOSE_EASE}`;
    }
    lyric.style.opacity = "0";

    // backdrop 提前结束（70% 时长） —— 模糊封面背景率先淡出，
    // 露出底层播放页，cover 还在收回但视觉上"页面已经回到主视图"
    backdrop.style.transition = `opacity ${Math.round(CLOSE_DURATION_MS * 0.7)}ms ${CLOSE_EASE}`;
    backdrop.style.opacity = "0";

    if (titleBar) {
      titleBar.style.transition =
        `opacity ${Math.round(CLOSE_DURATION_MS * 0.4)}ms ${CLOSE_EASE}, ` +
        `transform ${Math.round(CLOSE_DURATION_MS * 0.4)}ms ${CLOSE_EASE}`;
      titleBar.style.opacity = "0";
      titleBar.style.transform = "translate3d(0, 6px, 0)";
    }

    const onEnd = (e: TransitionEvent) => {
      if (e.target !== cover) return;
      if (e.propertyName !== "width" && e.propertyName !== "height") return;
      cover.removeEventListener("transitionend", onEnd);
      setPhase("closed");
    };
    cover.addEventListener("transitionend", onEnd);
    const fallback = window.setTimeout(() => setPhase("closed"), CLOSE_DURATION_MS + 120);
    return () => {
      cover.removeEventListener("transitionend", onEnd);
      window.clearTimeout(fallback);
    };
  }, [phase, sourceCoverRect, sourceLyricRect, layout]);

  if (phase === "closed") return null;
  if (typeof document === "undefined") return null;

  const { current, isPlaying, positionSec, toggle, next, prev, lyric } = player;
  const immersiveDurationSec = Math.max(0, current?.durationSec ?? 0);
  const coverUrl = current?.cover ? cdn(current.cover) : null;
  const desktopBackdropColor = `rgb(${appleBackdropBaseRgb(topRgb, seamRgb, leftRgb, rightRgb)})`;
  // Apple fullscreen lyrics paints a song-specific opaque color field. Keep
  // the richer sampled gradient for the mobile immersive path only.
  const desktopBackdropImage = "none";
  const desktopBackdropArtworkImage = coverUrl ? cssUrl(coverUrl) : "none";

  return createPortal(
    <div
      ref={containerRef}
      className={layout.isDesktop ? "appleLyricsFullscreen" : undefined}
      style={{
        position: "fixed",
        inset: 0,
        zIndex: 9000,
        overflow: "hidden",
        // 桌面 Apple lyrics 是不透底的全屏环境；底色跟随封面采样，
        // artwork / veil 节点仍保留给过场结构，但桌面稳态不再显示它们。
        background: layout.isDesktop ? desktopBackdropColor : APPLE_LYRIC_BG_COLOR,
        backgroundImage: desktopBackdropImage,
        opacity: 1,
        isolation: "isolate",
      }}
    >
      {/* 背景：Apple Web fullscreen lyrics 的稳态底层是 nowPlayingBackdropBG
          对应的不透明色场。封面 artwork 仅保留在 DOM 中承接过场，桌面透明度为 0；
          移动端仍使用同源 blur artwork，不再给歌词列单独铺黑块。 */}
      <div
        ref={backdropRef}
        data-apple-lyrics-backdrop={layout.isDesktop ? "true" : undefined}
        aria-hidden
        style={{
          position: "absolute",
          inset: 0,
          opacity: phase === "closing" ? 0 : 1,
          overflow: "hidden",
          backgroundColor: layout.isDesktop ? desktopBackdropColor : APPLE_LYRIC_BG_COLOR,
          backgroundImage: desktopBackdropImage,
          backgroundPosition: "50%",
          backgroundRepeat: "no-repeat",
          backgroundSize: "cover",
        }}
      >
        {coverUrl && (
          <div
            data-apple-lyrics-backdrop-artwork={layout.isDesktop ? "true" : undefined}
            aria-hidden
            style={{
              position: "absolute",
              inset: layout.isDesktop ? "-16%" : "-8%",
              backgroundImage: layout.isDesktop ? desktopBackdropArtworkImage : cssUrl(coverUrl),
              backgroundSize: "cover",
              backgroundPosition: "center",
              filter: layout.isDesktop
                ? APPLE_DESKTOP_BACKDROP_ARTWORK_FILTER
                : IMMERSIVE_BACKDROP_ARTWORK_FILTER,
              opacity: layout.isDesktop
                ? APPLE_DESKTOP_BACKDROP_ARTWORK_OPACITY
                : IMMERSIVE_BACKDROP_ARTWORK_OPACITY,
              transform: layout.isDesktop ? "scale(1.22)" : "scale(1.16)",
              pointerEvents: "none",
            }}
          />
        )}
        <div
          data-apple-lyrics-backdrop-veil={layout.isDesktop ? "true" : undefined}
          aria-hidden
          style={{
            position: "absolute",
            inset: 0,
            background: layout.isDesktop ? APPLE_DESKTOP_BACKDROP_VEIL : IMMERSIVE_BACKDROP_VEIL,
            pointerEvents: "none",
          }}
        />
      </div>

      {/* 封面边缘融合层：只保留中间一圈过渡带，外缘必须归零到 transparent。
          这样边界处露出来的是背景色彩云，而不是一圈模糊封面贴片。 */}
      {coverUrl && (
        <div
          ref={coverHaloRef}
          data-apple-lyrics-cover-halo={layout.isDesktop ? "true" : undefined}
          aria-hidden
          style={{
            position: "absolute",
            ...layout.cover,
            zIndex: 1,
            pointerEvents: "none",
            opacity: layout.isDesktop ? APPLE_DESKTOP_COVER_HALO_OPACITY : IMMERSIVE_COVER_HALO_OPACITY,
            backgroundImage: cssUrl(coverUrl),
            backgroundSize: "cover",
            backgroundPosition: "center",
            filter: layout.isDesktop
              ? APPLE_DESKTOP_ARTWORK_RADIOSITY_FILTER
              : "blur(34px) saturate(1.16) brightness(1.02)",
            transform: layout.isDesktop ? "scale(0.92)" : "scale(1.08)",
            transformOrigin: layout.isDesktop ? "bottom center" : "center",
            WebkitMaskImage: layout.coverHaloMask,
            maskImage: layout.coverHaloMask,
            borderRadius: layout.isDesktop ? appleArtworkRadiusCss() : undefined,
          }}
        />
      )}

      {/* 封面：尺寸 / 位置 / mask 全部由 layout 决定。
          - 手机端单向 mask（底部渐隐进歌词区）
          - 桌面端多向 mask（intersect）：四边都柔化溶进同源模糊 backdrop

          mask 一直挂着 —— 这是唯一能让"封面底部颜色"和"歌词区背后的 backdrop"
          视觉无缝衔接的方式（mask 让封面底部变透明，露出背后同源模糊 backdrop，
          backdrop 是全屏的，从封面下沿一直延续到歌词区，肉眼看像一整张图）。
          之前试过 phase 闸控 / overlay 兜底 mask 平滑过渡，效果都更差：
            - phase 闸控：mask 在 opening 末尾 snap-on，肉眼能读到"底部一闪"
            - overlay：用 seam 色实色画在 cover 底，跟下方真正的 backdrop 不同
              颜色 → 封面下沿和歌词区之间出现一条硬边
          真正的 flicker 源是另一处：opening 时 backdrop 透明度还在 0→1 爬升，
          mask 透出的"背后"短暂是 opacity 0 的 backdrop（即播放页本体），而不是
          模糊封面 → 封面底部那 45% 一闪就是播放页的控件区。
          解决方案在下面 useLayoutEffect 里：opening 阶段 backdrop 直接 opacity 1，
          不再走渐入。这样 mask 透出的从第一帧起就是同源模糊封面，颜色连续。 */}
      <div
        ref={coverRef}
        data-apple-lyrics-cover={layout.isDesktop ? "true" : undefined}
        onClick={onClose}
        style={{
          position: "absolute",
          ...layout.cover,
          overflow: "hidden",
          cursor: "pointer",
          zIndex: 2,
          borderRadius: layout.isDesktop ? appleArtworkRadiusCss() : undefined,
          boxShadow: layout.isDesktop ? APPLE_DESKTOP_ARTWORK_SHADOW : undefined,
          WebkitMaskImage: layout.coverMask === "none" ? undefined : layout.coverMask,
          WebkitMaskComposite: layout.coverMask === "none"
            ? undefined
            : layout.coverMaskComposite === "intersect"
              ? "source-in"
              : "source-over",
          maskImage: layout.coverMask === "none" ? undefined : layout.coverMask,
          maskComposite: layout.coverMask === "none" ? undefined : layout.coverMaskComposite,
        }}
      >
        {coverUrl ? (
          // eslint-disable-next-line @next/next/no-img-element
          <img
            src={coverUrl}
            alt=""
            style={{
              position: "absolute",
              inset: 0,
              width: "100%",
              height: "100%",
              objectFit: "cover",
              display: "block",
              ...(layout.coverImageMask && !layout.isDesktop
                ? {
                    WebkitMaskImage: layout.coverImageMask,
                    maskImage: layout.coverImageMask,
                  }
                : {}),
              transform: layout.isDesktop ? "none" : isPlaying ? "scale(1.012)" : "scale(1)",
              transition: layout.isDesktop ? "none" : "transform 4000ms ease-in-out",
              willChange: "transform",
            }}
          />
        ) : (
          <div
            style={{
              position: "absolute",
              inset: 0,
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
            }}
          >
            <Waveform height={120} bars={32} gap={4} />
          </div>
        )}
      </div>

      {/* 标题 + 控件条
          - 手机：叠在封面下半部（封面底部 mask 让颜色淡出），独立兄弟节点不吃 mask
          - 桌面：放在封面正下方，title / 按钮 显著放大，作为画面主体的延续 */}
      <div
        ref={titleBarRef}
        style={{
          position: "absolute",
          left: layout.title.left,
          width: layout.title.width,
          top: layout.title.top,
          display: "flex",
          flexDirection: layout.isDesktop ? "column" : "row",
          alignItems: "center",
          justifyContent: layout.isDesktop ? "center" : undefined,
          gap: layout.isDesktop ? 18 : 14,
          opacity: 0,
          zIndex: 4,
        }}
      >
        <div
          style={{
            minWidth: 0,
            flex: layout.isDesktop ? "0 0 auto" : "1 1 auto",
            width: layout.isDesktop ? "100%" : undefined,
            textAlign: layout.isDesktop ? "center" : undefined,
          }}
        >
          <div
            style={{
              ...(layout.isDesktop ? immersiveTitleLarge : immersiveTitle),
              color: chromeFgColor,
            }}
          >
            {current?.title ?? "—"}
          </div>
          <div
            style={{
              ...(layout.isDesktop ? immersiveSubtitleLarge : immersiveSubtitle),
              color: chromeFgDimColor,
            }}
          >
            {current?.artist ?? ""}
          </div>
        </div>
        {layout.isDesktop && (
          <ImmersiveProgress
            positionSec={positionSec}
            durationSec={immersiveDurationSec}
            onSeek={player.seek}
            color={chromeFgColor}
            dimColor={chromeFgDimColor}
          />
        )}
        <div
          style={{
            display: "flex",
            gap: layout.isDesktop ? 22 : 8,
            flexShrink: 0,
            alignItems: "center",
            justifyContent: "center",
          }}
        >
          {layout.isDesktop && (
            <ImmersiveIconBtn
              onClick={(e) => {
                e.stopPropagation();
                prev();
              }}
              aria-label="上一首"
              color={chromeFgColor}
              size="large"
            >
              <AppIcon name="previous" size={24} />
            </ImmersiveIconBtn>
          )}
          <ImmersiveIconBtn
            onClick={(e) => {
              e.stopPropagation();
              toggle();
            }}
            aria-label={isPlaying ? "暂停" : "播放"}
            color={chromeFgColor}
            size={layout.isDesktop ? "large" : "small"}
          >
            <AppIcon name={isPlaying ? "pause" : "play"} size={24} />
          </ImmersiveIconBtn>
          <ImmersiveIconBtn
            onClick={(e) => {
              e.stopPropagation();
              next();
            }}
            aria-label="下一首"
            color={chromeFgColor}
            size={layout.isDesktop ? "large" : "small"}
          >
            <AppIcon name="next" size={24} />
          </ImmersiveIconBtn>
        </div>
      </div>

      {layout.isDesktop && (
        <div
          data-apple-lyrics-column-veil="true"
          aria-hidden
          style={{
            position: "absolute",
            top: layout.lyric.top,
            left: `calc(${layout.lyric.left} - ${APPLE_DESKTOP_LYRIC_COLUMN_VEIL_BLEED})`,
            right: 0,
            height: layout.lyric.height,
            overflow: "hidden",
            zIndex: 4,
            background: APPLE_DESKTOP_LYRIC_COLUMN_VEIL,
            backgroundImage: "none",
            opacity: 0,
            pointerEvents: "none",
          }}
        />
      )}

      {/* 歌词层 —— 位置由 layout 决定（手机贴封面下沿，桌面在 cover 右侧）。
          桌面按 Apple 的 lyrics 列使用 plus-lighter 混合，不再额外铺黑色卡片。 */}
      <div
        ref={lyricRef}
        data-apple-lyrics-column={layout.isDesktop ? "true" : undefined}
        style={{
          position: "absolute",
          ...layout.lyric,
          maxWidth: layout.isDesktop ? 972.8 : undefined,
          overflow: "hidden",
          zIndex: 5,
          backgroundColor: layout.isDesktop ? "transparent" : undefined,
          backgroundImage: "none",
          opacity: 1,
          mixBlendMode: layout.isDesktop
            ? ("plus-lighter" as React.CSSProperties["mixBlendMode"])
            : undefined,
        }}
      >
        <div
          data-apple-lyrics-text-blend={layout.isDesktop ? "true" : undefined}
          style={{
            position: "relative",
            width: "100%",
            height: "100%",
            mixBlendMode: undefined,
          }}
	        >
	          <LyricColumn
	            lines={lyric?.lines ?? []}
	            yrcLines={lyric?.yrcLines ?? []}
	            positionSec={positionSec}
	            isPlaying={isPlaying}
	            sessionId={current?.id}
	            meta={lyric}
	            desktopAppleMotion={isDesktop}
	            fgColor={lyricFgColor}
	            fgDimColor={lyricFgDimColor}
	            fgUnsungColor={lyricFgUnsungColor}
	            onSeekToSec={player.seek}
	          />
	        </div>
	      </div>
    </div>,
    document.body,
  );
}

// ---- helpers ----

type TargetSpec =
  | { mode: "css"; top: string; left: string; width: string; height: string }
  | { mode: "rect"; top: number; left: number; width: number; height: number };

// ============== 沉浸式布局参数 ==============
//
// 根据 isDesktop 算出一整套位置 / 尺寸：cover / title / lyric 的 top/left/width/
// height，mask 方向，背景 gradient。手机 stacked、桌面 side-by-side 两套，
// 完全靠这一个函数切换。

type Pos = { top: string; left: string; width: string; height: string };

type ImmersiveLayout = {
  cover: Pos;
  title: { top: string; left: string; width: string };
  lyric: Pos;
  // 封面 mask（可能是单条或多条 linear-gradient 组合）
  coverMask: string;
  coverImageMask?: string;
  coverHaloMask: string;
  // 多 mask 时用 "intersect" 求交；单 mask 默认 "add"
  coverMaskComposite: "intersect" | "add";
  // 背景 gradient（采样色驱动，作为模糊封面 backdrop 拉不到时的兜底底色）
  bgGradient: string;
  // 文字色 / 弱化色（根据 bg 亮度自适应）
  fgColor: string;
  fgDimColor: string;
  // 桌面 / 手机：影响 title 字号、按钮大小、布局走向
  isDesktop: boolean;
};

export function computeLayout(
  isDesktop: boolean,
  topRgb: string,
  seamRgb: string,
  leftRgb: string,
  rightRgb: string,
  fgColor: string,
  fgDimColor: string,
): ImmersiveLayout {
  if (!isDesktop) {
    // ---- 手机 stacked：cover 顶 / lyric 底 ----
    const W = "min(100vw, 78vh, 700px)";
    const top = `clamp(0px, calc(100vw - ${W}), 32px)`;
    const left = `calc((100vw - ${W}) / 2)`;
    const lyricTop = `calc(${top} + ${W} + clamp(8px, 1.6vh, 16px))`;
    // title 横向内边距跟下方歌词区 inner padding 严格对齐 —— 歌名和歌词左右两边
    // 同一条竖线，视觉上整张页面是一根脊柱。
    // 移动端用户希望左右更"透气"，原 clamp(20, 5vw, 36) 加大 1/3 → 28-48
    const titlePadX = "clamp(28px, 6.7vw, 48px)";
    return {
      cover: { top, left, width: W, height: W },
      title: {
        top: `calc(${top} + ${W} * 0.82)`,
        left: `calc(${left} + ${titlePadX})`,
        width: `calc(${W} - 2 * ${titlePadX})`,
      },
      lyric: {
        top: lyricTop,
        left,
        width: W,
        height: `calc(100vh - (${lyricTop}) - clamp(8px, 2vh, 24px))`,
      },
      // 多 stop 平滑底部溶解：
      // 0..38% 全亮，然后快速落到 0；最外缘必须透明，避免封面边界像贴片。
      coverMask:
        "linear-gradient(to bottom, " +
        "rgba(0,0,0,1) 0%, " +
        "rgba(0,0,0,1) 38%, " +
        "rgba(0,0,0,0.62) 54%, " +
        "rgba(0,0,0,0.26) 68%, " +
        "rgba(0,0,0,0.07) 82%, " +
        "transparent 94%, " +
        "transparent 100%)",
      coverHaloMask:
        "linear-gradient(to bottom, " +
        "transparent 0%, " +
        "transparent 38%, " +
        "rgba(0,0,0,0.34) 56%, " +
        "rgba(0,0,0,0.42) 72%, " +
        "rgba(0,0,0,0.16) 86%, " +
        "transparent 100%)",
      coverMaskComposite: "add",
      bgGradient:
        `linear-gradient(180deg, ` +
        `rgb(${topRgb}) 0px, ` +
        `rgb(${topRgb}) ${top}, ` +
        `rgb(${seamRgb}) calc(${top} + ${W}), ` +
        `rgb(${seamRgb}) 100%)`,
      fgColor,
      fgDimColor,
      isDesktop: false,
    };
  }
  // ---- 桌面：Apple Music fullscreen lyrics 的 controls / lyrics 两列 ----
  // Apple Web 的容器是 grid-template-columns: 32vw 40vw; padding: 0 10vw。
  // 这里保留用户要的"海报溶解"：清晰 artwork 仍是左侧方形主体，外圈同源
  // radiosity 光晕融进全屏 backdrop，而不是把清晰大图铺在整张背景里。
  const coverColumnW = "var(--apple-lyrics-cover-column-width, 32vw)";
  const lyricsColumnW = "var(--apple-lyrics-column-width, 40vw)";
  const pagePadX = "var(--apple-lyrics-page-padding, 10vw)";
  const W = `min(${coverColumnW}, calc(100vh - 241px), 600px)`;
  const left = `calc(${pagePadX} + (${coverColumnW} - ${W}) / 2)`;
  const top = `max(52px, calc((100vh - (${W} + 201px)) / 2 + 20px))`;
  const backdropRgb = appleBackdropBaseRgb(topRgb, seamRgb, leftRgb, rightRgb);
  return {
    cover: { top, left, width: W, height: W },
    title: {
      top: `calc(${top} + ${W} + 18px)`,
      left,
      width: W,
    },
    lyric: {
      top: "0",
      left: `calc(100vw - ${pagePadX} - ${lyricsColumnW})`,
      width: lyricsColumnW,
      height: "100vh",
    },
    coverMask:
      "linear-gradient(180deg, " +
      "transparent 0%, rgba(0,0,0,.24) 3%, rgba(0,0,0,.72) 8%, #000 14%, #000 78%, rgba(0,0,0,.70) 86%, rgba(0,0,0,.30) 94%, transparent 100%), " +
      "linear-gradient(90deg, " +
      "transparent 0%, rgba(0,0,0,.28) 3%, rgba(0,0,0,.76) 8%, #000 13%, #000 87%, rgba(0,0,0,.76) 92%, rgba(0,0,0,.28) 97%, transparent 100%)",
    coverImageMask: undefined,
    coverHaloMask:
      "radial-gradient(ellipse 74% 74% at 50% 52%, rgba(0,0,0,.72) 0%, rgba(0,0,0,.38) 44%, rgba(0,0,0,.10) 66%, transparent 100%)",
    coverMaskComposite: "intersect",
    // 桌面背景以采样色场为准；该梯度仅作为布局数据保留，不参与稳态 artwork 铺底。
    bgGradient:
      `linear-gradient(90deg, rgba(${leftRgb},0.36), rgba(${rightRgb},0.24)), ` +
      `linear-gradient(180deg, rgba(${topRgb},0.22), rgba(${backdropRgb},0.46)), ` +
      `linear-gradient(180deg, rgb(${backdropRgb}), rgb(${backdropRgb}))`,
    fgColor,
    fgDimColor,
    isDesktop: true,
  };
}

function computeImmersiveTargets(layout: ImmersiveLayout): {
  cover: TargetSpec;
  lyric: TargetSpec;
} {
  return {
    cover: {
      mode: "css",
      top: layout.cover.top,
      left: layout.cover.left,
      width: layout.cover.width,
      height: layout.cover.height,
    },
    lyric: {
      mode: "css",
      top: layout.lyric.top,
      left: layout.lyric.left,
      width: layout.lyric.width,
      height: layout.lyric.height,
    },
  };
}

function applyRectImmediate(
  el: HTMLElement,
  rect: { top: number; left: number; width: number; height: number },
) {
  el.style.transition = "none";
  el.style.top = rect.top + "px";
  el.style.left = rect.left + "px";
  el.style.width = rect.width + "px";
  el.style.height = rect.height + "px";
}

function applyRectCss(
  el: HTMLElement,
  rect: { top: number; left: number; width: number; height: number },
) {
  el.style.top = rect.top + "px";
  el.style.left = rect.left + "px";
  el.style.width = rect.width + "px";
  el.style.height = rect.height + "px";
}

function applyTargetImmediate(el: HTMLElement, t: TargetSpec) {
  el.style.transition = "none";
  applyTargetCss(el, t);
}

function applyTargetCss(el: HTMLElement, t: TargetSpec) {
  if (t.mode === "css") {
    el.style.top = t.top;
    el.style.left = t.left;
    el.style.width = t.width;
    el.style.height = t.height;
  } else {
    el.style.top = t.top + "px";
    el.style.left = t.left + "px";
    el.style.width = t.width + "px";
    el.style.height = t.height + "px";
  }
}

function transitionFor(
  props: string[],
  durationMs: number = FLIP_DURATION_MS,
  ease: string = FLIP_EASE,
): string {
  return props.map((p) => `${p} ${durationMs}ms ${ease}`).join(", ");
}

// 根据 RGB 计算亮度（perceptual luma），决定文字用深色还是浅色
// useCoverEdgeColors / computeTone 已抽到 @/lib/cover-color，给歌词页 + 歌单页共用。

function ImmersiveIconBtn({
  children,
  onClick,
  "aria-label": ariaLabel,
  color = "rgba(255,255,255,0.92)",
  size = "small",
}: {
  children: React.ReactNode;
  onClick?: (e: React.MouseEvent) => void;
  "aria-label"?: string;
  color?: string;
  size?: "small" | "large";
}) {
  const isLarge = size === "large";
  return (
    <button
      onClick={onClick}
      aria-label={ariaLabel}
      className="platform-icon-button"
      style={{
        // small（手机）：跟手机 title (~16-20px) 等量级，按钮明显但不抢戏
        // large（桌面）：跟桌面 title (~28px) 同量级，"封面 + title + 按钮"
        //   形成一个完整视觉块
        fontSize: isLarge ? 26 : 22,
        width: isLarge ? 46 : 38,
        height: isLarge ? 46 : 38,
        display: "inline-flex",
        alignItems: "center",
        justifyContent: "center",
        cursor: "pointer",
        border: "none",
        background: "transparent",
        color,
        padding: 0,
        transition: "transform 120ms ease, opacity 160ms ease",
      }}
      onMouseDown={(e) => {
        e.currentTarget.style.transform = "scale(0.9)";
      }}
      onMouseUp={(e) => {
        e.currentTarget.style.transform = "scale(1)";
      }}
      onMouseLeave={(e) => {
        e.currentTarget.style.transform = "scale(1)";
      }}
    >
      {children}
    </button>
  );
}

function ImmersiveProgress({
  positionSec,
  durationSec,
  onSeek,
  color,
  dimColor,
}: {
  positionSec: number;
  durationSec: number;
  onSeek: (sec: number) => void;
  color: string;
  dimColor: string;
}) {
  const draggingRef = useRef(false);
  const [previewSec, setPreviewSec] = useState<number | null>(null);
  const safeDuration = Math.max(0, durationSec);
  const displayedSec = Math.min(safeDuration, Math.max(0, previewSec ?? positionSec));
  const progress = safeDuration > 0 ? displayedSec / safeDuration : 0;

  const commit = (value: number) => {
    const nextSec = Math.min(safeDuration, Math.max(0, value));
    draggingRef.current = false;
    setPreviewSec(null);
    onSeek(nextSec);
  };

  return (
    <div
      style={{
        width: "min(100%, 520px)",
        display: "flex",
        flexDirection: "column",
        gap: 2,
      }}
    >
      <input
        className="immersive-progress"
        type="range"
        min={0}
        max={Math.max(0.1, safeDuration)}
        step={0.05}
        value={displayedSec}
        disabled={safeDuration <= 0}
        aria-label="播放进度"
        aria-valuetext={`${fmt(displayedSec)} / ${fmt(safeDuration)}`}
        onPointerDown={(e) => {
          draggingRef.current = true;
        }}
        onPointerUp={(e) => {
          commit(Number(e.currentTarget.value));
        }}
        onPointerCancel={(e) => commit(Number(e.currentTarget.value))}
        onChange={(e) => {
          const value = Number(e.currentTarget.value);
          if (draggingRef.current) setPreviewSec(value);
          else commit(value);
        }}
        onBlur={(e) => {
          if (draggingRef.current) commit(Number(e.currentTarget.value));
        }}
        style={
          {
            color,
            opacity: safeDuration > 0 ? 1 : 0.38,
            "--immersive-progress": `${(progress * 100).toFixed(3)}%`,
          } as React.CSSProperties & { "--immersive-progress": string }
        }
      />
      <div
        style={{
          display: "flex",
          justifyContent: "space-between",
          color: dimColor,
          fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace",
          fontSize: 10.5,
          fontVariantNumeric: "tabular-nums",
          lineHeight: 1.2,
        }}
      >
        <span>{fmt(displayedSec)}</span>
        <span>-{fmt(Math.max(0, safeDuration - displayedSec))}</span>
      </div>
    </div>
  );
}



// ============== compact CoverBox（v5 原样，加 ref 转发） ==============

const CoverBox = React.forwardRef<
  HTMLDivElement,
  { cover?: string | null; isPlaying: boolean; hidden?: boolean }
>(function CoverBox({ cover, isPlaying, hidden = false }, ref) {
  const coverUrl = cover ? cdn(cover) : null;
  return (
    <div
      ref={ref}
      data-claudio-cover
      style={{
        width: "100%",
        aspectRatio: "1 / 1",
        borderRadius: 12,
        overflow: "hidden",
        position: "relative",
        boxShadow:
          "0 24px 64px rgba(0,0,0,0.5), 0 0 0 1px rgba(255,255,255,0.04)",
        opacity: hidden ? 0 : 1,
        transform: isPlaying ? "scale(1.012)" : "scale(1)",
        transition: "transform 4000ms ease-in-out",
        background:
          "linear-gradient(135deg, rgba(155,227,198,0.10) 0%, rgba(155,227,198,0.02) 100%)",
      }}
    >
      <CoverImageLayer key={coverUrl ?? "empty-cover"} url={coverUrl} />
      <AiCoverCaption hidden={hidden} />
      <div
        style={{
          ...coverPlaceholder,
          opacity: coverUrl ? 0 : 1,
          transition: `opacity ${COVER_TRANSITION_MS}ms cubic-bezier(0.22, 1, 0.36, 1)`,
        }}
      >
        <Waveform height={80} bars={28} gap={3} />
      </div>
    </div>
  );
});

function CoverImageLayer({ url }: { url: string | null }) {
  const [isLoaded, setIsLoaded] = useState(false);
  if (!url) return null;
  return (
    // eslint-disable-next-line @next/next/no-img-element
    <img
      src={url}
      alt=""
      style={{
        position: "absolute",
        inset: 0,
        width: "100%",
        height: "100%",
        objectFit: "cover",
        display: "block",
        opacity: isLoaded ? 1 : 0,
        transform: isLoaded ? "scale(1)" : "scale(1.018)",
        filter: isLoaded ? "saturate(1)" : "saturate(0.92)",
        transition:
          `opacity ${COVER_TRANSITION_MS}ms cubic-bezier(0.22, 1, 0.36, 1), ` +
          `transform ${COVER_TRANSITION_MS}ms cubic-bezier(0.22, 1, 0.36, 1), ` +
          `filter ${COVER_TRANSITION_MS}ms cubic-bezier(0.22, 1, 0.36, 1)`,
        willChange: "opacity, transform",
      }}
      onLoad={() => setIsLoaded(true)}
      onError={(e) => {
        (e.target as HTMLImageElement).style.opacity = "0";
      }}
    />
  );
}

function ProgressBar({ pct }: { pct: number }) {
  return (
    <div style={progressTrack}>
      <div style={{ ...progressFill, width: `${pct * 100}%` }} />
    </div>
  );
}

function Controls({
  isPlaying,
  hasTrack,
  onPrev,
  onToggle,
  onNext,
}: {
  isPlaying: boolean;
  hasTrack: boolean;
  onPrev: () => void;
  onToggle: () => void;
  onNext: () => void;
}) {
  return (
    <div style={controlsRow}>
      <FlatBtn onClick={onPrev} disabled={!hasTrack} aria-label="上一首">
        <SkipBack />
      </FlatBtn>
      <FlatBtn
        onClick={onToggle}
        disabled={!hasTrack}
        aria-label={isPlaying ? "暂停" : "播放"}
        large
      >
        {isPlaying ? <PauseGlyph /> : <PlayGlyph />}
      </FlatBtn>
      <FlatBtn onClick={onNext} disabled={!hasTrack} aria-label="下一首">
        <SkipForward />
      </FlatBtn>
    </div>
  );
}

// ============== 歌词带（compact，3 行） ==============

const LYRIC_ROWS_COMPACT = 3;

function LyricStrip({
  lines,
  yrcLines,
  positionSec,
  meta,
}: {
  lines: LrcLine[];
  yrcLines: YrcLine[];
  positionSec: number;
  meta:
    | { lines: LrcLine[]; yrcLines: YrcLine[]; instrumental: boolean; uncollected: boolean }
    | null;
}) {
  const view = useLyricView({
    lines,
    yrcLines,
    positionSec,
    isPlaying: false,
    visibleRows: LYRIC_ROWS_COMPACT,
    rowH: LYRIC_ROW_H,
    rowGap: "0px",
    activeFs: LYRIC_ACTIVE_FS,
    dimFs: LYRIC_DIM_FS,
  });

  if (!meta) return <PlaceholderLyric text="歌词加载中…" mode="compact" />;
  if (meta.instrumental) return <PlaceholderLyric text="♪ 纯音乐 ♪" mode="compact" />;
  if (view.empty) {
    return (
      <PlaceholderLyric
        text={`这首歌没有歌词${meta.uncollected ? "（网易云收录不全）" : ""}`}
        mode="compact"
      />
    );
  }

  return (
    <MeasuredLyricColumn
      activeIdx={view.activeIdx}
      scrollIdx={view.scrollIdx}
      outerStyle={{
        ...lyricBox,
        // mask 必须挂在 outer（视口） —— 挂在 inner（滚动列）会跟着 translate
        // 一起平移，效果只在歌词列首尾两行出现，中段滚动看不到边缘渐隐
        WebkitMaskImage:
          "linear-gradient(180deg, transparent 0%, #000 18%, #000 82%, transparent 100%)",
        maskImage:
          "linear-gradient(180deg, transparent 0%, #000 18%, #000 82%, transparent 100%)",
      }}
      innerExtraStyle={{}}
      transitionMs={520}
    >
      {view.rows}
    </MeasuredLyricColumn>
  );
}

// ============== 歌词列（immersive） ==============

export function LyricColumn({
  lines,
  yrcLines,
  positionSec,
  isPlaying,
  sessionId,
  meta,
  desktopAppleMotion,
  fgColor,
  fgDimColor,
  fgUnsungColor,
  onSeekToSec,
}: {
  lines: LrcLine[];
  yrcLines: YrcLine[];
  positionSec: number;
  isPlaying: boolean;
  sessionId?: string;
  meta:
    | { lines: LrcLine[]; yrcLines: YrcLine[]; instrumental: boolean; uncollected: boolean }
    | null;
  desktopAppleMotion: boolean;
  fgColor: string;
  fgDimColor: string;
  fgUnsungColor: string;
  onSeekToSec: (sec: number) => void;
}) {
  const viewportWidth = useViewportWidth();
  const appleMetrics = appleLyricMetrics(viewportWidth);
  const activeFs = desktopAppleMotion ? `${appleMetrics.fontPx}px` : IMMERSIVE_ACTIVE_FS;
  const rowH = desktopAppleMotion ? `${appleMetrics.lineBoxPx}px` : IMMERSIVE_LYRIC_ROW_H;
  const rowGap = desktopAppleMotion ? `${appleMetrics.marginBottomPx}px` : "0px";
  const appleSupplementaryScrollKey = useMemo(
    () => desktopAppleMotion ? appleSupplementarySignature(yrcLines) : undefined,
    [desktopAppleMotion, yrcLines],
  );
  const previousSupplementaryScrollKeyRef = useRef(appleSupplementaryScrollKey);
  const [appleSupplementaryRevealState, setAppleSupplementaryRevealState] =
    useState<AppleSupplementaryRevealState>(() => ({
      key: appleSupplementaryScrollKey,
      visible: true,
    }));

  useLayoutEffect(() => {
    if (!desktopAppleMotion) {
      previousSupplementaryScrollKeyRef.current = appleSupplementaryScrollKey;
      setAppleSupplementaryRevealState({ key: appleSupplementaryScrollKey, visible: true });
      return;
    }
    const previousKey = previousSupplementaryScrollKeyRef.current;
    if (previousKey === appleSupplementaryScrollKey) return;
    previousSupplementaryScrollKeyRef.current = appleSupplementaryScrollKey;
    if (appleSupplementaryScrollKey == null || appleSupplementaryScrollKey === "none") {
      setAppleSupplementaryRevealState({ key: appleSupplementaryScrollKey, visible: true });
      return;
    }
    setAppleSupplementaryRevealState({ key: appleSupplementaryScrollKey, visible: false });
    let rafId = 0;
    let secondRafId = 0;
    rafId = window.requestAnimationFrame(() => {
      secondRafId = window.requestAnimationFrame(() => {
        setAppleSupplementaryRevealState({ key: appleSupplementaryScrollKey, visible: true });
      });
    });
    return () => {
      window.cancelAnimationFrame(rafId);
      if (secondRafId) window.cancelAnimationFrame(secondRafId);
    };
  }, [appleSupplementaryScrollKey, desktopAppleMotion]);

  const appleSupplementaryVisible = !desktopAppleMotion ||
    appleSupplementaryScrollKey == null ||
    appleSupplementaryScrollKey === "none" ||
    (appleSupplementaryRevealState.key === appleSupplementaryScrollKey &&
      appleSupplementaryRevealState.visible);
  const view = useLyricView({
    lines,
    yrcLines,
    positionSec,
    isPlaying,
    sessionId,
    visibleRows: IMMERSIVE_ROWS,
    rowH,
    rowGap,
    activeFs,
    dimFs: activeFs,
    appleViewportWidth: viewportWidth,
    immersive: true,
    desktopAppleMotion,
    fgColor,
    fgDimColor,
    fgUnsungColor,
    appleSupplementaryVisible,
    onSeekToSec,
  });

  if (!meta) return <PlaceholderLyric text="歌词加载中…" mode="immersive" />;
  if (meta.instrumental) return <PlaceholderLyric text="♪ 纯音乐 ♪" mode="immersive" />;
  if (view.empty) {
    return (
      <PlaceholderLyric
        text={`这首歌没有歌词${meta.uncollected ? "（网易云收录不全）" : ""}`}
        mode="immersive"
      />
    );
  }

  return (
    <MeasuredLyricColumn
      activeIdx={view.activeIdx}
      scrollIdx={view.scrollIdx}
      // Apple Music Web fullscreen passes offset-ratio=.4 to amp-lyrics.
      anchorMode="center"
      focusFraction={desktopAppleMotion ? APPLE_LYRIC_FULLSCREEN_OFFSET_RATIO : 0.11}
      focusOffsetPx={0}
      outerStyle={{
        ...(desktopAppleMotion ? appleDesktopLyricFrame : immersiveLyricFrame),
        WebkitMaskImage: desktopAppleMotion
          ? APPLE_LYRIC_IDLE_MASK
          : "linear-gradient(180deg, transparent 0%, rgba(0,0,0,0.10) 5%, rgba(0,0,0,0.50) 10%, #000 14%, #000 80%, rgba(0,0,0,0.40) 94%, transparent 100%)",
        maskImage: desktopAppleMotion
          ? APPLE_LYRIC_IDLE_MASK
          : "linear-gradient(180deg, transparent 0%, rgba(0,0,0,0.10) 5%, rgba(0,0,0,0.50) 10%, #000 14%, #000 80%, rgba(0,0,0,0.40) 94%, transparent 100%)",
      }}
      innerExtraStyle={{
        // 横向 padding 跟移动端 titlePadX 同步加大 1/3：28-48；
        // 桌面 6.7vw 的上限也是 48，比原 36 多一档透气
        padding: desktopAppleMotion
          ? "0"
          : "clamp(20px, 4vh, 36px) clamp(28px, 6.7vw, 48px)",
      }}
      transitionMs={desktopAppleMotion ? APPLE_LYRIC_SCROLL_MS : 720}
      appleSupplementaryScrollKey={desktopAppleMotion ? appleSupplementaryScrollKey : undefined}
      applePlaybackJumpSerial={desktopAppleMotion ? view.applePlaybackJumpSerial : undefined}
      applePlaybackJumpTargetIdx={desktopAppleMotion ? view.applePlaybackJumpTargetIdx : undefined}
      onAppleScrollPrepared={desktopAppleMotion ? view.commitAppleVisualActive : undefined}
    >
      {view.rows}
    </MeasuredLyricColumn>
  );
}

// ============== Measure-based 歌词容器 ==============
//
// 不再用"每行 = rowH"做硬编码 translate —— 实际行高随歌词字数 / 换行变化，
// 用 ref 直接测量 active 行的 offsetTop + offsetHeight，确保激活行垂直居中
// 在容器里。长歌词换行也不会跟下一行重叠。

function useCompactSpringNumber(target: number, enabled = true): number {
  const [value, setValue] = useState(target);
  const valueRef = useRef(target);
  const velocityRef = useRef(0);

  useEffect(() => {
    if (!enabled) {
      valueRef.current = target;
      velocityRef.current = 0;
      setValue(target);
      return;
    }
    let raf = 0;
    let last = performanceNow();
    const stiffness = 190;
    const dampingRatio = 0.92;
    const damping = 2 * dampingRatio * Math.sqrt(stiffness);
    const tick = () => {
      const now = performanceNow();
      const dt = Math.min(0.034, Math.max(0.001, (now - last) / 1000));
      last = now;
      const x = valueRef.current;
      const v = velocityRef.current;
      const accel = -stiffness * (x - target) - damping * v;
      const nextV = v + accel * dt;
      const nextX = x + nextV * dt;
      if (Math.abs(nextX - target) < 0.35 && Math.abs(nextV) < 0.35) {
        valueRef.current = target;
        velocityRef.current = 0;
        setValue(target);
        return;
      }
      valueRef.current = nextX;
      velocityRef.current = nextV;
      setValue(nextX);
      raf = window.requestAnimationFrame(tick);
    };
    raf = window.requestAnimationFrame(tick);
    return () => window.cancelAnimationFrame(raf);
  }, [target, enabled]);

  return value;
}

function MeasuredLyricColumn({
  activeIdx,
  scrollIdx,
  /** 焦点行落在容器纵向多少位置（0..1）。compact = 0.5（居中），immersive = 0.22（偏上） */
  focusFraction = 0.5,
  focusOffsetPx = 0,
  anchorMode = "center",
  outerStyle,
  innerExtraStyle,
  transitionMs,
  appleSupplementaryScrollKey,
  applePlaybackJumpSerial,
  applePlaybackJumpTargetIdx,
  onAppleScrollPrepared,
  children,
}: {
  activeIdx: number;
  scrollIdx?: number;
  focusFraction?: number;
  focusOffsetPx?: number;
  anchorMode?: "center" | "top";
  outerStyle: React.CSSProperties;
  innerExtraStyle: React.CSSProperties;
  transitionMs: number;
  appleSupplementaryScrollKey?: string;
  applePlaybackJumpSerial?: number;
  applePlaybackJumpTargetIdx?: number;
  onAppleScrollPrepared?: () => void;
  children: React.ReactNode;
}) {
  const outerRef = useRef<HTMLDivElement>(null);
  const innerRef = useRef<HTMLDivElement>(null);
  const appleTopSpacerRef = useRef<HTMLDivElement>(null);
  const appleGeometryRef = useRef<AppleLyricGeometrySnapshot | null>(null);
  const appleActiveIdxRef = useRef(activeIdx);
  const appleMeasuredActiveIdxRef = useRef(activeIdx);
  const appleMeasuredPlaybackJumpSerialRef = useRef(applePlaybackJumpSerial ?? 0);
  const appleLineTransitionSerialRef = useRef(0);
  const appleScrollAnimationRef = useRef<AppleScrollAnimationState>({
    rafIds: new Map(),
    serial: 0,
    active: false,
    value: 0,
    velocity: 0,
    target: 0,
    lastFrameAtMs: 0,
    focusViewportOffsetPx: APPLE_LYRIC_TOP_OFFSET_PX + APPLE_LYRIC_SCROLL_TOP_MARGIN_PX,
    focusSpanPx: 1,
    spatialRows: [],
  });
  const appleGeometrySamplerRafRef = useRef<number | null>(null);
  const appleScrollReadyRef = useRef(false);
  const appleSupplementaryScrollKeyRef = useRef(appleSupplementaryScrollKey);
  const measureAndPlaceRef = useRef<((options?: { force?: boolean }) => void) | null>(null);
  const isAppleScroll = transitionMs === APPLE_LYRIC_SCROLL_MS;
  const [appleViewportHeight, setAppleViewportHeight] = useState(0);
  const appleTopOffsetPx = isAppleScroll && appleViewportHeight > 0
    ? Math.max(0, appleViewportHeight * focusFraction + focusOffsetPx)
    : APPLE_LYRIC_TOP_OFFSET_PX;
  const [translateY, setTranslateY] = useState(0);
  appleActiveIdxRef.current = activeIdx;
  // compact/mobile 仍使用自己的 translate spring；桌面路径在
  // performAppleScrollTop() 内使用 retained-velocity scroll spring。
  const compactSpringTranslateY = useCompactSpringNumber(translateY, transitionMs >= 700);
  const animatedTranslateY = transitionMs >= 700
    ? compactSpringTranslateY
    : translateY;
  const captureAppleGeometryFromDom = useCallback(() => {
    const outer = outerRef.current;
    const inner = innerRef.current;
    if (!outer || !inner) return null;
    const snapshot = captureAppleLyricGeometry(
      outer,
      inner,
      appleTopSpacerRef.current,
      appleActiveIdxRef.current,
    );
    appleGeometryRef.current = snapshot;
    writeAppleLyricGeometryDebug(outer, snapshot);
    return snapshot;
  }, []);
  const scheduleAppleGeometrySampler = useCallback(() => {
    if (!isAppleScroll) return;
    if (appleGeometrySamplerRafRef.current != null) {
      window.cancelAnimationFrame(appleGeometrySamplerRafRef.current);
      appleGeometrySamplerRafRef.current = null;
    }
    const startedAt = performanceNow();
    const tick = () => {
      captureAppleGeometryFromDom();
      const elapsed = performanceNow() - startedAt;
      if (elapsed <= APPLE_LYRIC_LAYOUT_MS + 80 || appleScrollAnimationRef.current.active) {
        appleGeometrySamplerRafRef.current = window.requestAnimationFrame(tick);
        return;
      }
      appleGeometrySamplerRafRef.current = null;
    };
    appleGeometrySamplerRafRef.current = window.requestAnimationFrame(tick);
  }, [captureAppleGeometryFromDom, isAppleScroll]);
  const measureAndPlace = useCallback((options: { force?: boolean } = {}) => {
    const outer = outerRef.current;
    const inner = innerRef.current;
    if (!outer || !inner) return;
    const force = options.force === true;
    const currentAppleLine = isAppleScroll
      ? inner.querySelector<HTMLElement>('[data-active="1"]')
      : null;
    const target = isAppleScroll
      ? activeIdx >= 0
        ? inner.querySelector<HTMLElement>(`[data-apple-lyric-row-index="${activeIdx}"]`)
        : null
      : inner.querySelector<HTMLElement>('[data-scroll-target="1"]') ??
        inner.querySelector<HTMLElement>('[data-active="1"]');
    if (!target) return;
    if (isAppleScroll) {
      if (!currentAppleLine) return;
      const currentAppleLineIndex = Number.parseInt(
        currentAppleLine.dataset.appleLyricRowIndex ?? "",
        10,
      );
      if (!force && appleScrollReadyRef.current && currentAppleLineIndex === activeIdx) return;
      const targetIndex = Number.parseInt(target.dataset.appleLyricRowIndex ?? "", 10);
      const cachedGeometry = appleGeometryRef.current;
      if (!force && appleScrollAnimationRef.current.active && cachedGeometry?.activeIdx === activeIdx) {
        return;
      }
      const cachedTarget = Number.isFinite(targetIndex)
        ? cachedGeometry?.rows[targetIndex]
        : undefined;
      const useCachedAppleTarget = Boolean(
        !force &&
          cachedGeometry &&
          cachedGeometry.activeIdx !== activeIdx &&
          cachedTarget,
      );
      const scrollTarget = useCachedAppleTarget && cachedGeometry && cachedTarget
        ? {
            scrollTop: appleScrollTopFromCachedTarget(
              cachedGeometry,
              cachedTarget,
            ),
            source: "previous-layout" as const,
            rowTop: cachedTarget.top,
            rowHeight: cachedTarget.height,
            topSpacerHeight: cachedGeometry.topSpacerHeight,
            baseScrollTop: cachedGeometry.scrollTop,
          }
        : {
            scrollTop: appleScrollTopForTarget(
              target,
              outer,
              appleTopSpacerRef.current,
              appleTopOffsetPx,
            ),
            source: "live-layout" as const,
            rowTop: target.getBoundingClientRect().y,
            rowHeight: target.getBoundingClientRect().height,
            topSpacerHeight:
              appleTopSpacerRef.current?.getBoundingClientRect().height ?? appleTopOffsetPx,
            baseScrollTop: outer.scrollTop,
          };
      const skipAnimation = !appleScrollReadyRef.current;
      appleScrollReadyRef.current = true;
      performAppleScrollTop(
        outer,
        scrollTarget.scrollTop,
        skipAnimation ? 0 : transitionMs,
        appleScrollAnimationRef,
        {
          source: scrollTarget.source,
          targetIndex: Number.isFinite(targetIndex) ? targetIndex : -1,
          targetRowTop: scrollTarget.rowTop,
          targetRowHeight: scrollTarget.rowHeight,
          anchorPx: appleTopOffsetPx,
          offsetRatio: focusFraction,
          topSpacerHeight: scrollTarget.topSpacerHeight,
          baseScrollTop: scrollTarget.baseScrollTop,
          force,
        },
        (serial) => {
          if (
            !appleScrollAnimationRef.current.active &&
            appleScrollAnimationRef.current.serial === serial
          ) {
            captureAppleGeometryFromDom();
          }
        },
      );
      if (skipAnimation) onAppleScrollPrepared?.();
      else window.requestAnimationFrame(() => onAppleScrollPrepared?.());
      return;
    }
    const containerH = outer.clientHeight;
    const top = target.offsetTop;
    const h = target.offsetHeight;
    const targetTop = anchorMode === "top"
      ? focusOffsetPx
      : containerH * focusFraction + focusOffsetPx - h / 2;
    setTranslateY(targetTop - top);
  }, [
    activeIdx,
    anchorMode,
    appleTopOffsetPx,
    focusFraction,
    focusOffsetPx,
    isAppleScroll,
    onAppleScrollPrepared,
    transitionMs,
  ]);

  useLayoutEffect(() => {
    measureAndPlaceRef.current = measureAndPlace;
  }, [measureAndPlace]);

  useLayoutEffect(() => {
    const previousMeasuredActiveIdx = appleMeasuredActiveIdxRef.current;
    const previousPlaybackJumpSerial = appleMeasuredPlaybackJumpSerialRef.current;
    const nextPlaybackJumpSerial = applePlaybackJumpSerial ?? previousPlaybackJumpSerial;
    const playbackJumpTargetIdx = applePlaybackJumpTargetIdx ?? activeIdx;
    const playbackJumpReached = activeIdx === playbackJumpTargetIdx;
    const hasApplePlaybackJump =
      isAppleScroll &&
      nextPlaybackJumpSerial !== previousPlaybackJumpSerial &&
      playbackJumpReached;
    if (
      isAppleScroll &&
      activeIdx >= 0 &&
      previousMeasuredActiveIdx >= 0 &&
      previousMeasuredActiveIdx !== activeIdx
    ) {
      const outer = outerRef.current;
      if (outer) {
        const serial = appleLineTransitionSerialRef.current + 1;
        appleLineTransitionSerialRef.current = serial;
        outer.dataset.appleLyricsLineTransitionSerial = String(serial);
        outer.dataset.appleLyricsLineTransitionStartedAt = performanceNow().toFixed(3);
        outer.dataset.appleLyricsLineTransitionFrom = String(previousMeasuredActiveIdx);
        outer.dataset.appleLyricsLineTransitionTo = String(activeIdx);
      }
    }
    measureAndPlace();
    if (
      isAppleScroll &&
      activeIdx >= 0 &&
      previousMeasuredActiveIdx >= 0 &&
      previousMeasuredActiveIdx !== activeIdx &&
      hasApplePlaybackJump
    ) {
      measureAndPlace();
    }
    appleMeasuredActiveIdxRef.current = activeIdx;
    if (nextPlaybackJumpSerial === previousPlaybackJumpSerial || playbackJumpReached) {
      appleMeasuredPlaybackJumpSerialRef.current = nextPlaybackJumpSerial;
    }
    if (isAppleScroll) scheduleAppleGeometrySampler();
  }, [
    activeIdx,
    applePlaybackJumpSerial,
    applePlaybackJumpTargetIdx,
    scrollIdx,
    isAppleScroll,
    measureAndPlace,
    scheduleAppleGeometrySampler,
  ]);

  useEffect(() => {
    if (!isAppleScroll) {
      appleSupplementaryScrollKeyRef.current = appleSupplementaryScrollKey;
      return;
    }
    const previousKey = appleSupplementaryScrollKeyRef.current;
    if (appleSupplementaryScrollKey == null || appleSupplementaryScrollKey === "none") {
      appleSupplementaryScrollKeyRef.current = appleSupplementaryScrollKey;
      return;
    }
    if (previousKey === appleSupplementaryScrollKey) return;
    appleSupplementaryScrollKeyRef.current = appleSupplementaryScrollKey;
    if (previousKey == null) return;
    const timer = window.setTimeout(() => {
      measureAndPlaceRef.current?.({ force: true });
    }, APPLE_SUPPLEMENTARY_FORCE_SCROLL_DELAY_MS);
    return () => window.clearTimeout(timer);
  }, [appleSupplementaryScrollKey, isAppleScroll]);

  useLayoutEffect(() => {
    if (!isAppleScroll) return;
    captureAppleGeometryFromDom();
    // React refreshes the row's inline fallback variables when visual-current
    // changes. Re-apply the live spring sample in the same layout phase so a
    // render cannot flash the new row at full scale/color for one frame.
    const springState = appleScrollAnimationRef.current;
    if (springState.active && springState.spatialRows.length > 0) {
      writeAppleSpatialFocus(springState);
    }
  });

  useLayoutEffect(() => {
    const outer = outerRef.current;
    const inner = innerRef.current;
    if (!outer) return;
    let rafId: number | null = null;
    const scheduleMeasure = () => {
      if (rafId != null) window.cancelAnimationFrame(rafId);
      rafId = window.requestAnimationFrame(() => {
        rafId = null;
        measureAndPlace();
      });
    };
    const ro = new ResizeObserver(() => {
      if (isAppleScroll) {
        setAppleViewportHeight((current) => {
          const next = outer.clientHeight;
          return Math.abs(current - next) > 0.5 ? next : current;
        });
      }
      scheduleMeasure();
    });
    ro.observe(outer);
    if (inner && !isAppleScroll) ro.observe(inner);
    return () => {
      if (rafId != null) window.cancelAnimationFrame(rafId);
      ro.disconnect();
    };
  }, [isAppleScroll, measureAndPlace]);

  useLayoutEffect(() => {
    if (!isAppleScroll) return;
    const outer = outerRef.current;
    if (!outer) return;
    setAppleViewportHeight(outer.clientHeight);
    return () => {
      for (const rafId of appleScrollAnimationRef.current.rafIds.values()) {
        window.cancelAnimationFrame(rafId);
      }
      appleScrollAnimationRef.current.rafIds.clear();
      appleScrollAnimationRef.current.active = false;
      appleScrollAnimationRef.current.velocity = 0;
      appleScrollAnimationRef.current.lastFrameAtMs = 0;
      appleScrollAnimationRef.current.spatialRows = [];
      if (appleGeometrySamplerRafRef.current != null) {
        window.cancelAnimationFrame(appleGeometrySamplerRafRef.current);
        appleGeometrySamplerRafRef.current = null;
      }
      appleScrollReadyRef.current = false;
    };
  }, [isAppleScroll]);

  const appleTopSpacerHeight = isAppleScroll
    ? Math.max(0, appleTopOffsetPx)
    : APPLE_LYRIC_TOP_OFFSET_PX;
  const appleBottomSpacerHeight = Math.max(0, appleViewportHeight - 2 * appleTopSpacerHeight);

  return (
    <div
      ref={outerRef}
      data-apple-lyrics-viewport={isAppleScroll ? "true" : undefined}
      style={{
        ...outerStyle,
        overflowY: isAppleScroll ? "auto" : outerStyle.overflowY,
        overflowX: isAppleScroll ? "visible" : outerStyle.overflowX,
        scrollBehavior: isAppleScroll ? "auto" : outerStyle.scrollBehavior,
        overflowAnchor: isAppleScroll ? "none" : outerStyle.overflowAnchor,
        scrollbarWidth: isAppleScroll ? "none" : outerStyle.scrollbarWidth,
      }}
    >
      <div
        ref={innerRef}
        data-apple-lyrics-inner={isAppleScroll ? "true" : undefined}
        style={isAppleScroll
          ? {
              position: "relative",
              width: "100%",
              minHeight: "100%",
              ...innerExtraStyle,
            }
          : {
              position: "absolute",
              left: 0,
              right: 0,
              top: 0,
              transform: `translate3d(0, ${animatedTranslateY}px, 0)`,
              transformOrigin: anchorMode === "top"
                ? `left ${Math.max(0, focusOffsetPx)}px`
                : `left ${Math.round(focusFraction * 100)}%`,
              transition:
                transitionMs >= 700
                  ? "none"
                  : `transform ${transitionMs}ms ${APPLE_LYRIC_LIST_EASE}`,
              willChange: "transform",
              ...innerExtraStyle,
            }}
      >
        {isAppleScroll && (
          <div
            ref={appleTopSpacerRef}
            data-apple-lyrics-top-spacer="true"
            aria-hidden
            style={{ height: appleTopSpacerHeight }}
          />
        )}
        {children}
        {isAppleScroll && (
          <div
            data-apple-lyrics-bottom-spacer="true"
            aria-hidden
            style={{ height: appleBottomSpacerHeight }}
          />
        )}
      </div>
    </div>
  );
}

function appleScrollTopForTarget(
  target: HTMLElement,
  scrollContainer: HTMLElement,
  topSpacer: HTMLElement | null,
  fallbackTopOffsetPx: number,
): number {
  const targetRect = target.getBoundingClientRect();
  const topSpacerHeight = topSpacer?.getBoundingClientRect().height ?? fallbackTopOffsetPx;
  return Math.max(
    0,
    targetRect.y -
      topSpacerHeight -
      APPLE_LYRIC_SCROLL_TOP_MARGIN_PX +
      scrollContainer.scrollTop,
  );
}

type AppleLyricGeometrySnapshot = {
  activeIdx: number;
  scrollTop: number;
  topSpacerHeight: number;
  rows: Record<number, { top: number; height: number }>;
};

type AppleScrollAnimationState = {
  rafIds: Map<number, number>;
  serial: number;
  active: boolean;
  value: number;
  velocity: number;
  target: number;
  lastFrameAtMs: number;
  focusViewportOffsetPx: number;
  focusSpanPx: number;
  spatialRows: Array<{
    element: HTMLElement;
    index: number;
    anchorPx: number;
  }>;
};

function captureAppleLyricGeometry(
  scrollContainer: HTMLElement,
  inner: HTMLElement,
  topSpacer: HTMLElement | null,
  activeIdx: number,
): AppleLyricGeometrySnapshot {
  const rows: AppleLyricGeometrySnapshot["rows"] = {};
  inner.querySelectorAll<HTMLElement>("[data-apple-lyric-row-index]").forEach((row) => {
    const index = Number.parseInt(row.dataset.appleLyricRowIndex ?? "", 10);
    if (!Number.isFinite(index)) return;
    const rect = row.getBoundingClientRect();
    rows[index] = { top: rect.y, height: rect.height };
  });
  const activeRow = inner.querySelector<HTMLElement>('[data-active="1"]');
  const visualActiveIdx = Number.parseInt(
    activeRow?.dataset.appleLyricRowIndex ?? "",
    10,
  );
  return {
    activeIdx: Number.isFinite(visualActiveIdx) ? visualActiveIdx : activeIdx,
    scrollTop: scrollContainer.scrollTop,
    topSpacerHeight: topSpacer?.getBoundingClientRect().height ?? APPLE_LYRIC_TOP_OFFSET_PX,
    rows,
  };
}

function writeAppleLyricGeometryDebug(
  scrollContainer: HTMLElement,
  snapshot: AppleLyricGeometrySnapshot,
): void {
  scrollContainer.dataset.appleLyricsGeometryActiveIdx = String(snapshot.activeIdx);
  scrollContainer.dataset.appleLyricsGeometryScrollTop = snapshot.scrollTop.toFixed(3);
}

function appleScrollTopFromCachedTarget(
  geometry: AppleLyricGeometrySnapshot,
  target: { top: number; height: number },
): number {
  return Math.max(
    0,
    target.top -
      geometry.topSpacerHeight -
      APPLE_LYRIC_SCROLL_TOP_MARGIN_PX +
      geometry.scrollTop,
  );
}

function prepareAppleSpatialFocus(
  element: HTMLElement,
  state: AppleScrollAnimationState,
  targetIndex: number | undefined,
  targetScrollTop: number,
  fallbackFocusViewportOffsetPx: number,
): void {
  const containerRect = element.getBoundingClientRect();
  const rows = Array.from(
    element.querySelectorAll<HTMLElement>("[data-apple-lyric-row-index]"),
  ).map((row) => ({
    element: row,
    index: Number.parseInt(row.dataset.appleLyricRowIndex ?? "", 10),
    anchorPx: row.getBoundingClientRect().top - containerRect.top + element.scrollTop,
  })).filter((row) => Number.isFinite(row.index) && Number.isFinite(row.anchorPx));
  const orderedAnchors = rows
    .map((row) => row.anchorPx)
    .sort((a, b) => a - b);
  const gaps = orderedAnchors
    .slice(1)
    .map((anchor, index) => anchor - orderedAnchors[index]!)
    .filter((gap) => gap > 1)
    .sort((a, b) => a - b);
  const medianGap = gaps.length > 0
    ? gaps[Math.floor(gaps.length / 2)]!
    : Math.max(1, element.clientHeight / 7);
  const targetRow = rows.find((row) => row.index === targetIndex);
  state.spatialRows = rows;
  state.focusSpanPx = Math.max(1, medianGap * APPLE_LYRIC_SPATIAL_FOCUS_SPAN_ROWS);
  state.focusViewportOffsetPx = targetRow
    ? targetRow.anchorPx - targetScrollTop
    : fallbackFocusViewportOffsetPx;
}

function writeAppleSpatialFocus(state: AppleScrollAnimationState): void {
  const focusAnchorPx = state.value + state.focusViewportOffsetPx;
  for (const row of state.spatialRows) {
    const raw = Math.max(
      0,
      Math.min(1, 1 - Math.abs(row.anchorPx - focusAnchorPx) / state.focusSpanPx),
    );
    const focus = raw * raw * (3 - 2 * raw);
    const scale = 1 + (APPLE_LYRIC_CURRENT_SCALE - 1) * focus;
    const lineAlpha = APPLE_INACTIVE_LINE_ALPHA +
      (APPLE_TIMED_GRADIENT_ACTIVE_ALPHA - APPLE_INACTIVE_LINE_ALPHA) * focus;
    const unsungAlpha = APPLE_INACTIVE_LINE_ALPHA +
      (APPLE_TIMED_GRADIENT_UNSUNG_ALPHA - APPLE_INACTIVE_LINE_ALPHA) * focus;
    row.element.style.setProperty("--apple-lyric-focus-progress", focus.toFixed(4));
    row.element.style.setProperty("--apple-lyric-line-scale", scale.toFixed(4));
    row.element.style.setProperty("--apple-lyric-line-alpha", lineAlpha.toFixed(4));
    row.element.style.setProperty("--apple-lyric-sung-alpha", lineAlpha.toFixed(4));
    row.element.style.setProperty("--apple-lyric-unsung-alpha", unsungAlpha.toFixed(4));
    row.element.dataset.appleLyricSpatialFocus = focus.toFixed(4);
  }
}

function performAppleScrollTop(
  element: HTMLElement,
  targetScrollTop: number,
  durationMs: number,
  animationRef: React.MutableRefObject<AppleScrollAnimationState>,
  debug?: {
    source: "previous-layout" | "live-layout";
    targetIndex: number;
    targetRowTop: number;
    targetRowHeight: number;
    anchorPx: number;
    offsetRatio: number;
    topSpacerHeight: number;
    baseScrollTop: number;
    force?: boolean;
  },
  onComplete?: (serial: number) => void,
): void {
  const state = animationRef.current;
  const activeCountAtStart = state.rafIds.size;
  const from = element.scrollTop;
  const maxScrollTop = Math.max(0, element.scrollHeight - element.clientHeight);
  const target = Math.max(0, Math.min(maxScrollTop, targetScrollTop));
  const delta = target - from;
  const serial = state.serial + 1;
  state.serial = serial;
  const writeScrollDebug = (duration: number, startedAt: number, activeCountAtStart: number) => {
    element.dataset.appleLyricsScrollSerial = String(serial);
    element.dataset.appleLyricsScrollStartedAt = startedAt.toFixed(3);
    element.dataset.appleLyricsScrollFrom = from.toFixed(3);
    element.dataset.appleLyricsScrollTarget = target.toFixed(3);
    element.dataset.appleLyricsScrollDuration = duration.toFixed(3);
    element.dataset.appleLyricsScrollMotion = "spring";
    element.dataset.appleLyricsScrollSpringStiffness = String(APPLE_LYRIC_SCROLL_SPRING_STIFFNESS);
    element.dataset.appleLyricsScrollSpringDamping = String(APPLE_LYRIC_SCROLL_SPRING_DAMPING);
    element.dataset.appleLyricsScrollActiveCountAtStart = String(activeCountAtStart);
    element.dataset.appleLyricsScrollActiveCount = String(animationRef.current.rafIds.size);
    if (debug) {
      element.dataset.appleLyricsScrollSource = debug.source;
      element.dataset.appleLyricsScrollTargetIndex = String(debug.targetIndex);
      element.dataset.appleLyricsScrollTargetRowTop = debug.targetRowTop.toFixed(3);
      element.dataset.appleLyricsScrollTargetRowHeight = debug.targetRowHeight.toFixed(3);
      element.dataset.appleLyricsScrollAnchorPx = debug.anchorPx.toFixed(3);
      element.dataset.appleLyricsScrollOffsetRatio = debug.offsetRatio.toFixed(3);
      element.dataset.appleLyricsScrollTopSpacerHeight = debug.topSpacerHeight.toFixed(3);
      element.dataset.appleLyricsScrollBaseScrollTop = debug.baseScrollTop.toFixed(3);
      element.dataset.appleLyricsScrollTopMargin = String(APPLE_LYRIC_SCROLL_TOP_MARGIN_PX);
      element.dataset.appleLyricsScrollForce = debug.force ? "true" : "false";
    }
    let history: Array<Record<string, string | number | boolean | null>> = [];
    try {
      history = JSON.parse(element.dataset.appleLyricsScrollHistory ?? "[]");
      if (!Array.isArray(history)) history = [];
    } catch {
      history = [];
    }
    history.push({
      serial,
      startedAt: Number(startedAt.toFixed(3)),
      from: Number(from.toFixed(3)),
      target: Number(target.toFixed(3)),
      duration: Number(duration.toFixed(3)),
      activeCountAtStart,
      source: debug?.source ?? null,
      force: debug?.force === true,
      targetIndex: debug?.targetIndex ?? null,
      targetRowTop: debug ? Number(debug.targetRowTop.toFixed(3)) : null,
      topSpacerHeight: debug ? Number(debug.topSpacerHeight.toFixed(3)) : null,
      baseScrollTop: debug ? Number(debug.baseScrollTop.toFixed(3)) : null,
      topMargin: debug ? APPLE_LYRIC_SCROLL_TOP_MARGIN_PX : null,
    });
    element.dataset.appleLyricsScrollHistory = JSON.stringify(history.slice(-8));
  };
  if (durationMs <= 0 || Math.abs(delta) < 0.5) {
    writeScrollDebug(0, performanceNow(), activeCountAtStart);
    for (const rafId of state.rafIds.values()) window.cancelAnimationFrame(rafId);
    state.rafIds.clear();
    state.active = false;
    state.value = target;
    state.target = target;
    state.velocity = 0;
    state.lastFrameAtMs = 0;
    element.scrollTop = target;
    prepareAppleSpatialFocus(
      element,
      state,
      debug?.targetIndex,
      target,
      (debug?.topSpacerHeight ?? APPLE_LYRIC_TOP_OFFSET_PX) + APPLE_LYRIC_SCROLL_TOP_MARGIN_PX,
    );
    writeAppleSpatialFocus(state);
    element.dataset.appleLyricsScrollActiveCount = "0";
    element.dataset.appleLyricsScrolling = "false";
    onComplete?.(serial);
    return;
  }
  const startedAt = performanceNow();
  const preserveVelocity = state.active && Number.isFinite(state.velocity);
  for (const rafId of state.rafIds.values()) window.cancelAnimationFrame(rafId);
  state.rafIds.clear();
  state.active = true;
  state.value = from;
  state.target = target;
  state.velocity = preserveVelocity ? state.velocity : 0;
  state.lastFrameAtMs = startedAt;
  prepareAppleSpatialFocus(
    element,
    state,
    debug?.targetIndex,
    target,
    (debug?.topSpacerHeight ?? APPLE_LYRIC_TOP_OFFSET_PX) + APPLE_LYRIC_SCROLL_TOP_MARGIN_PX,
  );
  writeAppleSpatialFocus(state);
  state.rafIds.set(serial, 0);
  element.dataset.appleLyricsScrolling = "true";
  writeScrollDebug(durationMs, startedAt, activeCountAtStart);
  const tick = (now: number) => {
    if (state.serial !== serial) return;
    let remainingSec = Math.max(
      0.001,
      Math.min(
        APPLE_LYRIC_SCROLL_SPRING_MAX_FRAME_SEC,
        (now - state.lastFrameAtMs) / 1000,
      ),
    );
    state.lastFrameAtMs = now;
    while (remainingSec > 0) {
      const stepSec = Math.min(APPLE_LYRIC_SCROLL_SPRING_SUBSTEP_SEC, remainingSec);
      const acceleration =
        APPLE_LYRIC_SCROLL_SPRING_STIFFNESS * (state.target - state.value) -
        APPLE_LYRIC_SCROLL_SPRING_DAMPING * state.velocity;
      state.velocity += acceleration * stepSec;
      state.value += state.velocity * stepSec;
      remainingSec -= stepSec;
    }
    element.scrollTop = state.value;
    const actualScrollTop = element.scrollTop;
    if (Math.abs(actualScrollTop - state.value) > 0.5) {
      state.value = actualScrollTop;
      state.velocity = 0;
    }
    writeAppleSpatialFocus(state);
    const settled =
      Math.abs(state.target - state.value) <= APPLE_LYRIC_SCROLL_SPRING_POSITION_EPS_PX &&
      Math.abs(state.velocity) <= APPLE_LYRIC_SCROLL_SPRING_VELOCITY_EPS_PX_S;
    const timedOut = now - startedAt >= 1200;
    if (!settled && !timedOut) {
      const nextRafId = window.requestAnimationFrame(tick);
      state.rafIds.set(serial, nextRafId);
      state.active = true;
      element.dataset.appleLyricsScrollVelocity = state.velocity.toFixed(3);
      element.dataset.appleLyricsScrollActiveCount = "1";
      element.dataset.appleLyricsScrolling = "true";
      return;
    }
    state.value = state.target;
    state.velocity = 0;
    element.scrollTop = state.target;
    writeAppleSpatialFocus(state);
    state.rafIds.delete(serial);
    state.active = false;
    element.dataset.appleLyricsScrollVelocity = "0.000";
    element.dataset.appleLyricsScrollActiveCount = "0";
    element.dataset.appleLyricsScrolling = "false";
    onComplete?.(serial);
  };
  const rafId = window.requestAnimationFrame(tick);
  state.rafIds.set(serial, rafId);
  element.dataset.appleLyricsScrollActiveCount = "1";
  element.dataset.appleLyricsScrolling = "true";
}

// ============== shared 歌词视图逻辑 ==============

type LyricViewParams = {
  lines: LrcLine[];
  yrcLines: YrcLine[];
  positionSec: number;
  isPlaying: boolean;
  sessionId?: string;
  visibleRows: number;
  rowH: string;
  rowGap: string;
  activeFs: string;
  dimFs: string;
  appleViewportWidth?: number;
  immersive?: boolean;
  // 文字颜色（active）/ 弱化色（inactive）。
  // immersive 模式根据 bg 亮度由调用方算好传进来；compact 模式用默认（白 / 灰）
  fgColor?: string;
  fgDimColor?: string;
  // active 行内 "未唱" 字符颜色（介于 fg 和 fgDim 之间的中亮度）。
  // 不传则 fallback 到 fgDimColor。
  fgUnsungColor?: string;
  appleSupplementaryVisible?: boolean;
  desktopAppleMotion?: boolean;
  onSeekToSec?: (sec: number) => void;
};

type SmoothPositionAnchor = {
  positionSec: number;
  capturedAtMs: number;
  resetToken: number;
  canExtrapolate: boolean;
};

type AppleLyricTimelineAnchor = {
  lineStartSec: number;
  audioAnchorSec: number;
};

type AppleVisualActiveState = {
  sessionKey: string;
  idx: number;
};

type ApplePlaybackJumpState = {
  sessionKey: string;
  positionSec: number;
  serial: number;
  targetIdx: number;
};

type AppleSupplementaryRevealState = {
  key: string | undefined;
  visible: boolean;
};

function lyricViewSessionKey(
  sessionId: string | undefined,
  lines: readonly (LrcLine | YrcLine)[],
): string {
  const first = lines[0];
  const last = lines[lines.length - 1];
  const firstTime = first ? ("time" in first ? first.time : 0) : 0;
  const lastTime = last ? ("time" in last ? last.time : 0) : 0;
  const firstText = first?.text ?? "";
  const lastText = last?.text ?? "";
  return `${sessionId ?? ""}:${lines.length}:${firstTime}:${hashLyricText(firstText)}:${lastTime}:${hashLyricText(lastText)}`;
}

function appleSupplementarySignature(lines: readonly YrcLine[]): string {
  const parts: string[] = [];
  lines.forEach((line, index) => {
    (line.companionLines ?? []).forEach((companion) => {
      if (!isStaticSupplementaryLine(companion)) return;
      parts.push([
        index,
        companion.role ?? "translation",
        companion.time,
        companion.durSec,
        hashLyricText(companion.text),
      ].join(":"));
    });
  });
  return parts.length > 0 ? parts.join("|") : "none";
}

function hashLyricText(text: string): number {
  let hash = 0;
  for (let i = 0; i < text.length; i++) {
    hash = (hash * 31 + text.charCodeAt(i)) | 0;
  }
  return hash;
}

function useSmoothLyricPositionSec(
  rawPositionSec: number,
  isPlaying: boolean,
  sessionKey: string,
): number {
  const [output, setOutput] = useState(rawPositionSec);
  const anchorRef = useRef<SmoothPositionAnchor>({
    positionSec: rawPositionSec,
    capturedAtMs: performanceNow(),
    resetToken: 0,
    canExtrapolate: rawPositionSec > SMOOTH_POSITION_START_GUARD_SEC,
  });

  useEffect(() => {
    anchorRef.current = {
      positionSec: rawPositionSec,
      capturedAtMs: performanceNow(),
      resetToken: anchorRef.current.resetToken + 1,
      canExtrapolate: rawPositionSec > SMOOTH_POSITION_START_GUARD_SEC,
    };
    setOutput(rawPositionSec);
  }, [sessionKey]);

  useEffect(() => {
    const previous = anchorRef.current;
    const jumpedBackward = rawPositionSec + SMOOTH_POSITION_BACKWARD_RESET_SEC < previous.positionSec;
    const jumpedForward = rawPositionSec > previous.positionSec + SMOOTH_POSITION_SEEK_RESET_SEC;
    const shouldReset = jumpedBackward || jumpedForward;
    const advanced = rawPositionSec > previous.positionSec;
    const canExtrapolate = shouldReset
      ? rawPositionSec > SMOOTH_POSITION_START_GUARD_SEC
      : previous.canExtrapolate || advanced || rawPositionSec > SMOOTH_POSITION_START_GUARD_SEC;
    anchorRef.current = {
      positionSec: rawPositionSec,
      capturedAtMs: performanceNow(),
      resetToken: shouldReset ? previous.resetToken + 1 : previous.resetToken,
      canExtrapolate,
    };

    setOutput((prev) => {
      const outputLagSec = rawPositionSec - prev;
      if (isPlaying && !shouldReset && outputLagSec > SMOOTH_POSITION_OUTPUT_STALE_SEC) {
        return rawPositionSec;
      }
      if (!isPlaying || shouldReset) {
        return rawPositionSec;
      }
      return prev;
    });
  }, [rawPositionSec, isPlaying]);

  useEffect(() => {
    if (!isPlaying) {
      setOutput(anchorRef.current.positionSec);
      return;
    }
    let raf = 0;
    let smoothed = anchorRef.current.positionSec;
    let seenResetToken = anchorRef.current.resetToken;
    const tick = () => {
      const anchor = anchorRef.current;
      if (anchor.resetToken !== seenResetToken) {
        seenResetToken = anchor.resetToken;
        smoothed = anchor.positionSec;
      }
      if (!anchor.canExtrapolate) {
        smoothed = anchor.positionSec;
      } else {
        const target = anchor.positionSec + (performanceNow() - anchor.capturedAtMs) / 1000;
        const diff = target - smoothed;
        if (Math.abs(diff) > SMOOTH_POSITION_FRAME_RESET_SEC) smoothed = target;
        else if (diff < -SMOOTH_POSITION_BACKWARD_RESET_SEC) smoothed = target;
        else if (diff > 0) smoothed += diff * SMOOTH_POSITION_FOLLOW_ALPHA;
      }
      setOutput((prev) => (Math.abs(prev - smoothed) < 0.001 ? prev : smoothed));
      raf = window.requestAnimationFrame(tick);
    };
    raf = window.requestAnimationFrame(tick);
    return () => window.cancelAnimationFrame(raf);
  }, [isPlaying, sessionKey]);

  return output;
}

function performanceNow(): number {
  return typeof performance !== "undefined" ? performance.now() : Date.now();
}

type LyricDisplayItem =
  | { kind: "line"; lineIndex: number; startSec: number; endSec: number }
  | { kind: "interlude"; key: string; startSec: number; endSec: number; nextLineIndex: number };

function buildLyricDisplayItems(
  lines: readonly (LrcLine | YrcLine)[],
  useYrc: boolean,
  includeInterludes: boolean,
): LyricDisplayItem[] {
  const items: LyricDisplayItem[] = [];
  let previousLineEndSec: number | null = null;
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]!;
    const startSec = useYrc ? yrcAudioStart(line as YrcLine) : (line as LrcLine).time;
    const nextStartSec =
      i + 1 < lines.length
        ? useYrc
          ? yrcAudioStart(lines[i + 1] as YrcLine)
          : (lines[i + 1] as LrcLine).time
        : null;
    const endSec = useYrc
      ? yrcAudioEnd(line as YrcLine)
      : Math.max(startSec + 0.4, nextStartSec ?? startSec + 5);

    if (includeInterludes) {
      if (i === 0 && startSec > APPLE_INTERLUDE_MIN_GAP_SEC) {
        items.push({
          kind: "interlude",
          key: `lead-${i}`,
          startSec: 0,
          endSec: Math.max(0.001, startSec - 0.001),
          nextLineIndex: i,
        });
      } else if (previousLineEndSec != null) {
        if (startSec - previousLineEndSec > APPLE_INTERLUDE_MIN_GAP_SEC) {
          items.push({
            kind: "interlude",
            key: `${i}-${previousLineEndSec}-${startSec}`,
            startSec: previousLineEndSec + 0.001,
            endSec: startSec - 0.001,
            nextLineIndex: i,
          });
        }
      }
    }

    items.push({ kind: "line", lineIndex: i, startSec, endSec });
    previousLineEndSec = endSec;
  }
  return items;
}

function useLyricView(p: LyricViewParams): {
  empty: boolean;
  rows: React.ReactNode;
  translateExpr: string;
  activeIdx: number;
  /** 应当被滚到焦点位置的行 —— 只保留 100ms 轻预滚动，避免视觉先切句。 */
  scrollIdx: number;
  applePlaybackJumpSerial: number;
  applePlaybackJumpTargetIdx: number;
  commitAppleVisualActive: () => void;
} {
  const useYrc = p.yrcLines.some((line) => line.chars.length > 0 || (line.companionLines?.length ?? 0) > 0);
  const lyricLines = useMemo(
    () => (useYrc ? p.yrcLines : p.lines),
    [useYrc, p.lines, p.yrcLines],
  );
  const sessionKey = useMemo(
    () => lyricViewSessionKey(p.sessionId, lyricLines),
    [p.sessionId, lyricLines],
  );
  const previousActiveIdxRef = useRef(-1);
  const previousSessionKeyRef = useRef(sessionKey);
  const applePlaybackJumpRef = useRef<ApplePlaybackJumpState>({
    sessionKey,
    positionSec: p.positionSec,
    serial: 0,
    targetIdx: -1,
  });
  const appleTimelineAnchorsRef = useRef<Map<number, AppleLyricTimelineAnchor>>(new Map());
  const [appleVisualActiveState, setAppleVisualActiveState] = useState<AppleVisualActiveState>({
    sessionKey,
    idx: -1,
  });
  const pendingAppleVisualActiveRef = useRef<AppleVisualActiveState | null>(null);
  const [appleTimedReleaseIdx, setAppleTimedReleaseIdx] = useState(-1);
  if (previousSessionKeyRef.current !== sessionKey) {
    previousSessionKeyRef.current = sessionKey;
    previousActiveIdxRef.current = -1;
    appleTimelineAnchorsRef.current.clear();
    pendingAppleVisualActiveRef.current = null;
  }
  const smoothedPositionSec = useSmoothLyricPositionSec(p.positionSec, p.isPlaying, sessionKey);
  // Apple 的 currentIndex / updateScroll 直接吃连续的 currentPlaybackMillis + 250ms。
  // Claudio 的全局 positionSec 为减少整页重渲会约 50ms 才更新一次，所以桌面
  // Apple 路径用本组件 rAF 外推后的 clock 做切行、滚动和当前行 syllable timeline。
  const appleRenderPositionSec = p.desktopAppleMotion ? smoothedPositionSec : p.positionSec;
  const appleClockPositionSec = p.desktopAppleMotion ? appleRenderPositionSec : smoothedPositionSec;
  const renderPositionSec = Math.max(
    0,
    appleClockPositionSec + (p.desktopAppleMotion ? 0 : LYRIC_SWEEP_VISUAL_LEAD_SEC),
  );
  const displayItems = useMemo(
    () => buildLyricDisplayItems(lyricLines, useYrc, p.immersive ?? false),
    [lyricLines, useYrc, p.immersive],
  );
  const audioStarts = useMemo(
    () => displayItems.map((item) => item.startSec),
    [displayItems],
  );
  const lineFocusLeadSec = p.desktopAppleMotion
    ? APPLE_LYRIC_CURRENT_LOOKAHEAD_SEC
    : useYrc
      ? LYRIC_WORD_LINE_FOCUS_LEAD_SEC
      : LYRIC_LINE_FOCUS_LEAD_SEC;
  const scrollLookaheadSec = p.desktopAppleMotion
    ? APPLE_LYRIC_CURRENT_LOOKAHEAD_SEC
    : useYrc
      ? LYRIC_WORD_SCROLL_LOOKAHEAD_SEC
      : LYRIC_LINE_FOCUS_LEAD_SEC;
  const lineClockPositionSec = p.desktopAppleMotion
    ? appleClockPositionSec
    : useYrc
      ? renderPositionSec
      : p.positionSec;
  const scrollClockPositionSec = p.desktopAppleMotion
    ? appleClockPositionSec
    : useYrc
      ? renderPositionSec
      : p.positionSec;
  const idx = useMemo(() => {
    const positionSec = lineClockPositionSec + lineFocusLeadSec;
    if (p.desktopAppleMotion) return appleDisplayIndexAt(positionSec, displayItems);
    if (audioStarts.length === 0) return -1;
    if (positionSec < audioStarts[0]!) return -1;
    return lastLineIndexAt(positionSec, audioStarts);
  }, [audioStarts, displayItems, lineClockPositionSec, lineFocusLeadSec, p.desktopAppleMotion]);
  const scrollIdx = useMemo(() => {
    const positionSec = scrollClockPositionSec + scrollLookaheadSec;
    if (p.desktopAppleMotion) return Math.max(0, appleDisplayIndexAt(positionSec, displayItems));
    if (audioStarts.length === 0) return -1;
    return Math.max(0, lastLineIndexAt(positionSec, audioStarts));
  }, [audioStarts, displayItems, p.desktopAppleMotion, scrollClockPositionSec, scrollLookaheadSec]);
  const rawAppleJumpTargetIdx = p.desktopAppleMotion
    ? appleDisplayIndexAt(p.positionSec + APPLE_LYRIC_CURRENT_LOOKAHEAD_SEC, displayItems)
    : -1;
  if (applePlaybackJumpRef.current.sessionKey !== sessionKey) {
    applePlaybackJumpRef.current = {
      sessionKey,
      positionSec: p.positionSec,
      serial: 0,
      targetIdx: rawAppleJumpTargetIdx,
    };
  } else if (p.desktopAppleMotion) {
    const jumpSec = Math.abs(p.positionSec - applePlaybackJumpRef.current.positionSec);
    applePlaybackJumpRef.current = {
      sessionKey,
      positionSec: p.positionSec,
      serial: applePlaybackJumpRef.current.serial + (jumpSec > 1 ? 1 : 0),
      targetIdx: jumpSec > 1 ? rawAppleJumpTargetIdx : applePlaybackJumpRef.current.targetIdx,
    };
  } else {
    applePlaybackJumpRef.current = {
      sessionKey,
      positionSec: p.positionSec,
      serial: applePlaybackJumpRef.current.serial,
      targetIdx: -1,
    };
  }

  const hasActiveLine = idx >= 0;
  const safeIdx = hasActiveLine ? idx : 0;
  const safeScrollIdx = Math.max(0, scrollIdx);
  const total = displayItems.length;
  const logicalActiveIdx = hasActiveLine ? safeIdx : -1;
  const storedVisualActiveIdx =
    appleVisualActiveState.sessionKey === sessionKey ? appleVisualActiveState.idx : -1;
  const rowActiveIdx = p.desktopAppleMotion
    ? storedVisualActiveIdx >= 0
      ? storedVisualActiveIdx
      : logicalActiveIdx
    : logicalActiveIdx;
  const delayedAppleVisualActiveState = useMemo<AppleVisualActiveState | null>(() => {
    if (
      !p.desktopAppleMotion ||
      !p.isPlaying ||
      logicalActiveIdx < 0 ||
      storedVisualActiveIdx < 0 ||
      storedVisualActiveIdx === logicalActiveIdx
    ) {
      return null;
    }
    return { sessionKey, idx: logicalActiveIdx };
  }, [
    logicalActiveIdx,
    p.desktopAppleMotion,
    p.isPlaying,
    sessionKey,
    storedVisualActiveIdx,
  ]);
  const hasRowActiveLine = rowActiveIdx >= 0;
  const safeRowActiveIdx = hasRowActiveLine ? rowActiveIdx : 0;
  const previousActiveIdx = previousActiveIdxRef.current;
  if (p.desktopAppleMotion && p.isPlaying && hasRowActiveLine) {
    const activeItem = displayItems[safeRowActiveIdx];
    const existingAnchor = appleTimelineAnchorsRef.current.get(safeRowActiveIdx);
    if (activeItem && (!existingAnchor || previousActiveIdx !== safeRowActiveIdx)) {
      appleTimelineAnchorsRef.current.set(safeRowActiveIdx, {
        lineStartSec: activeItem.startSec,
        audioAnchorSec: appleClockPositionSec,
      });
      for (const rowIndex of Array.from(appleTimelineAnchorsRef.current.keys())) {
        if (rowIndex < safeRowActiveIdx - 1 || rowIndex > safeRowActiveIdx) {
          appleTimelineAnchorsRef.current.delete(rowIndex);
        }
      }
    }
  }

  const commitAppleVisualActive = useCallback(() => {
    const pending = pendingAppleVisualActiveRef.current ?? delayedAppleVisualActiveState;
    if (!pending) return;
    pendingAppleVisualActiveRef.current = null;
    setAppleVisualActiveState((current) =>
      current.sessionKey === pending.sessionKey && current.idx === pending.idx
        ? current
        : pending,
    );
  }, [delayedAppleVisualActiveState]);

  useLayoutEffect(() => {
    const desired = logicalActiveIdx;
    if (!p.desktopAppleMotion) {
      pendingAppleVisualActiveRef.current = null;
      setAppleVisualActiveState((current) =>
        current.sessionKey === sessionKey && current.idx === desired
          ? current
          : { sessionKey, idx: desired },
      );
      return;
    }
    const currentVisual =
      appleVisualActiveState.sessionKey === sessionKey ? appleVisualActiveState.idx : -1;
    if (currentVisual === desired) {
      pendingAppleVisualActiveRef.current = null;
      return;
    }
    if (desired < 0 || currentVisual < 0 || !p.isPlaying) {
      pendingAppleVisualActiveRef.current = null;
      setAppleVisualActiveState({ sessionKey, idx: desired });
      return;
    }
    // Apple 的 updateScroll() 在 row 重新 render 前运行；这里等当前
    // MeasuredLyricColumn 按旧几何启动 scroll tween 后，再 commit 新 is-current。
    pendingAppleVisualActiveRef.current = { sessionKey, idx: desired };
    const fallback = window.setTimeout(
      commitAppleVisualActive,
      APPLE_LYRIC_VISUAL_CURRENT_FALLBACK_MS,
    );
    return () => {
      window.clearTimeout(fallback);
      const pending = pendingAppleVisualActiveRef.current;
      if (pending?.sessionKey === sessionKey && pending.idx === desired) {
        pendingAppleVisualActiveRef.current = null;
      }
    };
  }, [
    appleVisualActiveState.idx,
    appleVisualActiveState.sessionKey,
    commitAppleVisualActive,
    logicalActiveIdx,
    p.desktopAppleMotion,
    p.isPlaying,
    sessionKey,
  ]);

  useLayoutEffect(() => {
    const previous = previousActiveIdxRef.current;
    previousActiveIdxRef.current = rowActiveIdx;
    if (!p.desktopAppleMotion || !hasRowActiveLine || previous < 0 || previous !== rowActiveIdx - 1) {
      setAppleTimedReleaseIdx(-1);
      return;
    }
    setAppleTimedReleaseIdx(previous);
    const timer = window.setTimeout(() => {
      setAppleTimedReleaseIdx((current) => (current === previous ? -1 : current));
    }, APPLE_LYRIC_LAYOUT_MS + 80);
    return () => window.clearTimeout(timer);
  }, [hasRowActiveLine, rowActiveIdx, sessionKey, p.desktopAppleMotion]);

  if (audioStarts.length === 0) {
    return {
      empty: true,
      rows: null,
      translateExpr: "0px",
      activeIdx: -1,
      scrollIdx: -1,
      applePlaybackJumpSerial: applePlaybackJumpRef.current.serial,
      applePlaybackJumpTargetIdx: applePlaybackJumpRef.current.targetIdx,
      commitAppleVisualActive,
    };
  }

  // 渲染所有行（不再做 sliding window 切片）。配合 column 整体 translateY，
  // 行的位置变化由 transform 平滑过渡 —— 不会出现"上一句没收起来下一句已经
  // 上来"的 snap 跳位现象。
  //
  // 性能：
  //   - 非当前行的 props 全部稳定（isActive=false, line 引用不变, 其余常量），
  //     React.memo 直接 bail-out。一首歌 80 行也只会真正渲染 1 行（当前行）。
  //   - 当前行接收 positionSec 每帧重渲，其它行 positionSec=undefined 稳定。
  //   - idx 变化时，仅"上一个 active"和"新 active"两行的 isActive 翻转，
  //     总共 2 个真实 reconcile。
  const fg = p.fgColor ?? "rgba(255, 255, 255, 0.96)";
  const fgDim = p.fgDimColor ?? "rgba(168, 174, 194, 0.9)";
  // active 行内 "未唱字符" 颜色：默认 fallback 到 fgDim（兼容旧 compact 行为），
  // immersive 调用方会传入更亮的值（约 0.55-0.62 alpha）以避免 active 行整段灰掉。
  const fgUnsung = p.fgUnsungColor ?? fgDim;
  const appleIsDuet = Boolean(
    p.desktopAppleMotion &&
    useYrc &&
    p.yrcLines.some((line) => line.alignment === "end"),
  );
  const rowEls: React.ReactNode[] = [];
  for (let i = 0; i < total; i++) {
    const item = displayItems[i]!;
    const isActive = hasRowActiveLine && i === safeRowActiveIdx;
    const isScrollTarget = p.desktopAppleMotion ? hasActiveLine && i === safeIdx : i === safeScrollIdx;
    // Apple passes willAnimate to the current row and the next row:
    // `willAnimate: s===e || s===e-1` where `s` is currentIndex and `e` is row index.
    // The previous row still shrinks because its current class is removed; it is not
    // part of Apple's `is-animating` set.
    const willAnimate = Boolean(
      p.desktopAppleMotion &&
      hasRowActiveLine &&
      (i === safeRowActiveIdx || i === safeRowActiveIdx + 1),
    );
    const wasActiveBeforeSwitch = Boolean(
      p.desktopAppleMotion &&
      hasRowActiveLine &&
      i === safeRowActiveIdx - 1 &&
      (previousActiveIdx === i || appleTimedReleaseIdx === i),
    );
    const appleFadeOut = Boolean(p.desktopAppleMotion && hasRowActiveLine && i < safeRowActiveIdx);
    const appleInactiveFilter = Boolean(
      p.desktopAppleMotion &&
      hasRowActiveLine &&
      !isActive &&
      (i < safeRowActiveIdx || i > 0),
    );
    const appleTimelinePositionSec = p.desktopAppleMotion && p.isPlaying
      ? appleTimelinePositionForRow(
          appleTimelineAnchorsRef.current.get(i),
          smoothedPositionSec,
        )
      : undefined;
    const distance = hasRowActiveLine ? Math.abs(i - safeRowActiveIdx) : i + 1;
    const seekSec = item.startSec;
    if (item.kind === "interlude") {
      rowEls.push(
        <InterludeRow
          key={`i-${item.key}`}
          startSec={item.startSec}
          endSec={item.endSec}
          positionSec={renderPositionSec}
          rowIndex={i}
          isActive={isActive}
          isScrollTarget={isScrollTarget}
          willAnimate={willAnimate}
          wasActiveBeforeSwitch={wasActiveBeforeSwitch}
          appleFadeOut={appleFadeOut}
          appleInactiveFilter={appleInactiveFilter}
          distance={distance}
          rowH={p.rowH}
          rowGap={p.rowGap}
          activeFs={p.activeFs}
          dimFs={p.dimFs}
          appleViewportWidth={p.appleViewportWidth}
          immersive={p.immersive ?? false}
          desktopAppleMotion={p.desktopAppleMotion ?? false}
          appleIsDuet={appleIsDuet}
          fgColor={fg}
          fgDimColor={fgDim}
          fgUnsungColor={fgUnsung}
        />,
      );
    } else if (useYrc) {
      const line = p.yrcLines[item.lineIndex]!;
      rowEls.push(
        <YrcRow
          key={`y-${item.lineIndex}`}
          line={line}
          rowIndex={i}
          isActive={isActive}
          isScrollTarget={isScrollTarget}
          willAnimate={willAnimate}
          wasActiveBeforeSwitch={wasActiveBeforeSwitch}
          appleFadeOut={appleFadeOut}
          appleInactiveFilter={appleInactiveFilter}
          distance={distance}
          positionSec={isActive || willAnimate ? renderPositionSec : undefined}
          appleTimelinePositionSec={appleTimelinePositionSec}
          rowH={p.rowH}
          rowGap={p.rowGap}
          activeFs={p.activeFs}
          dimFs={p.dimFs}
          appleViewportWidth={p.appleViewportWidth}
          immersive={p.immersive ?? false}
          desktopAppleMotion={p.desktopAppleMotion ?? false}
          appleIsDuet={appleIsDuet}
          fgColor={fg}
          fgDimColor={fgDim}
          fgUnsungColor={fgUnsung}
          appleSupplementaryVisible={p.appleSupplementaryVisible ?? true}
          seekSec={seekSec}
          onSeekToSec={p.onSeekToSec}
        />,
      );
    } else {
      const ln = p.lines[item.lineIndex]!;
      let lineProgress = 0;
      if (isActive) {
        const start = ln.time;
        const end = item.endSec;
        const dur = Math.max(0.4, end - start);
        lineProgress = Math.max(0, Math.min(1, (p.positionSec - start) / dur));
      } else if (i < safeIdx) {
        lineProgress = 1;
      }
      rowEls.push(
        <LrcRow
          key={`l-${item.lineIndex}`}
          text={ln.text}
          rowIndex={i}
          isActive={isActive}
          isScrollTarget={isScrollTarget}
          willAnimate={willAnimate}
          wasActiveBeforeSwitch={wasActiveBeforeSwitch}
          appleFadeOut={appleFadeOut}
          appleInactiveFilter={appleInactiveFilter}
          distance={distance}
          progress={lineProgress}
          rowH={p.rowH}
          rowGap={p.rowGap}
          activeFs={p.activeFs}
          dimFs={p.dimFs}
          appleViewportWidth={p.appleViewportWidth}
          immersive={p.immersive ?? false}
          desktopAppleMotion={p.desktopAppleMotion ?? false}
          appleIsDuet={appleIsDuet}
          fgColor={fg}
          fgDimColor={fgDim}
          fgUnsungColor={fgUnsung}
          seekSec={seekSec}
          onSeekToSec={p.onSeekToSec}
        />,
      );
    }
  }

  // column 偏移：让 safeScrollIdx 行落在 centerRow 槽位（注意：用 scroll target，不是 active）。
  // 只保留 100ms 轻预滚动，避免视觉早于声音明显切到下一句。
  const centerRow = Math.floor(p.visibleRows / 2);
  const offset = centerRow - safeScrollIdx;
  const translateExpr = `calc(${offset} * ${p.rowH})`;

  return {
    empty: false,
    rows: rowEls,
    translateExpr,
    activeIdx: hasActiveLine ? safeIdx : -1,
    scrollIdx: safeScrollIdx,
    applePlaybackJumpSerial: applePlaybackJumpRef.current.serial,
    applePlaybackJumpTargetIdx: applePlaybackJumpRef.current.targetIdx,
    commitAppleVisualActive,
  };
}

function appleTimelinePositionForRow(
  anchor: AppleLyricTimelineAnchor | undefined,
  smoothedPositionSec: number,
): number | undefined {
  if (!anchor) return undefined;
  return anchor.lineStartSec + Math.max(0, smoothedPositionSec - anchor.audioAnchorSec);
}

function lastLineIndexAt(positionSec: number, audioStarts: readonly number[]): number {
  if (audioStarts.length === 0) return -1;
  let lo = 0;
  let hi = audioStarts.length - 1;
  let ans = -1;
  while (lo <= hi) {
    const mid = (lo + hi) >> 1;
    if (audioStarts[mid]! <= positionSec) {
      ans = mid;
      lo = mid + 1;
    } else {
      hi = mid - 1;
    }
  }
  return ans;
}

function appleDisplayIndexAt(positionSec: number, items: readonly LyricDisplayItem[]): number {
  if (items.length === 0) return -1;
  let activeIndex = -1;
  for (let i = 0; i < items.length; i++) {
    const item = items[i]!;
    if (item.startSec <= positionSec && item.endSec >= positionSec) {
      activeIndex = i;
    }
  }
  if (activeIndex >= 0) return activeIndex;
  const nextIndex = items.findIndex((item) => positionSec < item.startSec);
  if (nextIndex >= 0) return nextIndex - 1;
  return items.length - 1;
}

function yrcAudioStart(line: YrcLine): number {
  return line.chars[0]?.startSec ?? line.time;
}

function yrcAudioEnd(line: YrcLine): number {
  const ownEnd = line.chars.length === 0
    ? line.time + Math.max(0.4, line.durSec)
    : Math.max(
        line.time + Math.max(0, line.durSec),
        ...line.chars.map((char) => char.startSec + Math.max(0.001, char.durSec)),
      );
  const companionEnds = (line.companionLines ?? []).map(yrcAudioEnd);
  return companionEnds.length > 0 ? Math.max(ownEnd, ...companionEnds) : ownEnd;
}

// ============== 行组件（React.memo 包，非当前行不每帧重渲） ==============

const SUNG_WHITE = "rgba(255,255,255,0.96)";
const UNSUNG_GRAY = "rgba(168,174,194,0.9)";
const UNSUNG_GRAY_IMMERSIVE = "rgba(255,255,255,0.32)";

type RowCommon = {
  rowIndex: number;
  isActive: boolean;
  /** 是否是"该被滚到焦点位置"的行 —— 通常等于 isActive，但在 lookahead 窗口内
   *  会比 active 早一行打 true，让 column 提前滚动 */
  isScrollTarget: boolean;
  willAnimate: boolean;
  wasActiveBeforeSwitch: boolean;
  appleFadeOut: boolean;
  appleInactiveFilter: boolean;
  distance: number;
  rowH: string;
  rowGap: string;
  activeFs: string;
  dimFs: string;
  appleViewportWidth?: number;
  immersive: boolean;
  desktopAppleMotion: boolean;
  appleIsDuet: boolean;
  fgColor: string;
  fgDimColor: string;
  fgUnsungColor: string;
  appleSupplementaryVisible?: boolean;
  seekSec?: number;
  onSeekToSec?: (sec: number) => void;
};

type LyricRowShellProps = {
  desktopAppleMotion: boolean;
  rowProps: React.HTMLAttributes<HTMLElement> & Record<string, unknown>;
  lineStyle: React.CSSProperties;
  onClick?: React.MouseEventHandler<HTMLElement>;
  children: React.ReactNode;
};

function LyricRowShell({
  desktopAppleMotion,
  rowProps,
  lineStyle,
  onClick,
  children,
}: LyricRowShellProps) {
  if (desktopAppleMotion) {
    return (
      <ruby {...rowProps}>
        <button
          type="button"
          className="line"
          onClick={onClick as React.MouseEventHandler<HTMLButtonElement> | undefined}
          style={lineStyle}
        >
          {children}
        </button>
      </ruby>
    );
  }

  return (
    <div {...(rowProps as React.HTMLAttributes<HTMLDivElement>)} onClick={onClick}>
      <div style={lineStyle}>{children}</div>
    </div>
  );
}

function appleSyncedLineClassName({
  desktopAppleMotion,
  isActive,
  willAnimate,
  collapsible = false,
  isFirst = false,
  alignment = "start",
  appleIsDuet,
}: {
  desktopAppleMotion: boolean;
  isActive: boolean;
  willAnimate: boolean;
  collapsible?: boolean;
  isFirst?: boolean;
  alignment?: "start" | "end";
  appleIsDuet: boolean;
}): string | undefined {
  if (!desktopAppleMotion) return undefined;
  const classes = ["display-synced-line"];
  if (willAnimate) classes.push("is-animating");
  if (isActive) classes.push("is-current");
  if (collapsible) classes.push("collapsible");
  if (isFirst) classes.push("is-first");
  if (alignment === "end") classes.push("is-secondary-vocalist");
  if (appleIsDuet) classes.push("is-duet");
  return classes.join(" ");
}

const InterludeRow = React.memo(function InterludeRow({
  startSec,
  endSec,
  positionSec,
  rowIndex,
  isActive,
  isScrollTarget,
  willAnimate,
  wasActiveBeforeSwitch,
  appleFadeOut,
  appleInactiveFilter,
  distance,
  rowH,
  rowGap,
  activeFs,
  dimFs,
  appleViewportWidth,
  immersive,
  desktopAppleMotion,
  appleIsDuet,
  fgColor,
  fgDimColor,
}: RowCommon & { startSec: number; endSec: number; positionSec: number }) {
  void wasActiveBeforeSwitch;
  const durationSec = Math.max(0.001, endSec - startSec);
  const currentSec = Math.max(0, Math.min(durationSec, positionSec - startSec));
  const isCurrent = isActive && positionSec >= startSec && positionSec < endSec;
  const dotStepSec = Math.max(0.001, durationSec / APPLE_INTERLUDE_DOT_COUNT);
  const scale = isCurrent ? appleInterludeScale(currentSec, durationSec) : 1;
  const dotSizeEm = APPLE_INTERLUDE_DOT_SIZE_EM;
  const dotGapEm = dotSizeEm * APPLE_INTERLUDE_DOT_GAP_RATIO;
  const frameStyle = lineFrame(
    isActive,
    rowH,
    rowGap,
    activeFs,
    dimFs,
    immersive,
    distance,
    desktopAppleMotion,
    willAnimate,
    "",
    "start",
    appleInactiveFilter,
    appleFadeOut,
  );
  const collapsibleStyle = desktopAppleMotion
    ? appleCollapsibleLineFrame(isActive, rowIndex === 0)
    : null;
  const rowProps = {
    "data-lyric-row-kind": "interlude",
    "data-apple-lyric-collapsible": desktopAppleMotion ? "true" : undefined,
    "data-apple-lyric-row-index": rowIndex,
    "data-apple-lyric-will-animate": willAnimate ? "1" : "0",
    "data-active": isActive ? "1" : "0",
    "data-scroll-target": isScrollTarget ? "1" : "0",
    className: appleSyncedLineClassName({
      desktopAppleMotion,
      isActive,
      willAnimate,
      collapsible: true,
      isFirst: rowIndex === 0,
      appleIsDuet,
    }),
    style: {
      ...frameStyle,
      ...(collapsibleStyle ?? {}),
    },
  };
  const lineStyle = lineInner(
    immersive,
    "start",
    desktopAppleMotion,
    activeFs,
    isActive,
    rowGap,
    "",
    willAnimate,
    desktopAppleMotion && !isActive,
    appleIsDuet,
  );

  return (
    <LyricRowShell
      desktopAppleMotion={desktopAppleMotion}
      rowProps={rowProps}
      lineStyle={lineStyle}
    >
      <span
        style={{
          display: "inline-grid",
          gridTemplateColumns: `repeat(${APPLE_INTERLUDE_DOT_COUNT}, ${dotSizeEm}em)`,
          columnGap: `${dotGapEm}em`,
          alignItems: "center",
          transform: `scale(${scale.toFixed(3)})`,
          transformOrigin: "left center",
          transition: isCurrent ? "none" : `transform ${APPLE_LYRIC_FILTER_MS}ms ease`,
        }}
      >
        {Array.from({ length: APPLE_INTERLUDE_DOT_COUNT }).map((_, dotIndex) => {
          const dotAlpha = isCurrent
            ? appleInterludeDotAlpha(currentSec, dotStepSec, dotIndex)
            : APPLE_INTERLUDE_DOT_INACTIVE_ALPHA;
          return (
            <span
              key={dotIndex}
              style={{
                display: "inline-block",
                width: `${dotSizeEm}em`,
                height: `${dotSizeEm}em`,
                borderRadius: `${dotSizeEm}em`,
                backgroundColor: isCurrent ? fgColor : fgDimColor,
                opacity: dotAlpha,
                transition: `opacity ${Math.round(dotStepSec * 1000)}ms ease`,
              }}
            />
          );
        })}
      </span>
    </LyricRowShell>
  );
});

const YrcRow = React.memo(function YrcRow({
  line,
  rowIndex,
  isActive,
  isScrollTarget,
  willAnimate,
  wasActiveBeforeSwitch,
  appleFadeOut,
  appleInactiveFilter,
  distance,
  positionSec,
  appleTimelinePositionSec,
  rowH,
  rowGap,
  activeFs,
  dimFs,
  appleViewportWidth,
  immersive,
  desktopAppleMotion,
  appleIsDuet,
  fgColor,
  fgDimColor,
  fgUnsungColor,
  appleSupplementaryVisible = true,
  seekSec,
  onSeekToSec,
}: RowCommon & { line: YrcLine; positionSec?: number; appleTimelinePositionSec?: number }) {
  // desktop Apple 风用固定暗行颜色 + blur；其它 immersive 仍用 fgColor + opacity 压暗。
  // compact 继续用 fgDimColor，保留旧的紧凑歌词带视觉。
  const inactiveColor = desktopAppleMotion ? fgDimColor : immersive ? fgColor : fgDimColor;
  const textTransition = desktopAppleMotion
    ? APPLE_LYRIC_COLOR_TRANSITION
    : undefined;
  const keepTimedRelease = desktopAppleMotion && wasActiveBeforeSwitch && line.chars.length > 0;
  const supplementarySafetyGapPx = desktopAppleMotion && immersive
    ? appleSupplementarySafetyGapPx(line, isActive, activeFs)
    : 0;
  const webkitPrimaryLineCount = desktopAppleMotion
    ? appleEstimatedPrimaryLineCount(line.text, activeFs, appleViewportWidth, appleIsDuet)
    : 1;
  const webkitActivePaddingGuardPx = desktopAppleMotion && isActive
    ? APPLE_LYRIC_CURRENT_PADDING_BLOCK_PX * 2
    : 0;
  const webkitGuardMinHeight = appleRowMinHeightCss(
    rowH,
    rowGap,
    supplementarySafetyGapPx,
    webkitPrimaryLineCount,
    webkitActivePaddingGuardPx,
  );
  const appleLineGuardMinHeight = supplementarySafetyGapPx > 0
    ? appleLineMinHeightCss(rowH, supplementarySafetyGapPx, webkitPrimaryLineCount)
    : undefined;
  const appleLinePositionSec = desktopAppleMotion
    ? appleTimelinePositionSec ?? (positionSec != null
        ? positionSec + APPLE_LYRIC_CURRENT_LOOKAHEAD_SEC
        : undefined)
    : positionSec;
  const inactiveTimedPositionSec = keepTimedRelease
    ? appleLinePositionSec ?? yrcAudioEnd(line)
    : appleFadeOut
      ? yrcAudioEnd(line)
      : yrcAudioStart(line) - 0.001;
  const handleSeek = onSeekToSec && seekSec != null ? () => onSeekToSec(seekSec) : undefined;
  const rowProps = {
    "data-lyric-row-kind": "yrc",
    "data-apple-lyric-row-index": rowIndex,
    "data-lyric-text": line.text,
    "data-lyric-alignment": line.alignment ?? "start",
    "data-active": isActive ? "1" : "0",
    "data-scroll-target": isScrollTarget ? "1" : "0",
    "data-apple-lyric-will-animate": willAnimate ? "1" : "0",
    "data-apple-lyric-was-active-before-switch": wasActiveBeforeSwitch ? "1" : "0",
    "data-apple-lyric-timed-release": keepTimedRelease ? "1" : "0",
    "data-apple-lyric-supplementary-safety-gap": desktopAppleMotion
      ? String(supplementarySafetyGapPx)
      : undefined,
    className: appleSyncedLineClassName({
      desktopAppleMotion,
      isActive,
      willAnimate,
      isFirst: rowIndex === 0,
      alignment: line.alignment ?? "start",
      appleIsDuet,
    }),
    style: lineFrame(
      isActive,
      rowH,
      rowGap,
      activeFs,
      dimFs,
      immersive,
      distance,
      desktopAppleMotion,
      willAnimate,
      line.text,
      line.alignment ?? "start",
      appleInactiveFilter,
      appleFadeOut,
    ),
  };
  if (desktopAppleMotion && rowProps.style) {
    rowProps.style = {
      ...rowProps.style,
      "--apple-lyric-row-min-height": appleRowMinHeightCss(rowH, rowGap, supplementarySafetyGapPx),
      "--apple-lyric-row-webkit-min-height": webkitGuardMinHeight,
    } as React.CSSProperties;
  }
  const lineStyle = lineInner(
    immersive,
    line.alignment,
    desktopAppleMotion,
    activeFs,
    isActive,
    rowGap,
    line.text,
    willAnimate,
    false,
    appleIsDuet,
    appleLineGuardMinHeight,
  );
  return (
    <LyricRowShell
      desktopAppleMotion={desktopAppleMotion}
      rowProps={rowProps}
      lineStyle={lineStyle}
      onClick={handleSeek}
    >
      {isActive ? (
        <LyricContentStack desktopAppleMotion={desktopAppleMotion}>
          <YrcActiveLine
            line={line}
            positionSec={appleLinePositionSec ?? 0}
            fgColor={fgColor}
            fgUnsungColor={fgUnsungColor}
            desktopAppleMotion={desktopAppleMotion}
            appleVocalClassName="primary-vocals"
          />
          {immersive &&
            (line.companionLines ?? []).map((companion, idx) => {
              const companionPositionSec = appleLinePositionSec ?? 0;
              const staticSupplementary = isStaticSupplementaryLine(companion);
              const backgroundCompanion = companion.role === "companion";
              const companionActive = staticSupplementary ||
                backgroundCompanion ||
                isCompanionLyricActive(companion, companionPositionSec);
              if (!shouldRenderCompanionInActiveRow(companion, companionPositionSec)) return null;
              const companionRole = companion.role ?? "companion";
              const companionSupplementaryVisible = desktopAppleMotion &&
                isStaticSupplementaryLine(companion)
                ? appleSupplementaryVisible
                : true;
              const companionStyle = companionLyricStyle(
                companionActive,
                fgColor,
                fgUnsungColor,
                desktopAppleMotion,
                companionRole,
                activeFs,
                companionSupplementaryVisible,
              );
              return companionActive && companion.chars.length > 0 ? (
                <YrcActiveLine
                  key={`${companion.time}-${idx}`}
                  line={companion}
                  positionSec={companionPositionSec}
                  fgColor={desktopAppleMotion
                    ? `rgba(255, 255, 255, ${APPLE_BG_VOCAL_GRADIENT_ACTIVE_ALPHA})`
                    : fgColor}
                  fgUnsungColor={desktopAppleMotion
                    ? `rgba(255, 255, 255, ${APPLE_BG_VOCAL_GRADIENT_UNSUNG_ALPHA})`
                    : fgUnsungColor}
                  desktopAppleMotion={desktopAppleMotion}
                  appleVocalClassName="background-vocals"
                  appleVocalStyle={companionStyle}
                  appleVocalDataRole={companionRole}
                  appleSupplementaryKind={appleSupplementaryKind(companionRole)}
                  appleSupplementaryVisible={companionSupplementaryVisible}
                />
              ) : (
                <span
                  key={`${companion.time}-${idx}`}
                  className={desktopAppleMotion
                    ? appleSupplementaryClassName(companionRole, companionSupplementaryVisible)
                    : undefined}
                  data-companion-role={companionRole}
                  data-apple-supplementary-kind={appleSupplementaryKind(companionRole)}
                  data-apple-supplementary-visible={desktopAppleMotion
                    ? String(companionSupplementaryVisible)
                    : undefined}
                  style={companionStyle}
                >
                  {companion.text}
                </span>
              );
            })}
        </LyricContentStack>
      ) : (
        <LyricContentStack desktopAppleMotion={desktopAppleMotion}>
          {desktopAppleMotion && line.chars.length > 0 ? (
            <YrcActiveLine
              line={line}
              positionSec={inactiveTimedPositionSec}
              fgColor={inactiveColor}
              fgUnsungColor={inactiveColor}
              desktopAppleMotion={desktopAppleMotion}
              appleVocalClassName="primary-vocals"
            />
          ) : (
            <span
              style={{
                color: desktopAppleMotion
                  ? appleSpatialLineColor(inactiveColor)
                  : inactiveColor,
                whiteSpace: "break-spaces",
                transition: desktopAppleMotion ? undefined : textTransition,
              }}
            >
              {line.text}
            </span>
          )}
          {immersive &&
            (line.companionLines ?? [])
              .filter(isAlwaysVisibleSupplementaryLine)
              .map((companion, idx) => {
                const companionRole = companion.role ?? "translation";
                return (
                  <span
                    key={`${companion.time}-${idx}`}
                    className={desktopAppleMotion
                      ? appleSupplementaryClassName(companionRole, appleSupplementaryVisible)
                      : undefined}
                    data-companion-role={companionRole}
                    data-apple-supplementary-kind={appleSupplementaryKind(companionRole)}
                    data-apple-supplementary-visible={desktopAppleMotion
                      ? String(appleSupplementaryVisible)
                      : undefined}
                    style={companionLyricStyle(
                      false,
                      inactiveColor,
                      inactiveColor,
                      desktopAppleMotion,
                      companionRole,
                      activeFs,
                      appleSupplementaryVisible,
                    )}
                  >
                    {companion.text}
                  </span>
                );
              })}
        </LyricContentStack>
      )}
    </LyricRowShell>
  );
});

const LrcRow = React.memo(function LrcRow({
  text,
  rowIndex,
  isActive,
  isScrollTarget,
  willAnimate,
  wasActiveBeforeSwitch,
  appleFadeOut,
  appleInactiveFilter,
  distance,
  progress,
  rowH,
  rowGap,
  activeFs,
  dimFs,
  appleViewportWidth,
  immersive,
  desktopAppleMotion,
  appleIsDuet,
  fgColor,
  fgDimColor,
  fgUnsungColor,
  seekSec,
  onSeekToSec,
}: RowCommon & { text: string; progress: number }) {
  void progress;
  void wasActiveBeforeSwitch;
  const inactiveColor = desktopAppleMotion ? fgDimColor : immersive ? fgColor : fgDimColor;
  const textTransition = desktopAppleMotion
    ? APPLE_LYRIC_COLOR_TRANSITION
    : undefined;
  const webkitPrimaryLineCount = desktopAppleMotion
    ? appleEstimatedPrimaryLineCount(text, activeFs, appleViewportWidth, appleIsDuet)
    : 1;
  const webkitActivePaddingGuardPx = desktopAppleMotion && isActive
    ? APPLE_LYRIC_CURRENT_PADDING_BLOCK_PX * 2
    : 0;
  const webkitGuardMinHeight = appleRowMinHeightCss(
    rowH,
    rowGap,
    0,
    webkitPrimaryLineCount,
    webkitActivePaddingGuardPx,
  );
  const handleSeek = onSeekToSec && seekSec != null ? () => onSeekToSec(seekSec) : undefined;
  const rowProps = {
    "data-lyric-row-kind": "lrc",
    "data-apple-lyric-row-index": rowIndex,
    "data-lyric-text": text,
    "data-active": isActive ? "1" : "0",
    "data-scroll-target": isScrollTarget ? "1" : "0",
    "data-apple-lyric-will-animate": willAnimate ? "1" : "0",
    className: appleSyncedLineClassName({
      desktopAppleMotion,
      isActive,
      willAnimate,
      isFirst: rowIndex === 0,
      appleIsDuet,
    }),
    style: lineFrame(
      isActive,
      rowH,
      rowGap,
      activeFs,
      dimFs,
      immersive,
      distance,
      desktopAppleMotion,
      willAnimate,
      text,
      "start",
      appleInactiveFilter,
      appleFadeOut,
    ),
  };
  if (desktopAppleMotion && rowProps.style) {
    rowProps.style = {
      ...rowProps.style,
      "--apple-lyric-row-webkit-min-height": webkitGuardMinHeight,
    } as React.CSSProperties;
  }
  const lineStyle = lineInner(
    immersive,
    "start",
    desktopAppleMotion,
    activeFs,
    isActive,
    rowGap,
    text,
    willAnimate,
    false,
    appleIsDuet,
    undefined,
  );
  return (
    <LyricRowShell
      desktopAppleMotion={desktopAppleMotion}
      rowProps={rowProps}
      lineStyle={lineStyle}
      onClick={handleSeek}
    >
      {isActive ? (
        <span
          style={{
            color: desktopAppleMotion ? appleSpatialLineColor(fgColor) : fgColor,
            transition: desktopAppleMotion ? undefined : textTransition,
          }}
        >
          {text}
        </span>
      ) : (
        <span
          style={{
            color: desktopAppleMotion ? appleSpatialLineColor(inactiveColor) : inactiveColor,
            transition: desktopAppleMotion ? undefined : textTransition,
          }}
        >
          {text}
        </span>
      )}
    </LyricRowShell>
  );
});

const activeLyricStack: React.CSSProperties = {
  display: "flex",
  flexDirection: "column",
  width: "100%",
  gap: 0,
};

function LyricContentStack({
  desktopAppleMotion,
  children,
}: {
  desktopAppleMotion: boolean;
  children: React.ReactNode;
}) {
  if (desktopAppleMotion) return <>{children}</>;
  return <div style={activeLyricStack}>{children}</div>;
}

function appleSupplementarySafetyGapPx(line: YrcLine, isActive: boolean, activeFs: string): number {
  let visibleCompanionGapPx = 0;
  for (const companion of line.companionLines ?? []) {
    if (!isAlwaysVisibleSupplementaryLine(companion) && !(isActive && companion.role === "background-translation")) {
      continue;
    }
    const role = companion.role ?? "translation";
    visibleCompanionGapPx +=
      APPLE_LYRIC_SUPPLEMENTARY_REVEAL_BOX_PX +
      appleSupplementaryMarginTopPx(role, activeFs);
  }
  const hasTokenSupplementary = line.chars.some((char) => Boolean(char.supplementaryText));
  const tokenSupplementaryGap = hasTokenSupplementary && visibleCompanionGapPx === 0
    ? APPLE_LYRIC_TOKEN_SUPPLEMENTARY_BOX_PX + appleTokenSupplementaryMarginTopPx()
    : 0;
  return Math.ceil(visibleCompanionGapPx + tokenSupplementaryGap);
}

function appleRowMinHeightCss(
  rowH: string,
  rowGap: string,
  supplementarySafetyGapPx: number,
  primaryLineCount = 1,
  extraPx = 0,
): string {
  const safePrimaryLineCount = Math.max(1, Math.ceil(primaryLineCount));
  return `calc(${rowH} * ${safePrimaryLineCount} + ${rowGap}${supplementarySafetyGapPx > 0 ? ` + ${supplementarySafetyGapPx}px` : ""}${extraPx > 0 ? ` + ${extraPx}px` : ""})`;
}

function appleLineMinHeightCss(
  rowH: string,
  supplementarySafetyGapPx: number,
  primaryLineCount = 1,
  extraPx = 0,
): string {
  const safePrimaryLineCount = Math.max(1, Math.ceil(primaryLineCount));
  return `calc(${rowH} * ${safePrimaryLineCount}${supplementarySafetyGapPx > 0 ? ` + ${supplementarySafetyGapPx}px` : ""}${extraPx > 0 ? ` + ${extraPx}px` : ""})`;
}

function appleEstimatedPrimaryLineCount(
  text: string,
  activeFs: string,
  viewportWidth: number | undefined,
  appleIsDuet: boolean,
): number {
  const fontPx = Number.parseFloat(activeFs);
  const columnWidth = appleLyricsColumnWidthForViewport(viewportWidth);
  const contentWidth = Math.max(
    120,
    columnWidth * (appleIsDuet ? 0.6 : 1),
  );
  const size = Number.isFinite(fontPx) && fontPx > 0 ? fontPx : 28;
  const hardLines = text.split(/\n/u);
  let lineCount = 0;
  for (const hardLine of hardLines) {
    const estimatedWidth = appleEstimatedTextWidthPx(hardLine, size);
    lineCount += Math.max(1, Math.ceil(estimatedWidth / contentWidth));
  }
  return Math.max(1, Math.min(8, lineCount));
}

function appleLyricsColumnWidthForViewport(viewportWidth: number | undefined): number {
  const width = Number.isFinite(viewportWidth) && viewportWidth ? viewportWidth : 1180;
  let trackWidth: number;
  if (width >= 2561) {
    trackWidth = width * 0.2812 + 252.928;
  } else if (width >= 2000) {
    trackWidth = width * 0.38;
  } else if (width >= 1680) {
    trackWidth = width * 0.38;
  } else {
    trackWidth = width * 0.4;
  }
  return Math.min(trackWidth, 972.8);
}

function appleEstimatedTextWidthPx(text: string, fontPx: number): number {
  let width = 0;
  for (const char of Array.from(text)) {
    if (/\s/u.test(char)) {
      width += fontPx * 0.32;
    } else if (/[\u3400-\u9fff\uf900-\ufaff]/u.test(char)) {
      width += fontPx;
    } else if (/[A-Z]/u.test(char)) {
      width += fontPx * 0.64;
    } else if (/[a-z0-9]/u.test(char)) {
      width += fontPx * 0.54;
    } else {
      width += fontPx * 0.42;
    }
  }
  return width;
}

function companionLyricStyle(
  active: boolean,
  fgColor: string,
  fgUnsungColor: string,
  desktopAppleMotion: boolean,
  role: string,
  activeFs: string,
  supplementaryVisible = true,
): React.CSSProperties {
  const isBackgroundTranslation = role === "background-translation";
  const desktopFontSize = isBackgroundTranslation
    ? "12px"
    : role === "translation"
      ? appleSupplementaryFontSizeCss(activeFs, "secondary")
      : role === "romaji"
        ? appleSupplementaryFontSizeCss(activeFs, "static")
        : `${APPLE_LYRIC_BG_FONT_PX}px`;
  const appleSecondaryOpacity = role === "translation" || isBackgroundTranslation ? 0.45 : 1;
  const isAppleStaticSupplementary = role === "translation" || role === "romaji" || isBackgroundTranslation;
  const appleSupplementaryVisible = !desktopAppleMotion ||
    !isAppleStaticSupplementary ||
    supplementaryVisible;
  const supplementaryMaxHeight = desktopAppleMotion && isAppleStaticSupplementary
    ? `${trimCssNumber(appleSupplementaryVisibleBoxPx(role, activeFs))}px`
    : undefined;
  return {
    display: "block",
    color: active ? fgColor : fgUnsungColor,
    maxHeight: desktopAppleMotion
      ? isAppleStaticSupplementary
        ? appleSupplementaryVisible
          ? supplementaryMaxHeight
          : "0px"
        : `${APPLE_LYRIC_SUPPLEMENTARY_REVEAL_BOX_PX}px`
      : undefined,
    overflow: desktopAppleMotion ? "hidden" : undefined,
    opacity: desktopAppleMotion
      ? isAppleStaticSupplementary
        ? appleSupplementaryVisible
          ? appleSecondaryOpacity
          : 0
        : appleSecondaryOpacity
      : 1,
    fontSize: desktopAppleMotion ? desktopFontSize : role === "companion" ? "0.76em" : "0.72em",
    lineHeight: desktopAppleMotion ? "1.2em" : 1.28,
    fontWeight: desktopAppleMotion ? 700 : 650,
    marginTop: desktopAppleMotion
      ? role === "companion" || isBackgroundTranslation
        ? role === "companion" ? 20 : 0
        : appleSupplementaryVisible ? "0.2em" : 0
      : 0,
    transform: desktopAppleMotion && isAppleStaticSupplementary
      ? appleSupplementaryVisible
        ? "translate3d(0, 0, 0)"
        : "translate3d(0, -10px, 0)"
      : undefined,
    whiteSpace: desktopAppleMotion ? (role === "romaji" ? "pre-wrap" : "normal") : "break-spaces",
    textWrap: desktopAppleMotion ? "balance" : undefined,
    overflowWrap: desktopAppleMotion ? "break-word" : undefined,
    textAlign: "inherit",
    transition: desktopAppleMotion
      ? isAppleStaticSupplementary
        ? "max-height 0.6s ease, opacity 0.6s ease, transform 0.6s ease, margin-top 0.4s linear, color 0.1s"
        : `color 0.1s, opacity ${APPLE_LYRIC_FILTER_MS}ms linear, max-height ${APPLE_LYRIC_LAYOUT_MS}ms linear, margin-top ${APPLE_LYRIC_LAYOUT_MS}ms linear`
      : "color 170ms cubic-bezier(0.25, 0.1, 0.25, 1), opacity 170ms cubic-bezier(0.25, 0.1, 0.25, 1)",
  };
}

function appleSupplementaryVisibleBoxPx(role: string, activeFs: string): number {
  const fontPx = appleSupplementaryFontPx(role, activeFs);
  const lineHeightPx = fontPx * 1.2;
  if (!Number.isFinite(lineHeightPx) || lineHeightPx <= 0) {
    return APPLE_LYRIC_SUPPLEMENTARY_REVEAL_BOX_PX;
  }
  const wholeLines = Math.max(
    1,
    Math.floor(APPLE_LYRIC_SUPPLEMENTARY_REVEAL_BOX_PX / lineHeightPx),
  );
  return Math.min(APPLE_LYRIC_SUPPLEMENTARY_REVEAL_BOX_PX, wholeLines * lineHeightPx);
}

function appleSupplementaryFontPx(role: string, activeFs: string): number {
  if (role === "background-translation") return 12;
  const activeFontPx = Number.parseFloat(activeFs);
  if (!Number.isFinite(activeFontPx)) return role === "romaji" ? 15 : 13;
  if (role === "romaji") {
    return activeFontPx * (
      activeFontPx >= 38
        ? APPLE_LYRIC_SUPPLEMENTARY_FONT_RATIO_MEDIUM
        : APPLE_LYRIC_SUPPLEMENTARY_FONT_RATIO_SMALL
    );
  }
  if (role === "translation") {
    return activeFontPx * (
      activeFontPx >= 38
        ? APPLE_LYRIC_SECONDARY_FONT_RATIO_MEDIUM
        : APPLE_LYRIC_SECONDARY_FONT_RATIO_SMALL
    );
  }
  return APPLE_LYRIC_BG_FONT_PX;
}

function appleSupplementaryMarginTopPx(role: string, activeFs: string): number {
  if (role === "background-translation") return 0;
  if (role === "companion") return 20;
  return appleSupplementaryFontPx(role, activeFs) * APPLE_LYRIC_SUPPLEMENTARY_MARGIN_TOP_EM;
}

function appleTokenSupplementaryMarginTopPx(): number {
  return 15 * APPLE_LYRIC_SUPPLEMENTARY_MARGIN_TOP_EM;
}

function appleSupplementaryFontSizeCss(
  activeFs: string,
  kind: "secondary" | "static",
): string {
  const activeFontPx = Number.parseFloat(activeFs);
  if (!Number.isFinite(activeFontPx)) {
    return kind === "secondary"
      ? `calc(${activeFs} * ${APPLE_LYRIC_SECONDARY_FONT_RATIO_SMALL})`
      : `calc(${activeFs} * ${APPLE_LYRIC_SUPPLEMENTARY_FONT_RATIO_SMALL})`;
  }
  const ratio = kind === "secondary"
    ? activeFontPx >= 38
      ? APPLE_LYRIC_SECONDARY_FONT_RATIO_MEDIUM
      : APPLE_LYRIC_SECONDARY_FONT_RATIO_SMALL
    : activeFontPx >= 38
      ? APPLE_LYRIC_SUPPLEMENTARY_FONT_RATIO_MEDIUM
      : APPLE_LYRIC_SUPPLEMENTARY_FONT_RATIO_SMALL;
  return `${trimCssNumber(activeFontPx * ratio)}px`;
}

function trimCssNumber(value: number): string {
  return value.toFixed(3).replace(/\.?0+$/u, "");
}

function appleSupplementaryKind(role: string): string {
  if (role === "romaji") return "static-supplementary";
  if (role === "background-translation") return "secondary secondary--background";
  if (role === "translation") return "secondary";
  if (role === "companion") return "background-vocals";
  return "secondary";
}

function appleSupplementaryClassName(role: string, visible: boolean): string {
  const base = appleSupplementaryKind(role);
  if (!visible || role === "companion") return base;
  return `${base} is-visible`;
}

function appleInterludeScale(currentSec: number, durationSec: number): number {
  const remainingSec = durationSec - currentSec;
  if (remainingSec >= 0 && remainingSec < APPLE_INTERLUDE_ENDING_SEC) {
    const phase =
      ((currentSec - Math.max(0, durationSec - APPLE_INTERLUDE_ENDING_SEC)) %
        APPLE_INTERLUDE_END_HEARTBEAT_SEC) /
      APPLE_INTERLUDE_END_HEARTBEAT_SEC;
    const t = cssEaseIn01(phase);
    return APPLE_INTERLUDE_END_SCALE_START +
      (APPLE_INTERLUDE_END_SCALE_PEAK - APPLE_INTERLUDE_END_SCALE_START) * t;
  }

  const phase = ((currentSec % APPLE_INTERLUDE_HEARTBEAT_SEC) / APPLE_INTERLUDE_HEARTBEAT_SEC);
  if (phase <= 0.5) {
    const t = cssEaseIn01(phase / 0.5);
    return 1 + (APPLE_INTERLUDE_HEARTBEAT_PEAK_SCALE - 1) * t;
  }
  const t = cssEaseIn01((phase - 0.5) / 0.5);
  return APPLE_INTERLUDE_HEARTBEAT_PEAK_SCALE -
    (APPLE_INTERLUDE_HEARTBEAT_PEAK_SCALE - 1) * t;
}

function appleInterludeDotAlpha(currentSec: number, dotStepSec: number, dotIndex: number): number {
  const threshold = dotStepSec * dotIndex;
  const t = cssDefaultEase01((currentSec - threshold) / dotStepSec);
  return APPLE_INTERLUDE_DOT_INACTIVE_ALPHA +
    (1 - APPLE_INTERLUDE_DOT_INACTIVE_ALPHA) * t;
}

function cssDefaultEase01(t: number): number {
  return cubicBezierYForX(0.25, 0.1, 0.25, 1, t);
}

function cssEaseIn01(t: number): number {
  return cubicBezierYForX(0.42, 0, 1, 1, t);
}

function cubicBezierYForX(x1: number, y1: number, x2: number, y2: number, t: number): number {
  const x = Math.max(0, Math.min(1, t));
  let u = x;
  for (let i = 0; i < 5; i++) {
    const currentX = cubicBezierPoint(0, x1, x2, 1, u);
    const dx = cubicBezierDerivative(0, x1, x2, 1, u);
    if (Math.abs(currentX - x) < 0.0005 || Math.abs(dx) < 0.0001) break;
    u = Math.max(0, Math.min(1, u - (currentX - x) / dx));
  }
  return cubicBezierPoint(0, y1, y2, 1, u);
}

function cubicBezierPoint(p0: number, p1: number, p2: number, p3: number, t: number): number {
  const inv = 1 - t;
  return inv * inv * inv * p0 + 3 * inv * inv * t * p1 + 3 * inv * t * t * p2 + t * t * t * p3;
}

function cubicBezierDerivative(p0: number, p1: number, p2: number, p3: number, t: number): number {
  const inv = 1 - t;
  return 3 * inv * inv * (p1 - p0) + 6 * inv * t * (p2 - p1) + 3 * t * t * (p3 - p2);
}

function shouldRenderCompanionLyric(line: YrcLine, positionSec: number): boolean {
  return positionSec >= yrcAudioStart(line) - COMPANION_LYRIC_LEAD_SEC;
}

function isStaticSupplementaryLine(line: YrcLine): boolean {
  return line.role === "translation" || line.role === "romaji" || line.role === "background-translation";
}

function isAlwaysVisibleSupplementaryLine(line: YrcLine): boolean {
  return line.role === "translation" || line.role === "romaji";
}

function shouldRenderCompanionInActiveRow(line: YrcLine, positionSec: number): boolean {
  if (isStaticSupplementaryLine(line)) return true;
  if (line.role === "companion") return true;
  return shouldRenderCompanionLyric(line, positionSec) && !isCompanionLyricPast(line, positionSec);
}

function isCompanionLyricActive(line: YrcLine, positionSec: number): boolean {
  return positionSec >= yrcAudioStart(line) && positionSec < yrcAudioEnd(line);
}

function isCompanionLyricPast(line: YrcLine, positionSec: number): boolean {
  return positionSec >= yrcAudioEnd(line);
}

function YrcActiveLine({
  line,
  positionSec,
  fgColor,
  fgUnsungColor,
  desktopAppleMotion,
  appleVocalClassName = "primary-vocals",
  appleVocalStyle,
  appleVocalDataRole,
  appleSupplementaryKind,
  appleSupplementaryVisible = true,
}: {
  line: YrcLine;
  positionSec: number;
  fgColor: string;
  // 未唱字符颜色 —— 介于 fg 和 fgDim 之间的"中亮度"。Apple Music 思路：
  // 即便整行都还没唱到（刚切到下一句），这一行依然明显比其它非 active 行亮，
  // 因为它的字符颜色是中亮度而非 fgDim。
  fgUnsungColor: string;
  desktopAppleMotion: boolean;
  appleVocalClassName?: "primary-vocals" | "background-vocals";
  appleVocalStyle?: React.CSSProperties;
  appleVocalDataRole?: string;
  appleSupplementaryKind?: string;
  appleSupplementaryVisible?: boolean;
}) {
  const chars = line.chars;
  const usesSpatialPrimaryColor = desktopAppleMotion && appleVocalClassName === "primary-vocals";
  const fixedBackgroundVocalVars = desktopAppleMotion && appleVocalClassName === "background-vocals"
    ? appleFixedVocalMotionVars(fgColor, fgUnsungColor)
    : null;
  if (chars.length === 0) {
    return (
      <span
        className={desktopAppleMotion ? appleVocalClassName : undefined}
        data-companion-role={appleVocalDataRole}
        data-apple-vocals={desktopAppleMotion ? appleVocalClassName : undefined}
        data-apple-supplementary-kind={appleSupplementaryKind}
        data-apple-supplementary-visible={desktopAppleMotion && appleVocalDataRole
          ? String(appleSupplementaryVisible)
          : undefined}
        style={{
          ...activeLyricLine,
          ...(appleVocalStyle ?? null),
          color: usesSpatialPrimaryColor ? appleSpatialLineColor(fgColor) : fgColor,
          transition: usesSpatialPrimaryColor ? undefined : APPLE_LYRIC_COLOR_TRANSITION,
        }}
      >
        {line.text}
      </span>
    );
  }

  const vocalNode = (
    <span
      className={desktopAppleMotion ? appleVocalClassName : undefined}
      data-companion-role={appleVocalDataRole}
      data-apple-vocals={desktopAppleMotion ? appleVocalClassName : undefined}
      data-apple-supplementary-kind={appleSupplementaryKind}
      data-apple-supplementary-visible={desktopAppleMotion && appleVocalDataRole
        ? String(appleSupplementaryVisible)
        : undefined}
      dir={desktopAppleMotion ? "auto" : undefined}
      style={desktopAppleMotion
        ? {
            ...applePrimaryVocalsStyle,
            ...(appleVocalStyle ?? null),
            ...(fixedBackgroundVocalVars ?? null),
          }
        : appleVocalStyle}
    >
      {chars.map((char, idx) => (
        <YrcTokenGroup
          key={yrcTokenKey(char, idx)}
          desktopAppleMotion={desktopAppleMotion}
          hasTrailingWhitespace={/\s$/.test(char.text)}
          alignment={line.alignment ?? "start"}
          supplementaryText={char.supplementaryText}
        >
          {shouldUseSlowEmphasis(char, desktopAppleMotion) ? (
            <YrcSlowEmphasisToken
              char={char}
              tokenIndex={idx}
              positionSec={positionSec}
              fgColor={fgColor}
              fgUnsungColor={fgUnsungColor}
              desktopAppleMotion={desktopAppleMotion}
            />
          ) : (
            <YrcOrdinaryToken
              chars={chars}
              char={char}
              tokenIndex={idx}
              positionSec={positionSec}
              fgColor={fgColor}
              fgUnsungColor={fgUnsungColor}
              desktopAppleMotion={desktopAppleMotion}
            />
          )}
        </YrcTokenGroup>
      ))}
    </span>
  );

  if (desktopAppleMotion) {
    return vocalNode;
  }

  return (
    <span style={activeLyricLine}>
      {vocalNode}
    </span>
  );
}

function YrcOrdinaryToken({
  chars,
  char,
  tokenIndex,
  positionSec,
  fgColor,
  fgUnsungColor,
  desktopAppleMotion,
}: {
  chars: YrcChar[];
  char: YrcChar;
  tokenIndex: number;
  positionSec: number;
  fgColor: string;
  fgUnsungColor: string;
  desktopAppleMotion: boolean;
}) {
  const progress = charProgress(char, positionSec);
  const beingSung = progress > 0 && progress < 1;
  const sung = progress >= 1;
  const liftT = desktopAppleMotion
    ? regularTokenLiftProgress(char, positionSec)
    : regularLyricLiftProgress(chars, tokenIndex, positionSec);
  const liftPx = -(desktopAppleMotion ? APPLE_REGULAR_WORD_LIFT_PX : REGULAR_WORD_LIFT_PX) * liftT;
  const gradientProgress =
    -APPLE_SWEEP_LEAD_PERCENT + APPLE_SWEEP_TRAVEL_PERCENT * Math.max(0, Math.min(1, progress));
  const tokenColor = sung ? fgColor : fgUnsungColor;
  const displayText = desktopAppleMotion ? appleVisibleSyllableText(char.text) : char.text;
  const backgroundImage = desktopAppleMotion
    ? APPLE_LYRIC_SWEEP_BACKGROUND
    : beingSung
      ? `linear-gradient(90deg, ${fgColor} ${gradientProgress.toFixed(3)}%, ${fgUnsungColor} ${(gradientProgress + APPLE_SWEEP_LEAD_PERCENT).toFixed(3)}%)`
      : "none";
  const clipsText = desktopAppleMotion || beingSung;

  return (
    <span
      className={desktopAppleMotion ? "syllable" : undefined}
      data-yrc-token={char.text}
      data-yrc-token-index={tokenIndex}
      data-yrc-token-kind="ordinary"
      data-apple-syllable={desktopAppleMotion ? "true" : undefined}
      style={{
        ...(desktopAppleMotion ? appleSyllableStyle : null),
        ...(desktopAppleMotion ? appleSweepCssVars(gradientProgress, fgColor, fgUnsungColor) : null),
        position: "relative",
        display: "inline-block",
        whiteSpace: desktopAppleMotion ? "pre" : "break-spaces",
        ...(desktopAppleMotion ? appleCurrentSyllableEdge : null),
        color: tokenColor,
        transition: desktopAppleMotion ? APPLE_LYRIC_TOKEN_COLOR_TRANSITION : undefined,
        transform: `translate3d(0, ${liftPx.toFixed(2)}px, 0)`,
        backgroundImage,
        WebkitBackgroundClip: clipsText ? "text" : "border-box",
        backgroundClip: clipsText ? "text" : "border-box",
        WebkitTextFillColor: clipsText ? "transparent" : tokenColor,
      }}
    >
      {displayText}
    </span>
  );
}

function YrcStaticLine({
  line,
  color,
}: {
  line: YrcLine;
  color: string;
}) {
  const chars = line.chars;
  if (chars.length === 0) {
    return (
      <span
        style={{
          ...activeLyricLine,
          color,
          transition: APPLE_LYRIC_COLOR_TRANSITION,
        }}
      >
        {line.text}
      </span>
    );
  }

  return (
    <span
      style={{
        ...activeLyricLine,
        color,
        transition: APPLE_LYRIC_COLOR_TRANSITION,
      }}
    >
      <span style={applePrimaryVocalsStyle}>
        {chars.map((char, idx) => (
          <YrcTokenGroup
            key={yrcTokenKey(char, idx)}
            desktopAppleMotion
            supplementaryText={char.supplementaryText}
          >
            {shouldUseSlowEmphasis(char, true) ? (
              <span
                data-yrc-token={char.text}
                data-yrc-token-index={idx}
                data-yrc-token-kind="slow-static"
                style={{
                  ...appleSyllableStyle,
                  color: "inherit",
                }}
              >
                {Array.from(appleVisibleSyllableText(char.text)).map((glyph, glyphIdx) => (
                  <span
                    key={`${glyphIdx}-${glyph}`}
                    data-yrc-letter-static={glyph}
                    data-yrc-letter-index={glyphIdx}
                    style={{
                      display: "inline-block",
                      whiteSpace: "pre",
                    }}
                  >
                    {glyph}
                  </span>
                ))}
              </span>
            ) : (
              <span
                data-yrc-token={char.text}
                data-yrc-token-index={idx}
                data-yrc-token-kind="ordinary-static"
                style={{
                  ...appleSyllableStyle,
                  color: "inherit",
                }}
              >
                {appleVisibleSyllableText(char.text)}
              </span>
            )}
          </YrcTokenGroup>
        ))}
      </span>
    </span>
  );
}

function YrcTokenGroup({
  desktopAppleMotion,
  hasTrailingWhitespace = false,
  alignment = "start",
  supplementaryText,
  children,
}: {
  desktopAppleMotion: boolean;
  hasTrailingWhitespace?: boolean;
  alignment?: "start" | "end";
  supplementaryText?: string;
  children: React.ReactNode;
}) {
  if (!desktopAppleMotion) return <>{children}</>;
  const hasSupplementary = Boolean(supplementaryText);
  return (
    <span
      className={[
        "group",
        hasSupplementary ? "show-supplementary" : "",
        hasTrailingWhitespace ? "trailing-whitespace" : "",
      ].filter(Boolean).join(" ")}
      data-yrc-group="true"
      data-yrc-group-align={alignment}
      data-yrc-group-trailing-whitespace={hasTrailingWhitespace ? "true" : "false"}
      data-yrc-group-show-supplementary={hasSupplementary ? "true" : "false"}
      style={{
        ...appleGroupStyle,
        marginBottom: hasSupplementary ? "0.4em" : undefined,
      }}
    >
      <div className="main" data-yrc-main="true" style={appleMainStyle}>
        {children}
      </div>
      {hasSupplementary && (
        <rt
          className="supplementary"
          data-yrc-token-supplementary="true"
          style={appleTokenSupplementaryStyle}
        >
          <span className="syllable" style={appleSupplementarySyllableStyle}>
            {supplementaryText}
          </span>
        </rt>
      )}
    </span>
  );
}

function yrcTokenKey(char: YrcChar, idx: number): string {
  return `${idx}-${char.startSec}-${char.text}`;
}

const activeLyricLine: React.CSSProperties = {
  display: "block",
  width: "100%",
  maxWidth: "100%",
  whiteSpace: "break-spaces",
  textAlign: "inherit",
};

const applePrimaryVocalsStyle: React.CSSProperties = {
  display: "block",
  textAlign: "inherit",
};

const appleGroupStyle: React.CSSProperties = {
  display: "inline-block",
  width: "auto",
  textAlign: "start",
  verticalAlign: "top",
  transition: `height ${APPLE_LYRIC_LAYOUT_MS}ms linear, margin ${APPLE_LYRIC_LAYOUT_MS}ms linear`,
};

const appleMainStyle: React.CSSProperties = {
  display: "inline-block",
  textAlign: "start",
  transition: `height ${APPLE_LYRIC_LAYOUT_MS}ms linear, width ${APPLE_LYRIC_LAYOUT_MS}ms linear`,
};

const appleTokenSupplementaryStyle: React.CSSProperties = {
  display: "block",
  width: "auto",
  maxHeight: "24px",
  overflow: "visible",
  marginTop: "0.2em",
  fontSize: "15px",
  lineHeight: "1.2em",
  whiteSpace: "nowrap",
  opacity: 1,
  transition: `width ${APPLE_LYRIC_LAYOUT_MS}ms linear, height ${APPLE_LYRIC_LAYOUT_MS}ms linear, margin-top ${APPLE_LYRIC_LAYOUT_MS}ms linear`,
};

const appleSyllableStyle: React.CSSProperties = {
  marginTop: -5,
  paddingTop: 5,
  display: "inline-block",
  position: "relative",
  transformOrigin: "right",
  lineHeight: "normal",
  whiteSpace: "pre",
};

const appleSupplementarySyllableStyle: React.CSSProperties = {
  ...appleSyllableStyle,
  color: "inherit",
  WebkitTextFillColor: "currentcolor",
};

const APPLE_LYRIC_SWEEP_BACKGROUND =
  "linear-gradient(var(--gradient-direction, to right), rgba(var(--gradient-color), var(--gradient-color), var(--gradient-color), var(--gradient-color-alpha-active)) var(--gradient-progress), rgba(var(--gradient-color), var(--gradient-color), var(--gradient-color), var(--gradient-color-alpha)) calc(var(--gradient-progress) + 20%))";
const APPLE_LYRIC_SWEEP_TEXT_SHADOW =
  "0 0 var(--text-shadow-blur-radius) rgba(255, 255, 255, var(--text-shadow-opacity))";

type AppleCssVarStyle = React.CSSProperties & Record<`--${string}`, string | number>;

function appleSweepCssVars(
  gradientProgress: number,
  fgColor: string,
  fgUnsungColor: string,
): AppleCssVarStyle {
  const fg = parseCssColor(fgColor);
  const unsung = parseCssColor(fgUnsungColor);
  const channel = Math.round((fg.r + fg.g + fg.b) / 3);
  return {
    "--gradient-progress": `${gradientProgress.toFixed(3)}%`,
    "--gradient-color": channel,
    "--gradient-color-alpha-active": `var(--apple-lyric-sung-alpha, ${appleCssNumber(fg.a)})`,
    "--gradient-color-alpha": `var(--apple-lyric-unsung-alpha, ${appleCssNumber(unsung.a)})`,
  };
}

function appleSpatialLineColor(fallbackColor: string): string {
  const color = parseCssColor(fallbackColor);
  return `rgba(${color.r}, ${color.g}, ${color.b}, var(--apple-lyric-line-alpha, ${appleCssNumber(color.a)}))`;
}

function appleFixedVocalMotionVars(
  fgColor: string,
  fgUnsungColor: string,
): AppleCssVarStyle {
  return {
    "--apple-lyric-sung-alpha": appleCssNumber(parseCssColor(fgColor).a),
    "--apple-lyric-unsung-alpha": appleCssNumber(parseCssColor(fgUnsungColor).a),
  };
}

function parseCssColor(value: string): { r: number; g: number; b: number; a: number } {
  const parts = [...value.matchAll(/-?\d+(?:\.\d+)?/g)].map((match) => Number(match[0]));
  return {
    r: Number.isFinite(parts[0]) ? parts[0] : 255,
    g: Number.isFinite(parts[1]) ? parts[1] : 255,
    b: Number.isFinite(parts[2]) ? parts[2] : 255,
    a: Number.isFinite(parts[3]) ? parts[3] : 1,
  };
}

function appleCssNumber(value: number): string {
  return Number.isFinite(value) ? `${Math.round(value * 1000) / 1000}` : "1";
}

function appleVisibleSyllableText(text: string): string {
  const content = appleSyllableContent(text);
  const stripped = content.replace(/\s+$/u, "");
  return stripped.length > 0 ? stripped : content;
}

function appleRawSyllableText(text: string): string {
  const stripped = text.replace(/\s+$/u, "");
  return stripped.length > 0 ? stripped : text;
}

function appleSyllableContent(text: string): string {
  return text.replace(/[()]/g, "");
}

function YrcSlowEmphasisToken({
  char,
  tokenIndex,
  positionSec,
  fgColor,
  fgUnsungColor,
  desktopAppleMotion,
}: {
  char: YrcChar;
  tokenIndex: number;
  positionSec: number;
  fgColor: string;
  fgUnsungColor: string;
  desktopAppleMotion: boolean;
}) {
  // Apple keeps `hasTrailingWhitespace` on the surrounding word group. The
  // spacing glyph is not part of `data-content`, the emphasis letter list, or
  // the per-letter stagger divisor.
  const renderedContent = desktopAppleMotion ? appleVisibleSyllableText(char.text) : char.text;
  const glyphs = Array.from(renderedContent);
  const glyphCountValue = desktopAppleMotion ? appleLyricContentLength(char.text) : visibleLyricGlyphCount(char.text);
  const empGain = Math.max(0, Math.min(1, emphasisAmp(char.durSec)));
  let visibleIndex = 0;

  return (
    <span
      className={desktopAppleMotion ? "syllable emphasis" : undefined}
      data-yrc-token={char.text}
      data-yrc-token-index={tokenIndex}
      data-yrc-token-kind="slow"
      data-apple-syllable={desktopAppleMotion ? "true" : undefined}
      style={desktopAppleMotion
        ? {
            ...appleSyllableStyle,
            ...appleCurrentSyllableEdge,
          }
        : { display: "inline-block", whiteSpace: "break-spaces" }}
    >
      {glyphs.map((glyph, idx) => {
        if (!desktopAppleMotion && /\s/.test(glyph)) {
          return <span key={`${idx}-${glyph}`}>{glyph}</span>;
        }
        const letterIdx = desktopAppleMotion ? idx : visibleIndex++;
        const letterState = appleSlowLetterState(char, letterIdx, glyphCountValue, positionSec, empGain);
        // Keep one word-wide color front. Letter lift/glow windows may overlap,
        // but only one visible glyph owns a partial color sweep at a time.
        const colorProgress = desktopAppleMotion
          ? appleSlowLetterColorProgress(char, letterIdx, glyphCountValue, positionSec)
          : letterState.gradientProgress;
        const isSweeping =
          colorProgress > -APPLE_SWEEP_LEAD_PERCENT &&
          colorProgress < 100;
        const gradientBackground = desktopAppleMotion
          ? APPLE_LYRIC_SWEEP_BACKGROUND
          : isSweeping
            ? `linear-gradient(90deg, ${fgColor} ${colorProgress.toFixed(3)}%, ${fgUnsungColor} ${(colorProgress + APPLE_SWEEP_LEAD_PERCENT).toFixed(3)}%)`
          : "none";
        const glowShadow = desktopAppleMotion
          ? APPLE_LYRIC_SWEEP_TEXT_SHADOW
          : letterState.shadowOpacity > 0.004 && letterState.shadowBlur > 0.2
            ? `0 0 ${letterState.shadowBlur.toFixed(2)}px rgba(255,255,255,${letterState.shadowOpacity.toFixed(3)})`
            : "none";
        const desktopLetterVars = desktopAppleMotion
          ? {
              ...appleSweepCssVars(colorProgress, fgColor, fgUnsungColor),
              "--text-shadow-blur-radius": `${letterState.shadowBlur.toFixed(2)}px`,
              "--text-shadow-opacity": appleCssNumber(letterState.shadowOpacity),
            } as AppleCssVarStyle
          : null;
        if (desktopAppleMotion) {
          return (
            <span
              key={`${idx}-${glyph}`}
              data-yrc-letter={glyph}
              data-yrc-letter-index={idx}
              className="letter"
              style={{
                ...desktopLetterVars,
                ...appleCurrentSyllableEdge,
                display: "inline-block",
                whiteSpace: "pre",
                overflow: "visible",
                color: colorProgress >= 100 ? fgColor : fgUnsungColor,
                transition: APPLE_LYRIC_TOKEN_COLOR_TRANSITION,
                transform: `translate3d(0, ${letterState.translateY.toFixed(2)}px, 0) scale(${letterState.scale.toFixed(3)})`,
                transformOrigin: "center",
                backgroundImage: gradientBackground,
                WebkitBackgroundClip: "text",
                backgroundClip: "text",
                WebkitTextFillColor: "transparent",
                textShadow: glowShadow,
              }}
            >
              {glyph}
            </span>
          );
        }
        return (
          <span
            key={`${idx}-${glyph}`}
            data-yrc-letter={glyph}
            data-yrc-letter-index={idx}
            style={{
              display: "inline-block",
              ...(desktopAppleMotion ? appleCurrentSyllableEdge : null),
              color: colorProgress >= 100 ? fgColor : fgUnsungColor,
              transform: `translate3d(0, ${letterState.translateY.toFixed(2)}px, 0) scale(${letterState.scale.toFixed(3)})`,
              transformOrigin: "center",
              backgroundImage: gradientBackground,
              WebkitBackgroundClip:
                isSweeping ? "text" : "border-box",
              backgroundClip:
                isSweeping ? "text" : "border-box",
              WebkitTextFillColor:
                isSweeping ? "transparent" : "currentColor",
              textShadow: glowShadow,
            }}
          >
            {glyph}
          </span>
        );
      })}
    </span>
  );
}

const appleCurrentSyllableEdge: React.CSSProperties = {
  margin: APPLE_CURRENT_SYLLABLE_MARGIN,
  padding: APPLE_CURRENT_SYLLABLE_PADDING,
  clipPath: APPLE_CURRENT_SYLLABLE_CLIP,
};

function appleSlowLetterColorProgress(
  char: YrcChar,
  letterIndex: number,
  letterCount: number,
  positionSec: number,
): number {
  const wordProgress = Math.max(
    0,
    Math.min(1, (positionSec - char.startSec) / Math.max(0.001, char.durSec)),
  );
  const letterProgress = Math.max(
    0,
    Math.min(1, wordProgress * Math.max(1, letterCount) - letterIndex),
  );
  return -APPLE_SWEEP_LEAD_PERCENT +
    (100 + APPLE_SWEEP_LEAD_PERCENT) * letterProgress;
}

function appleSlowLetterState(
  char: YrcChar,
  letterIndex: number,
  letterCount: number,
  positionSec: number,
  empGain: number,
): {
  scale: number;
  translateY: number;
  gradientProgress: number;
  shadowBlur: number;
  shadowOpacity: number;
} {
  const letterStepSec = Math.max(0.001, char.durSec / Math.max(1, letterCount));
  const elapsedSec = positionSec - (char.startSec + letterStepSec * letterIndex);
  const firstPhaseSec = 0.5;
  const totalPhaseSec = 1.0;
  if (elapsedSec <= 0) {
    return { scale: 1, translateY: 0, gradientProgress: -20, shadowBlur: 0, shadowOpacity: 0 };
  }
  if (elapsedSec < firstPhaseSec) {
    const t = Math.max(0, Math.min(1, elapsedSec / firstPhaseSec));
    return {
      scale: 1 + EMP_PEAK_SCALE * t * empGain,
      translateY: -2.05 * t * empGain,
      gradientProgress: -20 + 110 * t,
      shadowBlur: EMP_GLOW_BLUR_PX * t,
      shadowOpacity: EMP_GLOW_OPACITY * t * empGain,
    };
  }
  if (elapsedSec < totalPhaseSec) {
    const t = Math.max(0, Math.min(1, (elapsedSec - firstPhaseSec) / (totalPhaseSec - firstPhaseSec)));
    return {
      scale: 1 + EMP_PEAK_SCALE * (1 - t) * empGain,
      translateY: -(2.05 + (2 - 2.05) * t) * empGain,
      gradientProgress: 90 + 10 * t,
      shadowBlur: EMP_GLOW_BLUR_PX + (4 - EMP_GLOW_BLUR_PX) * t,
      shadowOpacity: EMP_GLOW_OPACITY * (1 - t) * empGain,
    };
  }
  return {
    scale: 1,
    translateY: -2 * empGain,
    gradientProgress: 100,
    shadowBlur: 4,
    shadowOpacity: 0,
  };
}

function easeOutCss(t: number): number {
  return 1 - Math.pow(1 - Math.max(0, Math.min(1, t)), 3);
}

function regularLyricLiftProgress(chars: YrcChar[], idx: number, positionSec: number): number {
  const char = chars[idx];
  if (!char) return 0;
  const base = regularWordLiftEase(regularTokenLiftProgress(char, positionSec));
  const prev = chars[idx - 1];
  if (!prev || prev.text.includes("\n") || positionSec < char.startSec) return base;
  const prevEndSec = prev.startSec + Math.max(0.001, prev.durSec);
  const gapSec = Math.max(0, char.startSec - prevEndSec);
  if (gapSec > WORD_CONTINUITY_MAX_GAP_SEC) return base;

  const durSec = Math.max(0.001, char.durSec);
  const handoffSec = Math.max(
    0.001,
    Math.min(WORD_CONTINUITY_HANDOFF_SEC, durSec * WORD_CONTINUITY_HANDOFF_FRACTION),
  );
  const elapsedSec = positionSec - char.startSec;
  if (elapsedSec >= handoffSec) return base;

  const gapT = 1 - Math.max(0, Math.min(1, gapSec / WORD_CONTINUITY_MAX_GAP_SEC));
  const gapStrength = smootherStep(gapT);
  const attackSec = Math.max(0.001, Math.min(WORD_CONTINUITY_ATTACK_SEC, handoffSec * 0.18));
  const attack = smootherStep(elapsedSec / attackSec);
  const release = 1 - smootherStep(elapsedSec / handoffSec);
  const carry = attack * release * gapStrength * WORD_CONTINUITY_LIFT_CARRY;
  return Math.max(base, carry);
}

function regularTokenLiftProgress(char: YrcChar, positionSec: number): number {
  const liftPositionSec = positionSec - APPLE_WORD_LIFT_DELAY_SEC;
  if (liftPositionSec <= char.startSec) return 0;
  return Math.max(0, Math.min(1, (liftPositionSec - char.startSec) / Math.max(0.001, char.durSec)));
}

function regularWordLiftEase(progress: number): number {
  const p = Math.max(0, Math.min(1, progress));
  const eased = easeOutCss(p);
  return p + (eased - p) * WORD_FLOAT_EASE_BLEND;
}

function shouldUseSlowEmphasis(char: YrcChar, desktopAppleMotion = false): boolean {
  const minDurationSec = desktopAppleMotion ? APPLE_EMP_MIN_DURATION_SEC : EMP_MIN_DURATION_SEC;
  if (char.durSec < minDurationSec) return false;
  return desktopAppleMotion ? appleLyricRawContentLength(char.text) <= 7 : visibleLyricGlyphCount(char.text) <= 7;
}

function appleLyricContentLength(text: string): number {
  return Math.max(1, appleVisibleSyllableText(text).length);
}

function appleLyricRawContentLength(text: string): number {
  return Math.max(1, appleRawSyllableText(text).length);
}

function visibleLyricGlyphCount(text: string): number {
  return Math.max(1, Array.from(text).filter((c) => !/\s/.test(c)).length);
}

function emphasisAmp(durationSec: number): number {
  return durationSec >= EMP_MIN_DURATION_SEC ? 1 : 0;
}

function smootherStep(t: number): number {
  const x = Math.max(0, Math.min(1, t));
  return x * x * x * (x * (x * 6 - 15) + 10);
}

function lineFrame(
  isActive: boolean,
  rowH: string,
  rowGap: string,
  activeFs: string,
  dimFs: string,
  immersive: boolean,
  distance: number,
  desktopAppleMotion = false,
  willAnimate = false,
  text = "",
  alignment: "start" | "end" = "start",
  appleInactiveFilter = false,
  appleFadeOut = false,
): React.CSSProperties {
  if (desktopAppleMotion) {
    const focusProgress = isActive ? 1 : 0;
    const style: React.CSSProperties & Record<`--${string}`, string | number | undefined> = {
      margin: 0,
      marginRight: `calc(${APPLE_LYRIC_LINE_OVERBLEED} * -1)`,
      paddingRight: APPLE_LYRIC_LINE_OVERBLEED,
      height: "auto",
      width: "100%",
      display: "block",
      boxSizing: "border-box",
      fontFamily: APPLE_LYRIC_FONT_FAMILY,
      fontSize: activeFs,
      lineHeight: 0,
      textAlign: alignment === "end" ? "right" : "left",
      overflow: "visible",
      cursor: "pointer",
      filter: appleInactiveFilter ? APPLE_LYRIC_INACTIVE_ROW_FILTER : undefined,
      transition: APPLE_LYRIC_ROW_FILTER_TRANSITION,
      "--line-animation-name": appleFadeOut ? APPLE_LYRIC_FADE_OUT_ANIMATION : undefined,
      "--apple-lyric-focus-progress": focusProgress,
      "--apple-lyric-line-scale": 1 + (APPLE_LYRIC_CURRENT_SCALE - 1) * focusProgress,
      "--apple-lyric-line-alpha": APPLE_INACTIVE_LINE_ALPHA +
        (APPLE_TIMED_GRADIENT_ACTIVE_ALPHA - APPLE_INACTIVE_LINE_ALPHA) * focusProgress,
      "--apple-lyric-sung-alpha": APPLE_INACTIVE_LINE_ALPHA +
        (APPLE_TIMED_GRADIENT_ACTIVE_ALPHA - APPLE_INACTIVE_LINE_ALPHA) * focusProgress,
      "--apple-lyric-unsung-alpha": APPLE_INACTIVE_LINE_ALPHA +
        (APPLE_TIMED_GRADIENT_UNSUNG_ALPHA - APPLE_INACTIVE_LINE_ALPHA) * focusProgress,
      willChange: willAnimate
        ? "transform, opacity, color, top, background-image"
        : undefined,
    };
    return style;
  }
  // transition 写在"目标态"上，进 / 出 active 用不同时间窗。
  // immersive 不再变 fontSize（active/inactive 同号），但仍保留 transition
  // 兼容 compact 模式以及未来重新启用尺寸切换的可能。
  const transition =
    "font-size 170ms cubic-bezier(0.25, 0.1, 0.25, 1), opacity 170ms cubic-bezier(0.25, 0.1, 0.25, 1), filter 140ms cubic-bezier(0.25, 0.1, 0.25, 1), color 170ms cubic-bezier(0.25, 0.1, 0.25, 1)";
  const immersiveOpacity = isActive
    ? 1
    : desktopAppleMotion
      ? 1
      : distance === 1
        ? 0.36
        : distance === 2
          ? 0.27
          : distance === 3
            ? 0.2
            : distance === 4
              ? 0.15
              : 0.11;
  const immersiveBlur = !immersive || isActive
    ? "none"
    : desktopAppleMotion
      ? "none"
      : distance <= 1
        ? "none"
        : `blur(${distance === 2 ? 1.2 : 2}px)`;
  const appleLineHeight = desktopAppleMotion ? appleLineHeightForFontSize(activeFs) : APPLE_LYRIC_LINE_HEIGHT;
  const appleFontWeight = desktopAppleMotion && containsCjkLyric(text) ? 600 : 700;
  const verticalPadding = immersive
    ? desktopAppleMotion && isActive
      ? `${APPLE_LYRIC_CURRENT_PADDING_BLOCK_PX}px`
      : "0"
    : "2px";
  const horizontalPadding = immersive ? (desktopAppleMotion ? "0" : "4px") : "12px";

  return {
    minHeight: rowH,
    height: undefined,
    marginBottom: desktopAppleMotion ? rowGap : undefined,
    width: desktopAppleMotion ? "100%" : undefined,
    boxSizing: "border-box",
    display: "flex",
    alignItems: desktopAppleMotion ? "flex-start" : "center",
    justifyContent: immersive ? "flex-start" : "center",
    fontSize: isActive ? activeFs : dimFs,
    fontFamily: desktopAppleMotion ? APPLE_LYRIC_FONT_FAMILY : undefined,
    // immersive: 所有行同字重（Heavy）—— 跟 Apple Music 一致，激活态全靠
    //   "色彩对比 + 逐字 wipe" 凸显，不靠字重切换。
    // compact: 仍保持 active 加粗 / inactive 细体，节省竖向空间。
    fontWeight: immersive ? (desktopAppleMotion ? appleFontWeight : 800) : isActive ? 600 : 400,
    letterSpacing: 0,
    lineHeight: immersive ? (desktopAppleMotion ? appleLineHeight : 1.56) : undefined,
    transform: desktopAppleMotion && isActive
      ? `scale(${APPLE_LYRIC_CURRENT_SCALE})`
      : "scale(1)",
    transformOrigin: desktopAppleMotion && alignment === "end" ? "right center" : "left center",
    filter: immersiveBlur,
    // 非 active 行：desktop Apple 风主要靠暗行颜色 + blur；其它 immersive
    // 仍用 opacity 形成对比；compact 保留较亮（0.42）让 3 行带子整体可读。
    opacity: immersive ? immersiveOpacity : isActive ? 1 : 0.42,
    overflow: "visible",
    paddingTop: verticalPadding,
    paddingBottom: verticalPadding,
    paddingLeft: horizontalPadding,
    paddingRight: desktopAppleMotion ? APPLE_LYRIC_LINE_OVERBLEED : horizontalPadding,
    marginRight: desktopAppleMotion ? `calc(${APPLE_LYRIC_LINE_OVERBLEED} * -1)` : undefined,
    textAlign: immersive ? (desktopAppleMotion && alignment === "end" ? "right" : "left") : "center",
    cursor: "pointer",
    transition,
    willChange: desktopAppleMotion && (willAnimate || isActive)
      ? "transform, min-height, height, padding, opacity, filter, color"
      : undefined,
  };
}

function appleCollapsibleLineFrame(
  isActive: boolean,
  isFirst: boolean,
): React.CSSProperties {
  if (isActive) {
    return {
      height: "auto",
      minHeight: undefined,
      overflow: "visible",
      animation: isFirst
        ? "none"
        : `${APPLE_LYRIC_COLLAPSIBLE_EXPAND_ANIMATION} ${APPLE_LYRIC_COLLAPSIBLE_MS}ms ease-in-out 1`,
    };
  }
  return {
    height: 0,
    minHeight: 0,
    overflow: "hidden",
    animation: `${APPLE_LYRIC_COLLAPSIBLE_COLLAPSE_ANIMATION} ${APPLE_LYRIC_COLLAPSIBLE_MS}ms ease-in-out 1`,
  };
}

function appleLineHeightForFontSize(fontSize: string): number {
  if (fontSize === "84px") return 1.1904761905;
  if (fontSize === "62px") return 1.1935483871;
  if (fontSize === "48px") return 1.2083333333;
  if (fontSize === "38px") return 1.2105263158;
  return APPLE_LYRIC_LINE_HEIGHT;
}

function containsCjkLyric(text: string): boolean {
  return /[\u3400-\u9fff\uf900-\ufaff]/.test(text);
}

function lineInner(
  immersive = false,
  alignment: "start" | "end" = "start",
  desktopAppleMotion = false,
  activeFs = IMMERSIVE_ACTIVE_FS,
  isActive = false,
  rowGap = "0px",
  text = "",
  willAnimate = false,
  appleCollapsedLine = false,
  appleIsDuet = false,
  appleLineMinHeight: string | undefined = undefined,
): React.CSSProperties {
  const lineHeight = desktopAppleMotion ? appleLineHeightForFontSize(activeFs) : APPLE_LYRIC_LINE_HEIGHT;
  if (desktopAppleMotion) {
    const appleFontWeight = containsCjkLyric(text) ? 600 : 700;
    return {
      margin: 0,
      marginTop: 0,
      marginRight: 0,
      marginBottom: rowGap,
      marginLeft: 0,
      padding: isActive ? `${APPLE_LYRIC_CURRENT_PADDING_BLOCK_PX}px 0` : "0",
      display: "inline-block",
      width: appleIsDuet ? "60%" : "auto",
      minHeight: appleLineMinHeight,
      maxWidth: "100%",
      boxSizing: "content-box",
      verticalAlign: "baseline",
      border: 0,
      backgroundColor: "transparent",
      outline: "none",
      cursor: "pointer",
      appearance: "none",
      fontFamily: APPLE_LYRIC_FONT_FAMILY,
      fontSize: activeFs,
      fontWeight: appleFontWeight,
      letterSpacing: 0,
      lineHeight,
      color: "inherit",
      whiteSpace: "normal",
      overflow: appleCollapsedLine ? "hidden" : "visible",
      overflowWrap: "normal",
      wordBreak: "normal",
      textOverflow: "clip",
      textAlign: alignment === "end" ? "right" : "initial",
      height: appleCollapsedLine ? 0 : undefined,
      transform: appleCollapsedLine
        ? "scale(0.1)"
        : `scale(var(--apple-lyric-line-scale, ${isActive ? APPLE_LYRIC_CURRENT_SCALE : 1}))`,
      transformOrigin: alignment === "end" ? "right top" : "left top",
      transition: APPLE_LYRIC_SPRING_LINE_TRANSITION,
      // Apple .line defaults this var to paused, but fullscreen lyrics
      // overrides --line-animation-play-state to running on amp-lyrics.
      // Rows before [is-current] receive the fade-out animation name from
      // host CSS; current/future rows keep animationName none.
      animationName: "var(--line-animation-name, none)",
      animationDuration: "1s",
      animationDelay: "0s",
      animationPlayState: "var(--line-animation-play-state, paused)",
      animationTimingFunction: "linear",
      animationIterationCount: 1,
      animationFillMode: "forwards",
    };
  }
  return {
    width: "100%",
    minWidth: 0,
    whiteSpace: "normal",
    overflow: "visible",
    textOverflow: "clip",
    maxWidth: "100%",
    lineHeight: immersive ? lineHeight : 1.35,
    overflowWrap: immersive ? "normal" : "anywhere",
    wordBreak: immersive ? "normal" : "break-word",
    textAlign: alignment === "end" ? "right" : immersive ? "left" : "center",
  };
}

function PlaceholderLyric({ text, mode }: { text: string; mode: "compact" | "immersive" }) {
  if (mode === "immersive") {
    return (
      <div
        style={{
          position: "absolute",
          inset: 0,
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
          color: "rgba(255,255,255,0.45)",
          fontSize: 15,
          fontStyle: "italic",
        }}
      >
        {text}
      </div>
    );
  }
  return (
    <div style={lyricBox}>
      <div style={lyricPlaceholder}>{text}</div>
    </div>
  );
}

// ============== Shared desktop glyphs ==============

function PlayGlyph() {
  return <AppIcon name="play" size="1em" />;
}
function PauseGlyph() {
  return <AppIcon name="pause" size="1em" />;
}
function SkipBack() {
  return <AppIcon name="previous" size="1em" />;
}
function SkipForward() {
  return <AppIcon name="next" size="1em" />;
}

function FlatBtn({
  children,
  onClick,
  disabled,
  large,
  "aria-label": ariaLabel,
}: {
  children: React.ReactNode;
  onClick?: () => void;
  disabled?: boolean;
  large?: boolean;
  "aria-label"?: string;
}) {
  return (
    <button
      onClick={onClick}
      disabled={disabled}
      aria-label={ariaLabel}
      className="platform-icon-button"
      style={{
        fontSize: large ? "clamp(46px, 11vw, 56px)" : "clamp(32px, 7.6vw, 38px)",
        width: large ? "clamp(58px, 14vw, 72px)" : "clamp(44px, 10vw, 56px)",
        height: large ? "clamp(58px, 14vw, 72px)" : "clamp(44px, 10vw, 56px)",
        display: "inline-flex",
        alignItems: "center",
        justifyContent: "center",
        cursor: disabled ? "not-allowed" : "pointer",
        border: "none",
        background: "transparent",
        color: "#f5f7ff",
        padding: 0,
        opacity: disabled ? 0.32 : 1,
        transition: "transform 120ms ease, opacity 160ms ease",
      }}
      onMouseDown={(e) => {
        if (!disabled) e.currentTarget.style.transform = "scale(0.92)";
      }}
      onMouseUp={(e) => {
        e.currentTarget.style.transform = "scale(1)";
      }}
      onMouseLeave={(e) => {
        e.currentTarget.style.transform = "scale(1)";
      }}
    >
      {children}
    </button>
  );
}

function fmt(s: number) {
  const m = Math.floor(s / 60);
  const sec = Math.floor(s % 60);
  return `${m}:${sec.toString().padStart(2, "0")}`;
}

// ============== styles ==============

const shell: React.CSSProperties = {
  position: "relative",
  width: "100%",
  maxWidth: 600,
  margin: "0 auto",
  // 手机端 padding ×2：原 8/2vw/20 视觉上太贴边，封面跟标题都顶到屏幕
  padding: "0 clamp(16px, 4vw, 40px)",
};

const contentLayer: React.CSSProperties = {
  position: "relative",
  zIndex: 1,
};

const trackColumn: React.CSSProperties = {
  width: COVER_SIZE,
  margin: "0 auto",
  display: "flex",
  flexDirection: "column",
};

const coverPlaceholder: React.CSSProperties = {
  position: "absolute",
  inset: 0,
  width: "100%",
  height: "100%",
  display: "flex",
  alignItems: "center",
  justifyContent: "center",
  background:
    "linear-gradient(135deg, rgba(155,227,198,0.10) 0%, rgba(155,227,198,0.02) 100%)",
  padding: 24,
};

const titleBlock: React.CSSProperties = {
  // 比原先 14-22 略松：2.0-3.4vh
  marginTop: "clamp(18px, 3vh, 28px)",
  padding: 0,
};

const titleTextCol: React.CSSProperties = {
  minWidth: 0,
  textAlign: "left",
};

const titleStyle: React.CSSProperties = {
  fontSize: TITLE_FS,
  fontWeight: 600,
  letterSpacing: 0,
  lineHeight: 1.25,
  color: "#f5f7ff",
  overflow: "hidden",
  textOverflow: "ellipsis",
  whiteSpace: "nowrap",
};

const subtitleStyle: React.CSSProperties = {
  marginTop: 6,
  fontSize: SUBTITLE_FS,
  fontWeight: 500,
  color: "rgba(233,239,255,0.55)",
  overflow: "hidden",
  textOverflow: "ellipsis",
  whiteSpace: "nowrap",
};

const progressWrap: React.CSSProperties = {
  // 比原先 10-16 略松：14-22
  marginTop: "clamp(14px, 2.4vh, 22px)",
  padding: 0,
};

const progressTrack: React.CSSProperties = {
  position: "relative",
  // 进度条粗一档：3 → 4
  height: 4,
  background: "rgba(233,239,255,0.12)",
  borderRadius: 999,
  overflow: "hidden",
};

const progressFill: React.CSSProperties = {
  position: "absolute",
  left: 0,
  top: 0,
  bottom: 0,
  background: "rgba(245,247,255,0.92)",
  borderRadius: 999,
  transition: "width 120ms linear",
};

const timeRow: React.CSSProperties = {
  marginTop: 8,
  display: "flex",
  justifyContent: "space-between",
  alignItems: "center",
};

const mono: React.CSSProperties = {
  fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace",
  fontSize: 11,
  fontVariantNumeric: "tabular-nums",
  color: "rgba(233,239,255,0.72)",
};

const controlsRow: React.CSSProperties = {
  marginTop: "clamp(22px, 3.4vh, 32px)",
  // 三列等分网格 + 每格居中：prev / play / next 各自落在 1/3 列中心。
  // 底栏另用四列，但与本行共用 320px 外边界，避免不同按钮数量互相挤压。
  display: "grid",
  gridTemplateColumns: "1fr 1fr 1fr",
  placeItems: "center",
  width: "min(100%, 320px)",
  alignSelf: "center",
  // 三列等分网格的列中心已经在 1/6、3/6、5/6 处，不需要再加内边距
  paddingInline: 0,
};

const lyricBox: React.CSSProperties = {
  marginTop: "clamp(6px, 1.2vh, 14px)",
  marginLeft: "clamp(8px, 4vw, 24px)",
  marginRight: "clamp(8px, 4vw, 24px)",
  height: LYRIC_BOX_H,
  // 现在所有行都渲染，靠 overflow + mask 限定可见窗 —— 否则歌词会撑出容器
  overflow: "hidden",
  textAlign: "center",
  position: "relative",
};

const lyricPlaceholder: React.CSSProperties = {
  position: "absolute",
  inset: 0,
  display: "flex",
  alignItems: "center",
  justifyContent: "center",
  fontSize: 13,
  color: "rgba(233,239,255,0.7)",
  fontStyle: "italic",
};

const errorBar: React.CSSProperties = {
  margin: "18px clamp(12px, 4vw, 28px) 0",
  padding: "9px 14px",
  borderRadius: 10,
  background: "rgba(255,180,180,0.08)",
  border: "1px solid rgba(255,180,180,0.22)",
  color: "#ffb4b4",
  fontSize: 12,
  lineHeight: 1.5,
  textAlign: "center",
};

// ---------- immersive styles ----------

const immersiveTitle: React.CSSProperties = {
  fontSize: "clamp(16px, 4.4vw, 20px)",
  fontWeight: 700,
  letterSpacing: 0,
  color: "rgba(255,255,255,0.96)",
  overflow: "hidden",
  textOverflow: "ellipsis",
  whiteSpace: "nowrap",
};

const immersiveSubtitle: React.CSSProperties = {
  marginTop: 5,
  fontSize: "clamp(12px, 3.2vw, 14px)",
  fontWeight: 500,
  color: "rgba(255,255,255,0.62)",
  overflow: "hidden",
  textOverflow: "ellipsis",
  whiteSpace: "nowrap",
};

// 桌面版：title 落在封面正下方，没有 mask 干扰，可以放心放大
const immersiveTitleLarge: React.CSSProperties = {
  fontSize: "clamp(20px, 2.2vw, 28px)",
  fontWeight: 700,
  letterSpacing: 0,
  color: "rgba(255,255,255,0.96)",
  overflow: "hidden",
  textOverflow: "ellipsis",
  whiteSpace: "nowrap",
};

const immersiveSubtitleLarge: React.CSSProperties = {
  marginTop: 6,
  fontSize: "clamp(13px, 1.3vw, 16px)",
  fontWeight: 500,
  color: "rgba(255,255,255,0.62)",
  overflow: "hidden",
  textOverflow: "ellipsis",
  whiteSpace: "nowrap",
};

const immersiveLyricFrame: React.CSSProperties = {
  position: "absolute",
  inset: 0,
  overflow: "hidden",
};

const appleDesktopLyricFrame: React.CSSProperties = {
  position: "absolute",
  top: "10%",
  right: 0,
  bottom: "10%",
  left: 0,
  overflow: "hidden",
  "--inactive-gaussian-blur": "2px",
  "--lyrics-display-synced-line-opacity": 0,
  "--line-animation-play-state": "running",
} as React.CSSProperties & {
  "--inactive-gaussian-blur": string;
  "--lyrics-display-synced-line-opacity": number;
  "--line-animation-play-state": string;
};

// 极简底栏图标 —— 跟 distill / play page 的 floating chip 同款规格
const navIcon: React.CSSProperties = {
  display: "inline-flex",
  alignItems: "center",
  justifyContent: "center",
  width: 36,
  height: 36,
  borderRadius: 999,
  border: "none",
  background: "transparent",
  color: "rgba(245,247,255,0.82)",
  cursor: "pointer",
  textDecoration: "none",
  filter: "drop-shadow(0 1px 6px rgba(0,0,0,0.45))",
  transition: "transform 160ms cubic-bezier(0.22,0.61,0.36,1), opacity 160ms ease",
};

// 三个 nav 图标 strokeWidth 全部 2.2 + 尺寸 20，跟 SkipBack/Forward 的 2.2 视觉重量对齐
function LyricsIcon() {
  return <AppIcon name="lyrics" size={20} />;
}

function ListIcon() {
  return <AppIcon name="library" size={20} />;
}

function NavDownloadIcon() {
  return <AppIcon name="download" size={20} />;
}

function NavGearIcon() {
  return <AppIcon name="settings" size={20} />;
}
