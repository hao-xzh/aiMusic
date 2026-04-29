"use client";

/**
 * 我的歌单 —— 真数据。
 *
 * 流程：
 *   1) netease.account() 拿到 userId
 *   2) netease.userPlaylists(userId) 拿歌单列表
 *   3) 点一张歌单 -> netease.playlistDetail(id) 拿曲目
 *   4) 点一首曲目 -> player.playNetease(track, 同歌单作为队列)
 *      player-state 会在后台 fetch 直链 + set <audio>.src + play
 *
 * 未登录 / 出错时原地给提示，不再伪装成 mock 数据。
 */

import { usePlayer } from "@/lib/player-state";
import { cdn } from "@/lib/cdn";
import {
  ai,
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
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useMemo, useState } from "react";

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
  const [me, setMe] = useState<UserProfile | null>(null);
  const [list, setList] = useState<Load<PlaylistInfo[]>>({ kind: "loading" });
  const [activeId, setActiveId] = useState<number | null>(null);
  /** 后台 SWR 刷新时给用户一个"正在同步…"的微弱提示 */
  const [refreshing, setRefreshing] = useState(false);

  // 多选模式 —— 进入"挑歌单蒸馏画像"流程时把网格切到 toggle 选择，
  // 不进入详情页。退出后选中状态清空。
  const [selectMode, setSelectMode] = useState(false);
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
    setSelectMode(false);
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

      // 蒸馏完跳口味页前，只后台分析整个库的 BPM / energy / 人声段。
      // 不 await：让页面立刻跳转，分析在后台慢慢跑（cache 落盘，下次启动跳过已分析的）。
      // 宠物排歌单时能看到这些数据，排序就有依据，丝滑接歌才有可能。
      clearLibraryMemo();
      void (async () => {
        try {
          const lib = await loadLibrary();
          if (lib.length === 0) return;
          console.debug(`[claudio] 启动后台音频分析：${lib.length} 首`);
          // 音频分析进度走 analysis-progress 全局 store，设置页可见
          await startBackgroundAnalysis(lib);
          console.debug(`[claudio] 后台分析完成`);
        } catch (e) {
          console.debug("[claudio] 后台分析挂了，跳过", e);
        }
      })();

      // 成功 → 跳到口味页看结果
      router.push("/taste");
    } catch (e) {
      setDistillState({
        kind: "error",
        message: e instanceof Error ? e.message : String(e),
      });
    }
  };

  // 选中的总曲目数（按缓存的 trackCount 估算 —— 实际蒸馏会拉详情）
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

        // 1) cache-first：缓存有就立刻铺 UI，不等网络
        const cached = await cache.getPlaylists(profile.userId);
        if (!alive) return;
        if (cached.length > 0) {
          setList({
            kind: "loaded",
            data: cached.map((c) => cachedToPlaylistInfo(c, profile.userId)),
          });
        }

        // 2) 后台 SWR：拉最新 weapi，对比 updateTime 再决定是否 upsert
        setRefreshing(true);
        try {
          const fresh = await netease.userPlaylists(profile.userId, 1000);
          if (!alive) return;

          // 差异对比：只要出现/消失/updateTime 变化就认为要更新
          const byId = new Map(cached.map((c) => [c.id, c.updateTime ?? null]));
          const changed =
            fresh.length !== cached.length ||
            fresh.some((p) => {
              if (!byId.has(p.id)) return true;
              const old = byId.get(p.id);
              return (p.updateTime ?? null) !== old;
            });

          if (changed) {
            // 先写缓存（让下次进来秒开），再刷 UI
            await cache.savePlaylists(profile.userId, fresh);
            if (!alive) return;
            setList({ kind: "loaded", data: fresh });
          } else if (cached.length === 0) {
            // 首次 = 缓存是空的，这次拉到了，就写进去并展示
            await cache.savePlaylists(profile.userId, fresh);
            if (!alive) return;
            setList({ kind: "loaded", data: fresh });
          }
        } catch (e) {
          // 后台刷新失败但已经有缓存 -> 不把页面打成 error，静默
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

  return (
    <div
      style={{
        // 横向 padding clamp 跟随窗口；最大宽度 960 居中。
        padding: "clamp(8px, 2vw, 16px) clamp(12px, 4vw, 24px) 60px",
        maxWidth: 960,
        margin: "0 auto",
        width: "100%",
      }}
    >
      <div style={pageBrand}>MY LIBRARY</div>
      <div style={pageSubtitle}>
        {me
          ? `你好 ${me.nickname} · 挑一张歌单，点一首歌 Claudio 就会在主窗口播放`
          : "挑一张歌单，点一首歌 Claudio 就会在主窗口播放"}
      </div>

      {list.kind === "loading" && <Placeholder text="正在拉取你的歌单…" />}
      {list.kind === "unauth" && <UnauthState />}
      {list.kind === "error" && (
        <Placeholder text={`出错了：${list.message}`} err />
      )}

      {refreshing && list.kind === "loaded" && (
        <div
          style={{
            textAlign: "center",
            color: "#8a93a8",
            fontSize: 11,
            marginBottom: 8,
            opacity: 0.7,
          }}
        >
          · 正在同步最新歌单 ·
        </div>
      )}

      {list.kind === "loaded" && activeId === null && (
        <>
          <DistillToolbar
            selectMode={selectMode}
            onEnter={() => setSelectMode(true)}
            onExit={exitSelectMode}
            selectedCount={selectedIds.size}
          />
          <PlaylistGrid
            list={list.data}
            selectMode={selectMode}
            selectedIds={selectedIds}
            onPick={setActiveId}
            onToggleSelect={toggleSelect}
          />
          {selectMode && (
            <DistillBottomBar
              count={selectedIds.size}
              estimateTracks={selectedTrackEstimate}
              state={distillState}
              onRun={runDistill}
              onCancel={exitSelectMode}
            />
          )}
        </>
      )}
      {list.kind === "loaded" && activeId !== null && me && (
        <PlaylistDetailView
          id={activeId}
          uid={me.userId}
          onBack={() => setActiveId(null)}
        />
      )}
    </div>
  );
}

