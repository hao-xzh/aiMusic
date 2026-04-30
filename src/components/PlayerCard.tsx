"use client";

/**
 * Claudio 主播放卡片 v6 —— Apple Music / Shopify 极简风 + 沉浸式歌词覆盖层。
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
 *   - 动画全部走 transform / opacity，没有 width/height 过渡。
 */

import { Waveform } from "./Waveform";
import { usePlayer } from "@/lib/player-state";
import { type LrcLine } from "@/lib/lrc";
import { type YrcLine } from "@/lib/yrc";
import { cdn } from "@/lib/cdn";
import { useIsDesktop } from "@/lib/use-is-desktop";
import { useCoverEdgeColors, computeTone } from "@/lib/cover-color";
import Link from "next/link";
import React, { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { createPortal } from "react-dom";

// 兼容导出（page.tsx 仍 import）
export type PlayerMode = "compact";
export function usePlayerMode(): PlayerMode {
  return "compact";
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
// 整体氛围靠：屏幕级 frost layer（backdrop-filter blur 全屏）+ 封面底部 mask
// 平滑渐隐 → 露出 frost → 形成"封面溶进毛玻璃歌词区"的渐进模糊感。
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
const IMMERSIVE_LYRIC_ROW_H = "clamp(58px, 7.4vh, 78px)";
const IMMERSIVE_ACTIVE_FS = "clamp(22px, 3vw, 34px)";
const IMMERSIVE_DIM_FS = IMMERSIVE_ACTIVE_FS;
const IMMERSIVE_ROWS = 7;
const FLIP_DURATION_MS = 620;
const FLIP_EASE = "cubic-bezier(0.32, 0.72, 0, 1)";
// 关闭走另一组参数：用户离场时希望"先动起来 → 落到位"的爽快感，
// 跟开场的"慢慢settle"不一样。
//   - 时长更短（看起来更有"撤退感"，避免回拽感）
//   - 曲线起步更陡（cubic-bezier 第一控制点 y 提到 0.04），
//     再衔接长尾减速（第三、四控制点保持平滑），整体像 iOS modal dismiss
const CLOSE_DURATION_MS = 540;
const CLOSE_EASE = "cubic-bezier(0.6, 0.04, 0.22, 1)";


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

type PlayerAPI = ReturnType<typeof usePlayer>;

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
            // 顶角的 ☰ 库按钮已经隐含"去挑一首"的行为入口。
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

          {/* 极简底栏：跟 controlsRow 同款三列等分网格 → 每个 nav 图标在自己
              1/3 列里居中，跟上面 prev / play / next 的中心严格垂直对齐。
              marginTop 跟 controlsRow.marginTop 一致 = 进度条 → 控件 = 控件 → 图标。 */}
          <div
            ref={compactLyricRef}
            style={{
              marginTop: "clamp(22px, 3.4vh, 32px)",
              display: "grid",
              gridTemplateColumns: "1fr 1fr 1fr",
              placeItems: "center",
              paddingInline: 0,
              opacity: immersiveActive ? 0 : 1,
              transition: "opacity 200ms ease",
            }}
          >
            <button
              onClick={hasTrack ? onLyricClick : undefined}
              disabled={!hasTrack}
              aria-label="歌词"
              title="歌词"
              style={{
                ...navIcon,
                opacity: hasTrack ? 0.82 : 0.32,
                cursor: hasTrack ? "pointer" : "not-allowed",
              }}
            >
              <LyricsIcon />
            </button>
            <Link href="/distill" aria-label="我的歌单" title="我的歌单" style={navIcon}>
              <ListIcon />
            </Link>
            <Link href="/settings" aria-label="设置" title="设置" style={navIcon}>
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
// 设计核心：封面图与画面无色彩断层。
//   1. 背景层就是同一张封面 URL，重度模糊（blur 90px）+ 升饱和度，铺满整个屏幕。
//      整个画面拿到封面的色调氛围。
//   2. 前景清晰封面在顶部，底部用 mask-image 线性渐变溶进背景层 ——
//      因为是同一张图，模糊背景 / 清晰封面在边缘的颜色绝对连续，肉眼无法分辨断层。
//   3. 歌词层无独立背景，直接坐在背景层之上，色调自然继承封面色。
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

function ImmersiveLyrics({
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
  const coverRef = useRef<HTMLDivElement>(null);
  const lyricRef = useRef<HTMLDivElement>(null);
  const titleBarRef = useRef<HTMLDivElement>(null);

  // 采样封面边缘颜色：背景层、frost 用这俩颜色染色，封面渐隐时
  // 边缘溶到的颜色 == 背景同位置颜色 → 接缝处颜色 100% 连续，不再有色彩断层
  const coverUrlRaw = player.current?.cover ? cdn(player.current.cover) : null;
  const edgeColors = useCoverEdgeColors(coverUrlRaw);

  // 桌面 / 手机布局切换
  const isDesktop = useIsDesktop();

  // 接缝 / 顶部 / 右侧采样色 fallback
  const seamRgb = edgeColors.bottom ?? "8, 10, 18";
  const topRgb = edgeColors.top ?? "8, 10, 18";
  const rightRgb = edgeColors.right ?? seamRgb;

  // 文字颜色根据 bg 亮度自适应（取 cover 底部 / 右侧色算 luma）
  const luminanceProbe = isDesktop ? rightRgb : seamRgb;
  const tone = computeTone(luminanceProbe);
  const fgColor = tone === "dark" ? "rgba(0, 0, 0, 0.92)" : "rgba(255, 255, 255, 0.96)";
  const fgDimColor = tone === "dark" ? "rgba(0, 0, 0, 0.32)" : "rgba(255, 255, 255, 0.32)";
  // active 行里"未唱"字符的颜色：跟主色同源，仅压低 alpha 到中等亮度。
  // 不能直接用 fgDimColor —— 那个是给非 active 行用的过暗值，
  // 当 active 行里大部分字符还没唱到时（如刚切到下一句），会让整行看起来像
  // 灰乎乎的旁白。Apple Music 的处理：active 行未唱字符保持中亮度，
  // 已唱字符全亮，逐字 wipe 表现为 "中亮 → 满亮" 的渐变。
  const fgUnsungColor = tone === "dark" ? "rgba(0, 0, 0, 0.55)" : "rgba(255, 255, 255, 0.62)";

  // 一次性算出整套布局：cover/title/lyric 位置、mask 方向、bg gradient
  const layout = useMemo(
    () => computeLayout(isDesktop, topRgb, seamRgb, rightRgb, fgColor, fgDimColor),
    [isDesktop, topRgb, seamRgb, rightRgb, fgColor, fgDimColor],
  );

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
    const { backdrop, cover, lyric, titleBar } = ctx;

    const srcCover = sourceCoverRect();
    const srcLyric = sourceLyricRect();

    // 自然 immersive 目标尺寸 —— 跟下面 render 时不带 inline width/height 时
    // CSS 默认状态保持一致
    const target = computeImmersiveTargets(layout);

    // 没源 rect 就退化成纯淡入；移动端走 FLIP（120Hz 设备 hold 得住）
    if (!srcCover) {
      applyTargetImmediate(cover, target.cover);
      applyTargetImmediate(lyric, target.lyric);
      backdrop.style.transition = "none";
      backdrop.style.opacity = "0";
      void backdrop.offsetWidth;
      backdrop.style.transition = `opacity 360ms ${FLIP_EASE}`;
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
    cover.style.borderRadius = "12px";
    if (srcLyric) {
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
    backdrop.style.opacity = "0";
    if (titleBar) {
      titleBar.style.transition = "none";
      titleBar.style.opacity = "0";
      titleBar.style.transform = "translate3d(0, 8px, 0)";
    }
    void cover.offsetWidth;

    // 下一帧动到自然 immersive 目标
    requestAnimationFrame(() => {
      const t = transitionFor(["top", "left", "width", "height", "border-radius", "opacity"]);
      cover.style.transition = t;
      applyTargetCss(cover, target.cover);
      cover.style.borderRadius = "0px";

      lyric.style.transition = t;
      applyTargetCss(lyric, target.lyric);
      lyric.style.opacity = "1";

      backdrop.style.transition = `opacity ${FLIP_DURATION_MS}ms ${FLIP_EASE}`;
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
      const cover = coverRef.current;
      const lyric = lyricRef.current;
      if (!backdrop || !cover || !lyric) return null;
      return { backdrop, cover, lyric, titleBar: titleBarRef.current };
    }
  }, [phase, sourceCoverRect, sourceLyricRect, layout]);

  // 退场
  useLayoutEffect(() => {
    if (phase !== "closing") return;
    const backdrop = backdropRef.current;
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
    applyRectCss(cover, srcCover);
    cover.style.borderRadius = "12px";

    if (srcLyric) {
      // 歌词位置过渡比 cover 短一截 + 自身 opacity 在 ~60% 时淡掉，
      // 不让长行歌词拖到最后还压在画面上
      lyric.style.transition =
        `top ${Math.round(CLOSE_DURATION_MS * 0.85)}ms ${CLOSE_EASE}, ` +
        `left ${Math.round(CLOSE_DURATION_MS * 0.85)}ms ${CLOSE_EASE}, ` +
        `width ${Math.round(CLOSE_DURATION_MS * 0.85)}ms ${CLOSE_EASE}, ` +
        `height ${Math.round(CLOSE_DURATION_MS * 0.85)}ms ${CLOSE_EASE}, ` +
        `opacity ${Math.round(CLOSE_DURATION_MS * 0.55)}ms ${CLOSE_EASE}`;
      applyRectCss(lyric, srcLyric);
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

  const { current, isPlaying, positionSec, toggle, next, lyric } = player;
  const coverUrl = current?.cover ? cdn(current.cover) : null;

  return createPortal(
    <div
      ref={containerRef}
      style={{
        position: "fixed",
        inset: 0,
        zIndex: 9000,
        overflow: "hidden",
        // 容器本身保持透明 —— backdrop 淡入淡出时底层播放页能自然显出，
        // 不再出现"叠一层 #05060a → 关歌词页瞬间整屏黑一下"的问题。
        // 真正的兜底底色挪到 backdrop 那层（layout.bgGradient），跟 backdrop
        // 一起淡入淡出。
        background: "transparent",
      }}
    >
      {/* 背景三层叠合（从下到上）：
            1. layout.bgGradient —— 采样自封面顶/底/右沿的纯色渐变，提供主色调
            2. 重度模糊封面（blur 140 + saturate 1.6）—— 给画面带来色彩起伏
               高对比封面（深主体 + 浅背景）下，80px 远不够 —— 主体仍以暗块
               形式可见。提高到 140 + 拉饱和 + brightness 1.04 后，主体轮廓
               彻底化为色块，不再可辨认
            3. 再叠一层 0.42 的 bgGradient —— 把模糊封面里残留的明暗 pattern
               进一步柔化，整体趋向于纯色氛围，但保留一点色彩呼吸感
          叠合后的 bg 在前景封面 mask 渐隐区显出的颜色仍跟前景封面色调一致
          （都是同源），不会出现"卡进背景"的方块感。 */}
      <div
        ref={backdropRef}
        aria-hidden
        style={{
          position: "absolute",
          inset: 0,
          opacity: 0,
          overflow: "hidden",
          background: layout.bgGradient,
        }}
      >
        {coverUrl && (
          // eslint-disable-next-line @next/next/no-img-element
          <img
            src={coverUrl}
            alt=""
            style={{
              position: "absolute",
              top: "-25%",
              left: "-25%",
              width: "150%",
              height: "150%",
              objectFit: "cover",
              filter: "blur(140px) saturate(1.6) brightness(1.04)",
              opacity: 0.72,
              transform: "translateZ(0)",
              willChange: "transform",
            }}
          />
        )}
        {/* 顶层柔化：重新涂一遍采样色 gradient，把模糊封面的残留 pattern 压平 */}
        <div
          aria-hidden
          style={{
            position: "absolute",
            inset: 0,
            background: layout.bgGradient,
            opacity: 0.42,
            pointerEvents: "none",
          }}
        />
      </div>

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
        onClick={onClose}
        style={{
          position: "absolute",
          ...layout.cover,
          overflow: "hidden",
          cursor: "pointer",
          zIndex: 2,
          WebkitMaskImage: layout.coverMask,
          WebkitMaskComposite:
            layout.coverMaskComposite === "intersect" ? "source-in" : "source-over",
          maskImage: layout.coverMask,
          maskComposite: layout.coverMaskComposite,
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
              transform: isPlaying ? "scale(1.012)" : "scale(1)",
              transition: "transform 4000ms ease-in-out",
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
          alignItems: "center",
          gap: layout.isDesktop ? 16 : 14,
          opacity: 0,
          zIndex: 4,
        }}
      >
        <div style={{ minWidth: 0, flex: "1 1 auto" }}>
          <div
            style={{
              ...(layout.isDesktop ? immersiveTitleLarge : immersiveTitle),
              color: layout.fgColor,
            }}
          >
            {current?.title ?? "—"}
          </div>
          <div
            style={{
              ...(layout.isDesktop ? immersiveSubtitleLarge : immersiveSubtitle),
              color: layout.fgDimColor,
            }}
          >
            {current?.artist ?? ""}
          </div>
        </div>
        <div style={{ display: "flex", gap: layout.isDesktop ? 10 : 8, flexShrink: 0 }}>
          <ImmersiveIconBtn
            onClick={(e) => {
              e.stopPropagation();
              toggle();
            }}
            aria-label={isPlaying ? "暂停" : "播放"}
            color={layout.fgColor}
            size={layout.isDesktop ? "large" : "small"}
          >
            {isPlaying ? <PauseGlyph /> : <PlayGlyph />}
          </ImmersiveIconBtn>
          <ImmersiveIconBtn
            onClick={(e) => {
              e.stopPropagation();
              next();
            }}
            aria-label="下一首"
            color={layout.fgColor}
            size={layout.isDesktop ? "large" : "small"}
          >
            <SkipForward />
          </ImmersiveIconBtn>
        </div>
      </div>

      {/* 歌词层 —— 位置由 layout 决定（手机贴封面下沿，桌面在 cover 右侧）。
          透明背景，bg 由 backdrop 那层填，文字颜色 luma 自适应。 */}
      <div
        ref={lyricRef}
        style={{
          position: "absolute",
          ...layout.lyric,
          overflow: "hidden",
          zIndex: 5,
        }}
      >
        <LyricColumn
          lines={lyric?.lines ?? []}
          yrcLines={lyric?.yrcLines ?? []}
          positionSec={positionSec}
          meta={lyric}
          fgColor={layout.fgColor}
          fgDimColor={layout.fgDimColor}
          fgUnsungColor={fgUnsungColor}
        />
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

function computeLayout(
  isDesktop: boolean,
  topRgb: string,
  seamRgb: string,
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
      coverMask: "linear-gradient(to bottom, #000 55%, transparent 100%)",
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
  // ---- 桌面 side-by-side：cover 左、lyric 右 ----
  // 把 (cover + gap + title-block) 作为一组整体在视口内垂直居中，
  // title / 控件不再叠在封面上，而是落在封面正下方。
  const W = "min(46vw, 70vh, 540px)";
  const titleBlockH = "clamp(92px, 12vh, 122px)";
  // gap 调大 —— 把 title 块整体往下推，原先 "下面留白偏多" 是因为 cover 居中
  // 后下方空间没被用掉；加大 gap 让组合块底部更贴近视口下部，视觉重心更稳
  const gapBelow = "clamp(36px, 5.4vh, 60px)";
  const totalH = `calc(${W} + ${gapBelow} + ${titleBlockH})`;
  const top = `calc((100vh - ${totalH}) / 2)`;
  const left = "clamp(24px, 6vw, 80px)";
  const lyricLeft = `calc(${left} + ${W} + clamp(40px, 6vw, 100px))`;
  const lyricRight = "clamp(24px, 6vw, 80px)";
  // title 块跟封面同宽：左边沿对齐封面左、右边沿对齐封面右。
  //   - title 文本（左 flex-grow）天然贴封面左边
  //   - 按钮（右 flex-shrink-0）天然落在封面右边
  // 之前试过收窄到视觉中心，结果按钮跟着挤到中段，跟封面右边差太多 ——
  // 用户感觉"按钮太靠左"。回到 W 同宽，title 文本不会塞满，自然有留白，
  // 但是按钮位置稳稳压在封面右边沿，整体节奏才稳。
  return {
    cover: { top, left, width: W, height: W },
    title: {
      top: `calc(${top} + ${W} + ${gapBelow})`,
      left,
      width: W,
    },
    lyric: {
      top: "8vh",
      left: lyricLeft,
      width: `calc(100vw - ${lyricLeft} - ${lyricRight})`,
      height: "84vh",
    },
    // 桌面四向 mask（mask-composite: intersect）：
    //   - 横向：左 4% / 右 35% 渐隐 → 右侧主溶进歌词区，左侧柔化进模糊 backdrop
    //   - 纵向：上下各 5% 渐隐 → 上下边都溶进模糊 backdrop，没有矩形硬边
    // 配合"模糊封面同源 backdrop"，渐隐区显出的颜色 == 当前像素的模糊版，
    // 视觉上完全没有"封面卡进背景"的方块感。
    coverMask:
      "linear-gradient(to right, transparent 0%, #000 4%, #000 65%, transparent 100%), " +
      "linear-gradient(to bottom, transparent 0%, #000 5%, #000 95%, transparent 100%)",
    coverMaskComposite: "intersect",
    // 桌面 bg：模糊封面拉不到时的兜底纯色 gradient（采样色驱动）
    bgGradient:
      `linear-gradient(90deg, ` +
      `rgb(${topRgb}) 0px, ` +
      `rgb(${seamRgb}) ${left}, ` +
      `rgb(${rightRgb}) calc(${left} + ${W}), ` +
      `rgb(${rightRgb}) 100%)`,
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
    visibleRows: LYRIC_ROWS_COMPACT,
    rowH: LYRIC_ROW_H,
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

function LyricColumn({
  lines,
  yrcLines,
  positionSec,
  meta,
  fgColor,
  fgDimColor,
  fgUnsungColor,
}: {
  lines: LrcLine[];
  yrcLines: YrcLine[];
  positionSec: number;
  meta:
    | { lines: LrcLine[]; yrcLines: YrcLine[]; instrumental: boolean; uncollected: boolean }
    | null;
  fgColor: string;
  fgDimColor: string;
  fgUnsungColor: string;
}) {
  const view = useLyricView({
    lines,
    yrcLines,
    positionSec,
    visibleRows: IMMERSIVE_ROWS,
    rowH: IMMERSIVE_LYRIC_ROW_H,
    activeFs: IMMERSIVE_ACTIVE_FS,
    dimFs: IMMERSIVE_DIM_FS,
    immersive: true,
    fgColor,
    fgDimColor,
    fgUnsungColor,
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
      outerStyle={{
        ...immersiveLyricFrame,
        // mask 挂在 outer：歌词列怎么滚动，渐隐区都钉在视口顶 / 底，
        // 边界附近的歌词永远是淡出状态，不再有"看到容器硬切"的感觉。
        // 渐隐区放大到 18% 和 82%（约 1-2 行），跟 Apple Music 一致。
        WebkitMaskImage:
          "linear-gradient(180deg, transparent 0%, #000 18%, #000 82%, transparent 100%)",
        maskImage:
          "linear-gradient(180deg, transparent 0%, #000 18%, #000 82%, transparent 100%)",
      }}
      innerExtraStyle={{
        // 横向 padding 跟移动端 titlePadX 同步加大 1/3：28-48；
        // 桌面 6.7vw 的上限也是 48，比原 36 多一档透气
        padding: "clamp(20px, 4vh, 36px) clamp(28px, 6.7vw, 48px)",
      }}
      transitionMs={560}
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

function MeasuredLyricColumn({
  activeIdx,
  outerStyle,
  innerExtraStyle,
  transitionMs,
  children,
}: {
  activeIdx: number;
  outerStyle: React.CSSProperties;
  innerExtraStyle: React.CSSProperties;
  transitionMs: number;
  children: React.ReactNode;
}) {
  const outerRef = useRef<HTMLDivElement>(null);
  const innerRef = useRef<HTMLDivElement>(null);
  const [translateY, setTranslateY] = useState(0);

  useLayoutEffect(() => {
    const outer = outerRef.current;
    const inner = innerRef.current;
    if (!outer || !inner) return;
    const active = inner.querySelector<HTMLElement>('[data-active="1"]');
    if (!active) return;
    const containerH = outer.clientHeight;
    const top = active.offsetTop;
    const h = active.offsetHeight;
    setTranslateY(containerH / 2 - top - h / 2);
  }, [activeIdx, children]);

  return (
    <div ref={outerRef} style={outerStyle}>
      <div
        ref={innerRef}
        style={{
          position: "absolute",
          left: 0,
          right: 0,
          top: 0,
          transform: `translate3d(0, ${translateY}px, 0)`,
          transition: `transform ${transitionMs}ms cubic-bezier(0.22, 1, 0.36, 1)`,
          willChange: "transform",
          ...innerExtraStyle,
        }}
      >
        {children}
      </div>
    </div>
  );
}

// ============== shared 歌词视图逻辑 ==============

type LyricViewParams = {
  lines: LrcLine[];
  yrcLines: YrcLine[];
  positionSec: number;
  visibleRows: number;
  rowH: string;
  activeFs: string;
  dimFs: string;
  immersive?: boolean;
  // 文字颜色（active）/ 弱化色（inactive）。
  // immersive 模式根据 bg 亮度由调用方算好传进来；compact 模式用默认（白 / 灰）
  fgColor?: string;
  fgDimColor?: string;
  // active 行内 "未唱" 字符颜色（介于 fg 和 fgDim 之间的中亮度）。
  // 不传则 fallback 到 fgDimColor。
  fgUnsungColor?: string;
};

function useLyricView(p: LyricViewParams): {
  empty: boolean;
  rows: React.ReactNode;
  translateExpr: string;
  activeIdx: number;
} {
  const useYrc = p.yrcLines.length > 0;
  const lrcTimes = useMemo(
    () => (useYrc ? p.yrcLines.map((y) => y.time) : p.lines.map((l) => l.time)),
    [useYrc, p.lines, p.yrcLines],
  );

  // 二分定位当前行
  const idx = useMemo(() => {
    if (lrcTimes.length === 0) return -1;
    let lo = 0,
      hi = lrcTimes.length - 1,
      ans = -1;
    while (lo <= hi) {
      const mid = (lo + hi) >> 1;
      if (lrcTimes[mid] <= p.positionSec) {
        ans = mid;
        lo = mid + 1;
      } else {
        hi = mid - 1;
      }
    }
    return ans;
  }, [lrcTimes, p.positionSec]);

  if (lrcTimes.length === 0) {
    return { empty: true, rows: null, translateExpr: "0px", activeIdx: -1 };
  }

  const safeIdx = Math.max(idx, 0);
  const total = useYrc ? p.yrcLines.length : p.lines.length;

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
  const rowEls: React.ReactNode[] = [];
  for (let i = 0; i < total; i++) {
    const isActive = i === safeIdx;
    if (useYrc) {
      const line = p.yrcLines[i]!;
      rowEls.push(
        <YrcRow
          key={`y-${i}`}
          line={line}
          isActive={isActive}
          positionSec={isActive ? p.positionSec : undefined}
          rowH={p.rowH}
          activeFs={p.activeFs}
          dimFs={p.dimFs}
          immersive={p.immersive ?? false}
          fgColor={fg}
          fgDimColor={fgDim}
          fgUnsungColor={fgUnsung}
        />,
      );
    } else {
      const ln = p.lines[i]!;
      let lineProgress = 0;
      if (isActive) {
        const start = ln.time;
        const end = i + 1 < p.lines.length ? p.lines[i + 1]!.time : start + 5;
        const dur = Math.max(0.4, end - start);
        lineProgress = Math.max(0, Math.min(1, (p.positionSec - start) / dur));
      } else if (i < safeIdx) {
        lineProgress = 1;
      }
      rowEls.push(
        <LrcRow
          key={`l-${i}`}
          text={ln.text}
          isActive={isActive}
          progress={lineProgress}
          rowH={p.rowH}
          activeFs={p.activeFs}
          dimFs={p.dimFs}
          immersive={p.immersive ?? false}
          fgColor={fg}
          fgDimColor={fgDim}
          fgUnsungColor={fgUnsung}
        />,
      );
    }
  }

  // column 偏移：让 safeIdx 行落在 centerRow 槽位。idx 增大时 offset 减小
  // → column 向上平移。CSS 过渡平滑滑动。
  const centerRow = Math.floor(p.visibleRows / 2);
  const offset = centerRow - safeIdx;
  const translateExpr = `calc(${offset} * ${p.rowH})`;

  return {
    empty: false,
    rows: rowEls,
    translateExpr,
    activeIdx: safeIdx,
  };
}

// ============== 行组件（React.memo 包，非当前行不每帧重渲） ==============

const SUNG_WHITE = "rgba(255,255,255,0.96)";
const UNSUNG_GRAY = "rgba(168,174,194,0.9)";
const UNSUNG_GRAY_IMMERSIVE = "rgba(255,255,255,0.32)";

type RowCommon = {
  isActive: boolean;
  rowH: string;
  activeFs: string;
  dimFs: string;
  immersive: boolean;
  fgColor: string;
  fgDimColor: string;
  fgUnsungColor: string;
};

const YrcRow = React.memo(function YrcRow({
  line,
  isActive,
  positionSec,
  rowH,
  activeFs,
  dimFs,
  immersive,
  fgColor,
  fgDimColor,
  fgUnsungColor,
}: RowCommon & { line: YrcLine; positionSec?: number }) {
  // immersive 下非 active 行用 fgColor + 容器 opacity 压暗（统一颜色 + 透明度），
  // compact 仍用 fgDimColor（旧行为，保留视觉熟悉感）。
  const inactiveColor = immersive ? fgColor : fgDimColor;
  return (
    <div
      data-active={isActive ? "1" : "0"}
      style={lineFrame(isActive, rowH, activeFs, dimFs, immersive)}
    >
      <div style={lineInner()}>
        {isActive ? (
          <YrcActiveLine
            line={line}
            positionSec={positionSec ?? 0}
            fgColor={fgColor}
            fgUnsungColor={fgUnsungColor}
          />
        ) : (
          <span
            style={{
              color: inactiveColor,
              whiteSpace: "break-spaces",
            }}
          >
            {line.text}
          </span>
        )}
      </div>
    </div>
  );
});

const LrcRow = React.memo(function LrcRow({
  text,
  isActive,
  progress,
  rowH,
  activeFs,
  dimFs,
  immersive,
  fgColor,
  fgDimColor,
  fgUnsungColor,
}: RowCommon & { text: string; progress: number }) {
  const inactiveColor = immersive ? fgColor : fgDimColor;
  return (
    <div
      data-active={isActive ? "1" : "0"}
      style={lineFrame(isActive, rowH, activeFs, dimFs, immersive)}
    >
      <div style={lineInner()}>
        {isActive ? (
          <span
            style={{
              backgroundImage: `linear-gradient(90deg, ${fgColor} 0%, ${fgColor} ${progress * 100}%, ${fgUnsungColor} ${progress * 100 + 1}%, ${fgUnsungColor} 100%)`,
              WebkitBackgroundClip: "text",
              backgroundClip: "text",
              WebkitTextFillColor: "transparent",
            }}
          >
            {text}
          </span>
        ) : (
          <span
            style={{
              color: inactiveColor,
            }}
          >
            {text}
          </span>
        )}
      </div>
    </div>
  );
});

function YrcActiveLine({
  line,
  positionSec,
  fgColor,
  fgUnsungColor,
}: {
  line: YrcLine;
  positionSec: number;
  fgColor: string;
  // 未唱字符颜色 —— 介于 fg 和 fgDim 之间的"中亮度"。Apple Music 思路：
  // 即便整行都还没唱到（刚切到下一句），这一行依然明显比其它非 active 行亮，
  // 因为它的字符颜色是中亮度而非 fgDim。
  fgUnsungColor: string;
}) {
  const chars = line.chars;
  if (chars.length === 0) {
    return (
      <span style={{ color: fgColor, whiteSpace: "break-spaces" }}>
        {line.text}
      </span>
    );
  }

  const currentIdx = chars.findIndex((c) => positionSec < c.startSec + c.durSec);
  if (currentIdx === -1) {
    return (
      <span style={{ color: fgColor, whiteSpace: "break-spaces" }}>
        {line.text}
      </span>
    );
  }

  const current = chars[currentIdx]!;
  let before = "";
  let after = "";
  for (let i = 0; i < currentIdx; i++) before += chars[i]!.text;
  for (let i = currentIdx + 1; i < chars.length; i++) after += chars[i]!.text;

  let progress = 0;
  if (positionSec >= current.startSec + current.durSec) progress = 1;
  else if (positionSec > current.startSec)
    progress = (positionSec - current.startSec) / Math.max(0.001, current.durSec);

  const beingSung = progress > 0 && progress < 1;
  const sung = progress >= 1;

  return (
    <>
      {before && (
        <span style={{ color: fgColor, whiteSpace: "break-spaces" }}>
          {before}
        </span>
      )}
      <span
        style={{
          position: "relative",
          display: "inline-block",
          whiteSpace: "break-spaces",
          color: sung ? fgColor : fgUnsungColor,
          backgroundImage: beingSung
            ? `linear-gradient(90deg, ${fgColor} 0%, ${fgColor} ${progress * 100}%, ${fgUnsungColor} ${progress * 100 + 1}%, ${fgUnsungColor} 100%)`
            : "none",
          WebkitBackgroundClip: beingSung ? "text" : "border-box",
          backgroundClip: beingSung ? "text" : "border-box",
          WebkitTextFillColor: beingSung ? "transparent" : "currentColor",
        }}
      >
        {current.text}
      </span>
      {after && (
        <span style={{ color: fgUnsungColor, whiteSpace: "break-spaces" }}>
          {after}
        </span>
      )}
    </>
  );
}

function lineFrame(
  isActive: boolean,
  rowH: string,
  activeFs: string,
  dimFs: string,
  immersive: boolean,
): React.CSSProperties {
  // transition 写在"目标态"上，进 / 出 active 用不同时间窗。
  // immersive 不再变 fontSize（active/inactive 同号），但仍保留 transition
  // 兼容 compact 模式以及未来重新启用尺寸切换的可能。
  const transition = isActive
    ? "font-size 280ms cubic-bezier(0.22, 1, 0.36, 1) 80ms, opacity 360ms cubic-bezier(0.22, 1, 0.36, 1) 40ms, color 320ms cubic-bezier(0.22, 1, 0.36, 1)"
    : "font-size 200ms cubic-bezier(0.4, 0, 0.6, 1), opacity 280ms cubic-bezier(0.4, 0, 0.6, 1), color 280ms cubic-bezier(0.4, 0, 0.6, 1)";

  return {
    // 用 minHeight 而非 height —— 长歌词换行后行高自然增长，不会跟下一行重叠。
    // 容器 translate 由 LyricColumn / LyricStrip 通过 ref 测量 active 行的
    // offsetTop 决定，所以行高变化不影响居中。
    minHeight: rowH,
    boxSizing: "border-box",
    display: "flex",
    alignItems: "center",
    justifyContent: immersive ? "flex-start" : "center",
    fontSize: isActive ? activeFs : dimFs,
    // immersive: 所有行同字重（Heavy）—— 跟 Apple Music 一致，激活态全靠
    //   "色彩对比 + 逐字 wipe" 凸显，不靠字重切换。
    // compact: 仍保持 active 加粗 / inactive 细体，节省竖向空间。
    fontWeight: immersive ? 700 : isActive ? 600 : 400,
    // 大字号 + Heavy 配紧字距，避免字符间空气感稀释主体
    letterSpacing: immersive ? "-0.012em" : 0,
    lineHeight: immersive ? 1.32 : undefined,
    transformOrigin: "left center",
    filter: "none",
    // 非 active 行：immersive 显著压暗（0.32）形成强对比；compact 保留较亮
    // （0.42）让 3 行带子整体可读。
    opacity: isActive ? 1 : immersive ? 0.32 : 0.42,
    overflow: "visible",
    padding: immersive ? "10px 4px" : "2px 12px",
    textAlign: immersive ? "left" : "center",
    transition,
  };
}

function lineInner(): React.CSSProperties {
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

// ============== SVG glyph（v5 原样） ==============

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
  // 下面 nav 行用完全相同的网格 → 两行的"按钮中心"严格垂直对齐，
  // 不受按钮宽度差影响（play 比 prev/next 大也对齐得上）。
  display: "grid",
  gridTemplateColumns: "1fr 1fr 1fr",
  placeItems: "center",
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
  letterSpacing: "-0.005em",
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
  letterSpacing: "-0.01em",
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
  return (
    <svg
      width="20"
      height="20"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2.2"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden
    >
      <rect x="3" y="5" width="18" height="14" rx="2.5" ry="2.5" />
      <path d="M7 10.5h6.5M7 14.5h9" />
    </svg>
  );
}

function ListIcon() {
  return (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <path d="M3.5 6h11M3.5 12h11M3.5 18h7" />
      <path d="M19.5 4v9" />
      <ellipse cx="17.7" cy="14" rx="2.2" ry="1.6" fill="currentColor" stroke="none" />
    </svg>
  );
}

function NavGearIcon() {
  return (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <circle cx="12" cy="12" r="3" />
      <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09a1.65 1.65 0 0 0-1-1.51 1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09a1.65 1.65 0 0 0 1.51-1 1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33h0a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51h0a1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82v0a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z" />
    </svg>
  );
}

