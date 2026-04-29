"use client";

/**
 * AI 宠物的对话脑 v3 —— 一次 LLM,人格和意图同源。
 *
 * v1：宠物聊天人格 + 从 800 首库里选歌塞一个 prompt。AI 一边演宠物一边选歌，质量不稳。
 * v2：拆成 parseMusicIntent + planPlaylist 两次调用。意图准了，但 planPlaylist 频繁抽风
 *     （JSON 坏 / id 幻觉 / zod 自相矛盾），用户经常看到 "我这边没排出来"。
 *     而且人格回复跟"放不放歌"分裂 —— AI 嘴上说"行"但其实没放，反过来也有。
 * v3（本版）：把人格 reply 和意图字段合并到 parseMusicIntent **一次** LLM 输出里。
 *     选歌彻底交给本地（recall + rank + smoothQueue），不再有第二次 LLM 调用。
 *
 *   userText
 *     │
 *     ▼  parseMusicIntent (one LLM call)
 *   { reply, action, hardConstraints, softPreferences, queueIntent, ... }
 *     │
 *     ├─ action="chat" ──► return { text=reply }
 *     │
 *     └─ action="play"
 *           │
 *           ▼  recallCandidates (本地多路召回)
 *           ▼  rankCandidates (本地公式打分)
 *           ▼  pick top N (no second LLM)
 *           ▼  smoothQueue (DJ 局部贪心排序)
 *           ▼  return { text=reply, play, resolvedTracks }
 *
 * 关键性质：reply 永远来自同一次 LLM，所以 AI 说"来点带劲的"的同时必然真的在放带劲的；
 * 不会出现嘴在说但手没动的情况。
 *
 * 协议保持兼容：AgentResponse 仍然返回 { text, play, resolvedTracks }，
 * AiPet 组件不需要改。
 */

import { ai, cache } from "./tauri";
import type { TrackInfo } from "./tauri";
import { getMemoryDigest, recordUserUtterance } from "./pet-memory";
import { loadTasteProfile } from "./taste-profile";
import { getAppContext, describeContext } from "./context";
import { getWeather } from "./weather";
import { loadLibrary } from "./library";
import { smoothQueue } from "./smooth-queue";
import {
  parseMusicIntent,
  type MusicIntent,
} from "./music-intent";
import { recallCandidates } from "./candidate-recall";
import { rankCandidates } from "./candidate-ranker";
import { readRecentPlayContext } from "./behavior-log";
import {
  logRecommendations,
  readRecentRecommendationContext,
} from "./recommendation-log";
import {
  dedupeTrackInfos,
  queryAsksForSpecificVersion,
} from "./track-dedupe";

export type ChatMessage = {
  role: "user" | "assistant";
  text: string;
  /** 如果这条 assistant 消息触发了播放，附上播放计划 */
  play?: PlayPlan;
};

export type PlayPlan = {
  trackIds: number[];
  /** AI 写的"为什么这一组"短文 —— UI 上展示给用户看 */
  reason: string;
};

// ---------- 每日首开问候 ----------

const GREET_KEY = "claudio_pet_last_greet_date";

export function shouldGreetToday(): boolean {
  if (typeof window === "undefined") return false;
  const today = new Date().toISOString().slice(0, 10);
  return localStorage.getItem(GREET_KEY) !== today;
}

export function markGreeted(): void {
  if (typeof window === "undefined") return;
  const today = new Date().toISOString().slice(0, 10);
  localStorage.setItem(GREET_KEY, today);
}

/**
 * 生成问候语 —— 周几 + 天气 + 想听啥。失败兜底成不带天气的版本。
 * 这一段没经过流水线 —— 不需要意图解析也不需要选歌。
 */
