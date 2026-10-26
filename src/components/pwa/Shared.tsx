"use client";

import { useEffect, useLayoutEffect, useRef, useState, type CSSProperties, type ReactNode, type RefObject } from "react";
import { blend, browseSurfaces, coverSource, cssRgb, usePalette } from "@/lib/pwa/palette";
import type { NativeTrack } from "@/lib/pwa/model";
import { useBackLayer } from "@/lib/pwa/back-navigation";
import { Icon, type IconName } from "./Icon";

export function IconButton({ name, label, onClick, disabled, size = 24, className = "" }: { name: IconName; label: string; onClick?: () => void; disabled?: boolean; size?: number; className?: string }) {
  return <button type="button" className={`pipo-icon-button ${className}`} aria-label={label} onClick={onClick} disabled={disabled}><Icon name={name} size={size} /></button>;
}
export function Artwork({ url, className = "", style, eager = false, fade = 0 }: { url?: string | null; className?: string; style?: CSSProperties; eager?: boolean; fade?: number }) {
  const source = coverSource(url);
  const [displayed, setDisplayed] = useState(source);
  const [previous, setPrevious] = useState("");
  const [failed, setFailed] = useState(false);
  const shown = useRef(source);
  useEffect(() => {
    setFailed(false);
    if (!source) { setDisplayed(""); shown.current = ""; setPrevious(""); return; }
    if (!fade) { setDisplayed(source); shown.current = source; return; }
    let active = true; const image = new Image();
    image.onload = () => { if (!active) return; setPrevious(shown.current); setDisplayed(source); shown.current = source; };
    image.onerror = () => { if (active) setFailed(true); }; image.src = source;
    return () => { active = false; image.onload = null; image.onerror = null; };
  }, [source, fade]);
  useEffect(() => { if (!previous) return; const timer = setTimeout(() => setPrevious(""), fade); return () => clearTimeout(timer); }, [displayed, previous, fade]);
  return <span className={`pipo-artwork ${className}`} style={style}>
    {!displayed || failed ? <Icon name="MusicNote" size={24} /> : <>
      {previous && <img src={previous} alt="" className="pipo-artwork-previous" />}
      <img key={displayed} src={displayed} alt="" loading={eager ? "eager" : "lazy"} decoding="async" onError={() => setFailed(true)} style={previous ? { animation: `pipo-page-enter ${fade}ms linear both` } : undefined} />
    </>}
  </span>;
}

export function Backdrop({ url, height = 390, overlay = .22 }: { url?: string | null; height?: number; overlay?: number }) {
  const palette = usePalette(url); const colors = browseSurfaces(palette); const { top, bridge, surface } = colors;
  const source = coverSource(url || "/pwa/artwork/login-music-classics.png");
  const image = `url(${JSON.stringify(source)})`;
  return <div className="pipo-backdrop" aria-hidden="true" style={{ background: `linear-gradient(${cssRgb(top)} 0%,${cssRgb(bridge)} 30%,${cssRgb(blend(bridge,surface,.28)!)} 54%,${cssRgb(blend(bridge,surface,.72)!)} 76%,${cssRgb(surface)} 100%)` }}>
    <div className="pipo-backdrop-wash" style={{ backgroundImage: image }} />
    <div className="pipo-backdrop-top" style={{ height, backgroundImage: image }} />
    <div style={{ position: "absolute", inset: 0, background: `linear-gradient(transparent 0%,transparent 50%,${cssRgb(surface,.2)} 74%,${cssRgb(surface,.52)} 100%)` }} />
    <div className="pipo-backdrop-overlay" style={{ background: `rgba(7,11,18,${overlay})` }} />
  </div>;
}

