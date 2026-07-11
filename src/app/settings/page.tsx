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
import { BackButton } from "@/components/BackButton";
import { AppIcon } from "@/components/AppIcon";
import { usePlatformInfo } from "@/lib/use-platform";
import {
  useAnalysisProgress,
  startBackgroundAnalysis,
  resetAnalysisState,
} from "@/lib/analysis-progress";
import { loadLibrary } from "@/lib/library";
import { getUserFacts, setUserFacts } from "@/lib/pet-memory";
import Link from "next/link";
import { Children, useEffect, useState, type ReactNode } from "react";

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
    <>
      <SettingsTopBar />
      <div style={pageWrap}>
        <PageHeading title="SETTINGS" subtitle="账号、播放和 AI 都在本机。" />

        <Section title="音乐来源" label="SOURCE">
          <NeteaseRow me={me} err={err} />
        </Section>

        <Section title="整库声学分析" subtitle="接歌丝滑度" label="ANALYSIS">
          <AnalysisRow />
        </Section>

        <Section title="音频缓存" subtitle="原始字节" label="CACHE">
          <AudioCacheRow />
        </Section>

        <Section title="外观" label="LOOK">
          <Toggle
            label="隐藏点阵纹理"
            desc="去掉播放页全屏的点阵叠加，只保留封面模糊背景"
            value={appSettings.hideDotPattern}
            onChange={(v) => updateAppSettings({ hideDotPattern: v })}
          />
          <Toggle
            label="隐藏 AI 圆球"
            desc="不显示右下角圆球和绳子，只在封面上冒一句"
            value={appSettings.hideAiPetOrb}
            onChange={(v) => updateAppSettings({ hideAiPetOrb: v })}
          />
        </Section>

        <Section title="播放规则" label="RULES">
          <Toggle
            label="主动安排一段"
            desc="启动后按时间、天气和近期听感自动排一小段"
            value={appSettings.smartSessionPlanner}
            onChange={(v) => updateAppSettings({ smartSessionPlanner: v })}
          />
          <Toggle
            label="工作时段自动播放"
            desc="09:00 – 18:30，偏专注、不吵"
            value={appSettings.workdayAutoplay}
            onChange={(v) => updateAppSettings({ workdayAutoplay: v })}
          />
          <Toggle
            label="午休换放松歌单"
            desc="12:00 – 13:30"
            value={appSettings.lunchRelaxMode}
            onChange={(v) => updateAppSettings({ lunchRelaxMode: v })}
          />
          <Toggle
            label="深夜降低推荐节奏"
            desc="22:30 之后"
            value={appSettings.lateNightCalmMode}
            onChange={(v) => updateAppSettings({ lateNightCalmMode: v })}
          />
          <PromptedRadioRule
            value={appSettings.promptedRadioRule}
            onChange={(value) => updateAppSettings({ promptedRadioRule: value })}
          />
        </Section>

        <Section title="关于你" subtitle="自述事实" label="ABOUT YOU">
          <UserFactsRow />
        </Section>

        <Section title="AI 接入" label="AI">
          <AiProvidersRow />
        </Section>

        <Section title="AI 解说" label="NARRATION">
          <Toggle
            label="封面短提示"
            desc="只出现一句，自动消失"
            value={appSettings.aiNarration}
            onChange={(v) => updateAppSettings({ aiNarration: v })}
          />
        </Section>

        <div style={footerNote}>
          登录 cookie & API key 仅保存在本机 · 不上传
        </div>
      </div>
    </>
  );
}

function SettingsTopBar() {
  const { platform, runtime } = usePlatformInfo();
  const isMac = runtime === "tauri" && platform === "mac";
  const isWin = runtime === "tauri" && platform === "windows";
  const isAndroid = platform === "android";
  const safeTop = isAndroid ? "max(env(safe-area-inset-top), 28px)" : "0px";
  const barHeight = isAndroid ? `calc(${safeTop} + 48px)` : "40px";
  const contentTop = isAndroid ? safeTop : "4px";
  const leftInset = isMac ? 112 : 18;
  const rightInset = isWin ? 158 : 24;

  return (
    <div
      data-tauri-drag-region
      style={{
        position: "fixed",
        top: 0,
        left: 0,
        right: 0,
        height: barHeight,
        display: "flex",
        alignItems: "center",
        paddingTop: contentTop,
        paddingLeft: leftInset,
        paddingRight: rightInset,
        boxSizing: "border-box",
        background: "transparent",
        zIndex: 40,
      }}
    >
      <div style={{ display: "flex", alignItems: "center", height: 30 }}>
        <BackButton href="/" />
      </div>
    </div>
  );
}

