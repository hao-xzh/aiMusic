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
import { type LrcLine } from "@/lib/lrc";
import { type YrcLine } from "@/lib/yrc";
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
const LYRIC_BOX_H = "clamp(116px, 15vh, 150px)";
const LYRIC_ROW_H = "clamp(34px, 4.8vh, 42px)";
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
          yrcLines={lyric?.yrcLines ?? []}
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
// 显示策略：
//   - 优先 yrc（逐字 / karaoke）：每个字按 (now - charStart) / charDur 算 progress，
//     已唱完 → 白；正在唱 → 半白半灰的 wipe 渐变；还没唱 → 灰
//   - 没有 yrc 时回退到行级 LRC + 行内插值"假逐字"：当前行从开头到下一行起点
//     之间的进度按字符等分，扫一道高亮过去
//   - 当前行整体 scale 略大 + 一道 box-shadow 暖光（跟 cover orb 同色系）
//   - 容器有上下 mask 渐隐 + 当前行两端 mask "侧光"

// 可视行数：3 行（上一行 / 激活行 / 下一行）
const LYRIC_ROWS = 3;

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
  // ⚠️ 所有 hooks 必须在任何 early return 之前。

  // 优先用 yrc；没有就降级到 LRC 行级
  const useYrc = yrcLines.length > 0;
  const lrcTimes = useMemo(
    () =>
      useYrc
        ? yrcLines.map((y) => y.time)
        : lines.map((l) => l.time),
    [useYrc, lines, yrcLines],
  );

  const idx = useMemo(() => {
    // 二分找当前行
    if (lrcTimes.length === 0) return -1;
    let lo = 0, hi = lrcTimes.length - 1, ans = -1;
    while (lo <= hi) {
      const mid = (lo + hi) >> 1;
      if (lrcTimes[mid] <= positionSec) {
        ans = mid;
        lo = mid + 1;
      } else {
        hi = mid - 1;
      }
    }
    return ans;
  }, [lrcTimes, positionSec]);

  // 渲染所有行：lines/yrcLines 数组本身不变就不重建
  // 但每行需要根据 positionSec 实时算字进度 —— 这一段必须每帧跑
  const visibleStart = Math.max(0, idx - 1);
  const visibleEnd = useYrc
    ? Math.min(yrcLines.length, idx + 2)
    : Math.min(lines.length, idx + 2);

  const renderedLines = useMemo(() => {
    if (useYrc) {
      return yrcLines.slice(visibleStart, visibleEnd).map((y, offset) => {
        const i = visibleStart + offset;
        return (
          <YrcLineView
            key={i}
            line={y}
            isActive={i === idx}
            dist={Math.abs(i - idx)}
            positionSec={positionSec}
          />
        );
      });
    }
    return lines.slice(visibleStart, visibleEnd).map((ln, offset) => {
      const i = visibleStart + offset;
      const dist = Math.abs(i - idx);
      const isActive = i === idx;
      // LRC 模式下用插值"假逐字"：当前行从其 start 到下一行 start 的时长里，
      // 高亮按字符等分推进
      let lineProgress = 0;
      if (isActive) {
        const start = ln.time;
        const end = i + 1 < lines.length ? lines[i + 1].time : start + 5;
        const dur = Math.max(0.4, end - start);
        lineProgress = Math.max(0, Math.min(1, (positionSec - start) / dur));
      } else if (i < idx) {
        lineProgress = 1;
      }
      return (
        <LrcLineView
          key={i}
          text={ln.text}
          isActive={isActive}
          dist={dist}
          progress={lineProgress}
        />
      );
    });
  }, [useYrc, lines, yrcLines, idx, positionSec, visibleStart, visibleEnd]);

  // 早 return 必须放在所有 hooks 之后
  const placeholder = (text: string) => (
    <div style={lyricBox}>
      <div style={lyricPlaceholder}>{text}</div>
    </div>
  );

  if (!meta) return placeholder("歌词加载中…");
  if (meta.instrumental) return placeholder("♪ 纯音乐 ♪");
  if (lrcTimes.length === 0) {
    return placeholder(
      `这首歌没有歌词${meta.uncollected ? "（网易云收录不全）" : ""}`,
    );
  }

  const safeIdx = Math.max(idx, 0);
  const centerRow = Math.floor(LYRIC_ROWS / 2);
  const offset = centerRow - (safeIdx - visibleStart);
  const translateExpr = `calc(${offset} * ${LYRIC_ROW_H})`;

  return (
    <div style={lyricBox}>
      <div
        style={{
          position: "absolute",
          left: 0,
          right: 0,
          top: 0,
          transform: `translate3d(0, ${translateExpr}, 0)`,
          transition: "transform 520ms cubic-bezier(0.22, 1, 0.36, 1)",
          willChange: "transform",
        }}
      >
        {renderedLines}
      </div>
    </div>
  );
}