// ---------- 多选工具栏 + 底部蒸馏栏 ----------

function DistillToolbar({
  selectMode,
  selectedCount,
  onEnter,
  onExit,
}: {
  selectMode: boolean;
  selectedCount: number;
  onEnter: () => void;
  onExit: () => void;
}) {
  return (
    <div
      style={{
        display: "flex",
        justifyContent: "space-between",
        alignItems: "center",
        marginBottom: 12,
        gap: 10,
        flexWrap: "wrap",
      }}
    >
      <div style={{ color: "#8a93a8", fontSize: 12 }}>
        {selectMode
          ? `挑选歌单中 · 已选 ${selectedCount} 张`
          : "想要一份你的「音乐画像」？挑几张能代表你的歌单"}
      </div>
      {selectMode ? (
        <button onClick={onExit} style={ghostPillBtn}>
          取消
        </button>
      ) : (
        <button onClick={onEnter} style={primaryPillBtn}>
          ✦ 蒸馏画像
        </button>
      )}
    </div>
  );
}

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

const pageBrand: React.CSSProperties = {
  textAlign: "center",
  fontSize: 11,
  letterSpacing: 4,
  color: "rgba(233,239,255,0.42)",
  fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace",
  marginTop: "clamp(12px, 3vh, 24px)",
  marginBottom: 14,
  textTransform: "uppercase",
};

const pageSubtitle: React.CSSProperties = {
  textAlign: "center",
  color: "#8a93a8",
  marginBottom: "clamp(22px, 4vh, 32px)",
  fontSize: 13,
  lineHeight: 1.55,
};

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

