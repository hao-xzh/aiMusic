"use client";

import { diagnostic } from "./diagnostics";

type Channel={audio:HTMLAudioElement;id:string;activated?:boolean;source?:MediaElementAudioSourceNode;fade?:GainNode;loudness?:GainNode;analyser?:AnalyserNode;rms?:number};
type Handoff={token:number;outgoing:Channel;incoming:Channel;nextId:string;started:number;duration:number;audible:boolean;timer?:ReturnType<typeof setTimeout>;complete:()=>void};
const linearRms=(rms:number|undefined)=>rms==null||!Number.isFinite(rms)||rms>=0?1:Math.pow(10,Math.max(-12,Math.min(0,-16-rms))/20);

/** Two encoded streams, one clock and one transition owner. Never decode a complete track into a JS AudioBuffer. */
export class StreamingAudio {
  private channels:[Channel,Channel];
  private selected=0;
  private context:AudioContext|undefined;
  private master:GainNode|undefined;
  private handoff:Handoff|undefined;
  private token=0;
  private resumePositions=new Map<string,number>();
  private samples=new Float32Array(1024);
  private removers:Array<()=>void>=[];
  private requested=false;
  private interrupted=false;
  private fallbackGain=1;

  constructor(audio:HTMLAudioElement,private onHandoff:(id:string,position:number,outgoingPosition:number,listenedMs:number)=>void) {
    const spare=new Audio();spare.preload="auto";spare.setAttribute("playsinline","");
    this.channels=[{audio,id:""},{audio:spare,id:""}];
    for(const channel of this.channels) {
      const playing=()=>{channel.activated=true;};
      const ended=()=>{const mix=this.handoff;if(!mix) return;if(channel===mix.outgoing&&mix.audible&&!mix.incoming.audio.paused) this.finish(mix);else if(channel===mix.incoming) this.cancel(false,"incoming-ended");};
      const failed=()=>{if(this.handoff?.incoming===channel) this.cancel(false,"incoming-stalled");};
      channel.audio.addEventListener("playing",playing);channel.audio.addEventListener("ended",ended);channel.audio.addEventListener("error",failed);channel.audio.addEventListener("waiting",failed);
      this.removers.push(()=>{channel.audio.removeEventListener("playing",playing);channel.audio.removeEventListener("ended",ended);channel.audio.removeEventListener("error",failed);channel.audio.removeEventListener("waiting",failed);});
    }
  }
  get current(){return this.channels[this.selected].audio;}
  get id(){return this.channels[this.selected].id;}
  get mixing(){return !!this.handoff;}
  get canMix(){return !!this.context&&this.context.state==="running"&&this.channels.every(channel=>!!channel.fade);}
  get outputReady(){return !this.context||this.context.state==="running";}
  listen(event:string,listener:(audio:HTMLAudioElement)=>void) {
    for(const channel of this.channels) {const callback=()=>{if(channel.audio===this.current) listener(channel.audio);};channel.audio.addEventListener(event,callback);this.removers.push(()=>channel.audio.removeEventListener(event,callback));}
  }
  private ensureGraph() {
    if(this.context) return;
    const Constructor=window.AudioContext||(window as Window & {webkitAudioContext?:typeof AudioContext}).webkitAudioContext;
    if(!Constructor) return;
    let context:AudioContext;
    try {context=new Constructor({latencyHint:"playback"});}catch {diagnostic("audio_context","unavailable");return;}
    this.context=context;
    const master=context.createGain();master.gain.value=this.fallbackGain;master.connect(context.destination);this.master=master;
    for(const channel of this.channels) {
      const loudness=context.createGain(),fade=context.createGain(),analyser=context.createAnalyser();
      let source:MediaElementAudioSourceNode;
      try {source=context.createMediaElementSource(channel.audio);}catch {diagnostic("audio_context","stream_binding_failed");continue;}
      analyser.fftSize=1024;analyser.smoothingTimeConstant=0;loudness.gain.value=linearRms(channel.rms);
      source.connect(analyser);analyser.connect(loudness);loudness.connect(fade);fade.connect(master);
      Object.assign(channel,{source,loudness,fade,analyser});channel.audio.volume=1;
    }
    context.onstatechange=()=>{
      const state=String(context.state);diagnostic("audio_context","state",{state});
      if(state==="interrupted") this.interrupted=true;
      else if(state==="running"&&this.interrupted) {this.interrupted=false;if(this.requested) void this.current.play().catch(()=>undefined);}
    };
  }
  async play() {
    this.requested=true;
    this.ensureGraph();
    const context=this.context;
    // Both calls stay in the user gesture; neither waits for a React effect or network preparation.
    const resume=context&&context.state!=="running"?context.resume():Promise.resolve();
    await Promise.all([resume,this.current.play()]);
  }
  pause() {this.requested=false;this.cancel(false,"pause");this.current.pause();}
  async recover() {
    if(!this.requested||!this.context||!this.interrupted) return;
    try {await this.context.resume();if(this.context.state==="running") {this.interrupted=false;await this.current.play();}}catch{/* A new play gesture can resume after OS audio interruption. */}
  }
  volume(value:number) {this.fallbackGain=value;if(this.master) this.master.gain.setValueAtTime(value,this.context!.currentTime);else this.current.volume=value;}
  tempo(value:number) {if(!this.handoff) this.current.playbackRate=value;}
  amplitude() {const analyser=this.channels[this.selected].analyser;if(!analyser||!this.canMix||this.current.paused) return 0;analyser.getFloatTimeDomainData(this.samples);let sum=0;for(const value of this.samples) sum+=value*value;return Math.min(1,Math.sqrt(sum/this.samples.length));}
  normalize(audio:HTMLAudioElement,rms:number|undefined) {const channel=this.channels.find(value=>value.audio===audio);if(!channel||channel.rms===rms) return;channel.rms=rms;if(channel.loudness&&this.context) channel.loudness.gain.setTargetAtTime(linearRms(rms),this.context.currentTime,.12);}
  prepare(id:string,url:string,rms?:number) {
    if(this.handoff) return;const next=this.channels[1-this.selected];this.normalize(next.audio,rms);
    if(next.id===id&&next.audio.getAttribute("src")===url) return;
    next.audio.pause();next.id=id;next.audio.src=url;next.audio.playbackRate=1;next.audio.preload="auto";next.audio.load();
    if(next.fade&&this.context) {next.fade.gain.cancelScheduledValues(this.context.currentTime);next.fade.gain.setValueAtTime(0,this.context.currentTime);}
  }
  select(id:string,url:string,resume=0) {
    this.cancel(false,"select");const old=this.channels[this.selected],next=this.channels[1-this.selected];
    const remembered=this.resumePositions.get(id)||0;this.resumePositions.delete(id);resume=Math.max(resume,remembered);
    // WebKit grants playback permission per element. Keep the activated element until the spare has actually played.
    if(next.activated&&next.id===id&&next.audio.getAttribute("src")===url&&!next.audio.error) this.selected=1-this.selected;
    this.current.pause();old.audio.pause();const chosen=this.channels[this.selected];chosen.id=id;chosen.rms=undefined;
    if(chosen.audio.getAttribute("src")!==url||chosen.audio.error) {chosen.audio.src=url;chosen.audio.load();}else if(chosen.audio.readyState>=1) chosen.audio.currentTime=resume;
    chosen.audio.playbackRate=1;
    if(chosen.fade&&this.context) {chosen.fade.gain.cancelScheduledValues(this.context.currentTime);chosen.fade.gain.setValueAtTime(1,this.context.currentTime);}
    return resume;
  }
  private cancel(preserveCurrent:boolean,reason:string) {
    ++this.token;const mix=this.handoff;if(!mix) return;this.handoff=undefined;clearTimeout(mix.timer);
    if(mix.audible) this.resumePositions.set(mix.nextId,Math.max(this.resumePositions.get(mix.nextId)||0,mix.incoming.audio.currentTime));
    const time=this.context?.currentTime||0;
    mix.incoming.audio.pause();mix.incoming.audio.playbackRate=1;
    for(const channel of this.channels) {if(channel.fade) {channel.fade.gain.cancelScheduledValues(time);channel.fade.gain.setValueAtTime(channel.audio===this.current?1:0,time);}}
    if(!preserveCurrent) this.current.playbackRate=1;
    diagnostic("transition","cancelled",{reason,nextId:mix.nextId,preservedPosition:this.resumePositions.get(mix.nextId)||0});
  }
  cancelForSeek(){this.cancel(false,"seek");}
  discardPrepared(forgetPositions=false){this.cancel(false,"queue-changed");if(forgetPositions) this.resumePositions.clear();const spare=this.channels[1-this.selected];spare.audio.pause();spare.audio.removeAttribute("src");spare.audio.load();spare.id="";}
  async crossfade(id:string,durationMs:number,entryMs:number,speed:number):Promise<boolean> {
    if(this.handoff||!this.canMix) return false;
    const outgoing=this.channels[this.selected],incoming=this.channels[1-this.selected];
    if(incoming.id!==id||incoming.audio.readyState<3||outgoing.audio.paused||!outgoing.fade||!incoming.fade) return false;
    const token=++this.token,mix:Handoff={token,outgoing,incoming,nextId:id,started:0,duration:durationMs/1000,audible:false,complete:()=>this.finish(mix)};
    this.handoff=mix;
    const entry=Math.max(entryMs/1000,this.resumePositions.get(id)||0);incoming.audio.currentTime=entry;incoming.audio.playbackRate=speed;
    try {
      await incoming.audio.play();
      if(this.handoff!==mix||token!==this.token) return false;
      if(incoming.audio.readyState<3||incoming.audio.paused) {this.cancel(false,"incoming-not-ready");return false;}
      const context=this.context!,now=context.currentTime,duration=Math.min(durationMs/1000,Math.max(.1,(outgoing.audio.duration-outgoing.audio.currentTime)/outgoing.audio.playbackRate));
      mix.started=now;mix.duration=duration;mix.audible=true;
      const down=new Float32Array(256),up=new Float32Array(256);for(let i=0;i<256;i++){const theta=i/255*Math.PI/2;down[i]=Math.cos(theta);up[i]=Math.sin(theta);}
      outgoing.fade.gain.cancelScheduledValues(now);incoming.fade.gain.cancelScheduledValues(now);
      outgoing.fade.gain.setValueCurveAtTime(down,now,duration);incoming.fade.gain.setValueCurveAtTime(up,now,duration);
      mix.timer=setTimeout(mix.complete,duration*1000+12);
      diagnostic("transition","crossfade_started",{from:outgoing.id,to:id,durationMs:Math.round(duration*1000),entryMs:Math.round(entry*1000),speed});
      return true;
    }catch {if(this.handoff===mix) this.cancel(false,"incoming-play-failed");return false;}
  }
  private finish(mix:Handoff) {
    if(this.handoff!==mix||!mix.audible) return;
    if(mix.incoming.audio.paused||mix.incoming.audio.error) {this.cancel(false,"incoming-unavailable-at-handoff");return;}
    this.handoff=undefined;clearTimeout(mix.timer);this.selected=this.channels.indexOf(mix.incoming);
    const time=this.context!.currentTime;
    mix.outgoing.fade!.gain.cancelScheduledValues(time);mix.outgoing.fade!.gain.setValueAtTime(0,time);
    mix.incoming.fade!.gain.cancelScheduledValues(time);mix.incoming.fade!.gain.setValueAtTime(1,time);
    mix.outgoing.audio.pause();mix.outgoing.audio.playbackRate=1;
    // Keep the live incoming element and its media position. Never restart its intro on another decoder.
    const position=mix.incoming.audio.currentTime;this.resumePositions.delete(mix.nextId);
    mix.incoming.audio.playbackRate=1;this.onHandoff(mix.nextId,position,mix.outgoing.audio.currentTime,Math.min(mix.duration,Math.max(0,time-mix.started))*1000);
    diagnostic("transition","handoff_complete",{trackId:mix.nextId,position});
  }
  async safeCut(id:string,fadeOutMs:number,fadeInMs:number,action:()=>boolean) {
    if(this.handoff||!this.canMix) return false;const channel=this.channels[this.selected],context=this.context!,start=context.currentTime,token=++this.token;
    channel.fade!.gain.cancelScheduledValues(start);channel.fade!.gain.setValueAtTime(1,start);channel.fade!.gain.linearRampToValueAtTime(0,start+fadeOutMs/1000);
    await new Promise(resolve=>setTimeout(resolve,Math.round(fadeOutMs*.92)));
    if(token!==this.token||this.current!==channel.audio||channel.audio.paused) {channel.fade?.gain.cancelScheduledValues(context.currentTime);channel.fade?.gain.setValueAtTime(1,context.currentTime);return false;}
    if(!action()) {channel.fade?.gain.cancelScheduledValues(context.currentTime);channel.fade?.gain.setValueAtTime(1,context.currentTime);return false;}
    const current=this.channels[this.selected];
    current.fade!.gain.cancelScheduledValues(context.currentTime);current.fade!.gain.setValueAtTime(0,context.currentTime);
    current.fade!.gain.linearRampToValueAtTime(1,context.currentTime+fadeInMs/1000);
    diagnostic("transition","safe_cut",{trackId:id,fadeOutMs,fadeInMs});return true;
  }
  dispose() {
    this.requested=false;this.cancel(false,"dispose");++this.token;this.removers.forEach(remove=>remove());this.removers=[];
    for(const channel of this.channels){channel.audio.pause();channel.audio.removeAttribute("src");channel.audio.load();channel.source?.disconnect();channel.analyser?.disconnect();channel.loudness?.disconnect();channel.fade?.disconnect();}
    if(this.context){this.context.onstatechange=null;void this.context.close();}
  }
}
