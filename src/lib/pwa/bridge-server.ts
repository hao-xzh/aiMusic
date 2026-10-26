import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import { createHmac, randomBytes, timingSafeEqual } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import path from "node:path";
import { createInterface } from "node:readline";
import type { NextRequest, NextResponse } from "next/server";

const root = path.resolve(process.env.PIPO_PWA_DATA_DIR || path.join(process.cwd(), ".pwa-data"));
const cookieName = "pipo_pwa_session";
const commands = new Set([
  "netease_account", "netease_logout", "netease_user_playlists", "netease_playlist_track_ids", "netease_playlist_detail",
  "netease_search", "netease_daily_recommended_tracks", "netease_personal_fm_tracks", "netease_similar_tracks", "netease_user_cloud_tracks",
  "netease_qr_start", "netease_qr_check", "netease_captcha_sent", "netease_captcha_verify", "netease_phone_login", "netease_song_urls",
  "netease_song_lyric", "netease_cloud_lyric", "netease_like_song", "netease_playlist_create", "netease_playlist_modify_tracks",
  "audio_cache_set_max_mb", "audio_cache_clear", "audio_cache_stats", "audio_get_features",
  "ai_set_provider", "ai_get_config", "ai_set_api_key", "ai_clear_api_key", "ai_set_model", "ai_list_models", "ai_ping", "ai_chat", "ai_chat_tools", "ai_embed",
]);

export class PwaApiError extends Error {
  constructor(message: string, readonly status = 400) { super(message); }
}
export function sessionDataDirectory(session:string) {
  if(!/^[a-f0-9]{48}$/.test(session)) throw new PwaApiError("会话无效。",401);
  const directory=path.join(root,"sessions",session);mkdirSync(directory,{recursive:true,mode:0o700});return directory;
}
type Pending = { resolve: (value: unknown) => void; reject: (reason: Error) => void; timer: ReturnType<typeof setTimeout> };
type Worker = { process: ChildProcessWithoutNullStreams; pending: Map<number, Pending>; lastUsed: number; nextId: number };
type BridgeState = { workers: Map<string, Worker>; secret?: Buffer; cleanup?: ReturnType<typeof setInterval> };
const globals = globalThis as typeof globalThis & { __pipoPwaBridge?: BridgeState };
const state: BridgeState = globals.__pipoPwaBridge ||= { workers: new Map() };

function secret(): Buffer {
  if (state.secret) return state.secret;
  mkdirSync(root, { recursive: true, mode: 0o700 });
  const filename = path.join(root, "session-key");
  try { writeFileSync(filename, randomBytes(32), { flag: "wx", mode: 0o600 }); }
  catch (error) { if ((error as NodeJS.ErrnoException).code !== "EEXIST") throw error; }
  state.secret = readFileSync(filename);
  return state.secret;
}
function signature(id: string) { return createHmac("sha256", secret()).update(id).digest("hex"); }
export function agentSessionScope(id:string) {return createHmac("sha256",secret()).update(`agent-scope:${id}`).digest("hex");}

export function sessionFor(request: NextRequest, create = false): string {
  const value = request.cookies.get(cookieName)?.value || "";
  const [id, signed] = value.split(".");
  if (/^[a-f0-9]{48}$/.test(id || "") && /^[a-f0-9]{64}$/.test(signed || "") && timingSafeEqual(Buffer.from(signature(id), "hex"), Buffer.from(signed, "hex"))) return id;
  if (!create) throw new PwaApiError("请先打开 Pipo，建立当前设备的连接。", 401);
  return randomBytes(24).toString("hex");
}
export function attachSession(response: NextResponse, request: NextRequest, id: string) {
  response.cookies.set(cookieName, `${id}.${signature(id)}`, {
    httpOnly: true, sameSite: "strict", secure: request.nextUrl.protocol === "https:" || process.env.PIPO_PWA_PUBLIC_ORIGIN?.startsWith("https://") === true,
    path: "/api/pwa", maxAge: 365 * 24 * 60 * 60,
  });
  response.headers.set("Cache-Control", "private, no-store");
}
export function checkOrigin(request: NextRequest) {
  const origin = request.headers.get("origin");
  if (!origin) return;
  const expected = process.env.PIPO_PWA_PUBLIC_ORIGIN;
  if (origin === expected || origin === request.nextUrl.origin) return;
  throw new PwaApiError("请求来源与当前 Pipo 服务不一致。", 403);
}

