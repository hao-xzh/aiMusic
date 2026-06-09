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

import { ai, cache, netease } from "./tauri";
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
import { recallCandidates, type Candidate } from "./candidate-recall";
import { rankCandidates } from "./candidate-ranker";
import { readRecentPlayContext } from "./behavior-log";
import {
  logRecommendations,
  readRecentRecommendationContext,
  type RecentRecommendationContext,
} from "./recommendation-log";
import { discoverBeyondLibrary } from "./discovery";
import {
  getSemanticProfiles,
  type TrackSemanticProfile,
} from "./track-semantic-profile";
import { loadAnalysis, type TrackAnalysis } from "./audio-analysis";
import {
  dedupeTrackInfos,
  normalizeTitle,
  queryAsksForSpecificVersion,
  songKey,
} from "./track-dedupe";

export type ChatMessage = {
  role: "user" | "assistant";
  text: string;
  /** 如果这条 assistant 消息触发了播放，附上播放计划 */
  play?: PlayPlan;
};

export type PlayPlan = {
  trackIds: number[];
  /** 内部播放计划摘要；极简 UI 不展示。 */
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
// 一天最多触发一次；这里独立成一个缓存条目，反复几天命中，省一点是一点。
const GREETING_SYSTEM = `你是 Pipo —— TA 熟到不用客气的音乐宠物。
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
    console.debug("[pipo] pet greeting AI 失败,用兜底", e);
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

// ---------- 兼容旧入口：播放链路保持沉默 ----------

export type TrackCommentInput = {
  track: {
    id: number;
    title: string;
    artist: string;
    /** 旧版单曲点评参数；当前播放链路不再自动请求 AI。 */
    moods?: string[];
    scenes?: string[];
    genres?: string[];
    summary?: string;
  };
  /** 旧版单曲点评参数；保留给历史调用兼容。 */
  userContext?: string;
  /** 旧版单曲点评参数；保留给历史调用兼容。 */
  positionInQueue?: number;
  /** 旧版单曲点评参数；保留给历史调用兼容。 */
  previousTrack?: { title: string; artist: string };
};

export async function commentOnTrack(input: TrackCommentInput): Promise<string> {
  void input;
  return "";
}

// ---------- 主聊天入口 ----------

export type AgentInput = {
  history: ChatMessage[];
  userText: string;
  /** 系统主动规划时不把内部 prompt 记成用户原话 */
  skipMemory?: boolean;
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

/**
 * 续杯式推荐源 —— 队列接近播完时,player-state 调一发拿下一批同口味的歌。
 *
 * - 闭包内部维护 consumed 集合(songKey 粒度),保证跨多次 fetchMore 不重复。
 *   初始那一批的 songKey 也已经预填进 consumed,所以续杯永远不会返回同一首歌
 *   的另一个版本。
 * - excludeIds 是当前播放队列里已经存在的 neteaseId —— 防御性兜底,处理用户
 *   手动加歌之类的场景。
 * - 返回 [] 表示这一支已经榨干,player-state 看到空就摘掉这个 source,
 *   接着按"播完了"自然结束(不再循环回第一首,这是用户明确要的体验)。
 */
export type ContinuousSource = {
  fetchMore: (excludeIds: Set<number>) => Promise<TrackInfo[]>;
};

export type AgentResponse = {
  /** 给用户看的文本部分（剥掉 PLAY 块） */
  text: string;
  /** 如果有播放指令 */
  play: PlayPlan | null;
  /** 若 play 有值，配合返回去重 + 校验过的真 TrackInfo[]（按 trackIds 顺序） */
  resolvedTracks: TrackInfo[];
  /** insert = 单曲插队；replace = 换整条队列 */
  queueAction?: "insert" | "replace";
  /** 续杯式推荐：队列接近末尾时由 player-state 调用，返回下一批同口味的歌 */
  continuous?: ContinuousSource | null;
};

/**
 * 主入口。
 *
 * 新流水线（v3）：
 *
 *   userText
 *     │
 *     ▼  parseMusicIntent —— 一次 LLM 调用同时产出 { reply, action, intent...}
 *     │  reply 是 Pipo 以人格说出来的话（永远要有）
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
  // 让 Pipo 不再每次启动都失忆。
  const memoryDigest = await getMemoryDigest().catch(() => "");

  // 用户说话先写入跨 session 记忆(在 AI 调用前 fire-and-forget,
  // 这样如果用户连续发话,后面的 AI 调用看得到前面的话作为上下文)
  if (!input.skipMemory) {
    void recordUserUtterance(input.userText);
  }

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
  const namedTrackHints = getNamedTrackHints(intent);
  const hasNamedTrackHints = namedTrackHints.length > 0;
  const preserveVersions = queryAsksForSpecificVersion(intent);
  const isInsert = intent.queueIntent.action === "insert";
  const pinnedLocal = hasNamedTrackHints ? resolvePinnedTracks(intent, library) : [];

  // 用户明确点名某首歌时，insert 不走泛推荐：先用户歌单，本地没覆盖才搜库外。
  if (isInsert && hasNamedTrackHints) {
    const pinnedOnline = await resolvePinnedFromOnline(intent, pinnedLocal);
    const toInsert = mergeUniqueTrackInfos(
      [...pinnedLocal, ...pinnedOnline],
      preserveVersions,
    ).slice(0, 1);
    if (toInsert.length === 0) {
      void writeDebugRecord({
        userText: input.userText,
        intent,
        reply: replyText,
        reason: "named insert not found",
        finalTrackIds: [],
      });
      return {
        text: `${replyText}（这首我没找到，换个名字？）`,
        play: null,
        resolvedTracks: [],
        queueAction: "insert",
      };
    }
    const picked = toInsert[0];
    void logRecommendations([picked], "pet");
    void writeDebugRecord({
      userText: input.userText,
      intent,
      reply: replyText,
      reason: "named insert local-first",
      finalTrackIds: [picked.id],
    });
    return {
      text: replyText,
      play: {
        trackIds: [picked.id],
        reason: "insert",
      },
      resolvedTracks: [picked],
      queueAction: "insert",
      continuous: null,
    };
  }

  // 召回 + 本地打分
  // 并行做三件事:本地 recall / 行为日志 / 推荐日志 —— 三路都涉及 cache I/O,
  // 串行没意义。
  const [candidatesFromLibrary, recentPlay, recentRecommendation] = await Promise.all([
    recallCandidates({
      intent,
      library,
      currentTrack: currentTrackInfo,
      limit: 240,
    }),
    readRecentPlayContext().catch(() => undefined),
    readRecentRecommendationContext().catch(() => undefined),
  ]);

  // 库外 discovery 注入:满足触发条件时,并发跑一次轻量 discovery 把库外冷门
  // 候选混进主流。修两个痛点 —— "歌手有更多歌不继续推"(库外补)、"小众冷门
  // 也想要"(seed 出冷门)。
  const discoveryHits = await maybeDiscoverExtra({
    intent,
    library,
    candidateCount: candidatesFromLibrary.length,
    recentRecommendation,
  });
  const pinnedOnline = hasNamedTrackHints
    ? await resolvePinnedFromOnline(intent, pinnedLocal)
    : [];
  const pinnedTracks = mergeUniqueTrackInfos(
    [...pinnedLocal, ...pinnedOnline],
    preserveVersions,
  );
  const candidates = [...candidatesFromLibrary, ...discoveryHits];

  if (candidates.length === 0 && pinnedTracks.length === 0) {
    return {
      text: `${replyText}（这次库里真没贴的，换个说法？）`,
      play: null,
      resolvedTracks: [],
    };
  }
  // topN 240 ≈ recall 上限,基本不裁。
  // 续杯模式需要尽可能大的池子（reservoir 喂得越饱,fetchMore 才有得续杯）；
  // 即使没续杯,这里多收点候选给 dedupe 之后也只用前 wanted 首,代价只是
  // 一次本地排序，可以接受。
  const ranked = rankCandidates(candidates, intent, {
    topN: 240,
    recentPlay,
    recentRecommendation,
  });
  if (ranked.length === 0 && pinnedTracks.length === 0) {
    return {
      text: `${replyText}（符合得太少，换个说法？）`,
      play: null,
      resolvedTracks: [],
    };
  }

  // 直接拿排序后的前 N 首 —— 不再走第二次 LLM 编排。
  //
  // 续杯式推荐：把整个 ranked top（~100 首）都先 dedupe 一遍，初始批切前 N 首给
  // player 立刻播，剩下的留在闭包里给 fetchMore 慢慢喂。这样不论 fetchMore 调
  // 多少次都不会跟初始批撞同一首歌的另一版本（VIP 重唱 / Live / Remix 等），
  // 因为 dedupe 是在切批之前一次性做完的。
  const allRankedTracks = ranked.map((c) => c.track);
  // bucketMap:从 ranker 阶段抽出每首歌的 bucket(0-3 分位档位),给续杯端跨段
  // 抽样用,这样 fetchMore 8 首里有不同档位的歌混合,而不是把第 0 段抽完再抽第 1 段。
  const bucketMap = new Map<number, 0 | 1 | 2 | 3>();
  for (const r of ranked) bucketMap.set(r.track.id, r.bucket);
  const rankedBase = preserveVersions
    ? allRankedTracks
    : dedupeTrackInfos(allRankedTracks);
  const dedupedBase = pinnedTracks.length > 0
    ? mergeUniqueTrackInfos([...pinnedTracks, ...rankedBase], preserveVersions)
    : rankedBase;

  if (isInsert) {
    const picked = dedupedBase[0] ?? ranked[0]?.track;
    if (!picked) {
      return {
        text: `${replyText}（这首我没在库里抓准。）`,
        play: null,
        resolvedTracks: [],
        queueAction: "insert",
      };
    }
    void logRecommendations([picked], "pet");
    void writeDebugRecord({
      userText: input.userText,
      intent,
      candidateCount: candidates.length,
      rankedCount: ranked.length,
      finalTrackIds: [picked.id],
      reply: replyText,
      reason: "queueAction: insert",
    });
    return {
      text: replyText,
      play: {
        trackIds: [picked.id],
        reason: "insert",
      },
      resolvedTracks: [picked],
      queueAction: "insert",
      continuous: null,
    };
  }

  const dedupedAll = diversifyTrackInfos(dedupedBase, intent);

  // 初始批刻意压短（≤15 首）—— 续杯模式下队列本来就会随播随长,
  // 留尽量多的歌在 reservoir 里,fetchMore 才有得喂。
  const INITIAL_BATCH_CAP = 15;
  const wanted = Math.max(
    10,
    Math.min(intent.desiredCount, dedupedAll.length, INITIAL_BATCH_CAP),
  );
  const dedupedResolved = dedupedAll.slice(0, wanted);
  const reservoir = dedupedAll.slice(wanted);

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

  // 续杯式推荐源 —— 一律开启,反正用户说了"根据口味一直推荐"。
  // 即使 reservoir 空（库小 / 过滤太紧）,closure 内部还有一道 refill：放宽
  // hard exclude 重新跑一次 recall，把语义边邻的歌捞进来,避免循环回放。
  const continuous: ContinuousSource = buildContinuousSource({
    initialBatch: smoothed,
    reservoir,
    intent,
    library,
    currentTrack: currentTrackInfo,
    bucketMap,
  });

  void logRecommendations(smoothed, "pet");

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
    queueAction: "replace",
    continuous,
  };
}

/**
 * 构造一个 ContinuousSource。
 *
 * 双层池子：
 *   - reservoir：同一次 chat() 里 ranked top 切去初始批后剩下的歌。优先消费,
 *     口味跟初始批最贴。
 *   - refilled：reservoir 见底后,放宽 hard exclude 再跑一次 recall + rank,把
 *     语义边邻的歌捞进来。只 refill 一次,避免无限拉新（库就那么大,反复跑
 *     只会得到更差的同一批结果）。
 *
 * 不重复保证：
 *   - consumed 集合（songKey 粒度）跨调用累积,初始批的 songKey 也预填进去 ——
 *     无论调多少次 fetchMore 都不会推同一首歌的另一版本。
 *   - excludeIds 是 player 当前队列的 neteaseId 集合,作为再一道 id 级兜底
 *     （防御性,处理用户手动加歌之类的场景）。
 *
 * 两层都掏空后返回 []，player-state 摘掉 source 并自然结束（不再循环回第一
 * 首）—— 这是用户明确要求的体验。
 */
function buildContinuousSource(args: {
  initialBatch: TrackInfo[];
  reservoir: TrackInfo[];
  intent: MusicIntent;
  library: TrackInfo[];
  currentTrack: TrackInfo | null;
  /** 每首歌的 bucket(0-3 分位)。drainPool 按 bucket 跨段抽样,保持混合。 */
  bucketMap: Map<number, 0 | 1 | 2 | 3>;
}): ContinuousSource {
  const { initialBatch, reservoir, intent, library, currentTrack, bucketMap } = args;
  const consumed = new Set<string>(initialBatch.map(songKey));
  let pool: TrackInfo[] = reservoir;
  let refillTried = false;

  // 跨 bucket 抽样:8 首 = 4(top) + 2(2nd) + 1(3rd) + 1(4th)。
  // 这样续杯每一批都是"主调 + 边缘"混合,不会先把第 0 段抽完才碰冷门候选。
  // bucket 不在 map 里的(refill 之后的新候选)统一当 bucket 1 处理。
  const BUCKET_QUOTA: Record<0 | 1 | 2 | 3, number> = { 0: 4, 1: 2, 2: 1, 3: 1 };

  const drainPool = (excludeIds: Set<number>, want: number): TrackInfo[] => {
    const quotas: Record<0 | 1 | 2 | 3, number> = { ...BUCKET_QUOTA };
    // 按 want 比例缩放(默认设定按 want=8 算的)
    const scale = want / 8;
    quotas[0] = Math.max(1, Math.round(quotas[0] * scale));
    quotas[1] = Math.max(0, Math.round(quotas[1] * scale));
    quotas[2] = Math.max(0, Math.round(quotas[2] * scale));
    quotas[3] = Math.max(0, Math.round(quotas[3] * scale));

    const out: TrackInfo[] = [];
    // 先按 bucket 配额走一遍
    for (const t of pool) {
      if (out.length >= want) break;
      const k = songKey(t);
      if (consumed.has(k)) continue;
      if (excludeIds.has(t.id)) continue;
      const b = bucketMap.get(t.id) ?? 1;
      if (quotas[b] <= 0) continue;
      quotas[b]--;
      consumed.add(k);
      out.push(t);
    }
    // 配额没用满(某个 bucket 已枯竭)的话,再扫一遍补齐
    if (out.length < want) {
      for (const t of pool) {
        if (out.length >= want) break;
        const k = songKey(t);
        if (consumed.has(k)) continue;
        if (excludeIds.has(t.id)) continue;
        consumed.add(k);
        out.push(t);
      }
    }
    return out;
  };

  // 放宽 hard exclude 重跑一次 recall + rank —— reservoir 见底时调一次。
  // 不动 softPreferences / queueIntent,口味基底跟原意图一致。
  const refillPool = async (): Promise<void> => {
    if (refillTried) return;
    refillTried = true;
    try {
      const relaxedIntent: MusicIntent = {
        ...intent,
        hardConstraints: {
          ...intent.hardConstraints,
          excludeTags: [],
          excludeGenres: [],
          excludeLanguages: [],
        },
      };
      const candidates = await recallCandidates({
        intent: relaxedIntent,
        library,
        currentTrack,
        limit: 240,
      });
      const ranked = rankCandidates(candidates, relaxedIntent, { topN: 240 });
      // refill 阶段的 bucket 也写进同一个 map,让续杯端继续按段抽样
      for (const r of ranked) bucketMap.set(r.track.id, r.bucket);
      const fresh = ranked.map((c) => c.track);
      // refilled pool 只放还没被 consume 的,fetchMore 内 drainPool 还会再过一遍
      pool = fresh.filter((t) => !consumed.has(songKey(t)));
      console.debug("[pipo] 续杯 refill", { freshCount: pool.length });
    } catch (e) {
      console.debug("[pipo] 续杯 refill 失败", e);
      pool = [];
    }
  };

  const fetchMore = async (excludeIds: Set<number>): Promise<TrackInfo[]> => {
    const want = 8;
    let out = drainPool(excludeIds, want);
    if (out.length === 0) {
      await refillPool();
      out = drainPool(excludeIds, want);
    }
    return out;
  };

  return { fetchMore };
}

// ---------- 库外 discovery 注入 ----------

const DISCOVERY_TRIGGER_WORDS = [
  "随便", "随意", "探索", "挖", "挖宝", "小众", "冷门", "没听过", "陌生",
  "新东西", "新歌", "新一点", "新的", "discover", "explore",
];

/**
 * 判断当前一次 chat 是否值得跑一次库外 discovery。
 *
 * 触发条件(任一):
 *   1) 库内候选 < 20 —— 召回太少,补几首画像匹配的库外歌凑数
 *   2) intent.queryText / userText 含探索关键词 —— 用户明示想发现新东西
 *
 * 不触发的情况:
 *   - 点名了某艺人 / 具体歌名 / 专辑(artistLocked) —— 该听库内匹配的,别岔出去
 *   - intent.queueIntent.action === "insert" —— 单曲插队,不要库外的
 *   - 一般的"放点歌""推荐一下" —— 库内排序足够,别引入 2-3 秒延迟
 *
 * 决策动机:用户要的是"贴口味",不是"非冷门不可"。discovery 是兜底/明示触发,
 * 不是默认副菜。每条对话都跑 discovery 会拖慢响应且引入不必要的库外噪声。
 */
function shouldEnableDiscovery(
  intent: MusicIntent,
  candidateCount: number,
): boolean {
  if (intent.queueIntent.action === "insert") return false;
  if (intent.hardConstraints.artists.length > 0) return false;
  if (intent.textHints.artists.length > 0) return false;
  if (intent.textHints.tracks.length > 0 || intent.textHints.albums.length > 0) {
    return false;
  }

  if (candidateCount < 20) return true;
  const q = (intent.queryText ?? "").toLowerCase();
  if (DISCOVERY_TRIGGER_WORDS.some((w) => q.includes(w))) return true;
  return false;
}

/**
 * 跑一次 discovery,把 picks 转成 Candidate[] 注入主流。
 *
 * 失败容错:任何环节挂(画像缺 / 网络问题 / AI 解析失败)就静默返回空数组,
 * 库内候选照走 —— discovery 是锦上添花,不能拖主流程。
 */
async function maybeDiscoverExtra(args: {
  intent: MusicIntent;
  library: TrackInfo[];
  candidateCount: number;
  recentRecommendation: RecentRecommendationContext | undefined;
}): Promise<Candidate[]> {
  const { intent, library, candidateCount, recentRecommendation } = args;
  if (!shouldEnableDiscovery(intent, candidateCount)) return [];

  const profile = await loadTasteProfile().catch(() => null);
  if (!profile) return []; // 没画像就跑不了 seed

  // 最近 24h 内推得最多的几个艺人,作为 avoidArtists 传进去
  const avoidArtists: string[] = [];
  if (recentRecommendation && recentRecommendation.last24hArtistCounts.size > 0) {
    const sorted = [...recentRecommendation.last24hArtistCounts.entries()]
      .sort((a, b) => b[1] - a[1])
      .filter(([, count]) => count >= 3); // 至少推过 3 次才算"反复推"
    for (const [key] of sorted.slice(0, 5)) avoidArtists.push(key);
  }

  const ownedIds = new Set(library.map((t) => t.id));
  try {
    const picks = await discoverBeyondLibrary(profile, ownedIds, {
      seedCount: 4,
      perSeedLimit: 8,
      finalCount: 10,
      intentHint: {
        artists: intent.textHints.artists,
        moods: intent.musicHints.moods,
        genres: intent.musicHints.genres,
      },
      avoidArtists,
    });
    if (picks.length === 0) return [];

    // 把 discovery 的 TrackInfo 算上 semantic / analysis(本地缓存里有就用,没有就 null)
    const tracks = picks.map((p) => p.track);
    const [semanticMap, analysisMap] = await Promise.all([
      getSemanticProfiles(tracks, { includeRuleBasedFallback: true }).catch(
        () => new Map<number, TrackSemanticProfile>(),
      ),
      loadAnalysesBulk(tracks),
    ]);

    return tracks.map<Candidate>((track) => ({
      track,
      analysis: analysisMap.get(track.id) ?? null,
      semanticProfile: semanticMap.get(track.id) ?? null,
      sources: ["explore", "profile_tags"],
      // discovery 的 AI 按"画像匹配度"已 rerank,但毕竟是库外候选,不该跟本地高分歌
      // 完全平起平坐。0.7/0.7 让 discovery 的歌进得了主流但通常排在本地匹配后面;
      // 只有本地池薄 / 用户明示探索 时才会有 discovery 的歌排到前面。
      sourceScores: { explore: 0.70, profile_tags: 0.70 },
    }));
  } catch (e) {
    console.debug("[pipo] discovery 注入失败", e);
    return [];
  }
}

async function loadAnalysesBulk(tracks: TrackInfo[]): Promise<Map<number, TrackAnalysis>> {
  const out = new Map<number, TrackAnalysis>();
  await Promise.all(
    tracks.map(async (t) => {
      const a = await loadAnalysis(t.id).catch(() => null);
      if (a) out.set(t.id, a);
    }),
  );
  return out;
}

// ---------- 命名歌曲优先级 ----------

function getNamedTrackHints(intent: MusicIntent): string[] {
  const out: string[] = [];
  const seen = new Set<string>();
  for (const raw of [...intent.hardConstraints.tracks, ...intent.textHints.tracks]) {
    const title = raw.trim();
    const key = normalizeForMatch(title);
    if (!title || !key || seen.has(key)) continue;
    seen.add(key);
    out.push(title);
  }
  return out;
}

function getArtistHints(intent: MusicIntent): string[] {
  const out: string[] = [];
  const seen = new Set<string>();
  for (const raw of [...intent.hardConstraints.artists, ...intent.textHints.artists]) {
    const artist = raw.trim();
    const key = normalizeForMatch(artist);
    if (!artist || !key || seen.has(key)) continue;
    seen.add(key);
    out.push(artist);
  }
  return out;
}

function resolvePinnedTracks(intent: MusicIntent, library: TrackInfo[]): TrackInfo[] {
  if (library.length === 0) return [];
  const titles = getNamedTrackHints(intent);
  if (titles.length === 0) return [];
  const artistKeys = new Set(getArtistHints(intent).map(normalizeForMatch).filter(Boolean));

  const out = new Map<string, TrackInfo>();
  for (const rawTitle of titles) {
    const titleKey = normalizeForMatch(rawTitle);
    if (!titleKey) continue;
    const artistLocked = artistKeys.size > 0;
    const exact: TrackInfo[] = [];
    const partial: TrackInfo[] = [];
    for (const track of library) {
      if (artistLocked && !trackMatchesAnyArtist(track, artistKeys)) continue;
      const trackTitle = normalizeForMatch(track.name);
      if (!trackTitle) continue;
      if (trackTitle === titleKey) exact.push(track);
      else if (trackTitle.includes(titleKey) || titleKey.includes(trackTitle)) partial.push(track);
    }
    const pick = [...exact, ...partial].sort((a, b) => trackVariantWeight(a.name) - trackVariantWeight(b.name))[0];
    if (!pick) continue;
    const key = songKey(pick);
    if (!out.has(key)) out.set(key, pick);
  }
  return [...out.values()];
}

async function resolvePinnedFromOnline(
  intent: MusicIntent,
  alreadyPinned: TrackInfo[],
): Promise<TrackInfo[]> {
  const missingTitles = getNamedTrackHints(intent).filter((title) =>
    !alreadyPinned.some((track) => titleMatchesRequest(track.name, title)),
  );
  if (missingTitles.length === 0) return [];

  const artistHints = getArtistHints(intent);
  const singleArtistHint = artistHints.length === 1 ? artistHints[0] : "";
  const singleArtistKey = normalizeForMatch(singleArtistHint);
  const picks = await Promise.all(
    missingTitles.map(async (title) => {
      const query = singleArtistHint ? `${title} ${singleArtistHint}` : title;
      const hits = await netease.search(query, 5).catch(() => []);
      return hits
        .filter((track) => titleMatchesRequest(track.name, title))
        .filter((track) => !singleArtistKey || trackMatchesAnyArtist(track, new Set([singleArtistKey])))
        .sort((a, b) => trackVariantWeight(a.name) - trackVariantWeight(b.name))[0] ?? null;
    }),
  );
  return picks.filter((track): track is TrackInfo => Boolean(track));
}

function mergeUniqueTrackInfos(items: TrackInfo[], preserveVersions: boolean): TrackInfo[] {
  const seen = new Set<string>();
  const out: TrackInfo[] = [];
  for (const track of items) {
    const key = preserveVersions ? String(track.id) : songKey(track);
    if (seen.has(key)) continue;
    seen.add(key);
    out.push(track);
  }
  return out;
}

function titleMatchesRequest(actualTitle: string, requestedTitle: string): boolean {
  const actual = normalizeForMatch(actualTitle);
  const requested = normalizeForMatch(requestedTitle);
  return Boolean(
    actual &&
      requested &&
      (actual === requested || actual.includes(requested) || requested.includes(actual)),
  );
}

function trackMatchesAnyArtist(track: TrackInfo, artistKeys: Set<string>): boolean {
  return track.artists.some((artist) => {
    const key = normalizeForMatch(artist.name);
    if (!key) return false;
    for (const wanted of artistKeys) {
      if (key === wanted || key.includes(wanted) || wanted.includes(key)) return true;
    }
    return false;
  });
}

function trackVariantWeight(title: string): number {
  const lower = title.toLowerCase();
  let weight = title.length;
  if (lower.includes("live") || lower.includes("现场") || lower.includes("演唱会")) weight += 1000;
  if (lower.includes("伴奏") || lower.includes("instrumental") || lower.includes("karaoke")) weight += 1000;
  if (lower.includes("cover") || lower.includes("翻唱")) weight += 800;
  if (lower.includes("remix") || lower.includes("混音")) weight += 700;
  if (lower.includes("acoustic") || lower.includes("unplugged")) weight += 600;
  if (lower.includes("demo")) weight += 500;
  if (lower.includes("remaster") || lower.includes("重制")) weight += 300;
  return weight;
}

function normalizeForMatch(value: string): string {
  return normalizeTitle(value).replace(/[\s'"`·・\-－—_,，。.、!?！？()（）\[\]【】《》<>&\/]+/g, "");
}

