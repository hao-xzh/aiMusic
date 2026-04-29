"use client";

/**
 * 口味画像 —— Claudio AI 推荐的"地基"。
 *
 * 设计要点：
 *   - 整张 profile 是一个 plain JSON，存到 cache.setState(KEY, JSON)。
 *     Provider-agnostic：换 OpenAI / Claude 只是换"用谁来写 prompt"，
 *     画像本身和这次输出无关。
 *   - 持久化：sourceHash 记录上次蒸馏所基于的歌单+曲目快照的哈希，
 *     用来判断库变化大不大、要不要触发增量。
 *   - 用户主动选歌单蒸馏 → 全量重算并覆盖；冷启动 / 自动增量晚点再做。
 */

import { parseJsonObjectLike } from "./ai-json";
import { ai, audio, cache, netease, type CachedPlaylistDetail, type TrackInfo } from "./tauri";
import { readBehaviorLog, summarize } from "./behavior-log";
import { summarizeAcoustics, type AcousticMetrics } from "./acoustic-summary";

// ---------- 持久化 KV 键 ----------
const KEY_PROFILE = "taste_profile_v1";

// ---------- 类型 ----------

export type GenreTag = {
  /** 标签：indie folk / city pop / 90s 港乐 / 后摇 */
  tag: string;
  /** 0~1：在画像里的权重 —— 越高越能代表"主旋律" */
  weight: number;
  /** 标志性曲目 / 艺人，方便用户校对 */
  examples: string[];
};

export type EraSlice = {
  label: string; // "1990s" / "2010s" / "2020-now"
  weight: number; // 0~1
};

export type ArtistAffinity = {
  name: string;
  /** 0~1：被反复点的、风格代表性的，倾向高 */
  affinity: number;
};

export type TasteProfile = {
  version: 1;
  /** unix seconds */
  derivedAt: number;
  /** 这次蒸馏覆盖的歌单数量 */
  sourcePlaylistCount: number;
  /** 这次实际进 AI 的曲目采样数 */
  sampledTrackCount: number;
  /** 库里覆盖到的总曲目数（去重前） */
  totalTrackCount: number;
  /** 用过的歌单 id —— 同选会命中缓存判断 */
  sourcePlaylistIds: number[];
  /** 库快照的哈希（曲目 id 排序后取前 N 拼接哈希）；增量判断用 */
  sourceHash: string;

  // ---- AI 写出的内容 ----
  /** 主流派/风格 —— 排序后前几个就是"主旋律" */
  genres: GenreTag[];
  /** 年代倾向频谱 */
  eras: EraSlice[];
  /** 情绪关键词 ——"午夜""克制""带潮汐感"这种 */
  moods: string[];
  /** Top 艺人 */
  topArtists: ArtistAffinity[];
  /** 文化语境 ——"华语独立""日系 city pop""indie America" */
  culturalContext: string[];
  /** 一两句人设短语 ——"独立唱片店老板会拿出来给你听的那种" */
  taglines: string[];
  /** 一句话短评：作为画像页头部 hero */
  summary: string;

  /**
   * 声学指纹（已分析曲目聚合的 BPM/响度/动态范围/音色），
   * 蒸馏时算一份存进来，之后 discovery / 推荐都直接读，不再算第二次。
   * 老画像没这个字段 → null，discovery 端会跳过这块 prompt。
   */
  acoustics?: {
    analyzed: number;
    metrics: AcousticMetrics;
  } | null;
};

// ---------- 持久化 ----------

export async function loadTasteProfile(): Promise<TasteProfile | null> {
  try {
    const raw = await cache.getState(KEY_PROFILE);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as TasteProfile;
    if (!parsed || parsed.version !== 1) return null;
    return parsed;
  } catch (e) {
    console.warn("[claudio] taste profile 解析失败", e);
    return null;
  }
}

export async function saveTasteProfile(profile: TasteProfile): Promise<void> {
  await cache.setState(KEY_PROFILE, JSON.stringify(profile));
}

export async function clearTasteProfile(): Promise<void> {
  await cache.setState(KEY_PROFILE, "");
}

// ---------- 采样 ----------

