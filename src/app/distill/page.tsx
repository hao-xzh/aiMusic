"use client";

/**
 * 我的歌单 —— 轮盘 hero + 融合列表（去 nav 化重写版）。
 *
 * 视图：
 *   - "library"（默认）：横向 cover-flow 轮盘 + 焦点歌单的曲目列表
 *     · 整页背景 = 焦点封面重模糊（PlaylistFusionBg），无 hero/list 边界
 *     · 桌面 (≥720px) 自动切左右布局
 *   - "select"：进入"挑歌单蒸馏画像"流程，沿用旧版 PlaylistGrid + DistillBottomBar
 *
 * 数据流和老版本一致：cache-first → 后台 SWR → upsert。
 */

import { usePlayer } from "@/lib/player-state";
import { cdn } from "@/lib/cdn";
import {
  cache,
  netease,
  type CachedPlaylist,
  type PlaylistInfo,
  type TrackInfo,
  type UserProfile,
} from "@/lib/tauri";
import { distillTaste, type DistillProgress } from "@/lib/taste-profile";
import { loadLibrary, clearLibraryMemo } from "@/lib/library";
import { startBackgroundAnalysis } from "@/lib/analysis-progress";
import { PlaylistPager } from "@/components/PlaylistPager";
import { PlaylistFusionBg } from "@/components/PlaylistFusionBg";
import { useIsDesktop } from "@/lib/use-is-desktop";
import { usePlatform } from "@/lib/use-platform";
import { useCoverEdgeColors, computeTone, pickFg, pickFgDim } from "@/lib/cover-color";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useMemo, useRef, useState } from "react";

type Load<T> =
  | { kind: "loading" }
  | { kind: "loaded"; data: T }
  | { kind: "error"; message: string }
  | { kind: "unauth" };

/**
 * 把缓存行投射成前端侧 `PlaylistInfo`。updateTime/coverImgUrl 直接透传，
 * userId 用调用方传进来的 uid —— 缓存表里按 uid 索引，没必要单独存列。
 */
function cachedToPlaylistInfo(row: CachedPlaylist, uid: number): PlaylistInfo {
  return {
    id: row.id,
    name: row.name,
    trackCount: row.trackCount,
    coverImgUrl: row.coverImgUrl,
    userId: uid,
    updateTime: row.updateTime,
  };
}

