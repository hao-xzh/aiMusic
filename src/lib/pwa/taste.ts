"use client";

import { useSyncExternalStore } from "react";
import type { TasteProfile } from "@/lib/taste-profile";
import { parseJsonObjectLike } from "@/lib/ai-json";
import { pwaInvoke } from "./api";
import { library } from "./library";
import type { NativePlaylist, NativeTrack } from "./model";
import type { ListeningEvent } from "./player";
import { preferences } from "./settings";
import { messageOf,readRecord,writeRecord } from "./storage";

export const artistKeys=(artist:string) => artist.split(/\/|&|,|、| feat\.? | featuring | ft\.? /i).map(value => value.toLocaleLowerCase().replace(/[\s'"`·・\-－—_,，。.、!?！？]+/g,"")).filter(Boolean);
export const firstArtist=(track:NativeTrack) => artistKeys(track.artist)[0] || "";
export type ExplicitTaste={key:string;dimension:"artist"|"genre"|"language"|"vocal"|"category";value:string;liked:boolean;evidence:string;taskId:string;updatedAt:number};
export type SemanticTrack={genres:string[];moods:string[];scenes:string[];language:string;vocal:string;confidence:number;sourceLlm:boolean};
export type BehaviorSnapshot={confidence:number;weightedEvidence:number;artist:Record<string,number>;genre:Record<string,number>;mood:Record<string,number>;scene:Record<string,number>;language:Record<string,number>;vocal:Record<string,number>;brief:string};
type TasteState={ready:boolean;profile:TasteProfile|null;explicit:ExplicitTaste[];events:ListeningEvent[];tracks:NativeTrack[];running:boolean;progress:string;error:string;revision:number};
const initial:TasteState={ready:false,profile:null,explicit:[],events:[],tracks:[],running:false,progress:"",error:"",revision:0};
const emptyBehavior:BehaviorSnapshot={confidence:0,weightedEvidence:0,artist:{},genre:{},mood:{},scene:{},language:{},vocal:{},brief:""};
const day=86_400_000;
export function listeningWeight(event:ListeningEvent) {
  const ratio=event.durationMs>0 ? Math.min(1,event.listenedMs/event.durationMs) : null;
  switch(event.type) {
    case "PlayStarted":return 0;
    case "Completed":return ratio!=null && ratio>=.8 ? 1.15*ratio : 0;
    case "Skipped":return ratio==null ? -.55 : ratio>=.8 ? .28 : ratio>=.5 || event.listenedMs<1500 ? 0 : -1.15*(1-ratio);
    case "ManualCut":return ratio!=null && ratio>=.8 ? .28 : 0;
  }
}
export async function mapConcurrent<T,R>(values:T[],limit:number,fn:(value:T,index:number) => Promise<R>):Promise<R[]> {
  let next=0;const results:R[]=new Array(values.length);
  await Promise.all(Array.from({length:Math.min(values.length,limit)},async () => {while(next<values.length) {const index=next++;results[index]=await fn(values[index],index);}}));return results;
}
export function aiChat(system:string,user:string,temperature=.5,maxTokens=1400,signal?:AbortSignal) {
  return pwaInvoke<string>("ai_chat",{system,user,temperature,maxTokens},signal);
}
export function aiReady() {const config=preferences.snapshot().ai;return !!config?.providers.find(provider => provider.id===config.activeProvider)?.hasKey;}
class Taste {
  private state=initial;private listeners=new Set<() => void>();private owner="";private generation=0;private serial=Promise.resolve();private job:AbortController|undefined;
  readonly semantics=new Map<string,SemanticTrack>();
  subscribe=(fn:() => void) => {this.listeners.add(fn);return () => {this.listeners.delete(fn);};};snapshot=() => this.state;serverSnapshot=() => initial;
  private update(patch:Partial<TasteState>) {this.state={...this.state,...patch,revision:this.state.revision+1};this.listeners.forEach(listener => listener());}
  private persist() {const key=`taste:${this.owner}`,{profile,explicit,events}=this.state;this.serial=this.serial.catch(() => undefined).then(() => writeRecord(key,{profile,explicit,events}));void this.serial.catch(() => this.update({error:"音乐口味未能保存，请检查存储空间。"}));}
  load=async (uid?:number) => {const owner=String(uid || "guest");if(owner===this.owner && this.state.ready) return;this.cancel();const generation=++this.generation;this.owner=owner;this.semantics.clear();this.update({...initial});const cached=await readRecord<Pick<TasteState,"profile"|"explicit"|"events">>(`taste:${owner}`).catch(() => undefined);if(generation!==this.generation) return;this.update({...cached,ready:true});};
  record=(event:ListeningEvent) => {if(!this.state.ready) return;const events=[...this.state.events,event].filter(item => item.tsMs>Date.now()-180*day).slice(-12_000);this.update({events});this.persist();};
  libraryTracks=async () => {
    const generation=this.generation,playlists=library.snapshot().playlists;
    const groups=await mapConcurrent(playlists,4,async playlist => {const cached=await library.cachedTracks(playlist.id);return cached.length ? cached : library.loadTracks(playlist.id).catch(() => []);});
    if(generation!==this.generation) return [];
    const tracks=[...new Map(groups.flat().map(track => [track.id,track])).values()];this.update({tracks});return tracks;
  };
  behavior=():BehaviorSnapshot => {
    const snapshot:BehaviorSnapshot={...emptyBehavior,artist:{},genre:{},mood:{},scene:{},language:{},vocal:{}};const now=Date.now();
    const add=(bucket:Record<string,number>,keys:string[],weight:number) => [...new Set(keys)].filter(key => key && key!=="unknown").slice(0,8).forEach(key => {bucket[key]=(bucket[key] || 0)+weight;});
    for(const event of this.state.events) {
      if(event.tsMs<now-180*day) continue;const base=listeningWeight(event);if(!base) continue;
      const age=(now-event.tsMs)/3_600_000,long=base*Math.exp(-age/(24*21)),short=event.tsMs>=now-21*day ? base*Math.exp(-age/120) : 0;
      snapshot.weightedEvidence+=Math.abs(long);add(snapshot.artist,artistKeys(event.track.artist).slice(0,4),long);
      const semantic=this.semantics.get(event.track.id);if(!semantic?.sourceLlm || semantic.confidence<.55) continue;
      add(snapshot.genre,semantic.genres,long);add(snapshot.language,[semantic.language],long);add(snapshot.vocal,[semantic.vocal],long*.65);add(snapshot.mood,semantic.moods,short*.85);add(snapshot.scene,semantic.scenes,short*.75);
    }
    if(snapshot.weightedEvidence<2.5) return emptyBehavior;
    snapshot.confidence=Math.min(1,Math.sqrt(snapshot.weightedEvidence/42));
    const merged:Record<string,number>={};
    for(const bucket of [snapshot.artist,snapshot.genre,snapshot.language,snapshot.vocal,snapshot.mood,snapshot.scene]) {
      const max=Math.max(...Object.values(bucket).map(Math.abs),0);for(const [key,value] of Object.entries(bucket)) {if(Math.abs(value)<.12) {delete bucket[key];continue;}bucket[key]=value/max;if(Math.abs(bucket[key])>Math.abs(merged[key] || 0)) merged[key]=bucket[key];}
    }
    const entries=Object.entries(merged).sort((a,b) => Math.abs(b[1])-Math.abs(a[1]));const likes=entries.filter(([,value]) => value>.32).slice(0,4).map(([key]) => key),avoids=entries.filter(([,value]) => value<-.32).slice(0,4).map(([key]) => key);
    snapshot.brief=[likes.length ? `最近更常听完: ${likes.join("、")}` : "",avoids.length ? `最近常跳过: ${avoids.join("、")}` : ""].filter(Boolean).join("；");return snapshot;
  };
  behaviorScore=(track:NativeTrack,snapshot=this.behavior()) => {
    const match=(bucket:Record<string,number>,keys:string[]) => keys.reduce((best,key) => Math.abs(bucket[key] || 0)>Math.abs(best) ? bucket[key] : best,0);
    let score=match(snapshot.artist,artistKeys(track.artist))*.32;const semantic=this.semantics.get(track.id);
    if(semantic?.sourceLlm && semantic.confidence>=.55) score+=match(snapshot.genre,semantic.genres)*.28+match(snapshot.mood,semantic.moods)*.2+match(snapshot.scene,semantic.scenes)*.14+match(snapshot.language,[semantic.language])*.12+match(snapshot.vocal,[semantic.vocal])*.08;
    return Math.max(-1,Math.min(1,score*snapshot.confidence));
  };
  explicitScore=(track:NativeTrack) => {
    const keys=artistKeys(track.artist),semantic=this.semantics.get(track.id);let positive=0,negative=0;
    for(const fact of this.state.explicit) {
      const match=fact.dimension==="artist" ? keys.includes(artistKeys(fact.value)[0]) : semantic?.sourceLlm && semantic.confidence>=.55 && (fact.dimension==="genre" ? semantic.genres.includes(fact.value) : fact.dimension==="language" ? semantic.language===fact.value : fact.dimension==="vocal" ? semantic.vocal===fact.value : false);
      if(match) {if(fact.liked) positive=1;else negative=-1;}
    }return negative || positive;
  };
  context=() => {const {profile,explicit,tracks}=this.state,artists=this.topLibraryArtists(tracks).slice(0,12);return [profile?.summary.slice(0,650),profile?.genres.map(item => item.tag).join("、"),artists.map(item => `${item.name}(${item.count})`).join("、"),this.behavior().brief,...explicit.slice(-12).map(fact => `${fact.liked ? "喜欢" : "不喜欢"}${fact.value}（用户原话：${fact.evidence}）`)].filter(Boolean).join("\n");};
  topLibraryArtists=(tracks=this.state.tracks) => {const map=new Map<string,{name:string;count:number}>();for(const track of tracks) {for(const name of track.artist.split(/\/|&|,|、/).map(value => value.trim()).filter(Boolean)) {const key=artistKeys(name)[0];map.set(key,{name,count:(map.get(key)?.count || 0)+1});}}return [...map.values()].sort((a,b) => b.count-a.count);};
  extract=async (text:string,taskId:string) => {
    if(!aiReady() || text.trim().length<4) return;const generation=this.generation,controller=new AbortController(),timer=setTimeout(() => controller.abort(),8000);
    try {
      const raw=await aiChat("提取用户明确表达的长期音乐偏好。一次点歌、当前情绪、举例、否定猜测都不是长期偏好。不推断未明确表达的信息。只输出 JSON {preferences:[{dimension:artist|genre|language|vocal|category,value,liked:boolean,evidence,scope:lifetime}]}，最多6条；evidence 必须是用户原文连续片段，4至240字。没有就空数组。",text,.1,700,controller.signal);
      if(generation!==this.generation) return;const parsed=parseJsonObjectLike(raw) as {preferences?:unknown[]}|null;
      const facts=new Map(this.state.explicit.map(fact => [fact.key,fact]));
      for(const value of (parsed?.preferences || []).slice(0,6)) {if(!value || typeof value!=="object") continue;const entry=value as Record<string,unknown>;if(entry.scope!=="lifetime" || typeof entry.liked!=="boolean" || typeof entry.value!=="string" || typeof entry.evidence!=="string" || entry.evidence.length<4 || entry.evidence.length>240 || !text.includes(entry.evidence) || !["artist","genre","language","vocal","category"].includes(String(entry.dimension))) continue;const name=entry.value.trim().slice(0,80),dimension=entry.dimension as ExplicitTaste["dimension"],key=`${dimension}:${name.toLocaleLowerCase()}`;if(name) facts.set(key,{key,dimension,value:name,liked:entry.liked,evidence:entry.evidence,taskId,updatedAt:Date.now()});}
      this.update({explicit:[...facts.values()].sort((a,b) => a.updatedAt-b.updatedAt).slice(-80)});this.persist();
    } catch { /* An optional memory extraction never blocks the requested music action. */ }
    finally {clearTimeout(timer);}
  };
  forget=async (key:string) => {this.update({explicit:this.state.explicit.filter(fact => fact.key!==key)});this.persist();await this.serial;};
  clear=async () => {this.cancel();++this.generation;this.update({profile:null,explicit:[],events:[],error:""});this.persist();await this.serial;};
  cancel=() => {this.job?.abort();this.job=undefined;this.update({running:false,progress:""});};
  dismissError=() => this.update({error:""});
  distill=async (playlists:NativePlaylist[]) => {
    if(this.state.running || !playlists.length) return;const controller=new AbortController();this.job=controller;const generation=this.generation;this.update({running:true,error:""});
    const active=() => {if(controller.signal.aborted || generation!==this.generation) throw new DOMException("已取消","AbortError");};
    try {
      let loaded=0;const groups=await mapConcurrent(playlists,4,async playlist => {active();const cached=await library.cachedTracks(playlist.id);const tracks=cached.length ? cached : await library.loadTracks(playlist.id);active();this.update({progress:`正在读取歌单 ${++loaded}/${playlists.length}`});return tracks;});active();
      const full=[...new Map(groups.flat().map(track => [track.id,track])).values()];if(!full.length) throw new Error("所选歌单没有可用曲目。");this.update({tracks:full,progress:"正在提炼音乐口味"});
      const behavior=this.behavior(),sample=weightedSample(groups,behavior),artists=this.topLibraryArtists(full),total=groups.reduce((count,group) => count+group.length,0);
      const user=`用户挑了 ${playlists.length} 张歌单：${playlists.map((list,index) => `「${list.name}」（${groups[index].length} 首）`).join("、")}。\n全库去重 ${full.length} 首；艺人完整统计：${artists.slice(0,50).map(item => `${item.name}:${item.count}`).join("、")}。\n实际聆听反馈：${behavior.brief || "尚无足够记录"}。\nSample（${sample.length} 首代表曲目，仅用于补充全库事实）：\n${sample.map((track,index) => `${index+1}. ${track.neteaseId || track.id}. ${track.title} — ${track.artist} · ${track.album}`).join("\n")}\n基于全库统计与样本画像，不能光按歌名猜测；可以承认看不出来。像独立唱片店老板聊天，不写官话。给至少4个具体子流派 genres:[{tag,weight:0到1,examples:2到3个样本曲目}]、eras:[{label:具体十年段,weight}]、moods:3到6个短语、topArtists:[{name,affinity}]6到10个、culturalContext:2到4个、taglines:1到3句、summary:30汉字以内。只输出一行 JSON。`;
      let parsed:Partial<TasteProfile>|null=null;if(aiReady()) {try {parsed=parseJsonObjectLike(await aiChat("你是 Pipo 的口味蒸馏器。像独立唱片店老板，依据事实，只输出 JSON。",user,.5,1400,controller.signal)) as Partial<TasteProfile>|null;} catch(error) {active();this.update({error:`AI 画像生成失败，已保留本地收藏统计：${messageOf(error)}`});}}
      active();const top=artists.slice(0,10).map(artist => ({name:artist.name,affinity:Math.min(1,artist.count/Math.max(3,full.length*.04))}));
      const profile:TasteProfile={version:1,derivedAt:Math.floor(Date.now()/1000),sourcePlaylistCount:playlists.length,sampledTrackCount:sample.length,totalTrackCount:total,sourcePlaylistIds:playlists.map(list => list.id),sourceHash:sourceHash(groups.flat()),genres:validGenres(parsed?.genres),eras:Array.isArray(parsed?.eras) ? parsed!.eras.filter(item => typeof item?.label==="string" && Number.isFinite(item.weight)).map(item => ({...item,weight:Math.max(0,Math.min(1,item.weight))})) : [],moods:strings(parsed?.moods),topArtists:Array.isArray(parsed?.topArtists) ? parsed!.topArtists.filter(item => typeof item?.name==="string" && Number.isFinite(item.affinity)).map(item => ({...item,affinity:Math.max(0,Math.min(1,item.affinity))})) : top,culturalContext:strings(parsed?.culturalContext),taglines:strings(parsed?.taglines),summary:typeof parsed?.summary==="string" ? parsed.summary : "私人曲库混合口味"};
      this.update({profile});this.persist();await this.serial;
    } catch(error) {if(!controller.signal.aborted && generation===this.generation) this.update({error:messageOf(error)});}
    finally {if(this.job===controller) {this.job=undefined;this.update({running:false,progress:""});}}
  };
}
function strings(value:unknown):string[] {return Array.isArray(value) ? value.filter(item => typeof item==="string").slice(0,30) : [];}
function validGenres(value:unknown):TasteProfile["genres"] {return Array.isArray(value) ? value.filter(item => item && typeof item.tag==="string" && Number.isFinite(item.weight)).map(item => ({tag:item.tag,weight:Math.max(0,Math.min(1,item.weight)),examples:strings(item.examples)})) : [];}
function sourceHash(tracks:NativeTrack[]) {let hash=5381;for(const char of tracks.flatMap(track => track.neteaseId || []).sort((a,b) => a-b).slice(0,1000).join(",")) hash=((hash<<5)+hash+char.charCodeAt(0))|0;return (hash>>>0).toString(36);}
function weightedSample(groups:NativeTrack[][],behavior:BehaviorSnapshot,target=200) {
  const full=[...new Map(groups.flat().map(track => [track.id,track])).values()],total=groups.reduce((count,group) => count+group.length,0);if(total<=target) return full;
  const quotas=groups.map(group => Math.max(1,Math.round(group.length/total*target)));while(quotas.reduce((a,b) => a+b,0)>target && quotas.some(value => value>1)) {const index=quotas.indexOf(Math.max(...quotas));quotas[index]--;}
  const draw=(tracks:NativeTrack[],count:number) => tracks.map(track => {const signal=behavior.artist[firstArtist(track)] || 0;return {track,key:Math.random()**(1/(signal>.32 ? 2.5 : signal<-.32 ? .3 : 1))};}).sort((a,b) => b.key-a.key).slice(0,count).map(item => item.track);
  const seen=new Set<string>(),picked=groups.flatMap((group,index) => draw(group.filter(track => !seen.has(track.id) && !!seen.add(track.id)),quotas[index])),counts=new Map<string,number>(),cap=Math.max(8,Math.floor(target*.05)),result:NativeTrack[]=[];
  const add=(track:NativeTrack) => {const key=firstArtist(track),count=counts.get(key) || 0;if(!key || count<cap) {counts.set(key,count+1);result.push(track);}};picked.forEach(add);const ids=new Set(result.map(track => track.id));draw(full.filter(track => !ids.has(track.id)),target-result.length).forEach(add);return result.slice(0,target);
}
export const taste=new Taste();
export function useTaste() {return useSyncExternalStore(taste.subscribe,taste.snapshot,taste.serverSnapshot);}
