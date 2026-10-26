"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { LyricColumn } from "@/components/PlayerCard";
import { parseLrc } from "@/lib/lrc";
import type { YrcLine } from "@/lib/yrc";
import { createDemoTracks } from "@/lib/pwa-demo";
import { randomId } from "@/lib/pwa/id";
import { putTracks, readLibrary, readSession, removeTrack, saveLyrics, saveSession, type PwaTrack } from "@/lib/pwa-library";
import styles from "./pwa.module.css";

const noYrc: YrcLine[] = [];
const time = (value: number) => `${Math.floor(value / 60)}:${String(Math.floor(value % 60)).padStart(2, "0")}`;
const errorText = (error: unknown) => error instanceof Error ? error.message : "操作失败，请重试。";

export default function PwaPage() {
  const audioRef = useRef<HTMLAudioElement>(null);
  const fileRef = useRef<HTMLInputElement>(null);
  const lrcRef = useRef<HTMLInputElement>(null);
  const libraryRef = useRef<PwaTrack[]>([]);
  const currentIdRef = useRef<string | null>(null);
  const objectUrlRef = useRef<string | null>(null);
  const resumeRef = useRef(0);
  const lastSaveRef = useRef(0);
  const [tracks, setTracks] = useState<PwaTrack[]>([]);
  const [currentId, setCurrentId] = useState<string | null>(null);
  const [position, setPosition] = useState(0);
  const [duration, setDuration] = useState(0);
  const [playing, setPlaying] = useState(false);
  const [busy, setBusy] = useState(true);
  const [error, setError] = useState("");
  const [cacheStatus, setCacheStatus] = useState("正在准备离线页面");
  const [online, setOnline] = useState(true);
  const [installed, setInstalled] = useState(false);
  const [mediaSupported, setMediaSupported] = useState(false);
  const [events, setEvents] = useState<string[]>([]);
  const current = tracks.find(track => track.id === currentId);
  const lines = useMemo(() => parseLrc(current?.lrc), [current?.lrc]);
  const lyricMeta = useMemo(() => ({ lines, yrcLines: noYrc, instrumental: false, uncollected: false }), [lines]);

  const record = useCallback((message: string) => {
    setEvents(previous => [`${new Date().toLocaleTimeString("zh-CN", { hour12: false })} ${message}`, ...previous].slice(0, 16));
  }, []);
  const persist = useCallback(() => {
    const audio = audioRef.current;
    if (!audio || !currentIdRef.current) return;
    void saveSession({ trackId: currentIdRef.current, positionSec: resumeRef.current || audio.currentTime || 0 })
      .catch(() => setError("播放位置未能保存；请检查设备剩余空间。"));
    lastSaveRef.current = Date.now();
  }, []);
  const play = useCallback(() => {
    const audio = audioRef.current;
    if (!audio?.src) return;
    setError("");
    void audio.play().catch(error => {
      if (error?.name === "AbortError") return;
      setError(error?.name === "NotAllowedError" ? "浏览器暂停了播放，请点播放继续。" : "无法播放此音频，请尝试 MP3、AAC 或 WAV 文件。");
      record(`播放未开始：${error?.name || "未知错误"}`);
    });
  }, [record]);

  // 在同一个 audio 元素上直接切源；后台跨曲不依赖 React 渲染或计时器。
  const selectTrack = useCallback((id: string, autoplay: boolean, resume = 0) => {
    const track = libraryRef.current.find(item => item.id === id);
    const audio = audioRef.current;
    if (!track || !audio) return;
    audio.pause();
    const previousUrl = objectUrlRef.current;
    objectUrlRef.current = URL.createObjectURL(track.blob);
    currentIdRef.current = id;
    resumeRef.current = Math.max(0, resume);
    audio.src = objectUrlRef.current;
    audio.load();
    if (previousUrl) URL.revokeObjectURL(previousUrl);
    setCurrentId(id); setPosition(resumeRef.current); setDuration(0); setError("");
    if ("mediaSession" in navigator && "MediaMetadata" in window) {
      navigator.mediaSession.metadata = new MediaMetadata({
        title: track.name, artist: "Pipo · 本地音乐", album: "随身听",
        artwork: [{ src: new URL("/icon-512.png", location.href).href, sizes: "512x512", type: "image/png" }],
      });
    }
    record(`已选择：${track.name}`);
    persist();
    if (autoplay) play();
  }, [persist, play, record]);

  const next = useCallback((direction = 1) => {
    const library = libraryRef.current;
    if (!library.length) return;
    const index = library.findIndex(item => item.id === currentIdRef.current);
    selectTrack(library[(index + direction + library.length) % library.length].id, true);
  }, [selectTrack]);
  const seek = useCallback((seconds: number) => {
    const audio = audioRef.current;
    if (!audio || !Number.isFinite(audio.duration)) return;
    resumeRef.current = 0;
    audio.currentTime = Math.min(audio.duration, Math.max(0, seconds));
    setPosition(audio.currentTime); persist();
  }, [persist]);
  const updateLibrary = useCallback((items: PwaTrack[]) => {
    libraryRef.current = items; setTracks(items);
  }, []);

  useEffect(() => {
    let cancelled = false;
    void Promise.all([readLibrary(), readSession()]).then(([items, session]) => {
      if (cancelled) return;
      updateLibrary(items);
      const selected = items.find(item => item.id === session?.trackId) || items[0];
      if (selected) selectTrack(selected.id, false, selected.id === session?.trackId ? session.positionSec : 0);
      record(`本机曲库已恢复：${items.length} 首`);
    }).catch(error => { if (!cancelled) setError(`无法读取本机曲库：${errorText(error)}`); })
      .finally(() => { if (!cancelled) setBusy(false); });
    return () => {
      cancelled = true;
      audioRef.current?.pause();
      if (objectUrlRef.current) URL.revokeObjectURL(objectUrlRef.current);
    };
  }, [record, selectTrack, updateLibrary]);

  useEffect(() => {
    const updateOnline = () => setOnline(navigator.onLine);
    updateOnline();
    setInstalled(matchMedia("(display-mode: standalone)").matches || Boolean((navigator as Navigator & { standalone?: boolean }).standalone));
    const onVisibility = () => { persist(); record(document.hidden ? "页面进入后台" : "页面回到前台"); };
    window.addEventListener("online", updateOnline);
    window.addEventListener("offline", updateOnline);
    window.addEventListener("pagehide", persist);
    document.addEventListener("visibilitychange", onVisibility);
    return () => {
      window.removeEventListener("online", updateOnline); window.removeEventListener("offline", updateOnline);
      window.removeEventListener("pagehide", persist); document.removeEventListener("visibilitychange", onVisibility);
    };
  }, [persist, record]);

  useEffect(() => {
    if (!("mediaSession" in navigator)) return;
    setMediaSupported(true);
    const handlers: Partial<Record<MediaSessionAction, MediaSessionActionHandler>> = {
      play, pause: () => audioRef.current?.pause(),
      nexttrack: () => { record("系统媒体控件：下一首"); next(); },
      previoustrack: () => { record("系统媒体控件：上一首"); next(-1); },
      seekto: detail => { if (detail.seekTime != null) seek(detail.seekTime); },
      seekbackward: detail => seek((audioRef.current?.currentTime || 0) - (detail.seekOffset || 10)),
      seekforward: detail => seek((audioRef.current?.currentTime || 0) + (detail.seekOffset || 10)),
    };
    const supported: MediaSessionAction[] = [];
    for (const [action, handler] of Object.entries(handlers)) {
      try { navigator.mediaSession.setActionHandler(action as MediaSessionAction, handler!); supported.push(action as MediaSessionAction); }
      catch { record(`浏览器未提供系统操作：${action}`); }
    }
    return () => {
      supported.forEach(action => navigator.mediaSession.setActionHandler(action, null));
      navigator.mediaSession.metadata = null;
      navigator.mediaSession.playbackState = "none";
    };
  }, [next, play, record, seek]);

  useEffect(() => {
    if (!window.isSecureContext || !("serviceWorker" in navigator)) {
      setCacheStatus("离线页面需要 HTTPS 或 localhost"); return;
    }
    if (process.env.NODE_ENV !== "production") {
      setCacheStatus("开发预览；离线缓存请使用生产构建"); return;
    }
    let cancelled = false;
    const onMessage = (event: MessageEvent) => {
      if (event.data?.type === "CACHE_READY") { setCacheStatus("离线页面已就绪"); record("页面资源缓存完成"); }
      if (event.data?.type === "CACHE_ERROR") setCacheStatus("离线页面缓存未完成，请联网刷新重试");
    };
    navigator.serviceWorker.addEventListener("message", onMessage);
    void navigator.serviceWorker.register("/pwa-sw.js", { scope: "/pwa" }).then(async () => {
      const registration = await navigator.serviceWorker.ready;
      if (cancelled) return;
      const urls = [...new Set([
        ...performance.getEntriesByType("resource").map(entry => entry.name),
        ...Array.from(document.querySelectorAll<HTMLScriptElement>("script[src]")).map(script => script.src),
        ...Array.from(document.querySelectorAll<HTMLLinkElement>('link[rel="stylesheet"]')).map(link => link.href),
      ])].filter(url => { const parsed = new URL(url, location.href); return parsed.origin === location.origin && parsed.pathname.startsWith("/_next/static/"); });
      registration.active?.postMessage({ type: "CACHE_URLS", urls });
    }).catch(error => { if (!cancelled) setCacheStatus(`离线页面未就绪：${errorText(error)}`); });
    return () => { cancelled = true; navigator.serviceWorker.removeEventListener("message", onMessage); };
  }, [record]);

  const addTracks = async (items: PwaTrack[]) => {
    if (!items.length) return;
    setBusy(true); setError("");
    try {
      await putTracks(items);
      const merged = await readLibrary(); updateLibrary(merged);
      if (!currentIdRef.current) selectTrack(items[0].id, false);
      record(`音频已保存到本机：${items.length} 首`);
    } catch (error) { setError(`保存失败：${errorText(error)} 请检查设备剩余空间。`); }
    finally { setBusy(false); }
  };
  const importFiles = async (files: FileList | null) => {
    if (!files) return;
    const accepted = Array.from(files).filter(file => file.type.startsWith("audio/") || /\.(mp3|m4a|aac|wav|flac|ogg|opus|aiff)$/i.test(file.name));
    if (!accepted.length) { setError("请选择音频文件，例如 MP3、M4A 或 WAV。"); return; }
    await addTracks(accepted.map((file, index) => ({ id: randomId(), name: file.name.replace(/\.[^.]+$/, ""), size: file.size, mime: file.type, addedAt: Date.now() + index, blob: file })));
  };
  const deleteTrack = async (id: string) => {
    setBusy(true);
    try {
      await removeTrack(id);
      const remaining = libraryRef.current.filter(track => track.id !== id);
      const shouldPlay = Boolean(audioRef.current && !audioRef.current.paused);
      updateLibrary(remaining);
      if (currentIdRef.current === id) {
        if (remaining.length) selectTrack(remaining[0].id, shouldPlay);
        else {
          audioRef.current?.pause(); audioRef.current?.removeAttribute("src"); audioRef.current?.load();
          if (objectUrlRef.current) URL.revokeObjectURL(objectUrlRef.current);
          objectUrlRef.current = null; currentIdRef.current = null;
          setCurrentId(null); setPosition(0); setDuration(0);
          if ("mediaSession" in navigator) { navigator.mediaSession.metadata = null; navigator.mediaSession.playbackState = "none"; }
          await saveSession({ trackId: null, positionSec: 0 });
        }
      }
    } catch (error) { setError(errorText(error)); }
    finally { setBusy(false); }
  };

  return (
    <div className={styles.page}>
      <audio ref={audioRef} preload="metadata" playsInline
        onLoadedMetadata={() => {
          const audio = audioRef.current!;
          setDuration(Number.isFinite(audio.duration) ? audio.duration : 0);
          if (resumeRef.current && Number.isFinite(audio.duration)) audio.currentTime = Math.min(resumeRef.current, Math.max(0, audio.duration - 0.1));
          resumeRef.current = 0; setPosition(audio.currentTime);
        }}
        onTimeUpdate={() => {
          const audio = audioRef.current!; setPosition(audio.currentTime);
          if (Date.now() - lastSaveRef.current > 5000) persist();
          if ("mediaSession" in navigator && Number.isFinite(audio.duration) && audio.duration > 0) {
            try { navigator.mediaSession.setPositionState({ duration: audio.duration, playbackRate: audio.playbackRate, position: Math.min(audio.currentTime, audio.duration) }); } catch { /* 部分浏览器不支持位置状态。 */ }
          }
        }}
        onPlay={() => { setPlaying(true); if ("mediaSession" in navigator) navigator.mediaSession.playbackState = "playing"; record("开始播放"); }}
        onPause={() => { setPlaying(false); if ("mediaSession" in navigator) navigator.mediaSession.playbackState = "paused"; persist(); record("播放暂停"); }}
        onEnded={() => { record("播放结束，自动切到下一首"); next(); }}
        onWaiting={() => record("等待音频数据")}
        onError={() => { if (currentIdRef.current) { setError("此音频无法解码。请尝试 MP3、AAC 或 WAV 格式。"); record(`音频错误：${audioRef.current?.error?.code}`); } }}
      />
      <div className={styles.wrap}>
        <header className={styles.header}>
          <div className={styles.brand}><img src="/icon-192.png" width="38" height="38" alt="" /><div><strong>Pipo</strong><span>随身听 · PWA 验证版</span></div></div>
          <span className={styles.badge}>{installed ? "主屏幕应用" : "网页预览"}</span>
        </header>
        <div className={styles.intro}><span className={styles.eyebrow}>YOUR MUSIC, WITH YOU</span><h1>把音乐，带在身边。</h1><p>从「文件」导入音频，保存在这台设备上。</p></div>
        <div className={styles.grid}>
          <section className={styles.player} aria-label="音乐播放器">
            <div className={styles.now}><span>正在播放</span><span>{playing ? "播放中" : "已暂停"}</span></div>
            <h2>{current?.name || "从一首喜欢的歌开始"}</h2>
            <p className={styles.trackMeta}>{current ? `${(current.size / 1024 / 1024).toFixed(1)} MB · 本机音频 · 列表循环` : "导入音乐，或先用三段原创短音序试听"}</p>
            <div className={styles.lyrics}>
              {lines.length ? <LyricColumn lines={lines} yrcLines={noYrc} positionSec={position} isPlaying={playing} sessionId={currentId || undefined} meta={lyricMeta} desktopAppleMotion={true} fgColor="#e9efff" fgDimColor="#8a93a8" fgUnsungColor="#6c7589" onSeekToSec={seek} />
                : <div className={styles.lyricEmpty}><span>♫</span><p>{current ? "让音乐自己说话" : "你的音乐，留在你的设备"}</p><small>{current ? "可导入 LRC 歌词，体验原有歌词动画" : "无需登录，也无需上传"}</small></div>}
            </div>
            <input className={styles.seek} aria-label="播放进度" type="range" min="0" max={duration || 1} step="0.1" value={Math.min(position, duration || position)} disabled={!duration} onChange={event => seek(Number(event.target.value))} />
            <div className={styles.time}><span>{time(position)}</span><span>{time(duration)}</span></div>
            <div className={styles.controls}><button disabled={!current} onClick={() => next(-1)} aria-label="上一首">上一首</button><button className={styles.play} disabled={!current || busy} onClick={() => playing ? audioRef.current?.pause() : play()} aria-label={playing ? "暂停" : "播放"}>{playing ? "Ⅱ" : "▶"}</button><button disabled={!current} onClick={() => next()} aria-label="下一首">下一首</button></div>
            {current && <button className={styles.lyricButton} disabled={busy} onClick={() => lrcRef.current?.click()}>{lines.length ? "替换 LRC 歌词" : "导入 LRC 歌词"}</button>}
          </section>
          <section className={styles.library} aria-label="本机曲库">
            <div className={styles.sectionTitle}><h2>本机曲库 <span>{tracks.length}</span></h2><button className={styles.import} disabled={busy} onClick={() => fileRef.current?.click()}>{busy ? "正在读取…" : "＋ 导入音频"}</button></div>
            <input ref={fileRef} className={styles.file} type="file" accept="audio/*,.mp3,.m4a,.aac,.wav,.flac,.ogg,.opus,.aiff" multiple onChange={event => { void importFiles(event.target.files); event.target.value = ""; }} />
            <input ref={lrcRef} className={styles.file} type="file" accept=".lrc,text/plain" onChange={async event => {
              const file = event.target.files?.[0]; const id = currentIdRef.current; event.target.value = "";
              if (!file || !id) return;
              setBusy(true); setError("");
              try { const lrc = await file.text(); if (!parseLrc(lrc).length) throw new Error("未找到带时间戳的歌词，请选择 LRC 文件。"); await saveLyrics(id, lrc); updateLibrary(libraryRef.current.map(track => track.id === id ? { ...track, lrc } : track)); }
              catch (error) { setError(errorText(error)); } finally { setBusy(false); }
            }} />
            {error && <p className={styles.error} role="alert">{error}</p>}
            {tracks.length ? <ol className={styles.trackList}>{tracks.map((track, index) => <li key={track.id} className={track.id === currentId ? styles.activeTrack : ""}><button className={styles.trackButton} disabled={busy} onClick={() => selectTrack(track.id, true)}><span className={styles.trackIndex}>{track.id === currentId && playing ? "♫" : String(index + 1).padStart(2, "0")}</span><span><strong>{track.name}</strong><small>{(track.size / 1024 / 1024).toFixed(1)} MB · {track.lrc ? "附歌词" : "本机音频"}</small></span></button><button className={styles.remove} aria-label={`移除 ${track.name}`} disabled={busy} onClick={() => void deleteTrack(track.id)}>×</button></li>)}</ol>
              : <div className={styles.empty}><p>曲库还空着</p><span>支持 MP3、M4A、WAV 等浏览器可播放格式。<br />导入后，即可离线播放。</span></div>}
            {!tracks.some(track => track.id.startsWith("pipo-demo-")) && <button className={styles.demo} disabled={busy} onClick={() => void addTracks(createDemoTracks())}>先试三段测试音序 <span>每段 18 秒 →</span></button>}
            <p className={styles.storageNote}>音频仅存此设备。清除网站数据或系统回收存储后，需要重新导入；请保留原文件。</p>
          </section>
        </div>
        <section className={styles.install}><div><h2>放到 iPhone 主屏幕</h2><p>用 Safari 打开 HTTPS 地址，轻点「分享」→「添加到主屏幕」，再从图标打开。</p></div><span>{online ? "当前在线" : "当前离线"} · {cacheStatus}</span></section>
        <details className={styles.diagnostics}><summary>验证信息与播放记录</summary><p>音频引擎：HTMLAudioElement · {mediaSupported ? "已注册系统媒体控制" : "当前浏览器无 Media Session"}</p><p>iPhone 待实测：锁屏跨曲、蓝牙切歌、来电后恢复、离线冷启动。本页记录用于观察事件；接口存在不代表设备验证通过。</p><ol>{events.map((event, index) => <li key={`${event}-${index}`}>{event}</li>)}</ol></details>
      </div>
    </div>
  );
}