/**
 * 分层采样：
 *   - 总曲目按歌单分组
 *   - 每张歌单按它的曲目数占比分配配额（避免大歌单淹没小歌单）
 *   - 每张歌单内 Fisher-Yates 洗牌后取配额数
 *
 * 入参可以传几张歌单的 detail，输出去重后的 TrackInfo[]。
 */
export function stratifiedSample(
  details: CachedPlaylistDetail[],
  targetTotal: number,
): TrackInfo[] {
  if (details.length === 0) return [];
  const totalRaw = details.reduce((s, d) => s + d.tracks.length, 0);
  if (totalRaw <= targetTotal) {
    // 小于目标量直接全量去重返回
    return dedupeById(details.flatMap((d) => d.tracks));
  }

  // 每张歌单分配 quota：按比例 + 至少 1
  const quotas = details.map((d) => {
    const ratio = d.tracks.length / totalRaw;
    return Math.max(1, Math.round(ratio * targetTotal));
  });

  // 校正总数到 targetTotal（四舍五入会偏一点）
  const sumQuota = quotas.reduce((s, q) => s + q, 0);
  if (sumQuota > targetTotal) {
    // 削最大歌单
    let over = sumQuota - targetTotal;
    while (over > 0) {
      const i = quotas.indexOf(Math.max(...quotas));
      quotas[i] = Math.max(1, quotas[i] - 1);
      over--;
    }
  }

  const picked: TrackInfo[] = [];
  for (let i = 0; i < details.length; i++) {
    const tracks = [...details[i].tracks];
    fisherYates(tracks);
    picked.push(...tracks.slice(0, quotas[i]));
  }
  return dedupeById(picked);
}

function dedupeById<T extends { id: number }>(arr: T[]): T[] {
  const seen = new Set<number>();
  const out: T[] = [];
  for (const t of arr) {
    if (seen.has(t.id)) continue;
    seen.add(t.id);
    out.push(t);
  }
  return out;
}

function fisherYates<T>(arr: T[]) {
  for (let i = arr.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [arr[i], arr[j]] = [arr[j], arr[i]];
  }
}

// 简易稳定哈希（djb2 变种）—— 不上 crypto，省个 import
export function quickHash(input: string): string {
  let h = 5381;
  for (let i = 0; i < input.length; i++) {
    h = ((h << 5) + h + input.charCodeAt(i)) | 0;
  }
  return (h >>> 0).toString(36);
}

// ---------- 蒸馏（调 AI） ----------

export type DistillProgress =
  | { phase: "loading-tracks"; done: number; total: number }
  | { phase: "sampling" }
  | { phase: "calling-ai" }
  | { phase: "done" };

export type DistillOptions = {
  /** 总采样目标，太大 prompt 撑爆，太小画像不准。300~600 之间是甜区 */
  sampleSize?: number;
  /** AI 温度：画像要稳定一点（0.4-0.6），不像写旁白那样飘 */
  temperature?: number;
  /** 进度回调，UI 用来给"正在拉曲目""正在喂 AI"反馈 */
  onProgress?: (p: DistillProgress) => void;
};

/**
 * 主流程：选中的歌单 IDs -> 拉详情（cache-first）-> 分层采样 -> 喂 AI -> 解析 -> 持久化。
 */
