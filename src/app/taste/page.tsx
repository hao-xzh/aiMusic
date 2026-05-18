"use client";

/**
 * 我的口味（Taste Profile）页。
 *
 * 这一页只渲染缓存里那份 JSON profile：
 *   - 没有就引导去 /distill 选歌单蒸馏
 *   - 有就把 genres / eras / moods / topArtists / culturalContext / taglines / summary
 *     做一个比较有"音乐杂志"感的版式，不堆 card 不堆 chips。
 *
 * 这一份 profile 后续会被 Phase B（库外推荐）当 prompt 上下文吃掉，
 * 也会被 Phase C 的 crossfade 决策当参考 —— 所以页面只是它的可视化层面，
 * 不是它的本体。
 */

import Link from "next/link";
import { useEffect, useState } from "react";
import { BackButton } from "@/components/BackButton";
import { cache, type TrackInfo } from "@/lib/tauri";
import { loadTasteProfile, type TasteProfile } from "@/lib/taste-profile";
import {
  discoverBeyondLibrary,
  type DiscoveryPick,
  type DiscoveryProgress,
} from "@/lib/discovery";
import {
  readBehaviorLog,
  summarize,
  logBehavior,
  type BehaviorEvent,
  type BehaviorStats,
} from "@/lib/behavior-log";
import { cdn } from "@/lib/cdn";
import { usePlayer } from "@/lib/player-state";

export default function TastePage() {
  const [profile, setProfile] = useState<TasteProfile | null | "loading">(
    "loading",
  );

  useEffect(() => {
    let alive = true;
    (async () => {
      const p = await loadTasteProfile();
      if (!alive) return;
      setProfile(p);
    })();
    return () => {
      alive = false;
    };
  }, []);

  return (
    <div
      style={{
        padding: "clamp(8px, 2vw, 16px) clamp(12px, 4vw, 24px) 60px",
        maxWidth: 760,
        margin: "0 auto",
        width: "100%",
      }}
    >
      <div className="safe-top" style={{ display: "flex", alignItems: "center", gap: 12, paddingBottom: 8 }}>
        <BackButton href="/distill" />
        <div style={{ ...brand, marginTop: 0, marginBottom: 0, flex: 1 }}>
          MUSIC PROFILE
        </div>
      </div>

      {profile === "loading" && (
        <div style={loadingStyle}>正在读取你的音乐画像…</div>
      )}

      {profile === null && <EmptyState />}

      {profile && profile !== "loading" && <ProfileView profile={profile} />}
    </div>
  );
}

function EmptyState() {
  return (
    <div style={emptyCard}>
      <div style={{ fontSize: 18, fontWeight: 700, color: "#e9efff" }}>
        还没画过你的音乐画像
      </div>
      <div style={{ color: "#8a93a8", fontSize: 13, marginTop: 8, lineHeight: 1.6 }}>
        去歌单页挑几张能代表你的歌单，按"✦ 蒸馏画像"。
        AI 会把这些歌单读完，把你的音乐性格画成一份结构化画像 ——
        后面的库外推荐和电台模式都基于它。
      </div>
      <Link href="/distill" style={ctaBtn}>
        去挑歌单 →
      </Link>
    </div>
  );
}

function ProfileView({ profile }: { profile: TasteProfile }) {
  return (
    <>
      {/* Hero summary */}
      <div style={heroBlock}>
        <div style={summaryQuote}>「{profile.summary}」</div>
        {profile.taglines.length > 0 && (
          <div style={taglinesRow}>
            {profile.taglines.map((t, i) => (
              <span key={i} style={tagline}>
                {t}
              </span>
            ))}
          </div>
        )}
        <Meta profile={profile} />
      </div>

      {/* 主流派 */}
      {profile.genres.length > 0 && (
        <Section title="主旋律 · 风格">
          <div style={{ display: "flex", flexDirection: "column", gap: 10 }}>
            {profile.genres
              .slice()
              .sort((a, b) => b.weight - a.weight)
              .map((g, i) => (
                <GenreRow key={i} g={g} />
              ))}
          </div>
        </Section>
      )}

      {/* 年代频谱 */}
      {profile.eras.length > 0 && (
        <Section title="年代倾向">
          <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
            {profile.eras
              .slice()
              .sort((a, b) => b.weight - a.weight)
              .map((e, i) => (
                <EraBar key={i} e={e} />
              ))}
          </div>
        </Section>
      )}

      {/* 情绪关键词 */}
      {profile.moods.length > 0 && (
        <Section title="情绪关键词">
          <ChipRow chips={profile.moods} />
        </Section>
      )}

      {/* 文化语境 */}
      {profile.culturalContext.length > 0 && (
        <Section title="文化语境">
          <ChipRow chips={profile.culturalContext} accent />
        </Section>
      )}

      {/* Top 艺人 */}
      {profile.topArtists.length > 0 && (
        <Section title="常听艺人">
          <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
            {profile.topArtists
              .slice()
              .sort((a, b) => b.affinity - a.affinity)
              .map((a, i) => (
                <ArtistRow key={i} a={a} />
              ))}
          </div>
        </Section>
      )}

      <BehaviorSummary />

      <LibraryOrganizer profile={profile} />

      <DiscoverySection profile={profile} />

      <div style={{ marginTop: 24, display: "flex", gap: 10, justifyContent: "center", flexWrap: "wrap" }}>
        <Link href="/distill" style={secondaryBtn}>
          重新挑歌单蒸馏
        </Link>
      </div>
    </>
  );
}

