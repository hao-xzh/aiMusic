"use client";

import { useEffect, useMemo, useRef, useState, type CSSProperties, type RefObject } from "react";
import { assistant, useAssistant } from "@/lib/pwa/assistant";
import type { AgentCard } from "@/lib/pwa/agent-engine";
import { usePreferences } from "@/lib/pwa/settings";
import { player,usePlayerState } from "@/lib/pwa/player";
import { blend, cssRgb, luma, usePalette, type Palette, type RGB } from "@/lib/pwa/palette";
import { messageOf } from "@/lib/pwa/storage";
import { compactCaption,dailyGreeting } from "@/lib/pwa/captions";
import { aiReady } from "@/lib/pwa/taste";
import { useBackLayer } from "@/lib/pwa/back-navigation";
import { Artwork } from "./Shared";
import { Icon, type IconName } from "./Icon";
import { AssistantWake } from "./AssistantWake";

const hints=["在。说吧。","醒着呢。","嗯？","想听啥。","随便说。","说点。","嗯。"];
const suggestions=["放点燃的","我刚才听啥","来点类似的"];
const saturation=(rgb:RGB) => Math.max(...rgb)?1-Math.min(...rgb)/Math.max(...rgb):0;
function petPalette(edges:Palette) {
  const bg:RGB=[10,13,20], white:RGB=[255,255,255], neutral:RGB=[200,195,186];
  const normalize=(rgb:RGB) => {let out=luma(rgb)<.18*255?blend(rgb,white,.46)!:luma(rgb)>.86*255?blend(rgb,bg,.22)!:rgb;if(saturation(out)<.08) out=blend(out,neutral,.4)!;return out;};
  const rank=(rgb:RGB) => saturation(rgb)*.62+Math.max(0,1-Math.abs(luma(rgb)/255-.56)/.56)*.38;
  const accent=normalize([edges.right,edges.bottom,edges.top].filter((rgb):rgb is RGB=>!!rgb).sort((a,b)=>rank(b)-rank(a))[0]||neutral);
  const top=normalize(edges.top||accent), lifted=luma(accent)<.28*255?blend(accent,white,.58)!:luma(accent)>.78*255?blend(accent,bg,.24)!:accent;
  const face=blend([224,217,196],lifted,.76)!, panel=blend(bg,accent,.3)!, user=blend(accent,luma(accent)>.56*255?[16,20,30]:[245,247,255],luma(accent)>.56*255?.22:.2)!;
  return {accent,face,halo:blend(accent,face,.34)!,rope:blend(top,white,.45)!,panel,user};
}

type SpeechResult={isFinal:boolean;0:{transcript:string}};
type SpeechSession={lang:string;continuous:boolean;interimResults:boolean;onresult:((event:{resultIndex:number;results:ArrayLike<SpeechResult>})=>void)|null;onend:(()=>void)|null;onerror:((event:{error:string})=>void)|null;start:()=>void;stop:()=>void;abort:()=>void};
type SpeechWindow=Window & {SpeechRecognition?:new()=>SpeechSession;webkitSpeechRecognition?:new()=>SpeechSession};

