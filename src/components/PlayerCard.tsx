"use client";

/**
 * Claudio 主播放卡片 v5 —— Apple Music / Shopify 极简风，全屏宽响应式。
 *
 * 设计原则：
 *   - 单列，没有玻璃卡，没有边框 —— 重感全部交给排版。
 *   - 整套尺寸用 clamp() 跑：iPhone 宽 340 起，到 1180 桌面，一套布局走完。
 *   - 顶部 header（CLAUDIO 标 + status + timecode）杀了 —— Apple Music 没有这条，
 *     之前那条是干扰，砍掉以后纵向有富余给歌词，最小窗口里也不挤。
 *   - 封面是 square via aspect-ratio + max-width，和卡同宽再封顶 320。
 *
 * 关于歌词：
 *   - 容器高度按 viewport 收缩（clamp(160px, 22vh, 220px)），矮窗里也能露至少 4 行。
 *   - 字号同样 clamp，不用 JS 测窗口。
 *
 * 之所以不再分 compact / immersive：
 *   - 两套布局意味着两套 bug 面，"歌词又看不到了"两次都是分支写崩。
 *   - 一个跑 340~1180 的单列，比两套窄宽都将就的好。
 */

import { Waveform } from "./Waveform";
import { usePlayer } from "@/lib/player-state";
import { findActiveLineIdx, type LrcLine } from "@/lib/lrc";
import { cdn } from "@/lib/cdn";
import React, { useMemo, useState } from "react";

// 兼容导出，page.tsx 里还在 import；现在永远是 "compact"。
export type PlayerMode = "compact";
export function usePlayerMode(): PlayerMode {
  return "compact";
}

// 设计 token —— 集中在这里方便整体调
//
// COVER_SIZE 用 min() 同时夹住 [视口宽 / 视口高 / 硬上限]：
//   - 86vw 让手机宽（~390px）下封面填到 ~86% 屏宽，不浪费两侧
//   - 上限 400px 是 Apple Music 桌面端的舒适大小
//   - 50vh 兜底：极矮的窗户里海报先让步给歌词
// 内层 column（title / progress / controls）也用同一个 token，海报和进度条天然同宽。
const COVER_SIZE = "min(clamp(220px, 86vw, 400px), 50vh)";
const TITLE_FS = "clamp(17px, 4vw, 22px)";
const SUBTITLE_FS = "clamp(12px, 3.2vw, 14px)";
// 容器高度跟行数成比例：3 行 × 每行 ~35px ≈ 105px。
// 之前 5 行用的 110~180，现在按 3/5 缩到 70~120，单行高度仍维持 ~30-40px。
const LYRIC_BOX_H = "clamp(72px, 12vh, 120px)";
// 激活行单独做大并加重 —— 跟 dim 行拉开 6px 字号差，避免 DotField 干扰
const LYRIC_ACTIVE_FS = "clamp(16px, 4.2vw, 19px)";
const LYRIC_DIM_FS = "clamp(11px, 2.8vw, 13px)";
const COVER_TRANSITION_MS = 720;

// ============== 主组件 ==============

export function PlayerCard() {
  const {
    current,
    isPlaying,
    positionSec,
    toggle,
    next,
    prev,
    error,
    lyric,
  } = usePlayer();

  const hasTrack = current !== null;
  const duration = current?.durationSec ?? 0;
  const pct =
    hasTrack && duration > 0
      ? Math.min(1, Math.max(0, positionSec / duration))
      : 0;
  const cover = current?.cover;

  return (
    <div style={shell}>
      <div style={contentLayer}>
        <div style={trackColumn}>
          <CoverBox cover={cover} isPlaying={isPlaying} />

          <div style={titleBlock}>
            <div style={titleTextCol}>
              <div style={titleStyle} title={current?.title}>
                {current?.title ?? "等你点一首"}
              </div>
              <div style={subtitleStyle}>
                {current
                  ? current.artist + (current.album ? ` · ${current.album}` : "")
                  : "去「我的歌单」挑一张"}
              </div>
            </div>
          </div>

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
        </div>

        <LyricStrip
          lines={lyric?.lines ?? []}
          positionSec={positionSec}
          meta={lyric}
        />

        {error && <div style={errorBar}>{error}</div>}
      </div>
    </div>
  );
}

