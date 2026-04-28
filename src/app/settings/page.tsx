"use client";

import { DotText } from "@/components/DotText";
import { ai, netease, type AiConfigPublic, type UserProfile } from "@/lib/tauri";
import { useAppSettings } from "@/lib/app-settings";
import Link from "next/link";
import { useEffect, useState } from "react";

export default function SettingsPage() {
  const [me, setMe] = useState<UserProfile | null | "loading">("loading");
  const [err, setErr] = useState<string | null>(null);
  const [appSettings, updateAppSettings] = useAppSettings();

  useEffect(() => {
    let alive = true;
    (async () => {
      try {
        const loggedIn = await netease.isLoggedIn();
        if (!loggedIn) {
          if (alive) setMe(null);
          return;
        }
        const profile = await netease.account();
        if (alive) setMe(profile);
      } catch (e) {
        if (alive) setErr(e instanceof Error ? e.message : String(e));
      }
    })();
    return () => {
      alive = false;
    };
  }, []);

  return (
    <div
      style={{
        padding: "clamp(8px, 2vw, 16px) clamp(12px, 4vw, 24px) 60px",
        maxWidth: 720,
        margin: "0 auto",
        width: "100%",
      }}
    >
      <div style={{ display: "flex", justifyContent: "center", marginTop: "clamp(12px, 3vh, 24px)", marginBottom: 6 }}>
        <DotText text="设置" fontSize={56} grid={4} dotRadius={1.6} />
      </div>
      <div style={{ textAlign: "center", color: "#8a93a8", marginBottom: "clamp(20px, 4vh, 32px)", fontSize: 13 }}>
        Claudio 把你的账号状态、播放规则、AI 口吻都攒在本地。
      </div>

      <Section title="音乐来源">
        <NeteaseRow me={me} err={err} />
      </Section>

      <Section title="外观">
        <Toggle
          label="隐藏点阵纹理"
          desc="去掉播放页全屏的点阵叠加，只保留封面模糊背景"
          value={appSettings.hideDotPattern}
          onChange={(v) => updateAppSettings({ hideDotPattern: v })}
        />
      </Section>

      <Section title="播放规则">
        <Toggle label="工作时段自动播放" desc="09:00 – 18:30（可自定义）" initial={true} />
        <Toggle label="开会时自动暂停" desc="读取系统日历判断" initial={true} />
        <Toggle label="午休换放松歌单" desc="12:00 – 13:30" initial={false} />
        <Toggle label="深夜降低推荐节奏" desc="22:30 之后" initial={true} />
      </Section>

      <Section title="AI 接入 · DeepSeek">
        <DeepSeekRow />
      </Section>

      <Section title="AI 解说">
        <Toggle label="每首歌给一段解说" desc="类似 Claudio 的那种 DJ 口吻" initial={true} />
        <Toggle label="中文解说" desc="关闭则使用英文" initial={true} />
      </Section>

      <div style={{ marginTop: 24, color: "#8a93a8", fontSize: 12, textAlign: "center" }}>
        登录 cookie & API key 仅保存在本机 · 不上传
      </div>
    </div>
  );
}

// ---------- DeepSeek API key 录入 / 测试 ----------
//
// 设计决定：
//   - 文本框只在"用户正在编辑"时显示内容；保存后切回 masked preview（sk-a•••xyz9）。
//   - 测试按钮调 ai.ping()，把模型回的问候语亮出来 —— 既验 key，又让用户"听到"这把 key
//     连上的模型是什么口吻。
//   - 没填 key 也能点保存（传空串清除）；后端已 trim 处理。