export function NativeAssistant({open,onClose,onOpen,onSettings,pageRef,showDecorations,keyboard,requestKeyboard}:{open:boolean;onClose:()=>void;onOpen:()=>void;onSettings:()=>void;pageRef:RefObject<HTMLDivElement|null>;showDecorations:boolean;keyboard:number;requestKeyboard:boolean}) {
  const state=useAssistant(), playback=usePlayerState(), {settings,ai}=usePreferences(), edges=usePalette(playback.current?.artworkUrl);
  const palette=useMemo(()=>petPalette(edges),[edges]);
  const [ready,setReady]=useState(false), [input,setInput]=useState(""), [hint,setHint]=useState(hints[0]), [voice,setVoice]=useState(false), [voiceStatus,setVoiceStatus]=useState("");
  const inputRef=useRef<HTMLInputElement>(null), speech=useRef<SpeechSession|null>(null), history=useRef<HTMLDivElement>(null), beforeFocus=useRef<HTMLElement|null>(null),host=useRef<HTMLDivElement>(null),composer=useRef<HTMLDivElement>(null);
  const [caption,setCaption]=useState(""),[captionVisible,setCaptionVisible]=useState(false),captionTimer=useRef<ReturnType<typeof setTimeout>|undefined>(undefined);
  const showCaption=(value:string) => {const text=compactCaption(value);if(!text) return;clearTimeout(captionTimer.current);setCaption(text);setCaptionVisible(true);captionTimer.current=setTimeout(() => setCaptionVisible(false),5600);};
  const reply=[...state.tasks].reverse().find(task => task.reply)?.reply;
  useEffect(() => {if(reply) showCaption(reply);return () => clearTimeout(captionTimer.current);},[reply]);
  useEffect(() => {if(!settings.aiNarration || !showDecorations || !aiReady() || document.visibilityState!=="visible") return;let cancelled=false;void dailyGreeting().then(text => {if(text && !cancelled) showCaption(text);});return () => {cancelled=true;};},[settings.aiNarration,showDecorations,ai]);
  useEffect(() => {const element=composer.current;if(!element) return;const observer=new ResizeObserver(() => host.current?.style.setProperty("--ai-composer-height",`${element.getBoundingClientRect().height}px`));observer.observe(element);return () => observer.disconnect();},[]);
  const closing=useRef(onClose); closing.current=onClose;
  useBackLayer(open,() => closing.current());
  useEffect(()=>{
    let timer:ReturnType<typeof setTimeout>|undefined, focusTimer:ReturnType<typeof setTimeout>|undefined;
    if(open) {
      beforeFocus.current=document.activeElement as HTMLElement; setHint(hints[Math.floor(Math.random()*hints.length)]);
      timer=setTimeout(()=>setReady(true),650);
      if(requestKeyboard) focusTimer=setTimeout(()=>inputRef.current?.focus({preventScroll:true}),1450);
    } else {setReady(false);speech.current?.abort();inputRef.current?.blur();beforeFocus.current?.focus?.({preventScroll:true});}
    return ()=>{clearTimeout(timer);clearTimeout(focusTimer);};
  },[open,requestKeyboard]);
  useEffect(()=>{
    if(!open) return;const close=(event:KeyboardEvent)=>{if(event.key==="Escape") {event.preventDefault();closing.current();}};
    document.addEventListener("keydown",close);return ()=>document.removeEventListener("keydown",close);
  },[open]);
  useEffect(()=>()=>{speech.current?.abort();},[]);
  useEffect(()=>{if(open) history.current?.scrollTo({top:0,behavior:ready?"smooth":"instant"});},[state.revision,ready,open]);
  const submit=async(text=input)=>{
    if(!text.trim()||state.busy||voice) return;inputRef.current?.blur();if(await assistant.submit(text.trim())) setInput("");
  };
  const startVoice=()=>{
    if(speech.current) {speech.current.stop();return;}
    const Recognition=(window as SpeechWindow).SpeechRecognition||(window as SpeechWindow).webkitSpeechRecognition;
    if(!Recognition) {setVoiceStatus("此浏览器暂不支持语音识别，可使用键盘上的听写输入。");return;}
    const recognition=new Recognition(); speech.current=recognition;recognition.lang="zh-CN";recognition.continuous=false;recognition.interimResults=true;
    const prefix=input.trim();let committed="";
    recognition.onresult=event=>{let interim="";for(let i=event.resultIndex;i<event.results.length;i++) {const result=event.results[i];if(result.isFinal) committed+=result[0].transcript;else interim+=result[0].transcript;}setInput([prefix,committed+interim].filter(Boolean).join(" "));};
    recognition.onend=()=>{speech.current=null;setVoice(false);setVoiceStatus("");};
    recognition.onerror=event=>{setVoice(false);speech.current=null;setVoiceStatus(event.error==="not-allowed"?"请允许麦克风权限，或改用文字输入。":event.error==="no-speech"?"没有听清，再试一次。":"语音识别暂时不可用，请重试或输入文字。");recognition.onend=null;};
    try {inputRef.current?.blur();recognition.start();setVoice(true);setVoiceStatus("正在听…说完后点发送。");}catch(error){speech.current=null;setVoiceStatus(messageOf(error));}
  };
  const active=state.tasks.find(task=>["queued","running","waiting_player"].includes(task.status));
  const css={"--ai-accent":cssRgb(palette.accent),"--ai-face":cssRgb(palette.face),"--ai-face-light":cssRgb(blend(palette.face,[255,255,255],.18)!),"--ai-face-mid":cssRgb(blend(palette.face,palette.accent,.26)!),"--ai-face-ink":luma(palette.face)>.52*255?"#1b1815":"#f5f7ff","--ai-halo":cssRgb(palette.halo,.72),"--ai-rope":cssRgb(palette.rope,.82),"--ai-panel":cssRgb(palette.panel,.92),"--ai-panel-text":luma(palette.panel)>.58*255?"#0a0d14":"#f5f7ff","--ai-action-text":luma(palette.accent)>.58*255?"#0a0d14":"#f5f7ff","--ai-user":cssRgb(palette.user,.96),"--ai-user-text":luma(palette.user)>.58*255?"#0a0d14":"#f5f7ff","--keyboard-inset":`${keyboard}px`} as CSSProperties;
  return <div ref={host} className="pipo-native-assistant" data-open={open} data-ready={ready} style={css}>
    <div className="pipo-ai-dim"/>
    <AssistantWake open={open} target={pageRef}/>
    {open&&<button className="pipo-ai-dismiss" aria-label="关闭 AI 对话" onClick={onClose}/>}
    <div className="pipo-ai-conversation" aria-hidden={!open} inert={!open}>
      <div className="pipo-ai-history" ref={history} role="log" aria-label="AI 对话" aria-live="polite">
        {state.error&&<div className="pipo-ai-error" role="alert">{state.error}<button onClick={()=>{void assistant.refresh();}}>重试</button></div>}
        {state.pendingText&&<div className="pipo-ai-task"><div className="pipo-ai-user-row"><p className="pipo-ai-bubble pipo-ai-user">{state.pendingText}</p></div><p className="pipo-ai-bubble">正在连接…</p></div>}
        {active&&<div className="pipo-ai-pending"><span className="pipo-ai-bubble"><i/><i/><i/></span><div>{active.stage}<button onClick={()=>{void assistant.cancel(active.id).catch(error=>setVoiceStatus(messageOf(error)));}}>停止</button></div></div>}
        {[...state.tasks].reverse().map(task=><div className="pipo-ai-task" key={task.id}>
          <div className="pipo-ai-user-row"><p className="pipo-ai-bubble pipo-ai-user">{task.text}</p></div>
          {task.cards.map((card,index)=><ResultCard key={index} card={card}/>)}
          {task.reply&&<p className="pipo-ai-bubble">{task.reply}</p>}
        </div>)}
      </div>
      {!state.tasks.length&&!state.busy&&<div className="pipo-ai-suggestions">{suggestions.map(text=><button key={text} onClick={()=>{void submit(text);}}>{text}</button>)}</div>}
    </div>
    <div ref={composer} className="pipo-ai-composer" aria-hidden={!open} inert={!open}>
      {voiceStatus&&<p className="pipo-ai-voice-status" role="status">{voiceStatus}</p>}
      <form onSubmit={event=>{event.preventDefault();void submit();}}>
        <button type="button" aria-label={voice?"结束语音输入":"语音输入"} disabled={state.busy} onClick={startVoice}><Icon name={voice?"Stop":"Mic"} size={21}/></button>
        <input ref={inputRef} aria-label="告诉 PIPO 你想听什么" value={input} placeholder={hint} onChange={event=>setInput(event.target.value)} onFocus={()=>{speech.current?.stop();}} enterKeyHint="send" autoComplete="off" maxLength={4000}/>
        <button type="submit" aria-label="发送" disabled={!input.trim()||state.busy||voice}><span><Icon name="ArrowUpward" size={18}/></span></button>
      </form>
    </div>
    {showDecorations&&!settings.hideAiPetOrb&&!open&&<PetOrb playing={playback.playing} pending={state.busy} onOpen={onOpen}/>}
    <div className="pipo-cover-caption" data-visible={showDecorations&&settings.hideAiPetOrb&&!open&&captionVisible} aria-hidden={!showDecorations||!settings.hideAiPetOrb||open||!captionVisible}><img src="/pwa/artwork/launcher-round.png" alt="" width="20" height="20"/><span>{caption}</span></div>
    {open&&!state.ready&&state.error&&<button className="pipo-ai-config-link" onClick={onSettings}>AI 设置</button>}
  </div>;
}

