"use client";

import { memo,useEffect,useLayoutEffect,useMemo,useRef,type CSSProperties } from "react";
import { charProgress,type YrcChar,type YrcLine } from "@/lib/yrc";
import { player,usePlayerState } from "@/lib/pwa/player";
import { Spring,bezier,clamp01,smooth } from "@/lib/pwa/motion";
import type { Lyrics as LyricsData } from "@/lib/pwa/lyrics";

type Slot={line:YrcLine;start:number;end:number;interlude?:boolean};
function audioEnd(line:YrcLine):number { return Math.max(line.time+line.durSec,...line.chars.map(char => char.startSec+char.durSec),...(line.companionLines || []).filter(line => line.role==="companion").map(audioEnd)); }
function slotsFor(lines:YrcLine[]):Slot[] {
  const slots:Slot[]=[];
  lines.forEach((line,i) => {
    const start=line.chars[0]?.startSec ?? line.time;
    if(i===0 && start>=9) slots.push({line:{time:0,durSec:start,text:"",chars:[]},start:0,end:start,interlude:true});
    const end=Math.max(start,Math.min(audioEnd(line) || lines[i+1]?.time || start+5,lines[i+1]?.time || Infinity));
    slots.push({line,start,end}); const next=lines[i+1]?.chars[0]?.startSec ?? lines[i+1]?.time;
    if(next != null && next-end>=9) slots.push({line:{time:end,durSec:next-end,text:"",chars:[]},start:end,end:next,interlude:true});
  }); return slots;
}
const attack=bezier(.2,.4,.58,1),release=bezier(.3,0,.58,1),ordinary=bezier(0,0,.58,1);
const displayText=(text:string) => text.replace(/[()]/g,"").replace(/\s+$/u,"\u2009");
function isChinese(text:string) { return /[\u3400-\u9fff]/u.test(text) && !/[\u3040-\u30ff\uac00-\ud7af]/u.test(text); }
const TimedText=memo(function TimedText({line}:{line:YrcLine}) {
  if(!line.chars.length) return <span className="pipo-lyric-plain">{line.text}</span>;
  return <>{line.chars.map((char,index) => {
    const text=displayText(char.text),content=Array.from(text.trim()),slow=char.durSec>=1 && content.length<=7 && content.length>0;
    return <span key={index} className={`pipo-lyric-word ${slow ? "pipo-slow-word" : ""}`} data-token={index} data-text={text} style={{"--word-sung":0} as CSSProperties}>
      {slow ? content.map((letter,i) => <span key={i} className="pipo-lyric-letter" data-letter={i} data-text={letter}>{letter}</span>) : text}
      {slow && /\s$/u.test(text) ? "\u2009" : null}
    </span>;
  })}</>;
});
const LyricRow=memo(function LyricRow({slot,index,showTranslation,onSelect}:{slot:Slot;index:number;showTranslation:boolean;onSelect:(index:number) => void}) {
  const {line}=slot;
  return <div className="pipo-lyric-row" data-row={index} data-interlude={!!slot.interlude} data-chinese={isChinese(line.text)} style={{textAlign:line.alignment==="end"?"right":"left",transformOrigin:line.alignment==="end"?"right top":"left top"}}>
    {slot.interlude ? <div className="pipo-interlude" aria-label="间奏"><i /><i /><i /></div> : <button className="pipo-lyric-line" onClick={() => onSelect(index)} aria-label={`定位歌词：${line.text}`}>
      <span className="pipo-lyric-main"><TimedText line={line} /></span>
      {(line.companionLines || []).filter(sub => sub.role==="companion").map((sub,i) => <span key={i} className="pipo-lyric-companion" data-companion={i}><TimedText line={sub} /></span>)}
      <span className="pipo-lyric-translations" data-visible={showTranslation}><span>{(line.companionLines || []).filter(sub => sub.role==="romaji" || sub.role==="translation").sort((a,b) => Number(a.role==="translation")-Number(b.role==="translation")).map((sub,i) => <span className="pipo-lyric-subline" data-role={sub.role} key={i}>{sub.text}</span>)}</span></span>
    </button>}
  </div>;
});