// SYSTEM 模块级常量 —— 让招呼语的规则/示例进 DeepSeek 缓存。
// 一天虽然只触发一次,但跟 commentOnTrack 没法共享(那个的 system 不一样),
// 这里独立成一个缓存条目,反复几天命中,省一点是一点。
const GREETING_SYSTEM = `你是 Claudio —— TA 熟到不用客气的音乐宠物。
打开 app 时由你说一句**进门招呼**(不是问候,是熟人语气的一句话陈述)。

# 要求
- **短**。≤18 字最好。一句话。
- 从 USER 给的"当下"里挑**一个**锚点(时段 / 天气 / 周几 / 临近周末 / 临近假期),别全报。
  临近周末或假期时优先用——那是 TA 心情会变化的信号。
- 不必问 TA 想听啥——陈述当下也行,反问也行(最多一次)。
- 绝不要客服腔("早上好!""希望您…")。不要双形容词对仗(既…又…)。不要感叹号。

# 输出格式
严格只输出这一句话,不要解释/JSON/markdown/引号。

# 参考样本(不同场景)
周日下午 → "周末还剩半天。"
周五晚上 → "明天就放假了。"
周一上午 → "周一这玩意。"
下雨天 → "下雨了。"
国庆前 3 天 → "假期还有 3 天就到。"
春节当天 → "过年好,听点啥?"
深夜 → "醒着呢。"
普通工作日下午 → "下午好懒。"`;

