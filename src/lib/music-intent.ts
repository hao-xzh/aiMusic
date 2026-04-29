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
  /**
   * Claudio 自己说的一句话 —— 永远要有，无论 action 是 chat 还是 play。
   * 这句是用户在 UI 上看到的回复。要有人格、幽默抽象、贴当下的语境。
   * 不要客服腔、不要鸡汤、不要解释"我为什么放这些"。
   * 例：「打工是吧。来点能把电量充满的。」「老天爷在哭。我换情绪。」「行，点火。」
   */
  reply: z.string().min(1).max(80).default("嗯。"),
});

export type MusicIntent = z.infer<typeof MusicIntentSchema>;
export type TextHints = z.infer<typeof TextHintsSchema>;
export type MusicHints = z.infer<typeof MusicHintsSchema>;
export type HardConstraints = z.infer<typeof HardConstraintsSchema>;
export type SoftPreferences = z.infer<typeof SoftPreferencesSchema>;

// ---------- 公共入口 ----------

export type IntentParseContext = {
  /** 当前正在播的曲目（如果有），让 AI 能理解"再来一首类似的"这种代词指代 */
  currentTrack?: { title: string; artist: string } | null;
  /** 时段描述，比如 "周一深夜 / 工作日午后"，让 LLM 能据此选 scene */
  timeContext?: string;
  /**
   * 最近几轮对话。意图分类必须看上下文 ——
   * 「我想听点激情的!」单看是闲聊，配上前一句「下雨了，放点好听的」就明显是 play。
   * 不传 history 会让追问/催促被误判成 chat，回个「行。」却不放歌。
   */
  history?: { role: "user" | "assistant"; text: string }[];
  /**
   * 跨 session 记忆 digest —— 用户偏好/跳过率/上次说过的话/总播放数。
   * 让 Claudio 不再每次启动失忆,能说"上次你提过累"或"你不爱 X 艺人,放别的"。
   */
  memoryDigest?: string;
};

// DeepSeek 偶尔慢，给足时间。原来 5s 经常超时 → 用户看到的全是兜底字符串。
const INTENT_TIMEOUT_MS = 20000;

export async function parseMusicIntent(
  userText: string,
  ctx: IntentParseContext = {},
): Promise<MusicIntent> {
  const trimmed = userText.trim();
  if (!trimmed) return offlineFallbackIntent("chat", trimmed);

  // 永远先走 AI —— 它的 reply 字段才是用户在 UI 看到的人格化输出。
  // 本地兜底只在 AI 真的不可达（超时/抛错/JSON 坏）时介入，并要让用户能感知到失败，
  // 不要拿 "在。" "嗯。" 这种字符串假装是正常回复。
  let timeoutId: ReturnType<typeof setTimeout> | null = null;
  const aiPromise = callAi(trimmed, ctx);
  const timeoutPromise = new Promise<MusicIntent>((resolve) => {
    timeoutId = setTimeout(() => {
      console.warn(`[claudio] AI 超时 (${INTENT_TIMEOUT_MS}ms)，本地兜底`);
      resolve(offlineFallbackIntent(
        looksLikeImperativePlay(trimmed) ? "play" : "chat",
        trimmed,
      ));
    }, INTENT_TIMEOUT_MS);
  });

  try {
    const result = await Promise.race([aiPromise, timeoutPromise]);
    if (timeoutId) clearTimeout(timeoutId);
    // 救正：AI 偶尔把"放歌啊"判成 chat，本地祈使检测覆盖回 play
    if (result.action === "chat" && looksLikeImperativePlay(trimmed)) {
      return normalizeIntent({ ...result, action: "play" }, trimmed);
    }
    return normalizeIntent(result, trimmed);
  } catch (e) {
    if (timeoutId) clearTimeout(timeoutId);
    console.warn("[claudio] AI 调用抛错", e);
    return offlineFallbackIntent(
      looksLikeImperativePlay(trimmed) ? "play" : "chat",
      trimmed,
    );
  }
}

/**
 * AI 不可达时的**诚实**兜底。
 *
 * 关键差别：play 分支因为还有本地召回管线能干活，给一句中性的动作短句；
 * chat 分支没了 AI 就真没法回应用户的内容，**直接说"断线了"**，
 * 不要拿 "嗯。" "在。" 这种万金油糊弄用户 —— 那比报错还糟糕。
 */
