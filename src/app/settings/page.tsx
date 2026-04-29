"use client";

import {
  ai,
  audio,
  netease,
  type AiConfigPublic,
  type AudioCacheStats,
  type ModelOption,
  type ProviderId,
  type ProviderView,
  type UserProfile,
} from "@/lib/tauri";
import { useAppSettings } from "@/lib/app-settings";
import {
  useAnalysisProgress,
  startBackgroundAnalysis,
  resetAnalysisState,
} from "@/lib/analysis-progress";
import { loadLibrary } from "@/lib/library";
import { getUserFacts, setUserFacts } from "@/lib/pet-memory";
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
        maxWidth: 760,
        margin: "0 auto",
        width: "100%",
      }}
    >
      <div style={brand}>SETTINGS</div>
      <div style={subtitle}>
        Claudio 把你的账号状态、播放规则、AI 口吻都攒在本地。
      </div>

      <Section title="音乐来源">
        <NeteaseRow me={me} err={err} />
      </Section>

      <Section title="音频分析（接歌丝滑度）">
        <AnalysisRow />
      </Section>

      <Section title="音频缓存">
        <AudioCacheRow />
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

      <Section title="关于你">
        <UserFactsRow />
      </Section>

      <Section title="AI 接入">
        <AiProvidersRow />
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

// ---------- 多 provider AI 接入 ----------
//
// 三家 provider 共用一份调用协议（OpenAI 兼容），但前端要让用户：
//   1) 切 provider（顶部 tab）
//   2) 填 / 改 / 清 当前 provider 的 key（key 永远不回传完整值）
//   3) 选模型（dropdown，从后端 ai.listModels(provider) 拿，按官方梯队从高到低）
//   4) 测试当前 provider+模型 是否能跑通（ai.ping）
//
// 切 provider 不会动别家的 key —— 用户能在 DeepSeek / OpenAI / 小米 MiMo 之间反复切。
// 选完模型后端立刻持久化，下次启动也记得。

const PROVIDER_LINKS: Record<ProviderId, { label: string; url: string }> = {
  deepseek: {
    label: "platform.deepseek.com",
    url: "https://platform.deepseek.com/api_keys",
  },
  openai: {
    label: "platform.openai.com",
    url: "https://platform.openai.com/api-keys",
  },
  "xiaomi-mimo": {
    label: "platform.xiaomimimo.com",
    url: "https://platform.xiaomimimo.com/",
  },
};

