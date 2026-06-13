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
 * 用户可以直接进歌单列表听歌。
 */

import { BackButton } from "@/components/BackButton";
import { netease, type QrCheck } from "@/lib/tauri";
import Link from "next/link";
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
    <div style={pageWrap}>
      <div className="safe-top" style={{ display: "flex", paddingTop: 4 }}>
        <BackButton href="/" />
      </div>
      <PageHeading
        title="LOGIN"
        subtitle={
          usePhone
            ? "登录网易云，把歌单和云盘接进 Pipo。"
            : "用网易云 App 扫码确认登录。"
        }
      />

      {decided && (
        <>
          <LoginSectionHeader index="01" title={usePhone ? "PHONE" : "QR"} />
          {usePhone ? <PhoneLoginCard /> : <QrLoginCard />}
        </>
      )}

      {decided && (
        <div
          style={{
            color: "#8a93a8",
            fontSize: 12,
            textAlign: "left",
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
          textAlign: "left",
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
    <div style={card}>
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
          <LoginDone nickname={phase.nickname} compact />
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
      <div style={card}>
        <LoginDone nickname={phase.nickname} />
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
    <div style={{ ...card, alignItems: "stretch" }}>
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

function LoginDone({
  nickname,
  compact = false,
}: {
  nickname?: string | null;
  compact?: boolean;
}) {
  return (
    <div style={{ ...expiredBox, color: MINT, maxWidth: compact ? 216 : 320 }}>
      <div style={{ fontSize: compact ? 19 : 22, fontWeight: 700 }}>
        已登录{nickname ? ` · ${nickname}` : ""}
      </div>
      <Link href="/distill" style={playlistCta}>
        进入我的歌单 →
      </Link>
    </div>
  );
}

// ============================ 样式 ============================

const pageWrap: React.CSSProperties = {
  padding: "clamp(12px, 2vw, 18px) clamp(18px, 5vw, 34px) 60px",
  maxWidth: 620,
  margin: "0 auto",
  width: "100%",
};

function PageHeading({ title, subtitle }: { title: string; subtitle: string }) {
  return (
    <header style={pageHeading}>
      <div style={pageTitle}>{title}</div>
      <div style={pageSubtitle}>{subtitle}</div>
    </header>
  );
}

function LoginSectionHeader({ index, title }: { index: string; title: string }) {
  return (
    <div style={sectionBlock}>
      <div style={sectionHeader}>
        <span style={sectionIndex}>{index}</span>
        <span style={sectionSlash}>/</span>
        <span style={sectionLabel}>{title}</span>
      </div>
      <div style={divider} />
    </div>
  );
}

const pageHeading: React.CSSProperties = {
  paddingTop: "clamp(18px, 4vh, 34px)",
  paddingBottom: 18,
};

const pageTitle: React.CSSProperties = {
  color: "#e9efff",
  fontSize: 20,
  fontWeight: 800,
  letterSpacing: 4,
  fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace",
};

const pageSubtitle: React.CSSProperties = {
  color: "rgba(233,239,255,0.42)",
  marginTop: 8,
  fontSize: 13,
  lineHeight: 1.55,
};

const sectionBlock: React.CSSProperties = {
  marginBottom: 0,
};

const sectionHeader: React.CSSProperties = {
  display: "flex",
  alignItems: "baseline",
  gap: 8,
  paddingTop: 10,
  paddingBottom: 8,
};

const sectionIndex: React.CSSProperties = {
  color: "rgba(233,239,255,0.42)",
  fontSize: 11,
  fontWeight: 800,
  letterSpacing: 2,
  fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace",
};

const sectionSlash: React.CSSProperties = {
  color: "rgba(233,239,255,0.22)",
  fontSize: 11,
  fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace",
};

const sectionLabel: React.CSSProperties = {
  color: "#e9efff",
  fontSize: 12,
  fontWeight: 800,
  letterSpacing: 3,
  fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace",
};

const divider: React.CSSProperties = {
  height: 1,
  background: "rgba(233,239,255,0.08)",
  width: "100%",
};

const card: React.CSSProperties = {
  padding: "18px 0 0",
  display: "flex",
  flexDirection: "column",
  alignItems: "center",
};
const qrBox: React.CSSProperties = {
  width: 240,
  height: 240,
  padding: 10,
  borderRadius: 0,
  background: "rgba(10,13,20,0.72)",
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
  borderRadius: 0,
  border: `1px solid ${AMBER}`,
  background: "transparent",
  color: AMBER,
  cursor: "pointer",
  fontSize: 13,
};
const playlistCta: React.CSSProperties = {
  display: "inline-flex",
  alignItems: "center",
  justifyContent: "center",
  marginTop: 16,
  padding: "8px 14px",
  borderRadius: 0,
  border: "1px solid rgba(233,239,255,0.92)",
  background: "rgba(233,239,255,0.94)",
  color: "#05060a",
  textDecoration: "none",
  cursor: "pointer",
  fontSize: 12,
  fontWeight: 700,
  letterSpacing: 0.8,
};
const fieldLabel: React.CSSProperties = {
  color: "#8a93a8",
  fontSize: 12,
  marginBottom: 6,
};
const input: React.CSSProperties = {
  height: 44,
  padding: "0 14px",
  borderRadius: 0,
  border: "1px solid rgba(233,239,255,0.10)",
  background: "rgba(10,13,20,0.72)",
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
  borderRadius: 0,
  border: "1px solid rgba(233,239,255,0.10)",
  background: "rgba(10,13,20,0.72)",
  color: "#8a93a8",
  fontSize: 14,
};
const sendCodeBtn: React.CSSProperties = {
  height: 44,
  padding: "0 14px",
  borderRadius: 0,
  border: `1px solid ${MINT}`,
  background: "transparent",
  color: MINT,
  fontSize: 13,
  whiteSpace: "nowrap",
  fontFamily: "inherit",
};
const submitBtn: React.CSSProperties = {
  height: 48,
  borderRadius: 0,
  border: "1px solid rgba(233,239,255,0.92)",
  background: "rgba(233,239,255,0.94)",
  color: "#05060a",
  fontSize: 13,
  fontWeight: 700,
  letterSpacing: 0.8,
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