export async function generateDailyGreeting(): Promise<string> {
  const ctx = getAppContext();
  const [weather, profile, memoryDigest] = await Promise.all([
    getWeather(),
    loadTasteProfile(),
    getMemoryDigest().catch(() => ""),
  ]);

  const ctxLine = describeContext({ ...ctx, weather: weather ?? undefined });
  const profileHint = profile?.summary
    ? `(TA 的音乐画像:${profile.summary})`
    : "";

  // USER 只放变量,~50-100 tokens. 跨 session 记忆让招呼能 reference 之前的话.
  const user = [
    `当下:${ctxLine}`,
    profileHint,
    memoryDigest ? `TA 的人:${memoryDigest}` : "",
  ]
    .filter(Boolean)
    .join("\n");

  try {
    const raw = await ai.chat({
      system: GREETING_SYSTEM,
      user,
      temperature: 0.95,
      maxTokens: 80,
    });
    const cleaned = raw.trim().replace(/^[「『"']+|[」』"']+$/g, "").slice(0, 80);
    if (cleaned) return cleaned;
  } catch (e) {
    console.debug("[claudio] pet greeting AI 失败,用兜底", e);
  }

  // 兜底也尽量带上锚点
  if (ctx.upcomingHoliday && ctx.upcomingHoliday.daysAhead <= 3) {
    return `还有 ${ctx.upcomingHoliday.daysAhead} 天就${ctx.upcomingHoliday.name}。`;
  }
  if (!ctx.isWeekend && ctx.daysUntilWeekend === 1) {
    return "明天就周末。";
  }
  return weather
    ? `${ctx.dayOfWeek}${ctx.timeSlotLabel},${weather.summary} ${weather.tempC}°。`
    : `${ctx.dayOfWeek}${ctx.timeSlotLabel}。`;
}

// ---------- 单曲点评：每首歌开始播时 Claudio 说一句 ----------

export type TrackCommentInput = {
  track: {
    id: number;
    title: string;
    artist: string;
    /** 这首歌的语义 profile —— 让 AI 真的能"看懂"这首歌的特点 */
    moods?: string[];
    scenes?: string[];
    genres?: string[];
    summary?: string;
  };
  /** 用户最近一条文字（用来贴 TA 当下心情）。没有就当"开场无 context" */
  userContext?: string;
  /** 队列第几首（1 起算）。1=开场，>1=接歌——语气区分 */
  positionInQueue?: number;
  /** 上一首什么 —— 让"接歌"有上下文（"降一点速""换个味道"） */
  previousTrack?: { title: string; artist: string };
};

/**
 * 单曲点评：每首歌开始播时让 Claudio 说一句"为什么放这首"。
 * 上限 16 字左右，幽默抽象，绝不客服腔。
 *
 * 失败时返回空字符串 —— 上层只在非空时显示气泡，不要兜底打扰用户。
 * AI 调用很轻（≤60 tokens 输出），每首歌一次完全在预算内。
 */
// SYSTEM 模块级常量 —— 含全部规则/示例,跨调用永远不变 → DeepSeek 自动缓存命中。
// 旧版把这一坨塞 user 字段且顶在变量行后面,每次发一遍且永远不命中。
const TRACK_COMMENT_SYSTEM = `你是 Claudio,一只幽默抽象的音乐宠物。
每当一首歌开始播,你说一句**为什么放这首给 TA**。

# 调性
- 短。一句话。能短就短,5-8 字最佳,绝不超过 16 字。
- 不是介绍歌,是说"为什么它适合这一刻"。
- 抽象比喻 OK,但要跟 TA 的话 / 这首歌的特征 / 当下时刻接得上。
- 把当下时段/天气/临近周末或假期当作锚点之一,但只挑最相关的一个,别一句话报全。
- 不要客服词("为您""推荐"),不要感叹号,不要 emoji。

# 输出格式
直接输出这一句话本身——不要 JSON、不要前缀、不要引号、不要解释。

# 示例(注意时间/天气/假期可以是锚点)
TA："今天好累",播 Coldplay → "拿这个把电量充回去。"
周五晚上,播 city pop → "周末已经在门口。"
下雨,播 ambient → "雨配这个,正好。"
再 3 天国庆,播 funk → "假期心情先到。"
TA："我刚分手",播 The Killers → "猛的,开场。"
深夜,播 lo-fi → "适合熬。"
周一上午,播 indie folk → "周一不该这么吵。"
接歌,前激情本慢 → "降一点速。"
接歌,同艺人连排 → "再多听 TA 一首。"
开场无 context,播 indie folk → "这首是入口。"`;

export async function commentOnTrack(input: TrackCommentInput): Promise<string> {
  const { track, userContext, positionInQueue, previousTrack } = input;
  const tagsLine = [
    track.summary,
    track.moods?.length ? `情绪=${track.moods.slice(0, 3).join("/")}` : null,
    track.scenes?.length ? `场景=${track.scenes.slice(0, 2).join("/")}` : null,
    track.genres?.length ? `风格=${track.genres.slice(0, 2).join("/")}` : null,
  ]
    .filter(Boolean)
    .join(" · ");

  const isOpener = !positionInQueue || positionInQueue <= 1;

  // 时间/天气/节假日上下文 —— 让点评能说"周五晚上""明天就周末"
  // "再 3 天就国庆" 这种期待感. weather 已经是 memo'd 调用,几乎无开销.
  // 同时也读跨 session 记忆 —— 偏好/跳过率/上次说过什么。
  const [ctxFresh, weather, memoryDigest] = await Promise.all([
    Promise.resolve(getAppContext()),
    getWeather().catch(() => null),
    getMemoryDigest().catch(() => ""),
  ]);
  const ctxLine = describeContext({ ...ctxFresh, weather: weather ?? undefined });

  // USER 只放变量。短,~80-150 tokens,每次都发新的(本来也不缓存)。
  const userPrompt =
    `当下：${ctxLine}\n` +
    (memoryDigest ? `TA 的人:${memoryDigest}\n` : "") +
    `现在播：${track.title} — ${track.artist}\n` +
    (tagsLine ? `这首特征：${tagsLine}\n` : "") +
    (userContext ? `TA 之前说：「${userContext}」\n` : "") +
    (previousTrack
      ? `刚刚那首：${previousTrack.title} — ${previousTrack.artist}\n`
      : "") +
    `${isOpener ? "这是开场。" : `这是接歌(队列第 ${positionInQueue} 首)。`}`;

  try {
    const raw = await ai.chat({
      system: TRACK_COMMENT_SYSTEM,
      user: userPrompt,
      temperature: 0.95,
      maxTokens: 60,
    });
    // 清掉常见的引号/书名号包裹和首尾空白
    return raw
      .trim()
      .replace(/^["'「『""]+|["'」』""]+$/g, "")
      .replace(/^\s*[-—]\s*/, "")
      .slice(0, 30);
  } catch (e) {
    console.debug("[claudio] commentOnTrack 失败", e);
    return "";
  }
}

// ---------- 主聊天入口 ----------

export type AgentInput = {
  history: ChatMessage[];
  userText: string;
  /**
   * 当前正在播的曲目（轻量 shape）。给 intent parser / recall 做语境锚点：
   *   - 让 AI 理解 "再来一首类似的" 这种代词
   *   - transition 召回路从这首接得上的歌里找候选
   * 不传也行，只是失去这部分上下文。
   */
  currentTrack?: {
    neteaseId: number;
    title: string;
    artist: string;
  } | null;
};

export type AgentResponse = {
  /** 给用户看的文本部分（剥掉 PLAY 块） */
  text: string;
  /** 如果有播放指令 */
  play: PlayPlan | null;
  /** 若 play 有值，配合返回去重 + 校验过的真 TrackInfo[]（按 trackIds 顺序） */
  resolvedTracks: TrackInfo[];
};

/**
 * 主入口。
 *
 * 新流水线（v3）：
 *
 *   userText
 *     │
 *     ▼  parseMusicIntent —— 一次 LLM 调用同时产出 { reply, action, intent...}
 *     │  reply 是 Claudio 以人格说出来的话（永远要有）
 *     │  action=chat：到此结束
 *     │  action=play：继续走召回
 *     ▼
 *   recallCandidates → rankCandidates → smoothQueue   ※ 全本地，无 LLM
 *     │
 *     ▼
 *   AgentResponse { text=intent.reply, play, resolvedTracks }
 *
 * 旧版 v2 有一次额外的 planPlaylist LLM 调用做最终编排，被去掉了 ——
 * 它频繁抽风（JSON 坏 / id 幻觉 / zod 自相矛盾），而本地排序 + smoothQueue 已经够好。
 * 同时人格 reply 现在和意图同源生成，不会出现"AI 嘴上说放歌但不放"或反过来。
 */
export async function chat(input: AgentInput): Promise<AgentResponse> {
  const ctx = getAppContext();
  // 顺手把天气也拉一份(memo'd, 几乎免费)给到上下文,
  // 让"下雨/晴/冷/热"成为人格回复可以借力的锚点之一。
  const weather = await getWeather().catch(() => null);
  // 跨 session 记忆: 偏好艺人 / 跳过率 / 上次说过什么 / 听过多少首
  // 让 Claudio 不再每次启动都失忆。
  const memoryDigest = await getMemoryDigest().catch(() => "");

  // 用户说话先写入跨 session 记忆(在 AI 调用前 fire-and-forget,
  // 这样如果用户连续发话,后面的 AI 调用看得到前面的话作为上下文)
  void recordUserUtterance(input.userText);

  // ---- 阶段 1：人格 + 意图（一次 LLM 调用）----
  const intent = await parseMusicIntent(input.userText, {
    currentTrack: input.currentTrack
      ? {
          title: input.currentTrack.title,
          artist: input.currentTrack.artist,
        }
      : null,
    // 用 describeContext 而不是简单拼接 —— 把"明天就周末""再 3 天就国庆""今天下雨"
    // 这种信号都给到 AI，让人格回复能借力。
    timeContext: describeContext({ ...ctx, weather: weather ?? undefined }),
    history: input.history.map((m) => ({ role: m.role, text: m.text })),
    memoryDigest: memoryDigest || undefined,
  });

  const replyText = (intent.reply || "嗯。").trim();

  // ---- 纯聊天分支：reply 已经有人格，直接返回 ----
  if (intent.action === "chat") {
    void writeDebugRecord({
      userText: input.userText,
      intent,
      reply: replyText,
    });
    return { text: replyText, play: null, resolvedTracks: [] };
  }

  // ---- 放歌分支 ----
  const library = await loadLibrary();
  if (library.length === 0) {
    // 库空也保留人格回复，再附一句提示
    return {
      text: `${replyText} 先去口味页蒸馏一下歌单库。`,
      play: null,
      resolvedTracks: [],
    };
  }

  const currentTrackInfo = input.currentTrack
    ? library.find((t) => t.id === input.currentTrack?.neteaseId) ?? null
    : null;

  // 召回 + 本地打分
  const candidates = await recallCandidates({
    intent,
    library,
    currentTrack: currentTrackInfo,
    limit: 240,
  });
  if (candidates.length === 0) {
    return {
      text: `${replyText}（这次库里真没贴的，换个说法？）`,
      play: null,
      resolvedTracks: [],
    };
  }
  const [recentPlay, recentRecommendation] = await Promise.all([
    readRecentPlayContext().catch(() => undefined),
    readRecentRecommendationContext().catch(() => undefined),
  ]);
  const ranked = rankCandidates(candidates, intent, {
    topN: 100,
    recentPlay,
    recentRecommendation,
  });
  if (ranked.length === 0) {
    return {
      text: `${replyText}（符合得太少，换个说法？）`,
      play: null,
      resolvedTracks: [],
    };
  }

  // 直接拿排序后的前 N 首 —— 不再走第二次 LLM 编排。
  const wanted = Math.max(10, Math.min(intent.desiredCount, ranked.length));
  const pickedTracks = ranked.slice(0, wanted).map((c) => c.track);

  const dedupedResolved = queryAsksForSpecificVersion(intent)
    ? pickedTracks
    : dedupeTrackInfos(pickedTracks);
  if (dedupedResolved.length === 0) {
    // 兜底中的兜底
    const safety = ranked.slice(0, 20).map((c) => c.track);
    if (safety.length === 0) {
      return {
        text: `${replyText}（这会儿真挑不出来。）`,
        play: null,
        resolvedTracks: [],
      };
    }
    dedupedResolved.push(...safety);
  }

  // smoothQueue：按接歌匹配分重排，让歌之间过渡自然
  const smoothed =
    dedupedResolved.length > 2
      ? await smoothQueue(dedupedResolved, {
          startTrackId: dedupedResolved[0].id,
          mode: "discovery",
        }).catch(() => dedupedResolved)
      : dedupedResolved;

  void logRecommendations(smoothed.map((t) => t.id), "pet");

  void writeDebugRecord({
    userText: input.userText,
    intent,
    candidateCount: candidates.length,
    rankedCount: ranked.length,
    topRanked: ranked.slice(0, 20).map((c) => ({
      id: c.track.id,
      name: c.track.name,
      sources: c.sources,
      finalScore: Number(c.finalScore.toFixed(3)),
      semantic: c.semanticProfile
        ? {
            language: c.semanticProfile.language.primary,
            region: c.semanticProfile.region.primary,
            genres: c.semanticProfile.style.genres,
          }
        : null,
    })),
    finalTrackIds: smoothed.map((t) => t.id),
    reply: replyText,
    reason: `intent: ${intent.softPreferences.moods.slice(0, 3).join("/")} · ${intent.queueIntent.orderStyle}`,
  });

  return {
    text: replyText,
    play: {
      trackIds: smoothed.map((t) => t.id),
      reason: `${intent.softPreferences.moods.slice(0, 2).join(" · ")} · ${intent.queueIntent.orderStyle}`,
    },
    resolvedTracks: smoothed,
  };
}

// ---------- 调试记录 ----------
//
// 每次 chat() 把意图 / 召回大小 / 排序 Top 20 / 最终选中 / AI 回复都写到 cache，
// 方便后续在调试页 / 命令行 dump 出来检视一次决策的全过程。
// 单 key 滚动覆盖；不做长尾 history（避免缓存膨胀）。
const DEBUG_KEY = "pet_debug_last";

type DebugRecord = {
  ts: number;
  userText: string;
  intent: MusicIntent;
  reply?: string;
  reason?: string;
  candidateCount?: number;
  rankedCount?: number;
  topRanked?: Array<{
    id: number;
    name: string;
    sources: string[];
    finalScore: number;
    semantic?: {
      language: string;
      region: string;
      genres: string[];
    } | null;
  }>;
  finalTrackIds?: number[];
  usedFallback?: boolean;
};

async function writeDebugRecord(rec: Omit<DebugRecord, "ts">): Promise<void> {
  try {
    await cache.setState(
      DEBUG_KEY,
      JSON.stringify({ ts: Date.now(), ...rec }, null, 0),
    );
  } catch {
    /* 调试落盘失败不影响主路径 */
  }
}

export async function loadLastDebugRecord(): Promise<DebugRecord | null> {
  try {
    const raw = await cache.getState(DEBUG_KEY);
    if (!raw) return null;
    return JSON.parse(raw) as DebugRecord;
  } catch {
    return null;
  }
}
