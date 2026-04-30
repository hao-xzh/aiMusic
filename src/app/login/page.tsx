"use client";

/**
 * 登录页。
 *
 * 桌面 / iOS：扫码登录（netease_qr_start + netease_qr_check 轮询）
 * Android：默认走手机号 + 验证码登录 —— 自己扫自己的二维码很别扭，
 *   所以平台分流：UA 命中 Android 时优先展示手机号表单，
 *   留个"用扫码登录"的兜底链接（极少数用户可能想拿别的设备扫）。
 *
 * 两条路径成功后 cookie 都已经在 Rust 侧 persist()，前端展示 "已登录"，
 * 用户自己回首页 / 去蒸馏歌单（跟原扫码流程行为一致，不强制跳转）。
 */

import { BackButton } from "@/components/BackButton";
import { DotText } from "@/components/DotText";
import { netease, type QrCheck } from "@/lib/tauri";
import QRCode from "qrcode";
import { useEffect, useRef, useState } from "react";

type QrPhase =
  | { kind: "starting" }
  | { kind: "waiting"; key: string }       // 801
  | { kind: "scanned"; key: string }       // 802
  | { kind: "expired" }                    // 800
  | { kind: "done"; nickname?: string | null }
  | { kind: "error"; message: string };

type PhonePhase =
  | { kind: "idle" }
  | { kind: "sending" }
  | { kind: "sent"; cooldown: number }
  | { kind: "verifying" }
  | { kind: "done"; nickname?: string | null }
  | { kind: "error"; message: string };

const MINT = "#9be3c6";
const AMBER = "#ffd28a";
const RED = "#ffb4b4";
const POLL_MS = 2000;
const RESEND_COOLDOWN_S = 60;

/** UA 检测 —— 只在 mounted 之后跑一次，避免 SSR/CSR 不一致。 */
function detectAndroid(): boolean {
  if (typeof navigator === "undefined") return false;
  return /Android/i.test(navigator.userAgent);
}

export default function LoginPage() {
  // null = 还没决定（首屏 SSR 时），决定完之后切到具体方法。
  // 这里给桌面默认 false，移动决定后再改 —— 避免桌面用户多看一帧空白。
  const [usePhone, setUsePhone] = useState<boolean>(false);
  const [decided, setDecided] = useState<boolean>(false);

  useEffect(() => {
    setUsePhone(detectAndroid());
    setDecided(true);
  }, []);

  return (
    <div
      style={{
        padding: "clamp(8px, 2vw, 16px) clamp(12px, 4vw, 24px) 60px",
        maxWidth: 520,
        margin: "0 auto",
        width: "100%",
      }}
    >
      <div className="safe-top" style={{ display: "flex", paddingTop: 4 }}>
        <BackButton href="/" />
      </div>
      <div
        style={{
          display: "flex",
          justifyContent: "center",
          marginTop: "clamp(12px, 3vh, 24px)",
          marginBottom: 6,
        }}
      >
        <DotText
          text={usePhone ? "手机号登录" : "扫码登录"}
          fontSize={48}
          grid={4}
          dotRadius={1.6}
        />
      </div>
      <div
        style={{
          textAlign: "center",
          color: "#8a93a8",
          marginBottom: "clamp(18px, 4vh, 28px)",
          fontSize: 13,
        }}
      >
        {usePhone
          ? "用手机号 + 短信验证码登录，把你 14 年的歌全部接进来。"
          : "用手机上的网易云音乐 App 扫码，就能把你 14 年的歌全部接进来。"}
      </div>

      {decided && (usePhone ? <PhoneLoginCard /> : <QrLoginCard />)}

      {decided && (
        <div
          style={{
            color: "#8a93a8",
            fontSize: 12,
            textAlign: "center",
            marginTop: 14,
          }}
        >
          <button
            type="button"
            onClick={() => setUsePhone((v) => !v)}
            style={swapBtn}
          >
            {usePhone ? "改用扫码登录" : "改用手机号登录"}
          </button>
        </div>
      )}

      <div
        style={{
          color: "#8a93a8",
          fontSize: 12,
          textAlign: "center",
          marginTop: 18,
        }}
      >
        登录信息只留在本机 cookie jar，不上传任何服务器。
      </div>
    </div>
  );
}

// ============================ 扫码登录 ============================