// ---------- 听歌行为摘要 ----------

function BehaviorSummary() {
  const [stats, setStats] = useState<BehaviorStats | null>(null);
  useEffect(() => {
    let alive = true;
    (async () => {
      const events = await readBehaviorLog();
      if (alive) setStats(summarize(events));
    })();
    return () => {
      alive = false;
    };
  }, []);

  if (!stats || stats.total < 5) return null;

  return (
    <div style={behaviorBlock}>
      <div style={sectionTitle}>近期反馈</div>
      <div style={{ display: "flex", flexWrap: "wrap", gap: 24, color: "#e9efff", fontSize: 13 }}>
        <BehaviorStat label="听过" value={String(stats.total)} />
        <BehaviorStat label="完成率" value={`${Math.round(stats.completionRate * 100)}%`} accent />
        <BehaviorStat label="跳过" value={String(stats.skipped)} />
        <BehaviorStat label="中途切走" value={String(stats.manualCuts)} />
      </div>
      {(stats.loveArtists.length > 0 || stats.skipHotArtists.length > 0) && (
        <div style={{ marginTop: 12, display: "flex", flexDirection: "column", gap: 6, fontSize: 12 }}>
          {stats.loveArtists.length > 0 && (
            <div style={{ color: "#9be3c6" }}>
              ❤ 反复完整听：{stats.loveArtists.slice(0, 6).join("、")}
            </div>
          )}
          {stats.skipHotArtists.length > 0 && (
            <div style={{ color: "rgba(255,180,180,0.85)" }}>
              ✕ 反复跳过：{stats.skipHotArtists.slice(0, 6).join("、")}
            </div>
          )}
        </div>
      )}
      <div style={{ marginTop: 10, color: "rgba(233,239,255,0.42)", fontSize: 11 }}>
        重新蒸馏画像时，AI 会把这份近期行为一起读进去，让画像跟着你的听感动态校正。
      </div>
    </div>
  );
}

function BehaviorStat({ label, value, accent }: { label: string; value: string; accent?: boolean }) {
  return (
    <div>
      <div
        style={{
          fontSize: 18,
          fontWeight: 700,
          color: accent ? "#9be3c6" : "#f5f7ff",
          fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace",
        }}
      >
        {value}
      </div>
      <div style={{ fontSize: 11, color: "rgba(233,239,255,0.42)", marginTop: 2 }}>{label}</div>
    </div>
  );
}

const behaviorBlock: React.CSSProperties = {
  marginTop: 24,
  padding: "clamp(14px, 2.6vw, 20px)",
  borderRadius: 14,
  border: "1px solid rgba(233,239,255,0.08)",
  background: "rgba(12,16,24,0.55)",
};

const libraryQueueRow: React.CSSProperties = {
  display: "flex",
  gap: 8,
  flexWrap: "wrap",
  marginTop: 8,
};

const microBtn: React.CSSProperties = {
  height: 30,
  padding: "0 12px",
  borderRadius: 999,
  border: "1px solid rgba(155,227,198,0.22)",
  background: "rgba(155,227,198,0.08)",
  color: "#9be3c6",
  fontSize: 12,
  cursor: "pointer",
};

type LibraryOrganizerState =
  | { kind: "loading" }
  | {
      kind: "ready";
      duplicateGroups: number;
      staleCount: number;
      topArtistShare: number;
      queues: LibraryQueue[];
    }
  | { kind: "empty" };

type LibraryQueue = {
  key: string;
  label: string;
  tracks: TrackInfo[];
};

