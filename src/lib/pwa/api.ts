export class PwaConnectionError extends Error {
  constructor() {super("无法连接 PIPO 服务，请检查网络后重试。");this.name="PwaConnectionError";}
}

export async function requestPwa(url:string,options:RequestInit):Promise<Response> {
  let response:Response;
  try {response=await fetch(url,options);}
  catch(error) {if(options.signal?.aborted) throw error;throw new PwaConnectionError();}
  if (response.status === 401) {
    const body = await response.clone().json().catch(() => null) as {authRequired?:boolean}|null;
    if (body?.authRequired && typeof window !== "undefined") {
      window.location.replace("/access");
      throw new Error("请验证此设备后继续使用。");
    }
  }
  return response;
}

let sessionReady:Promise<void>|undefined;
export function ensurePwaSession():Promise<void> {
  return sessionReady ||= requestPwa("/api/pwa/session", {credentials:"same-origin",cache:"no-store"}).then(response => {
    if(response.status>=500) throw new PwaConnectionError();
    if (!response.ok) throw new Error("无法连接 PIPO 服务，请检查网络后重试。");
  }).catch(error => {sessionReady=undefined;throw error;});
}

export async function pwaInvoke<T = unknown>(command: string, args?: unknown, signal?: AbortSignal): Promise<T> {
  await ensurePwaSession();
  const response = await requestPwa("/api/pwa/rpc", { method: "POST", credentials: "same-origin", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ command, args }), signal });
  let body:{result?:T;error?:string};
  try {body=await response.json();}
  catch(error) {
    if(signal?.aborted) throw error;
    if([502,503,504].includes(response.status)) throw new PwaConnectionError();
    throw new Error("服务返回了无法识别的结果，请稍后重试。");
  }
  if (!response.ok || body.error) throw new Error(body.error || "连接失败，请稍后重试。");
  return body.result as T;
}

export function audioUrl(id:number,level="exhigh",owner?:number) {return `/api/pwa/audio/${id}?level=${encodeURIComponent(level)}&owner=${owner || "guest"}`;}
export function artworkUrl(url: string | null | undefined) { return url ? `/api/pwa/artwork?url=${encodeURIComponent(url)}` : ""; }