function QrLoginCard() {
  const [phase, setPhase] = useState<QrPhase>({ kind: "starting" });
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const pollRef = useRef<number | null>(null);

  async function start() {
    setPhase({ kind: "starting" });
    try {
      const { key, qrContent } = await netease.qrStart();
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
      setPhase({
        kind: "error",
        message: e instanceof Error ? e.message : String(e),
      });
    }
  }

  function beginPolling(key: string) {
    stopPolling();
    const tick = async () => {
      let r: QrCheck;
      try {
        r = await netease.qrCheck(key);
      } catch (e) {
        setPhase({
          kind: "error",
          message: e instanceof Error ? e.message : String(e),
        });
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
          setPhase({
            kind: "error",
            message: `qr_check unexpected code=${r.code} msg=${r.message ?? ""}`,
          });
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

  const statusLine = qrStatusLine(phase);

  return (
    <div className="glass" style={card}>
      <div style={qrBox}>
        <canvas
          ref={canvasRef}
          width={220}
          height={220}
          style={{
            display:
              phase.kind === "done" || phase.kind === "expired"
                ? "none"
                : "block",
          }}
        />
        {phase.kind === "expired" && (
          <div style={expiredBox}>
            <div style={{ color: AMBER, marginBottom: 10 }}>二维码已过期</div>
            <button style={refreshBtn} onClick={start}>
              重新获取
            </button>
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

      <div
        style={{
          textAlign: "center",
          marginTop: 18,
          color: MINT,
          fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace",
          fontSize: 13,
        }}
      >
        {statusLine}
      </div>

      {phase.kind === "error" && (
        <div
          style={{
            marginTop: 12,
            color: RED,
            fontSize: 12,
            textAlign: "center",
          }}
        >
          {phase.message}
        </div>
      )}
    </div>
  );
}

function qrStatusLine(phase: QrPhase): string {
  switch (phase.kind) {
    case "starting":
      return "正在生成二维码…";
    case "waiting":
      return "等你扫码 · 手机打开网易云 App → 右上角扫一扫";
    case "scanned":
      return "扫码成功，请在手机上点 “确认登录”";
    case "expired":
      return "二维码已过期";
    case "done":
      return "登录完成";
    case "error":
      return "出错了";
  }
}

// ============================ 手机号登录 ============================

function PhoneLoginCard() {
  const [phone, setPhone] = useState("");
  const [captcha, setCaptcha] = useState("");
  const [phase, setPhase] = useState<PhonePhase>({ kind: "idle" });
  const cooldownTimer = useRef<number | null>(null);

  // 简单的大陆手机号校验：1 开头 + 10 位数字。其他国家暂时不暴露区号选择。
  const phoneOk = /^1\d{10}$/.test(phone);
  const captchaOk = /^\d{4,6}$/.test(captcha);

  function startCooldown() {
    if (cooldownTimer.current != null) {
      clearInterval(cooldownTimer.current);
    }
    cooldownTimer.current = window.setInterval(() => {
      setPhase((p) => {
        if (p.kind !== "sent") return p;
        const next = p.cooldown - 1;
        if (next <= 0) {
          if (cooldownTimer.current != null) {
            clearInterval(cooldownTimer.current);
            cooldownTimer.current = null;
          }
          return { kind: "idle" };
        }
        return { kind: "sent", cooldown: next };
      });
    }, 1000);
  }

  useEffect(() => {
    return () => {
      if (cooldownTimer.current != null) {
        clearInterval(cooldownTimer.current);
        cooldownTimer.current = null;
      }
    };
  }, []);

  async function sendCode() {
    if (!phoneOk) return;
    setPhase({ kind: "sending" });
    try {
      const r = await netease.captchaSent(phone);
      if (r.code === 200) {
        setPhase({ kind: "sent", cooldown: RESEND_COOLDOWN_S });
        startCooldown();
      } else {
        setPhase({
          kind: "error",
          message: r.message ?? `发送失败（code=${r.code}）`,
        });
      }
    } catch (e) {
      setPhase({
        kind: "error",
        message: e instanceof Error ? e.message : String(e),
      });
    }
  }

  async function submit() {
    if (!phoneOk || !captchaOk) return;
    setPhase({ kind: "verifying" });
    try {
      const r = await netease.phoneLogin(phone, captcha);
      if (r.code === 200) {
        if (cooldownTimer.current != null) {
          clearInterval(cooldownTimer.current);
          cooldownTimer.current = null;
        }
        setPhase({ kind: "done", nickname: r.nickname });
      } else {
        setPhase({
          kind: "error",
          message: r.message ?? `登录失败（code=${r.code}）`,
        });
      }
    } catch (e) {
      setPhase({
        kind: "error",
        message: e instanceof Error ? e.message : String(e),
      });
    }
  }

  // 已登录态：跟扫码成功一样，留个静态卡片就行。
  if (phase.kind === "done") {
    return (
      <div className="glass" style={card}>
        <div style={{ ...expiredBox, color: MINT }}>
          <div style={{ fontSize: 22, fontWeight: 700 }}>
            已登录{phase.nickname ? ` · ${phase.nickname}` : ""}
          </div>
          <div style={{ color: "#8a93a8", marginTop: 8, fontSize: 12 }}>
            现在可以去蒸馏歌单了。
          </div>
        </div>
      </div>
    );
  }

  const sending = phase.kind === "sending";
  const verifying = phase.kind === "verifying";
  const sentCooldown = phase.kind === "sent" ? phase.cooldown : 0;
  const sendDisabled = !phoneOk || sending || sentCooldown > 0;
  const submitDisabled =
    !phoneOk || !captchaOk || verifying || phase.kind === "sending";

  return (
    <div className="glass" style={{ ...card, alignItems: "stretch" }}>
      <label style={fieldLabel}>手机号</label>
      <div style={{ display: "flex", gap: 8 }}>
        <div style={prefix}>+86</div>
        <input
          type="tel"
          inputMode="numeric"
          autoComplete="tel"
          maxLength={11}
          placeholder="11 位手机号"
          value={phone}
          onChange={(e) => setPhone(e.target.value.replace(/\D/g, ""))}
          style={{ ...input, flex: 1 }}
        />
      </div>

      <label style={{ ...fieldLabel, marginTop: 14 }}>短信验证码</label>
      <div style={{ display: "flex", gap: 8 }}>
        <input
          type="text"
          inputMode="numeric"
          autoComplete="one-time-code"
          maxLength={6}
          placeholder="4-6 位数字"
          value={captcha}
          onChange={(e) => setCaptcha(e.target.value.replace(/\D/g, ""))}
          style={{ ...input, flex: 1, letterSpacing: 4 }}
        />
        <button
          type="button"
          onClick={sendCode}
          disabled={sendDisabled}
          style={{
            ...sendCodeBtn,
            opacity: sendDisabled ? 0.5 : 1,
            cursor: sendDisabled ? "not-allowed" : "pointer",
          }}
        >
          {sentCooldown > 0
            ? `${sentCooldown}s`
            : sending
            ? "发送中…"
            : "发送验证码"}
        </button>
      </div>

      <button
        type="button"
        onClick={submit}
        disabled={submitDisabled}
        style={{
          ...submitBtn,
          opacity: submitDisabled ? 0.5 : 1,
          cursor: submitDisabled ? "not-allowed" : "pointer",
          marginTop: 22,
        }}
      >
        {verifying ? "登录中…" : "登录"}
      </button>

      <div
        style={{
          textAlign: "center",
          marginTop: 14,
          color: MINT,
          fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace",
          fontSize: 12,
          minHeight: 16,
        }}
      >
        {phoneStatusLine(phase)}
      </div>

      {phase.kind === "error" && (
        <div
          style={{
            marginTop: 4,
            color: RED,
            fontSize: 12,
            textAlign: "center",
          }}
        >
          {phase.message}
        </div>
      )}
    </div>
  );
}

function phoneStatusLine(phase: PhonePhase): string {
  switch (phase.kind) {
    case "idle":
      return "输入手机号 → 发送验证码 → 登录";
    case "sending":
      return "正在请求验证码…";
    case "sent":
      return `验证码已发送，${phase.cooldown}s 内勿重复发送`;
    case "verifying":
      return "正在验证…";
    case "done":
      return "登录完成";
    case "error":
      return "出错了";
  }
}

// ============================ 样式 ============================

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
const fieldLabel: React.CSSProperties = {
  color: "#8a93a8",
  fontSize: 12,
  marginBottom: 6,
};
const input: React.CSSProperties = {
  height: 44,
  padding: "0 14px",
  borderRadius: 12,
  border: "1px solid rgba(233,239,255,0.12)",
  background: "rgba(255,255,255,0.04)",
  color: "#e9efff",
  fontSize: 16,
  outline: "none",
  fontFamily: "inherit",
  WebkitAppearance: "none",
};
const prefix: React.CSSProperties = {
  height: 44,
  display: "flex",
  alignItems: "center",
  padding: "0 14px",
  borderRadius: 12,
  border: "1px solid rgba(233,239,255,0.12)",
  background: "rgba(255,255,255,0.04)",
  color: "#8a93a8",
  fontSize: 14,
};
const sendCodeBtn: React.CSSProperties = {
  height: 44,
  padding: "0 14px",
  borderRadius: 12,
  border: `1px solid ${MINT}`,
  background: "transparent",
  color: MINT,
  fontSize: 13,
  whiteSpace: "nowrap",
  fontFamily: "inherit",
};
const submitBtn: React.CSSProperties = {
  height: 48,
  borderRadius: 999,
  border: "none",
  background: MINT,
  color: "#0e1116",
  fontSize: 15,
  fontWeight: 700,
  fontFamily: "inherit",
};
const swapBtn: React.CSSProperties = {
  background: "transparent",
  border: "none",
  color: MINT,
  fontSize: 12,
  cursor: "pointer",
  padding: "4px 8px",
  fontFamily: "inherit",
};
