"use client";

/**
 * AI 宠物的对话脑 v2 —— 流水线架构。
 *
 * 旧版：把"宠物聊天人格 + 从 800 首库里选歌"塞进一个巨型 prompt，
 *       AI 同时要演宠物又要做选歌规划，质量不稳。
 *
 * 新版：
 *
 *   userText
 *     │
 *     ▼  parseMusicIntent (small LLM call)
 *   MusicIntent
 *     │
 *     ├─ action="chat" ──► petReplyOnly (one short LLM call)  ──► AgentResponse
 *     │
 *     └─ action="play"
 *           │
 *           ▼  recallCandidates (本地多路召回，不调 AI)
 *         Candidate[]
 *           │
 *           ▼  rankCandidates (本地公式打分)
 *         Top N (≈100)
 *           │
 *           ▼  planPlaylist (LLM 只看 Top N，输出 trackIds + reason + reply)
 *         PlanResult
 *           │
 *           ▼  smoothQueue (DJ 局部贪心排序)
 *         AgentResponse
 *
 * 这样 LLM 只做"理解用户 + 在已经收敛的候选池里编排"，不再面对 800 首随机采样的全库。
 *
 * 协议保持兼容：AgentResponse 仍然返回 { text, play, resolvedTracks }，
 * AiPet 组件不需要改。
 */

import { z } from "zod";