/** Native lyric layout and motion, with a single media clock and DOM writes confined to visible rows. */
export function NativeLyrics({data,showTranslation,landscape=false,active=true}:{data:LyricsData;showTranslation:boolean;landscape?:boolean;active?:boolean}) {
  const state=usePlayerState(); const slots=useMemo(() => slotsFor(data.lines),[data.lines]);
  const viewport=useRef<HTMLDivElement>(null); const content=useRef<HTMLDivElement>(null);
  const selectRef=useRef<(index:number) => void>(() => undefined);
  const onSelect=useRef((index:number) => selectRef.current(index)).current;
  useLayoutEffect(() => {
    const root=viewport.current,list=content.current; if(!active || !root || !list || !slots.length) return;
    const rows=Array.from(list.querySelectorAll<HTMLElement>("[data-row]")); let tops:number[]=[],heights:number[]=[],height=0;
    const spring=new Spring(0,140,1); let ready=false,targetIndex=-1,previousTime=player.position(),lastFrame=performance.now(),frame=0;
    let holdTimer:ReturnType<typeof setTimeout>|undefined;
    let holdUntil=0,dragY:number | null=null,dragOrigin=0,pending=-1,pendingUntil=0,staggerFirst=0,stagger=false,settledAt=0;
    let history:Array<[number,number]>=[]; let lastVisibleStart=-1,lastVisibleEnd=-1;
    const wordMaps=rows.map((row,i) => {
      const main=row.querySelector<HTMLElement>(".pipo-lyric-main");
      const maps:Array<{node:HTMLElement;token:YrcChar;letters:HTMLElement[];settledPhase?:number}>=[];
      const add=(element:HTMLElement|null,line:YrcLine) => { element?.querySelectorAll<HTMLElement>("[data-token]").forEach(node => { const token=line.chars[Number(node.dataset.token)]; if(token) maps.push({node,token,letters:Array.from(node.querySelectorAll<HTMLElement>("[data-letter]"))}); }); };
      add(main,slots[i].line); const companions=(slots[i].line.companionLines || []).filter(line => line.role==="companion"); row.querySelectorAll<HTMLElement>("[data-companion]").forEach(node => add(node,companions[Number(node.dataset.companion)])); return maps;
    });
    const indexAt=(time:number) => { let lo=0,hi=slots.length; while(lo<hi) { const mid=(lo+hi)>>1; if(slots[mid].start<=time) lo=mid+1; else hi=mid; } return Math.max(0,lo-1); };
    const measure=() => {
      height=root.clientHeight; const width=root.clientWidth-(landscape?0:48),font=Math.max(28,Math.min(34,width/10,height/6))*(landscape?.88:1);
      root.style.setProperty("--lyric-font",`${font}px`);
      const old=tops[targetIndex] || 0; tops=rows.map(row => row.offsetTop); heights=rows.map(row => row.offsetHeight);
      if(ready && targetIndex>=0 && Math.abs(tops[targetIndex]-old)>.5) { const delta=tops[targetIndex]-old; spring.value+=delta; spring.target+=delta; history=history.map(([time,value]) => [time,value+delta]); }
      draw(performance.now());
    };
    const centerAt=(time:number) => { if(!history.length) return spring.value; if(time<=history[0][0]) return history[0][1]; for(let i=history.length-1;i>0;i--) if(history[i-1][0]<=time) { const [a,x]=history[i-1],[b,y]=history[i]; return x+(y-x)*clamp01((time-a)/Math.max(1,b-a)); } return spring.value; };
    const draw=(now:number) => {
      const position=player.position(),current=indexAt(position+.25),jump=Math.abs(position-previousTime)>.8;
      previousTime=position;
      if(!ready) { spring.snap(tops[current] || 0); targetIndex=current; ready=true; }
      if(now>=holdUntil && dragY==null && current!==targetIndex) {
        stagger=current>targetIndex && !jump; staggerFirst=Math.max(0,rows.findIndex((_,i) => (tops[i]||0)+(heights[i]||60)>spring.value));
        if(stagger) history=[[now,spring.value]];
        if(jump && Math.abs(current-targetIndex)>Math.ceil(height/60)+7) spring.snap(tops[current] || 0); else spring.to(tops[current] || 0);
        targetIndex=current;
      }
      if(pending>=0 && now>pendingUntil) { pending=-1; root.dataset.pending="false"; }
      if(now>=holdUntil && dragY==null && !spring.settled) spring.step((now-lastFrame)/1000);
      if(spring.settled) { if(!settledAt) settledAt=now; if(now-settledAt>300) stagger=false; } else settledAt=0;
      lastFrame=now; history.push([now,spring.value]); if(history.length>180) history.shift(); while(history.length>2 && history[1][0]<now-2000) history.shift();
      const anchor=landscape ? Math.min(height*.35-((34*.88*1.2059624+24)*.8)-80+90,8) : 14;
      list.style.transform=`translate3d(0,${anchor-spring.value}px,0)`;
      let first=0; while(first<rows.length && tops[first]+heights[first]<spring.value-height*.18) first++;
      // Manual browsing may be far from the playing line. Only animate the viewport and its overscan.
      const start=Math.max(0,first-7); let end=first; while(end<rows.length && tops[end]<spring.value+height*1.7) end++; end=Math.min(rows.length,end+7);
      if(start!==lastVisibleStart || end!==lastVisibleEnd) {
        rows.forEach((row,i) => { row.style.visibility=i>=start && i<end ? "inherit" : "hidden"; }); lastVisibleStart=start; lastVisibleEnd=end;
      }
      const manual=now<holdUntil || dragY!=null;
      for(let i=start;i<end;i++) {
        const row=rows[i],slot=slots[i]; let delay=0,step=50; for(let n=0;n<Math.min(8,Math.max(0,i-staggerFirst));n++) {delay+=step;step/=1.05;}
        const delayed=stagger && !manual ? centerAt(now-Math.min(300,delay)) : spring.value;
        const span=spring.value<tops[i] ? tops[i]-(tops[i-1]??tops[i]-heights[i]) : (tops[i+1]??tops[i]+heights[i])-tops[i];
        const focus=pending>=0 ? (pending===i?1:0) : smooth(1-Math.abs(tops[i]-spring.value)/Math.max(1,span));
        row.style.transform=`translateY(${spring.value-delayed}px) scale(${1+.05*focus})`;
        const singing=position>=slot.start && position<=slot.end,isCurrent=i===current;
        row.style.setProperty("--line-alpha",String(landscape ? .4+.45*focus : .175+.745*focus));
        row.dataset.active=String(singing || isCurrent || (manual && focus>.5)); row.dataset.selected=String(pending===i);
        if(landscape) row.style.filter=(singing || isCurrent || i===0 || manual) ? "none" : "blur(2px)";
        if(slot.interlude) {
          const duration=slot.end-slot.start,progress=clamp01((position-slot.start)/Math.max(.01,duration)); const remain=slot.end-position;
          const scale=remain<1.5 ? 1+.4*Math.sin(Math.PI*clamp01((1.5-remain)/1.5)) : 1+.2*Math.max(0,Math.sin((position-slot.start)/5*Math.PI*2));
          row.querySelectorAll<HTMLElement>("i").forEach((dot,n) => { dot.style.opacity=String(.3+.7*clamp01(progress*3-n)); dot.style.transform=`scale(${scale*(remain<.12?clamp01(remain/.12):1)})`; }); continue;
        }
        for(const word of wordMaps[i]) {
          const {node,token,letters}=word,elapsed=position-token.startSec;
          // Unsung and fully settled words retain their DOM styles until a seek or active sweep changes them.
          const phase=elapsed<0?0:elapsed>=Math.max(1,token.durSec)*(letters.length?1.8:1)?2:1;
          if(phase!==1&&word.settledPhase===phase) continue;
          word.settledPhase=phase;
          const progress=charProgress(token,position),sweep=progress*120-20;
          node.style.setProperty("--sweep-start",`${sweep}%`); node.style.setProperty("--sweep-end",`${sweep+20}%`);
          const baseLift=ordinary(clamp01(elapsed/Math.max(1,token.durSec)));
          node.style.transform=letters.length?"none":`translateY(${-2*baseLift}px)`;
          if(letters.length) {
            const duration=Math.max(1,token.durSec),gain=clamp01((token.durSec-1)/.6),step=duration/letters.length*.4;
            letters.forEach((letter,n) => {
              const phase=clamp01((elapsed-step*n)/duration),float=clamp01((elapsed-step*n)/(duration*1.4)),emphasis=(phase<.5?attack(phase*2):1-release((phase-.5)*2))*gain;
              const x=22*.015*emphasis*(n-(letters.length-1)*.5),y=-2*baseLift-22*(.05*Math.sin(float*Math.PI)*gain+.0125*emphasis);
              letter.style.transform=`translate(${x}px,${y}px) scale(${1+.05*emphasis})`; letter.style.setProperty("--letter-glow",String(.4*emphasis));
              const charProgress=clamp01(progress*letters.length-n); letter.style.setProperty("--sweep-start",`${charProgress*120-20}%`); letter.style.setProperty("--sweep-end",`${charProgress*120}%`);
            });
          }
        }
      }
    };
    const loop=(now:number) => { frame=0;if(document.visibilityState!=="visible") return; draw(now); if(player.snapshot().playing || !spring.settled || holdUntil>now || stagger) frame=requestAnimationFrame(loop); };
    const wake=() => { if(document.visibilityState!=="visible") {cancelAnimationFrame(frame);frame=0;return;}if(frame) return;lastFrame=performance.now();frame=requestAnimationFrame(loop); };
    selectRef.current=(index:number) => {
      const now=performance.now(),current=indexAt(player.position()+.25);
      if(pending===index && now<=pendingUntil) { if(index!==current || player.position()<slots[index].start) player.seek(slots[index].start); pending=-1; pendingUntil=0; holdUntil=0; spring.to(tops[index] || 0); targetIndex=index; }
      else { pending=index; pendingUntil=now+2800; holdUntil=now+3000; spring.snap(tops[index] || 0); root.dataset.pending="true"; }
      wake();
    };
    const down=(event:PointerEvent) => { if(event.button!==0) return; dragY=event.clientY;dragOrigin=spring.value;holdUntil=performance.now()+3000;stagger=false; };
    const move=(event:PointerEvent) => { if(dragY==null) return; const offset=event.clientY-dragY; if(Math.abs(offset)>6) { pending=-1; root.setPointerCapture(event.pointerId); const total=(tops.at(-1)||0)+(heights.at(-1)||0); spring.snap(Math.max(-height*.18,Math.min(total+height*.7,dragOrigin-offset))); wake(); } };
    const up=() => { if(dragY==null) return; dragY=null;holdUntil=performance.now()+3000;clearTimeout(holdTimer);holdTimer=setTimeout(() => { if(performance.now()>=holdUntil) { targetIndex=-1;wake(); } },3010);wake(); };
    const wheel=(event:WheelEvent) => { event.preventDefault(); const total=(tops.at(-1)||0)+(heights.at(-1)||0); spring.snap(Math.max(-height*.18,Math.min(total+height*.7,spring.value+event.deltaY)));holdUntil=performance.now()+3000;stagger=false;clearTimeout(holdTimer);holdTimer=setTimeout(() => { if(performance.now()>=holdUntil) {targetIndex=-1;wake();} },3010);wake(); };
    root.addEventListener("pointerdown",down);root.addEventListener("pointermove",move);root.addEventListener("pointerup",up);root.addEventListener("pointercancel",up);root.addEventListener("wheel",wheel,{passive:false});document.addEventListener("visibilitychange",wake);
    const observer=new ResizeObserver(measure);observer.observe(root);rows.forEach(row => observer.observe(row)); measure();wake();
    const unclock=player.subscribeClock(wake),unstate=player.subscribe(wake);
    return () => { clearTimeout(holdTimer);cancelAnimationFrame(frame);observer.disconnect();unclock();unstate();selectRef.current=() => undefined;root.removeEventListener("pointerdown",down);root.removeEventListener("pointermove",move);root.removeEventListener("pointerup",up);root.removeEventListener("pointercancel",up);root.removeEventListener("wheel",wheel);document.removeEventListener("visibilitychange",wake); };
  },[slots,landscape,active]);
  useEffect(() => { if(content.current) content.current.dataset.translation=String(showTranslation); },[showTranslation]);
  if(data.loading) return <div className="pipo-lyric-placeholder">歌词加载中…</div>;
  if(!slots.length) return <div className="pipo-lyric-placeholder">{data.instrumental?"♪ 纯音乐 ♪":data.error || "这首歌没有歌词"}</div>;
  return <div ref={viewport} className={`pipo-native-lyrics ${landscape?"pipo-landscape-lyrics":""}`} aria-label={`${state.current?.title || ""}歌词`}><div ref={content} className="pipo-lyric-flow">{slots.map((slot,i) => <LyricRow key={`${slot.start}:${i}`} slot={slot} index={i} showTranslation={showTranslation} onSelect={onSelect} />)}</div></div>;
}