function PromptedRadioRule({
  value,
  onChange,
}: {
  value: string;
  onChange: (value: string) => void;
}) {
  return (
    <div style={{ paddingTop: 8 }}>
      <div style={{ fontWeight: 600, marginBottom: 6 }}>Prompted Radio</div>
      <textarea
        value={value}
        onChange={(e) => onChange(e.target.value.slice(0, 240))}
        placeholder="例：每天上午 90 分钟不吵，熟歌 70%，新歌 30%，不要太多同一歌手。"
        rows={3}
        style={smallTextarea}
      />
      <div style={{ color: "#8a93a8", fontSize: 12, marginTop: 6 }}>
        {value.length} / 240
      </div>
    </div>
  );
}

const smallTextarea: React.CSSProperties = {
  width: "100%",
  padding: "10px 12px",
  background: "rgba(10,13,20,0.72)",
  border: "1px solid rgba(233,239,255,0.08)",
  borderRadius: 0,
  color: "rgba(233,239,255,0.92)",
  fontSize: 13,
  lineHeight: 1.55,
  fontFamily: "inherit",
  resize: "vertical",
  minHeight: 74,
  boxSizing: "border-box",
};

// ---------- 多 provider AI 接入 ----------
//
// 多家 provider 共用一份调用协议（OpenAI 兼容），但前端要让用户：
//   1) 切 provider（顶部 tab）
//   2) 填 / 改 / 清 当前 provider 的 key（key 永远不回传完整值）
//   3) 选模型（dropdown，从后端 ai.listModels(provider) 拿，按官方梯队从高到低）
//   4) 自定义 provider 额外填中转站请求地址
//   5) 测试当前 provider+模型 是否能跑通（ai.ping）
//
// 切 provider 不会动别家的 key —— 用户能在 DeepSeek / OpenAI / 小米 MiMo / 自定义之间反复切。
// 选完模型后端立刻持久化，下次启动也记得。

const PROVIDER_LINKS: Record<ProviderId, { label: string; url?: string }> = {
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
  custom: {
    label: "OpenAI 兼容中转站",
  },
};