function LibraryOrganizer({ profile }: { profile: TasteProfile }) {
  const { playNetease } = usePlayer();
  const [state, setState] = useState<LibraryOrganizerState>({ kind: "loading" });

  useEffect(() => {
    let alive = true;
    (async () => {
      const tracks: TrackInfo[] = [];
      for (const pid of profile.sourcePlaylistIds) {
        const detail = await cache.getPlaylistDetail(pid).catch(() => null);
        if (detail) tracks.push(...detail.tracks);
      }
      if (!alive) return;
      if (tracks.length === 0) {
        setState({ kind: "empty" });
        return;
      }
      const bySong = new Map<string, number>();
      const byArtist = new Map<string, number>();
      for (const t of tracks) {
        const primaryArtist = t.artists[0]?.name ?? "Unknown";
        const key = `${normalizeSong(t.name)}::${normalizeSong(primaryArtist)}`;
        bySong.set(key, (bySong.get(key) ?? 0) + 1);
        byArtist.set(primaryArtist, (byArtist.get(primaryArtist) ?? 0) + 1);
      }
      const duplicateGroups = [...bySong.values()].filter((count) => count > 1).length;
      const topArtistShare = Math.max(...byArtist.values()) / tracks.length;
      const events = await readBehaviorLog().catch(() => []);
      const touched = new Set(events.map((ev) => ev.trackId));
      const staleCount = tracks.filter((track) => !touched.has(track.id)).length;
      const queues = buildLibraryQueues(tracks, profile, events);
      if (alive) setState({ kind: "ready", duplicateGroups, staleCount, topArtistShare, queues });
    })();
    return () => {
      alive = false;
    };
  }, [profile.sourcePlaylistIds]);

  if (state.kind === "empty") return null;
  return (
    <Section title="音乐图书管理员">
      {state.kind === "loading" ? (
        <Status text="正在看你的库..." />
      ) : (
        <div style={{ display: "grid", gap: 8 }}>
          <LibraryTip label="重复版本" value={`${state.duplicateGroups} 组`} />
          <LibraryTip label="旧歌复活" value={`${state.staleCount} 首`} />
          <LibraryTip label="歌单集中度" value={`${Math.round(state.topArtistShare * 100)}%`} />
          {state.queues.length > 0 && (
            <div style={libraryQueueRow}>
              {state.queues.map((queue) => (
                <button
                  key={queue.key}
                  style={microBtn}
                  onClick={() => {
                    const [head, ...rest] = queue.tracks;
                    if (head) void playNetease(head, [head, ...rest], { smoothMode: "library" });
                  }}
                >
                  {queue.label}
                </button>
              ))}
            </div>
          )}
        </div>
      )}
    </Section>
  );
}

function LibraryTip({ label, value }: { label: string; value: string }) {
  return (
    <div style={{ display: "flex", justifyContent: "space-between", color: "#e9efff", fontSize: 13 }}>
      <span style={{ color: "#8a93a8" }}>{label}</span>
      <span>{value}</span>
    </div>
  );
}

