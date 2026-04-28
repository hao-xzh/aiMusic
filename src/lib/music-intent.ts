"use client";

/**
 * Intent Parser —— 用户一句话 → 结构化"音乐意图"。
 *
 * 这一层从 pet-agent 的"聊天人格 + 选歌规划"巨型 prompt 里抽出来：
 * 让 LLM 只做一件事 —— 解析文本里的歌名/艺人/情绪/场景/能量提示。
 * 不再让它一边扮演宠物一边从 800 首库里选歌，那两件事互相冲突。
 *
 * 输出 schema 严格用 zod 校验，AI 任何字段返错都直接降级到 chat。
 */

import { z } from "zod";

import { parseJsonObjectLike } from "./ai-json";
import { ai } from "./tauri";

// ---------- Schema ----------

const TextHintsSchema = z.object({
  /** 用户提到的艺人名（中文/英文都可，可能带错别字 —— recall 端再做模糊匹配） */
  artists: z.array(z.string()).default([]),
  /** 用户提到的具体歌曲名 */
  tracks: z.array(z.string()).default([]),
  /** 专辑名 */
  albums: z.array(z.string()).default([]),
});

const MusicHintsSchema = z.object({
  /** 情绪标签：忧郁 / 治愈 / 兴奋 / 安静 / ... */
  moods: z.array(z.string()).default([]),
  /** 场景标签：开车 / 失眠 / 健身 / 学习 / 雨夜 / ... */
  scenes: z.array(z.string()).default([]),
  /** 流派标签：indie folk / city pop / 后摇 / r&b / ... */
  genres: z.array(z.string()).default([]),
  /** 用户不想要的（"别放电子""不要日语"） */
  avoid: z.array(z.string()).default([]),
  /** 能量水平大致诉求；不确定就 "any" */
  energy: z.enum(["low", "mid", "high", "any"]).default("any"),
  /** 接歌风格倾向，传给 mix-planner / transition-judge */
  transitionStyle: z
    .enum(["soft", "tight", "ambient", "party"])
    .default("soft"),
});

const HardConstraintsSchema = z.object({
  languages: z.array(z.string()).default([]),
  regions: z.array(z.string()).default([]),
  genres: z.array(z.string()).default([]),
  subGenres: z.array(z.string()).default([]),
  artists: z.array(z.string()).default([]),
  tracks: z.array(z.string()).default([]),
  vocalTypes: z.array(z.string()).default([]),
  excludeLanguages: z.array(z.string()).default([]),
  excludeRegions: z.array(z.string()).default([]),
  excludeGenres: z.array(z.string()).default([]),
  excludeArtists: z.array(z.string()).default([]),
  excludeVocalTypes: z.array(z.string()).default([]),
  excludeTags: z.array(z.string()).default([]),
});

const SoftPreferencesSchema = z.object({
  moods: z.array(z.string()).default([]),
  scenes: z.array(z.string()).default([]),
  textures: z.array(z.string()).default([]),
  energy: z
    .enum(["low", "mid_low", "mid", "mid_high", "high", "any"])
    .default("any"),
  tempoFeel: z.enum(["slow", "medium", "fast", "any"]).default("any"),
  eras: z.array(z.string()).default([]),
  qualityWords: z.array(z.string()).default([]),
});

const EmotionalGoalSchema = z.object({
  currentMood: z.string().optional(),
  targetMood: z.string().optional(),
  direction: z
    .enum(["match", "change", "calm_down", "cheer_up", "focus", "sleep"])
    .optional(),
});

const ReferencesSchema = z.object({
  artists: z.array(z.string()).default([]),
  tracks: z.array(z.string()).default([]),
  styles: z.array(z.string()).default([]),
  negativeReferences: z.array(z.string()).default([]),
});

const QueueIntentSchema = z.object({
  allowReorder: z.boolean().default(true),
  orderStyle: z
    .enum(["original", "smooth", "energy_up", "energy_down", "same_vibe", "sleep", "party"])
    .default("smooth"),
  transitionStyle: z
    .enum(["soft", "tight", "dj", "gapless", "auto"])
    .default("soft"),
});

