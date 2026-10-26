import { NextRequest, NextResponse } from "next/server";
import { bridgeCall, musicResponse, PwaApiError, sessionFor } from "@/lib/pwa/bridge-server";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export async function GET(request: NextRequest, context: { params: Promise<{ id: string }> }) {
  try {
    const { id } = await context.params;
    if (!/^\d{1,16}$/.test(id) || !Number.isSafeInteger(Number(id))) throw new PwaApiError("歌曲编号无效。");
    const session = sessionFor(request);
    const levels = new Set(["standard", "higher", "exhigh", "lossless", "hires", "jyeffect", "sky", "jymaster"]);
    const level = request.nextUrl.searchParams.get("level") || "exhigh";
    const urls = await bridgeCall<Array<{ id: number; url: string | null }>>(session, "netease_song_urls", { ids: [Number(id)], level: levels.has(level) ? level : "exhigh" });
    const url = urls.find(item => item.id === Number(id))?.url;
    if (!url) throw new PwaApiError("当前账号暂时无法播放这首歌。", 404);
    const response = await musicResponse(url, request);
    if (response.ok && !/^(audio\/|video\/mp4|application\/(octet-stream|ogg))/i.test(response.headers.get("Content-Type") || "")) {
      await response.body?.cancel(); throw new PwaApiError("音乐服务返回的内容无法播放。", 502);
    }
    if (request.nextUrl.searchParams.get("download") === "1") response.headers.set("Content-Disposition", `attachment; filename="${id}.audio"`);
    return response;
  } catch (error) {
    const message=error instanceof PwaApiError ? error.message : "音频加载失败，请稍后重试。";
    return NextResponse.json({ error:message }, { status: error instanceof PwaApiError ? error.status : 502, headers: { "Cache-Control": "no-store","X-Pipo-Audio-Error":encodeURIComponent(message) } });
  }
}

/** A failed player can inspect the current response without downloading another complete song. */
export async function HEAD(request:NextRequest,context:{params:Promise<{id:string}>}) {
  const headers=new Headers(request.headers);headers.set("Range","bytes=0-1");
  const response=await GET(new NextRequest(request.url,{headers,signal:request.signal}),context);
  await response.body?.cancel();return new Response(null,{status:response.status,headers:response.headers});
}
