"use client";

import Link from "next/link";
import { useEffect, useMemo, useRef, useState } from "react";

import { BackButton } from "@/components/BackButton";
import { netease, wrapAudioUrl, type ExportedTrack, type TrackInfo } from "@/lib/tauri";
import { usePlatform } from "@/lib/use-platform";

type Tone = "idle" | "ok" | "warn" | "err";

const LEVELS = [
  { value: "exhigh", label: "320k MP3" },
  { value: "higher", label: "较高" },
  { value: "standard", label: "标准" },
  { value: "lossless", label: "无损源" },
];

export default function ExportPage() {
  const [loggedIn, setLoggedIn] = useState<boolean | null>(null);
  const [query, setQuery] = useState("");
  const [level, setLevel] = useState("exhigh");
  const [limit, setLimit] = useState(20);
  const [outDir, setOutDir] = useState("");
  const [ffmpeg, setFfmpeg] = useState("ffmpeg");
  const [overwrite, setOverwrite] = useState(false);
  const [tracks, setTracks] = useState<TrackInfo[]>([]);
  const [busy, setBusy] = useState<"search" | `preview:${number}` | `export:${number}` | null>(null);
  const [status, setStatus] = useState<{ tone: Tone; text: string }>({
    tone: "idle",
    text: "准备好了。",
  });
  const [lastExport, setLastExport] = useState<ExportedTrack | null>(null);
  const audioRef = useRef<HTMLAudioElement | null>(null);

  useEffect(() => {
    let alive = true;
    netease
      .isLoggedIn()
      .then((ok) => {
        if (alive) setLoggedIn(ok);
      })
      .catch((e) => {
        if (!alive) return;
        setLoggedIn(false);
        setStatus({ tone: "err", text: messageOf(e) });
      });
    return () => {
      alive = false;
    };
  }, []);

  const canSearch = query.trim().length > 0 && busy !== "search";

  async function search() {
    const q = query.trim();
    if (!q) {
      setStatus({ tone: "warn", text: "请先输入歌曲名或歌手。" });
      return;
    }
    setBusy("search");
    setTracks([]);
    setLastExport(null);
    setStatus({ tone: "idle", text: "正在搜索..." });
    try {
      const list = await netease.search(q, limit);
      setTracks(list);
      setStatus({ tone: "ok", text: `找到 ${list.length} 首候选歌曲。` });
    } catch (e) {
      setStatus({ tone: "err", text: messageOf(e) });
    } finally {
      setBusy(null);
    }
  }

  async function preview(track: TrackInfo) {
    setBusy(`preview:${track.id}`);
    setStatus({ tone: "idle", text: `正在获取「${track.name}」的播放地址...` });
    try {
      const urls = await netease.songUrls([track.id], level);
      const item = urls.find((url) => url.id === track.id);
      if (!item?.url) {
        setStatus({ tone: "warn", text: "这首歌没有可播放地址，可能没有权限或当前音质不可用。" });
        return;
      }
      const player = audioRef.current;
      if (player) {
        player.src = wrapAudioUrl(track.id, item.url);
        await player.play().catch(() => undefined);
      }
      setStatus({ tone: "ok", text: `正在试听：${trackTitle(track)}` });
    } catch (e) {
      setStatus({ tone: "err", text: messageOf(e) });
    } finally {
      setBusy(null);
    }
  }

  async function exportMp3(track: TrackInfo) {
    setBusy(`export:${track.id}`);
    setLastExport(null);
    setStatus({ tone: "idle", text: `正在导出：${trackTitle(track)}` });
    try {
      const result = await netease.exportMp3({
        id: track.id,
        level,
        outDir: outDir.trim() || undefined,
        ffmpeg: ffmpeg.trim() || undefined,
        overwrite,
        name: trackFileName(track),
        openFolder: true,
      });
      setLastExport(result);
      const detail = result.converted
        ? `已从 ${result.sourceFormat.toUpperCase()} 转为 MP3`
        : "已直接保存 MP3";
      const folder = result.openedFolder
        ? "已打开文件所在文件夹。"
        : `文件已保存，但打开文件夹失败：${result.openFolderError || "未知原因"}`;
      setStatus({ tone: result.openedFolder ? "ok" : "warn", text: `${detail}\n${folder}` });
    } catch (e) {
      setStatus({ tone: "err", text: messageOf(e) });
    } finally {
      setBusy(null);
    }
  }

  const emptyText = useMemo(() => {
    if (loggedIn === false) return "需要先登录网易云。";
    if (tracks.length === 0) return "搜索后会在这里显示候选歌曲。";
    return "";
  }, [loggedIn, tracks.length]);

  return (
    <>
      <ExportTopBar />
      <main style={pageWrap}>
        <header style={heading}>
          <div style={eyebrow}>NETEASE</div>
          <h1 style={title}>曲库导出</h1>
        </header>

        {loggedIn === false && (
          <section style={loginBand}>
            <div>
              <div style={bandTitle}>网易云未登录</div>
              <div style={bandDesc}>登录后才能搜索、试听和导出可播放音频。</div>
            </div>
            <Link href="/login" style={primaryLink}>
              去登录
            </Link>
          </section>
        )}

        <section style={toolBand}>
          <div style={searchGrid}>
            <label style={fieldWide}>
              <span style={labelText}>搜索</span>
              <input
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === "Enter") void search();
                }}
                placeholder="歌曲名 / 歌手"
                style={inputStyle}
              />
            </label>
            <label style={fieldStyle}>
              <span style={labelText}>音质</span>
              <select value={level} onChange={(e) => setLevel(e.target.value)} style={inputStyle}>
                {LEVELS.map((item) => (
                  <option key={item.value} value={item.value}>
                    {item.label}
                  </option>
                ))}
              </select>
            </label>
            <label style={fieldStyle}>
              <span style={labelText}>数量</span>
              <input
                value={String(limit)}
                onChange={(e) => setLimit(clampNumber(e.target.value, 1, 50, 20))}
                inputMode="numeric"
                style={inputStyle}
              />
            </label>
          </div>
          <div style={actionRow}>
            <button
              type="button"
              onClick={() => void search()}
              disabled={!canSearch || loggedIn === false}
              style={{ ...buttonStyle, ...primaryButton }}
            >
              <SearchIcon />
              搜索
            </button>
            <button
              type="button"
              onClick={() => {
                setQuery("");
                setTracks([]);
                setLastExport(null);
                setStatus({ tone: "idle", text: "准备好了。" });
              }}
              style={buttonStyle}
            >
              清空
            </button>
          </div>
        </section>

        <section style={toolBand}>
          <div style={exportGrid}>
            <label style={fieldWide}>
              <span style={labelText}>保存目录</span>
              <input
                value={outDir}
                onChange={(e) => setOutDir(e.target.value)}
                placeholder="留空：系统音乐目录 / Pipo Exports"
                style={inputStyle}
              />
            </label>
            <label style={fieldStyle}>
              <span style={labelText}>ffmpeg</span>
              <input value={ffmpeg} onChange={(e) => setFfmpeg(e.target.value)} style={inputStyle} />
            </label>
            <label style={fieldStyle}>
              <span style={labelText}>覆盖选项</span>
              <select
                value={overwrite ? "yes" : "no"}
                onChange={(e) => setOverwrite(e.target.value === "yes")}
                style={inputStyle}
              >
                <option value="no">不覆盖已有文件</option>
                <option value="yes">覆盖已有文件</option>
              </select>
            </label>
          </div>
        </section>

        <section style={statusStyle(status.tone)}>{status.text}</section>

        <audio ref={audioRef} controls style={audioStyle} />

        {lastExport && (
          <section style={exportResult}>
            <div style={resultLabel}>最近导出</div>
            <div style={resultPath}>{lastExport.path}</div>
          </section>
        )}

        <section style={resultsBand}>
          {tracks.length === 0 ? (
            <div style={emptyState}>{emptyText}</div>
          ) : (
            tracks.map((track) => (
              <TrackRow
                key={track.id}
                track={track}
                busy={busy}
                onPreview={preview}
                onExport={exportMp3}
              />
            ))
          )}
        </section>
      </main>
    </>
  );
}

