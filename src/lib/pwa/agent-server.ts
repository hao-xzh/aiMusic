import { readFile,writeFile,rename } from "node:fs/promises";
import path from "node:path";
import { agentSessionScope,bridgeCall,PwaApiError,sessionDataDirectory } from "./bridge-server";
import { runAgent,resolveContinuation,type QueueGoal,type AgentInput,type AgentCard,type PlayerCommand,type PlayerAcknowledgement } from "./agent-engine";
import type { NativeTrack } from "./model";
import type { UserProfile } from "@/lib/tauri";

export type AgentTaskView={id:string;text:string;createdAt:number;updatedAt:number;status:"queued"|"running"|"waiting_player"|"succeeded"|"failed"|"cancelled";stage:string;reply:string;cards:AgentCard[];command:PlayerCommand|null};
type Task=AgentTaskView & {input:AgentInput;journal?:Record<string,unknown>;activeGoal?:QueueGoal};
type Session={tasks:Task[];epoch:number;revision:number;running?:string;execution?:Promise<void>;controller?:AbortController;waiting?:{command:PlayerCommand;resolve:(value:PlayerAcknowledgement) => void;reject:(error:Error) => void};writing:Promise<void>;filename:string;lastUsed:number};
const globalState=globalThis as typeof globalThis & {__pipoAgentSessions?:Map<string,Promise<Session>>};
const sessions=globalState.__pipoAgentSessions ||= new Map<string,Promise<Session>>();
async function session(id:string) {
  if(sessions.size>64) for(const [key,pending] of sessions) {if(key!==id) void pending.then(async state=>{if(state.running||state.tasks.some(task=>task.status==="queued")) return;await state.writing.catch(()=>undefined);if(Date.now()-state.lastUsed>1800000&&sessions.get(key)===pending) {sessions.delete(key);for(const offsetKey of catalogOffsets.keys()) if(offsetKey.startsWith(`${key}:`)) catalogOffsets.delete(offsetKey);}}).catch(()=>undefined);}
  let pending=sessions.get(id);if(!pending) {pending=(async () => {
    const filename=path.join(sessionDataDirectory(id),"agent-tasks.json");let data:{tasks:Task[];epoch:number;revision:number}={tasks:[],epoch:0,revision:0};
    try {data=JSON.parse(await readFile(filename,"utf8"));} catch(error) {if((error as NodeJS.ErrnoException).code!=="ENOENT") throw new PwaApiError("对话任务记录读取失败。",500);}
    const state:Session={tasks:Array.isArray(data.tasks)?data.tasks:[],epoch:data.epoch || 0,revision:data.revision || 0,writing:Promise.resolve(),filename,lastUsed:Date.now()};
    let interrupted=false;for(const task of state.tasks) {if(["running","waiting_player","queued"].includes(task.status)) {task.status="failed";task.command=null;task.stage="服务连接已重新启动";task.reply=task.journal?.stage==="external_write_started" || task.journal?.stage==="playlist_created"?"服务重启前已经发出账号操作，结果需要先在资料库核对；没有重复发送这次操作。":"服务重启使本次任务中断。已保留对话与完成结果，请重新提出尚未完成的要求。";task.updatedAt=Date.now();interrupted=true;}}
    if(interrupted) await save(state);return state;
  })();sessions.set(id,pending);void pending.catch(() => {if(sessions.get(id)===pending) sessions.delete(id);});}
  const state=await pending;state.lastUsed=Date.now();return state;
}
function save(state:Session) {
  state.revision++;const contents=JSON.stringify({epoch:state.epoch,revision:state.revision,tasks:state.tasks.slice(-80)});state.writing=state.writing.catch(() => undefined).then(async () => {const temporary=`${state.filename}.tmp`;await writeFile(temporary,contents,{mode:0o600});await rename(temporary,state.filename);});return state.writing;
}
function publicTask(task:Task):AgentTaskView {const {id,text,createdAt,updatedAt,status,stage,reply,cards,command}=task;return {id,text,createdAt,updatedAt,status,stage,reply,cards,command};}
export async function agentSnapshot(id:string) {const state=await session(id);return {scope:agentSessionScope(id),revision:state.revision,epoch:state.epoch,tasks:state.tasks.slice(-60).map(publicTask)};}
function validateInput(raw:unknown):AgentInput {
  if(!raw || typeof raw!=="object") throw new PwaApiError("点歌请求格式无效。");const value=raw as AgentInput;
  if(typeof value.text!=="string" || !value.text.trim() || value.text.length>4000 || !Array.isArray(value.queue) || !Array.isArray(value.library) || !Array.isArray(value.history) || !Number.isFinite(value.selectionRevision)) throw new PwaApiError("点歌请求缺少必要内容。");
  return {...value,text:value.text.trim(),history:value.history.slice(-28),queue:value.queue.slice(0,200),library:value.library.slice(0,600),userFacts:String(value.userFacts || "").slice(0,400),taste:String(value.taste || "").slice(0,8000),persona:String(value.persona || "toxic"),accountId:typeof value.accountId==="number"?value.accountId:null};
}
export async function submitAgent(id:string,raw:unknown,requestId:string,epoch:number,scope:string) {
  if(scope!==agentSessionScope(id)) throw new PwaApiError("设备连接已变化，请先核对旧请求的结果，再重新提出要求。",409);
  const state=await session(id);if(!/^[a-f0-9-]{36}$/.test(requestId)) throw new PwaApiError("请求标识无效。");const existing=state.tasks.find(task => task.id===requestId);if(existing) return agentSnapshot(id);
  if(epoch!==state.epoch) throw new PwaApiError("这段对话已经更新，请重新连接后再发送。",409);
  if(state.tasks.some(task => ["queued","running","waiting_player"].includes(task.status))) throw new PwaApiError("正在处理上一条请求，请稍等。",409);
  const input=validateInput(raw),now=Date.now(),task:Task={id:requestId,text:input.text,input,createdAt:now,updatedAt:now,status:"queued",stage:"等待处理",reply:"",cards:[],command:null};state.tasks.push(task);state.tasks=state.tasks.slice(-80);await save(state);
  // A cleared task may still be unwinding an in-flight request. Serialize its successor without dropping it.
  state.execution=(state.execution||Promise.resolve()).catch(()=>undefined).then(async()=>{if(state.epoch===epoch&&state.tasks.includes(task)&&task.status==="queued") await run(id,state,task);}).catch(() => {if(state.epoch!==epoch) return;task.status="failed";task.stage="任务记录保存失败";task.reply="本次操作的完成状态未能保存。请先在播放器和资料库核对结果，再提出后续要求。";state.revision++;});return agentSnapshot(id);
}
async function run(id:string,state:Session,task:Task) {
  if(state.running) return;state.running=task.id;const controller=new AbortController(),epoch=state.epoch;state.controller=controller;task.status="running";task.updatedAt=Date.now();
  try {
    await save(state);const outcome=await runAgent(task.id,task.input,{
      signal:controller.signal,rpc:(command,args) => bridgeCall(id,command,args),
      progress:stage => {if(state.epoch!==epoch || controller.signal.aborted) return;task.stage=stage;task.updatedAt=Date.now();state.revision++;},
      checkpoint:async journal => {if(state.epoch!==epoch || controller.signal.aborted) throw new DOMException("已取消","AbortError");task.journal=journal;task.updatedAt=Date.now();const cards=journal.cards;if(Array.isArray(cards)) task.cards=cards as AgentCard[];await save(state);},
      player:command => new Promise((resolve,reject) => {
        if(controller.signal.aborted) {reject(new Error("已取消"));return;}task.command=command;task.status="waiting_player";task.stage="等待播放器应用";task.updatedAt=Date.now();
        const abort=() => {state.waiting=undefined;reject(new Error("已取消"));};controller.signal.addEventListener("abort",abort,{once:true});
        state.waiting={command,resolve:ack => {controller.signal.removeEventListener("abort",abort);state.waiting=undefined;if(ack.ok) {task.input.current=ack.current;task.input.queue=ack.queue;task.input.selectionRevision=ack.selectionRevision;if(command.kind==="queue" && command.goal.operation!=="insert_next") task.activeGoal=command.goal;}task.command=null;task.status="running";task.updatedAt=Date.now();resolve(ack);},reject};
        void save(state).catch(error => {controller.signal.removeEventListener("abort",abort);state.waiting=undefined;reject(error);});
      }),
    });
    if(epoch!==state.epoch || controller.signal.aborted) return;task.reply=outcome.reply;task.cards=outcome.cards;task.status="succeeded";task.stage="已完成";
  } catch(error) {if(epoch!==state.epoch) return;task.status=controller.signal.aborted?"cancelled":"failed";task.stage=controller.signal.aborted?"已停止":"未完成";task.reply=controller.signal.aborted?"已停止这次请求；已经完成的操作保留。":error instanceof Error?error.message:"这次请求没有完成，请重试。";}
  finally {if(state.running===task.id) {state.running=undefined;state.controller=undefined;state.waiting=undefined;}task.command=null;task.updatedAt=Date.now();if(epoch===state.epoch) await save(state);}
}
export async function acknowledgeAgent(id:string,commandId:string,raw:unknown) {
  const state=await session(id),waiting=state.waiting;if(!waiting || waiting.command.id!==commandId) return agentSnapshot(id);
  if(!raw || typeof raw!=="object") throw new PwaApiError("播放器反馈无效。");const ack=raw as PlayerAcknowledgement;if(typeof ack.ok!=="boolean" || !Array.isArray(ack.queue) || !Number.isFinite(ack.selectionRevision)) throw new PwaApiError("播放器反馈缺少必要内容。");
  waiting.resolve(ack);await save(state);return agentSnapshot(id);
}
export async function cancelAgent(id:string,taskId:string) {const state=await session(id);if(state.running===taskId) state.controller?.abort();else {const task=state.tasks.find(task=>task.id===taskId&&task.status==="queued");if(task) {task.status="cancelled";task.stage="已停止";task.reply="已停止这次请求。";await save(state);}}return agentSnapshot(id);}
export async function clearAgent(id:string) {const state=await session(id);state.epoch++;state.controller?.abort();state.tasks=[];await save(state);return agentSnapshot(id);}
const continuationJobs=new Map<string,Promise<NativeTrack[]>>(),catalogOffsets=new Map<string,Map<string,number>>();
export async function continueAgent(id:string,taskId:string,excluded:NativeTrack[]) {
  const key=`${id}:${taskId}`,pending=continuationJobs.get(key);if(pending) return {tracks:await pending};
  const state=await session(id),task=state.tasks.find(task => task.id===taskId),goal=task?.activeGoal;if(!task || !goal || goal.continuation_mode==="disabled" || goal.closer || goal.closer_title) return {tracks:[]};
  const account=await bridgeCall<UserProfile|null>(id,"netease_account");if((account?.userId || null)!==task.input.accountId) throw new PwaApiError("账号已变化，原来的点歌续播已停止。",409);
  const offsets=catalogOffsets.get(key) || new Map<string,number>();catalogOffsets.set(key,offsets);const job=resolveContinuation(goal,task.input,excluded.slice(-300),<T>(command:string,args?:Record<string,unknown>) => bridgeCall<T>(id,command,args),offsets);continuationJobs.set(key,job);
  try {return {tracks:await job};} finally {if(continuationJobs.get(key)===job) continuationJobs.delete(key);}
}