function offlineFallbackIntent(
  action: "chat" | "play",
  queryText: string,
): MusicIntent {
  const reply =
    action === "play"
      ? "AI 没回来，先按本地排了。" // play 还能继续，告诉一声
      : "我这边断线了，再说一次？"; // chat 没 AI 就是哑的，承认就行
  return MusicIntentSchema.parse({ action, queryText, reply });
}

// ---------- 本地启发 ----------

/**
 * 启发式：是否像一句要求放歌的祈使句。
 * 命中条件（任一）：
 *   - 含明确"放/播/换/听/来点/搞点/整点/给我"+ 模糊宾语的组合
 *   - 含"放歌/播歌/换歌/切歌"等组合词
 *   - 末尾感叹号 + 含"想听 / 听点 / 来点"
 */
function looksLikeImperativePlay(text: string): boolean {
  const t = text.toLowerCase();
  if (/(放歌|播歌|换歌|切歌|来歌|放点|播点|来点|搞点|整点|换点)/.test(t)) return true;
  if (/(给我).{0,4}(放|播|来|整|搞|听)/.test(t)) return true;
  if (/(想听|听点|想要听)/.test(t)) return true;
  if (/^(放|播|换)[歌一首点]/.test(t)) return true;
  if (/play (some|something|me)/.test(t)) return true;
  return false;
}

// ---------- AI 调用 ----------

/**
 * SYSTEM PROMPT —— 模块级常量，永远不变。
 *
 * 关键设计：把所有 persona、字段约定、示例**全部**塞进 system，
 * 这样每次调用 system 部分前缀完全相同 → DeepSeek 自动 prompt cache 命中，
 * 缓存命中后 input 价格降到 1/10，延迟也明显下降。
 *
 * USER 部分只放真正变化的：用户这句话 + 时段 + 在播曲目 + 最近对话。
 * 旧版把 1.5k 字示例都拼在 user 里，每次重新计费、缓存永远失效，又慢又贵。
 */
const SYSTEM_PROMPT = `你是 Claudio —— 一只幽默抽象的音乐宠物。你既会接话，也会顺手放歌；这两件事不是互斥的。

# 任务
用户每说一句话，你输出一个 JSON。reply 是你以人格说出来的话；其他字段告诉本地音乐引擎要不要放歌、放什么。

# 人格调性（reply 字段）
- 永远短，中文一般 ≤24 字，最多两句。
- 抽象的比喻 OK："打工是吧""老天爷在哭""把音量旋大点""上班就是吃公司的电压"。
- 关系熟到不用客气的朋友：随便、懒、偶尔损一下。
- 决定放歌就直接说"放着""听着""点火"，不要问"要不要"。
- 绝不要：客服腔（"好的""为您"）/ 鸡汤（"加油"）/ 双形容词对仗（既…又…）/ 三连问 / 感叹号 / emoji / "嘿"起手 / "这首歌很适合你"这种夸奖。

# action 怎么判
- play：用户表达任何想听歌的信号（说情绪/累/烦/开心、点名艺人、催促"放歌啊"）
- chat：纯打招呼/问名字/感谢/闲聊
- 模糊就偏 play —— 这是音乐宠物，沉默是失败。

# 字段约定（用得上才填，默认值都 OK）
- queryText: 用户原句
- textHints.{artists,tracks,albums}: 句子里直接点名的（不要瞎补）
- musicHints.{moods,scenes,genres,avoid,energy,transitionStyle}
- hardConstraints: 用户明确说死的语言/地区/流派/排除项
- softPreferences.{moods,scenes,textures,energy,tempoFeel,eras,qualityWords}
- queueIntent.orderStyle: 默认 smooth；激情用 energy_up；派对 party；睡眠 sleep
- desiredCount: 1-60，默认 30

# 输出格式
严格只输出一个 JSON 对象，不要 markdown，不要 \`\`\`，不要解释。

# 当下上下文怎么用
USER 部分会带"时段"、可能含"明天就周末"/"再 3 天就国庆"/"今天下雨"这种锚点。
如果命中 TA 的话题或当下情绪,reply 里可以借力一下("周末已经在门口""假期心情先到");
不要硬塞,挑相关的一个用就行。

# 示例
1) "今天好累" →
{"reply":"打工是吧。来点能把电量充满的。","action":"play","queryText":"今天好累","softPreferences":{"moods":["uplifting","punchy"],"energy":"mid_high"},"hardConstraints":{"excludeTags":["sad","mellow"]},"queueIntent":{"orderStyle":"energy_up","transitionStyle":"tight"}}

2) "下雨了，放点好听的" →
{"reply":"老天爷在哭。我配点。","action":"play","queryText":"下雨了，放点好听的","softPreferences":{"scenes":["rainy day","night"],"moods":["melancholic","calm","atmospheric"],"energy":"mid_low"},"queueIntent":{"orderStyle":"smooth"}}

3) "我刚分手了" →
{"reply":"那你需要点猛的。","action":"play","queryText":"我刚分手了","softPreferences":{"moods":["cathartic","defiant"],"energy":"high"},"queueIntent":{"orderStyle":"energy_up","transitionStyle":"tight"}}

4) "你叫什么" →
{"reply":"Claudio。一只放歌的。","action":"chat","queryText":"你叫什么"}

5) "你知道火星哥吗" →
{"reply":"Bruno Mars。给你来一组。","action":"play","queryText":"你知道火星哥吗","textHints":{"artists":["Bruno Mars"]},"hardConstraints":{"artists":["Bruno Mars"]}}

6) "谢谢" →
{"reply":"嗯。","action":"chat","queryText":"谢谢"}

7) "我饿了" →
{"reply":"吃饭跟听歌一起。下饭的来一组。","action":"play","queryText":"我饿了","softPreferences":{"scenes":["dining"],"moods":["mellow","groovy"]},"queueIntent":{"orderStyle":"smooth"}}`;