function ResultCard({card}:{card:AgentCard}) {
  if(card.kind==="play") return <div className="pipo-ai-result"><span className="pipo-ai-result-play"><Icon name="play" size={18}/></span>{card.tracks?.filter(track=>track.artworkUrl).slice(0,3).map(track=><Artwork url={track.artworkUrl} key={track.id}/>)}<span className="pipo-ai-result-copy"><strong>{card.label}</strong><small>{[...new Set(card.tracks?.map(track=>track.artist))].slice(0,3).join("、")}</small></span></div>;
  const icon:IconName=card.kind==="like"?"heartFilled":card.kind==="unlike"?"heart":card.kind==="skip"?"next":card.kind==="playlistRemove"?"RemoveCircleOutline":card.kind==="error"?"close":"playlistAdd";
  return <div className="pipo-ai-action" data-ok={card.ok}><span><Icon name={icon} size={15}/></span>{card.label}</div>;
}

function PetOrb({playing,pending,onOpen}:{playing:boolean;pending:boolean;onOpen:()=>void}) {
  const element=useRef<HTMLButtonElement>(null);
  useEffect(()=>{
    if(!playing) return;let frame=0,last=0,angle=0,velocity=0,phase=0,ampSmooth=0,ampSlow=0,beatLevel=0,lastBeat=0,nextGust=0;const phases=[Math.random(),Math.random(),Math.random()].map(value=>value*Math.PI*2);
    const draw=(now:number)=>{if(document.visibilityState!=="visible") return;const dt=Math.min(.05,Math.max(.001,(now-(last||now-16))/1000));last=now;const t=now/1000;
      const norm=Math.max(0,Math.min(1,(player.amplitude()-.25)/.7));ampSmooth+=(norm-ampSmooth)*(1-Math.exp(-dt/.2));ampSlow+=(norm-ampSlow)*(1-Math.exp(-dt/.8));
      const breeze=(Math.sin(t*.18+phases[0])+Math.sin(t*.31+phases[1])*.55+Math.sin(t*.49+phases[2])*.3)*ampSmooth*3.4,excess=norm-ampSlow;
      if(excess>.12&&now-lastBeat>160&&ampSmooth>.05) {const direction=Math.abs(velocity)>1.5?Math.sign(velocity):Math.random()>.5?1:-1;velocity+=(5+excess*26)*direction;beatLevel=Math.min(1,beatLevel+.6+excess*1.4);lastBeat=now;}
      beatLevel+=(0-beatLevel)*(1-Math.exp(-dt/.16));
      if(now>=nextGust) {if(ampSmooth>.08) velocity+=(5+Math.random()*6)*(.8+ampSmooth*1.9)*(Math.random()>.5?1:-1);nextGust=now+1500+Math.random()*3300-ampSmooth*1800;}
      velocity+=((breeze*2.8-angle)*20-velocity*3.6)*dt;angle+=velocity*dt;if(angle>18) {angle=18;velocity=Math.min(0,velocity);}else if(angle< -18) {angle=-18;velocity=Math.max(0,velocity);}
      if(pending) angle+=(Math.sin(t*Math.PI*2*1.8)*8-angle)*(1-Math.exp(-dt/.15));
      phase+=dt*(pending?2:1+ampSmooth*.4);const pulse=.5+.5*Math.sin(phase*Math.PI*2);
      element.current?.style.setProperty("--orb-angle",`${angle}deg`);element.current?.style.setProperty("--orb-scale",String(pending?1+.08*Math.sin(t*Math.PI*2*1.5):1+ampSmooth*.06+beatLevel*.1));element.current?.style.setProperty("--halo-scale",String(1+pulse*.3));element.current?.style.setProperty("--halo-opacity",String((.4+(1-pulse)*.6)*.45));frame=requestAnimationFrame(draw);
    };const wake=()=>{cancelAnimationFrame(frame);last=0;if(document.visibilityState==="visible") frame=requestAnimationFrame(draw);};wake();document.addEventListener("visibilitychange",wake);
    return()=>{cancelAnimationFrame(frame);document.removeEventListener("visibilitychange",wake);element.current?.style.setProperty("--orb-angle","0deg");element.current?.style.setProperty("--orb-scale","1");};
  },[playing,pending]);
  return <button ref={element} className="pipo-pet-orb" aria-label="唤醒 PIPO" onClick={onOpen}><span className="pipo-pet-rope"/><span className="pipo-pet-body"><i className="pipo-pet-halo"/><svg viewBox="0 0 100 100"><ellipse cx="34" cy="36" rx="4.5" ry={pending?2:8}/><ellipse cx="66" cy="36" rx="4.5" ry={pending?2:8}/><path d="M20 64C30 75 42 78 54 69C62 61 72 58 80 63" fill="none" stroke="currentColor" strokeWidth="7.5" strokeLinecap="round" strokeLinejoin="round"/></svg></span></button>;
}