// 颜色 token —— 已唱（白）/ 未唱（淡灰）。距离当前行越远的行颜色越浅
const SUNG_WHITE = "rgba(255,255,255,0.9)";
const UNSUNG_GRAY = "rgba(168,174,194,0.9)";

/**
 * yrc 一行：每个字独立渲染，按播放进度做颜色过渡 + wipe 渐变。
 * 字与字之间用空格 / 标点天然分隔（yrc 字段已含空格）。
 */
function YrcLineView({
  line,
  isActive,
  dist,
  positionSec,
}: {
  line: YrcLine;
  isActive: boolean;
  dist: number;
  positionSec: number;
}) {
  return (
    <div style={lineFrame(isActive, dist)}>
      <div style={lineInner(isActive)}>
        {isActive ? (
          <YrcActiveLine line={line} positionSec={positionSec} />
        ) : (
          <span
            style={{
              color: UNSUNG_GRAY,
              opacity: Math.max(0.4, 1 - dist * 0.18),
              whiteSpace: "break-spaces",
            }}
          >
            {line.text}
          </span>
        )}
      </div>
    </div>
  );
}

function YrcActiveLine({
  line,
  positionSec,
}: {
  line: YrcLine;
  positionSec: number;
}) {
  const chars = line.chars;
  if (chars.length === 0) {
    return (
      <span style={{ color: SUNG_WHITE, whiteSpace: "break-spaces" }}>
        {line.text}
      </span>
    );
  }

  const currentIdx = chars.findIndex((c) => positionSec < c.startSec + c.durSec);
  if (currentIdx === -1) {
    return (
      <span style={{ color: SUNG_WHITE, whiteSpace: "break-spaces" }}>
        {line.text}
      </span>
    );
  }

  const current = chars[currentIdx]!;
  let before = "";
  let after = "";
  for (let i = 0; i < currentIdx; i++) before += chars[i]!.text;
  for (let i = currentIdx + 1; i < chars.length; i++) after += chars[i]!.text;

  // 已经过了字尾 → 白；还没到字头 → 灰
  // 正好正在唱 → 用 mask-image 的线性渐变 wipe
  let progress = 0;
  if (positionSec >= current.startSec + current.durSec) progress = 1;
  else if (positionSec > current.startSec) progress = (positionSec - current.startSec) / Math.max(0.001, current.durSec);

  const beingSung = progress > 0 && progress < 1;
  const sung = progress >= 1;

  return (
    <>
      {before && (
        <span style={{ color: SUNG_WHITE, whiteSpace: "break-spaces" }}>
          {before}
        </span>
      )}
      <span
      style={{
        position: "relative",
        display: "inline-block",
        whiteSpace: "break-spaces",
        // 双层叠加：底层灰字 + 上层白字裁剪到 progress 宽度
        color: sung ? SUNG_WHITE : UNSUNG_GRAY,
        // wipe：用 background-clip text 给字一个渐变前景
        // 白色部分从左 0% 到 progress*100%，再 fade 1px 到灰
        backgroundImage: beingSung
          ? `linear-gradient(90deg, ${SUNG_WHITE} 0%, ${SUNG_WHITE} ${progress * 100}%, ${UNSUNG_GRAY} ${progress * 100 + 1}%, ${UNSUNG_GRAY} 100%)`
          : "none",
        WebkitBackgroundClip: beingSung ? "text" : "border-box",
        backgroundClip: beingSung ? "text" : "border-box",
        WebkitTextFillColor: beingSung ? "transparent" : "currentColor",
        // 已唱的字保留一点点 text-shadow 暖光，跟当前播放页主色对应
        textShadow: sung
          ? "0 0 14px rgba(var(--orb-rgb, 155 227 198), 0.18)"
          : "none",
      }}
    >
        {current.text}
      </span>
      {after && (
        <span style={{ color: UNSUNG_GRAY, whiteSpace: "break-spaces" }}>
          {after}
        </span>
      )}
    </>
  );
}