async function callAi(
  userText: string,
  ctx: IntentParseContext,
): Promise<MusicIntent> {
  // USER PROMPT —— 只放变量。短，每次都重新发，但只有几百 token。
  const ctxLines: string[] = [];
  if (ctx.timeContext) ctxLines.push(`时段：${ctx.timeContext}`);
  // memoryDigest 放第二行,容易被 AI 注意到。包含偏好/排斥艺人 + 上次说过的话。
  if (ctx.memoryDigest) ctxLines.push(`TA 的人:${ctx.memoryDigest}`);
  if (ctx.currentTrack) {
    ctxLines.push(
      `在播：${ctx.currentTrack.title} — ${ctx.currentTrack.artist}`,
    );
  }
  const historyBlock = (ctx.history ?? [])
    .slice(-4)
    .map((m) => `${m.role === "user" ? "U" : "C"}：${m.text}`)
    .join("\n");
  if (historyBlock) ctxLines.push(`最近：\n${historyBlock}`);

  const user =
    (ctxLines.length ? ctxLines.join("\n") + "\n\n" : "") +
    `用户：${userText}`;

  const raw = await ai.chat({
    system: SYSTEM_PROMPT,
    user,
    temperature: 0.75,
    // maxTokens 是安全上限不是配额——日常聊天 LLM 输出 200~400 token 就自然停了，
    // 设 4000 只是给极端情况留余量，不会因此多花钱。
    maxTokens: 4000,
  });

  const json = extractJsonObject(raw);
  if (!json) {
    console.warn("[claudio] AI 返回无法解析为 JSON。原始文本:", raw);
    return offlineFallbackIntent(
      looksLikeImperativePlay(userText) ? "play" : "chat",
      userText,
    );
  }

  const result = MusicIntentSchema.safeParse(json);
  if (!result.success) {
    console.warn(
      "[claudio] AI JSON zod 校验失败",
      result.error.flatten(),
      "raw=",
      raw,
    );
    // zod 失败时尝试抢救 reply / action —— 至少别让用户彻底听不到 Claudio 的话
    const salvaged = trySalvageReply(json, userText);
    if (salvaged) return normalizeIntent(salvaged, userText);
    return offlineFallbackIntent(
      looksLikeImperativePlay(userText) ? "play" : "chat",
      userText,
    );
  }
  return normalizeIntent(result.data, userText);
}

/**
 * AI JSON 部分有效（reply 在但其它字段缺/错）时的抢救：
 * 至少把 reply 和 action 拿出来，其他字段交给 schema default 兜底。
 * 比直接走 offlineFallbackIntent 好 —— 用户能听到 AI 真说了啥。
 */
function trySalvageReply(json: unknown, userText: string): MusicIntent | null {
  if (typeof json !== "object" || !json) return null;
  const obj = json as Record<string, unknown>;
  const reply = typeof obj.reply === "string" && obj.reply.trim() ? obj.reply.trim() : null;
  if (!reply) return null;
  const rawAction = typeof obj.action === "string" ? obj.action : "chat";
  const action = ["play", "recommend", "continue", "adjust_queue"].includes(rawAction)
    ? "play"
    : "chat";
  try {
    return MusicIntentSchema.parse({
      action,
      queryText: userText,
      reply,
    });
  } catch {
    return null;
  }
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