function AiProvidersRow() {
  const [cfg, setCfg] = useState<AiConfigPublic | null>(null);
  const [models, setModels] = useState<Record<ProviderId, ModelOption[]>>(
    {} as Record<ProviderId, ModelOption[]>,
  );
  const [editingDraft, setEditingDraft] = useState<string | null>(null);
  const [baseUrlDraft, setBaseUrlDraft] = useState("");
  const [savingBaseUrl, setSavingBaseUrl] = useState(false);
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);
  const [reply, setReply] = useState<string | null>(null);
  const [err, setErr] = useState<string | null>(null);

  const refresh = async () => {
    try {
      const c = await ai.getConfig();
      setCfg(c);
      const activeProvider =
        c.providers.find((p) => p.id === c.activeProvider) ?? c.providers[0];
      setBaseUrlDraft(activeProvider?.baseUrl ?? "");
      // 第一次加载时把全部 provider 的模型列表都拉一下，切 provider 时不需再等网络
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

  const saveBaseUrl = async () => {
    if (active.id !== "custom") return;
    setSavingBaseUrl(true);
    setErr(null);
    setReply(null);
    try {
      await ai.setBaseUrl(active.id, baseUrlDraft);
      await refresh();
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e));
    } finally {
      setSavingBaseUrl(false);
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
    <div style={{ display: "flex", flexDirection: "column", gap: 14 }}>
      <div style={controlRow}>
        <div style={controlLabel}>服务商</div>
        <select
          value={active.id}
          onChange={(e) => void switchProvider(e.target.value as ProviderId)}
          style={{ ...selectStyle, minWidth: 210 }}
        >
          {cfg.providers.map((p) => (
            <option key={p.id} value={p.id} style={{ background: "#0a0d14", color: "#e9efff" }}>
              {p.label}{p.hasKey ? " · key" : ""}
            </option>
          ))}
        </select>
      </div>

      {/* 当前 provider 介绍 */}
      <div style={{ color: "#8a93a8", fontSize: 12, lineHeight: 1.55 }}>
        {link.url ? (
          <>
            去{" "}
            <a
              href={link.url}
              target="_blank"
              rel="noreferrer"
              style={{ color: "#9be3c6" }}
            >
              {link.label}
            </a>{" "}
            创建 API key，贴在下面。
          </>
        ) : (
          <>填入中转站的 OpenAI 兼容请求地址和 API key。</>
        )}
        DJ 旁白、AI 选曲会走当前选中的 provider。所有 key 本机 0600 存储，不上传。
      </div>

      {active.id === "custom" && (
        <BaseUrlRow
          value={baseUrlDraft}
          endpoint={active.baseUrl}
          saving={savingBaseUrl}
          onChange={setBaseUrlDraft}
          onSave={saveBaseUrl}
        />
      )}

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
            borderRadius: 0,
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
            borderRadius: 0,
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
  borderRadius: 0,
  border: "1px solid rgba(233,239,255,0.10)",
  background: "rgba(10,13,20,0.72)",
  color: "#e9efff",
  fontSize: 13,
  fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace",
  outline: "none",
};

const controlRow: React.CSSProperties = {
  display: "flex",
  alignItems: "center",
  gap: 10,
  flexWrap: "wrap",
};

const controlLabel: React.CSSProperties = {
  color: "#8a93a8",
  fontSize: 12,
  minWidth: 48,
};

const selectStyle: React.CSSProperties = {
  flex: 1,
  minWidth: 200,
  padding: "9px 12px",
  borderRadius: 0,
  border: "1px solid rgba(233,239,255,0.10)",
  background: "rgba(10,13,20,0.72)",
  color: "#e9efff",
  fontSize: 13,
  outline: "none",
  fontFamily: "inherit",
  appearance: "none",
  backgroundImage:
    "linear-gradient(45deg, transparent 50%, rgba(233,239,255,0.55) 50%), linear-gradient(135deg, rgba(233,239,255,0.55) 50%, transparent 50%)",
  backgroundPosition: "calc(100% - 18px) 50%, calc(100% - 12px) 50%",
  backgroundSize: "6px 6px, 6px 6px",
  backgroundRepeat: "no-repeat",
  paddingRight: 32,
  cursor: "pointer",
};

function trimEndpoint(v: string) {
  return v.trim().replace(/\/+$/, "");
}

function BaseUrlRow({
  value,
  endpoint,
  saving,
  onChange,
  onSave,
}: {
  value: string;
  endpoint: string;
  saving: boolean;
  onChange: (v: string) => void;
  onSave: () => void;
}) {
  const changed = trimEndpoint(value) !== trimEndpoint(endpoint);
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 7 }}>
      <div style={controlRow}>
        <div style={controlLabel}>地址</div>
        <input
          type="url"
          value={value}
          onChange={(e) => onChange(e.target.value)}
          placeholder="https://your-relay.example.com/v1"
          spellCheck={false}
          style={{ ...keyInputStyle, minWidth: 280 }}
        />
        <button
          onClick={onSave}
          disabled={saving || !value.trim() || !changed}
          style={primaryBtn}
        >
          {saving ? "保存中…" : "保存地址"}
        </button>
      </div>
      <div style={{ color: "#6c7489", fontSize: 11, lineHeight: 1.45, paddingLeft: 58 }}>
        支持 OpenAI 兼容 Base URL；如果粘贴完整 /chat/completions，后端会自动规整。
      </div>
    </div>
  );
}

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
  onChange: (model: string) => void | Promise<void>;
}) {
  const [draft, setDraft] = useState(active.model);
  const [savingDraft, setSavingDraft] = useState(false);

  useEffect(() => {
    setDraft(active.model);
  }, [active.id, active.model]);

  // 后端选中的 model 可能是用户改过的、不在 known_models 里。
  // 这种情况我们仍然要让用户看见当前值，所以构造一个合并列表（已选模型在前）。
  const selectedInList = options.some((o) => o.id === active.model);
  const merged: ModelOption[] = selectedInList
    ? options
    : [{ id: active.model, label: `${active.model}（自定义）` }, ...options];
  const isCustom = active.id === "custom";
  const canSaveCustomModel =
    isCustom && draft.trim().length > 0 && draft.trim() !== active.model;

  const saveDraft = async () => {
    const model = draft.trim();
    if (!model || model === active.model) return;
    setSavingDraft(true);
    try {
      await onChange(model);
    } finally {
      setSavingDraft(false);
    }
  };

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
      <div style={controlRow}>
        <div style={controlLabel}>模型</div>
        <select
          value={active.model}
          onChange={(e) => void onChange(e.target.value)}
          style={selectStyle}
        >
          {merged.map((o) => (
            <option key={o.id} value={o.id} style={{ background: "#0a0d14", color: "#e9efff" }}>
              {o.label} · {o.id}
            </option>
          ))}
        </select>
      </div>

      {isCustom && (
        <div style={controlRow}>
          <div style={controlLabel}>ID</div>
          <input
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            placeholder="gpt-4o-mini"
            spellCheck={false}
            style={{ ...keyInputStyle, minWidth: 220 }}
          />
          <button
            onClick={() => void saveDraft()}
            disabled={savingDraft || !canSaveCustomModel}
            style={ghostBtn}
          >
            {savingDraft ? "保存中…" : "保存模型"}
          </button>
        </div>
      )}
    </div>
  );
}

