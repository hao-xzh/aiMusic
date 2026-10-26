import { NextRequest, NextResponse } from "next/server";
import { allowedMusicUrl, attachSession, bridgeCall, checkOrigin, PwaApiError, sessionFor } from "@/lib/pwa/bridge-server";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export async function POST(request: NextRequest) {
  try {
    checkOrigin(request);
    if (!request.headers.get("content-type")?.startsWith("application/json")) throw new PwaApiError("请求格式无效。", 415);
    if (Number(request.headers.get("content-length") || 0) > 2_000_000) throw new PwaApiError("请求内容过长。", 413);
    const text = await request.text();
    if (text.length > 2_000_000) throw new PwaApiError("请求内容过长。", 413);
    const input = JSON.parse(text) as { command?: unknown; args?: unknown };
    if (typeof input.command !== "string" || (input.args != null && (typeof input.args !== "object" || Array.isArray(input.args)))) throw new PwaApiError("请求参数无效。");
    const args = (input.args || {}) as Record<string, unknown>;
    if (input.command === "audio_get_features") args.url = allowedMusicUrl(String(args.url || "")).href;
    const session = sessionFor(request, true);
    const result = await bridgeCall(session, input.command, args);
    const response = NextResponse.json({ result }); attachSession(response, request, session); return response;
  } catch (error) {
    return NextResponse.json({ error: error instanceof PwaApiError ? error.message : "服务暂时不可用，请稍后重试。" }, { status: error instanceof PwaApiError ? error.status : 500, headers: { "Cache-Control": "no-store" } });
  }
}