export function Sheet({ children, label, onClose, busy = false, closeRequested=false, className = "" }: { children: ReactNode | ((dismiss: () => void) => ReactNode); label: string; onClose: () => void; busy?: boolean; closeRequested?:boolean; className?: string }) {
  const panel = useRef<HTMLDivElement>(null); const layer = useRef<HTMLDivElement>(null);
  const closeRef = useRef(onClose); closeRef.current = onClose;
  const [closing, setClosing] = useState(false); const drag = useRef<{ y: number; at: number; offset: number } | null>(null);
  const dismiss = () => { if (!busy) setClosing(true); };
  useEffect(()=>{if(closeRequested) setClosing(true);},[closeRequested]);
  useBackLayer(true,() => {if(busy) return false;setClosing(true);});
  useEffect(() => { if (!closing) return; const timer = setTimeout(() => closeRef.current(), 300); return () => clearTimeout(timer); }, [closing]);
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null;
    if (!panel.current?.contains(document.activeElement)) panel.current?.focus({ preventScroll: true });
    return () => { if (previous?.isConnected) previous.focus({ preventScroll: true }); };
  }, []);
  return <div ref={layer} className="pipo-sheet-layer" data-closing={closing} onKeyDown={event => {
    if (event.key === "Escape") { event.preventDefault(); event.stopPropagation(); dismiss(); }
    if (event.key === "Tab") {
      const targets = Array.from(panel.current?.querySelectorAll<HTMLElement>('button:not(:disabled),input:not(:disabled),textarea:not(:disabled),select:not(:disabled),[tabindex="0"]') || []);
      const first = targets[0], last = targets.at(-1);
      if (!first) event.preventDefault();
      else if (event.shiftKey && (document.activeElement === first || document.activeElement === panel.current)) { event.preventDefault(); last?.focus(); }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
    }
  }}>
    <button className="pipo-sheet-scrim" aria-label="关闭弹层" disabled={busy} tabIndex={-1} onClick={dismiss} />
    <div ref={panel} role="dialog" aria-modal="true" aria-label={label} tabIndex={-1} className={`pipo-sheet ${className}`}>
      <div className="pipo-sheet-grab" onPointerDown={event => {
        if (busy) return; event.currentTarget.setPointerCapture(event.pointerId);
        drag.current = { y: event.clientY, at: performance.now(), offset: 0 };
        if (panel.current) { panel.current.style.animation = "none"; panel.current.style.transition = "none"; }
      }} onPointerMove={event => {
        const state = drag.current; if (!state || !panel.current) return;
        state.offset = Math.max(0, event.clientY - state.y); panel.current.style.transform = `translateY(${state.offset}px)`;
      }} onPointerUp={() => {
        const state = drag.current; if (!state || !panel.current) return; drag.current = null;
        const velocity = state.offset / Math.max(1,performance.now() - state.at);
        panel.current.style.transition = "transform 300ms cubic-bezier(.4,0,.2,1)";
        panel.current.style.transform = state.offset > panel.current.clientHeight * .3 || velocity > .7 ? "translateY(100%)" : "translateY(0)";
        if (state.offset > panel.current.clientHeight * .3 || velocity > .7) dismiss();
      }} onPointerCancel={() => { drag.current = null; if (panel.current) { panel.current.style.transition = "transform 300ms cubic-bezier(.4,0,.2,1)"; panel.current.style.transform = "translateY(0)"; } }}><div className="pipo-sheet-handle" /></div>
      {typeof children === "function" ? children(dismiss) : children}
    </div>
  </div>;
}

export function TrackRow({ track, current, onPlay, onMore, home = false }: { track: NativeTrack; current?: string; onPlay: () => void; onMore: () => void; home?: boolean }) {
  return <div className={`pipo-track-row ${home ? "pipo-home-track" : ""}`} data-current={track.id === current}>
    <button className="pipo-track-main" onClick={onPlay} aria-label={`播放 ${track.title}，${track.artist}`}><Artwork url={track.artworkUrl} /><span className="pipo-track-copy"><span className="pipo-track-title">{track.title}</span><span className="pipo-track-artist">{track.artist}</span></span></button>
    <IconButton name="MoreHoriz" label={`${track.title}的歌曲菜单`} onClick={onMore} />
  </div>;
}

// Only visible rows mount. The parent owns scroll state, so opening the player never resets it.
export function TrackList({ tracks, scrollRef, current, onPlay, onMore, home = false }: { tracks: NativeTrack[]; scrollRef: RefObject<HTMLElement | null>; current?: string; onPlay: (track: NativeTrack, index: number) => void; onMore: (track: NativeTrack) => void; home?: boolean }) {
  const container = useRef<HTMLDivElement>(null); const rowHeight = home ? 70 : 60;
  const [range, setRange] = useState({ start: 0, end: 24 });
  useLayoutEffect(() => {
    const scroll = scrollRef.current, list = container.current; if (!scroll || !list) return;
    let frame = 0;
    const update = () => {
      cancelAnimationFrame(frame); frame = requestAnimationFrame(() => {
        const top = list.getBoundingClientRect().top - scroll.getBoundingClientRect().top + scroll.scrollTop;
        const start = Math.max(0,Math.floor((scroll.scrollTop - top)/rowHeight)-6);
        const end = Math.min(tracks.length,Math.ceil((scroll.scrollTop + scroll.clientHeight - top)/rowHeight)+6);
        setRange(old => old.start === start && old.end === end ? old : { start, end: Math.max(start,end) });
      });
    };
    update(); scroll.addEventListener("scroll",update,{ passive:true }); const observer = new ResizeObserver(update); observer.observe(scroll); observer.observe(list);
    return () => { cancelAnimationFrame(frame); scroll.removeEventListener("scroll",update); observer.disconnect(); };
  }, [tracks.length, rowHeight, scrollRef]);
  const start = Math.min(range.start,tracks.length), end = Math.max(start,Math.min(range.end,tracks.length));
  return <div ref={container} style={{ height: tracks.length * rowHeight, position: "relative" }}><div style={{ paddingTop: start * rowHeight }}>{tracks.slice(start,end).map((track,i) => <TrackRow key={`${track.id}:${start+i}`} track={track} current={current} home={home} onPlay={() => onPlay(track,start+i)} onMore={() => onMore(track)} />)}</div></div>;
}
