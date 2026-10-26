"use client";

import { memo,useEffect,useLayoutEffect,useMemo,useRef,useState,type CSSProperties,type ReactNode,type RefObject } from "react";
import { player,usePlayerState,type QueueMode } from "@/lib/pwa/player";
import { useLyrics } from "@/lib/pwa/lyrics";
import { blend,cssRgb,luma,lyricAccent,miniPalette,paletteSurfaces,usePalette } from "@/lib/pwa/palette";
import { Spring,bezier,clamp01,smooth } from "@/lib/pwa/motion";
import { library,useLibrary } from "@/lib/pwa/library";
import { messageOf } from "@/lib/pwa/storage";
import type { NativeTrack } from "@/lib/pwa/model";
import { Artwork,IconButton,Sheet } from "./Shared";
import { Icon,type IconName } from "./Icon";
import { NativeLyrics } from "./Lyrics";

export const queueModes:Array<{value:QueueMode;label:string;icon:IconName}>=[{value:"OrderOnce",label:"顺序播放",icon:"PlaylistPlay"},{value:"PlaylistLoop",label:"列表循环",icon:"Repeat"},{value:"SingleLoop",label:"单曲循环",icon:"RepeatOne"},{value:"ShufflePlay",label:"随机播放",icon:"Shuffle"},{value:"AiRadio",label:"AI 续播",icon:"spark"}];
export function MiniPlayer({onOpen,onQueue,miniRef,active=true}:{onOpen:() => void;onQueue:() => void;miniRef?:RefObject<HTMLDivElement|null>;active?:boolean}) {
  const state=usePlayerState(); const palette=usePalette(state.current?.artworkUrl); const colors=useMemo(() => miniPalette(palette),[palette]);
  const progress=useRef<HTMLElement>(null);
  useEffect(() => {
    if(!active) return;
    let frame=0;const draw=() => {if(progress.current) progress.current.style.transform=`scaleX(${state.duration ? clamp01(player.position()/state.duration) : 0})`;if(state.playing && document.visibilityState==="visible") frame=requestAnimationFrame(draw);};
    const wake=() => {cancelAnimationFrame(frame);draw();};draw();const unsubscribe=player.subscribeClock(wake);document.addEventListener("visibilitychange",wake);
    return () => {cancelAnimationFrame(frame);unsubscribe();document.removeEventListener("visibilitychange",wake);};
  },[active,state.playing,state.duration]);
  if(!state.current) return null;
  return <div ref={miniRef} className="pipo-mini" style={{"--mini-surface":cssRgb(colors.surface),"--mini-ink":cssRgb(colors.ink)} as CSSProperties}><div className="pipo-mini-row"><button className="pipo-mini-open" onClick={onOpen} aria-label="展开播放器"><Artwork url={state.current.artworkUrl} fade={720} eager /><span className="pipo-mini-copy"><span>{state.current.title}</span><small>{state.current.artist}</small></span></button><IconButton name={state.playing?"pause":"play"} size={26} label={state.playing?"暂停":"播放"} onClick={player.toggle} /><IconButton name="list" size={26} label="播放队列" onClick={onQueue} /></div><div className="pipo-mini-progress"><i ref={progress} /></div></div>;
}