export const MusicIntentSchema = z.object({
  /**
   * 主动作：
   *   - chat：纯聊天，不要切歌
   *   - play：要听歌（可能是泛泛"放点东西"，也可能是点名某首）
   */
  action: z.enum(["chat", "play", "recommend", "continue", "avoid", "adjust_queue", "explain"]),
  queryText: z.string().default(""),
  textHints: TextHintsSchema.default({
    artists: [],
    tracks: [],
    albums: [],
  }),
  musicHints: MusicHintsSchema.default({
    moods: [],
    scenes: [],
    genres: [],
    avoid: [],
    energy: "any",
    transitionStyle: "soft",
  }),
  hardConstraints: HardConstraintsSchema.default({}),
  softPreferences: SoftPreferencesSchema.default({}),
  emotionalGoal: EmotionalGoalSchema.default({}),
  references: ReferencesSchema.default({}),
  queueIntent: QueueIntentSchema.default({}),
  confidence: z.number().min(0).max(1).default(0.5),
  /** 希望的歌单长度。默认 30。专辑/精选场景可能更小，电台连播更大 */
  desiredCount: z.number().int().min(1).max(60).default(30),
  /** 宠物回复语气强度。silent = 不要回复 */
  replyStyle: z.enum(["silent", "short", "normal"]).default("short"),
});

export type MusicIntent = z.infer<typeof MusicIntentSchema>;
export type TextHints = z.infer<typeof TextHintsSchema>;
export type MusicHints = z.infer<typeof MusicHintsSchema>;
export type HardConstraints = z.infer<typeof HardConstraintsSchema>;
export type SoftPreferences = z.infer<typeof SoftPreferencesSchema>;

const CHAT_FALLBACK: MusicIntent = MusicIntentSchema.parse({ action: "chat" });

// ---------- 公共入口 ----------

export type IntentParseContext = {
  /** 当前正在播的曲目（如果有），让 AI 能理解"再来一首类似的"这种代词指代 */
  currentTrack?: { title: string; artist: string } | null;
  /** 时段描述，比如 "周一深夜 / 工作日午后"，让 LLM 能据此选 scene */
  timeContext?: string;
};

const INTENT_TIMEOUT_MS = 5000;

export async function parseMusicIntent(
  userText: string,
  ctx: IntentParseContext = {},
): Promise<MusicIntent> {
  const trimmed = userText.trim();
  if (!trimmed) return CHAT_FALLBACK;

  // 先做一个超快的本地短路：纯问候/感谢这种几乎不会是听歌请求，
  // 直接返回 chat，不浪费一次 AI 调用。
  if (looksLikeSmallTalk(trimmed)) return CHAT_FALLBACK;

  const aiPromise = callAi(trimmed, ctx);
  const timeoutPromise = new Promise<MusicIntent>((resolve) =>
    setTimeout(() => resolve(CHAT_FALLBACK), INTENT_TIMEOUT_MS),
  );

  try {
    return normalizeIntent(await Promise.race([aiPromise, timeoutPromise]), trimmed);
  } catch (e) {
    console.debug("[claudio] intent parse 失败，降级 chat", e);
    return normalizeIntent(CHAT_FALLBACK, trimmed);
  }
}

// ---------- 本地短路 ----------

/** 极简启发：单字"嗯/哦/好/谢谢/早"等 → 不调 AI 直接 chat */
function looksLikeSmallTalk(text: string): boolean {
  if (text.length <= 2) return true;
  const smalltalk = [
    "你好", "您好", "早上好", "晚安", "谢谢", "辛苦", "嗯嗯", "好的", "知道了",
    "在吗", "在不", "hi", "hello", "thanks", "thx",
  ];
  const lower = text.toLowerCase();
  return smalltalk.some((s) => lower === s.toLowerCase());
}

// ---------- AI 调用 ----------