function PlaylistDetailView({
  id,
  uid,
  onBack,
}: {
  id: number;
  uid: number;
  onBack: () => void;
}) {
  const [tracks, setTracks] = useState<Load<TrackInfo[]>>({ kind: "loading" });
  const [playlistName, setPlaylistName] = useState<string>("");
  // updateTime 用来做蒸馏缓存的 key —— 歌单没变就不必再 call AI
  const [updateTime, setUpdateTime] = useState<number | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const player = usePlayer();

  useEffect(() => {
    let alive = true;
    setTracks({ kind: "loading" });
    (async () => {
      try {
        // 1) cache-first —— 但只有当缓存里真的有曲目时才拿它铺 UI。
        //    坑：进 /distill 网格页时 `cache.savePlaylists` 只会写 playlists 表的
        //    header（name/cover/updateTime），不会写详情。所以这里 getPlaylistDetail
        //    可能返回 `Some({ ..., tracks: [] })` —— 只有 header 没有详情。
        //    此时必须去 weapi 拉一次，否则页面会一直"空的"。
        const cached = await cache.getPlaylistDetail(id);
        if (!alive) return;
        // 缓存里这首 track 看起来是"坏"的（没艺人 + 没时长）说明是之前一版
        // TrackInfo 序列化 bug 写坏的，应当触发强刷新，而不是把空艺人铺 UI。
        const looksCorrupt =
          !!cached &&
          cached.tracks.length > 0 &&
          cached.tracks.every(
            (t) => t.durationMs === 0 && (!t.artists || t.artists.length === 0),
          );
        const hasCachedTracks =
          !!cached && cached.tracks.length > 0 && !looksCorrupt;
        if (hasCachedTracks) {
          setPlaylistName(cached.name);
          setUpdateTime(cached.updateTime ?? null);
          setTracks({ kind: "loaded", data: cached.tracks });
        } else if (cached) {
          // 至少先把名字亮出来，别让标题栏空着
          setPlaylistName(cached.name);
          setUpdateTime(cached.updateTime ?? null);
        }

        // 2) 后台：没有缓存 / 缓存只有 header / updateTime 变了 都要拉
        setRefreshing(true);
        try {
          const fresh = await netease.playlistDetail(id);
          if (!alive) return;

          const oldUT = cached?.updateTime ?? null;
          const newUT = fresh.updateTime ?? null;
          // looksCorrupt 也算一种需要重拉的信号，绕开 updateTime 相等的短路
          const shouldUpdate = !hasCachedTracks || looksCorrupt || oldUT !== newUT;

          if (shouldUpdate) {
            // 落缓存（详情表 + tracks 表 + playlist_tracks 关联 + 更新 playlists.update_time）
            await cache.savePlaylistDetail(uid, fresh);
            if (!alive) return;
            setPlaylistName(fresh.name);
            setUpdateTime(fresh.updateTime ?? null);
            setTracks({ kind: "loaded", data: fresh.tracks });
          }
        } catch (e) {
          // 缓存里已经有真实 tracks 顶着 -> 网络挂就静默；
          // 没有真实 tracks（null or 空）-> 必须报红让用户知道
          if (!hasCachedTracks) {
            setTracks({
              kind: "error",
              message: e instanceof Error ? e.message : String(e),
            });
          } else {
            console.warn("[claudio] 歌单详情后台刷新失败，用缓存继续", e);
          }
        } finally {
          if (alive) setRefreshing(false);
        }
      } catch (e) {
        if (alive)
          setTracks({
            kind: "error",
            message: e instanceof Error ? e.message : String(e),
          });
      }
    })();
    return () => {
      alive = false;
    };
  }, [id, uid]);

  return (
    <div>
      <div
        style={{
          display: "flex",
          alignItems: "center",
          gap: 10,
          marginBottom: 14,
        }}
      >
        <button
          onClick={onBack}
          style={{
            padding: "8px 14px",
            borderRadius: 999,
            border: "1px solid rgba(233,239,255,0.2)",
            background: "transparent",
            color: "#e9efff",
            fontSize: 13,
            cursor: "pointer",
          }}
        >
          ← 返回
        </button>
        <div style={{ color: "#8a93a8", fontSize: 13 }}>
          {playlistName || "歌单"}
          {refreshing && tracks.kind === "loaded" && (
            <span style={{ marginLeft: 8, opacity: 0.6, fontSize: 11 }}>
              · 同步中
            </span>
          )}
        </div>
      </div>

      {tracks.kind === "loading" && <Placeholder text="正在加载曲目…" />}
      {tracks.kind === "error" && (
        <Placeholder text={`出错了：${tracks.message}`} err />
      )}
      {tracks.kind === "loaded" && (
        <>
          <DistillBlock
            playlistId={id}
            playlistName={playlistName}
            updateTime={updateTime}
            tracks={tracks.data}
            onPlay={player.playNetease}
          />
          <TrackList tracks={tracks.data} onPlay={player.playNetease} />
        </>
      )}

      {player.error && (
        <div
          style={{
            marginTop: 12,
            padding: "10px 14px",
            borderRadius: 10,
            background: "rgba(255,180,180,0.1)",
            border: "1px solid rgba(255,180,180,0.3)",
            color: "#ffb4b4",
            fontSize: 12,
          }}
        >
          {player.error}
        </div>
      )}
    </div>
  );
}