export function PlayerSurface({expanded,visible,canCollapse,miniRef,onCollapse,onSettings,onAi,children,compact}:{expanded:boolean;visible:boolean;canCollapse:boolean;miniRef:RefObject<HTMLDivElement|null>;onCollapse:() => void;onSettings:() => void;onAi:() => void;children:ReactNode;compact:ReactNode}) {
  const state=usePlayerState(),palette=usePalette(state.current?.artworkUrl);
  const controlColor=luma(paletteSurfaces(palette).surface)>145?"#000":"#fff";
  const surface=useRef<HTMLDivElement>(null),inner=useRef<HTMLDivElement>(null),compactLayer=useRef<HTMLDivElement>(null);
  const spring=useRef(new Spring(0));const frame=useRef(0),last=useRef(0),drag=useRef<{y:number;lastY:number;time:number;velocity:number;started:boolean}|null>(null);
  const bounds=useRef({left:16,top:650,right:374,bottom:714,width:358});
  const expandedRef=useRef(expanded);expandedRef.current=expanded;
  const drawRef=useRef<() => void>(() => undefined);
  drawRef.current=() => {
    const host=surface.current,body=inner.current;if(!host || !body) return;const p=clamp01(spring.current.value),b=bounds.current,w=host.clientWidth,h=host.clientHeight;
    host.style.clipPath=`inset(${b.top*(1-p)}px ${(w-b.right)*(1-p)}px ${(h-b.bottom)*(1-p)}px ${b.left*(1-p)}px round ${14*(1-p)}px)`;
    host.style.visibility=p>0?"visible":"hidden";body.style.transform=`translateY(${48*(1-p)}px) scale(${.96+.04*p})`;body.style.opacity=String(smooth(p,.12,.9));
    // visibility on a child overrides a hidden ancestor; never leave the animation copy visible at rest.
    if(compactLayer.current) {compactLayer.current.style.opacity=String(clamp01(1-p/.28));compactLayer.current.style.transform=`translate(${b.left*(1-p)}px,${b.top*(1-p)}px)`;compactLayer.current.style.width=`${b.width}px`;compactLayer.current.style.visibility=p>0&&p<.28?"visible":"hidden";}
  };
  const animate=() => {
    cancelAnimationFrame(frame.current);last.current=performance.now();
    const tick=(now:number) => {spring.current.step((now-last.current)/1000);last.current=now;drawRef.current();if(!spring.current.settled) frame.current=requestAnimationFrame(tick);};frame.current=requestAnimationFrame(tick);
  };
  useLayoutEffect(() => {
    const host=surface.current;if(!host) return;
    const capture=() => {const mini=miniRef.current;if(mini && mini.getBoundingClientRect().height>0) {const m=mini.getBoundingClientRect(),box=host.getBoundingClientRect();bounds.current={left:m.left-box.left,top:m.top-box.top,right:m.right-box.left,bottom:m.bottom-box.top,width:m.width};}drawRef.current();};
    capture();const observer=new ResizeObserver(capture);observer.observe(host);if(miniRef.current) observer.observe(miniRef.current);
    return () => {observer.disconnect();cancelAnimationFrame(frame.current);};
  },[miniRef]);
  useEffect(() => {
    if(expanded && miniRef.current && surface.current) {const m=miniRef.current.getBoundingClientRect(),box=surface.current.getBoundingClientRect();if(m.height>0) bounds.current={left:m.left-box.left,top:m.top-box.top,right:m.right-box.left,bottom:m.bottom-box.top,width:m.width};}
    spring.current.to(expanded?1:0,expanded?300:380,.94);animate();
  },[expanded]);
  return <div ref={surface} className="pipo-player-surface" aria-hidden={!visible} inert={!visible} style={{pointerEvents:visible?"auto":"none"}} onDoubleClick={event => {if(canCollapse && !(event.target as HTMLElement).closest("button,input")) onAi();}}
    onPointerDown={event => {if(!canCollapse || (event.target as HTMLElement).closest("button,input,.pipo-native-lyrics")) return;drag.current={y:event.clientY,lastY:event.clientY,time:performance.now(),velocity:0,started:false};}}
    onPointerMove={event => {const state=drag.current;if(!state || !surface.current) return;const now=performance.now();state.velocity=(event.clientY-state.lastY)/Math.max(1,now-state.time)*1000;state.time=now;state.lastY=event.clientY;if(event.clientY-state.y>6 || state.started) {state.started=true;event.currentTarget.setPointerCapture(event.pointerId);cancelAnimationFrame(frame.current);spring.current.snap(clamp01(1-(event.clientY-state.y)/surface.current.clientHeight));drawRef.current();}}}
    onPointerUp={() => {const state=drag.current;drag.current=null;if(!state?.started) return;const dismiss=spring.current.value<.84 || (spring.current.value<.97 && state.velocity>1000);if(dismiss) onCollapse();else {spring.current.to(1,300,.94);animate();}}}
    onPointerCancel={() => {drag.current=null;spring.current.to(expandedRef.current?1:0,expandedRef.current?300:380,.94);animate();}}>
    <div ref={inner} className="pipo-player-content">{children}<div className="pipo-player-toolbar" style={{color:controlColor}}><IconButton name="collapse" size={28} label="收起播放器" onClick={onCollapse} /><IconButton name="gear" size={22} label="设置" onClick={onSettings} /></div></div><div ref={compactLayer} className="pipo-player-compact-transition" aria-hidden="true">{compact}</div>
  </div>;
}

