"use client";

import { useSyncExternalStore } from "react";
import { audioUrl, artworkUrl } from "./api";
import { defaultSettings, type NativeSettings, type NativeTrack } from "./model";
import { messageOf, readPreference, writePreference } from "./storage";
import { preferences } from "./settings";
import type { PlayerCommand,PlayerAcknowledgement } from "./agent-engine";
import { StreamingAudio } from "./streaming-audio";
import { audioFeatures } from "./music-analysis";
import { alignedStart,planTransition,type MixPlan,type NativeAudioFeatures } from "./transition";
import { diagnostic } from "./diagnostics";

export type QueueMode = NativeSettings["playbackMode"];
export type ContinuousSource = { fetch:(excluded:NativeTrack[]) => Promise<NativeTrack[]>; accepts?:(track:NativeTrack) => boolean; sourceText?:string };
type PlayerSnapshot = { queue:NativeTrack[]; index:number; current:NativeTrack | null; playing:boolean; buffering:boolean; duration:number; error:string; mode:QueueMode; revision:number; volume:number };
const initial:PlayerSnapshot = { queue:[],index:-1,current:null,playing:false,buffering:false,duration:0,error:"",mode:defaultSettings.playbackMode,revision:0,volume:1 };
export type ListeningEvent = { type:"PlayStarted" | "Completed" | "ManualCut" | "Skipped"; track:NativeTrack; tsMs:number; listenedMs:number; durationMs:number; completionPct:number };
type SavedPlayer = { queue:NativeTrack[]; index:number; position:number; order:string[]; mode:QueueMode };
function shuffle<T>(input:T[]):T[] {
  const result = [...input]; for (let i=result.length-1;i>0;i--) { const j=Math.floor(Math.random()*(i+1)); [result[i],result[j]]=[result[j],result[i]]; }
  if (result.length>1 && result.every((value,i) => value === input[i])) result.push(result.shift()!); return result;
}
class NativePlayer {
  private state = initial;
  private listeners = new Set<() => void>();
  private clockListeners = new Set<() => void>();
  private mountedAudio:HTMLAudioElement | null = null;
  private pipeline:StreamingAudio | undefined;
  private get audio() {return this.pipeline?.current || null;}
  private transitionTimer:ReturnType<typeof setTimeout>|undefined;
  private transitionBusy=false;
  private transitionState:{key:string;a?:NativeAudioFeatures;b?:NativeAudioFeatures;duration:number;plan:MixPlan|null;quietTicks:number;lastTick:number;attempted:boolean}|undefined;
  private playRevision = 0; private queueRevision = 0; private originalOrder:string[] = [];
  private source:ContinuousSource | undefined; private fallbackSource:ContinuousSource | undefined;
  private extension:Promise<void> | undefined; private retryAt = 0; private failures = 0;
  private preferredMode:QueueMode = defaultSettings.playbackMode;
  private restorePosition = 0;
  private manualSelection=0;private applyingAgent=false;
  private lastSaved = 0;
  private lastMedia = 0; private lastWall = 0; private listened = 0; private started = false; private recorded = false;
  private eventListeners = new Set<(event:ListeningEvent) => void>();
  private removers:Array<() => void> = [];
  subscribe = (listener:() => void) => { this.listeners.add(listener); return () => { this.listeners.delete(listener); }; };
  subscribeClock = (listener:() => void) => { this.clockListeners.add(listener); return () => { this.clockListeners.delete(listener); }; };
  onListening = (listener:(event:ListeningEvent) => void) => { this.eventListeners.add(listener); return () => { this.eventListeners.delete(listener); }; };
  snapshot = () => this.state; serverSnapshot = () => initial;
  position = () => this.restorePosition || this.audio?.currentTime || 0;
  amplitude = () => this.pipeline?.amplitude() || 0;
  selectionRevision=() => this.manualSelection;
  private markSelection() {if(this.applyingAgent) return;this.manualSelection++;writePreference("selectionRevision",this.manualSelection);}
  private update(patch:Partial<PlayerSnapshot>) { this.state={...this.state,...patch}; this.listeners.forEach(listener => listener()); }
  private tick = () => { this.clockListeners.forEach(listener => listener()); };
  attach = (audio:HTMLAudioElement) => {
    if (this.mountedAudio === audio) return () => undefined;
    this.detach(); this.mountedAudio=audio;this.pipeline=new StreamingAudio(audio,this.handoff);audio.preload="auto";
    const session=(navigator as Navigator & {audioSession?:{type:string}}).audioSession;if(session) {try {session.type="playback";} catch {}} audio.setAttribute("playsinline","");
    const on = (event:string,handler:(audio:HTMLAudioElement) => void) => this.pipeline!.listen(event,handler);
    on("play",() => { this.update({ playing:true,error:"" }); this.mediaState(); });
    on("playing",audio => { this.update({ playing:true,buffering:false }); this.lastWall=performance.now(); this.lastMedia=audio.currentTime; if (!this.started) { this.started=true; this.emitListening("PlayStarted"); }this.scheduleTransition(); });
    on("pause",() => { this.measure(); this.update({ playing:false,buffering:false }); this.persist(); this.mediaState();clearTimeout(this.transitionTimer); });
    on("waiting",audio => { if (!audio.paused) { this.measure(); this.update({ buffering:true }); } });
    on("stalled",audio => { if (!audio.paused && audio.readyState<3) this.update({ buffering:true }); });
    on("loadedmetadata",audio => {
      const duration = Number.isFinite(audio.duration) ? audio.duration : (this.state.current?.durationMs || 0)/1000;
      this.update({ duration }); if (this.restorePosition) { const position=this.restorePosition; this.restorePosition=0; audio.currentTime=Math.min(position,Math.max(0,duration-.1)); }
      this.mediaState(); this.tick();
    });
    on("durationchange",audio => { if (Number.isFinite(audio.duration)) this.update({ duration:audio.duration }); });
    on("timeupdate",() => { this.measure(); this.tick(); if (Date.now()-this.lastSaved>5000) this.persist(); this.maybeExtend();this.transitionTick(); });
    on("seeking",audio => { this.lastMedia=audio.currentTime; this.lastWall=performance.now(); });
    on("seeked",audio => { this.lastMedia=audio.currentTime; this.lastWall=performance.now(); this.tick(); this.persist(); this.mediaState(); });
    on("ended",audio => { this.measure(); this.emitListening("Completed"); if (this.state.mode === "SingleLoop") { this.listened=0; this.recorded=false; this.started=false; audio.currentTime=0; this.play(); } else this.advance(1,false); });
    on("error",audio => { if (!this.state.current) return; this.update({ playing:false,buffering:false,error:audio.error?.code === 4 ? "这首歌的音频格式暂时无法播放，请重试或切换下一首。" : "音频加载失败，请检查网络或账号权限后重试。" }); this.mediaState();diagnostic("playback","stream_error",{id:this.state.current.id,code:audio.error?.code || 0,message:audio.error?.message||"",ready:audio.readyState,network:audio.networkState,position:audio.currentTime});void this.describeStreamError(audio,this.playRevision); });
    const save=() => this.persist(); window.addEventListener("pagehide",save); document.addEventListener("visibilitychange",save);
    this.removers.push(() => { window.removeEventListener("pagehide",save); document.removeEventListener("visibilitychange",save); });
    const recover=() => {if(document.visibilityState==="visible") {void this.pipeline?.recover();this.scheduleTransition();}};document.addEventListener("visibilitychange",recover);this.removers.push(() => document.removeEventListener("visibilitychange",recover));
    if ("mediaSession" in navigator) {
      const actions:Partial<Record<MediaSessionAction,MediaSessionActionHandler>> = {
        play:() => this.play(),pause:() => this.pause(),nexttrack:() => this.next(),previoustrack:() => this.previous(),
        seekto:event => { if (event.seekTime != null) this.seek(event.seekTime); },seekforward:event => this.seek(this.position()+(event.seekOffset || 10)),seekbackward:event => this.seek(this.position()-(event.seekOffset || 10)),stop:() => this.pause(),
      };
      for (const [name,handler] of Object.entries(actions)) { try { navigator.mediaSession.setActionHandler(name as MediaSessionAction,handler!); } catch { /* The browser advertises only actions it implements. */ } }
      this.removers.push(() => { for (const name of Object.keys(actions)) { try { navigator.mediaSession.setActionHandler(name as MediaSessionAction,null); } catch {} } });
    }
    const saved=readPreference<SavedPlayer | null>("player",null); const settings=readPreference<NativeSettings>("settings",defaultSettings);
    this.preferredMode=settings.playbackMode;
    this.manualSelection=readPreference("selectionRevision",0);
    if (saved?.queue.length) { this.originalOrder=saved.order || saved.queue.map(track => track.id); this.update({ queue:saved.queue,mode:saved.mode || this.preferredMode }); this.select(Math.max(0,Math.min(saved.index,saved.queue.length-1)),false,saved.position); }
    else this.update({ mode:this.preferredMode });
    return () => { if (this.mountedAudio === audio) this.detach(); };
  };
  private detach() {
    this.persist(); ++this.playRevision; this.removers.forEach(remove => remove()); this.removers=[];
    clearTimeout(this.transitionTimer);this.pipeline?.dispose();this.pipeline=undefined;this.mountedAudio=null;this.transitionState=undefined;this.transitionBusy=false;
  }
  private measure() {
    const audio=this.audio; if (!audio) return; const now=performance.now(), media=audio.currentTime;
    if (this.state.playing && !this.state.buffering && !audio.seeking && this.lastWall>0) {
      const advance=Math.max(0,media-this.lastMedia), wall=Math.max(0,(now-this.lastWall)/1000);
      this.listened+=Math.min(advance,wall*audio.playbackRate)*1000;
    }
    this.lastWall=now; this.lastMedia=media;
  }
  private emitListening(type:ListeningEvent["type"],position=this.position()) {
    const track=this.state.current; if (!track || (type !== "PlayStarted" && this.recorded)) return;
    if (type !== "PlayStarted") this.recorded=true;
    const durationMs=this.state.duration*1000 || track.durationMs;
    const event:ListeningEvent={ type,track,tsMs:Date.now(),listenedMs:Math.round(this.listened),durationMs,completionPct:durationMs>0 ? Math.min(1,position*1000/durationMs) : 0 };
    this.eventListeners.forEach(listener => listener(event));
  }
  private mediaState() {
    if (!("mediaSession" in navigator)) return; navigator.mediaSession.playbackState=this.state.playing ? "playing" : "paused";
    if (this.state.duration>0) { try { navigator.mediaSession.setPositionState({ duration:this.state.duration,playbackRate:this.audio?.playbackRate || 1,position:Math.min(this.state.duration,this.position()) }); } catch {} }
  }
  private metadata(track:NativeTrack) {
    if (!("mediaSession" in navigator) || !("MediaMetadata" in window)) return;
    const cover=track.artworkUrl ? artworkUrl(track.artworkUrl) : "/icon-512.png";
    navigator.mediaSession.metadata=new MediaMetadata({ title:track.title,artist:track.artist,album:track.album,artwork:[{ src:new URL(cover,location.href).href,sizes:track.artworkUrl?"any":"512x512" }] });
  }
  private streamUrl(track:NativeTrack) {const owner=readPreference<{userId:number}|null>("lastAccount",null)?.userId;return track.neteaseId?audioUrl(track.neteaseId,this.audio?.canPlayType("audio/flac")?"lossless":"exhigh",owner):track.streamUrl || "";}
  private async describeStreamError(audio:HTMLAudioElement,revision:number) {
    const source=audio.getAttribute("src");if(!source?.startsWith("/api/pwa/audio/")) return;
    const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),8000);
    try {const response=await fetch(source,{method:"HEAD",credentials:"same-origin",cache:"no-store",signal:controller.signal});if(revision!==this.playRevision||audio!==this.audio) return;diagnostic("playback","stream_response",{status:response.status,type:response.headers.get("Content-Type")||"",bytes:Number(response.headers.get("Content-Length"))||0});const message=response.headers.get("X-Pipo-Audio-Error");if(!response.ok&&message) this.update({error:decodeURIComponent(message)});}catch{/* Keep the original media error when the diagnostic connection also fails. */}finally {clearTimeout(timer);}
  }
  private select(index:number,autoplay:boolean,resume=0) {
    const track=this.state.queue[index],audio=this.audio; if (!track || !audio) return;
    ++this.playRevision; audio.pause();
    this.transitionState=undefined;this.transitionBusy=false;clearTimeout(this.transitionTimer);
    this.restorePosition=resume; this.listened=0; this.lastMedia=resume; this.lastWall=0; this.started=false; this.recorded=false;
    this.update({ index,current:track,duration:track.durationMs/1000,buffering:autoplay,error:"",playing:false,revision:this.state.revision+1 });
    this.metadata(track);
    // Set the source synchronously in the originating gesture/ended callback. No React effect gates playback.
    this.restorePosition=this.pipeline!.select(track.id,this.streamUrl(track),resume);
    if(this.audio && this.audio.readyState>=1) {this.audio.currentTime=this.restorePosition;this.restorePosition=0;if(Number.isFinite(this.audio.duration)) this.update({duration:this.audio.duration});}
    if (autoplay) this.play();
    this.tick(); this.persist(); this.maybeExtend();
  }
  play = () => {
    const audio=this.audio; if (!audio?.getAttribute("src")) return; const revision=this.playRevision;
    void this.pipeline!.play().catch(error => { if (revision !== this.playRevision || error?.name === "AbortError") return; this.update({ playing:false,buffering:false,error:error?.name === "NotAllowedError" ? "请点播放继续聆听。" : messageOf(error) }); });
  };
  pause = () => { this.pipeline?.pause();clearTimeout(this.transitionTimer); };
  toggle = () => { if (this.audio?.paused) this.play(); else this.pause(); };
  retry = () => { if (this.state.index>=0) this.select(this.state.index,true,this.position()); };
  seek = (seconds:number) => { if (!this.audio || !Number.isFinite(seconds)) return; this.measure();this.pipeline?.cancelForSeek();this.transitionState=undefined;this.transitionBusy=false;this.restorePosition=0; this.audio.currentTime=Math.max(0,Math.min(seconds,this.state.duration || seconds)); this.lastMedia=this.audio.currentTime; this.lastWall=performance.now(); this.tick(); this.persist(); this.mediaState();this.scheduleTransition(); };
  volume = (value:number) => { const volume=Math.max(0,Math.min(1,value));this.pipeline?.volume(volume);this.update({ volume }); };
  private nextIndex() {
    const {index,mode,queue}=this.state;if(index<0 || queue.length<2 || mode==="SingleLoop") return -1;
    const next=index+1;if(next<queue.length) return next;
    return mode==="PlaylistLoop" || mode==="ShufflePlay" ? 0 : -1;
  }
  upcoming = () => this.state.queue[this.nextIndex()];
  private invalidateQueue(forgetPositions=false) {++this.queueRevision;this.transitionState=undefined;this.transitionBusy=false;this.pipeline?.discardPrepared(forgetPositions);}
  private scheduleTransition = () => {
    clearTimeout(this.transitionTimer);if(!this.audio || this.audio.paused || !this.state.playing) return;
    this.transitionTick();const remaining=this.state.duration-this.position();
    this.transitionTimer=setTimeout(this.scheduleTransition,remaining>14?1000:80);
  };
  private transitionTick() {
    const pipeline=this.pipeline,audio=this.audio,current=this.state.current,nextIndex=this.nextIndex(),next=this.state.queue[nextIndex];
    if(!pipeline || !audio || !current || audio.paused || this.state.buffering || audio.seeking || this.transitionBusy || pipeline.mixing || !Number.isFinite(audio.duration)) return;
    const a=audioFeatures.get(current.id);pipeline.normalize(audio,a?.rmsDb);
    if(!next || current.id===next.id) return;
    const duration=audio.duration*1000,remaining=duration-audio.currentTime*1000,b=audioFeatures.get(next.id);
    if(remaining>13_000 || remaining<=0) return;
    pipeline.prepare(next.id,this.streamUrl(next),b?.rmsDb);
    const key=`${this.queueRevision}:${this.playRevision}:${current.id}:${next.id}`;
    if(this.transitionState?.key!==key || this.transitionState.a!==a || this.transitionState.b!==b || this.transitionState.duration!==duration) {
      this.transitionState={key,a,b,duration,plan:planTransition(current,next,a,b,duration),quietTicks:0,lastTick:0,attempted:false};
      diagnostic("transition","plan",{from:current.id,to:next.id,policy:this.transitionState.plan?.policy || "NoMix",reason:this.transitionState.plan?.reason || "preserve-original-boundary",crossfade:this.transitionState.plan?.crossfade || false});
    }
    const state=this.transitionState,plan=state.plan;if(!plan || !pipeline.canMix || state.attempted) return;
    const now=performance.now();if(now-state.lastTick<65) return;state.lastTick=now;
    const position=audio.currentTime*1000;
    if(plan.crossfade) {
      const earliest=duration-plan.crossfadeMs,start=plan.alignment?alignedStart(plan.alignment,earliest,plan.entryMs):earliest;
      if(position<start || remaining<350) return;
      state.attempted=true;this.transitionBusy=true;
      void pipeline.crossfade(next.id,Math.min(plan.crossfadeMs,remaining),plan.entryMs,plan.alignment?.nextTempoRatio || 1).then(started => {
        if(this.transitionState!==state) return;this.transitionBusy=false;if(!started) {state.plan={...plan,crossfade:false};state.attempted=false;}
      });return;
    }
    if(plan.tempoLeadMs && remaining<=plan.tempoLeadMs) pipeline.tempo(1+(plan.tempoSpeed-1)*Math.min(1,(plan.tempoLeadMs-remaining)/1600));
    if(remaining>plan.mixMs || remaining<plan.fadeOutMs+80) return;
    state.quietTicks=pipeline.amplitude()<=plan.tailThreshold?state.quietTicks+1:0;
    if(state.quietTicks<plan.tailTicks) return;
    state.attempted=true;this.transitionBusy=true;const version=this.playRevision,queueVersion=this.queueRevision;
    void pipeline.safeCut(next.id,plan.fadeOutMs,plan.fadeInMs,() => {
      if(version!==this.playRevision || queueVersion!==this.queueRevision || this.state.queue[nextIndex]?.id!==next.id) return false;
      this.measure();this.emitListening("Completed");this.select(nextIndex,true,plan.entryMs/1000);return true;
    }).finally(() => {if(this.transitionState===state) this.transitionBusy=false;});
  }
  private handoff = (id:string,position:number,outgoingPosition:number,listenedMs:number) => {
    const now=performance.now(),advance=Math.max(0,outgoingPosition-this.lastMedia),wall=Math.max(0,(now-this.lastWall)/1000);
    this.listened+=Math.min(advance,wall*1.035)*1000;this.emitListening("Completed",outgoingPosition);
    const nextIndex=this.nextIndex();if(this.state.queue[nextIndex]?.id!==id) {this.pipeline?.pause();this.update({playing:false,error:"播放队列已变化，请重新选择歌曲。"});return;}
    const track=this.state.queue[nextIndex];++this.playRevision;this.restorePosition=0;this.listened=listenedMs;this.lastMedia=position;this.lastWall=now;this.started=true;this.recorded=false;this.transitionState=undefined;this.transitionBusy=false;
    const duration=this.audio && Number.isFinite(this.audio.duration)?this.audio.duration:track.durationMs/1000;
    this.update({index:nextIndex,current:track,duration,playing:true,buffering:false,error:"",revision:this.state.revision+1});this.metadata(track);this.mediaState();this.emitListening("PlayStarted");this.tick();this.persist();this.maybeExtend();this.scheduleTransition();
  };
  playQueue = (tracks:NativeTrack[],source?:ContinuousSource) => {
    const unique=tracks.filter((track,i) => tracks.findIndex(other => other.id === track.id) === i); if (!unique.length) return;
    this.markSelection();
    if(!this.applyingAgent) writePreference("activeIntent",null);
    this.measure(); this.emitListening(this.state.duration && this.position()/this.state.duration<.5 ? "Skipped" : "ManualCut");
    this.invalidateQueue(true); this.originalOrder=unique.map(track => track.id); this.source=source; this.extension=undefined; this.retryAt=0; this.failures=0;
    const queue=this.preferredMode === "ShufflePlay" && !source && !this.applyingAgent ? [unique[0],...shuffle(unique.slice(1))] : unique;
    this.update({ queue,mode:source ? "AiRadio" : this.preferredMode }); this.select(0,true);
  };
  playTrack = (id:string) => { const index=this.state.queue.findIndex(track => track.id === id); if (index>=0) { this.markSelection();this.measure(); this.emitListening("ManualCut"); this.select(index,true); } };
  enqueueNext = (track:NativeTrack) => {
    if (!this.state.queue.length) { this.playQueue([track]); return; }
    this.markSelection();
    this.invalidateQueue();
    const before=this.state.queue.slice(0,this.state.index+1),after=this.state.queue.slice(this.state.index+1).filter(item => item.id !== track.id);
    // A next insertion is a separate queue occurrence when the current song is inserted again.
    this.update({ queue:[...before,track,...after] }); this.originalOrder=this.state.queue.map(item => item.id); this.persist();
  };
  append = (tracks:NativeTrack[]) => { const ids=new Set(this.state.queue.map(track => track.id)); const fresh=tracks.filter(track => !ids.has(track.id) && !!ids.add(track.id)); if (!fresh.length) return; this.update({ queue:[...this.state.queue,...fresh] }); this.originalOrder.push(...fresh.map(track => track.id)); this.persist(); };
  remove = (index:number) => { if (index<0 || index>=this.state.queue.length) return;this.markSelection();this.invalidateQueue();const queue=this.state.queue.filter((_,i) => i!==index);if(index===this.state.index) {this.measure();this.emitListening("ManualCut");} if (!queue.length) { this.pause(); this.audio?.removeAttribute("src");this.audio?.load();this.originalOrder=[];this.update({ ...initial,mode:this.preferredMode });this.persist();return; } const oldIndex=this.state.index;this.originalOrder=queue.map(track => track.id);this.update({ queue,index:index<oldIndex ? oldIndex-1 : oldIndex }); if (index===oldIndex) this.select(Math.min(index,queue.length-1),this.state.playing); this.persist(); };
  next = () => this.advance(1,true); previous = () => this.advance(-1,true);
  private advance(direction:number,manual:boolean) {
    const { queue,index,mode }=this.state; if (!queue.length) return;
    if (manual) { this.markSelection();this.measure(); this.emitListening(direction>0 && this.state.duration>0 && this.position()/this.state.duration<.5 ? "Skipped" : "ManualCut"); }
    const raw=index+direction;
    if (mode === "OrderOnce" && (raw<0 || raw>=queue.length)) { if (direction<0) this.seek(0); else this.pause(); return; }
    if (direction>0 && raw>=queue.length && mode === "AiRadio" && (this.source || this.fallbackSource)) {
      const before=this.state.current?.id,version=this.playRevision; this.pause(); this.update({ buffering:true });
      void this.extend(true).finally(() => { if (version !== this.playRevision || before !== this.state.current?.id) return; this.select(raw<this.state.queue.length ? raw : 0,true); }); return;
    }
    this.select((raw+queue.length)%queue.length,true);
  }
  setMode = (mode:QueueMode) => {
    const old=this.state.mode;this.invalidateQueue();this.preferredMode=mode; let queue=this.state.queue;
    const first=this.state.index+1,tail=queue.slice(first);
    if (mode === "ShufflePlay" && old !== mode) queue=[...queue.slice(0,first),...shuffle(tail)];
    else if (old === "ShufflePlay" && mode !== old) { const rank=new Map(this.originalOrder.map((id,i) => [id,i])); queue=[...queue.slice(0,first),...tail.sort((a,b) => (rank.get(a.id) ?? Infinity)-(rank.get(b.id) ?? Infinity))]; }
    this.update({ mode,queue });preferences.update({playbackMode:mode}); this.persist(); this.maybeExtend();
  };
  setFallbackSource = (source:ContinuousSource) => { this.fallbackSource=source; };
  private maybeExtend() { if (this.state.mode === "AiRadio" && this.state.queue.length-this.state.index<=4 && Date.now()>=this.retryAt) void this.extend(false); }
  private extend(force:boolean) {
    if (this.extension) return this.extension; const source=this.source || this.fallbackSource; if (!source || (!force && Date.now()<this.retryAt)) return Promise.resolve();
    const version=this.playRevision,queueVersion=this.queueRevision;
    const promise=(async () => {
      try { const more=await source.fetch(this.state.queue); if (queueVersion!==this.queueRevision || (source !== (this.source || this.fallbackSource)) || this.state.mode !== "AiRadio") return; const accepted=more.filter(track => source.accepts?.(track) ?? true); if (!accepted.length) throw new Error("暂时没有合适的新歌，稍后继续补充"); this.append(accepted); this.failures=0; this.retryAt=Date.now()+3000; }
      catch (error) { this.retryAt=Date.now()+Math.min(60_000,5000*2**Math.min(this.failures++,4)); if (force && version===this.playRevision) this.update({ error:messageOf(error) }); }
    })(); this.extension=promise; void promise.finally(() => { if (this.extension===promise) this.extension=undefined; }); return promise;
  }
  persist = () => {
    if (!this.audio) return; this.lastSaved=Date.now();
    const value:SavedPlayer={ queue:this.state.queue,index:this.state.index,position:this.position(),order:this.originalOrder,mode:this.state.mode };
    // Queue metadata is written only at events and every five seconds, never on animation frames.
    writePreference("player",value);
  };
  applyAgent=async (command:PlayerCommand,source?:ContinuousSource):Promise<PlayerAcknowledgement> => {
    const audible=()=>this.state.playing&&!this.state.buffering&&!!this.audio&&this.audio.readyState>=3&&!!this.pipeline?.outputReady;
    const previous=readPreference<Record<string,PlayerAcknowledgement>>("appliedCommands",{});if(previous[command.id]) return {...previous[command.id],current:this.state.current,queue:this.state.queue,selectionRevision:this.manualSelection,playing:audible()};
    let applied=false;
    const result=(ok:boolean,message:string):PlayerAcknowledgement => ({ok,applied,message,current:this.state.current,queue:this.state.queue,selectionRevision:this.manualSelection,playing:audible()});
    if(command.selectionRevision!==this.manualSelection) return result(false,"你已经手动更改了播放选择，这次旧请求没有覆盖当前队列。");
    if(command.kind==="skip" && (this.state.mode==="OrderOnce" && this.state.index>=this.state.queue.length-1 || this.state.mode!=="AiRadio" && this.state.queue.length<2)) return result(false,"当前已没有可切换的下一首，未执行切歌。");
    this.applyingAgent=true;
    try {
      if(command.kind==="skip") this.next();
      else {
        const {goal,tracks}=command;if(!tracks.length) return result(false,"没有可提交的曲目。");
        if(goal.operation==="insert_next" && this.state.current) {for(const track of [...tracks].reverse()) this.enqueueNext(track);if(goal.jump_to_inserted) this.advance(1,false);}
        else if(goal.preserve_current && this.state.current) {this.invalidateQueue();this.source=source;this.extension=undefined;this.update({queue:[...this.state.queue.slice(0,this.state.index+1),...tracks],mode:source?"AiRadio":"OrderOnce"});this.originalOrder=this.state.queue.map(track => track.id);this.persist();this.maybeExtend();}
        else {this.playQueue(tracks,source);if(!source) this.update({mode:"OrderOnce"});}
        if(goal.operation!=="insert_next") writePreference("activeIntent",{goal,taskId:command.taskId,text:source?.sourceText || ""});
      }
    } finally {this.applyingAgent=false;}
    applied=true;
    this.persist();
    const pending=result(true,"");previous[command.id]=pending;writePreference("appliedCommands",Object.fromEntries(Object.entries(previous).slice(-80)));
    const startsPlayback=command.kind==="skip" || !command.goal.preserve_current&&(command.goal.operation!=="insert_next"||command.goal.jump_to_inserted);
    if(startsPlayback && !this.state.error) await new Promise<void>(resolve => {
      const started=performance.now(),position=this.position();
      const timer=setInterval(() => {
        if(this.state.error || performance.now()-started>=8000 || audible()&&this.position()-position>=.75) {clearInterval(timer);resolve();}
      },100);
    });
    const ack=result(!this.state.error || this.state.error==="请点播放继续聆听。",this.state.error || (this.state.buffering?"音频仍在加载。":""));previous[command.id]=ack;writePreference("appliedCommands",Object.fromEntries(Object.entries(previous).slice(-80)));return ack;
  };
  restoreContinuous=(source:ContinuousSource) => {this.source=source;if(readPreference<{mode:QueueMode}|null>("player",null)?.mode==="AiRadio") this.update({mode:"AiRadio"});this.maybeExtend();};
}
export const player=new NativePlayer();
export function usePlayerState() { return useSyncExternalStore(player.subscribe,player.snapshot,player.serverSnapshot); }