const primaryBtn: React.CSSProperties = {
  padding: "8px 14px",
  borderRadius: 0,
  border: "1px solid rgba(233,239,255,0.92)",
  background: "rgba(233,239,255,0.94)",
  color: "#05060a",
  fontSize: 12,
  fontWeight: 600,
  letterSpacing: 0.8,
  cursor: "pointer",
  whiteSpace: "nowrap",
};

const ghostBtn: React.CSSProperties = {
  padding: "8px 14px",
  borderRadius: 0,
  border: "1px solid rgba(233,239,255,0.10)",
  background: "transparent",
  color: "#e9efff",
  fontSize: 12,
  fontWeight: 600,
  letterSpacing: 0.8,
  cursor: "pointer",
  whiteSpace: "nowrap",
};

const ghostLinkBtn: React.CSSProperties = {
  ...ghostBtn,
  display: "inline-flex",
  alignItems: "center",
  justifyContent: "center",
  textDecoration: "none",
};

const dangerBtn: React.CSSProperties = {
  padding: "8px 14px",
  borderRadius: 0,
  border: "1px solid rgba(255,180,180,0.3)",
  background: "transparent",
  color: "#ffb4b4",
  fontSize: 12,
  fontWeight: 600,
  letterSpacing: 0.8,
  cursor: "pointer",
  whiteSpace: "nowrap",
};

const sectionOrder: Record<string, string> = {
  SOURCE: "01",
  ANALYSIS: "02",
  CACHE: "03",
  LOOK: "04",
  RULES: "05",
  "ABOUT YOU": "06",
  AI: "07",
  NARRATION: "08",
};

function PageHeading({ title, subtitle }: { title: string; subtitle: string }) {
  return (
    <header style={pageHeading}>
      <div style={pageTitle}>{title}</div>
      <div style={pageSubtitle}>{subtitle}</div>
    </header>
  );
}

// Section —— 对齐 Android SettingsSectionHeader：编号 / 大写 label / 1px 分割线。
function Section({
  title,
  subtitle,
  label,
  children,
}: {
  title: string;
  subtitle?: string;
  label?: string;
  children: ReactNode;
}) {
  const items = Children.toArray(children);
  const multi = items.length > 1;
  const code = label ?? title.toUpperCase();
  return (
    <section style={sectionBlock}>
      <div style={sectionHeader}>
        <div style={sectionIndex}>{sectionOrder[code] ?? "00"}</div>
        <div style={sectionSlash}>/</div>
        <div style={sectionLabel}>{code}</div>
        <div style={sectionTitle}>{title}</div>
        {subtitle && <div style={sectionSubtitleInline}>{subtitle}</div>}
      </div>
      <div style={divider} />
      <div style={sectionBody}>
        {multi
          ? items.map((c, i) => (
              <div
                key={i}
                style={
                  i === 0 ? undefined : { borderTop: "1px solid rgba(233,239,255,0.08)" }
                }
              >
                {c}
              </div>
            ))
          : children}
      </div>
    </section>
  );
}

const pageWrap: React.CSSProperties = {
  padding: "clamp(64px, 8vh, 78px) clamp(18px, 5vw, 34px) 64px",
  maxWidth: 820,
  margin: "0 auto",
  width: "100%",
};

const pageHeading: React.CSSProperties = {
  paddingTop: "clamp(8px, 2vh, 18px)",
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
  marginBottom: 28,
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
  textTransform: "uppercase",
  fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace",
};

const sectionTitle: React.CSSProperties = {
  color: "rgba(233,239,255,0.42)",
  fontSize: 12,
  marginLeft: 4,
};

const sectionSubtitleInline: React.CSSProperties = {
  color: "#6c7489",
  fontSize: 12,
  fontWeight: 400,
};

const divider: React.CSSProperties = {
  height: 1,
  background: "rgba(233,239,255,0.08)",
  width: "100%",
};

const sectionBody: React.CSSProperties = {
  display: "flex",
  flexDirection: "column",
};

const footerNote: React.CSSProperties = {
  marginTop: 28,
  color: "#6c7489",
  fontSize: 11,
  textAlign: "center",
  letterSpacing: 0.3,
};

