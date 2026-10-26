"use client";

import { useSyncExternalStore } from "react";
import type { AgentTaskView } from "./agent-server";
import type { AgentInput,QueueGoal } from "./agent-engine";
import { player,type ContinuousSource } from "./player";
import { preferences } from "./settings";
import { library } from "./library";
import { taste } from "./taste";
import { recommendations } from "./recommend";
import { acceptsCategory,permitsFunctional,isFunctional } from "./music-filters";
import { messageOf,readPreference,readRecord,writeRecord,writePreference } from "./storage";
import type { NativeTrack } from "./model";
import { ensurePwaSession,requestPwa } from "./api";
import { diagnostic } from "./diagnostics";
import { randomId } from "./id";

type Remote={scope:string;revision:number;epoch:number;tasks:AgentTaskView[]};
type State=Remote & {ready:boolean;busy:boolean;error:string;pendingText:string};
const initial:State={scope:"",revision:0,epoch:0,tasks:[],ready:false,busy:false,error:"",pendingText:""};
const pending=(task:AgentTaskView) => ["queued","running","waiting_player"].includes(task.status);
type PendingSubmission={requestId:string;input:AgentInput;epoch:number;scope:string};
class AssistantApiError extends Error {constructor(message:string,readonly status:number) {super(message);}}
async function call<T>(body?:unknown,signal?:AbortSignal):Promise<T> {await ensurePwaSession();const response=await requestPwa("/api/pwa/agent",{method:body?"POST":"GET",credentials:"same-origin",headers:body?{"Content-Type":"application/json"}:undefined,body:body?JSON.stringify(body):undefined,signal});const data=await response.json();if(!response.ok || data.error) throw new AssistantApiError(data.error || "AI 连接失败，请重试。",response.status);return data as T;}
function continuation(taskId:string,goal:QueueGoal,text:string):ContinuousSource|undefined {
  if(goal.continuation_mode==="disabled" || goal.closer || goal.closer_title) return undefined;
  return {sourceText:text,fetch:async excluded => (await call<{tracks:NativeTrack[]}>({action:"continue",taskId,input:excluded.slice(-300)})).tracks,accepts:track => acceptsCategory(track,text) && (permitsFunctional(text) || !isFunctional(track))};
}
class Assistant {
  private state=initial;private listeners=new Set<() => void>();private timer:ReturnType<typeof setTimeout>|undefined;private controller:AbortController|undefined;private running=false;private syncing=false;private executing=new Set<string>();private completedTasks=new Set<string>();private onPlayed:(() => void)|undefined;
  private submission:PendingSubmission|null=null;private failures=0;private restoredIntent=false;
  subscribe=(listener:() => void) => {this.listeners.add(listener);return () => {this.listeners.delete(listener);};};snapshot=() => this.state;serverSnapshot=() => initial;
  private update(patch:Partial<State>) {this.state={...this.state,...patch};this.listeners.forEach(listener => listener());}
  private receive(remote:Remote) {
    if(this.state.ready && remote.scope===this.state.scope && (remote.epoch<this.state.epoch || remote.epoch===this.state.epoch && remote.revision<this.state.revision)) return;
    let resetMessage="";
    if(this.submission && (remote.scope!==this.submission.scope || remote.epoch!==this.submission.epoch)) {resetMessage="设备连接或对话已变化，旧请求没有自动重发。请先核对已有结果，再提出后续要求。";this.submission=null;writePreference("assistantPending",null);}
    if(this.submission && remote.tasks.some(task => task.id===this.submission!.requestId)) {void taste.extract(this.submission.input.text,this.submission.requestId);this.submission=null;writePreference("assistantPending",null);}
    const first=!this.state.ready;this.update({...remote,ready:true,busy:remote.tasks.some(pending) || !!this.submission,pendingText:this.submission?.input.text || "",error:resetMessage || (this.state.busy||!this.state.ready?"":this.state.error)});void writeRecord("assistantHistory",remote).catch(() => undefined);
    if(!this.restoredIntent) {this.restoredIntent=true;const saved=readPreference<{goal:QueueGoal;taskId:string;text?:string}|null>("activeIntent",null);if(saved) {const text=saved.text || remote.tasks.find(task => task.id===saved.taskId)?.text || "",source=continuation(saved.taskId,saved.goal,text);if(source) player.restoreContinuous(source);}}
    if(first) remote.tasks.filter(task => task.status==="succeeded").forEach(task => this.completedTasks.add(task.id));
    for(const task of remote.tasks) {
      if(task.command && !this.executing.has(task.command.id) && document.visibilityState==="visible") void this.apply(task);
      if(task.status==="succeeded" && !this.completedTasks.has(task.id)) {this.completedTasks.add(task.id);if(task.cards.some(card => ["like","unlike","playlistAdd","playlistRemove"].includes(card.kind))) {void library.refresh().then(()=>library.refreshFavorites());for(const card of task.cards) if(card.ok&&(card.kind==="like"||card.kind==="unlike")) card.tracks?.forEach(track=>recommendations.favorite(track,card.kind==="like"));}}
    }
  }
  private async apply(task:AgentTaskView) {
    const command=task.command;if(!command) return;this.executing.add(command.id);
    try {
      const ack=await player.applyAgent(command,continuation(task.id,command.goal,task.text));
      if(ack.ok && command.kind==="queue" && (command.goal.operation!=="insert_next" || command.goal.jump_to_inserted)) this.onPlayed?.();
      const remote=await call<Remote>({action:"ack",commandId:command.id,ack});this.receive(remote);
    } catch(error) {this.update({error:messageOf(error)});} finally {this.executing.delete(command.id);}
  }
  start=(onPlayed:() => void) => {
    this.onPlayed=onPlayed;if(this.running) return () => undefined;this.running=true;this.submission=readPreference<PendingSubmission|null>("assistantPending",null);
    void readRecord<Remote>("assistantHistory").then(cached => {if(cached && !this.state.ready) this.update({...cached,busy:cached.tasks.some(pending)});}).catch(() => undefined);
    const wake=() => {if(document.visibilityState==="visible") {clearTimeout(this.timer);void this.sync();}};document.addEventListener("visibilitychange",wake);window.addEventListener("online",wake);void this.sync();
    return () => {this.running=false;this.onPlayed=undefined;clearTimeout(this.timer);this.controller?.abort();document.removeEventListener("visibilitychange",wake);window.removeEventListener("online",wake);};
  };
  refresh=() => this.sync();
  private async sync() {
    if(!this.running || this.syncing) return;this.syncing=true;this.controller=new AbortController();
    try {const remote=await call<Remote>(undefined,this.controller.signal);if(this.running) {this.receive(remote);if(this.submission && !remote.tasks.some(pending)) this.receive(await call<Remote>({action:"submit",...this.submission},this.controller.signal));this.failures=0;}}
    catch(error) {if(!this.controller.signal.aborted) {this.failures++;this.update({busy:this.state.tasks.some(pending) || !!this.submission,error:this.submission?"连接中断，正在核对这次请求的结果。请勿重复发送。":messageOf(error)});}}
    finally {this.syncing=false;if(this.running && this.state.busy && document.visibilityState==="visible") this.timer=setTimeout(() => {void this.sync();},this.failures?Math.min(60_000,3000*2**Math.min(this.failures,4)):1200);}
  }
  submit=async (text:string) => {
    if(this.state.busy || this.submission || !text.trim()) return false;this.update({busy:true,error:""});
    try {
      if(!this.state.scope) await this.sync();if(!this.state.scope) throw new AssistantApiError("设备连接尚未准备好，请稍后再试。",409);
      const {settings}=preferences.snapshot(),playback=player.snapshot(),id=randomId(),history=this.state.tasks.flatMap(task => [{role:"user" as const,text:task.text},...(task.reply?[{role:"assistant" as const,text:task.reply}]:[])]);
      const compact=(track:NativeTrack):NativeTrack=>({id:track.id,neteaseId:track.neteaseId,title:track.title,artist:track.artist,album:track.album,durationMs:track.durationMs,artworkUrl:track.artworkUrl});
      const input:AgentInput={text,history:history.slice(-28).map(message=>({...message,text:message.text.slice(0,5000)})),current:playback.current,queue:playback.queue.slice(0,200),playback:{mode:playback.mode,playing:playback.playing&&!playback.buffering,position:player.position()},selectionRevision:player.selectionRevision(),userFacts:settings.userFacts,persona:settings.personaId,taste:taste.context().slice(0,8000),library:taste.snapshot().tracks.slice(0,600).map(compact),activeIntent:readPreference<{goal:QueueGoal}|null>("activeIntent",null)?.goal,accountId:library.snapshot().account?.userId || null};
      this.submission={requestId:id,input,epoch:this.state.epoch,scope:this.state.scope};if(!writePreference("assistantPending",this.submission)) {this.submission=null;throw new Error("请求未能保存，请检查浏览器存储空间后重试。");}
      this.update({pendingText:text});diagnostic("assistant","submit",{bytes:JSON.stringify(input).length,libraryCount:input.library.length,queueCount:input.queue.length});
      const remote=await call<Remote>({action:"submit",...this.submission});this.receive(remote);clearTimeout(this.timer);void this.sync();return true;
    } catch(error) {diagnostic("assistant","submit_failed",{status:error instanceof AssistantApiError?error.status:0,message:messageOf(error)});if(error instanceof AssistantApiError && error.status>=400 && error.status<500 && ![408,429].includes(error.status)) {this.submission=null;writePreference("assistantPending",null);}this.update({busy:!!this.submission,pendingText:this.submission?.input.text || "",error:this.submission?"连接中断，正在核对这次请求的结果。请勿重复发送。":messageOf(error)});if(this.submission) void this.sync();return !!this.submission;}
  };
  cancel=async (taskId:string) => {this.receive(await call<Remote>({action:"cancel",taskId}));};
  clear=async () => {const remote=await call<Remote>({action:"clear"});this.submission=null;writePreference("assistantPending",null);this.receive(remote);this.completedTasks.clear();};
}
export const assistant=new Assistant();
export function useAssistant() {return useSyncExternalStore(assistant.subscribe,assistant.snapshot,assistant.serverSnapshot);}
