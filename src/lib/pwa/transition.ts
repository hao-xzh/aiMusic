import type { AudioFeatures } from "@/lib/tauri";
import type { NativeTrack } from "./model";

// Source: Android SmartAutoMixer, TransitionScore and BeatPhaseAlignment at d1d8128.
export type NativeAudioFeatures=AudioFeatures & {
  firstBeatS:number|null;introLowEnergy:number;outroLowEnergy:number;
  introVocalDensity:number;outroVocalDensity:number;drumEntryS:number|null;
  vocalEntryS:number|null;outroStartS:number|null;tonalKey:number|null;tonalConfidence:number;
};
export function nativeFeatures(value:AudioFeatures):NativeAudioFeatures {
  return {firstBeatS:null,introLowEnergy:0,outroLowEnergy:0,introVocalDensity:0,outroVocalDensity:0,drumEntryS:null,vocalEntryS:null,outroStartS:null,tonalKey:null,tonalConfidence:0,...value};
}
type Fit={score:number;style:"HardCut"|"Tight"|"Soft"|"SilenceBreath"};
const clamp=(n:number,min:number,max:number)=>Math.max(min,Math.min(max,n));
const sameArtist=(a:NativeTrack,b:NativeTrack)=>a.artist.toLocaleLowerCase().split(/\/|&|,|、/).some(name=>name.trim()&&b.artist.toLocaleLowerCase().split(/\/|&|,|、/).map(n=>n.trim()).includes(name.trim()));
const sameAlbum=(a:NativeTrack,b:NativeTrack)=>!!a.album&&a.album.toLocaleLowerCase()===b.album.toLocaleLowerCase()&&sameArtist(a,b);
export function transitionFit(a:NativeAudioFeatures,b:NativeAudioFeatures):Fit {
  const delta=a.bpm!=null&&b.bpm!=null?Math.abs(a.bpm-b.bpm):Infinity;
  const reliable=a.bpm!=null&&b.bpm!=null&&a.bpmConfidence>.25&&b.bpmConfidence>.25;
  const energy=Math.abs(a.outroEnergy-b.introEnergy),tight=a.tailSilenceS<=.45&&b.headSilenceS<=.45;
  if(tight&&energy<=.16&&(!reliable||delta<=6)) return {score:.98,style:"HardCut"};
  if(energy>=.34) return {score:clamp(.42-clamp((energy-.25)*1.4,0,1)*.25,0,1),style:"SilenceBreath"};
  let score=.28;if(reliable) score+=delta<=4?.3:delta<=8?.2:delta<=14?.08:-.18;
  score+=energy<=.1?.2:energy<=.2?.12:energy>.28?-.15:0;
  score+=tight?.18:a.tailSilenceS<=.8||b.headSilenceS<=.8?.08:0;
  return {score:clamp(score,0,1),style:reliable&&delta<=8&&energy<=.22?"Tight":"Soft"};
}
export function transitionRisk(a:NativeTrack,b:NativeTrack,af?:NativeAudioFeatures,bf?:NativeAudioFeatures,sameArtistPenalty=0) {
  let risk=0;
  if(af?.bpm!=null&&bf?.bpm!=null) {const diff=Math.abs(af.bpm-bf.bpm);risk+=diff<=8?diff/8*.35:diff<=15?.35+(diff-8)/7*.75:1.3+Math.min(2.2,(diff-15)/18);}else risk+=.28;
  if(af&&bf) {const diff=Math.abs(af.outroEnergy-bf.introEnergy);risk+=diff<=.18?diff/.18*.35:diff<=.25?.35+(diff-.18)/.07*.7:1.1+Math.min(1.8,diff*3.2);risk-=transitionFit(af,bf).score*.85;}else risk+=.28-.25*.85;
  return Math.max(-1.2,risk+(sameArtistPenalty&&sameArtist(a,b)?sameArtistPenalty:0));
}
function tonalDistance(a:NativeAudioFeatures,b:NativeAudioFeatures) {if(a.tonalKey==null||b.tonalKey==null) return null;const distance=Math.abs(a.tonalKey-b.tonalKey)%12;return Math.min(distance,12-distance);}
function entryCue(f:NativeAudioFeatures|undefined,policy:string) {
  if(!f) return 0;
  const silence=Math.round(f.headSilenceS*1000)-80,safe=silence>=220?Math.min(1800,silence):0;
  const first=f.firstBeatS==null?null:Math.round(f.firstBeatS*1000),vocal=f.vocalEntryS==null?null:Math.round(f.vocalEntryS*1000),drum=f.drumEntryS==null?null:Math.round(f.drumEntryS*1000);
  if(vocal!=null&&safe>0&&vocal<safe+450) return 0;
  if(policy!=="live-tail-cut"&&first!=null&&safe>0&&first>=safe&&first<=2400&&first-safe<=850&&f.introVocalDensity<.32&&(vocal==null||vocal>=first+450)) return clamp(first-45,safe,1800);
  if(policy!=="live-tail-cut"&&policy!=="breath-cut"&&f.introVocalDensity<.24&&!safe) {
    if(first!=null&&first>=320&&first<=2400&&(vocal==null||first-90<=vocal-450)) return Math.max(0,first-90);
    if(drum!=null&&drum>=320&&drum<=2400&&(vocal==null||drum-60<=vocal-450)&&f.introLowEnergy<=.16) return Math.max(0,drum-60);
  }
  return safe;
}
export type BeatAlignment={firstA:number;beatA:number;firstB:number;beatB:number;nextTempoRatio:number};
function beatAlignment(a:NativeAudioFeatures,b:NativeAudioFeatures):BeatAlignment|undefined {
  if(!a.bpm||!b.bpm||a.bpm<45||a.bpm>220||b.bpm<45||b.bpm>220||a.bpmConfidence<.45||b.bpmConfidence<.45||a.firstBeatS==null||b.firstBeatS==null||a.firstBeatS<0||b.firstBeatS<0||a.firstBeatS>=a.durationS||b.firstBeatS>=b.durationS) return;
  const ratio=a.bpm/b.bpm;if(ratio<.965||ratio>1.035) return;
  return {firstA:a.firstBeatS*1000,beatA:60000/a.bpm,firstB:b.firstBeatS*1000,beatB:60000/b.bpm,nextTempoRatio:ratio};
}
export function alignedStart(alignment:BeatAlignment,notBeforeMs:number,nextEntryMs:number) {
  const beat=(nextEntryMs-alignment.firstB)/alignment.beatB,phase=beat-Math.floor(beat);
  return Math.round(alignment.firstA+(Math.ceil((notBeforeMs-alignment.firstA)/alignment.beatA-phase)+phase)*alignment.beatA);
}
export type MixPlan={policy:string;reason:string;mixMs:number;crossfadeMs:number;crossfade:boolean;entryMs:number;fadeOutMs:number;fadeInMs:number;tailThreshold:number;tailTicks:number;tempoSpeed:number;tempoLeadMs:number;alignment?:BeatAlignment};
function threshold(f:NativeAudioFeatures,strict:boolean) {return clamp(f.outroEnergy*(strict?.52:.68),strict?.024:.035,strict?.065:.095);}
export function planTransition(aTrack:NativeTrack,bTrack:NativeTrack,rawA:NativeAudioFeatures|undefined,rawB:NativeAudioFeatures|undefined,actualDurationMs:number):MixPlan|null {
  if(actualDurationMs<35000) return null;
  const agrees=(f:NativeAudioFeatures|undefined,t:NativeTrack,duration:number)=>f&&duration>0&&Number.isFinite(f.durationS)&&Math.abs(duration-f.durationS*1000)<=2500&&(!t.neteaseId||t.neteaseId===f.trackId)?f:undefined;
  const a=agrees(rawA,aTrack,actualDurationMs),b=agrees(rawB,bTrack,bTrack.durationMs);
  const fallback=(reason:string):MixPlan|null=>{
    if(sameAlbum(aTrack,bTrack)||a&&a.outroVocalDensity>=.44&&a.outroStartS==null&&a.tailSilenceS<.85||(a?.outroVocalDensity||0)>=.36&&(b?.introVocalDensity||0)>=.32) return null;
    return {policy:"live-tail-cut",reason,mixMs:1450,crossfadeMs:1450,crossfade:false,entryMs:entryCue(b,"live-tail-cut"),fadeOutMs:170,fadeInMs:430,tailThreshold:a?threshold(a,true):.055,tailTicks:2,tempoSpeed:1,tempoLeadMs:0};
  };
  if(!a||!b) return fallback("missing-features");
  if(a.durationS<35||b.durationS<35) return null;
  const fit=transitionFit(a,b),energy=Math.abs(a.outroEnergy-b.introEnergy),tight=a.tailSilenceS<=.45&&b.headSilenceS<=.45;
  if(sameAlbum(aTrack,bTrack)&&tight&&energy<=.14&&fit.score>=.94) return null;
  const bpmReady=a.bpm!=null&&b.bpm!=null&&a.bpmConfidence>=.28&&b.bpmConfidence>=.28;
  const delta=bpmReady?Math.abs(a.bpm!-b.bpm!):0;if(bpmReady&&delta>10) return fallback("bpm-delta");
  const low=Math.abs(a.outroLowEnergy-b.introLowEnergy),brightness=Math.abs(a.spectralCentroidHz-b.spectralCentroidHz),tonal=tonalDistance(a,b),vocalClash=a.outroVocalDensity*b.introVocalDensity;
  const breathable=a.tailSilenceS>=.85||b.headSilenceS>=.85,busyTail=a.tailSilenceS<.8&&a.outroEnergy>=.1,melodic=a.tailSilenceS<.55&&(a.outroEnergy>=.16||a.outroVocalDensity>=.32);
  const needsDip=a.tailSilenceS<.8&&a.outroEnergy>=.05,busyHead=b.headSilenceS<.6&&(b.introEnergy>=.1||b.introVocalDensity>=.32),clash=needsDip&&busyHead;
  const blockedVocal=a.outroVocalDensity>=.44&&a.outroStartS==null&&a.tailSilenceS<.85,earlyVocal=b.vocalEntryS!=null&&b.vocalEntryS<=1.2&&b.introVocalDensity>=.32,earlyDrum=b.drumEntryS!=null&&b.drumEntryS<=.8&&b.introLowEnergy>=.09;
  const tonalClash=tonal!=null&&tonal>=5&&a.tonalConfidence>=.025&&b.tonalConfidence>=.025;
  if(energy>.32) return fallback("energy-delta");
  if(clash&&(fit.score<.92||energy>.12)) return fallback("clash-risk");
  if(melodic&&fit.score<.88) return fallback("melodic-tail");
  if(blockedVocal) return null;
  if(vocalClash>=.16) return fallback("vocal-clash");
  if(low>.16&&!tight&&!breathable) return fallback("low-energy-delta");
  if(brightness>1900&&!breathable) return fallback("brightness-delta");
  if(tonalClash&&!tight&&!breathable) return fallback("tonal-clash");
  let policy:string;
  if(fit.style==="HardCut") {if(!tight||fit.score<.88||energy>.14||earlyVocal&&vocalClash>.22) return fallback("hard-cut-guarded");policy="seamless-tight";}
  else if(fit.style==="Tight") {if(tight&&fit.score>=.88&&energy<=.14) policy="seamless-tight";else if(fit.score>=.82&&energy<=.22&&!melodic&&!earlyVocal&&!earlyDrum&&!tonalClash) policy="smart-cut";else return fallback("tight-guarded");}
  else if(fit.style==="Soft") {if(fit.score<.8||!breathable||melodic||busyHead||earlyVocal||earlyDrum) return fallback("soft-guarded");policy="breath-cut";}
  else return fallback("silence-breath");
  const fadeOutMs=melodic?190:policy==="seamless-tight"?110:policy==="smart-cut"?150:190,fadeInMs=policy==="seamless-tight"?260:policy==="smart-cut"?360:480;
  const avg=bpmReady?clamp((a.bpm!+b.bpm!)/2,70,180):null,beats=melodic||busyTail||clash?2:policy==="seamless-tight"&&delta<=4&&energy<=.12?8:4;
  const max=melodic?1050:clash?1100:policy==="seamless-tight"?1650:policy==="smart-cut"?1350:950;
  let lead=clamp(Math.min(avg?Math.round(60000/avg*beats):policy==="seamless-tight"?3400:policy==="smart-cut"?2800:2200,max),520,max);
  if(a.bpm&&a.firstBeatS!=null&&a.bpmConfidence>=.28) {const beat=60000/a.bpm;if(beat>0&&beat<=1200) {const phrase=beat*(policy==="seamless-tight"?4:2),first=a.firstBeatS*1000,aligned=Math.round(actualDurationMs-first-Math.round((actualDurationMs-lead-first)/phrase)*phrase);if(aligned>=520&&aligned<=1800&&Math.abs(aligned-lead)<=420) lead=aligned;}}
  const tailTicks=melodic||clash||a.outroVocalDensity>=.32?3:needsDip?2:0;
  let tempoSpeed=1,tempoLeadMs=0;
  if(a.bpm&&b.bpm&&a.bpmConfidence>=.36&&b.bpmConfidence>=.36&&delta>=1.4&&delta<=6.5&&policy!=="breath-cut"&&!melodic&&!clash&&!earlyVocal&&!earlyDrum&&fit.score>=.86&&!(a.outroVocalDensity>=.24&&a.outroStartS==null)&&b.introVocalDensity<.28) {const speed=clamp(b.bpm/a.bpm,.965,1.035);if(Math.abs(speed-1)>=.006) {tempoSpeed=speed;tempoLeadMs=5200;}}
  const realtimeBpm=a.bpm!=null&&b.bpm!=null&&a.bpmConfidence>=.25&&b.bpmConfidence>=.25;
  const crossfade=!melodic&&!clash&&fit.score>=.82&&vocalClash<=.12&&(policy!=="breath-cut"||fit.score>=.88&&vocalClash<=.08)&&(realtimeBpm||breathable&&fit.score>=.88||fit.score>=.88)&&energy<=(policy==="breath-cut"?.16:.22)&&low<=(policy==="breath-cut"?.11:.16)&&!(a.tonalConfidence>=.025&&b.tonalConfidence>=.025&&tonal!=null&&tonal>4)&&!(a.outroVocalDensity>=.28&&a.outroStartS==null&&a.tailSilenceS<1.2)&&!(b.introVocalDensity>=.28&&(b.vocalEntryS??99)<=1.2);
  return {policy,reason:`${fit.style} score=${fit.score.toFixed(2)} bpmDelta=${delta.toFixed(1)}`,mixMs:clamp(lead+Math.round(fadeOutMs*.92),650,1800),crossfadeMs:clamp(avg?Math.round(60000/avg*(melodic||clash?8:policy==="seamless-tight"?16:policy==="smart-cut"?12:8)):policy==="seamless-tight"?4600:policy==="smart-cut"?3800:3000,1800,5500),crossfade,entryMs:entryCue(b,policy),fadeOutMs,fadeInMs,tailThreshold:threshold(a,melodic||clash),tailTicks,tempoSpeed,tempoLeadMs,alignment:beatAlignment(a,b)};
}