function failWorker(session: string, worker: Worker, error: Error) {
  for (const pending of worker.pending.values()) { clearTimeout(pending.timer); pending.reject(error); }
  worker.pending.clear();
  if (state.workers.get(session) === worker) state.workers.delete(session);
}
function workerFor(session: string): Worker {
  const existing = state.workers.get(session);
  if (existing) return existing;
  if (state.workers.size >= 6) {
    const idle = [...state.workers].filter(([, worker]) => !worker.pending.size).sort((a, b) => a[1].lastUsed - b[1].lastUsed)[0];
    if (!idle) throw new PwaApiError("服务正在处理其它任务，请稍后重试。", 503);
    state.workers.delete(idle[0]); idle[1].process.kill();
  }
  const binary = process.env.PIPO_PWA_BRIDGE_BIN || path.join(process.cwd(), "services/pwa-bridge/target/release/pipo_pwa_bridge");
  if (!existsSync(binary)) throw new PwaApiError("Pipo 业务服务尚未构建，请先运行 pnpm build:pwa-bridge。", 503);
  const directory = path.join(root, "sessions", session);
  mkdirSync(directory, { recursive: true, mode: 0o700 });
  const child = spawn(binary, [directory], { stdio: "pipe" });
  const worker: Worker = { process: child, pending: new Map(), lastUsed: Date.now(), nextId: 0 };
  state.workers.set(session, worker);
  // Android bridge diagnostics may contain upstream data; keep them out of HTTP responses and browser logs.
  child.stderr.on("data", () => undefined);
  createInterface({ input: child.stdout }).on("line", line => {
    try {
      const message = JSON.parse(line) as { id: number; result: unknown };
      const pending = worker.pending.get(message.id);
      if (!pending) return;
      clearTimeout(pending.timer); worker.pending.delete(message.id);
      const result = message.result as { error?: unknown } | null;
      if (result && typeof result === "object" && typeof result.error === "string") pending.reject(new PwaApiError(result.error, 502));
      else pending.resolve(message.result);
    } catch { /* Ignore non-protocol diagnostics. */ }
  });
  child.once("error", () => failWorker(session, worker, new PwaApiError("无法启动 Pipo 业务服务。", 503)));
  child.once("exit", () => failWorker(session, worker, new PwaApiError("业务连接已中断，请重试。", 503)));
  if (!state.cleanup) {
    state.cleanup = setInterval(() => {
      for (const [id, candidate] of state.workers) {
        if (!candidate.pending.size && Date.now() - candidate.lastUsed > 10 * 60_000) { state.workers.delete(id); candidate.process.kill(); }
      }
    }, 60_000);
    state.cleanup.unref();
  }
  return worker;
}

export function bridgeCall<T = unknown>(session: string, command: string, args: Record<string, unknown> = {}): Promise<T> {
  if (!commands.has(command)) return Promise.reject(new PwaApiError("当前网页不支持此操作。", 400));
  const worker = workerFor(session);
  if (worker.pending.size >= 24) return Promise.reject(new PwaApiError("正在处理的任务过多，请稍后重试。", 429));
  worker.lastUsed = Date.now();
  const id = ++worker.nextId;
  return new Promise<T>((resolve, reject) => {
    const timer = setTimeout(() => { worker.pending.delete(id); reject(new PwaApiError("操作超时，请检查网络后重试。", 504)); }, command.startsWith("ai_") || command === "audio_get_features" ? 180_000 : 90_000);
    worker.pending.set(id, { resolve: value => resolve(value as T), reject, timer });
    worker.process.stdin.write(`${JSON.stringify({ id, command, args })}\n`, error => {
      if (error) { clearTimeout(timer); worker.pending.delete(id); reject(new PwaApiError("业务连接已中断。", 503)); }
    });
  });
}

export function allowedMusicUrl(raw: string): URL {
  let url: URL;
  try { url = new URL(raw); } catch { throw new PwaApiError("音频地址无效。", 400); }
  if (!/^https?:$/.test(url.protocol) || url.username || url.password || !["music.126.net", "music.163.com"].some(domain => url.hostname === domain || url.hostname.endsWith(`.${domain}`))) throw new PwaApiError("音频来源不在当前音乐服务范围内。", 400);
  url.protocol = "https:";
  return url;
}

export async function musicResponse(raw: string, request: NextRequest): Promise<Response> {
  let url = allowedMusicUrl(raw);
  for (let attempt = 0; attempt < 4; attempt++) {
    const headers: Record<string, string> = { "User-Agent": "Mozilla/5.0", "Accept-Encoding": "identity" };
    const range = request.headers.get("range");
    if (range && /^bytes=\d*-\d*$/.test(range)) headers.Range = range;
    const upstream = await fetch(url, { headers, redirect: "manual", cache: "no-store", signal: request.signal });
    if ([301, 302, 303, 307, 308].includes(upstream.status)) {
      const target = upstream.headers.get("location"); await upstream.body?.cancel();
      if (!target) throw new PwaApiError("音乐服务返回了无效跳转。", 502);
      url = allowedMusicUrl(new URL(target, url).href); continue;
    }
    const responseHeaders = new Headers({ "Cache-Control": "private, no-store", "X-Content-Type-Options": "nosniff" });
    for (const key of ["Content-Type", "Content-Length", "Content-Range", "Accept-Ranges"]) { const value = upstream.headers.get(key); if (value) responseHeaders.set(key, value); }
    const type=(responseHeaders.get("Content-Type")||"").split(";")[0].toLowerCase();
    if(!type||type==="application/octet-stream"||type==="binary/octet-stream") {
      const extension=/\.([a-z0-9]+)$/i.exec(url.pathname)?.[1].toLowerCase();
      const audioTypes:Record<string,string>={mp3:"audio/mpeg",flac:"audio/flac",m4a:"audio/mp4",mp4:"audio/mp4",aac:"audio/aac",ogg:"audio/ogg",wav:"audio/wav"};
      if(extension&&audioTypes[extension]) responseHeaders.set("Content-Type",audioTypes[extension]);
    }
    return new Response(upstream.body, { status: upstream.status, headers: responseHeaders });
  }
  throw new PwaApiError("音乐服务跳转次数过多。", 502);
}