function DeepSeekRow() {
  const [cfg, setCfg] = useState<AiConfigPublic | null>(null);
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState("");
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);
  const [reply, setReply] = useState<string | null>(null);
  const [err, setErr] = useState<string | null>(null);

  const refresh = async () => {
    try {
      const c = await ai.getConfig();
      setCfg(c);
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e));
    }
  };

  useEffect(() => {
    refresh();
  }, []);

  const save = async () => {
    setSaving(true);
    setErr(null);
    setReply(null);
    try {
      await ai.setApiKey(draft);
      setEditing(false);
      setDraft("");
      await refresh();
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e));
    } finally {
      setSaving(false);
    }
  };

  const clear = async () => {
    if (!confirm("确定要清除 DeepSeek API key 吗？")) return;
    setErr(null);
    setReply(null);
    try {
      await ai.clearApiKey();
      await refresh();
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e));
    }
  };

  const test = async () => {
    setTesting(true);
    setErr(null);
    setReply(null);
    try {
      const r = await ai.ping();
      setReply(r);
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e));
    } finally {
      setTesting(false);
    }
  };

  const inputStyle: React.CSSProperties = {
    flex: 1,
    padding: "10px 14px",
    borderRadius: 10,
    border: "1px solid rgba(233,239,255,0.15)",
    background: "rgba(14,18,28,0.6)",
    color: "#e9efff",
    fontSize: 13,
    fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace",
    outline: "none",
  };

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 10, padding: "2px 2px" }}>
      <div>
        <div style={{ fontWeight: 600 }}>DeepSeek API Key</div>
        <div style={{ color: "#8a93a8", fontSize: 12, marginTop: 2, lineHeight: 1.55 }}>
          去{" "}
          <a
            href="https://platform.deepseek.com/api_keys"
            target="_blank"
            rel="noreferrer"
            style={{ color: "#9be3c6" }}
          >
            platform.deepseek.com
          </a>{" "}
          创建，贴在这里。DJ 旁白、AI 选曲都走这把。API key 本机 0600 存储，不上传。
        </div>
      </div>

      {/* 录入 / 展示区 —— flexWrap 让窄窗里按钮自动换行，不会撑爆容器 */}
      <div style={{ display: "flex", gap: 8, alignItems: "center", flexWrap: "wrap" }}>
        {editing ? (
          <>
            <input
              type="password"
              value={draft}
              onChange={(e) => setDraft(e.target.value)}
              placeholder="sk-..."
              autoFocus
              style={inputStyle}
            />
            <button onClick={save} disabled={saving} style={primaryBtn}>
              {saving ? "保存中…" : "保存"}
            </button>
            <button
              onClick={() => {
                setEditing(false);
                setDraft("");
                setErr(null);
              }}
              disabled={saving}
              style={ghostBtn}
            >
              取消
            </button>
          </>
        ) : (
          <>
            <div style={{ ...inputStyle, display: "flex", alignItems: "center", color: cfg?.hasKey ? "#e9efff" : "#6c7489" }}>
              {cfg?.hasKey ? cfg.keyPreview : "（还没填）"}
            </div>
            <button onClick={() => setEditing(true)} style={primaryBtn}>
              {cfg?.hasKey ? "改 key" : "填 key"}
            </button>
            {cfg?.hasKey && (
              <button onClick={test} disabled={testing} style={ghostBtn}>
                {testing ? "测试中…" : "测试"}
              </button>
            )}
            {cfg?.hasKey && (
              <button onClick={clear} style={dangerBtn}>
                清除
              </button>
            )}
          </>
        )}
      </div>

      {/* 模型 / base url meta */}
      {cfg && (
        <div style={{ color: "#6c7489", fontSize: 11, fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace" }}>
          model: {cfg.model} · {cfg.baseUrl}
        </div>
      )}

      {/* ping 回复 */}
      {reply && (
        <div
          style={{
            marginTop: 4,
            padding: "10px 14px",
            borderRadius: 10,
            background: "rgba(155,227,198,0.08)",
            border: "1px solid rgba(155,227,198,0.3)",
            color: "#c9f0dc",
            fontSize: 13,
            lineHeight: 1.5,
          }}
        >
          <span style={{ color: "#9be3c6", fontWeight: 700 }}>DeepSeek 说：</span>{" "}
          {reply}
        </div>
      )}

      {err && (
        <div
          style={{
            marginTop: 4,
            padding: "8px 12px",
            borderRadius: 10,
            background: "rgba(255,180,180,0.08)",
            border: "1px solid rgba(255,180,180,0.25)",
            color: "#ffb4b4",
            fontSize: 12,
            lineHeight: 1.45,
            whiteSpace: "pre-wrap",
            wordBreak: "break-word",
          }}
        >
          {err}
        </div>
      )}
    </div>
  );
}

const primaryBtn: React.CSSProperties = {
  padding: "10px 16px",
  borderRadius: 10,
  border: "1px solid rgba(155,227,198,0.5)",
  background: "rgba(155,227,198,0.14)",
  color: "#9be3c6",
  fontSize: 13,
  fontWeight: 600,
  cursor: "pointer",
};

const ghostBtn: React.CSSProperties = {
  padding: "10px 14px",
  borderRadius: 10,
  border: "1px solid rgba(233,239,255,0.15)",
  background: "transparent",
  color: "#e9efff",
  fontSize: 13,
  cursor: "pointer",
};