export async function distillTaste(
  uid: number,
  selectedPlaylistIds: number[],
  opts: DistillOptions = {},
): Promise<TasteProfile> {
  const {
    sampleSize = 480,
    temperature = 0.5,
    onProgress = () => {},
  } = opts;

  if (selectedPlaylistIds.length === 0) {
    throw new Error("没有选中歌单");
  }

  // ---- 1) 拉每张选中歌单的曲目（cache-first，miss 才打网络） ----
  const details: CachedPlaylistDetail[] = [];
  for (let i = 0; i < selectedPlaylistIds.length; i++) {
    const id = selectedPlaylistIds[i];
    onProgress({ phase: "loading-tracks", done: i, total: selectedPlaylistIds.length });
    let detail = await cache.getPlaylistDetail(id).catch(() => null);
    if (!detail || detail.tracks.length === 0) {
      const fresh = await netease.playlistDetail(id);
      // 顺手回写缓存
      await cache.savePlaylistDetail(uid, fresh).catch(() => {});
      detail = {
        id: fresh.id,
        name: fresh.name,
        coverImgUrl: fresh.coverImgUrl ?? null,
        trackCount: fresh.trackCount,
        updateTime: fresh.updateTime ?? null,
        syncedAt: Math.floor(Date.now() / 1000),
        tracks: fresh.tracks,
      };
    }
    details.push(detail);
  }
  onProgress({ phase: "loading-tracks", done: details.length, total: selectedPlaylistIds.length });

  // ---- 2) 分层采样 ----
  onProgress({ phase: "sampling" });
  const totalTracks = details.reduce((s, d) => s + d.tracks.length, 0);
  const sample = stratifiedSample(details, sampleSize);

  // 库快照哈希：用排序后的曲目 id 列表前 1000 拼接
  const allIds = details
    .flatMap((d) => d.tracks.map((t) => t.id))
    .sort((a, b) => a - b)
    .slice(0, 1000)
    .join(",");
  const sourceHash = quickHash(allIds);

  // ---- 3) 拼 prompt ----
  // 每行：序号. neteaseId. 歌名 — 艺人 · 专辑
  const lines = sample
    .map((t, i) => {
      const artist = t.artists.map((a) => a.name).join(" / ") || "未知艺人";
      const album = t.album?.name ? ` · ${t.album.name}` : "";
      return `${i + 1}. ${t.id}. ${t.name} — ${artist}${album}`;
    })
    .join("\n");

  // 声学指纹：从已分析过的曲目里聚合 BPM/响度/动态范围/音色亮度。
  // 只看 sample（采样过的），跟 lines 对齐。库里没分析过的就跳过 ——
  // 不强制现场分析，让蒸馏不阻塞在 I/O 上。
  let acousticBlock = "";
  let acousticPersist: TasteProfile["acoustics"] = null;
  try {
    const featuresList = await audio.getCachedFeaturesBulk(sample.map((t) => t.id));
    const acoustic = summarizeAcoustics(featuresList);
    if (acoustic.analyzed >= 20) {
      // 至少 20 首才出 prompt block，否则统计太薄不靠谱
      acousticBlock = `\n${acoustic.promptBlock}\n\n→ 画像里的 BPM 偏好、响度气质、音色亮度都要 reflect 这份指纹。\n`;
      acousticPersist = { analyzed: acoustic.analyzed, metrics: acoustic.metrics };
    }
  } catch (e) {
    console.debug("[claudio] acoustic aggregate failed", e);
  }

  const playlistList = details
    .map((d) => `「${d.name}」（${d.tracks.length} 首）`)
    .join("、");

  // 把"近期听歌行为"压成一段提示喂给 AI ——
  // 比歌单纯静态快照更接近"现在这段时间想听什么"。
  const behaviorEvents = await readBehaviorLog();
  const stats = summarize(behaviorEvents);
  const behaviorBlock =
    stats.total >= 10
      ? `\n近期听歌行为（最近 ${stats.total} 条事件）：\n` +
        `- 完成率 ${(stats.completionRate * 100).toFixed(0)}%（completed=${stats.completed} / skipped=${stats.skipped} / manual_cut=${stats.manualCuts}）\n` +
        (stats.loveArtists.length > 0
          ? `- 反复完整听过的艺人：${stats.loveArtists.slice(0, 8).join("、")}\n`
          : "") +
        (stats.skipHotArtists.length > 0
          ? `- 反复跳过的艺人：${stats.skipHotArtists.slice(0, 8).join("、")}\n`
          : "") +
        `→ 画像里记得让"反复听过"的权重更高、"反复跳过"的降权。\n`
      : "";

  const user =
    `用户挑了 ${details.length} 张歌单：${playlistList}\n` +
    `合计 ${totalTracks} 首，已分层采样 ${sample.length} 首。\n` +
    behaviorBlock +
    acousticBlock +
    `\n曲目（序号. neteaseId. 歌名 — 艺人 · 专辑）：\n${lines}\n\n` +
    `任务：基于这些曲目，画一份这个用户的"音乐口味画像"。\n` +
    `要求：\n` +
    `1) 不写官话套话，不堆形容词，像独立唱片店老板对客人聊天那种语气。\n` +
    `2) 必须从曲目里"看"出风格，不要光按歌名臆测；可以承认"看不太出来"。\n` +
    `3) genres 至少给 4 个具体子流派（不要"流行""摇滚"这种空洞的），按权重降序，每个带 2~3 首列表里出现过的代表曲。\n` +
    `4) eras 用具体十年段（"1990s" / "2000s" / "2010s" / "2020-now"），权重 0~1。\n` +
    `5) moods 给 3~6 个有质感的形容词或短语，比如"午夜独白""带潮汐感""克制忧郁"。\n` +
    `6) topArtists 选 6~10 个，affinity 0~1。\n` +
    `7) culturalContext 给 2~4 个文化坐标，比如"华语独立""日系 city pop""indie America"。\n` +
    `8) taglines 给 1~3 句一句话人设。\n` +
    `9) summary：≤30 个汉字的总结，不要"这是一位..."这种套话。\n\n` +
    `严格只输出一行 JSON，结构：\n` +
    `{"genres":[{"tag":"...","weight":0.42,"examples":["...","..."]}],"eras":[{"label":"2010s","weight":0.55}],"moods":["..."],"topArtists":[{"name":"...","affinity":0.9}],"culturalContext":["..."],"taglines":["..."],"summary":"..."}\n` +
    `不要 markdown，不要解释，不要代码块包裹，只一行 JSON。`;

  onProgress({ phase: "calling-ai" });
  const raw = await ai.chat({
    system:
      "你是 Claudio 的口味蒸馏器。听过比客人多的曲库 + 像独立唱片店老板。只输出 JSON，不要解释。",
    user,
    temperature,
    maxTokens: 1400,
  });

  // ---- 4) 解析 ----
  const aiParsed = parseProfileBody(raw);
  const parsed = aiParsed ?? buildFallbackProfile(sample, details);
  if (!aiParsed) {
    console.warn("[claudio] AI 画像 JSON 不可用，已使用本地统计画像兜底");
  }

  const profile: TasteProfile = {
    version: 1,
    derivedAt: Math.floor(Date.now() / 1000),
    sourcePlaylistCount: details.length,
    sampledTrackCount: sample.length,
    totalTrackCount: totalTracks,
    sourcePlaylistIds: details.map((d) => d.id),
    sourceHash,
    ...parsed,
    acoustics: acousticPersist,
  };

  // ---- 5) 持久化 ----
  await saveTasteProfile(profile);
  onProgress({ phase: "done" });
  return profile;
}