async function callAi(
  userText: string,
  ctx: IntentParseContext,
): Promise<MusicIntent> {
  const ctxLines: string[] = [];
  if (ctx.currentTrack) {
    ctxLines.push(
      `当前在播：${ctx.currentTrack.title} — ${ctx.currentTrack.artist}`,
    );
  }
  if (ctx.timeContext) ctxLines.push(`时段：${ctx.timeContext}`);
  const ctxBlock = ctxLines.length > 0 ? `\n${ctxLines.join("\n")}\n` : "";

  const user =
    `用户对一只 AI 音乐宠物说：\n"${userText}"\n` +
    ctxBlock +
    `\n任务：把这句话解析成结构化的音乐意图 JSON，下游会用它从用户的本地音乐库里召回候选。\n` +
    `\n字段约定：\n` +
    `  action："play/recommend/continue/adjust_queue"（用户在表达想听歌或换歌）/ "chat"（纯闲聊）\n` +
    `  queryText：保留用户原句。\n` +
    `  textHints.artists/tracks/albums：句子里直接点名的（不要瞎补）\n` +
    `  musicHints.moods：情绪标签（忧郁/治愈/兴奋/安静/迷离…）\n` +
    `  musicHints.scenes：场景（开车/失眠/雨夜/晨跑…）\n` +
    `  musicHints.genres：流派（indie folk / city pop / 后摇 / r&b…）\n` +
    `  musicHints.avoid：用户明确说不要的\n` +
    `  musicHints.energy：low / mid / high / any\n` +
    `  musicHints.transitionStyle：soft（默认）/ tight / ambient / party\n` +
    `  desiredCount：1-60，默认 30\n` +
    `  replyStyle：silent / short / normal —— 看用户语气，命令式短句多半 short\n` +
    `  hardConstraints：硬条件。把用户明确说死的语言/地区/流派/人声/排除项放这里；不要局限于示例词，按用户原话抽象。\n` +
    `  softPreferences：软偏好。把氛围、质感、场景、情绪目标、审美词自由展开成短标签；可以生成开放标签，不必拘泥固定枚举。\n` +
    `  references：用户说"像某艺人/某首歌/某种感觉"时填参考对象和背后的风格，不要直接把参考艺人当唯一结果。\n` +
    `  queueIntent：顺序和接歌意图，默认 allowReorder=true/orderStyle=smooth/transitionStyle=soft。\n` +
    `\n严格只输出一个 JSON 对象，不要解释，不要 markdown。\n` +
    `示例：{"action":"play","queryText":"来点欧美 R&B，别太吵，适合晚上写代码","textHints":{"artists":[],"tracks":[],"albums":[]},` +
    `"musicHints":{"moods":["治愈"],"scenes":["雨夜"],"genres":[],"avoid":[],` +
    `"energy":"low","transitionStyle":"soft"},"hardConstraints":{"languages":["english"],"regions":["western"],"genres":["r&b"],"excludeTags":["noisy","aggressive"]},"softPreferences":{"scenes":["coding","night"],"moods":["focused","chill"],"energy":"mid_low"},"queueIntent":{"allowReorder":true,"orderStyle":"smooth","transitionStyle":"soft"},"desiredCount":30,"replyStyle":"short","confidence":0.85}`;

  const raw = await ai.chat({
    system:
      "你是 Claudio 的意图解析器。只输出 JSON。不要扮演宠物，不要解释，不要选歌，不要编 id。",
    user,
    temperature: 0.1,
    maxTokens: 500,
  });

  const json = extractJsonObject(raw);
  if (!json) return CHAT_FALLBACK;

  const result = MusicIntentSchema.safeParse(json);
  if (!result.success) {
    console.debug(
      "[claudio] intent zod 校验失败",
      result.error.flatten(),
      "raw=",
      raw,
    );
    return CHAT_FALLBACK;
  }
  return normalizeIntent(result.data, userText);
}