function AiProvidersRow() {
  const [cfg, setCfg] = useState<AiConfigPublic | null>(null);
  const [models, setModels] = useState<Record<ProviderId, ModelOption[]>>(
    {} as Record<ProviderId, ModelOption[]>,
  );
  const [editingDraft, setEditingDraft] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);
  const [reply, setReply] = useState<string | null>(null);
  const [err, setErr] = useState<string | null>(null);

  const refresh = async () => {
    try {
      const c = await ai.getConfig();
      setCfg(c);
      // 第一次加载时把三家的模型列表都拉一下，切 provider 时不需再等网络
      if (Object.keys(models).length === 0) {
        const entries = await Promise.all(
          c.providers.map(async (p) => {
            try {
              return [p.id, await ai.listModels(p.id)] as const;
            } catch {
              return [p.id, []] as const;
            }
          }),
        );
        setModels(Object.fromEntries(entries) as Record<ProviderId, ModelOption[]>);
      }
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e));
    }
  };

  useEffect(() => {
    void refresh();
    // refresh closure 里 models 只用一次（首次空才填），不需要每次重创建
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  if (!cfg) {
    return (
      <div style={{ color: "#8a93a8", fontSize: 13, padding: "8px 4px" }}>
        正在加载 AI 配置…
      </div>
    );
  }

  const active = cfg.providers.find((p) => p.id === cfg.activeProvider) ?? cfg.providers[0];
  const activeModels = models[active.id] ?? [];

  const switchProvider = async (id: ProviderId) => {
    if (id === cfg.activeProvider) return;
    setErr(null);
    setReply(null);
    setEditingDraft(null);
    try {
      await ai.setProvider(id);
      await refresh();
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e));
    }
  };

  const saveKey = async () => {
    if (editingDraft == null) return;
    setSaving(true);
    setErr(null);
    setReply(null);
    try {
      await ai.setApiKey(active.id, editingDraft);
      setEditingDraft(null);
      await refresh();
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e));
    } finally {
      setSaving(false);
    }
  };

  const clearKey = async () => {
    if (!confirm(`确定要清除 ${active.label} 的 API key 吗？`)) return;
    setErr(null);
    setReply(null);
    try {
      await ai.clearApiKey(active.id);
      await refresh();
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e));
    }
  };

  const changeModel = async (model: string) => {
    setErr(null);
    setReply(null);
    try {
      await ai.setModel(active.id, model);
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

  const link = PROVIDER_LINKS[active.id];
  const editing = editingDraft != null;

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 14, padding: "2px 2px" }}>
      {/* Provider 切换 —— pill tab 形式 */}
      <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
        {cfg.providers.map((p) => {
          const isActive = p.id === active.id;
          return (
            <button
              key={p.id}
              onClick={() => void switchProvider(p.id)}
              style={{
                padding: "7px 14px",
                borderRadius: 999,
                border: isActive
                  ? "1px solid rgba(155,227,198,0.6)"
                  : "1px solid rgba(233,239,255,0.14)",
                background: isActive ? "rgba(155,227,198,0.16)" : "transparent",
                color: isActive ? "#9be3c6" : "rgba(233,239,255,0.7)",
                fontSize: 12,
                fontWeight: isActive ? 600 : 500,
                cursor: "pointer",
                display: "inline-flex",
                alignItems: "center",
                gap: 6,
                transition: "background 160ms ease, color 160ms ease, border-color 160ms ease",
              }}
            >
              {p.label}
              {p.hasKey && (
                <span
                  aria-hidden
                  style={{
                    width: 6,
                    height: 6,
                    borderRadius: 999,
                    background: "#9be3c6",
                  }}
                />
              )}
            </button>
          );
        })}
      </div>

      {/* 当前 provider 介绍 */}
      <div style={{ color: "#8a93a8", fontSize: 12, lineHeight: 1.55 }}>
        去{" "}
        <a
          href={link.url}
          target="_blank"
          rel="noreferrer"
          style={{ color: "#9be3c6" }}
        >
          {link.label}
        </a>{" "}
        创建 API key，贴在下面。DJ 旁白、AI 选曲会走当前选中的 provider。所有 key 本机 0600 存储，不上传。
      </div>

      {/* API key 录入区 */}
      <KeyRow
        provider={active}
        editing={editing}
        draft={editingDraft ?? ""}
        saving={saving}
        testing={testing}
        onStartEdit={() => setEditingDraft("")}
        onChangeDraft={setEditingDraft}
        onSave={saveKey}
        onCancel={() => {
          setEditingDraft(null);
          setErr(null);
        }}
        onClear={clearKey}
        onTest={test}
      />

      {/* 模型选择 */}
      <ModelPicker
        active={active}
        options={activeModels}
        onChange={changeModel}
      />

      {/* base url 元信息（小字给好奇用户） */}
      <div style={{ color: "#6c7489", fontSize: 11, fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace" }}>
        endpoint: {active.baseUrl}
      </div>

      {/* ping 回复 */}
      {reply && (
        <div
          style={{
            padding: "10px 14px",
            borderRadius: 10,
            background: "rgba(155,227,198,0.08)",
            border: "1px solid rgba(155,227,198,0.3)",
            color: "#c9f0dc",
            fontSize: 13,
            lineHeight: 1.5,
          }}
        >
          <span style={{ color: "#9be3c6", fontWeight: 700 }}>{active.label} 说：</span>{" "}
          {reply}
        </div>
      )}

      {err && (
        <div
          style={{
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

const keyInputStyle: React.CSSProperties = {
  flex: 1,
  minWidth: 180,
  padding: "10px 14px",
  borderRadius: 10,
  border: "1px solid rgba(233,239,255,0.15)",
  background: "rgba(14,18,28,0.6)",
  color: "#e9efff",
  fontSize: 13,
  fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace",
  outline: "none",
};

function KeyRow({
  provider,
  editing,
  draft,
  saving,
  testing,
  onStartEdit,
  onChangeDraft,
  onSave,
  onCancel,
  onClear,
  onTest,
}: {
  provider: ProviderView;
  editing: boolean;
  draft: string;
  saving: boolean;
  testing: boolean;
  onStartEdit: () => void;
  onChangeDraft: (v: string) => void;
  onSave: () => void;
  onCancel: () => void;
  onClear: () => void;
  onTest: () => void;
}) {
  return (
    <div style={{ display: "flex", gap: 8, alignItems: "center", flexWrap: "wrap" }}>
      {editing ? (
        <>
          <input
            type="password"
            value={draft}
            onChange={(e) => onChangeDraft(e.target.value)}
            placeholder="sk-..."
            autoFocus
            style={keyInputStyle}
          />
          <button onClick={onSave} disabled={saving} style={primaryBtn}>
            {saving ? "保存中…" : "保存"}
          </button>
          <button onClick={onCancel} disabled={saving} style={ghostBtn}>
            取消
          </button>
        </>
      ) : (
        <>
          <div
            style={{
              ...keyInputStyle,
              display: "flex",
              alignItems: "center",
              color: provider.hasKey ? "#e9efff" : "#6c7489",
            }}
          >
            {provider.hasKey ? provider.keyPreview : "（还没填）"}
          </div>
          <button onClick={onStartEdit} style={primaryBtn}>
            {provider.hasKey ? "改 key" : "填 key"}
          </button>
          {provider.hasKey && (
            <button onClick={onTest} disabled={testing} style={ghostBtn}>
              {testing ? "测试中…" : "测试"}
            </button>
          )}
          {provider.hasKey && (
            <button onClick={onClear} style={dangerBtn}>
              清除
            </button>
          )}
        </>
      )}
    </div>
  );
}

function ModelPicker({
  active,
  options,
  onChange,
}: {
  active: ProviderView;
  options: ModelOption[];
  onChange: (model: string) => void;
}) {
  // 后端选中的 model 可能是用户改过的、不在 known_models 里。
  // 这种情况我们仍然要让用户看见当前值，所以构造一个合并列表（已选模型在前）。
  const selectedInList = options.some((o) => o.id === active.model);
  const merged: ModelOption[] = selectedInList
    ? options
    : [{ id: active.model, label: `${active.model}（自定义）` }, ...options];

  return (
    <div style={{ display: "flex", alignItems: "center", gap: 10, flexWrap: "wrap" }}>
      <div style={{ color: "#8a93a8", fontSize: 12, minWidth: 36 }}>模型</div>
      <select
        value={active.model}
        onChange={(e) => onChange(e.target.value)}
        style={{
          flex: 1,
          minWidth: 200,
          padding: "9px 12px",
          borderRadius: 10,
          border: "1px solid rgba(233,239,255,0.15)",
          background: "rgba(14,18,28,0.6)",
          color: "#e9efff",
          fontSize: 13,
          outline: "none",
          fontFamily: "inherit",
          appearance: "none",
          backgroundImage:
            "linear-gradient(45deg, transparent 50%, rgba(233,239,255,0.5) 50%), linear-gradient(135deg, rgba(233,239,255,0.5) 50%, transparent 50%)",
          backgroundPosition:
            "calc(100% - 18px) 50%, calc(100% - 12px) 50%",
          backgroundSize: "6px 6px, 6px 6px",
          backgroundRepeat: "no-repeat",
          paddingRight: 32,
          cursor: "pointer",
        }}
      >
        {merged.map((o) => (
          <option key={o.id} value={o.id} style={{ background: "#0a0d14", color: "#e9efff" }}>
            {o.label} · {o.id}
          </option>
        ))}
      </select>
    </div>
  );
}

const primaryBtn: React.CSSProperties = {
  padding: "9px 18px",
  borderRadius: 999,
  border: "1px solid rgba(155,227,198,0.5)",
  background: "rgba(155,227,198,0.14)",
  color: "#9be3c6",
  fontSize: 13,
  fontWeight: 600,
  cursor: "pointer",
  whiteSpace: "nowrap",
};

const ghostBtn: React.CSSProperties = {
  padding: "9px 16px",
  borderRadius: 999,
  border: "1px solid rgba(233,239,255,0.18)",
  background: "transparent",
  color: "#e9efff",
  fontSize: 13,
  cursor: "pointer",
  whiteSpace: "nowrap",
};

const dangerBtn: React.CSSProperties = {
  padding: "9px 16px",
  borderRadius: 999,
  border: "1px solid rgba(255,180,180,0.3)",
  background: "transparent",
  color: "#ffb4b4",
  fontSize: 13,
  cursor: "pointer",
  whiteSpace: "nowrap",
};

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div style={sectionBlock}>
      <div style={sectionTitle}>{title}</div>
      <div style={{ display: "flex", flexDirection: "column", gap: 12 }}>{children}</div>
    </div>
  );
}

const brand: React.CSSProperties = {
  textAlign: "center",
  fontSize: 11,
  letterSpacing: 4,
  color: "rgba(233,239,255,0.42)",
  fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace",
  marginTop: "clamp(12px, 3vh, 24px)",
  marginBottom: 14,
  textTransform: "uppercase",
};

const subtitle: React.CSSProperties = {
  textAlign: "center",
  color: "#8a93a8",
  marginBottom: "clamp(22px, 4vh, 34px)",
  fontSize: 13,
  lineHeight: 1.55,
};

const sectionBlock: React.CSSProperties = {
  marginBottom: 28,
};

const sectionTitle: React.CSSProperties = {
  color: "rgba(233,239,255,0.42)",
  fontSize: 11,
  letterSpacing: 2.2,
  textTransform: "uppercase",
  marginBottom: 14,
  fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace",
};

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

function AnalysisRow() {
  const progress = useAnalysisProgress();
  const [libSize, setLibSize] = useState<number | null>(null);
  const [starting, setStarting] = useState(false);
  const [clearing, setClearing] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    void (async () => {
      try {
        const lib = await loadLibrary();
        if (alive) setLibSize(lib.length);
      } catch {
        if (alive) setLibSize(0);
      }
    })();
    return () => {
      alive = false;
    };
  }, []);

  const remaining = Math.max(0, progress.total - progress.done);
  const pct = progress.total > 0 ? Math.min(100, (progress.done / progress.total) * 100) : 0;
  const eta = progress.running && remaining > 0 ? estimateEtaMin(remaining) : null;

  const startResume = async () => {
    setStarting(true);
    setErr(null);
    try {
      const lib = await loadLibrary();
      if (lib.length === 0) {
        setErr("还没蒸馏歌单库，先去音乐来源里进我的歌单。");
        return;
      }
      void startBackgroundAnalysis(lib);
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e));
    } finally {
      setStarting(false);
    }
  };

  // 清空：洗掉 Symphonia features + JS analysis:v3:* 缓存。
  // 不动 audio_cache（字节文件），让"清缓存"和"清分析"两个动作语义分开。
  const clearHistory = async () => {
    if (
      !confirm(
        "清空所有分析历史？\n\n" +
          "包含：\n" +
          "· Symphonia 算的 BPM / 响度 / 动态范围 / 音色 / 静音边界\n" +
          "· JS 算的 BPM / 鼓入点 / 人声段 / 能量曲线\n\n" +
          "音频字节缓存不会动，下次分析不用再重新拉。",
      )
    )
      return;
    setClearing(true);
    setErr(null);
    try {
      await audio.clearFeatures();
      // 同步把进度条 store 归零，否则界面还显示"已分析 N / M"
      resetAnalysisState();
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e));
    } finally {
      setClearing(false);
    }
  };

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 12, padding: "2px 2px" }}>
      <div>
        <div style={{ fontWeight: 600 }}>整库 BPM / 能量 / 声学特征分析</div>
        <div style={{ color: "#8a93a8", fontSize: 12, marginTop: 2, lineHeight: 1.55 }}>
          双引擎：JS 端算结构（鼓入点/人声段/outro）给接歌用；Rust 端 Symphonia
          算声学（BPM/响度/动态范围/音色亮度/头尾静默）给 AI 选曲 + level match 用。
          关 app 会停，重开按"继续分析"接着跑（已分析的会跳过）。
        </div>
      </div>

      {progress.running ? (
        <div>
          <div style={{
            color: "#9be3c6",
            fontSize: 13,
            marginBottom: 8,
            fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace",
          }}>
            {progress.done} / {progress.total}
            {progress.skipped > 0 && (
              <span style={{ color: "#6c7489" }}> · 命中缓存 {progress.skipped}</span>
            )}
            {progress.failed > 0 && (
              <span style={{ color: "#ffb4b4" }}> · 失败 {progress.failed}</span>
            )}
            {eta && <span style={{ color: "#6c7489" }}> · 约 {eta}</span>}
          </div>
          <div style={{
            height: 6,
            borderRadius: 999,
            background: "rgba(233,239,255,0.08)",
            overflow: "hidden",
          }}>
            <div style={{
              height: "100%",
              width: `${pct}%`,
              background: "linear-gradient(90deg, #9be3c6, #c9f0dc)",
              transition: "width 240ms ease",
            }} />
          </div>
        </div>
      ) : progress.lastFinishedAt ? (
        <div style={{ display: "flex", alignItems: "center", gap: 12, flexWrap: "wrap" }}>
          <div style={{ color: "#9be3c6", fontSize: 13 }}>
            ✓ 已分析 {progress.done} / {progress.total}
          </div>
          <button onClick={startResume} disabled={starting || clearing} style={ghostBtn}>
            {starting ? "启动中…" : "重新跑"}
          </button>
          <button onClick={clearHistory} disabled={starting || clearing} style={dangerBtn}>
            {clearing ? "清空中…" : "清空分析历史"}
          </button>
        </div>
      ) : (
        <div style={{ display: "flex", alignItems: "center", gap: 12, flexWrap: "wrap" }}>
          <div style={{ color: "#8a93a8", fontSize: 13 }}>
            {libSize == null
              ? "正在加载库…"
              : libSize === 0
              ? "库是空的——先去蒸馏歌单"
              : `库里有 ${libSize} 首，还没开始分析`}
          </div>
          {libSize != null && libSize > 0 && (
            <>
              <button onClick={startResume} disabled={starting || clearing} style={primaryBtn}>
                {starting ? "启动中…" : "继续分析"}
              </button>
              <button onClick={clearHistory} disabled={starting || clearing} style={dangerBtn}>
                {clearing ? "清空中…" : "清空分析历史"}
              </button>
            </>
          )}
        </div>
      )}

      {err && (
        <div style={{
          padding: "8px 12px",
          borderRadius: 10,
          background: "rgba(255,180,180,0.08)",
          border: "1px solid rgba(255,180,180,0.25)",
          color: "#ffb4b4",
          fontSize: 12,
          lineHeight: 1.45,
        }}>
          {err}
        </div>
      )}
    </div>
  );
}

