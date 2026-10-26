import { NextRequest, NextResponse } from "next/server";
import { musicResponse, PwaApiError } from "@/lib/pwa/bridge-server";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export async function GET(request: NextRequest) {
  try {
    const response = await musicResponse(request.nextUrl.searchParams.get("url") || "", request);
    if (!/^image\/(jpe?g|png|webp|avif|gif)(;|$)/i.test(response.headers.get("Content-Type") || "")) { await response.body?.cancel(); throw new PwaApiError("封面格式无效。", 502); }
    if (/^image\/jpg(;|$)/i.test(response.headers.get("Content-Type") || "")) response.headers.set("Content-Type", "image/jpeg");
    response.headers.set("Cache-Control", "public, max-age=86400"); return response;
  } catch (error) { return NextResponse.json({ error: error instanceof Error ? error.message : "封面加载失败。" }, { status: error instanceof PwaApiError ? error.status : 502 }); }
}