function LrcLineView({
  text,
  isActive,
  dist,
  progress,
}: {
  text: string;
  isActive: boolean;
  dist: number;
  progress: number;
}) {
  return (
    <div style={lineFrame(isActive, dist)}>
      <div style={lineInner(isActive)}>
        {isActive ? (
          // 假逐字：字符按 progress 切两段
          <span
            style={{
              backgroundImage: `linear-gradient(90deg, ${SUNG_WHITE} 0%, ${SUNG_WHITE} ${progress * 100}%, ${UNSUNG_GRAY} ${progress * 100 + 1}%, ${UNSUNG_GRAY} 100%)`,
              WebkitBackgroundClip: "text",
              backgroundClip: "text",
              WebkitTextFillColor: "transparent",
              textShadow: "0 0 14px rgba(var(--orb-rgb, 155 227 198), 0.16)",
            }}
          >
            {text}
          </span>
        ) : (
          <span
            style={{
              color: dist === 0 ? SUNG_WHITE : UNSUNG_GRAY,
              opacity: dist === 0 ? 1 : Math.max(0.4, 1 - dist * 0.18),
            }}
          >
            {text}
          </span>
        )}
      </div>
    </div>
  );
}

// 行外框：负责行的高度 + scale 动画 + 上下淡入淡出（按距离）
function lineFrame(isActive: boolean, dist: number): React.CSSProperties {
  return {
    minHeight: LYRIC_ROW_H,
    display: "flex",
    alignItems: "center",
    justifyContent: "center",
    fontSize: isActive ? LYRIC_ACTIVE_FS : LYRIC_DIM_FS,
    fontWeight: isActive ? 600 : 400,
    letterSpacing: 0,
    transform: "none",
    transformOrigin: "center",
    filter: "none",
    opacity: isActive ? 1 : Math.max(0.55, 1 - dist * 0.14),
    overflow: "visible",
    padding: "3px 12px",
    transition:
      "font-size 360ms cubic-bezier(0.22, 1, 0.36, 1), opacity 360ms cubic-bezier(0.22, 1, 0.36, 1)",
  };
}

// 行内文字容器：当前行两侧加一道 mask "舞台聚光"
function lineInner(isActive: boolean): React.CSSProperties {
  return {
    whiteSpace: "normal",
    overflow: "visible",
    textOverflow: "clip",
    maxWidth: "100%",
    lineHeight: 1.35,
    overflowWrap: "anywhere",
    wordBreak: "break-word",
  };
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
  overflow: "visible",
  textAlign: "center",
  position: "relative",
  // 之前在这里加过一层径向暗化 vignette 来"抠"歌词，
  // 但它会让歌词区的点比别处暗 —— 跟"整张图同一个背景"冲突。
  // 删掉，靠加重歌词字号 + 颜色对比让歌词自己能立住。
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