import { ai, cache } from "./tauri";
import type { TrackInfo } from "./tauri";
import { loadTasteProfile } from "./taste-profile";
import { getAppContext, describeContext } from "./context";
import { getWeather } from "./weather";
import { loadLibrary } from "./library";
import { smoothQueue } from "./smooth-queue";
import {
  parseMusicIntent,
  type MusicIntent,
} from "./music-intent";
import { recallCandidates, type Candidate } from "./candidate-recall";
import { rankCandidates, type RankedCandidate } from "./candidate-ranker";
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
export async function generateDailyGreeting(): Promise<string> {
  const ctx = getAppContext();
  const weather = await getWeather();
  const profile = await loadTasteProfile();

  const weatherLine = weather
    ? `当下天气：${weather.summary} ${weather.tempC}°C`
    : "（天气拉不到，跳过）";

  const profileHint = profile?.summary
    ? `（用户音乐画像一句话：${profile.summary}）`
    : "（用户还没蒸馏画像）";

  const user =
    `${describeContext(ctx)}\n${weatherLine}\n${profileHint}\n\n` +
    `任务：你是 Claudio —— TA 熟到不用客气的音乐宠物。写一句进门招呼。\n` +
    `要求：\n` +
    `1) **短**。≤18 字最好。一句话就行。\n` +
    `2) 可以提一下周几/天气作为锚点，但只提一个，不要"今天周一傍晚下小雨"全报。\n` +
    `3) 不必每次都问 TA 想听啥 —— 偶尔陈述一下当下也行。\n` +
    `4) 绝对不要："早上好！" "今天周X，想听什么？" "希望您..." 这种客服腔，也不要双形容词对仗（既...又...）。\n` +
    `\n参考样本：\n` +
    `- "周六下午，懒一点的可以？"\n` +
    `- "下雨了。"\n` +
    `- "醒着呢。"\n` +
    `- "周三还剩半天。"\n` +
    `\n严格只输出这一句话，不要解释、不要 JSON、不要 markdown。`;

  try {
    const raw = await ai.chat({
      system:
        "你是 Claudio，TA 的音乐宠物。说话像熟到不用客气的朋友：短、随意、不套路、不演。绝不写感叹号或客服腔。",
      user,
      temperature: 0.95,
      maxTokens: 80,
    });
    const cleaned = raw.trim().replace(/^[「『"']+|[」』"']+$/g, "").slice(0, 80);
    if (cleaned) return cleaned;
  } catch (e) {
    console.debug("[claudio] pet greeting AI 失败，用兜底", e);
  }

  return weather
    ? `${ctx.dayOfWeek}${ctx.timeSlotLabel}，${weather.summary} ${weather.tempC}°，想听点什么？`
    : `${ctx.dayOfWeek}${ctx.timeSlotLabel}，想听点什么？`;
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
 * 主入口。流水线见模块顶部注释。
 */
export async function chat(input: AgentInput): Promise<AgentResponse> {
  const ctx = getAppContext();

  // ---- 阶段 1：解析意图 ----
  const intent = await parseMusicIntent(input.userText, {
    currentTrack: input.currentTrack
      ? {
          title: input.currentTrack.title,
          artist: input.currentTrack.artist,
        }
      : null,
    timeContext: `${ctx.dayOfWeek}${ctx.timeSlotLabel}`,
  });

  // ---- 阶段 2A：纯聊天 ----
  if (intent.action === "chat") {
    const text = await petChatReply(input);
    void writeDebugRecord({
      userText: input.userText,
      intent,
      reply: text,
    });
    return { text, play: null, resolvedTracks: [] };
  }

  // ---- 阶段 2B：要听歌 ----
  const library = await loadLibrary();
  if (library.length === 0) {
    return {
      text: "你还没蒸馏歌单库，先去口味页拉一下。",
      play: null,
      resolvedTracks: [],
    };
  }

  // 把当前曲目从轻量 shape 升级到 TrackInfo（recall 的 transition 路要用），
  // 库里没有就 null —— 不影响其它召回路。
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
      text: "库里没找到对得上这句的歌。",
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
      text: "符合的太少，换个说法？",
      play: null,
      resolvedTracks: [],
    };
  }

  // LLM 只看 Top N 编排歌单 + 写一句宠物短句
  const planResult = await planPlaylist({
    intent,
    history: input.history,
    candidates: ranked,
    timeContextLine: `${ctx.dayOfWeek}${ctx.timeSlotLabel}`,
  });
  if (!planResult || planResult.trackIds.length === 0) {
    return {
      text: "我这边没排出来，再说一次？",
      play: null,
      resolvedTracks: [],
    };
  }

  // 把 trackIds 物化回 TrackInfo[]（只保留候选池里的 id，过滤幻觉）
  const candidateById = new Map(
    ranked.map((c) => [c.track.id, c.track] as const),
  );
  const resolved: TrackInfo[] = [];
  const seen = new Set<number>();
  for (const id of planResult.trackIds) {
    if (seen.has(id)) continue;
    const t = candidateById.get(id);
    if (!t) continue;
    seen.add(id);
    resolved.push(t);
  }
  const dedupedResolved = queryAsksForSpecificVersion(intent)
    ? resolved
    : dedupeTrackInfos(resolved);
  if (dedupedResolved.length === 0) {
    return {
      text: "AI 编了不存在的 id，重试一下。",
      play: null,
      resolvedTracks: [],
    };
  }

  // smoothQueue：discovery 模式（AI 选的歌单要"丰富感"，强避免同艺人扎堆）
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
    reply: planResult.reply,
    reason: planResult.reason,
  });

  return {
    text: planResult.reply,
    play: {
      trackIds: smoothed.map((t) => t.id),
      reason: planResult.reason,
    },
    resolvedTracks: smoothed,
  };
}

// ---------- 阶段 2A：宠物纯聊天 ----------

const PET_PERSONA_PROMPT = `你是 Claudio，TA 的音乐宠物。

# 说话调性

不是客服。不是助手。**就是一个不太热情、有点懒、关系熟到不用客气的朋友**。

绝对不要做的：
- ❌ 三连问：一次最多一个问题，能不问就不问
- ❌ "说给我听听" / "都可以聊聊" / "我陪你" —— 假治疗师腔
- ❌ 双形容词对仗："有点沉，又有点暖" / "既...又..." —— AI 写文案
- ❌ A/B 菜单："想听什么方向的？" —— 别给选项
- ❌ "嘿/嗯，..." 起手 —— 太演
- ❌ "好嘞 / 好咧" —— 服务员腔
- ❌ "...真不错 / 最适合 / 完美匹配" —— 别夸奖
- ❌ 感叹号、emoji、长句、解释、铺垫

要做的：
- ✓ **短**。多数回复 1-12 个字。最多两句。
- ✓ 真朋友的随意感："嗯""行""好""ok""哦?"
- ✓ 偶尔反问一次（一次！），但要短："咋了？"够了
- ✓ 一个字回复也行
- ✓ TA 嫌你 bb 了就立刻闭嘴，**不要再说"好的不废话"，那也是话**

参考样本：
- 用户："我累了"  → "嗯。"
- 用户："今天好烦" → "咋了？"
- 用户："谢谢"   → "嗯。"
`;

