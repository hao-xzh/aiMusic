import { NextRequest,NextResponse } from "next/server";
import { acknowledgeAgent,agentSnapshot,cancelAgent,clearAgent,continueAgent,submitAgent } from "@/lib/pwa/agent-server";
import type { NativeTrack } from "@/lib/pwa/model";
import { attachSession,checkOrigin,PwaApiError,sessionFor } from "@/lib/pwa/bridge-server";

export const runtime="nodejs";
export const dynamic="force-dynamic";
function failed(error:unknown) {return NextResponse.json({error:error instanceof PwaApiError?error.message:"对话服务暂时不可用，请稍后重试。"},{status:error instanceof PwaApiError?error.status:500,headers:{"Cache-Control":"no-store"}});}
export async function GET(request:NextRequest) {try {const id=sessionFor(request);return NextResponse.json(await agentSnapshot(id),{headers:{"Cache-Control":"private, no-store"}});} catch(error) {return failed(error);}}
export async function POST(request:NextRequest) {
  try {
    checkOrigin(request);if(!request.headers.get("content-type")?.startsWith("application/json")) throw new PwaApiError("请求格式无效。",415);if(Number(request.headers.get("content-length") || 0)>700_000) throw new PwaApiError("请求内容过长。",413);const raw=await request.text();if(raw.length>700_000) throw new PwaApiError("请求内容过长。",413);const body=JSON.parse(raw) as {action:string;input?:unknown;requestId?:string;commandId?:string;ack?:unknown;taskId?:string;epoch?:number;scope?:string},id=sessionFor(request,true);
    const result=body.action==="submit"?await submitAgent(id,body.input,String(body.requestId || ""),Number(body.epoch),String(body.scope || "")):body.action==="ack"?await acknowledgeAgent(id,String(body.commandId || ""),body.ack):body.action==="cancel"?await cancelAgent(id,String(body.taskId || "")):body.action==="clear"?await clearAgent(id):body.action==="continue"?await continueAgent(id,String(body.taskId || ""),Array.isArray(body.input)?body.input as NativeTrack[]:[]):null;
    if(!result) throw new PwaApiError("不支持的对话操作。");const response=NextResponse.json(result);attachSession(response,request,id);return response;
  } catch(error) {return failed(error);}
}