function NeteaseRow({ me, err }: { me: UserProfile | null | "loading"; err: string | null }) {
  const loading = me === "loading";
  const connected = !!me && me !== "loading";
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 14 }}>
      <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", gap: 12 }}>
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
          className="platform-action-button"
          style={{
            ...ghostLinkBtn,
            border: connected
              ? "1px solid rgba(155,227,198,0.46)"
              : ghostLinkBtn.border,
            color: connected ? "#9be3c6" : ghostLinkBtn.color,
          }}
        >
          {connected ? "换账号" : "扫码登录"}
        </Link>
      </div>

      {/* 登录之后，直接给一个入口：进入我的歌单 */}
      {connected && (
        <Link
          href="/distill"
          className="platform-action-button"
          style={{
            display: "flex",
            alignItems: "center",
            justifyContent: "space-between",
            padding: "13px 14px",
            borderRadius: 0,
            border: "1px solid rgba(155,227,198,0.4)",
            background: "rgba(155,227,198,0.055)",
            textDecoration: "none",
            color: "#e9efff",
          }}
        >
          <div>
            <div style={{ color: "#9be3c6", fontWeight: 700, fontSize: 15, display: "flex", alignItems: "center", gap: 6 }}>
              <span>进入我的歌单</span><AppIcon name="forward" size={14} />
            </div>
            <div style={{ color: "#8a93a8", fontSize: 12, marginTop: 2 }}>
              挑一张歌单，点一首歌，Pipo 会在主窗口原生播放。
            </div>
          </div>
          <AppIcon name="music" size={22} style={{ color: "#9be3c6" }} />
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
    <div style={{ display: "flex", flexDirection: "column", gap: 12 }}>
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
            height: 5,
            borderRadius: 0,
            background: "rgba(233,239,255,0.08)",
            overflow: "hidden",
          }}>
            <div style={{
              height: "100%",
              width: `${pct}%`,
              background: "#9be3c6",
              transition: "width 240ms ease",
            }} />
          </div>
        </div>
      ) : progress.lastFinishedAt ? (
        <div style={{ display: "flex", alignItems: "center", gap: 12, flexWrap: "wrap" }}>
          <div style={{ color: "#9be3c6", fontSize: 13, display: "inline-flex", alignItems: "center", gap: 6 }}>
            <AppIcon name="check" size={13} />
            <span>已分析 {progress.done} / {progress.total}</span>
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
          borderRadius: 0,
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
    <div style={{ display: "flex", flexDirection: "column", gap: 12 }}>
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
                    borderRadius: 0,
                    border: "1px solid rgba(233,239,255,0.10)",
                    background: "rgba(10,13,20,0.72)",
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
            borderRadius: 0,
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
        borderRadius: 0,
        background: "rgba(233,239,255,0.08)",
        overflow: "hidden",
      }}
    >
      <div
        style={{
          width: `${pct}%`,
          height: "100%",
          background: color,
          borderRadius: 0,
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
        gap: 16,
        padding: "6px 0",
        cursor: "pointer",
      }}
    >
      <div style={{ minWidth: 0 }}>
        <div style={{ fontWeight: 600 }}>{label}</div>
        <div style={{ color: "#8a93a8", fontSize: 12, marginTop: 2 }}>{desc}</div>
      </div>
      <div
        style={{
          width: 42,
          height: 24,
          flexShrink: 0,
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
// 用户在这里写"工作时间 / 作息 / 习惯 / 爱好"等关于自己的情况, Pipo 会把这段话
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
    "随便写两行,告诉 Pipo 关于你的情况。例:\n" +
    "工作日 9:00-18:30,双休,法定节假日休息。\n" +
    "通勤 30 分钟。最近常熬夜。喜欢 city pop 和后摇。";

  return (
    <div>
      <div style={{ color: "#8a93a8", fontSize: 12, marginBottom: 8, lineHeight: 1.6 }}>
        TA 自己写的事实比 AI 从行为里推断的更准。任何工作时间 / 作息 / 习惯 / 喜好都可以塞进来,Pipo 会顺着这些回应你。
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
          background: "rgba(10,13,20,0.72)",
          border: "1px solid rgba(233,239,255,0.08)",
          borderRadius: 0,
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
              borderRadius: 0,
              border: dirty
                ? "1px solid rgba(233,239,255,0.92)"
                : "1px solid rgba(233,239,255,0.10)",
              background: dirty ? "rgba(233,239,255,0.94)" : "transparent",
              color: dirty ? "#05060a" : "rgba(233,239,255,0.4)",
              fontSize: 12,
              fontWeight: 600,
              letterSpacing: 0.8,
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