// ---------- 音频磁盘缓存 ----------
//
// Rust 端 `claudio-audio://` URI scheme 接管所有音频请求：命中本地直接读，
// miss 才拉网络 + 落盘 + LRU 淘汰。这里给用户三件事：看用量、调上限、清空。

function AudioCacheRow() {
  const [stats, setStats] = useState<AudioCacheStats | null>(null);
  const [maxMbDraft, setMaxMbDraft] = useState<string>("");
  const [editing, setEditing] = useState(false);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const refresh = async () => {
    try {
      const s = await audio.cacheStats();
      setStats(s);
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e));
    }
  };

  useEffect(() => {
    void refresh();
  }, []);

  const startEdit = () => {
    if (!stats) return;
    setMaxMbDraft(String(Math.round(stats.maxBytes / 1024 / 1024)));
    setEditing(true);
    setErr(null);
  };

  const saveMax = async () => {
    const mb = parseInt(maxMbDraft, 10);
    if (!Number.isFinite(mb) || mb < 64) {
      setErr("上限至少 64 MB");
      return;
    }
    setBusy(true);
    setErr(null);
    try {
      await audio.setCacheMaxMb(mb);
      setEditing(false);
      await refresh();
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  const clearAll = async () => {
    if (!confirm("确定要清空音频缓存吗？后续切歌会重新拉。")) return;
    setBusy(true);
    setErr(null);
    try {
      await audio.clearCache();
      await refresh();
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  const fmtBytes = (b: number) => {
    if (b < 1024) return `${b} B`;
    if (b < 1024 * 1024) return `${(b / 1024).toFixed(1)} KB`;
    if (b < 1024 * 1024 * 1024) return `${(b / 1024 / 1024).toFixed(1)} MB`;
    return `${(b / 1024 / 1024 / 1024).toFixed(2)} GB`;
  };

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 12, padding: "2px 2px" }}>
      <div>
        <div style={{ fontWeight: 600 }}>歌曲原始字节缓存</div>
        <div style={{ color: "#8a93a8", fontSize: 12, marginTop: 2, lineHeight: 1.55 }}>
          切歌时优先读本地缓存的 mp3/flac，命中就不再走网络。超过上限按 last-used
          淘汰。缓存放在系统的 cache 目录，删掉不影响歌单 / 画像数据。
        </div>
      </div>

      {stats && (
        <>
          <div style={{ display: "flex", alignItems: "center", gap: 12, flexWrap: "wrap" }}>
            <CacheUsageBar
              used={stats.totalBytes}
              max={stats.maxBytes}
            />
            <div
              style={{
                color: "#9be3c6",
                fontSize: 12,
                fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace",
                fontVariantNumeric: "tabular-nums",
              }}
            >
              {fmtBytes(stats.totalBytes)} / {fmtBytes(stats.maxBytes)}
            </div>
            <div
              style={{
                color: "#6c7489",
                fontSize: 11,
                fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace",
              }}
            >
              · {stats.count} 首
            </div>
          </div>

          {/* 上限调整 + 清空 */}
          <div style={{ display: "flex", gap: 8, alignItems: "center", flexWrap: "wrap" }}>
            {editing ? (
              <>
                <input
                  type="number"
                  value={maxMbDraft}
                  onChange={(e) => setMaxMbDraft(e.target.value)}
                  min={64}
                  step={64}
                  autoFocus
                  style={{
                    width: 110,
                    padding: "9px 12px",
                    borderRadius: 10,
                    border: "1px solid rgba(233,239,255,0.15)",
                    background: "rgba(14,18,28,0.6)",
                    color: "#e9efff",
                    fontSize: 13,
                    fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace",
                    outline: "none",
                  }}
                />
                <span style={{ color: "#8a93a8", fontSize: 12 }}>MB</span>
                <button onClick={saveMax} disabled={busy} style={primaryBtn}>
                  {busy ? "保存中…" : "保存"}
                </button>
                <button onClick={() => { setEditing(false); setErr(null); }} disabled={busy} style={ghostBtn}>
                  取消
                </button>
              </>
            ) : (
              <>
                <button onClick={startEdit} style={primaryBtn}>
                  调上限
                </button>
                <button
                  onClick={clearAll}
                  disabled={busy || stats.totalBytes === 0}
                  style={{
                    ...dangerBtn,
                    opacity: busy || stats.totalBytes === 0 ? 0.4 : 1,
                  }}
                >
                  清空缓存
                </button>
                <button onClick={refresh} disabled={busy} style={ghostBtn}>
                  刷新
                </button>
              </>
            )}
          </div>
        </>
      )}

      {!stats && !err && (
        <div style={{ color: "#8a93a8", fontSize: 12 }}>正在读取缓存状态…</div>
      )}

      {err && (
        <div
          style={{
            padding: "8px 12px",
            borderRadius: 10,
            background: "rgba(255,180,180,0.08)",
            border: "1px solid rgba(255,180,180,0.25)",
            color: "#ffb4b4",
            fontSize: 12,
            lineHeight: 1.45,
          }}
        >
          {err}
        </div>
      )}
    </div>
  );
}