const PlayerBackdrop=memo(function PlayerBackdrop({url,landscape}:{url?:string|null;landscape:boolean}) {
  const palette=usePalette(url);const colors=useMemo(() => paletteSurfaces(palette),[palette]);const {surface,top,bridge,wash}=colors;
  const left=blend(bridge,top,.62)!,right=blend(bridge,wash,.74)!;
  const style={"--play-top":cssRgb(top),"--play-bridge":cssRgb(bridge),"--play-mid":cssRgb(blend(bridge,surface,.64)!),"--play-surface":cssRgb(surface),"--play-left":cssRgb(left),"--play-right":cssRgb(right),"--play-landscape":cssRgb(colors.landscape),"--play-upper":cssRgb(palette.rightUpper||palette.right||colors.landscape),"--play-middle":cssRgb(palette.rightMiddle||palette.right||colors.landscape),"--play-lower":cssRgb(palette.rightLower||palette.right||colors.landscape)} as CSSProperties;
  if(landscape) return <div className="pipo-player-backdrop pipo-player-landscape-backdrop" aria-hidden="true" style={style}><Artwork url={url} className="pipo-landscape-cover" fade={520} eager /></div>;
  return <div className="pipo-player-backdrop" aria-hidden="true" style={style}><Artwork url={url} className="pipo-player-glass-wash" fade={1100} eager /><Artwork url={url || "/icon-512.png"} className="pipo-player-top-cover" fade={1100} eager /></div>;
});
export function Player({visible,onQueue,onTaste,onImmersive,immersive,showTranslation,onTranslation}:{visible:boolean;onQueue:() => void;onTaste:() => void;onImmersive:(value:boolean) => void;immersive:boolean;showTranslation:boolean;onTranslation:() => void}) {
  const state=usePlayerState(),lyrics=useLyrics(state.current),palette=usePalette(state.current?.artworkUrl),colors=useMemo(() => paletteSurfaces(palette),[palette]);
  const host=useRef<HTMLDivElement>(null),controls=useRef<HTMLDivElement>(null),title=useRef<HTMLDivElement>(null),overlay=useRef<HTMLDivElement>(null);
  const [landscape,setLandscape]=useState(false);const contentSpring=useRef(new Spring(0,380,.86));const contentProgress=useRef(0);const geometry=useRef({width:390,height:844,controlsTop:435,immersiveTop:372,titleFont:17,controlsWidth:335});
  const currentLayout=useRef(landscape);currentLayout.current=landscape;
  const draw=useRef<(p:number) => void>(() => undefined);
  draw.current=p => {
    const g=geometry.current;
    if(controls.current) {const exit=smooth(p,.08,.86);controls.current.style.opacity=String(1-exit);controls.current.style.transform=`translateY(${exit*18}px)`;controls.current.style.pointerEvents=p>.86?"none":"auto";}
    if(title.current) {const move=smooth(p,0,.62),exit=smooth(p,.70,.86);title.current.style.opacity=String(1-exit);title.current.style.transform=`translate(${(24-(g.width-g.controlsWidth)/2)*move}px,${(g.immersiveTop-g.controlsTop)*move}px) scale(${1+(Math.min(1.34,Math.max(1,22/g.titleFont))-1)*move})`;}
    if(overlay.current) {overlay.current.style.visibility=p>.001?"visible":"hidden";overlay.current.style.pointerEvents=p>.95?"auto":"none";overlay.current.style.setProperty("--lyric-title-enter",String(smooth(p,.70,.86)));overlay.current.style.setProperty("--lyric-controls-enter",String(smooth(p,.88,1)));overlay.current.style.setProperty("--lyric-list-enter",String(smooth(p,.90,1)));}
  };
  useLayoutEffect(() => {
    const element=host.current;if(!element) return;
    const resize=() => {const w=element.clientWidth,h=element.clientHeight;const computed=getComputedStyle(element);const safeTop=parseFloat(computed.paddingTop)||0,safeBottom=parseFloat(computed.paddingBottom)||0;const vh=h-safeTop-safeBottom-12;const controlsTop=Math.min(vh*.565,Math.max(vh*.515,w*1.02));geometry.current={width:w,height:h,controlsTop:controlsTop+safeTop,immersiveTop:safeTop+Math.min(w-18,(h-safeTop-safeBottom)*.43),titleFont:Math.max(17,Math.min(22,w*.04)),controlsWidth:Math.max(260,Math.min(420,w*.86))};
      element.style.setProperty("--player-w",`${w}px`);element.style.setProperty("--player-h",`${h}px`);element.style.setProperty("--controls-top",`${controlsTop}px`);element.style.setProperty("--control-gap",`${Math.max(22,Math.min(32,vh*.034))}px`);element.style.setProperty("--title-gap",`${Math.max(21,Math.min(30,vh*.033))}px`);element.style.setProperty("--nav-gap",`${Math.max(9,Math.min(14,vh*.014))+30+Math.max(12,Math.min(18,vh*.018))}px`);element.style.setProperty("--cover-pane",`${Math.min(h+42,w*.5)}px`);setLandscape(w>h);draw.current(contentProgress.current);
    };resize();const observer=new ResizeObserver(resize);observer.observe(element);return () => observer.disconnect();
  },[]);
  useEffect(() => {
    const start=contentProgress.current,target=immersive?1:0,started=performance.now(),duration=immersive?620:220,delay=immersive?90:0,ease=immersive?bezier(.2,.9,.15,1):bezier(.4,0,1,1);let frame=0;
    contentSpring.current.to(target,immersive?380:520,immersive?.86:.95);
    let previous=started;
    const tick=(now:number) => {const p=clamp01((now-started-delay)/duration);contentProgress.current=start+(target-start)*ease(p);contentSpring.current.step((now-previous)/1000);previous=now;overlay.current?.style.setProperty("--cover-progress",String(clamp01(contentSpring.current.value)));draw.current(contentProgress.current);if(p<1 || !contentSpring.current.settled) frame=requestAnimationFrame(tick);};frame=requestAnimationFrame(tick);return () => cancelAnimationFrame(frame);
  },[immersive]);
  useLayoutEffect(() => {draw.current(contentProgress.current);},[state.current?.id,landscape]);
  const foreground=luma(landscape?colors.landscape:colors.surface)>145?"0,0,0":"255,255,255",dim=foreground==="0,0,0"?.549:.62;
  const style={"--player-rgb":foreground,"--player-dim":dim,"--lyric-accent":cssRgb(lyricAccent(palette.accent)||[255,255,255])} as CSSProperties;
  return <div ref={host} className={`pipo-native-player ${landscape?"pipo-player-landscape":""}`} style={style}><PlayerBackdrop url={state.current?.artworkUrl} landscape={landscape} />
    {landscape ? <div className="pipo-landscape-panel pipo-orientation-enter"><div className="pipo-landscape-track-header"><div><h1>{state.current?.title || "—"}</h1><p>{state.current?.artist}{state.current?.album?` · ${state.current.album}`:""}</p></div>{lyrics.hasTranslation && <TranslationButton selected={showTranslation} onClick={onTranslation} />}</div><div className="pipo-landscape-lyric-slot"><NativeLyrics data={lyrics} showTranslation={showTranslation} landscape active={visible} /></div><LandscapeControls active={visible} /></div> : <>
      <div className="pipo-portrait-controls pipo-orientation-enter"><div className="pipo-control-width"><div ref={title} className="pipo-player-title" key={state.current?.title}><h1>{state.current?.title || "—"}</h1><p>{state.current?.artist}{state.current?.album?` · ${state.current.album}`:""}</p></div><div ref={controls}><div className="pipo-progress-space"><ProgressWithTimes active={visible && !immersive} /></div><div className="pipo-player-transport"><button aria-label="上一首" disabled={!state.current} onClick={player.previous}><Icon name="previous" size={48} /></button><button className="pipo-main-toggle" aria-label={state.playing?"暂停":"播放"} disabled={!state.current} onClick={player.toggle}>{state.buffering?<span className="pipo-spinner" />:<Icon name={state.playing?"pause":"play"} size={64} />}</button><button aria-label="下一首" disabled={!state.current} onClick={player.next}><Icon name="next" size={48} /></button></div><div className="pipo-player-navigation"><IconButton name="lyrics" label="沉浸歌词" disabled={!lyrics.lines.length} onClick={() => onImmersive(true)} /><IconButton name="list" label="播放队列" onClick={onQueue} /><IconButton name="profile" label="音乐口味" onClick={onTaste} /></div></div></div></div>
      <div ref={overlay} className="pipo-immersive-overlay"><button className="pipo-immersive-close-cover" aria-label="返回播放器" onClick={() => onImmersive(false)} /><div className="pipo-immersive-heading" key={state.current?.title}><div className="pipo-immersive-heading-copy"><h1>{state.current?.title || "—"}</h1><p>{state.current?.artist || " "}</p></div><div className="pipo-immersive-heading-controls">{lyrics.hasTranslation && <TranslationButton selected={showTranslation} onClick={onTranslation} />}<IconButton name={state.playing?"pause":"play"} size={23} label={state.playing?"暂停":"播放"} onClick={player.toggle} /><IconButton name="next" size={22} label="下一首" onClick={player.next} /></div></div><div className="pipo-immersive-lyric-slot"><NativeLyrics data={lyrics} showTranslation={showTranslation} active={visible && immersive} /></div></div>
    </>}
    {state.error && <div className="pipo-player-error" role="alert"><p>{state.error}</p><button onClick={player.retry}>重试</button></div>}
  </div>;
}
function TranslationButton({selected,onClick}:{selected:boolean;onClick:() => void}) {return <button className="pipo-icon-button pipo-translation-button" aria-label={selected?"隐藏译文":"显示译文"} aria-pressed={selected} onClick={onClick}><Icon name="Translate" size={20} /></button>;}
const formatTime=(seconds:number) => `${Math.floor(Math.max(0,seconds)/60)}:${String(Math.floor(Math.max(0,seconds)%60)).padStart(2,"0")}`;
type ProgressRenderer={range:RefObject<HTMLInputElement|null>;elapsed:RefObject<HTMLSpanElement|null>;remaining:RefObject<HTMLSpanElement|null>;startDrag:() => void;seek:(value:number) => void;endDrag:() => void;};
function useProgressRenderer(active:boolean,playing:boolean,duration:number):ProgressRenderer {
  const range=useRef<HTMLInputElement>(null),elapsed=useRef<HTMLSpanElement>(null),remaining=useRef<HTMLSpanElement>(null),dragging=useRef(false),draw=useRef<(position?:number) => void>(() => undefined);
  draw.current=(position=dragging.current?Number(range.current?.value || 0):player.position()) => {
    const current=Math.min(Math.max(0,position),Math.max(1,duration)),elapsedText=formatTime(current),remainingText=`-${formatTime(duration-current)}`,input=range.current;
    if(input) {input.value=String(current);input.style.setProperty("--progress",`${duration?clamp01(current/duration)*100:0}%`);if(input.getAttribute("aria-valuetext")!==elapsedText) input.setAttribute("aria-valuetext",elapsedText);}
    const elapsedElement=elapsed.current,remainingElement=remaining.current;
    if(elapsedElement && elapsedElement.textContent!==elapsedText) elapsedElement.textContent=elapsedText;
    if(remainingElement && remainingElement.textContent!==remainingText) remainingElement.textContent=remainingText;
  };
  useEffect(() => {
    if(!active) {dragging.current=false;return;}
    let frame=0,unsubscribe:(() => void)|undefined;
    function schedule() {if(!frame && playing && document.visibilityState==="visible") frame=requestAnimationFrame(tick);}
    function tick() {frame=0;draw.current();schedule();}
    function wake() {draw.current();schedule();}
    const visibility=() => {if(document.visibilityState!=="visible") {dragging.current=false;cancelAnimationFrame(frame);frame=0;unsubscribe?.();unsubscribe=undefined;} else {unsubscribe=player.subscribeClock(wake);wake();}};
    visibility();document.addEventListener("visibilitychange",visibility);
    return () => {dragging.current=false;cancelAnimationFrame(frame);unsubscribe?.();document.removeEventListener("visibilitychange",visibility);};
  },[active,playing,duration]);
  const startDrag=() => {dragging.current=true;};
  const seek=(value:number) => {draw.current(value);player.seek(value);};
  const endDrag=() => {dragging.current=false;draw.current();};
  return {range,elapsed,remaining,startDrag,seek,endDrag};
}
function ProgressBar({duration,progress}:{duration:number;progress:ProgressRenderer}) {
  return <input ref={progress.range} className="pipo-progress-range" type="range" min={0} max={Math.max(1,duration)} step="any" aria-label="播放进度" aria-valuetext={formatTime(player.position())} defaultValue={0} style={{"--progress":"0%"} as CSSProperties} disabled={!duration} onPointerDown={progress.startDrag} onChange={event => progress.seek(Number(event.target.value))} onPointerUp={progress.endDrag} onPointerCancel={progress.endDrag} onBlur={progress.endDrag} />;
}
function ProgressWithTimes({active}:{active:boolean}) {const {duration,playing}=usePlayerState(),progress=useProgressRenderer(active,playing,duration);return <><ProgressBar duration={duration} progress={progress} /><div className="pipo-progress-times"><span ref={progress.elapsed}>0:00</span><span ref={progress.remaining}>-{formatTime(duration)}</span></div></>;}
function LandscapeControls({active}:{active:boolean}) {const state=usePlayerState(),progress=useProgressRenderer(active,state.playing,state.duration);return <div className="pipo-landscape-controls"><span ref={progress.elapsed}>0:00</span><ProgressBar duration={state.duration} progress={progress} /><span ref={progress.remaining}>-{formatTime(state.duration)}</span><IconButton name={state.playing?"pause":"play"} size={22} label={state.playing?"暂停":"播放"} onClick={player.toggle} /><IconButton name="next" size={22} label="下一首" onClick={player.next} /></div>;}