function ExportTopBar() {
  const platform = usePlatform();
  const isMac = platform === "mac";
  const isWin = platform === "windows";
  const isAndroid = platform === "android";
  const safeTop = isAndroid ? "max(env(safe-area-inset-top), 28px)" : "0px";
  const barHeight = isAndroid ? `calc(${safeTop} + 48px)` : "40px";
  const contentTop = isAndroid ? safeTop : "4px";
  const leftInset = isMac ? 112 : 18;
  const rightInset = isWin ? 158 : 24;

  return (
    <div
      data-tauri-drag-region
      style={{
        position: "fixed",
        top: 0,
        left: 0,
        right: 0,
        height: barHeight,
        display: "flex",
        alignItems: "center",
        paddingTop: contentTop,
        paddingLeft: leftInset,
        paddingRight: rightInset,
        boxSizing: "border-box",
        background: "rgba(7,9,15,0.72)",
        backdropFilter: "blur(18px)",
        zIndex: 40,
      }}
    >
      <BackButton href="/distill" />
    </div>
  );
}

function TrackRow({
  track,
  busy,
  onPreview,
  onExport,
}: {
  track: TrackInfo;
  busy: string | null;
  onPreview: (track: TrackInfo) => void | Promise<void>;
  onExport: (track: TrackInfo) => void | Promise<void>;
}) {
  const previewBusy = busy === `preview:${track.id}`;
  const exportBusy = busy === `export:${track.id}`;
  return (
    <div style={trackRow}>
      <div style={{ minWidth: 0 }}>
        <div style={trackName}>{track.name}</div>
        <div style={trackMeta}>
          {artistText(track)}
          {albumText(track)}
          {durationText(track.durationMs)}
        </div>
      </div>
      <div style={rowActions}>
        <button
          type="button"
          onClick={() => void onPreview(track)}
          disabled={!!busy}
          style={buttonStyle}
        >
          <PlaySmallIcon />
          {previewBusy ? "获取中" : "试听"}
        </button>
        <button
          type="button"
          onClick={() => void onExport(track)}
          disabled={!!busy}
          style={{ ...buttonStyle, ...primaryButton }}
        >
          <DownloadIcon />
          {exportBusy ? "导出中" : "导出"}
        </button>
      </div>
    </div>
  );
}