function TrackList({
  tracks,
  onPlay,
}: {
  tracks: TrackInfo[];
  onPlay: (t: TrackInfo, queue?: TrackInfo[]) => Promise<void>;
}) {
  const player = usePlayer();
  if (tracks.length === 0) return <Placeholder text="这张歌单是空的" />;
  return (
    <div className="glass" style={{ padding: 8 }}>
      {tracks.map((t, i) => {
        const active = player.current?.neteaseId === t.id;
        return (
          <div
            key={t.id}
            onClick={() => void onPlay(t, tracks)}
            style={{
              display: "flex",
              alignItems: "center",
              padding: "10px 12px",
              borderRadius: 10,
              cursor: "pointer",
              gap: 12,
              background: active ? "rgba(155,227,198,0.12)" : "transparent",
              color: active ? "#9be3c6" : "#e9efff",
              transition: "background 120ms ease",
            }}
            onMouseEnter={(e) =>
              (e.currentTarget.style.background = active
                ? "rgba(155,227,198,0.18)"
                : "rgba(233,239,255,0.05)")
            }
            onMouseLeave={(e) =>
              (e.currentTarget.style.background = active
                ? "rgba(155,227,198,0.12)"
                : "transparent")
            }
          >
            <div
              style={{
                width: 24,
                textAlign: "right",
                color: active ? "#9be3c6" : "#8a93a8",
                fontSize: 12,
                fontFamily:
                  "ui-monospace, SFMono-Regular, Menlo, monospace",
              }}
            >
              {active && player.isPlaying ? "♪" : i + 1}
            </div>
            <div style={{ flex: 1, minWidth: 0 }}>
              <div
                style={{
                  fontSize: 14,
                  fontWeight: 500,
                  overflow: "hidden",
                  textOverflow: "ellipsis",
                  whiteSpace: "nowrap",
                }}
                title={t.name}
              >
                {t.name}
              </div>
              <div
                style={{
                  color: "#8a93a8",
                  fontSize: 12,
                  marginTop: 2,
                  overflow: "hidden",
                  textOverflow: "ellipsis",
                  whiteSpace: "nowrap",
                }}
              >
                {t.artists.map((a) => a.name).join(" / ") || "未知艺人"}
                {t.album?.name ? ` · ${t.album.name}` : ""}
              </div>
            </div>
            <div
              style={{
                color: "#8a93a8",
                fontSize: 12,
                fontFamily:
                  "ui-monospace, SFMono-Regular, Menlo, monospace",
              }}
            >
              {fmtMs(t.durationMs)}
            </div>
          </div>
        );
      })}
    </div>
  );
}

function fmtMs(ms: number) {
  const s = Math.max(0, Math.floor(ms / 1000));
  const m = Math.floor(s / 60);
  const r = s % 60;
  return `${m}:${r.toString().padStart(2, "0")}`;
}

// ============================================================================
// 蒸馏块
// ============================================================================
//
// 设计：
//   - cache-first：缓存按 `distill:{playlistId}:{updateTime}` 索引。歌单
//     updateTime 变了天然 miss，重算；没变永远命中，省 token。
//   - 超过 300 首时先做采样：每个艺人保 1 首，其余随机补齐，避免把 prompt
//     撑到 30k+ token，也避免 DeepSeek 被长列表带跑偏。
//   - AI 回 JSON，我们宽松解析（strip code fence / 花括号兜底）——
//     DeepSeek 偶尔会套 ```json ... ```。
//   - 点蒸馏出来的曲子 -> 把 picked 当 queue 传进 player，next/prev 在精华里循环。

type Essence = { summary: string; trackIds: number[] };

type DistillState =
  | { kind: "idle" }
  | { kind: "loading" }
  | { kind: "ready"; data: Essence }
  | { kind: "error"; message: string };