function parseProfileBody(raw: string): Omit<
  TasteProfile,
  | "version"
  | "derivedAt"
  | "sourcePlaylistCount"
  | "sampledTrackCount"
  | "totalTrackCount"
  | "sourcePlaylistIds"
  | "sourceHash"
> | null {
  const obj = parseJsonObjectLike(raw);
  if (!obj || typeof obj !== "object") return null;
  const record = obj as Record<string, unknown>;
  const summary = asString(record.summary);
  if (!summary) {
    console.warn("[claudio] taste profile JSON 缺少 summary", raw.slice(0, 200));
    return null;
  }
  return {
    genres: normalizeGenres(record.genres),
    eras: normalizeEras(record.eras),
    moods: asStringArray(record.moods).slice(0, 8),
    topArtists: normalizeArtists(record.topArtists),
    culturalContext: asStringArray(record.culturalContext).slice(0, 6),
    taglines: asStringArray(record.taglines).slice(0, 4),
    summary,
  };
}

function buildFallbackProfile(
  sample: TrackInfo[],
  details: CachedPlaylistDetail[],
): Omit<
  TasteProfile,
  | "version"
  | "derivedAt"
  | "sourcePlaylistCount"
  | "sampledTrackCount"
  | "totalTrackCount"
  | "sourcePlaylistIds"
  | "sourceHash"
