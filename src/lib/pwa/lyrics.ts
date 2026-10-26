"use client";

import { useEffect,useState } from "react";
import { parseLrc } from "@/lib/lrc";
import { lrcLinesToYrcLines,parseYrc,type YrcLine } from "@/lib/yrc";
import type { LyricData } from "@/lib/tauri";
import { repository,type NativeTrack } from "./model";
import { messageOf,readRecord,writeRecord } from "./storage";
import { pwaInvoke } from "./api";
import { library } from "./library";

export type Lyrics = { lines:YrcLine[]; loading:boolean; instrumental:boolean; error:string; hasTranslation:boolean };
const initial:Lyrics={lines:[],loading:false,instrumental:false,error:"",hasTranslation:false};
const cache=new Map<string,LyricData>(); const jobs=new Map<string,Promise<LyricData>>();
function language(text:string) {
  const counts=[(text.match(/[a-z\d]/gi)||[]).length,(text.match(/[\u4e00-\u9fff]/g)||[]).length,(text.match(/[\u3040-\u30ff]/g)||[]).length,(text.match(/[\uac00-\ud7af]/g)||[]).length];
  const max=Math.max(...counts); return max>=2 ? counts.indexOf(max) : -1;
}
// Translation host windows and embedded bilingual LRC detection follow JsonRustPipoBridge.kt.
export function lyricLines(raw:LyricData):YrcLine[] {
  const timed=parseYrc(raw.yrc); let primary=timed.length ? timed : lrcLinesToYrcLines(parseLrc(raw.lyric)); let translations=parseLrc(raw.translation);
  if (!timed.length && primary.length>=8 && translations.length>=4) {
    const buckets=[0,0,0,0]; translations.forEach(line => { const bucket=language(line.text); if(bucket>=0) buckets[bucket]++; });
    const dominant=buckets.indexOf(Math.max(...buckets)),count=buckets[dominant];
    const embedded=primary.filter(line => language(line.text)===dominant),other=primary.filter(line => language(line.text)!==dominant && language(line.text)>=0);
    if (count>=4 && embedded.length>=4 && other.length>=4 && embedded.length/primary.length>=.25 && embedded.length/primary.length<=.75 && Math.abs(embedded.length-count)<=Math.max(4,Math.floor(count*.35))) { primary=primary.filter(line => language(line.text)!==dominant); translations=embedded; }
  }
  const attached:YrcLine[][]=primary.map(() => []); let min=0;
  for (const translation of translations) {
    if (!translation.text.trim()) continue; let best=-1,score=-Infinity;
    primary.forEach((line,i) => {
      if(i<min) return; const end=primary[i+1]?.time ?? line.time+Math.max(1.2,line.durSec); const distance=Math.abs(translation.time-line.time); const within=translation.time>=line.time-.7 && translation.time<end+.35;
      if(!within && distance>1.65) return; const rank=(within?10:0)-distance; if(rank>score) { score=rank; best=i; }
    });
    if(best>=0 && !attached[best].some(line => line.text===translation.text.trim())) { attached[best].push({time:translation.time,durSec:0,text:translation.text.trim(),chars:[],role:"translation"}); min=Math.min(primary.length-1,best+1); }
  }
  return primary.map((line,i) => ({...line,companionLines:[...(line.companionLines || []),...attached[i].slice(0,1)]}));
}
async function fetchLyrics(track:NativeTrack) {
  const cached=cache.get(track.id); if(cached) return cached; const pending=jobs.get(track.id); if(pending) return pending;
  const job=(async () => {
    let raw=await readRecord<LyricData>(`lyrics:${track.id}`).catch(() => undefined);
    if(!raw || navigator.onLine) {
      try {
        if (!track.neteaseId) throw new Error("当前歌曲没有在线歌词。");
        raw=await repository.lyrics(track.neteaseId);
        if (!raw.lyric && !raw.yrc && library.snapshot().account) raw=await pwaInvoke<LyricData>("netease_cloud_lyric",{songId:track.neteaseId,userId:library.snapshot().account!.userId}).catch(() => raw!);
        await writeRecord(`lyrics:${track.id}`,raw).catch(() => undefined);
      } catch(error) { if(!raw) throw error; }
    }
    cache.set(track.id,raw!); if(cache.size>80) cache.delete(cache.keys().next().value!); return raw!;
  })(); jobs.set(track.id,job); void job.then(() => jobs.delete(track.id),() => jobs.delete(track.id)); return job;
}
export function useLyrics(track:NativeTrack | null) {
  const [state,setState]=useState<Lyrics>(initial);
  useEffect(() => { let active=true; if(!track) { setState(initial); return; } setState({...initial,loading:true});
    void fetchLyrics(track).then(raw => { if(!active) return; const lines=lyricLines(raw); setState({lines,loading:false,instrumental:raw.instrumental,error:"",hasTranslation:lines.some(line => line.companionLines?.some(companion => companion.role==="translation"))}); }).catch(error => { if(active) setState({...initial,error:messageOf(error)}); });
    return () => { active=false; };
  },[track?.id]);
  return state;
}