function diversifyTrackInfos(items: TrackInfo[], intent: MusicIntent): TrackInfo[] {
  // 用户明确点艺人时,同艺人扎堆不是问题;否则做"全池 artist 多样性"。
  //
  // 旧版本:只 cap 同 artist ≤ 2,溢出的拼在尾部 —— 初始批 15 首是混合,
  // 但 reservoir 里仍然全是 Taylor,fetchMore 续杯就又是 Taylor 一统天下。
  //
  // 新版本:
  //   1) cap 按池大小动态算 —— 240 首池 → max(2, 240/8) = 30 首/艺人
  //   2) 溢出的按 artist 轮转重排,而不是一锅塞尾巴,保证 reservoir 里也是
  //      混合;fetchMore 8 首拿到的是 8 个不同艺人,而不是 8 首 Taylor。
  const artistLocked =
    intent.hardConstraints.artists.length > 0 ||
    intent.textHints.artists.length > 0;
  if (artistLocked) return items;

  const seenSongs = new Set<string>();
  const artistCounts = new Map<string, number>();
  const cap = Math.max(2, Math.floor(items.length / 8));
  const primary: TrackInfo[] = [];
  const overflowByArtist = new Map<string, TrackInfo[]>();

  for (const track of items) {
    const sk = songKey(track);
    if (seenSongs.has(sk)) continue;
    seenSongs.add(sk);

    const ak = normalizeArtistKey(track);
    const count = artistCounts.get(ak) ?? 0;
    if (ak && count >= cap) {
      const bucket = overflowByArtist.get(ak) ?? [];
      bucket.push(track);
      overflowByArtist.set(ak, bucket);
      continue;
    }
    if (ak) artistCounts.set(ak, count + 1);
    primary.push(track);
  }

  // 溢出按 artist 轮转(round-robin): 先每个艺人各拿 1 首,再各拿 1 首...
  const overflowRoundRobin: TrackInfo[] = [];
  const buckets = Array.from(overflowByArtist.values());
  let consumed = true;
  while (consumed) {
    consumed = false;
    for (const b of buckets) {
      const next = b.shift();
      if (next) {
        overflowRoundRobin.push(next);
        consumed = true;
      }
    }
  }

  return [...primary, ...overflowRoundRobin];
}

function normalizeArtistKey(track: TrackInfo): string {
  return (track.artists[0]?.name ?? "")
    .toLowerCase()
    .replace(/[\s'"`·・\-－—_,，。.、!?！？]+/g, "");
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