> {
  const artistCounts = new Map<string, number>();
  let chineseLike = 0;
  let latinLike = 0;
  let japaneseLike = 0;
  for (const track of sample) {
    const text = `${track.name} ${track.album?.name ?? ""} ${track.artists.map((a) => a.name).join(" ")}`;
    if (/[\u4e00-\u9fff]/.test(text)) chineseLike++;
    if (/[a-zA-Z]/.test(text)) latinLike++;
    if (/[\u3040-\u30ff]/.test(text)) japaneseLike++;
    for (const artist of track.artists) {
      const name = artist.name.trim();
      if (!name) continue;
      artistCounts.set(name, (artistCounts.get(name) ?? 0) + 1);
    }
  }
  const topArtists = [...artistCounts.entries()]
    .sort((a, b) => b[1] - a[1])
    .slice(0, 10)
    .map(([name, count]) => ({
      name,
      affinity: clamp01(count / Math.max(3, sample.length * 0.04)),
    }));
  const total = Math.max(1, sample.length);
  const contexts = [
    chineseLike / total > 0.35 ? "华语音乐" : "",
    latinLike / total > 0.35 ? "欧美/英语语境" : "",
    japaneseLike / total > 0.08 ? "日系音乐" : "",
    details.some((d) => /喜欢|favorite|like/i.test(d.name)) ? "私人常听收藏" : "",
  ].filter(Boolean);

  return {
    genres: [
      { tag: "旋律向流行", weight: 0.72, examples: sample.slice(0, 3).map(trackLabel) },
      { tag: "私人收藏精选", weight: 0.62, examples: sample.slice(3, 6).map(trackLabel) },
      { tag: "情绪化人声作品", weight: 0.52, examples: sample.slice(6, 9).map(trackLabel) },
      { tag: "跨语种流行/独立", weight: 0.42, examples: sample.slice(9, 12).map(trackLabel) },
    ],
    eras: [{ label: "mixed", weight: 1 }],
    moods: ["松弛", "旋律感", "私人化", "适合连续播放"],
    topArtists,
    culturalContext: contexts.length > 0 ? contexts : ["私人曲库混合口味"],
    taglines: ["先按你的收藏重心排歌，再让后续播放慢慢学习。"],
    summary: "私人曲库混合口味",
  };
}

function normalizeGenres(value: unknown): GenreTag[] {
  if (!Array.isArray(value)) return [];
  return value
    .map((item) => {
      if (typeof item === "string") {
        return { tag: item, weight: 0.5, examples: [] };
      }
      if (!item || typeof item !== "object") return null;
      const record = item as Record<string, unknown>;
      const tag = asString(record.tag);
      if (!tag) return null;
      return {
        tag,
        weight: clamp01(asNumber(record.weight, 0.5)),
        examples: asStringArray(record.examples).slice(0, 4),
      };
    })
    .filter((item): item is GenreTag => item !== null)
    .slice(0, 12);
}

function normalizeEras(value: unknown): EraSlice[] {
  if (!Array.isArray(value)) return [];
  return value
    .map((item) => {
      if (typeof item === "string") return { label: item, weight: 0.5 };
      if (!item || typeof item !== "object") return null;
      const record = item as Record<string, unknown>;
      const label = asString(record.label);
      if (!label) return null;
      return { label, weight: clamp01(asNumber(record.weight, 0.5)) };
    })
    .filter((item): item is EraSlice => item !== null)
    .slice(0, 8);
}

function normalizeArtists(value: unknown): ArtistAffinity[] {
  if (!Array.isArray(value)) return [];
  return value
    .map((item) => {
      if (typeof item === "string") return { name: item, affinity: 0.5 };
      if (!item || typeof item !== "object") return null;
      const record = item as Record<string, unknown>;
      const name = asString(record.name);
      if (!name) return null;
      return { name, affinity: clamp01(asNumber(record.affinity, 0.5)) };
    })
    .filter((item): item is ArtistAffinity => item !== null)
    .slice(0, 14);
}

function asString(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}

function asStringArray(value: unknown): string[] {
  if (!Array.isArray(value)) return [];
  return value
    .map((item) => (typeof item === "string" ? item.trim() : ""))
    .filter(Boolean);
}

function asNumber(value: unknown, fallback: number): number {
  return typeof value === "number" && Number.isFinite(value) ? value : fallback;
}

function clamp01(value: number): number {
  return Math.max(0, Math.min(1, value));
}

function trackLabel(track: TrackInfo): string {
  const artist = track.artists[0]?.name;
  return artist ? `${track.name} - ${artist}` : track.name;
}