function normalizeIntent(intent: MusicIntent, queryText: string): MusicIntent {
  const q = queryText.toLowerCase();
  const hard = cloneHard(intent.hardConstraints);
  const soft = cloneSoft(intent.softPreferences);
  const refs = {
    artists: unique([...intent.references.artists]),
    tracks: unique([...intent.references.tracks]),
    styles: unique([...intent.references.styles]),
    negativeReferences: unique([...intent.references.negativeReferences]),
  };

  hard.artists = unique([...hard.artists, ...intent.textHints.artists]);
  hard.tracks = unique([...hard.tracks, ...intent.textHints.tracks]);
  hard.genres = unique([...hard.genres, ...intent.musicHints.genres]);
  soft.moods = unique([...soft.moods, ...intent.musicHints.moods]);
  soft.scenes = unique([...soft.scenes, ...intent.musicHints.scenes]);
  soft.energy = mapLegacyEnergy(intent.musicHints.energy, soft.energy);
  soft.qualityWords = unique([...soft.qualityWords, ...extractOpenDescriptors(queryText)]);

  // 下面只做 LLM 解析失败/漏字段时的安全补丁，不作为主理解路径。
  if (/欧美|western|euro|america|american|英美/.test(q)) hard.regions = add(hard.regions, "western");
  if (/英文|英语|english|欧美/.test(q)) hard.languages = add(hard.languages, "english");
  if (/国语|中文|华语|mandarin/.test(q)) hard.languages = add(hard.languages, "mandarin");
  if (/粤语|cantonese/.test(q)) hard.languages = add(hard.languages, "cantonese");
  if (/日语|日本|japanese/.test(q)) hard.languages = add(hard.languages, "japanese");
  if (/韩语|韩国|korean/.test(q)) hard.languages = add(hard.languages, "korean");
  if (/r&b|rnb|节奏布鲁斯|neo soul|neosoul/i.test(q)) hard.genres = add(hard.genres, "r&b");
  if (/soul|灵魂乐/i.test(q)) hard.genres = add(hard.genres, "soul");
  if (/hip.?hop|rap|说唱/i.test(q)) hard.genres = add(hard.genres, "hip-hop");
  if (/女声|female|女歌手/.test(q)) hard.vocalTypes = add(hard.vocalTypes, "female");
  if (/男声|male|男歌手/.test(q)) hard.vocalTypes = add(hard.vocalTypes, "male");

  if (/不要国语|别放国语|不要中文|别放中文/.test(q)) hard.excludeLanguages = add(hard.excludeLanguages, "mandarin");
  if (/不要粤语|别放粤语/.test(q)) hard.excludeLanguages = add(hard.excludeLanguages, "cantonese");
  if (/不要日语|别放日语/.test(q)) hard.excludeLanguages = add(hard.excludeLanguages, "japanese");
  if (/不要说唱|别放说唱|不要rap|别rap/i.test(q)) hard.excludeGenres = add(hard.excludeGenres, "hip-hop");
  if (/不要女声|别放女声/.test(q)) hard.excludeVocalTypes = add(hard.excludeVocalTypes, "female");
  if (/不要男声|别放男声/.test(q)) hard.excludeVocalTypes = add(hard.excludeVocalTypes, "male");
  if (/不要太吵|别太吵|不吵|安静|别炸|不要炸/.test(q)) {
    hard.excludeTags = unique([...hard.excludeTags, "noisy", "aggressive", "party"]);
    soft.energy = soft.energy === "any" ? "mid_low" : soft.energy;
  }
  if (/土嗨|抖音神曲|口水/.test(q)) hard.excludeTags = unique([...hard.excludeTags, "tiktok", "commercial", "cheesy"]);
  if (/苦情|太丧|太emo/.test(q)) hard.excludeTags = add(hard.excludeTags, "overly sad");

  if (/写代码|coding|code|编程/.test(q)) {
    soft.scenes = unique([...soft.scenes, "coding", "focus"]);
    soft.moods = unique([...soft.moods, "focused", "calm"]);
  }
  if (/晚上|夜里|深夜|夜晚|night/.test(q)) soft.scenes = add(soft.scenes, "night");
  if (/开车|drive|driving/.test(q)) soft.scenes = add(soft.scenes, "driving");
  if (/下雨|雨天|rain/.test(q)) {
    soft.scenes = unique([...soft.scenes, "rainy day", "night"]);
    soft.moods = unique([...soft.moods, "melancholic", "calm", "atmospheric"]);
  }
  if (/慢歌|慢一点|slow/.test(q)) soft.tempoFeel = "slow";
  if (/越来越带感|递进|越放越/.test(q)) intent.queueIntent.orderStyle = "energy_up";
  if (/睡觉|睡眠|入睡/.test(q)) {
    intent.queueIntent.orderStyle = "sleep";
    soft.energy = "low";
    soft.scenes = add(soft.scenes, "sleep");
  }

  return {
    ...intent,
    action: intent.action === "chat" ? "chat" : "play",
    queryText,
    hardConstraints: hard,
    softPreferences: soft,
    references: refs,
    confidence: intent.confidence || 0.65,
  };
}

function cloneHard(h: HardConstraints): HardConstraints {
  return {
    languages: [...h.languages],
    regions: [...h.regions],
    genres: [...h.genres],
    subGenres: [...h.subGenres],
    artists: [...h.artists],
    tracks: [...h.tracks],
    vocalTypes: [...h.vocalTypes],
    excludeLanguages: [...h.excludeLanguages],
    excludeRegions: [...h.excludeRegions],
    excludeGenres: [...h.excludeGenres],
    excludeArtists: [...h.excludeArtists],
    excludeVocalTypes: [...h.excludeVocalTypes],
    excludeTags: [...h.excludeTags],
  };
}

function cloneSoft(s: SoftPreferences): SoftPreferences {
  return {
    moods: [...s.moods],
    scenes: [...s.scenes],
    textures: [...s.textures],
    energy: s.energy,
    tempoFeel: s.tempoFeel,
    eras: [...s.eras],
    qualityWords: [...s.qualityWords],
  };
}

function mapLegacyEnergy(legacy: MusicHints["energy"], current: SoftPreferences["energy"]): SoftPreferences["energy"] {
  if (current !== "any") return current;
  return legacy === "low" ? "mid_low" : legacy;
}

function add(arr: string[], value: string): string[] {
  return arr.includes(value) ? arr : [...arr, value];
}

function unique(arr: string[]): string[] {
  return [...new Set(arr.map((s) => s.trim()).filter(Boolean))];
}

function extractOpenDescriptors(queryText: string): string[] {
  const cleaned = queryText
    .toLowerCase()
    .replace(/[，。！？,.!?]/g, " ")
    .replace(/来点|听点|想听|给我|播放|不要|别放/g, " ");
  return cleaned
    .split(/\s+/)
    .map((s) => s.trim())
    .filter((s) => s.length >= 2)
    .slice(0, 12);
}

function extractJsonObject(raw: string): unknown {
  return parseJsonObjectLike(raw);
}
