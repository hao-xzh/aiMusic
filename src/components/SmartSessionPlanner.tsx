"use client";

/**
 * 主动 Session Planner。
 *
 * 只在启动后一小会儿、用户还没播放时尝试一次：让 Pipo 根据当前时段和设置
 * 排一段短队列。AI 只负责理解场景和一句短回复，选歌仍走 pet-agent 的本地召回
 * / rank / smoothQueue 流水线，避免让模型直接编曲目。
 */

import { useCallback, useEffect, useRef, useState } from "react";
import { getAppSettingsSnapshot, useAppSettings, type AppSettings } from "@/lib/app-settings";
import { getAppContext, describeContext, type AppContext } from "@/lib/context";
import { loadLibrary } from "@/lib/library";
import { chat as petChat, type ContinuousSource } from "@/lib/pet-agent";
import { usePlayer } from "@/lib/player-state";
import { readBehaviorLog, summarize } from "@/lib/behavior-log";
import type { TrackInfo } from "@/lib/tauri";

const START_DELAY_MS = 11_000;
const RULE_CHECK_MS = 90_000;
const CORRECTION_CHECK_MS = 24_000;

type SessionProposal = {
  reply: string;
  tracks: TrackInfo[];
  continuous: ContinuousSource | null;
  canReplaceNow?: boolean;
};

export function SmartSessionPlanner() {
  const player = usePlayer();
  const [settings] = useAppSettings();
  const startedRef = useRef(false);
  const activeRuleRef = useRef("");
  const correctionRef = useRef("");
  const [proposal, setProposal] = useState<SessionProposal | null>(null);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (startedRef.current) return;
    const liveSettings = getAppSettingsSnapshot();
    if (!liveSettings.smartSessionPlanner) return;

    startedRef.current = true;
    const timer = window.setTimeout(() => {
      void makeSmartSession(getAppSettingsSnapshot(), player).then((next) => {
        if (next) setProposal(next);
      });
    }, START_DELAY_MS);
    return () => window.clearTimeout(timer);
  }, [settings, player]);

  useEffect(() => {
    let stopped = false;
    const tick = async () => {
      if (stopped || proposal) return;
      const liveSettings = getAppSettingsSnapshot();
      if (!liveSettings.smartSessionPlanner) return;
      const rule = activePlaybackRule(getAppContext(), liveSettings, player);
      if (!rule) return;
      if (activeRuleRef.current === rule.key) return;
      activeRuleRef.current = rule.key;
      const next = await makeSmartSession(liveSettings, player, {
        force: true,
        allowWhilePlaying: true,
        extraIntent: rule.intent,
      });
      if (stopped || !next) return;
      if (rule.autoPlay) {
        await playSessionNow(player, next);
        return;
      }
      setProposal({ ...next, canReplaceNow: rule.canReplaceNow });
    };
    const timeout = window.setTimeout(tick, 18_000);
    const interval = window.setInterval(tick, RULE_CHECK_MS);
    return () => {
      stopped = true;
      window.clearTimeout(timeout);
      window.clearInterval(interval);
    };
  }, [player, proposal]);

  useEffect(() => {
    let stopped = false;
    const tick = async () => {
      if (stopped || proposal || !player.isPlaying) return;
      const recent = await readBehaviorLog().catch(() => []);
      const skipped = recent
        .filter((ev) => ev.kind === "skipped")
        .slice(-3);
      if (skipped.length < 3) return;
      const newest = skipped[skipped.length - 1]!;
      const oldest = skipped[0]!;
      if (newest.ts - oldest.ts > 15 * 60) return;
      const key = `skip-correction:${newest.ts}`;
      if (correctionRef.current === key || window.sessionStorage.getItem(key) === "1") return;
      correctionRef.current = key;
      window.sessionStorage.setItem(key, "1");
      const next = await makeSmartSession(getAppSettingsSnapshot(), player, {
        force: true,
        allowWhilePlaying: true,
        extraIntent: "用户刚连续跳过三首，主动降能量、减少同类歌，换一段更贴耳的队列。",
      });
      if (!stopped && next) {
        setProposal({
          ...next,
          reply: "这波不太对。要我降下来吗？",
          canReplaceNow: true,
        });
      }
    };
    const interval = window.setInterval(tick, CORRECTION_CHECK_MS);
    return () => {
      stopped = true;
      window.clearInterval(interval);
    };
  }, [player, proposal]);

  const playProposal = useCallback(async () => {
    if (!proposal || (!proposal.canReplaceNow && (player.current || player.isPlaying))) return;
    if (proposal.tracks.length === 0) return;
    setProposal(null);
    await playSessionNow(player, proposal);
  }, [player, proposal]);

  const refreshProposal = useCallback(async () => {
    if (loading) return;
    setLoading(true);
    try {
      const next = await makeSmartSession(getAppSettingsSnapshot(), player, {
        refresh: true,
        force: true,
        allowWhilePlaying: proposal?.canReplaceNow,
      });
      if (next) setProposal({ ...next, canReplaceNow: proposal?.canReplaceNow });
    } finally {
      setLoading(false);
    }
  }, [loading, player, proposal?.canReplaceNow]);

  if (!proposal || (!proposal.canReplaceNow && (player.current || player.isPlaying))) return null;
  return (
    <div style={proposalShell}>
      <div style={proposalActions}>
        <button style={proposalButtonPrimary} onClick={playProposal}>播放</button>
        <button style={proposalButton} onClick={refreshProposal} disabled={loading}>
          {loading ? "换中" : "换一版"}
        </button>
      </div>
    </div>
  );
}