function DistillBlock({
  playlistId,
  playlistName,
  updateTime,
  tracks,
  onPlay,
}: {
  playlistId: number;
  playlistName: string;
  updateTime: number | null;
  tracks: TrackInfo[];
  onPlay: (t: TrackInfo, queue?: TrackInfo[]) => Promise<void>;
}) {
  const [state, setState] = useState<DistillState>({ kind: "idle" });

  // updateTime 为 null 时 fallback 到 "na"：即便没拿到 updateTime，至少
  // 同一张歌单同一次 session 里不会重算第二次。
  const cacheKey = `distill:${playlistId}:${updateTime ?? "na"}`;

  useEffect(() => {
    let alive = true;
    setState({ kind: "idle" });
    cache
      .getState(cacheKey)
      .then((raw) => {
        if (!alive || !raw) return;
        try {
          const parsed = JSON.parse(raw) as Essence;
          if (
            parsed &&
            typeof parsed.summary === "string" &&
            Array.isArray(parsed.trackIds)
          ) {
            setState({ kind: "ready", data: parsed });
          }
        } catch {
          /* 坏缓存忽略，点按钮重算 */
        }
      })
      .catch(() => {});
    return () => {
      alive = false;
    };
  }, [cacheKey]);

  const run = async () => {
    setState({ kind: "loading" });
    try {
      const sample = sampleTracks(tracks, 300);
      const lines = sample
        .map((t, i) => {
          const artist = t.artists.map((a) => a.name).join(" / ") || "未知艺人";
          const album = t.album?.name ? ` · ${t.album.name}` : "";
          return `${i + 1}. ${t.id}. ${t.name} — ${artist}${album}`;
        })
        .join("\n");

      const user =
        `歌单名：${playlistName || "未命名"}（共 ${tracks.length} 首，` +
        `${sample.length < tracks.length ? `已采样 ${sample.length} 首` : "全量"}）\n\n` +
        `曲目（序号. neteaseId. 歌名 — 艺人 · 专辑）：\n${lines}\n\n` +
        `任务：\n` +
        `1) 从上面选 ≤20 首最能代表这张歌单"灵魂"的，按审美风味排序，不是按热门度。\n` +
        `2) 给一句 ≤20 个汉字的总结，像独立唱片店老板的短评，不要煽情，不要"这是一张..."这种套话。\n` +
        `严格只输出一行 JSON：{"summary":"...","trackIds":[id1,id2,...]}\n` +
        `trackIds 必须从上面列表的 neteaseId 里挑，不许编。`;

      const raw = await ai.chat({
        system:
          "你是 Claudio 的歌单蒸馏器。只输出 JSON，不要解释，不要代码块包裹，不要任何前后缀文字。",
        user,
        temperature: 0.4,
        maxTokens: 600,
      });

      const parsed = parseEssence(raw);
      if (!parsed) throw new Error("AI 返回的不是合法 JSON");
      if (parsed.trackIds.length === 0) throw new Error("AI 没挑出任何曲目");

      await cache.setState(cacheKey, JSON.stringify(parsed)).catch(() => {});
      setState({ kind: "ready", data: parsed });
    } catch (e) {
      setState({
        kind: "error",
        message: e instanceof Error ? e.message : String(e),
      });
    }
  };

  // 把 trackIds 映射回真实 TrackInfo —— AI 偶尔会幻觉出列表里没有的 id，过滤掉
  const byId = new Map(tracks.map((t) => [t.id, t]));
  const picked: TrackInfo[] =
    state.kind === "ready"
      ? state.data.trackIds
          .map((tid) => byId.get(tid))
          .filter((x): x is TrackInfo => !!x)
      : [];

  return (
    <div
      className="glass"
      style={{
        padding: 16,
        marginBottom: 14,
        border: "1px solid rgba(155,227,198,0.35)",
        background: "rgba(155,227,198,0.05)",
      }}
    >
      <div
        style={{
          display: "flex",
          alignItems: "center",
          justifyContent: "space-between",
          gap: 12,
          flexWrap: "wrap",
        }}
      >
        <div style={{ display: "flex", alignItems: "baseline", gap: 10, minWidth: 0 }}>
          <div style={{ color: "#9be3c6", fontWeight: 700, fontSize: 14 }}>
            ✦ 蒸馏歌单
          </div>
          {state.kind === "ready" && (
            <div
              style={{
                color: "#c9f0dc",
                fontSize: 13,
                overflow: "hidden",
                textOverflow: "ellipsis",
                whiteSpace: "nowrap",
              }}
              title={state.data.summary}
            >
              「{state.data.summary}」
            </div>
          )}
        </div>
        <button
          onClick={run}
          disabled={state.kind === "loading"}
          style={{
            padding: "7px 16px",
            borderRadius: 999,
            border: "1px solid rgba(155,227,198,0.5)",
            background:
              state.kind === "loading"
                ? "rgba(155,227,198,0.06)"
                : "rgba(155,227,198,0.14)",
            color: "#9be3c6",
            fontSize: 12,
            fontWeight: 600,
            cursor: state.kind === "loading" ? "not-allowed" : "pointer",
            whiteSpace: "nowrap",
          }}
        >
          {state.kind === "loading"
            ? "蒸馏中 · · ·"
            : state.kind === "ready"
            ? "重新蒸馏"
            : "开始蒸馏"}
        </button>
      </div>

      {state.kind === "idle" && (
        <div style={{ color: "#8a93a8", fontSize: 12, marginTop: 8, lineHeight: 1.55 }}>
          让 DeepSeek 从这张 {tracks.length} 首的歌单里挑 ≤20 首最有灵魂的，
          再给一句歌单短评。结果按你这张歌单的 updateTime 缓存，不会每次都重算。
        </div>
      )}

      {state.kind === "error" && (
        <div
          style={{
            marginTop: 10,
            padding: "8px 12px",
            borderRadius: 10,
            background: "rgba(255,180,180,0.08)",
            border: "1px solid rgba(255,180,180,0.25)",
            color: "#ffb4b4",
            fontSize: 12,
            lineHeight: 1.5,
          }}
        >
          蒸馏失败：{state.message}
        </div>
      )}

      {state.kind === "ready" && picked.length > 0 && (
        <div style={{ marginTop: 12 }}>
          {picked.map((t, i) => (
            <DistilledRow
              key={t.id}
              index={i + 1}
              track={t}
              onClick={() => void onPlay(t, picked)}
            />
          ))}
        </div>
      )}
    </div>
  );
}

