"use client";

/**
 * 二维码登录页。
 * 流程：
 *   netease_qr_start() -> { key, qrContent }
 *   前端画 qrContent 成二维码
 *   每 2 秒 netease_qr_check(key)：
 *     800 = 过期，refresh 重来
 *     801 = 等待扫码
 *     802 = 已扫码，等手机上确认
 *     803 = 登录成功（cookie 已经自动落到 jar 里）
 */

import { DotText } from "@/components/DotText";
import { netease, type QrCheck } from "@/lib/tauri";
import QRCode from "qrcode";
import { useEffect, useRef, useState } from "react";

type Phase =
  | { kind: "starting" }
  | { kind: "waiting"; key: string }       // 801
  | { kind: "scanned"; key: string }       // 802
  | { kind: "expired" }                    // 800 -> UI 上提示点刷新
  | { kind: "done"; nickname?: string | null }
  | { kind: "error"; message: string };

const MINT = "#9be3c6";
const AMBER = "#ffd28a";
const POLL_MS = 2000;

export default function LoginPage() {
  const [phase, setPhase] = useState<Phase>({ kind: "starting" });
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const pollRef = useRef<number | null>(null);

  // 启动 / 重启登录流程
  async function start() {
    setPhase({ kind: "starting" });
    try {
      const { key, qrContent } = await netease.qrStart();
      // 画二维码
      if (canvasRef.current) {
        await QRCode.toCanvas(canvasRef.current, qrContent, {
          width: 220,
          margin: 1,
          color: { dark: "#e9efff", light: "#00000000" },
        });
      }
      setPhase({ kind: "waiting", key });
      beginPolling(key);
    } catch (e) {
      setPhase({ kind: "error", message: e instanceof Error ? e.message : String(e) });
    }
  }

  function beginPolling(key: string) {
    stopPolling();
    const tick = async () => {
      let r: QrCheck;
      try {
        r = await netease.qrCheck(key);
      } catch (e) {
        setPhase({ kind: "error", message: e instanceof Error ? e.message : String(e) });
        return;
      }
      switch (r.code) {
        case 800:
          setPhase({ kind: "expired" });
          stopPolling();
          return;
        case 801:
          setPhase({ kind: "waiting", key });
          break;
        case 802:
          setPhase({ kind: "scanned", key });
          break;
        case 803:
          setPhase({ kind: "done", nickname: r.nickname });
          stopPolling();
          return;
        default:
          // 其他 code 先原样展示，方便调试
          setPhase({ kind: "error", message: `qr_check unexpected code=${r.code} msg=${r.message ?? ""}` });
          stopPolling();
          return;
      }
      pollRef.current = window.setTimeout(tick, POLL_MS);
    };
    pollRef.current = window.setTimeout(tick, POLL_MS);
  }

  function stopPolling() {
    if (pollRef.current != null) {
      clearTimeout(pollRef.current);
      pollRef.current = null;
    }
  }

  useEffect(() => {
    start();
    return () => stopPolling();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const statusLine = useStatusLine(phase);

  return (
    <div
      style={{
        padding: "clamp(8px, 2vw, 16px) clamp(12px, 4vw, 24px) 60px",
        maxWidth: 520,
        margin: "0 auto",
        width: "100%",
      }}
    >
      <div style={{ display: "flex", justifyContent: "center", marginTop: "clamp(12px, 3vh, 24px)", marginBottom: 6 }}>
        <DotText text="扫码登录" fontSize={48} grid={4} dotRadius={1.6} />
      </div>
      <div style={{ textAlign: "center", color: "#8a93a8", marginBottom: "clamp(18px, 4vh, 28px)", fontSize: 13 }}>
        用手机上的网易云音乐 App 扫码，就能把你 14 年的歌全部接进来。
      </div>

      <div className="glass" style={card}>
        <div style={qrBox}>
          {/* 即便 phase 变成 done，也留着 canvas 尺寸占位，避免抖动 */}
          <canvas
            ref={canvasRef}
            width={220}
            height={220}
            style={{ display: phase.kind === "done" || phase.kind === "expired" ? "none" : "block" }}
          />
          {phase.kind === "expired" && (
            <div style={expiredBox}>
              <div style={{ color: AMBER, marginBottom: 10 }}>二维码已过期</div>
              <button style={refreshBtn} onClick={start}>重新获取</button>
            </div>
          )}
          {phase.kind === "done" && (
            <div style={{ ...expiredBox, color: MINT }}>
              <div style={{ fontSize: 22, fontWeight: 700 }}>
                已登录{phase.nickname ? ` · ${phase.nickname}` : ""}
              </div>
              <div style={{ color: "#8a93a8", marginTop: 8, fontSize: 12 }}>
                现在可以去蒸馏歌单了。
              </div>
            </div>
          )}
        </div>

        <div style={{ textAlign: "center", marginTop: 18, color: MINT, fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace", fontSize: 13 }}>
          {statusLine}
        </div>

        {phase.kind === "error" && (
          <div style={{ marginTop: 12, color: "#ffb4b4", fontSize: 12, textAlign: "center" }}>
            {phase.message}
          </div>
        )}
      </div>

      <div style={{ color: "#8a93a8", fontSize: 12, textAlign: "center", marginTop: 18 }}>
        登录信息只留在本机 cookie jar，不上传任何服务器。
      </div>
    </div>
  );
}

function useStatusLine(phase: Phase): string {
  switch (phase.kind) {
    case "starting": return "正在生成二维码…";
    case "waiting":  return "等你扫码 · 手机打开网易云 App → 右上角扫一扫";
    case "scanned":  return "扫码成功，请在手机上点 \u201c确认登录\u201d";
    case "expired":  return "二维码已过期";
    case "done":     return "登录完成";
    case "error":    return "出错了";
  }
}

const card: React.CSSProperties = {
  padding: 28,
  display: "flex",
  flexDirection: "column",
  alignItems: "center",
};
const qrBox: React.CSSProperties = {
  width: 240,
  height: 240,
  padding: 10,
  borderRadius: 16,
  background: "rgba(255,255,255,0.03)",
  border: "1px solid rgba(233,239,255,0.08)",
  display: "flex",
  alignItems: "center",
  justifyContent: "center",
};
const expiredBox: React.CSSProperties = {
  textAlign: "center",
  padding: 12,
};
const refreshBtn: React.CSSProperties = {
  padding: "8px 16px",
  borderRadius: 999,
  border: `1px solid ${AMBER}`,
  background: "transparent",
  color: AMBER,
  cursor: "pointer",
  fontSize: 13,
};