async function playSessionNow(
  player: ReturnType<typeof usePlayer>,
  proposal: SessionProposal,
) {
  const [head, ...rest] = proposal.tracks;
  if (!head) return;
  await player.playNetease(head, [head, ...rest], {
    smooth: false,
    continuous: proposal.continuous,
  });
}

async function makeSmartSession(
  settings: AppSettings,
  player: ReturnType<typeof usePlayer>,
  options: {
    refresh?: boolean;
    force?: boolean;
    allowWhilePlaying?: boolean;
    extraIntent?: string;
  } = {},
): Promise<SessionProposal | null> {
  if (document.hidden) return null;
  if (!options.allowWhilePlaying && (player.current || player.isPlaying)) return null;

  const ctx = getAppContext();
  const key = `claudio-smart-session:${ctx.date}:${ctx.timeSlot}`;
  if (!options.force && !options.refresh && window.sessionStorage.getItem(key) === "1") return null;
  if (!options.force) window.sessionStorage.setItem(key, "1");

  const library = await loadLibrary().catch(() => []);
  if (library.length === 0) return null;

  const behavior = await readBehaviorLog().catch(() => []);
  const stats = summarize(behavior);
  const last = behavior.slice().reverse().find((ev) => ev.kind === "completed" || ev.kind === "skipped" || ev.kind === "manual_cut");
  const brief = buildSessionBrief(ctx, settings, stats, last);
  const prompt = buildSessionPrompt(brief, Boolean(options.refresh), options.extraIntent);
  try {
    const response = await petChat({
      history: [],
      userText: prompt,
      skipMemory: true,
      currentTrack: null,
    });
    if (!response.resolvedTracks.length) return null;
    return {
      reply: response.text || "这段可以直接开始。",
      tracks: response.resolvedTracks,
      continuous: response.continuous ?? null,
    };
  } catch (e) {
    console.debug("[pipo] smart session failed", e);
    return null;
  }
}

type SessionBrief = {
  contextLine: string;
  mode: string;
  behaviorLine: string;
};

function buildSessionBrief(
  ctx: AppContext,
  settings: AppSettings,
  stats: ReturnType<typeof summarize>,
  last?: { title: string; artist: string; kind: string } | null,
): SessionBrief {
  const contextLine = describeContext(ctx);
  const mode = (() => {
    const radioRule = settings.promptedRadioRule.trim();
    if (radioRule) {
      return `Prompted Radio：${radioRule}`;
    }
    if (settings.lunchRelaxMode && ctx.timeSlot === "noon") {
      return "午休模式：轻、松、不抢注意力，像把房间亮度调低。";
    }
    if (settings.lateNightCalmMode && (ctx.timeSlot === "night" || ctx.timeSlot === "late_night")) {
      return "深夜模式：低能量、少鼓点、不要突然炸起来。";
    }
    if (isWorkSlot(ctx)) {
      return "工作模式：专注、干净、不中断，熟悉和新鲜各一半。";
    }
    if (ctx.isWeekend) {
      return "周末模式：松一点，可以有一点发现感，但别太吵。";
    }
    return "日常模式：自然开场，后面慢慢展开。";
  })();
  const skipLine = stats.skipHotArtists.length > 0
    ? `最近反复跳过：${stats.skipHotArtists.slice(0, 4).join("、")}`
    : "";
  const loveLine = stats.loveArtists.length > 0
    ? `最近更能听完：${stats.loveArtists.slice(0, 4).join("、")}`
    : "";
  const lastLine = last
    ? `上次停在：${last.title} — ${last.artist}（${last.kind === "skipped" ? "跳过" : "听过"}）`
    : "";
  const behaviorLine = [lastLine, loveLine, skipLine].filter(Boolean).join("；") || "最近行为样本还少，先保守安排。";
  return {
    contextLine,
    mode,
    behaviorLine,
  };
}