function CacheUsageBar({ used, max }: { used: number; max: number }) {
  const pct = max > 0 ? Math.min(100, (used / max) * 100) : 0;
  // 接近上限给个警告色，不然走主色（薄荷绿）
  const color = pct > 90 ? "#ffd28a" : "#9be3c6";
  return (
    <div
      style={{
        flex: 1,
        minWidth: 140,
        height: 6,
        borderRadius: 999,
        background: "rgba(233,239,255,0.08)",
        overflow: "hidden",
      }}
    >
      <div
        style={{
          width: `${pct}%`,
          height: "100%",
          background: color,
          borderRadius: 999,
          transition: "width 240ms ease, background 240ms ease",
        }}
      />
    </div>
  );
}

function estimateEtaMin(remaining: number): string {
  // 经验值：concurrency=3，每首 ~6-10s（fetch FLAC + decode + JS biquad/RMS）
  const seconds = Math.round((remaining * 8) / 3);
  if (seconds < 60) return `${seconds}s`;
  const min = Math.round(seconds / 60);
  if (min < 60) return `${min}min`;
  return `${(min / 60).toFixed(1)}h`;
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

// ---------- 用户自述事实 ----------
//
// 用户在这里写"工作时间 / 作息 / 习惯 / 爱好"等关于自己的情况, Claudio 会把这段话
// 注入到 AI 三个调用点的 user prompt 里(memory digest 的一部分),让它"了解你"。
//
// 这跟从行为日志里"推断"偏好不一样—— TA 自己写的话是 ground truth, 不会被 skip 这种
// 模糊信号污染。改一次保存一次, 长度封顶 400 字。

function UserFactsRow() {
  const [text, setText] = useState("");
  const [savedText, setSavedText] = useState("");
  const [loaded, setLoaded] = useState(false);
  const [saving, setSaving] = useState(false);
  const [savedAt, setSavedAt] = useState<number | null>(null);

  useEffect(() => {
    let alive = true;
    getUserFacts()
      .then((s) => {
        if (!alive) return;
        setText(s);
        setSavedText(s);
        setLoaded(true);
      })
      .catch(() => {
        if (alive) setLoaded(true);
      });
    return () => {
      alive = false;
    };
  }, []);

  const dirty = text.trim() !== savedText.trim();

  const save = async () => {
    setSaving(true);
    try {
      await setUserFacts(text);
      setSavedText(text);
      setSavedAt(Date.now());
    } finally {
      setSaving(false);
    }
  };

  const placeholder =
    "随便写两行,告诉 Claudio 关于你的情况。例:\n" +
    "工作日 9:00-18:30,双休,法定节假日休息。\n" +
    "通勤 30 分钟。最近常熬夜。喜欢 city pop 和后摇。";

  return (
    <div style={{ padding: "8px 4px 4px" }}>
      <div style={{ color: "#8a93a8", fontSize: 12, marginBottom: 8, lineHeight: 1.6 }}>
        TA 自己写的事实比 AI 从行为里推断的更准。任何工作时间 / 作息 / 习惯 / 喜好都可以塞进来,Claudio 会顺着这些回应你。
      </div>
      <textarea
        value={text}
        onChange={(e) => setText(e.target.value.slice(0, 400))}
        placeholder={placeholder}
        disabled={!loaded}
        rows={5}
        style={{
          width: "100%",
          padding: "10px 12px",
          background: "rgba(255,255,255,0.04)",
          border: "1px solid rgba(255,255,255,0.08)",
          borderRadius: 8,
          color: "rgba(233,239,255,0.92)",
          fontSize: 13,
          lineHeight: 1.6,
          fontFamily: "inherit",
          resize: "vertical",
          minHeight: 96,
          boxSizing: "border-box",
        }}
      />
      <div
        style={{
          marginTop: 8,
          display: "flex",
          alignItems: "center",
          justifyContent: "space-between",
          fontSize: 12,
          color: "#8a93a8",
        }}
      >
        <span>{text.length} / 400</span>
        <div style={{ display: "flex", alignItems: "center", gap: 12 }}>
          {savedAt && !dirty && <span>已保存</span>}
          <button
            onClick={save}
            disabled={!dirty || saving}
            style={{
              padding: "7px 16px",
              borderRadius: 999,
              border: dirty
                ? "1px solid rgba(155,227,198,0.6)"
                : "1px solid rgba(233,239,255,0.12)",
              background: dirty ? "rgba(155,227,198,0.16)" : "transparent",
              color: dirty ? "#9be3c6" : "rgba(233,239,255,0.4)",
              fontSize: 12,
              fontWeight: 600,
              cursor: dirty && !saving ? "pointer" : "default",
              transition: "background 160ms ease, color 160ms ease, border-color 160ms ease",
            }}
          >
            {saving ? "保存中…" : "保存"}
          </button>
        </div>
      </div>
    </div>
  );
}