export function QueueSheet({onClose}:{onClose:() => void}) {
  const state=usePlayerState(),account=useLibrary();const [error,setError]=useState("");const mode=queueModes.find(item => item.value===state.mode)!;
  const like=async (track:NativeTrack) => {if(!account.account) {setError("请先在设置中登录网易云");return;}if(!account.favoriteIds) {setError("正在确认收藏状态，请稍后再试");void library.refreshFavorites();return;}try {await library.toggleLike(track);setError("");}catch(error){setError(messageOf(error));}};
  const favorite=(track:NativeTrack) => <button className="pipo-icon-button" aria-label={`${account.favoriteIds?.has(track.neteaseId || 0)?"取消喜欢":"喜欢"}${track.title}`} disabled={!track.neteaseId || account.pendingLikes.has(track.neteaseId)} onClick={() => {void like(track);}} style={{color:account.favoriteIds?.has(track.neteaseId || 0)?"#ff4d67":"var(--browse-muted)"}}><Icon name={account.favoriteIds?.has(track.neteaseId || 0)?"heartFilled":"heart"} /></button>;
  return <Sheet label="接下来播放" onClose={onClose}><div className="pipo-sheet-content pipo-queue-content"><div className="pipo-section-row"><h2 style={{fontSize:24,fontWeight:400}}>接下来播放</h2><button className="pipo-text-button" style={{color:"var(--pipo-accent)"}} onClick={() => player.setMode(queueModes[(queueModes.indexOf(mode)+1)%queueModes.length].value)}><Icon name={mode.icon} size={20} />{mode.label}</button></div>{error && <p className="pipo-error">{error}</p>}<p className="pipo-queue-section-label">正在播放</p>{state.current?<div className="pipo-queue-row"><Artwork url={state.current.artworkUrl} style={{width:48}} /><div className="pipo-track-copy"><span className="pipo-track-title">{state.current.title}</span><span className="pipo-track-artist">{state.current.artist}</span></div>{favorite(state.current)}<IconButton name={state.playing?"pause":"PlayArrow"} label={state.playing?"暂停":"播放"} onClick={player.toggle} className="pipo-accent" /></div>:<p className="pipo-notice">还没有正在播放的歌曲</p>}<p className="pipo-queue-section-label" style={{padding:"18px 0"}}>待播歌曲</p>{state.queue.slice(state.index+1).map((track,i) => <div className="pipo-queue-row" key={`${track.id}:${i}`}><Artwork url={track.artworkUrl} style={{width:44}} /><button className="pipo-track-copy" style={{textAlign:"left"}} onClick={() => player.playTrack(track.id)}><span className="pipo-track-title">{track.title}</span><span className="pipo-track-artist">{track.artist}</span></button>{favorite(track)}<IconButton name="RemoveCircleOutline" label={`从待播队列移除${track.title}`} onClick={() => player.remove(state.index+1+i)} /></div>)}{state.queue.length<=state.index+1 && <p className="pipo-notice">没有更多待播歌曲</p>}</div></Sheet>;
}