// ============== 子组件 ==============

function CoverBox({
  cover,
  isPlaying,
}: {
  cover?: string | null;
  isPlaying: boolean;
}) {
  const coverUrl = cover ? cdn(cover) : null;
  return (
    <div
      data-claudio-cover
      style={{
        // 父级 trackColumn 已经是 COVER_SIZE 宽，这里跟满即可
        width: "100%",
        aspectRatio: "1 / 1",
        borderRadius: 12,
        overflow: "hidden",
        position: "relative",
        boxShadow:
          "0 24px 64px rgba(0,0,0,0.5), 0 0 0 1px rgba(255,255,255,0.04)",
        transform: isPlaying ? "scale(1.012)" : "scale(1)",
        transition: "transform 4000ms ease-in-out",
        background:
          "linear-gradient(135deg, rgba(155,227,198,0.10) 0%, rgba(155,227,198,0.02) 100%)",
      }}
    >
      <CoverImageLayer key={coverUrl ?? "empty-cover"} url={coverUrl} />
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
}

function CoverImageLayer({
  url,
}: {
  url: string | null;
}) {
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

// ---------- 歌词带 ----------
//
// Apple Music 风：5 行可视区，居中行点亮，相邻行按距离淡出。
// 容器高度走 viewport，矮屏自动收 —— 但行高用 % 跟容器走，不写死 36px。

// 可视行数：3 行（上一行 / 激活行 / 下一行）—— Apple Music 大屏歌词的克制版
const LYRIC_ROWS = 3;

function LyricStrip({
  lines,
  positionSec,
  meta,
}: {
  lines: LrcLine[];
  positionSec: number;
  meta:
    | { lines: LrcLine[]; instrumental: boolean; uncollected: boolean }
    | null;
}) {
  // ⚠️ 所有 hooks 必须在任何 early return 之前，否则歌词从 null 变非 null
  // 那一帧 hook 数量会从 1 跳到 2，React 立刻抛 "Rendered more hooks than..."
  // 整个 app 就崩了。

  const idx = useMemo(
    () => findActiveLineIdx(lines, positionSec),
    [lines, positionSec],
  );

  // 渲染一次，按 [lines, idx] memo —— positionSec 每 ~250ms 抖一次，
  // 但只有 idx 变了才需要重排所有行；不 memo 的话每次 timeupdate 都把
  // 50 行的 inline style 全拼一遍，即使值都没变也是重复活。
  const renderedLines = useMemo(() => {
    return lines.map((ln, i) => {
      const dist = Math.abs(i - idx);
      const isActive = i === idx;
      return (
        <div
          key={i}
          style={{
            height: `calc(${LYRIC_BOX_H} / ${LYRIC_ROWS})`,
            display: "flex",
            alignItems: "center",
            justifyContent: "center",
            fontSize: isActive ? LYRIC_ACTIVE_FS : LYRIC_DIM_FS,
            fontWeight: isActive ? 600 : 400,
            letterSpacing: isActive ? -0.2 : 0,
            color: isActive
              ? "#ffffff"
              : `rgba(233,239,255,${Math.max(0.32, 0.58 - dist * 0.08)})`,
            transform: isActive ? "scale(1.02)" : "scale(0.97)",
            transformOrigin: "center",
            filter: isActive ? "none" : "blur(0.2px)",
            overflow: "hidden",
            textOverflow: "ellipsis",
            whiteSpace: "nowrap",
            padding: "0 12px",
            // 所有动效统一到 520ms + 同一条 cubic-bezier，避免字号先到位、
            // 再颜色到位、最后位置才到位 —— 那种错位感就是"歌词不稳"。
            transition:
              "color 520ms cubic-bezier(0.22, 1, 0.36, 1), transform 520ms cubic-bezier(0.22, 1, 0.36, 1), font-size 520ms cubic-bezier(0.22, 1, 0.36, 1), filter 520ms cubic-bezier(0.22, 1, 0.36, 1)",
          }}
        >
          {ln.text}
        </div>
      );
    });
  }, [lines, idx]);

  // 早 return 必须放在所有 hooks 之后
  const placeholder = (text: string) => (
    <div style={lyricBox}>
      <div style={lyricPlaceholder}>{text}</div>
    </div>
  );

  if (!meta) return placeholder("歌词加载中…");
  if (meta.instrumental) return placeholder("♪ 纯音乐 ♪");
  if (lines.length === 0) {
    return placeholder(
      `这首歌没有歌词${meta.uncollected ? "（网易云收录不全）" : ""}`,
    );
  }

  const safeIdx = Math.max(idx, 0);
  const centerRow = Math.floor(LYRIC_ROWS / 2);
  const offset = centerRow - safeIdx;
  // translate 必须按"容器高度的 1/ROWS 行"算，不能用 % ——
  // translateY(%) 是相对元素自身高度，内层 stack 高度 = lines.length × 行高，
  // 50 行的歌写 -60% 实际是 -1080px 而不是 -3 行的 -108px。
  const translateExpr = `calc(${offset} * ${LYRIC_BOX_H} / ${LYRIC_ROWS})`;

  return (
    <div style={lyricBox}>
      <div
        style={{
          position: "absolute",
          left: 0,
          right: 0,
          top: 0,
          transform: `translate3d(0, ${translateExpr}, 0)`,
          // 父级 translate 也对齐 520ms —— 现在外滚 + 内变化是同时启停，
          // 视觉上就是"一整动作"而不是"先变形再滚到位"。
          transition: "transform 520ms cubic-bezier(0.22, 1, 0.36, 1)",
          willChange: "transform",
        }}
      >
        {renderedLines}
      </div>
    </div>
  );
}

// ---------- SVG（Apple Music 手机端风：裸 glyph，没有圆形底） ----------
//
// PlayGlyph / PauseGlyph 是大字号的中央按钮；SkipBack / SkipForward 是双三角。
// 高度全部用 1em，按钮的 fontSize 控制图标实际显示尺寸 —— 这样字号一改全部跟着缩放。

// 圆角技巧：fill 和 stroke 都用 currentColor，再加 stroke-linejoin="round"，
// 三角尖角和直角拐点都会被磨成圆角，几何还是原 path —— 比手画 cubic 路径稳。
// stroke-width 决定圆角"半径"，2.4 大约 ≈ 1.2px 视觉圆角，跟 Apple Music 接近。

function PlayGlyph() {
  return (
    <svg
      width="1em"
      height="1em"
      viewBox="0 0 24 24"
      fill="currentColor"
      stroke="currentColor"
      strokeWidth="2.4"
      strokeLinejoin="round"
      aria-hidden
    >
      <path d="M8 5.5v13l11-6.5z" />
    </svg>
  );
}
function PauseGlyph() {
  return (
    <svg width="1em" height="1em" viewBox="0 0 24 24" fill="currentColor" aria-hidden>
      <rect x="6" y="4.5" width="4.2" height="15" rx="1.6" />
      <rect x="13.8" y="4.5" width="4.2" height="15" rx="1.6" />
    </svg>
  );
}
// 双三角 + 竖线 —— 同样 stroke 圆角法，让三角尖部不刺眼
function SkipBack() {
  return (
    <svg
      width="1em"
      height="1em"
      viewBox="0 0 24 24"
      fill="currentColor"
      stroke="currentColor"
      strokeWidth="2.2"
      strokeLinejoin="round"
      aria-hidden
    >
      <rect x="3.6" y="6.5" width="1.8" height="11" rx="0.9" stroke="none" />
      <path d="M13 7.2L7 11.5v1L13 16.8z" />
      <path d="M21 7.2l-6 4.3v1l6 4.3z" />
    </svg>
  );
}
function SkipForward() {
  return (
    <svg
      width="1em"
      height="1em"
      viewBox="0 0 24 24"
      fill="currentColor"
      stroke="currentColor"
      strokeWidth="2.2"
      strokeLinejoin="round"
      aria-hidden
    >
      <path d="M3 7.2l6 4.3v1l-6 4.3z" />
      <path d="M11 7.2l6 4.3v1l-6 4.3z" />
      <rect x="18.6" y="6.5" width="1.8" height="11" rx="0.9" stroke="none" />
    </svg>
  );
}

/**
 * 裸 glyph 按钮 —— 没有圆形底色、没有 box-shadow、没有 hover 背景。
 * hover/press 全靠 opacity 和 scale 反馈，跟 Apple Music 一致。
 *
 * `large` 控制中央播放/暂停的字号；prev/next 用默认。
 */
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
      style={{
        // fontSize 决定 glyph（1em）的实际显示尺寸
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
  // 上限 600 —— 给 400 封面 + 两边各 ~100 的歌词外延留出来。
  // 手机宽下 width:100% 自动跟随，桌面宽下 600 封顶不至于"宽到没边"。
  maxWidth: 600,
  margin: "0 auto",
  padding: "0 clamp(8px, 2vw, 20px)",
};

const contentLayer: React.CSSProperties = {
  position: "relative",
  zIndex: 1,
};

// 海报 + 标题 + 进度 + 控制 同宽列。宽度走 COVER_SIZE，居中。
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
  marginTop: "clamp(14px, 2.6vh, 22px)",
  padding: 0,
};

const titleTextCol: React.CSSProperties = {
  minWidth: 0,
  textAlign: "left",
};

const titleStyle: React.CSSProperties = {
  fontSize: TITLE_FS,
  fontWeight: 600,
  letterSpacing: -0.4,
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
  marginTop: "clamp(10px, 1.8vh, 16px)",
  padding: 0,
};

const progressTrack: React.CSSProperties = {
  position: "relative",
  height: 3,
  background: "rgba(233,239,255,0.10)",
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
  marginTop: "clamp(18px, 3vh, 28px)",
  display: "flex",
  justifyContent: "center",
  alignItems: "center",
  gap: "clamp(36px, 12vw, 64px)",
};

const lyricBox: React.CSSProperties = {
  // 控制条 → 歌词的纵向间距收紧一档（之前 14~24，现在 6~14），
  // 让歌词区跟控制条视觉上更近，符合 Apple Music 那种"歌词紧跟在播控下面"的节奏
  marginTop: "clamp(6px, 1.2vh, 14px)",
  marginLeft: "clamp(8px, 4vw, 24px)",
  marginRight: "clamp(8px, 4vw, 24px)",
  height: LYRIC_BOX_H,
  overflow: "hidden",
  textAlign: "center",
  position: "relative",
  // 之前在这里加过一层径向暗化 vignette 来"抠"歌词，
  // 但它会让歌词区的点比别处暗 —— 跟"整张图同一个背景"冲突。
  // 删掉，靠加重歌词字号 + 颜色对比让歌词自己能立住。
  maskImage:
    "linear-gradient(180deg, transparent 0%, #000 22%, #000 78%, transparent 100%)",
  WebkitMaskImage:
    "linear-gradient(180deg, transparent 0%, #000 22%, #000 78%, transparent 100%)",
};

const lyricPlaceholder: React.CSSProperties = {
  position: "absolute",
  inset: 0,
  display: "flex",
  alignItems: "center",
  justifyContent: "center",
  fontSize: 13,
  // 0.42 在 DotField 波峰下完全看不见，提到 0.7
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