function buildSessionPrompt(brief: SessionBrief, refresh: boolean, extraIntent?: string): string {
  return [
    refresh ? "给我换一版 Pipo 电台，不要重复刚才那版。" : "你现在替我安排一段 Pipo 电台。",
    `当下：${brief.contextLine}`,
    `规则：${brief.mode}`,
    extraIntent ? `临时纠偏：${extraIntent}` : "",
    `近期行为：${brief.behaviorLine}`,
    "队列 10 到 15 首即可，能续杯，接歌要顺。",
    "回复只要一句很短的话；不要解释。",
  ].filter(Boolean).join("\n");
}

function isWorkSlot(ctx: AppContext): boolean {
  return !ctx.isWeekend && (
    ctx.timeSlot === "morning" ||
    ctx.timeSlot === "noon" ||
    ctx.timeSlot === "afternoon"
  );
}

function activePlaybackRule(
  ctx: AppContext,
  settings: AppSettings,
  player: ReturnType<typeof usePlayer>,
): { key: string; intent: string; canReplaceNow: boolean; autoPlay: boolean } | null {
  const idle = !player.current && !player.isPlaying;
  const custom = settings.promptedRadioRule.trim();
  if (custom && !player.isPlaying) {
    return {
      key: `prompted:${ctx.date}:${ctx.timeSlot}`,
      intent: `执行长期电台规则：${custom}`,
      canReplaceNow: false,
      autoPlay: idle,
    };
  }
  if (settings.lunchRelaxMode && ctx.timeSlot === "noon") {
    return {
      key: `lunch:${ctx.date}`,
      intent: "午休到了，把队列切到更柔和、更低注意力占用。",
      canReplaceNow: !idle,
      autoPlay: idle,
    };
  }
  if (settings.lateNightCalmMode && (ctx.timeSlot === "night" || ctx.timeSlot === "late_night")) {
    return {
      key: `night:${ctx.date}:${ctx.timeSlot}`,
      intent: "深夜降低 BPM、能量和鼓点密度，避免突然炸起来。",
      canReplaceNow: !idle,
      autoPlay: idle,
    };
  }
  if (settings.workdayAutoplay && isWorkSlot(ctx) && !player.isPlaying) {
    return {
      key: `work:${ctx.date}:${ctx.timeSlot}`,
      intent: "工作时段自动安排低打扰背景队列。",
      canReplaceNow: false,
      autoPlay: idle,
    };
  }
  return null;
}

const proposalShell: React.CSSProperties = {
  position: "fixed",
  left: "50%",
  bottom: "calc(env(safe-area-inset-bottom, 0px) + 18px)",
  transform: "translateX(-50%)",
  zIndex: 70,
  width: "min(72vw, 260px)",
  display: "grid",
  padding: "10px",
  borderRadius: 18,
  background: "rgba(10, 13, 20, 0.78)",
  border: "1px solid rgba(233, 239, 255, 0.10)",
  boxShadow: "0 18px 48px rgba(0,0,0,0.36)",
  backdropFilter: "blur(18px) saturate(1.2)",
};

const proposalActions: React.CSSProperties = {
  display: "grid",
  gridTemplateColumns: "1fr 1fr",
  gap: 8,
};

const proposalButton: React.CSSProperties = {
  height: 34,
  border: "1px solid rgba(233,239,255,0.10)",
  borderRadius: 999,
  background: "rgba(255,255,255,0.06)",
  color: "rgba(233,239,255,0.82)",
  fontSize: 13,
};

const proposalButtonPrimary: React.CSSProperties = {
  ...proposalButton,
  background: "rgba(155,227,198,0.18)",
  color: "#dffbed",
  border: "1px solid rgba(155,227,198,0.30)",
};