function artistText(track: TrackInfo): string {
  return track.artists.map((artist) => artist.name).filter(Boolean).join(", ") || "未知歌手";
}

function albumText(track: TrackInfo): string {
  return track.album?.name ? ` · ${track.album.name}` : "";
}

function durationText(ms: number): string {
  if (!ms) return "";
  const s = Math.round(ms / 1000);
  return ` · ${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
}

function trackTitle(track: TrackInfo): string {
  return `${artistText(track)} - ${track.name}`;
}

function trackFileName(track: TrackInfo): string {
  return trackTitle(track);
}

function clampNumber(value: string, min: number, max: number, fallback: number): number {
  const n = Number.parseInt(value, 10);
  if (!Number.isFinite(n)) return fallback;
  return Math.max(min, Math.min(max, n));
}

function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

function SearchIcon() {
  return (
    <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <circle cx="11" cy="11" r="7" />
      <path d="M20 20l-3.5-3.5" />
    </svg>
  );
}

function PlaySmallIcon() {
  return (
    <svg width="15" height="15" viewBox="0 0 24 24" fill="currentColor" aria-hidden>
      <path d="M8 5v14l11-7z" />
    </svg>
  );
}

function DownloadIcon() {
  return (
    <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <path d="M12 3v11" />
      <path d="M7 10l5 5 5-5" />
      <path d="M5 21h14" />
    </svg>
  );
}

const pageWrap: React.CSSProperties = {
  padding: "64px 24px 64px",
  maxWidth: 1080,
  margin: "0 auto",
  width: "100%",
  boxSizing: "border-box",
};

const heading: React.CSSProperties = {
  padding: "14px 0 18px",
};

const eyebrow: React.CSSProperties = {
  color: "#9be3c6",
  fontSize: 11,
  fontWeight: 800,
  letterSpacing: 0,
  fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace",
};

const title: React.CSSProperties = {
  margin: "5px 0 0",
  color: "#e9efff",
  fontSize: 24,
  lineHeight: 1.1,
  letterSpacing: 0,
};

const toolBand: React.CSSProperties = {
  borderTop: "1px solid rgba(233,239,255,0.08)",
  padding: "18px 0",
};

const searchGrid: React.CSSProperties = {
  display: "grid",
  gridTemplateColumns: "repeat(auto-fit, minmax(min(100%, 180px), 1fr))",
  gap: 12,
};

const exportGrid: React.CSSProperties = {
  display: "grid",
  gridTemplateColumns: "repeat(auto-fit, minmax(min(100%, 180px), 1fr))",
  gap: 12,
};

const fieldStyle: React.CSSProperties = {
  display: "flex",
  flexDirection: "column",
  gap: 7,
  minWidth: 0,
};

const fieldWide: React.CSSProperties = {
  ...fieldStyle,
};

const labelText: React.CSSProperties = {
  color: "rgba(233,239,255,0.56)",
  fontSize: 12,
  fontWeight: 750,
};

const inputStyle: React.CSSProperties = {
  height: 42,
  boxSizing: "border-box",
  borderRadius: 0,
  border: "1px solid rgba(233,239,255,0.12)",
  background: "rgba(10,13,20,0.72)",
  color: "rgba(233,239,255,0.94)",
  padding: "0 12px",
  font: "inherit",
  outline: "none",
};

const actionRow: React.CSSProperties = {
  display: "flex",
  gap: 10,
  flexWrap: "wrap",
  marginTop: 14,
};

const buttonStyle: React.CSSProperties = {
  height: 38,
  display: "inline-flex",
  alignItems: "center",
  justifyContent: "center",
  gap: 7,
  borderRadius: 0,
  border: "1px solid rgba(233,239,255,0.14)",
  background: "rgba(233,239,255,0.04)",
  color: "rgba(233,239,255,0.92)",
  padding: "0 14px",
  fontWeight: 800,
  cursor: "pointer",
};

const primaryButton: React.CSSProperties = {
  background: "rgba(233,239,255,0.94)",
  color: "#05060a",
  borderColor: "rgba(233,239,255,0.94)",
};

function statusStyle(tone: Tone): React.CSSProperties {
  const palette: Record<Tone, { color: string; border: string; bg: string }> = {
    idle: { color: "#b7bfd3", border: "rgba(233,239,255,0.12)", bg: "rgba(10,13,20,0.34)" },
    ok: { color: "#c9f0dc", border: "rgba(155,227,198,0.28)", bg: "rgba(155,227,198,0.08)" },
    warn: { color: "#ffe1a6", border: "rgba(255,210,138,0.28)", bg: "rgba(255,210,138,0.08)" },
    err: { color: "#ffb4b4", border: "rgba(255,180,180,0.28)", bg: "rgba(255,180,180,0.08)" },
  };
  const item = palette[tone];
  return {
    border: `1px solid ${item.border}`,
    background: item.bg,
    color: item.color,
    padding: "11px 12px",
    whiteSpace: "pre-wrap",
    wordBreak: "break-word",
    margin: "2px 0 16px",
    fontSize: 13,
    lineHeight: 1.5,
  };
}

const audioStyle: React.CSSProperties = {
  width: "100%",
  height: 42,
  marginBottom: 12,
};

const exportResult: React.CSSProperties = {
  borderTop: "1px solid rgba(233,239,255,0.08)",
  padding: "12px 0",
  marginBottom: 4,
};

const resultLabel: React.CSSProperties = {
  color: "#9be3c6",
  fontSize: 12,
  fontWeight: 800,
};

const resultPath: React.CSSProperties = {
  color: "rgba(233,239,255,0.76)",
  fontSize: 12,
  marginTop: 5,
  wordBreak: "break-word",
};

const resultsBand: React.CSSProperties = {
  borderTop: "1px solid rgba(233,239,255,0.08)",
};

const trackRow: React.CSSProperties = {
  display: "grid",
  gridTemplateColumns: "minmax(0, 1fr) auto",
  gap: 14,
  alignItems: "center",
  padding: "13px 0",
  borderBottom: "1px solid rgba(233,239,255,0.08)",
};

const trackName: React.CSSProperties = {
  color: "#e9efff",
  fontSize: 14,
  fontWeight: 750,
  overflow: "hidden",
  textOverflow: "ellipsis",
  whiteSpace: "nowrap",
};

const trackMeta: React.CSSProperties = {
  color: "rgba(233,239,255,0.46)",
  fontSize: 12,
  marginTop: 4,
  overflow: "hidden",
  textOverflow: "ellipsis",
  whiteSpace: "nowrap",
};

const rowActions: React.CSSProperties = {
  display: "flex",
  gap: 8,
  alignItems: "center",
};

const emptyState: React.CSSProperties = {
  padding: "34px 0",
  color: "rgba(233,239,255,0.42)",
  fontSize: 13,
};

const loginBand: React.CSSProperties = {
  borderTop: "1px solid rgba(255,210,138,0.22)",
  borderBottom: "1px solid rgba(255,210,138,0.22)",
  background: "rgba(255,210,138,0.06)",
  padding: "14px 0",
  marginBottom: 18,
  display: "flex",
  alignItems: "center",
  justifyContent: "space-between",
  gap: 14,
};

const bandTitle: React.CSSProperties = {
  color: "#ffe1a6",
  fontWeight: 800,
};

const bandDesc: React.CSSProperties = {
  color: "rgba(255,225,166,0.68)",
  fontSize: 12,
  marginTop: 3,
};

const primaryLink: React.CSSProperties = {
  height: 36,
  display: "inline-flex",
  alignItems: "center",
  justifyContent: "center",
  border: "1px solid rgba(255,225,166,0.46)",
  color: "#ffe1a6",
  padding: "0 14px",
  textDecoration: "none",
  fontWeight: 800,
  whiteSpace: "nowrap",
};
