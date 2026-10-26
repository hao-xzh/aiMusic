"use client";

import { useSyncExternalStore } from "react";
import type { AiConfigPublic } from "@/lib/tauri";
import { defaultSettings,repository,type NativeSettings } from "./model";
import { readPreference,writePreference } from "./storage";
import { pwaInvoke } from "./api";

export const personas=[{id:"toxic",label:"毒舌",description:"嘴欠但懂歌，像熟人 DJ"},{id:"friendly",label:"亲和",description:"温和会接情绪，陪你听"},{id:"cold",label:"高冷",description:"少话但准，冷面音乐总监"},{id:"kitty",label:"小猫咪",description:"黏一点，会撒娇也会挑歌"},{id:"jianghu",label:"江湖",description:"仗义老炮儿，放歌讲味道"}];
const initial={settings:defaultSettings,ai:null as AiConfigPublic|null};
class Preferences {
  private state=initial;private listeners=new Set<() => void>();private loaded=false;
  subscribe=(listener:() => void) => {this.listeners.add(listener);return () => {this.listeners.delete(listener);};};snapshot=() => this.state;serverSnapshot=() => initial;
  private publish() {this.listeners.forEach(listener => listener());}
  load=() => {if(this.loaded) return;this.loaded=true;this.state={...this.state,settings:{...defaultSettings,...readPreference<Partial<NativeSettings>>("settings",{})}};this.publish();};
  update=(patch:Partial<NativeSettings>) => {const next={...this.state.settings,...patch};if(!writePreference("settings",next)) throw new Error("设置未能保存，请检查浏览器存储权限。");this.state={...this.state,settings:next};this.publish();};
  refreshAi=async () => {const ai=await repository.aiConfig();this.state={...this.state,ai};this.publish();};
  setProvider=async (provider:string) => {await pwaInvoke("ai_set_provider",{provider});await this.refreshAi();};
  setModel=async (provider:string,model:string) => {await pwaInvoke("ai_set_model",{provider,model});await this.refreshAi();};
  setKey=async (provider:string,key:string) => {await pwaInvoke("ai_set_api_key",{provider,key});await this.refreshAi();};
}
export const preferences=new Preferences();
export function usePreferences() {return useSyncExternalStore(preferences.subscribe,preferences.snapshot,preferences.serverSnapshot);}
export type AudioCacheStats={totalBytes:number;count:number;maxBytes:number};
export async function audioCacheCommand(type:"AUDIO_STATS"|"AUDIO_CLEAR"|"AUDIO_LIMIT",mb?:number):Promise<AudioCacheStats> {
  if(!navigator.serviceWorker?.controller) throw new Error("离线缓存尚未准备好，请重新打开页面后重试。");
  return new Promise((resolve,reject) => {const channel=new MessageChannel();const timer=setTimeout(() => {channel.port1.close();reject(new Error("缓存操作超时，请重试。"));},15_000);channel.port1.onmessage=event => {clearTimeout(timer);channel.port1.close();if(event.data.error) reject(new Error(event.data.error));else resolve(event.data.result);};navigator.serviceWorker.controller!.postMessage({type,mb},[channel.port2]);});
}