function DistilledRow({
  index,
  track,
  onClick,
}: {
  index: number;
  track: TrackInfo;
  onClick: () => void;
}) {
  return (
    <div
      onClick={onClick}
      style={{
        display: "flex",
        alignItems: "center",
        padding: "8px 10px",
        borderRadius: 8,
        cursor: "pointer",
        gap: 10,
        color: "#e9efff",
        transition: "background 120ms ease",
      }}
      onMouseEnter={(e) =>
        (e.currentTarget.style.background = "rgba(155,227,198,0.09)")
      }
      onMouseLeave={(e) => (e.currentTarget.style.background = "transparent")}
    >
      <div
        style={{
          width: 22,
          textAlign: "right",
          color: "#9be3c6",
          fontSize: 12,
          fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace",
        }}
      >
        {index}
      </div>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div
          style={{
            fontSize: 13,
            fontWeight: 500,
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
            color: "#8a93a8",
            fontSize: 11,
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
          color: "#8a93a8",
          fontSize: 11,
          fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace",
        }}
      >
        {fmtMs(track.durationMs)}
      </div>
    </div>
  );
}

/**
 * 超过 cap 时做分层采样：每个主艺人先保 1 首（覆盖度优先），剩下的随机补齐。
 * 目的是不让 prompt 膨胀到 30k+ token，同时保留歌单的艺人广度 —— 不然
 * 第一个艺人有 50 首的话，随机抽会把它喂满一半。
 */
function sampleTracks(tracks: TrackInfo[], cap: number): TrackInfo[] {
  if (tracks.length <= cap) return tracks;
  const byArtist = new Map<string, TrackInfo[]>();
  for (const t of tracks) {
    const a = t.artists[0]?.name ?? "";
    const arr = byArtist.get(a);
    if (arr) arr.push(t);
    else byArtist.set(a, [t]);
  }
  const kept: TrackInfo[] = [];
  const rest: TrackInfo[] = [];
  for (const arr of byArtist.values()) {
    kept.push(arr[0]);
    for (let i = 1; i < arr.length; i++) rest.push(arr[i]);
  }
  // Fisher-Yates 洗牌
  for (let i = rest.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [rest[i], rest[j]] = [rest[j], rest[i]];
  }
  const need = Math.max(0, cap - kept.length);
  return kept.concat(rest.slice(0, need));
}

function parseEssence(raw: string): Essence | null {
  const stripped = raw
    .trim()
    .replace(/^```(?:json)?\s*/i, "")
    .replace(/\s*```$/i, "")
    .trim();

  const tryParse = (s: string): Essence | null => {
    try {
      const obj = JSON.parse(s) as unknown;
      if (
        obj &&
        typeof obj === "object" &&
        typeof (obj as { summary?: unknown }).summary === "string" &&
        Array.isArray((obj as { trackIds?: unknown }).trackIds)
      ) {
        const o = obj as { summary: string; trackIds: unknown[] };
        return {
          summary: o.summary.trim().slice(0, 40),
          trackIds: o.trackIds
            .map((x) => (typeof x === "number" ? x : Number(x)))
            .filter((x) => Number.isFinite(x) && x > 0)
            .slice(0, 20) as number[],
        };
      }
    } catch {
      /* noop */
    }
    return null;
  };

  const direct = tryParse(stripped);
  if (direct) return direct;
  // 兜底：抓第一个花括号对
  const match = stripped.match(/\{[\s\S]*\}/);
  if (match) return tryParse(match[0]);
  return null;
}
