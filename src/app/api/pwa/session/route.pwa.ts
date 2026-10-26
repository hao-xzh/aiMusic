import { NextRequest, NextResponse } from "next/server";
import { attachSession, checkOrigin, sessionFor } from "@/lib/pwa/bridge-server";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";
export function GET(request: NextRequest) {
  checkOrigin(request);
  const response = NextResponse.json({ ready:true });
  attachSession(response, request, sessionFor(request, true));
  return response;
}