async function petChatReply(input: AgentInput): Promise<string> {
  const historyBlock = input.history
    .slice(-8)
    .map((m) => `${m.role === "user" ? "用户" : "你"}：${m.text}`)
    .join("\n");

  const userPrompt =
    (historyBlock ? `刚才的对话：\n${historyBlock}\n\n` : "") +
    `用户最新一句：${input.userText}\n\n` +
    `按 system 调性回一句。**短**，多数情况 ≤12 字。`;

  try {
    const raw = await ai.chat({
      system: PET_PERSONA_PROMPT,
      user: userPrompt,
      temperature: 0.85,
      maxTokens: 120,
    });
    const cleaned = raw.trim().replace(/^[「『"']+|[」』"']+$/g, "").slice(0, 80);
    return cleaned || "嗯。";
  } catch (e) {
    console.warn("[claudio] pet chat reply AI 失败", e);
    return "我这边断线了，再说一次？";
  }
}

// ---------- 阶段 2B：歌单编排 ----------

const PlannerOutputSchema = z.object({
  trackIds: z.array(z.number().int()).min(1).max(60),
  reason: z.string().min(1).max(120),
  reply: z.string().min(1).max(60),
});

type PlannerOutput = z.infer<typeof PlannerOutputSchema>;

type PlanInput = {
  intent: MusicIntent;
  history: ChatMessage[];
  candidates: RankedCandidate[];
  timeContextLine: string;
};

async function planPlaylist(input: PlanInput): Promise<PlannerOutput | null> {
  const { intent, candidates, history, timeContextLine } = input;

  // 候选池序列化 —— 只放对编排有用的字段，省 token
  const candidateLines = candidates
    .map((c) => formatCandidateLine(c))
    .join("\n");

  const intentLine =
    `action=play, ` +
    `hardLanguages=[${intent.hardConstraints.languages.join(",")}], ` +
    `hardRegions=[${intent.hardConstraints.regions.join(",")}], ` +
    `hardGenres=[${intent.hardConstraints.genres.join(",")}], ` +
    `excludeLanguages=[${intent.hardConstraints.excludeLanguages.join(",")}], ` +
    `excludeGenres=[${intent.hardConstraints.excludeGenres.join(",")}], ` +
    `excludeTags=[${intent.hardConstraints.excludeTags.join(",")}], ` +
    `moods=[${intent.musicHints.moods.join(",")}], ` +
    `scenes=[${intent.musicHints.scenes.join(",")}], ` +
    `genres=[${intent.musicHints.genres.join(",")}], ` +
    `softMoods=[${intent.softPreferences.moods.join(",")}], ` +
    `softScenes=[${intent.softPreferences.scenes.join(",")}], ` +
    `textures=[${intent.softPreferences.textures.join(",")}], ` +
    `avoid=[${intent.musicHints.avoid.join(",")}], ` +
    `energy=${intent.softPreferences.energy}, ` +
    `style=${intent.queueIntent.transitionStyle}, ` +
    `order=${intent.queueIntent.orderStyle}, ` +
    `count=${intent.desiredCount}, ` +
    `replyStyle=${intent.replyStyle}`;

  const historyBlock = history
    .slice(-6)
    .map((m) => `${m.role === "user" ? "用户" : "你"}：${m.text}`)
    .join("\n");

  const user =
    `当下：${timeContextLine}\n` +
    `用户最新一句：${intent.queryText}\n` +
    `intent: ${intentLine}\n` +
    (historyBlock ? `\n最近对话：\n${historyBlock}\n` : "") +
    `\n候选池（已经过本地多路召回 + 打分排序，越上面越贴当下需求）：\n` +
    `# id|name|artist|lang|region|genres|moods|scenes|energy|bpm|sources|summary\n` +
    `${candidateLines}\n` +
    `\n任务：从候选池里选 ${intent.desiredCount} 首左右，做有序播放列表。\n` +
    `\n规则：\n` +
    `1) **trackIds 必须来自候选池**，不要编 id。\n` +
    `2) 必须遵守 hardLanguages/hardRegions/hardGenres，不要选择 excludeLanguages/excludeGenres/excludeTags 命中的歌。\n` +
    `3) 你要真正理解"用户最新一句"的自然语言，不要只机械匹配示例标签；候选里的 summary/moods/scenes/textures 是为了让你判断开放表达。\n` +
    `4) 用户没点名艺人时，不要让同一艺人占据歌单；同艺人不要连排，最多少量穿插。\n` +
    `5) 如果用户要求氛围，优先看 moods/scenes/textures/summary，不要只靠歌手名。\n` +
    `6) 第一首选最贴用户当下情境的；后续按听感路径排（BPM/能量平滑）。\n` +
    `7) BPM 差 >15 的两首不要相邻；energy 差 >0.25 的不要相邻。\n` +
    `8) reason ≤20 字写给自己看的笔记，可以略干。\n` +
    `9) reply 是宠物对用户的一句话回应（${intent.replyStyle}：` +
    `silent=空字符串 / short=≤8 字 / normal=≤16 字）。不要客服腔，不要感叹号。\n` +
    `\n严格输出 JSON：{"trackIds":[...],"reason":"...","reply":"..."}`;

  try {
    const raw = await ai.chat({
      system:
        "你是 Claudio 的歌单编排器。从给定候选池里挑歌，输出 JSON。不要扮演宠物，不要解释，不要编 id。",
      user,
      temperature: 0.5,
      maxTokens: 1500,
    });
    const obj = extractJsonObject(raw);
    if (!obj) return null;
    const parsed = PlannerOutputSchema.safeParse(obj);
    if (!parsed.success) {
      console.debug("[claudio] planner zod 失败", parsed.error.flatten());
      return null;
    }
    return parsed.data;
  } catch (e) {
    console.warn("[claudio] planner AI 失败", e);
    return null;
  }
}

function formatCandidateLine(c: RankedCandidate): string {
  const artist = c.track.artists.map((a) => a.name).join("/") || "未知";
  const bpm = c.analysis?.bpm ? Math.round(c.analysis.bpm) : "-";
  const energy = c.analysis
    ? ((c.analysis.introEnergy + c.analysis.outroEnergy) / 2).toFixed(2)
    : c.semanticProfile?.audioHints.energy?.toFixed(2) ?? "-";
  const sources = c.sources.join(",");
  const semantic = c.semanticProfile;
  const lang = semantic?.language.primary ?? "-";
  const region = semantic?.region.primary ?? "-";
  const genres = semantic?.style.genres.join(",") ?? "-";
  const moods = semantic?.vibe.moods.slice(0, 4).join(",") ?? "-";
  const scenes = semantic?.vibe.scenes.slice(0, 4).join(",") ?? "-";
  const summary = semantic?.summary ?? "-";
  return `${c.track.id}|${c.track.name}|${artist}|${lang}|${region}|${genres}|${moods}|${scenes}|${energy}|${bpm}|${sources}|${summary}`;
}

function extractJsonObject(raw: string): unknown {
  let s = raw.trim();
  if (s.startsWith("```")) {
    s = s.replace(/^```(?:json)?\s*/i, "").replace(/```$/, "").trim();
  }
  const first = s.indexOf("{");
  const last = s.lastIndexOf("}");
  if (first === -1 || last === -1 || last <= first) return null;
  try {
    return JSON.parse(s.slice(first, last + 1));
  } catch {
    return null;
  }
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