function normalizeSong(value: string): string {
  return value.toLowerCase().replace(/\s+/g, "").replace(/[()（）\[\]【】"'’‘“”\-—_,，。.、]/g, "");
}

function buildLibraryQueues(
  tracks: TrackInfo[],
  profile: TasteProfile,
  events: BehaviorEvent[],
): LibraryQueue[] {
  const touched = new Set(events.map((ev) => ev.trackId));
  const stale = tracks.filter((track) => !touched.has(track.id));
  const profileWords = [
    ...profile.moods,
    ...profile.genres.map((g) => g.tag),
    ...profile.culturalContext,
  ].map((v) => v.toLowerCase());
  const stableByArtist = diverseByArtist(stale.length >= 8 ? stale : tracks);
  const queues: LibraryQueue[] = [
    { key: "revive", label: "旧歌", tracks: stableByArtist.slice(0, 18) },
    { key: "work", label: "工作", tracks: scoreQueue(tracks, profileWords.concat(["ambient", "chill", "indie", "lofi", "民谣", "器乐", "轻"])) },
    { key: "drive", label: "开车", tracks: scoreQueue(tracks, profileWords.concat(["rock", "pop", "city", "funk", "律动", "公路"])) },
    { key: "night", label: "睡前", tracks: scoreQueue(tracks, ["night", "moon", "rain", "dream", "blue", "acoustic", "piano", "夜", "月", "雨", "梦"]) },
  ];
  return queues
    .map((queue) => ({ ...queue, tracks: queue.tracks.slice(0, 20) }))
    .filter((queue) => queue.tracks.length >= 4);
}

function scoreQueue(tracks: TrackInfo[], words: string[]): TrackInfo[] {
  return tracks
    .map((track) => {
      const haystack = `${track.name} ${track.artists.map((a) => a.name).join(" ")} ${track.album?.name ?? ""}`.toLowerCase();
      const score = words.reduce((sum, word) => sum + (word && haystack.includes(word) ? 1 : 0), 0);
      return { track, score };
    })
    .sort((a, b) => b.score - a.score || stableTrackRank(a.track) - stableTrackRank(b.track))
    .map((item) => item.track);
}

function diverseByArtist(tracks: TrackInfo[]): TrackInfo[] {
  const seen = new Map<string, number>();
  return tracks
    .slice()
    .sort((a, b) => stableTrackRank(a) - stableTrackRank(b))
    .sort((a, b) => {
      const aa = a.artists[0]?.name ?? "";
      const bb = b.artists[0]?.name ?? "";
      return (seen.get(aa) ?? 0) - (seen.get(bb) ?? 0);
    })
    .filter((track) => {
      const artist = track.artists[0]?.name ?? "";
      const count = seen.get(artist) ?? 0;
      seen.set(artist, count + 1);
      return count < 2;
    });
}

function stableTrackRank(track: TrackInfo): number {
  return (track.id * 2654435761) >>> 0;
}

function tasteRadarWeekKey(ids: Array<string | number>): string {
  const now = new Date();
  const start = new Date(now.getFullYear(), 0, 1);
  const week = Math.ceil((((now.getTime() - start.getTime()) / 86400000) + start.getDay() + 1) / 7);
  return `claudio-taste-radar:${now.getFullYear()}-${week}:${ids.join(",")}`;
}

// ---------- 库外推荐区 ----------

type DiscoveryState =
  | { kind: "idle" }
  | { kind: "loading-library" }
  | { kind: "running"; progress: DiscoveryProgress }
  | { kind: "ready"; picks: DiscoveryPick[] }
  | { kind: "error"; message: string };

type RadarVote = "up" | "down";
type RadarFeedback = Record<string, RadarVote>;

function DiscoverySection({ profile }: { profile: TasteProfile }) {
  const { playNetease } = usePlayer();
  const [state, setState] = useState<DiscoveryState>({ kind: "idle" });
  const [feedback, setFeedback] = useState<RadarFeedback>({});

  useEffect(() => {
    setFeedback(readRadarFeedback(profile));
  }, [profile.sourceHash]);

  const run = async (force = false) => {
    setState({ kind: "loading-library" });
    try {
      // 拿用户库里所有曲目 id（来自 profile.sourcePlaylistIds）作为"已有"集合
      const owned = new Set<number>();
      const libraryTracks: TrackInfo[] = [];
      for (const pid of profile.sourcePlaylistIds) {
        const d = await cache.getPlaylistDetail(pid).catch(() => null);
        if (d) {
          for (const t of d.tracks) {
            owned.add(t.id);
            libraryTracks.push(t);
          }
        }
      }
      const behavior = await readBehaviorLog().catch(() => []);

      setState({ kind: "running", progress: { phase: "seeding" } });
      const outsidePicks = await discoverBeyondLibrary(profile, owned, {
        finalCount: 9,
        onProgress: (p) => setState({ kind: "running", progress: p }),
      });
      const revivalPicks = buildRevivalPicks(libraryTracks, behavior, 3);
      const picks = mergeRadarPicks(outsidePicks, revivalPicks, 12);
      writeRadarCache(profile, picks);
      setState({ kind: "ready", picks });
    } catch (e) {
      setState({
        kind: "error",
        message: e instanceof Error ? e.message : String(e),
      });
      if (!force) {
        const cached = readRadarCache(profile);
        if (cached.length > 0) setState({ kind: "ready", picks: cached });
      }
    }
  };

  useEffect(() => {
    if (state.kind !== "idle") return;
    const cached = readRadarCache(profile);
    if (cached.length > 0) {
      setState({ kind: "ready", picks: cached });
      return;
    }
    const weekKey = tasteRadarWeekKey(profile.sourcePlaylistIds);
    if (window.localStorage.getItem(weekKey) === "1") return;
    window.localStorage.setItem(weekKey, "1");
    void run();
    // 只在首次进入本周 radar 时自动跑一次。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [profile.sourcePlaylistIds, state.kind]);

  const playAll = async () => {
    if (state.kind !== "ready" || state.picks.length === 0) return;
    const queue: TrackInfo[] = state.picks
      .filter((p) => feedback[String(p.track.id)] !== "down")
      .map((p) => p.track);
    if (queue.length === 0) return;
    // 库外推荐 = 算法挑的"丰富感"歌单，走 discovery 排序
    await playNetease(queue[0], queue, { smoothMode: "discovery" });
  };

  const playOne = async (pick: DiscoveryPick) => {
    if (state.kind !== "ready") return;
    const queue: TrackInfo[] = state.picks.map((p) => p.track);
    await playNetease(pick.track, queue, { smoothMode: "discovery" });
  };

  const vote = (pick: DiscoveryPick, nextVote: RadarVote) => {
    const key = String(pick.track.id);
    const next = { ...feedback, [key]: nextVote };
    setFeedback(next);
    writeRadarFeedback(profile, next);
    void logBehavior({
      trackId: pick.track.id,
      title: pick.track.name,
      artist: pick.track.artists.map((a) => a.name).join(" / ") || "未知",
      kind: nextVote === "up" ? "liked" : "disliked",
    }).catch(() => {});
  };

  return (
    <div style={{ marginTop: 24, marginBottom: 24 }}>
      <div style={discoveryHeader}>
        <div>
          <div style={{ fontSize: 16, fontWeight: 700, color: "#f5f7ff" }}>
            Taste Radar
          </div>
          <div style={{ color: "#8a93a8", fontSize: 12, marginTop: 4, lineHeight: 1.55 }}>
            每周自动刷新 12 首：库外发现、旧歌复活、相邻艺人。
          </div>
        </div>
        {state.kind === "idle" && (
          <button onClick={() => void run()} style={primaryBtn}>
            为我推荐 →
          </button>
        )}
        {state.kind === "ready" && (
          <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
            <button onClick={playAll} style={primaryBtn}>
              ▶ 全部进电台
            </button>
            <button onClick={() => void run(true)} style={secondaryBtn}>
              换一批
            </button>
          </div>
        )}
      </div>

      {state.kind === "loading-library" && (
        <Status text="正在汇总你的库..." />
      )}
      {state.kind === "running" && (
        <Status text={progressText(state.progress)} />
      )}
      {state.kind === "error" && (
        <div style={errorBlock}>推荐失败：{state.message}</div>
      )}
      {state.kind === "ready" && (
        <PickList picks={state.picks} feedback={feedback} onPlay={playOne} onVote={vote} />
      )}
    </div>
  );
}

function buildRevivalPicks(
  tracks: TrackInfo[],
  events: BehaviorEvent[],
  count: number,
): DiscoveryPick[] {
  if (tracks.length === 0) return [];
  const touched = new Set(events.map((ev) => ev.trackId));
  return tracks
    .filter((track) => !touched.has(track.id))
    .sort((a, b) => stableTrackRank(a) - stableTrackRank(b))
    .slice(0, count)
    .map((track) => ({
      track,
      fromSeed: "library-revival",
    }));
}

function mergeRadarPicks(
  outside: DiscoveryPick[],
  revivals: DiscoveryPick[],
  limit: number,
): DiscoveryPick[] {
  const seen = new Set<number>();
  const out: DiscoveryPick[] = [];
  for (const pick of [...outside.slice(0, 6), ...revivals, ...outside.slice(6)]) {
    if (seen.has(pick.track.id)) continue;
    seen.add(pick.track.id);
    out.push(pick);
    if (out.length >= limit) break;
  }
  return out;
}

function radarCacheKey(profile: TasteProfile): string {
  return `${tasteRadarWeekKey(profile.sourcePlaylistIds)}:picks:v2:${profile.sourceHash}`;
}

function radarFeedbackKey(profile: TasteProfile): string {
  return `claudio-taste-radar-feedback:v1:${profile.sourceHash}`;
}

function readRadarCache(profile: TasteProfile): DiscoveryPick[] {
  try {
    const raw = window.localStorage.getItem(radarCacheKey(profile));
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];
    return parsed.filter((p) => p?.track?.id && p?.track?.name).slice(0, 12);
  } catch {
    return [];
  }
}

function writeRadarCache(profile: TasteProfile, picks: DiscoveryPick[]) {
  try {
    window.localStorage.setItem(radarCacheKey(profile), JSON.stringify(picks.slice(0, 12)));
  } catch {}
}

function readRadarFeedback(profile: TasteProfile): RadarFeedback {
  try {
    const raw = window.localStorage.getItem(radarFeedbackKey(profile));
    const parsed = raw ? JSON.parse(raw) : {};
    return parsed && typeof parsed === "object" ? parsed : {};
  } catch {
    return {};
  }
}

function writeRadarFeedback(profile: TasteProfile, feedback: RadarFeedback) {
  try {
    window.localStorage.setItem(radarFeedbackKey(profile), JSON.stringify(feedback));
  } catch {}
}

function progressText(p: DiscoveryProgress): string {
  switch (p.phase) {
    case "seeding":
      return "AI 正在出搜索 seed…";
    case "searching":
      return `搜索候选中（${p.done + 1}/${p.total}）：${p.query}`;
    case "ranking":
      return `AI 从 ${p.candidateCount} 首候选里挑最贴你口味的…`;
    case "done":
      return "完成";
  }
}

function Status({ text }: { text: string }) {
  return (
    <div
      style={{
        padding: "14px 18px",
        borderRadius: 12,
        background: "rgba(155,227,198,0.06)",
        border: "1px solid rgba(155,227,198,0.2)",
        color: "#9be3c6",
        fontSize: 13,
        lineHeight: 1.55,
      }}
    >
      {text}
    </div>
  );
}

function PickList({
  picks,
  feedback,
  onPlay,
  onVote,
}: {
  picks: DiscoveryPick[];
  feedback: RadarFeedback;
  onPlay: (p: DiscoveryPick) => void;
  onVote: (p: DiscoveryPick, vote: RadarVote) => void;
}) {
  if (picks.length === 0) {
    return <Status text="AI 没挑出来 —— 可能候选池没合适的，换一批试试" />;
  }
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 10 }}>
      {picks.map((p, i) => (
        <PickRow
          key={p.track.id}
          pick={p}
          index={i + 1}
          vote={feedback[String(p.track.id)]}
          onPlay={onPlay}
          onVote={onVote}
        />
      ))}
    </div>
  );
}