export default function DistillPage() {
  const router = useRouter();
  const isDesktop = useIsDesktop();
  const [me, setMe] = useState<UserProfile | null>(null);
  const [list, setList] = useState<Load<PlaylistInfo[]>>({ kind: "loading" });
  const [refreshing, setRefreshing] = useState(false);

  // 视图模式：默认 "library"（轮盘 + 列表），"select" 切到旧版多选蒸馏流
  const [mode, setMode] = useState<"library" | "select">("library");

  // 多选状态
  const [selectedIds, setSelectedIds] = useState<Set<number>>(new Set());
  const [distillState, setDistillState] = useState<
    | { kind: "idle" }
    | { kind: "running"; progress: DistillProgress }
    | { kind: "error"; message: string }
  >({ kind: "idle" });

  const toggleSelect = (id: number) => {
    setSelectedIds((s) => {
      const next = new Set(s);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const exitSelectMode = () => {
    setMode("library");
    setSelectedIds(new Set());
    setDistillState({ kind: "idle" });
  };

  const runDistill = async () => {
    if (!me || selectedIds.size === 0) return;
    setDistillState({ kind: "running", progress: { phase: "loading-tracks", done: 0, total: selectedIds.size } });
    try {
      await distillTaste(me.userId, Array.from(selectedIds), {
        onProgress: (p) => setDistillState({ kind: "running", progress: p }),
      });
      clearLibraryMemo();
      void (async () => {
        try {
          const lib = await loadLibrary();
          if (lib.length === 0) return;
          console.debug(`[claudio] 启动后台音频分析：${lib.length} 首`);
          await startBackgroundAnalysis(lib);
          console.debug(`[claudio] 后台分析完成`);
        } catch (e) {
          console.debug("[claudio] 后台分析挂了，跳过", e);
        }
      })();
      router.push("/taste");
    } catch (e) {
      setDistillState({
        kind: "error",
        message: e instanceof Error ? e.message : String(e),
      });
    }
  };

  const selectedTrackEstimate = useMemo(() => {
    if (list.kind !== "loaded") return 0;
    return list.data
      .filter((p) => selectedIds.has(p.id))
      .reduce((s, p) => s + p.trackCount, 0);
  }, [list, selectedIds]);

  useEffect(() => {
    let alive = true;
    (async () => {
      try {
        const profile = await netease.account();
        if (!alive) return;
        if (!profile) {
          setList({ kind: "unauth" });
          return;
        }
        setMe(profile);

        const cached = await cache.getPlaylists(profile.userId);
        if (!alive) return;
        if (cached.length > 0) {
          setList({
            kind: "loaded",
            data: cached.map((c) => cachedToPlaylistInfo(c, profile.userId)),
          });
        }

        setRefreshing(true);
        try {
          const fresh = await netease.userPlaylists(profile.userId, 1000);
          if (!alive) return;

          const byId = new Map(cached.map((c) => [c.id, c.updateTime ?? null]));
          const changed =
            fresh.length !== cached.length ||
            fresh.some((p) => {
              if (!byId.has(p.id)) return true;
              const old = byId.get(p.id);
              return (p.updateTime ?? null) !== old;
            });

          if (changed) {
            await cache.savePlaylists(profile.userId, fresh);
            if (!alive) return;
            setList({ kind: "loaded", data: fresh });
          } else if (cached.length === 0) {
            await cache.savePlaylists(profile.userId, fresh);
            if (!alive) return;
            setList({ kind: "loaded", data: fresh });
          }
        } catch (e) {
          if (cached.length === 0) {
            setList({
              kind: "error",
              message: e instanceof Error ? e.message : String(e),
            });
          } else {
            console.warn("[claudio] 后台刷新歌单失败，用缓存继续", e);
          }
        } finally {
          if (alive) setRefreshing(false);
        }
      } catch (e) {
        if (alive)
          setList({
            kind: "error",
            message: e instanceof Error ? e.message : String(e),
          });
      }
    })();
    return () => {
      alive = false;
    };
  }, []);

  // ---- 焦点歌单 + 详情懒加载 ----
  const playlists = list.kind === "loaded" ? list.data : [];
  const [focusIdx, setFocusIdx] = useState(0);
  const focused = playlists[focusIdx] ?? null;
  const focusedCover = focused?.coverImgUrl ?? null;

  // 缓存每个歌单的 tracks，避免 swipe 来回时反复请求
  const tracksCacheRef = useRef<Map<number, TrackInfo[]>>(new Map());
  const [tracksTick, setTracksTick] = useState(0); // 强刷渲染
  const [tracksLoading, setTracksLoading] = useState(false);
  const [tracksError, setTracksError] = useState<string | null>(null);

  useEffect(() => {
    if (!focused || !me) return;
    const id = focused.id;
    if (tracksCacheRef.current.has(id)) return;

    let alive = true;
    setTracksLoading(true);
    setTracksError(null);

    (async () => {
      try {
        // cache-first
        const cached = await cache.getPlaylistDetail(id);
        if (!alive) return;
        const looksCorrupt =
          !!cached &&
          cached.tracks.length > 0 &&
          cached.tracks.every(
            (t) => t.durationMs === 0 && (!t.artists || t.artists.length === 0),
          );
        const hasCachedTracks = !!cached && cached.tracks.length > 0 && !looksCorrupt;

        if (hasCachedTracks) {
          tracksCacheRef.current.set(id, cached.tracks);
          setTracksTick((t) => t + 1);
        }

        // 后台 SWR
        try {
          const fresh = await netease.playlistDetail(id);
          if (!alive) return;
          const oldUT = cached?.updateTime ?? null;
          const newUT = fresh.updateTime ?? null;
          const shouldUpdate = !hasCachedTracks || looksCorrupt || oldUT !== newUT;
          if (shouldUpdate) {
            await cache.savePlaylistDetail(me.userId, fresh);
            if (!alive) return;
            tracksCacheRef.current.set(id, fresh.tracks);
            setTracksTick((t) => t + 1);
          }
        } catch (e) {
          if (!hasCachedTracks) {
            setTracksError(e instanceof Error ? e.message : String(e));
          } else {
            console.warn("[claudio] 歌单详情后台刷新失败，用缓存继续", e);
          }
        }
      } catch (e) {
        if (alive) setTracksError(e instanceof Error ? e.message : String(e));
      } finally {
        if (alive) setTracksLoading(false);
      }
    })();
    return () => {
      alive = false;
    };
  }, [focused?.id, me?.userId]);

  const focusedTracks = focused
    ? tracksCacheRef.current.get(focused.id) ?? []
    : [];
  // tracksTick 引用：在 cache 写入后让闭包能看到新的引用
  void tracksTick;

  // ---- 渲染 ----
  if (list.kind === "loading") {
    return (
      <PageShell>
        <FloatingTopBar onBack={() => router.push("/")} />
        <Placeholder text="正在拉取你的歌单…" />
      </PageShell>
    );
  }
  if (list.kind === "unauth") {
    return (
      <PageShell>
        <FloatingTopBar onBack={() => router.push("/")} />
        <UnauthState />
      </PageShell>
    );
  }
  if (list.kind === "error") {
    return (
      <PageShell>
        <FloatingTopBar onBack={() => router.push("/")} />
        <Placeholder text={`出错了：${list.message}`} err />
      </PageShell>
    );
  }

  if (mode === "select") {
    return (
      <PageShell>
        <PlaylistFusionBg src={focusedCover} />
        <FloatingTopBar
          onBack={exitSelectMode}
          backLabel="返回"
          right={
            <button onClick={exitSelectMode} style={ghostPillBtn}>
              取消
            </button>
          }
        />
        {/* PageShell 是 flex column + overflow hidden（兜歌单首页的"绝对定位
            布局"），select 模式下歌单网格内容通常超过一屏，必须把这一层做成
            可滚动 —— flex: 1 + minHeight: 0 拿到剩余高度，overflowY: auto 让
            它内部滚（不让 main 滚，避免顶部 floating bar 也跟着移）。 */}
        <div
          style={{
            flex: "1 1 auto",
            minHeight: 0,
            overflowY: "auto",
            WebkitOverflowScrolling: "touch",
          }}
        >
          <div style={{ padding: "76px 18px 120px", maxWidth: 960, margin: "0 auto" }}>
            <div style={{ color: "#e9efff", fontSize: 18, fontWeight: 700, textAlign: "center", marginBottom: 6 }}>
              挑选要蒸馏的歌单
            </div>
            <div style={{ color: "#8a93a8", fontSize: 12, textAlign: "center", marginBottom: 20 }}>
              选几张能代表你的，AI 会读完写一份音乐画像
            </div>
            <PlaylistGrid
              list={list.data}
              selectMode
              selectedIds={selectedIds}
              onPick={() => {}}
              onToggleSelect={toggleSelect}
            />
            <DistillBottomBar
              count={selectedIds.size}
              estimateTracks={selectedTrackEstimate}
              state={distillState}
              onRun={runDistill}
              onCancel={exitSelectMode}
            />
          </div>
        </div>
      </PageShell>
    );
  }

  return (
    <DistillLibraryPage
      isDesktop={isDesktop}
      playlists={playlists}
      focusIdx={focusIdx}
      onFocusChange={setFocusIdx}
      focused={focused}
      focusedCover={focusedCover}
      focusedTracks={focusedTracks}
      tracksLoading={tracksLoading}
      tracksError={tracksError}
      refreshing={refreshing}
      onBack={() => router.push("/")}
      onEnterSelect={() => setMode("select")}
    />
  );
}

// 单独抽出 library 视图主壳，方便用 hook（useCoverEdgeColors 等）
function DistillLibraryPage({
  isDesktop,
  playlists,
  focusIdx,
  onFocusChange,
  focused,
  focusedCover,
  focusedTracks,
  tracksLoading,
  tracksError,
  refreshing,
  onBack,
  onEnterSelect,
}: {
  isDesktop: boolean;
  playlists: PlaylistInfo[];
  focusIdx: number;
  onFocusChange: (i: number) => void;
  focused: PlaylistInfo | null;
  focusedCover: string | null;
  focusedTracks: TrackInfo[];
  tracksLoading: boolean;
  tracksError: string | null;
  refreshing: boolean;
  onBack: () => void;
  onEnterSelect: () => void;
}) {
  // === 跟歌词页同源的色彩管线 ===
  const coverUrl = focusedCover ? cdn(focusedCover) : null;
  const edge = useCoverEdgeColors(coverUrl);
  const seamRgb = edge.bottom ?? "8, 10, 18";
  const rightRgb = edge.right ?? seamRgb;
  const tone = computeTone(isDesktop ? rightRgb : seamRgb);
  const fg = pickFg(tone);
  const fgDim = pickFgDim(tone);

  const player = usePlayer();
  const onPlayAll = () => {
    if (focusedTracks.length === 0) return;
    const first = focusedTracks[0]!;
    void player.playNetease(first, focusedTracks);
  };

  return (
    <PageShell>
      <PlaylistFusionBg src={focusedCover} />
      <FloatingTopBar
        onBack={onBack}
        fg={fg}
        right={
          <div style={{ display: "flex", gap: 8 }}>
            <Link href="/taste" style={chipStyle(fg)} aria-label="我的画像" title="画像">
              <ProfileIcon />
            </Link>
            <button
              onClick={onEnterSelect}
              style={chipStyle(fg)}
              aria-label="蒸馏画像"
              title="蒸馏画像"
            >
              <SparkIcon />
            </button>
            <Link href="/settings" style={chipStyle(fg)} aria-label="设置" title="设置">
              <GearIcon />
            </Link>
          </div>
        }
      />
      {refreshing && (
        <div
          style={{
            position: "fixed",
            top: "max(env(safe-area-inset-top), 12px)",
            left: 0,
            right: 0,
            textAlign: "center",
            color: fg,
            fontSize: 10,
            opacity: 0.45,
            letterSpacing: 2,
            zIndex: 5,
            pointerEvents: "none",
          }}
        >
          SYNCING
        </div>
      )}

      {playlists.length === 0 ? (
        <div style={{ paddingTop: 120 }}>
          <Placeholder text="你还没有歌单" />
        </div>
      ) : (
        <ImmersiveLayout
          playlists={playlists}
          focusIdx={focusIdx}
          onFocusChange={onFocusChange}
          focused={focused}
          tracks={focusedTracks}
          loading={tracksLoading}
          error={tracksError}
          fg={fg}
          fgDim={fgDim}
          isDesktop={isDesktop}
          canPlay={focusedTracks.length > 0}
          onPlayAll={onPlayAll}
        />
      )}
    </PageShell>
  );
}

// ---------- 新版视图组件 ----------

function PageShell({ children }: { children: React.ReactNode }) {
  return (
    <div
      style={{
        position: "relative",
        flex: "1 1 auto",
        minHeight: 0,
        display: "flex",
        flexDirection: "column",
        // 整页可滚动（mobile 下 carousel + list 总高超过一屏时让外层滚）
        // 但桌面 side-by-side 时各自滚，外层不滚
        overflow: "hidden",
      }}
    >
      {children}
    </div>
  );
}

function FloatingTopBar({
  onBack,
  backLabel = "返回",
  right,
  fg = "rgba(255,255,255,0.92)",
}: {
  onBack: () => void;
  backLabel?: string;
  right?: React.ReactNode;
  fg?: string;
}) {
  const platform = usePlatform();
  const isMac = platform === "mac";
  const isWin = platform === "windows";
  const isAndroid = platform === "android";
  // paddingTop 要把状态栏 / 红黄绿 / decorum 三键的占位都算进去。
  //   Mac: 0 + 4 = 4（红黄绿中心 ~y=18，icon 36 居中后 ~y=18）
  //   Win: 0 + 1 = 1（decorum 中心 ~y=16）
  //   Android: env(safe-area-inset-top) + 6（状态栏后留 6px 透气，icon 不再压顶）
  //   default: 12
  // 之前用 .safe-top 类 + 内联 paddingTop 数字，inline 会覆盖类的 env padding
  // 导致 Android 下 icon 顶到状态栏。这里直接用 calc 把 env 包进 inline。
  const padTop: string =
    isMac
      ? "4px"
      : isWin
      ? "1px"
      : isAndroid
      ? "calc(max(env(safe-area-inset-top), 28px) + 6px)"
      : "12px";
  return (
    <div
      // data-tauri-drag-region：让顶部"空白"区域作为窗口拖动区
      // —— 没有这条 Mac/Win 桌面用户没法用鼠标拖动 app
      data-tauri-drag-region
      style={{
        position: "absolute",
        top: 0,
        left: 0,
        right: 0,
        display: "flex",
        alignItems: "center",
        justifyContent: "space-between",
        paddingTop: padTop,
        paddingBottom: 8,
        // Mac 红黄绿宽 ~78 + 透气；Win decorum ~138 + 透气
        paddingLeft: isMac ? 86 : 14,
        paddingRight: isWin ? 152 : 14,
        gap: 12,
        zIndex: 10,
      }}
    >
      <button
        onClick={onBack}
        style={chipStyle(fg)}
        aria-label={backLabel}
        title={backLabel}
      >
        <BackIcon />
      </button>
      <div style={{ display: "flex", gap: 8 }}>{right}</div>
    </div>
  );
}

// === 完全照抄 PlayerCard ImmersiveLyrics 的布局：
//     - 手机 stacked：cover 顶（80% 区域不 mask，下 20% 渐隐进背景），
//       title 块压在封面下边缘的 mask 区，列表在更下方
//     - 桌面 side-by-side：cover 居左，4 向 mask 把封面边缘溶进背景，
//       title 块在封面正下方，列表占满右侧 + 上下 18% mask 渐隐
//     - 列表上下也走 mask，跟歌词列同款上下淡入
const COVER_MASK_MOBILE =
  "linear-gradient(to bottom, #000 55%, transparent 100%)";
const COVER_MASK_DESKTOP =
  "linear-gradient(to right, transparent 0%, #000 4%, #000 65%, transparent 100%), " +
  "linear-gradient(to bottom, transparent 0%, #000 5%, #000 95%, transparent 100%)";
const LIST_MASK =
  "linear-gradient(180deg, transparent 0%, #000 18%, #000 82%, transparent 100%)";

function ImmersiveLayout({
  playlists,
  focusIdx,
  onFocusChange,
  focused,
  tracks,
  loading,
  error,
  fg,
  fgDim,
  isDesktop,
  canPlay,
  onPlayAll,
}: {
  playlists: PlaylistInfo[];
  focusIdx: number;
  onFocusChange: (i: number) => void;
  focused: PlaylistInfo | null;
  tracks: TrackInfo[];
  loading: boolean;
  error: string | null;
  fg: string;
  fgDim: string;
  isDesktop: boolean;
  canPlay: boolean;
  onPlayAll: () => void;
}) {
  if (isDesktop) {
    const W = "min(46vw, 70vh, 540px)";
    const titleBlockH = "clamp(92px, 12vh, 122px)";
    const gapBelow = "clamp(36px, 5.4vh, 60px)";
    const totalH = `calc(${W} + ${gapBelow} + ${titleBlockH})`;
    const top = `calc((100vh - ${totalH}) / 2)`;
    const left = "clamp(24px, 6vw, 80px)";
    const lyricLeft = `calc(${left} + ${W} + clamp(40px, 6vw, 100px))`;
    const lyricRight = "clamp(24px, 6vw, 80px)";
    return (
      <>
        <div
          // 桌面下封面区也算窗口拖动区（用户拖封面 = 拖窗口，不是拖图片）；
          // PlaylistPager 内部的 onWheel 仍然生效，按钮 / 链接 Tauri 自动豁免
          data-tauri-drag-region
          style={{ position: "absolute", top, left, width: W, height: W }}
        >
          <PlaylistPager
            playlists={playlists}
            focusIdx={focusIdx}
            onChange={onFocusChange}
            orientation="vertical"
            peek={0}
            mask={COVER_MASK_DESKTOP}
            maskComposite="intersect"
          />
        </div>
        {focused && (
          <div
            key={focused.id}
            style={{
              position: "absolute",
              top: `calc(${top} + ${W} + ${gapBelow})`,
              left,
              width: W,
              animation: "metaFade 380ms cubic-bezier(0.22,0.61,0.36,1) both",
              display: "flex",
              alignItems: "center",
              gap: 16,
            }}
          >
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ ...titleStyleLarge, color: fg }} title={focused.name}>
                {focused.name}
              </div>
              <div style={{ ...subtitleStyleLarge, color: fgDim }}>
                {focused.trackCount} TRACKS
              </div>
            </div>
            <PlayAllBtn fg={fg} canPlay={canPlay} onPlayAll={onPlayAll} />
          </div>
        )}
        <div
          style={{
            position: "absolute",
            top: "8vh",
            left: lyricLeft,
            right: lyricRight,
            height: "84vh",
            overflow: "hidden",
            WebkitMaskImage: LIST_MASK,
            maskImage: LIST_MASK,
          }}
        >
          <TrackListImmersive
            focusedKey={focused?.id}
            tracks={tracks}
            loading={loading}
            error={error}
            fg={fg}
            fgDim={fgDim}
          />
        </div>
      </>
    );
  }

  // ---- mobile stacked ----
  const W = "min(100vw, 78vh, 700px)";
  // cap 收到 8px：原先 32px 让封面顶离窗顶太远，跟标题"也跟着压下去一截"
  const top = `clamp(0px, calc((100vw - ${W}) / 2), 8px)`;
  const left = `calc((100vw - ${W}) / 2)`;
  const titlePadX = "clamp(20px, 5vw, 36px)";
  const lyricTop = `calc(${top} + ${W} + clamp(8px, 1.6vh, 16px))`;
  return (
    <>
      <div
        // 在 Android 上 data-tauri-drag-region 无副作用；桌面（窄窗 = mobile 布局）
        // 也能用拖动来移窗
        data-tauri-drag-region
        style={{ position: "absolute", top, left, width: W, height: W }}
      >
        <PlaylistPager
          playlists={playlists}
          focusIdx={focusIdx}
          onChange={onFocusChange}
          orientation="horizontal"
          peek={0}
          mask={COVER_MASK_MOBILE}
        />
      </div>
      {focused && (
        <div
          key={focused.id}
          style={{
            position: "absolute",
            top: `calc(${top} + ${W} * 0.82)`,
            left: `calc(${left} + ${titlePadX})`,
            width: `calc(${W} - 2 * ${titlePadX})`,
            animation: "metaFade 380ms cubic-bezier(0.22,0.61,0.36,1) both",
            display: "flex",
            alignItems: "center",
            gap: 12,
          }}
        >
          <div style={{ flex: 1, minWidth: 0 }}>
            <div style={{ ...titleStyle, color: fg }} title={focused.name}>
              {focused.name}
            </div>
            <div style={{ ...subtitleStyle, color: fgDim }}>
              {focused.trackCount} TRACKS
            </div>
          </div>
          <PlayAllBtn fg={fg} canPlay={canPlay} onPlayAll={onPlayAll} compact />
        </div>
      )}
      <div
        style={{
          position: "absolute",
          top: lyricTop,
          left,
          width: W,
          bottom: "clamp(8px, 2vh, 24px)",
          overflow: "hidden",
          WebkitMaskImage: LIST_MASK,
          maskImage: LIST_MASK,
        }}
      >
        <TrackListImmersive
          focusedKey={focused?.id}
          tracks={tracks}
          loading={loading}
          error={error}
          fg={fg}
          fgDim={fgDim}
        />
      </div>
    </>
  );
}

function PlayAllBtn({
  fg,
  canPlay,
  onPlayAll,
  compact = false,
}: {
  fg: string;
  canPlay: boolean;
  onPlayAll: () => void;
  compact?: boolean;
}) {
  return (
    <button
      onClick={onPlayAll}
      disabled={!canPlay}
      style={{
        display: "inline-flex",
        alignItems: "center",
        justifyContent: "center",
        width: compact ? 40 : 46,
        height: compact ? 40 : 46,
        borderRadius: 999,
        border: "none",
        background: "transparent",
        color: fg,
        cursor: canPlay ? "pointer" : "not-allowed",
        opacity: canPlay ? 1 : 0.4,
        flexShrink: 0,
        filter: "drop-shadow(0 2px 6px rgba(0,0,0,0.45))",
        transition: "transform 160ms ease",
      }}
      aria-label="播放全部"
      title="播放全部"
      onMouseEnter={(e) => {
        if (canPlay) e.currentTarget.style.transform = "scale(1.08)";
      }}
      onMouseLeave={(e) => {
        e.currentTarget.style.transform = "scale(1)";
      }}
    >
      <svg
        width={compact ? 20 : 24}
        height={compact ? 20 : 24}
        viewBox="0 0 24 24"
        fill="currentColor"
        aria-hidden
      >
        <path d="M8 5v14l11-7z" />
      </svg>
    </button>
  );
}

function TrackListImmersive({
  focusedKey,
  tracks,
  loading,
  error,
  fg,
  fgDim,
}: {
  focusedKey: number | undefined;
  tracks: TrackInfo[];
  loading: boolean;
  error: string | null;
  fg: string;
  fgDim: string;
}) {
  return (
    <div
      style={{
        position: "absolute",
        inset: 0,
        overflowY: "auto",
        WebkitOverflowScrolling: "touch",
        padding: "clamp(20px, 4vh, 36px) clamp(20px, 5vw, 36px)",
      }}
    >
      <div
        key={focusedKey ?? "none"}
        style={{ animation: "listFade 380ms cubic-bezier(0.22,0.61,0.36,1) both" }}
      >
        {loading && tracks.length === 0 && (
          <div style={{ color: fgDim, fontSize: 12, textAlign: "center", padding: 24 }}>
            正在加载曲目…
          </div>
        )}
        {error && tracks.length === 0 && (
          <div style={{ color: "#ffb4b4", fontSize: 12, textAlign: "center", padding: 24 }}>
            {error}
          </div>
        )}
        {tracks.length > 0 && (
          <FusionTrackList tracks={tracks} fg={fg} fgDim={fgDim} />
        )}
      </div>
    </div>
  );
}

// 跟 PlayerCard 的 immersive title / subtitle 同款字号、同款字重、同款 letterSpacing
const titleStyle: React.CSSProperties = {
  fontSize: "clamp(16px, 4.4vw, 20px)",
  fontWeight: 700,
  letterSpacing: "-0.005em",
  overflow: "hidden",
  textOverflow: "ellipsis",
  whiteSpace: "nowrap",
};
const subtitleStyle: React.CSSProperties = {
  marginTop: 5,
  fontSize: "clamp(11px, 2.6vw, 12px)",
  fontWeight: 500,
  letterSpacing: 2.4,
  fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace",
  overflow: "hidden",
  textOverflow: "ellipsis",
  whiteSpace: "nowrap",
};
const titleStyleLarge: React.CSSProperties = {
  fontSize: "clamp(20px, 2.2vw, 28px)",
  fontWeight: 700,
  letterSpacing: "-0.01em",
  overflow: "hidden",
  textOverflow: "ellipsis",
  whiteSpace: "nowrap",
};
const subtitleStyleLarge: React.CSSProperties = {
  marginTop: 6,
  fontSize: "clamp(12px, 1.2vw, 14px)",
  fontWeight: 500,
  letterSpacing: 2.4,
  fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace",
  overflow: "hidden",
  textOverflow: "ellipsis",
  whiteSpace: "nowrap",
};

function FusionTrackList({
  tracks,
  fg,
  fgDim,
}: {
  tracks: TrackInfo[];
  fg: string;
  fgDim: string;
}) {
  const player = usePlayer();
  return (
    <div style={{ padding: "0 4px" }}>
      {tracks.map((t) => (
        <TrackRow
          key={t.id}
          track={t}
          tracks={tracks}
          fg={fg}
          fgDim={fgDim}
          player={player}
        />
      ))}
    </div>
  );
}

function TrackRow({
  track,
  tracks,
  fg,
  fgDim,
  player,
}: {
  track: TrackInfo;
  tracks: TrackInfo[];
  fg: string;
  fgDim: string;
  player: ReturnType<typeof usePlayer>;
}) {
  const active = player.current?.neteaseId === track.id;
  const isPlayingThis = active && player.isPlaying;

  const onRowClick = () => {
    // 单击播放：active 行就 toggle，非 active 直接切歌
    if (active) player.toggle();
    else void player.playNetease(track, tracks);
  };
  const onPlayClick = (e: React.MouseEvent) => {
    e.stopPropagation();
    onRowClick();
  };

  return (
    // 用 div 不用 button：里面要再嵌一个 ▶ button（HTML 不允许 button 嵌 button）
    // 单击行 → 播放 / toggle；蓝色 tap highlight 用 WebkitTapHighlightColor 干掉
    <div
      role="button"
      tabIndex={0}
      onClick={onRowClick}
      onKeyDown={(e) => {
        if (e.key === "Enter" || e.key === " ") {
          e.preventDefault();
          onRowClick();
        }
      }}
      style={{
        display: "flex",
        alignItems: "center",
        gap: 14,
        padding: "8px 6px",
        cursor: "pointer",
        background: "transparent",
        transition: "opacity 160ms ease",
        opacity: active ? 1 : 0.78,
        WebkitTapHighlightColor: "transparent",
        outline: "none",
      }}
      onMouseEnter={(e) => {
        if (!active) e.currentTarget.style.opacity = "0.94";
      }}
      onMouseLeave={(e) => {
        if (!active) e.currentTarget.style.opacity = "0.78";
      }}
    >
      {/* 不再显示序号；仅当当前正在播放才左侧 8px 留一道动效 bar 当指示 */}
      {isPlayingThis && (
        <div
          style={{
            width: 12,
            display: "flex",
            justifyContent: "center",
            flexShrink: 0,
          }}
        >
          <PlayingMark fg={fg} />
        </div>
      )}

      <div style={{ flex: 1, minWidth: 0 }}>
        <div
          style={{
            color: fg,
            fontSize: 14,
            fontWeight: active ? 600 : 500,
            overflow: "hidden",
            textOverflow: "ellipsis",
            whiteSpace: "nowrap",
          }}
          title={track.name}
        >
          {track.name}
        </div>
        <div
          style={{
            color: fgDim,
            fontSize: 12,
            marginTop: 2,
            overflow: "hidden",
            textOverflow: "ellipsis",
            whiteSpace: "nowrap",
          }}
        >
          {track.artists.map((a) => a.name).join(" / ") || "未知艺人"}
        </div>
      </div>

      <div
        style={{
          color: fgDim,
          fontSize: 11,
          fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace",
          flexShrink: 0,
        }}
      >
        {fmtMs(track.durationMs)}
      </div>

      {/* 播放按钮：用户明确要求每行有；active 时切成暂停 */}
      <button
        onClick={onPlayClick}
        aria-label={active ? (player.isPlaying ? "暂停" : "继续") : "播放"}
        title={active ? (player.isPlaying ? "暂停" : "继续") : "播放"}
        style={{
          ...playBtn(fg),
          flexShrink: 0,
        }}
      >
        {isPlayingThis ? <PauseIcon /> : <PlayIcon />}
      </button>
    </div>
  );
}

function PlayingMark({ fg }: { fg: string }) {
  // 三条 bar 跳动，做成 active 行的"正在播"指示
  return (
    <span
      aria-hidden
      style={{
        display: "inline-flex",
        alignItems: "flex-end",
        gap: 2,
        height: 12,
      }}
    >
      <span style={{ ...bar(fg), animationDelay: "0ms" }} />
      <span style={{ ...bar(fg), animationDelay: "120ms", height: 8 }} />
      <span style={{ ...bar(fg), animationDelay: "240ms", height: 5 }} />
    </span>
  );
}

const bar = (fg: string): React.CSSProperties => ({
  display: "inline-block",
  width: 2,
  height: 10,
  background: fg,
  borderRadius: 1,
  animation: "playingBar 900ms ease-in-out infinite alternate",
});

function PlayIcon() {
  return (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor" aria-hidden>
      <path d="M8 5v14l11-7z" />
    </svg>
  );
}

function PauseIcon() {
  return (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor" aria-hidden>
      <path d="M6 5h4v14H6zM14 5h4v14h-4z" />
    </svg>
  );
}

// 列表行播放按钮：纯线稿，无 chip 背景，跟整页 floating 图标一致风格
const playBtn = (fg: string): React.CSSProperties => ({
  width: 36,
  height: 36,
  borderRadius: 999,
  border: "none",
  background: "transparent",
  color: fg,
  cursor: "pointer",
  display: "inline-flex",
  alignItems: "center",
  justifyContent: "center",
  filter: "drop-shadow(0 1px 4px rgba(0,0,0,0.4))",
  WebkitTapHighlightColor: "transparent",
  transition: "transform 160ms cubic-bezier(0.22,0.61,0.36,1), opacity 160ms ease",
});

// ---------- 顶部按钮图标 ----------
//
// 视觉统一三件事：
//   1. 所有 svg 都是 22x22（之前 20x20）—— 手指点更舒服，比 Mac 三键稍大一档
//   2. strokeWidth 全部 1.9：原先 2.0 / 2.2 混用，gear / profile 显粗，spark / back
//      显细，肉眼一眼看出"几个图标重量不一致"
//   3. 路径都填 24x24 viewBox 里的 ~18x18 主区，保证"墨水量"接近：
//      - Back：原 chevron 6x12 偏小 → 8x14
//      - Spark：原 4 角星中心偏上（y=10）+ 路径 14x14 → 居中 y=12 + 20x20
//      - Profile：心形+点已经填得够，保持
//      - Gear：齿轮太密，缩小到 ~17x17 + 居中，跟其它三个齐平
const TOP_ICON_SW = 1.9;
const TOP_ICON_SIZE = 22;

function BackIcon() {
  return (
    <svg width={TOP_ICON_SIZE} height={TOP_ICON_SIZE} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={TOP_ICON_SW} strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <path d="M16 5l-8 7 8 7" />
    </svg>
  );
}

function GearIcon() {
  // 把整套齿轮路径整体围绕 (12,12) 缩放到 0.9，图形更紧凑，跟 spark / profile
  // 的视觉直径对齐；strokeWidth 自动按 transform 视觉变化无碍（路径还是 path 单位）
  return (
    <svg width={TOP_ICON_SIZE} height={TOP_ICON_SIZE} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={TOP_ICON_SW} strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <g transform="translate(12 12) scale(0.88) translate(-12 -12)">
        <circle cx="12" cy="12" r="3" />
        <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09a1.65 1.65 0 0 0-1-1.51 1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09a1.65 1.65 0 0 0 1.51-1 1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33h0a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51h0a1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82v0a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z" />
      </g>
    </svg>
  );
}

function ProfileIcon() {
  return (
    <svg width={TOP_ICON_SIZE} height={TOP_ICON_SIZE} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={TOP_ICON_SW} strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <path d="M12 19c-4-2.5-7-5.4-7-9.2A3.8 3.8 0 0 1 12 7a3.8 3.8 0 0 1 7 2.8c0 3.8-3 6.7-7 9.2z" />
      <circle cx="18" cy="6" r="1.3" fill="currentColor" stroke="none" />
    </svg>
  );
}

function SparkIcon() {
  // 4 角星：中心移到 (12,12)，长轴 ±10、短轴 ±2 凹陷，整体填 ~20x20
  return (
    <svg width={TOP_ICON_SIZE} height={TOP_ICON_SIZE} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={TOP_ICON_SW} strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <path d="M12 2l1.8 8.2L22 12l-8.2 1.8L12 22l-1.8-8.2L2 12l8.2-1.8L12 2z" />
    </svg>
  );
}

/** 顶部 floating 图标 —— 36x36 chip + 20x20 SVG（手指点更舒服，
 *  视觉也比 Mac 三键稍大一档），无 chip 背景，drop-shadow 兜底可读性。 */
function chipStyle(fg: string): React.CSSProperties {
  const isDarkText = fg.startsWith("rgba(0,") || fg.startsWith("rgb(0,");
  const shadow = isDarkText
    ? "drop-shadow(0 1px 4px rgba(255,255,255,0.6))"
    : "drop-shadow(0 1px 6px rgba(0,0,0,0.55))";
  return {
    display: "inline-flex",
    alignItems: "center",
    justifyContent: "center",
    width: 36,
    height: 36,
    borderRadius: 999,
    border: "none",
    background: "transparent",
    color: fg,
    cursor: "pointer",
    textDecoration: "none",
    filter: shadow,
    WebkitTapHighlightColor: "transparent",
    transition: "transform 160ms cubic-bezier(0.22,0.61,0.36,1), opacity 160ms ease",
  };
}

// ---------- 多选工具栏 + 底部蒸馏栏 ----------

function DistillBottomBar({
  count,
  estimateTracks,
  state,
  onRun,
  onCancel,
}: {
  count: number;
  estimateTracks: number;
  state:
    | { kind: "idle" }
    | { kind: "running"; progress: DistillProgress }
    | { kind: "error"; message: string };
  onRun: () => void;
  onCancel: () => void;
}) {
  const running = state.kind === "running";
  const phaseText =
    state.kind === "running"
      ? state.progress.phase === "loading-tracks"
        ? `正在拉取第 ${state.progress.done + 1}/${state.progress.total} 张歌单…`
        : state.progress.phase === "sampling"
        ? "正在采样曲目…"
        : state.progress.phase === "calling-ai"
        ? "AI 正在写画像…（这一步最久）"
        : "完成"
      : null;

  return (
    <div
      style={{
        position: "sticky",
        bottom: 12,
        marginTop: 18,
        padding: "12px 16px",
        borderRadius: 14,
        background: "rgba(15,20,32,0.92)",
        border: "1px solid rgba(155,227,198,0.3)",
        backdropFilter: "blur(12px)",
        boxShadow: "0 18px 50px rgba(0,0,0,0.45)",
        display: "flex",
        alignItems: "center",
        gap: 14,
        flexWrap: "wrap",
        zIndex: 5,
      }}
    >
      <div style={{ flex: 1, minWidth: 180 }}>
        <div style={{ color: "#e9efff", fontSize: 13, fontWeight: 600 }}>
          已选 {count} 张 · 约 {estimateTracks} 首曲目
        </div>
        {phaseText && (
          <div style={{ color: "#9be3c6", fontSize: 11, marginTop: 4 }}>
            {phaseText}
          </div>
        )}
        {state.kind === "error" && (
          <div style={{ color: "#ffb4b4", fontSize: 11, marginTop: 4 }}>
            蒸馏失败：{state.message}
          </div>
        )}
      </div>
      <button
        onClick={onCancel}
        disabled={running}
        style={{ ...ghostPillBtn, opacity: running ? 0.5 : 1 }}
      >
        取消
      </button>
      <button
        onClick={onRun}
        disabled={count === 0 || running}
        style={{
          ...primaryPillBtn,
          opacity: count === 0 || running ? 0.5 : 1,
          cursor: count === 0 || running ? "not-allowed" : "pointer",
        }}
      >
        {running ? "蒸馏中 · · ·" : "开始蒸馏 →"}
      </button>
    </div>
  );
}

const primaryPillBtn: React.CSSProperties = {
  padding: "8px 18px",
  borderRadius: 999,
  border: "1px solid rgba(155,227,198,0.5)",
  background: "rgba(155,227,198,0.16)",
  color: "#9be3c6",
  fontSize: 13,
  fontWeight: 600,
  cursor: "pointer",
  whiteSpace: "nowrap",
};

const ghostPillBtn: React.CSSProperties = {
  padding: "8px 16px",
  borderRadius: 999,
  border: "1px solid rgba(233,239,255,0.18)",
  background: "transparent",
  color: "#e9efff",
  fontSize: 13,
  cursor: "pointer",
  whiteSpace: "nowrap",
};

// ---------- 子组件 ----------

function Placeholder({ text, err }: { text: string; err?: boolean }) {
  return (
    <div
      className="glass"
      style={{
        padding: 30,
        textAlign: "center",
        color: err ? "#ffb4b4" : "#8a93a8",
        fontSize: 13,
      }}
    >
      {text}
    </div>
  );
}

function UnauthState() {
  return (
    <div className="glass" style={{ padding: 30, textAlign: "center" }}>
      <div style={{ color: "#e9efff", fontSize: 16, marginBottom: 10 }}>
        还没扫码登录
      </div>
      <div style={{ color: "#8a93a8", fontSize: 13, marginBottom: 18 }}>
        需要登录网易云账号才能看到你的歌单
      </div>
      <Link
        href="/login"
        style={{
          display: "inline-block",
          padding: "8px 18px",
          borderRadius: 999,
          border: "1px solid #9be3c6",
          color: "#9be3c6",
          textDecoration: "none",
          fontSize: 13,
        }}
      >
        去扫码登录 →
      </Link>
    </div>
  );
}

function PlaylistGrid({
  list,
  selectMode,
  selectedIds,
  onPick,
  onToggleSelect,
}: {
  list: PlaylistInfo[];
  selectMode: boolean;
  selectedIds: Set<number>;
  onPick: (id: number) => void;
  onToggleSelect: (id: number) => void;
}) {
  if (list.length === 0) return <Placeholder text="你还没有歌单" />;
  return (
    <div
      style={{
        display: "grid",
        gridTemplateColumns: "repeat(auto-fill, minmax(220px, 1fr))",
        gap: 12,
      }}
    >
      {list.map((p) => {
        const selected = selectedIds.has(p.id);
        return (
          <div
            key={p.id}
            onClick={() =>
              selectMode ? onToggleSelect(p.id) : onPick(p.id)
            }
            className="glass"
            style={{
              padding: 14,
              cursor: "pointer",
              transition: "transform 180ms ease, border-color 180ms ease, background 180ms ease",
              display: "flex",
              gap: 12,
              alignItems: "center",
              position: "relative",
              borderColor: selected
                ? "rgba(155,227,198,0.7)"
                : "rgba(233,239,255,0.08)",
              background: selected
                ? "rgba(155,227,198,0.10)"
                : "rgba(12,16,24,0.55)",
            }}
            onMouseEnter={(e) =>
              (e.currentTarget.style.transform = "translateY(-2px)")
            }
            onMouseLeave={(e) =>
              (e.currentTarget.style.transform = "translateY(0)")
            }
          >
            <Cover src={p.coverImgUrl ?? undefined} />
            <div style={{ minWidth: 0, flex: 1 }}>
              <div
                style={{
                  fontSize: 14,
                  fontWeight: 600,
                  overflow: "hidden",
                  textOverflow: "ellipsis",
                  whiteSpace: "nowrap",
                }}
                title={p.name}
              >
                {p.name}
              </div>
              <div style={{ color: "#8a93a8", fontSize: 12, marginTop: 4 }}>
                {p.trackCount} 首
              </div>
            </div>

            {selectMode && (
              <div
                aria-hidden
                style={{
                  width: 20,
                  height: 20,
                  borderRadius: 999,
                  border: selected
                    ? "1.5px solid #9be3c6"
                    : "1.5px solid rgba(233,239,255,0.3)",
                  background: selected ? "#9be3c6" : "transparent",
                  display: "flex",
                  alignItems: "center",
                  justifyContent: "center",
                  flexShrink: 0,
                  transition: "all 160ms ease",
                }}
              >
                {selected && (
                  <svg
                    width="11"
                    height="11"
                    viewBox="0 0 12 12"
                    fill="none"
                    stroke="#0b0d12"
                    strokeWidth="2"
                    strokeLinecap="round"
                    strokeLinejoin="round"
                  >
                    <path d="M2.5 6.2l2.5 2.5L9.5 3.6" />
                  </svg>
                )}
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}

function Cover({ src, size = 56 }: { src?: string; size?: number }) {
  if (!src) {
    return (
      <div
        style={{
          width: size,
          height: size,
          borderRadius: 8,
          background: "rgba(155,227,198,0.12)",
          flexShrink: 0,
        }}
      />
    );
  }
  return (
    // eslint-disable-next-line @next/next/no-img-element
    <img
      src={cdn(src)}
      alt=""
      width={size}
      height={size}
      style={{
        width: size,
        height: size,
        borderRadius: 8,
        objectFit: "cover",
        flexShrink: 0,
      }}
    />
  );
}

function fmtMs(ms: number) {
  const s = Math.max(0, Math.floor(ms / 1000));
  const m = Math.floor(s / 60);
  const r = s % 60;
  return `${m}:${r.toString().padStart(2, "0")}`;
}