const dangerBtn: React.CSSProperties = {
  padding: "10px 14px",
  borderRadius: 10,
  border: "1px solid rgba(255,180,180,0.3)",
  background: "transparent",
  color: "#ffb4b4",
  fontSize: 13,
  cursor: "pointer",
};

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="glass" style={{ padding: 18, marginBottom: 16 }}>
      <div style={{
        color: "#8a93a8",
        fontSize: 11,
        letterSpacing: 2,
        textTransform: "uppercase",
        marginBottom: 12,
      }}>
        {title}
      </div>
      <div style={{ display: "flex", flexDirection: "column", gap: 12 }}>{children}</div>
    </div>
  );
}

function NeteaseRow({ me, err }: { me: UserProfile | null | "loading"; err: string | null }) {
  const loading = me === "loading";
  const connected = !!me && me !== "loading";
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 14, padding: "4px 4px" }}>
      <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between" }}>
        <div>
          <div style={{ fontWeight: 600 }}>网易云音乐</div>
          <div style={{ color: "#8a93a8", fontSize: 12, marginTop: 2 }}>
            {loading
              ? "正在检查登录状态…"
              : connected
              ? `已登录 · ${me.nickname}`
              : "用手机 App 扫码登录，直接把你 14 年的歌单接进来。"}
          </div>
          {err && (
            <div style={{ color: "#ffb4b4", fontSize: 12, marginTop: 4 }}>{err}</div>
          )}
        </div>
        <Link
          href="/login"
          style={{
            padding: "8px 16px",
            borderRadius: 999,
            border: connected
              ? "1px solid rgba(155,227,198,0.5)"
              : "1px solid rgba(233,239,255,0.2)",
            background: connected ? "rgba(155,227,198,0.14)" : "transparent",
            color: connected ? "#9be3c6" : "#e9efff",
            textDecoration: "none",
            fontSize: 13,
          }}
        >
          {connected ? "换账号" : "扫码登录"}
        </Link>
      </div>

      {/* 登录之后，直接给一个大 CTA：进入我的歌单 */}
      {connected && (
        <Link
          href="/distill"
          style={{
            display: "flex",
            alignItems: "center",
            justifyContent: "space-between",
            padding: "14px 18px",
            borderRadius: 14,
            border: "1px solid rgba(155,227,198,0.4)",
            background: "rgba(155,227,198,0.08)",
            textDecoration: "none",
            color: "#e9efff",
          }}
        >
          <div>
            <div style={{ color: "#9be3c6", fontWeight: 700, fontSize: 15 }}>
              进入我的歌单 →
            </div>
            <div style={{ color: "#8a93a8", fontSize: 12, marginTop: 2 }}>
              挑一张歌单，点一首歌，Claudio 会在主窗口原生播放。
            </div>
          </div>
          <div style={{ color: "#9be3c6", fontSize: 22 }}>♪</div>
        </Link>
      )}
    </div>
  );
}

function Toggle({
  label,
  desc,
  initial,
  value,
  onChange,
}: {
  label: string;
  desc: string;
  initial?: boolean;
  /** 给了 value/onChange 就走受控模式（持久化由调用方负责） */
  value?: boolean;
  onChange?: (next: boolean) => void;
}) {
  const [internal, setInternal] = useState(initial ?? false);
  const controlled = value !== undefined;
  const on = controlled ? value : internal;
  const flip = () => {
    const next = !on;
    if (controlled) onChange?.(next);
    else setInternal(next);
  };
  return (
    <div
      onClick={flip}
      style={{
        display: "flex",
        alignItems: "center",
        justifyContent: "space-between",
        padding: "10px 4px",
        cursor: "pointer",
      }}
    >
      <div>
        <div style={{ fontWeight: 600 }}>{label}</div>
        <div style={{ color: "#8a93a8", fontSize: 12, marginTop: 2 }}>{desc}</div>
      </div>
      <div
        style={{
          width: 42,
          height: 24,
          borderRadius: 999,
          background: on ? "#9be3c6" : "rgba(233,239,255,0.15)",
          position: "relative",
          transition: "background 160ms ease",
        }}
      >
        <div
          style={{
            position: "absolute",
            top: 2,
            left: on ? 20 : 2,
            width: 20,
            height: 20,
            borderRadius: 999,
            background: "#0b0d12",
            transition: "left 160ms ease",
          }}
        />
      </div>
    </div>
  );
}