function PickRow({
  pick,
  index,
  vote,
  onPlay,
  onVote,
}: {
  pick: DiscoveryPick;
  index: number;
  vote?: RadarVote;
  onPlay: (p: DiscoveryPick) => void;
  onVote: (p: DiscoveryPick, vote: RadarVote) => void;
}) {
  const { track } = pick;
  const cover = track.album?.picUrl;
  const artist = track.artists.map((a) => a.name).join(" / ") || "未知";
  return (
    <div style={{ ...pickRowStyle, opacity: vote === "down" ? 0.52 : 1 }}>
      <div style={{ width: 22, color: "rgba(233,239,255,0.42)", fontSize: 12, fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace", textAlign: "right" }}>
        {index}
      </div>
      {cover ? (
        // eslint-disable-next-line @next/next/no-img-element
        <img
          src={cdn(cover)}
          alt=""
          width={44}
          height={44}
          style={{ width: 44, height: 44, borderRadius: 6, objectFit: "cover", flexShrink: 0 }}
        />
      ) : (
        <div style={{ width: 44, height: 44, borderRadius: 6, background: "rgba(155,227,198,0.12)", flexShrink: 0 }} />
      )}
      <div style={{ flex: 1, minWidth: 0 }}>
        <div
          style={{ color: "#f5f7ff", fontSize: 14, fontWeight: 600, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}
          title={track.name}
        >
          {track.name}
        </div>
        <div
          style={{ color: "#8a93a8", fontSize: 12, marginTop: 2, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}
          title={artist}
        >
          {artist}
        </div>
      </div>
      <button
        onClick={() => onPlay(pick)}
        aria-label="播放"
        style={playBtn}
      >
        <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor" stroke="currentColor" strokeWidth="2.4" strokeLinejoin="round" aria-hidden>
          <path d="M8 5.5v13l11-6.5z" />
        </svg>
      </button>
      <div style={voteGroup}>
        <button
          style={{ ...voteBtn, ...(vote === "up" ? voteBtnActive : null) }}
          onClick={() => onVote(pick, "up")}
        >
          准
        </button>
        <button
          style={{ ...voteBtn, ...(vote === "down" ? voteBtnActiveBad : null) }}
          onClick={() => onVote(pick, "down")}
        >
          不准
        </button>
      </div>
    </div>
  );
}

const discoveryHeader: React.CSSProperties = {
  display: "flex",
  justifyContent: "space-between",
  alignItems: "flex-start",
  gap: 14,
  flexWrap: "wrap",
  marginBottom: 14,
};

const primaryBtn: React.CSSProperties = {
  padding: "9px 18px",
  borderRadius: 999,
  border: "1px solid rgba(155,227,198,0.5)",
  background: "rgba(155,227,198,0.16)",
  color: "#9be3c6",
  fontSize: 13,
  fontWeight: 600,
  cursor: "pointer",
  whiteSpace: "nowrap",
};

const errorBlock: React.CSSProperties = {
  padding: "12px 16px",
  borderRadius: 12,
  background: "rgba(255,180,180,0.08)",
  border: "1px solid rgba(255,180,180,0.25)",
  color: "#ffb4b4",
  fontSize: 13,
  lineHeight: 1.5,
};

const pickRowStyle: React.CSSProperties = {
  display: "flex",
  alignItems: "center",
  gap: 12,
  padding: "10px 12px",
  borderRadius: 12,
  background: "rgba(12,16,24,0.55)",
  border: "1px solid rgba(233,239,255,0.06)",
};

const playBtn: React.CSSProperties = {
  width: 36,
  height: 36,
  borderRadius: 999,
  border: "1px solid rgba(155,227,198,0.4)",
  background: "rgba(155,227,198,0.1)",
  color: "#9be3c6",
  display: "inline-flex",
  alignItems: "center",
  justifyContent: "center",
  cursor: "pointer",
  flexShrink: 0,
};

const voteGroup: React.CSSProperties = {
  display: "flex",
  gap: 5,
  flexShrink: 0,
};

const voteBtn: React.CSSProperties = {
  height: 28,
  padding: "0 9px",
  borderRadius: 999,
  border: "1px solid rgba(233,239,255,0.10)",
  background: "rgba(255,255,255,0.04)",
  color: "rgba(233,239,255,0.62)",
  fontSize: 11,
  cursor: "pointer",
};

const voteBtnActive: React.CSSProperties = {
  borderColor: "rgba(155,227,198,0.44)",
  background: "rgba(155,227,198,0.14)",
  color: "#9be3c6",
};

const voteBtnActiveBad: React.CSSProperties = {
  borderColor: "rgba(255,180,180,0.34)",
  background: "rgba(255,180,180,0.10)",
  color: "rgba(255,180,180,0.88)",
};

function Meta({ profile }: { profile: TasteProfile }) {
  const date = new Date(profile.derivedAt * 1000);
  const text = `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
  return (
    <div style={metaRow}>
      基于 {profile.sourcePlaylistCount} 张歌单 · 共 {profile.totalTrackCount} 首 ·
      采样 {profile.sampledTrackCount} · 蒸馏于 {text}
    </div>
  );
}
const pad = (n: number) => n.toString().padStart(2, "0");

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div style={sectionBlock}>
      <div style={sectionTitle}>{title}</div>
      <div>{children}</div>
    </div>
  );
}

function GenreRow({ g }: { g: { tag: string; weight: number; examples: string[] } }) {
  return (
    <div style={{ display: "flex", alignItems: "center", gap: 12 }}>
      <div style={{ flex: "0 0 auto", width: "30%", minWidth: 100 }}>
        <div style={{ fontWeight: 600, color: "#f5f7ff", fontSize: 14 }}>
          {g.tag}
        </div>
        {g.examples.length > 0 && (
          <div
            style={{
              color: "#8a93a8",
              fontSize: 11,
              marginTop: 2,
              overflow: "hidden",
              textOverflow: "ellipsis",
              whiteSpace: "nowrap",
            }}
            title={g.examples.join(" / ")}
          >
            {g.examples.slice(0, 3).join(" / ")}
          </div>
        )}
      </div>
      <div style={weightTrack}>
        <div style={{ ...weightFill, width: `${Math.round(g.weight * 100)}%` }} />
      </div>
      <div style={weightLabel}>{Math.round(g.weight * 100)}</div>
    </div>
  );
}

function EraBar({ e }: { e: { label: string; weight: number } }) {
  return (
    <div style={{ display: "flex", alignItems: "center", gap: 12 }}>
      <div style={{ width: 90, color: "#e9efff", fontSize: 13, fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace" }}>
        {e.label}
      </div>
      <div style={weightTrack}>
        <div
          style={{
            ...weightFill,
            width: `${Math.round(e.weight * 100)}%`,
            background: "rgba(255,210,138,0.78)",
          }}
        />
      </div>
      <div style={weightLabel}>{Math.round(e.weight * 100)}</div>
    </div>
  );
}

function ArtistRow({ a }: { a: { name: string; affinity: number } }) {
  return (
    <div style={{ display: "flex", alignItems: "center", gap: 12 }}>
      <div style={{ flex: "0 0 auto", width: "40%", minWidth: 110, color: "#f5f7ff", fontSize: 14, fontWeight: 500, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }} title={a.name}>
        {a.name}
      </div>
      <div style={weightTrack}>
        <div
          style={{
            ...weightFill,
            width: `${Math.round(a.affinity * 100)}%`,
            background: "rgba(155,227,198,0.85)",
          }}
        />
      </div>
      <div style={weightLabel}>{Math.round(a.affinity * 100)}</div>
    </div>
  );
}

function ChipRow({ chips, accent }: { chips: string[]; accent?: boolean }) {
  return (
    <div style={{ display: "flex", flexWrap: "wrap", gap: 8 }}>
      {chips.map((c, i) => (
        <span
          key={i}
          style={{
            padding: "6px 12px",
            borderRadius: 999,
            border: accent
              ? "1px solid rgba(255,210,138,0.4)"
              : "1px solid rgba(233,239,255,0.18)",
            background: accent
              ? "rgba(255,210,138,0.1)"
              : "rgba(233,239,255,0.04)",
            color: accent ? "#ffd28a" : "#e9efff",
            fontSize: 12,
            letterSpacing: 0.2,
          }}
        >
          {c}
        </span>
      ))}
    </div>
  );
}

// ---------- styles ----------

const brand: React.CSSProperties = {
  textAlign: "center",
  fontSize: 11,
  letterSpacing: 4,
  color: "rgba(233,239,255,0.42)",
  fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace",
  marginTop: "clamp(12px, 3vh, 24px)",
  marginBottom: 22,
  textTransform: "uppercase",
};

const loadingStyle: React.CSSProperties = {
  textAlign: "center",
  color: "#8a93a8",
  fontSize: 13,
  padding: 40,
};

const emptyCard: React.CSSProperties = {
  padding: "clamp(20px, 4vw, 36px)",
  textAlign: "center",
  background: "rgba(12,16,24,0.55)",
  border: "1px solid rgba(233,239,255,0.08)",
  borderRadius: 16,
  marginTop: 30,
};

const ctaBtn: React.CSSProperties = {
  display: "inline-block",
  marginTop: 18,
  padding: "10px 22px",
  borderRadius: 999,
  border: "1px solid rgba(155,227,198,0.5)",
  background: "rgba(155,227,198,0.14)",
  color: "#9be3c6",
  textDecoration: "none",
  fontSize: 13,
  fontWeight: 600,
};

const heroBlock: React.CSSProperties = {
  textAlign: "center",
  padding: "clamp(20px, 4vw, 36px) clamp(12px, 3vw, 24px)",
  marginBottom: 28,
};

const summaryQuote: React.CSSProperties = {
  fontSize: "clamp(20px, 5vw, 28px)",
  fontWeight: 600,
  color: "#f5f7ff",
  lineHeight: 1.4,
  letterSpacing: -0.2,
};

const taglinesRow: React.CSSProperties = {
  marginTop: 12,
  display: "flex",
  justifyContent: "center",
  flexWrap: "wrap",
  gap: 8,
};

const tagline: React.CSSProperties = {
  color: "rgba(233,239,255,0.7)",
  fontSize: 13,
  fontStyle: "italic",
};

const metaRow: React.CSSProperties = {
  marginTop: 18,
  color: "rgba(233,239,255,0.4)",
  fontSize: 11,
  fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace",
  letterSpacing: 0.4,
};

const sectionBlock: React.CSSProperties = {
  marginBottom: 26,
};

const sectionTitle: React.CSSProperties = {
  color: "rgba(233,239,255,0.42)",
  fontSize: 11,
  letterSpacing: 2.2,
  textTransform: "uppercase",
  marginBottom: 12,
  fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace",
};

const weightTrack: React.CSSProperties = {
  flex: 1,
  height: 6,
  borderRadius: 999,
  background: "rgba(233,239,255,0.06)",
  overflow: "hidden",
};

const weightFill: React.CSSProperties = {
  height: "100%",
  background: "rgba(245,247,255,0.85)",
  borderRadius: 999,
  transition: "width 320ms ease",
};

const weightLabel: React.CSSProperties = {
  width: 28,
  textAlign: "right",
  color: "rgba(233,239,255,0.5)",
  fontSize: 11,
  fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace",
};

const secondaryBtn: React.CSSProperties = {
  padding: "9px 18px",
  borderRadius: 999,
  border: "1px solid rgba(233,239,255,0.18)",
  background: "transparent",
  color: "#e9efff",
  textDecoration: "none",
  fontSize: 12,
};
