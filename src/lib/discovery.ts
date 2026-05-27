"use client";

/**
 * 库外推荐流水线 —— 拿口味画像出"我可能喜欢但歌单里没有"的歌。
 *
 * 三步走：
 *   1) AI 出 "seeds"：搜索关键词组合（艺人/子流派/年代/情绪）
 *   2) 用 netease.search 把每个 seed 搜回真实曲目；聚合 + 去重 + 排除用户已有
 *   3) 把候选池 + 画像再喂给 AI，让它从中挑 top N
 *
 * 这样 AI 只负责"语义/文化判断"，"什么真的存在/能播"交给 Netease ——
 * 把幻觉风险压到最低。
 */

import { ai, netease, type TrackInfo } from "./tauri";
import type { TasteProfile } from "./taste-profile";

export type DiscoverySeed = {
  /** 搜索关键词，比如 "Mitski Be the Cowboy" / "city pop 1985" */
  query: string;
};

export type DiscoveryPick = {
  track: TrackInfo;
  /** 来自哪个 seed */
  fromSeed?: string;
};

export type DiscoveryProgress =
  | { phase: "seeding" }
  | { phase: "searching"; done: number; total: number; query: string }
  | { phase: "ranking"; candidateCount: number }
  | { phase: "done"; pickCount: number };

export type DiscoveryOptions = {
  seedCount?: number;
  perSeedLimit?: number;
  finalCount?: number;
  onProgress?: (p: DiscoveryProgress) => void;
  /**
   * 用户此刻的提示词(从对话 intent 透传)。让 seed 偏向当下的口味
   * 而不是只看长期画像。
   */
  intentHint?: {
    artists?: string[];
    moods?: string[];
    genres?: string[];
  };
  /**
   * "最近推过太多次"的艺人,seed prompt 里告诉 AI 别再围着他们转 —— 修
   * "AI 老 seed 同一批艺人"的根因。Normalize 后的 artist name list。
   */
  avoidArtists?: string[];
};

// ---------- 主入口 ----------

// 15 分钟内存缓存:同画像 + 同意图近似(同 artists/moods)在窗口内复用同一批
// discovery 结果。窗口短是为了让画像更新 / 用户切换诉求时能尽快重算。
type DiscoveryCacheEntry = {
  ts: number;
  picks: DiscoveryPick[];
};
const DISCOVERY_CACHE_MS = 15 * 60 * 1000;
const discoveryCache = new Map<string, DiscoveryCacheEntry>();

function discoveryCacheKey(
  profile: TasteProfile,
  opts: DiscoveryOptions,
): string {
  const intent = opts.intentHint
    ? [...(opts.intentHint.artists ?? []), ...(opts.intentHint.moods ?? []), ...(opts.intentHint.genres ?? [])]
        .join("|")
        .toLowerCase()
    : "";
  const avoid = (opts.avoidArtists ?? []).slice(0, 5).join("|").toLowerCase();
  // profile 用 sourceHash + derivedAt 标识同一次蒸馏
  return `${profile.sourceHash}::${profile.derivedAt}::${opts.finalCount ?? "d"}::${intent}::${avoid}`;
}

export async function discoverBeyondLibrary(
  profile: TasteProfile,
  ownedTrackIds: Set<number>,
  opts: DiscoveryOptions = {},
): Promise<DiscoveryPick[]> {
  // 缓存命中直接返回(过滤掉这次的 ownedTrackIds —— 用户可能在窗口内加了新歌)
  const ck = discoveryCacheKey(profile, opts);
  const cached = discoveryCache.get(ck);
  if (cached && Date.now() - cached.ts < DISCOVERY_CACHE_MS) {
    const filtered = cached.picks.filter((p) => !ownedTrackIds.has(p.track.id));
    if (filtered.length > 0) {
      opts.onProgress?.({ phase: "done", pickCount: filtered.length });
      return filtered;
    }
  }

  const {
    seedCount = 12,
    perSeedLimit = 10,
    finalCount = 15,
    onProgress = () => {},
    intentHint,
    avoidArtists,
  } = opts;

  // ---- 1) AI 出 seeds ----
  onProgress({ phase: "seeding" });
  const seeds = await generateSeeds(profile, seedCount, { intentHint, avoidArtists });

  // ---- 2) 真实搜索聚合 ----
  // 简单串行（Netease 不喜欢瞬时并发太多） + 失败容错（单 seed 挂了不影响整体）
  const candidates = new Map<number, DiscoveryPick>(); // id -> pick
  for (let i = 0; i < seeds.length; i++) {
    const seed = seeds[i];
    onProgress({
      phase: "searching",
      done: i,
      total: seeds.length,
      query: seed.query,
    });
    try {
      const hits = await netease.search(seed.query, perSeedLimit);
      for (const t of hits) {
        if (ownedTrackIds.has(t.id)) continue; // 库内已有，跳过
        if (candidates.has(t.id)) continue; // 已被另一 seed 收录
        candidates.set(t.id, {
          track: t,
          fromSeed: seed.query,
        });
      }
    } catch (e) {
      console.warn(`[claudio] discovery seed search failed: ${seed.query}`, e);
    }
  }
  const candidateList = [...candidates.values()];

  if (candidateList.length === 0) {
    throw new Error(
      "搜不到任何库外候选 —— AI seed 可能太冷门或 Netease 没收录，建议重新蒸馏画像",
    );
  }

  // ---- 3) AI rerank ----
  onProgress({ phase: "ranking", candidateCount: candidateList.length });
  const ranked = await rankCandidates(profile, candidateList, finalCount);

  // 写缓存(15min 内同 key 复用)
  discoveryCache.set(ck, { ts: Date.now(), picks: ranked });

  onProgress({ phase: "done", pickCount: ranked.length });
  return ranked;
}

// ---------- AI 出 seeds ----------

async function generateSeeds(
  profile: TasteProfile,
  count: number,
  ctx: {
    intentHint?: DiscoveryOptions["intentHint"];
    avoidArtists?: string[];
  } = {},
): Promise<DiscoverySeed[]> {
  // 把画像中关键字段提炼成 prompt 上下文
  const profileSnippet = JSON.stringify({
    summary: profile.summary,
    taglines: profile.taglines,
    genres: profile.genres.map((g) => ({ tag: g.tag, weight: g.weight })),
    eras: profile.eras,
    moods: profile.moods,
    topArtists: profile.topArtists.slice(0, 8).map((a) => a.name),
    culturalContext: profile.culturalContext,
  });

  const acousticBlock = formatAcousticBlock(profile);
  const intentBlock =
    ctx.intentHint &&
    ((ctx.intentHint.artists?.length ?? 0) +
      (ctx.intentHint.moods?.length ?? 0) +
      (ctx.intentHint.genres?.length ?? 0) >
      0)
      ? `\n用户此刻说想要:${JSON.stringify(ctx.intentHint)}\n→ seed 优先贴这个当下诉求,画像作为长期锚点;两者冲突时听当下。\n`
      : "";
  const avoidBlock =
    ctx.avoidArtists && ctx.avoidArtists.length > 0
      ? `\n近期已经反复推过的艺人(seed 不要再围着他们转):${ctx.avoidArtists.slice(0, 8).join("、")}\n` +
        `→ 把 seed 的重心放在画像里的 genres / culturalContext / 相邻艺人上,而不是这几个已经听腻的人。\n`
      : "";

  const user =
    `这是用户的口味画像（JSON）：\n${profileSnippet}\n` +
    acousticBlock +
    intentBlock +
    avoidBlock +
    `\n任务：为这个用户生成 ${count} 个"搜索 seed"，去网易云搜出 TA 可能喜欢但歌单里**还没有**的歌。\n` +
    `要求：\n` +
    `1) **核心目标:贴 TA 的口味**。画像 genres / moods / culturalContext / 声学指纹是 ground truth,\n` +
    `   seed 要让搜出来的歌"听起来像 TA 会喜欢的"。冷门不是目标,是**可以接受的副作用** ——\n` +
    `   优先级是 [贴口味] >> [新鲜度] > [冷门度]。\n` +
    `2) seed 要"窄而准"——具体艺人 + 代表专辑、或者"子流派 + 年代"、或者"情绪 + 文化语境"。\n` +
    `   反例：太宽（"流行歌曲"）、太通用（"古典音乐"）、纯英文情绪词（"sad songs"）\n` +
    `3) topArtists 已经在 TA 库里了不用 seed 他们本人,但**风格相邻**的艺人是首选 ——\n` +
    `   听 Taylor 的用户 seed Phoebe Bridgers / Lorde / Mitski 这种,而不是 seed 一个完全风格不搭的 indie 实验艺人。\n` +
    (acousticBlock
      ? `4) 声学指纹给的 BPM 区间 / 响度气质 / 音色亮度是硬约束:` +
        `指纹偏慢就别推 EDM,偏暗就别推干净 pop,跑偏的 seed 直接不要。\n`
      : "") +
    `5) query 字段就是要塞进搜索框的那串字符（中英都行，看哪个更精准）。\n` +
    `6) 不要解释。\n\n` +
    `严格只输出一行 JSON：{"seeds":[{"query":"..."}]}\n` +
    `不要 markdown，不要解释，不要代码块包裹。`;

  const raw = await ai.chat({
    system:
      "你是 Claudio 的库外探索器。听过比客人多的曲库,目标是让 TA 听到符合口味的好歌 —— " +
      "用户库里已有的就别 seed 了,但风格相邻的邻居艺人是首选。冷门不是目标,贴口味才是。只输出 JSON。",
    user,
    temperature: 0.7, // 0.85 → 0.7:保留一些发散度,但不再特意往冷门偏
    maxTokens: 1200,
  });

  const parsed = parseJsonObject(raw) as { seeds?: DiscoverySeed[] } | null;
  if (!parsed || !Array.isArray(parsed.seeds) || parsed.seeds.length === 0) {
    throw new Error("AI 没出 seeds（解析失败或空数组）");
  }
  return parsed.seeds
    .filter((s) => s && typeof s.query === "string" && s.query.trim().length > 0)
    .slice(0, count);
}

// ---------- AI rerank ----------

async function rankCandidates(
  profile: TasteProfile,
  candidates: DiscoveryPick[],
  finalCount: number,
): Promise<DiscoveryPick[]> {
  // 把候选池压缩成短行喂 AI —— neteaseId 必须保留，AI 用它指代具体歌
  const lines = candidates
    .map((c, i) => {
      const t = c.track;
      const artist = t.artists.map((a) => a.name).join(" / ") || "未知";
      const album = t.album?.name ? ` · ${t.album.name}` : "";
      return `${i + 1}. ${t.id}. ${t.name} — ${artist}${album}`;
    })
    .join("\n");

  const profileSnippet = JSON.stringify({
    summary: profile.summary,
    taglines: profile.taglines,
    genres: profile.genres.slice(0, 6),
    moods: profile.moods,
    culturalContext: profile.culturalContext,
  });
  const acousticBlock = formatAcousticBlock(profile);

  const user =
    `用户口味画像（JSON）：\n${profileSnippet}\n` +
    acousticBlock +
    `\n这些是从网易云搜出来的候选，全部都是 TA 歌单里**没有**的歌：\n${lines}\n\n` +
    `任务：从候选池里挑出最符合 TA 口味气质的 ≤${finalCount} 首，给个性化推荐。\n` +
    `要求：\n` +
    `1) 不是按"热门度"挑，是按"和这个画像的匹配度"。\n` +
    (acousticBlock
      ? `   "匹配度"明确包含声学指纹：BPM/响度/音色亮度对得上才算匹配，不只是 genre 标签。\n`
      : "") +
    `2) 不要为了多样性刻意避开同一艺人；如果同一艺人的多首歌更贴口味，可以保留。\n` +
    `3) 不要解释。\n` +
    `4) trackIds 必须从上面候选列表的 neteaseId 里挑，不许编。\n\n` +
    `严格只输出一行 JSON：{"picks":[{"trackId":1234}]}\n` +
    `不要 markdown，不要解释，不要代码块包裹。`;

  const raw = await ai.chat({
    system:
      "你是 Claudio 的口味匹配器。从候选池里挑最贴气质的 ≤N 首。只输出 JSON，不要解释。",
    user,
    temperature: 0.5,
    maxTokens: 1400,
  });

  type RankedPick = { trackId: number };
  const parsed = parseJsonObject(raw) as { picks?: RankedPick[] } | null;
  if (!parsed || !Array.isArray(parsed.picks)) {
    throw new Error("AI rerank 返回不合法");
  }

  const byId = new Map(candidates.map((c) => [c.track.id, c]));
  const out: DiscoveryPick[] = [];
  for (const p of parsed.picks) {
    if (typeof p.trackId !== "number") continue;
    const c = byId.get(p.trackId);
    if (!c) continue; // AI 幻觉了一个不在候选池里的 id，丢
    out.push({ track: c.track, fromSeed: c.fromSeed });
    if (out.length >= finalCount) break;
  }
  return out;
}

// ---------- 把画像里的 acoustics 摘要拼成 prompt 块 ----------
//
// taste-profile 蒸馏时算了一份声学指纹存在 profile.acoustics 里。
// 老画像（蒸馏时还没接 Symphonia）→ 没这字段，就返回空串，prompt 自动跳过那一段。

function formatAcousticBlock(profile: TasteProfile): string {
  const a = profile.acoustics;
  if (!a || a.analyzed < 20) return "";
  const m = a.metrics;
  const lines: string[] = [`声学指纹（基于 ${a.analyzed} 首已分析曲目）：`];
  if (m.bpmMedian !== null) {
    lines.push(
      `- BPM 中位 ${m.bpmMedian.toFixed(0)}，分布 慢 ${pct(m.bpmDistribution.slow)}` +
        ` / 中 ${pct(m.bpmDistribution.mid)} / 快 ${pct(m.bpmDistribution.fast)}`,
    );
  }
  lines.push(
    `- 响度均值 ${m.rmsDbMean.toFixed(1)} dBFS · 动态范围 ${m.dynamicRangeDbMean.toFixed(1)} dB`,
  );
  lines.push(
    `- 音色：暗 ${pct(m.centroidDistribution.dark)} / 中 ${pct(m.centroidDistribution.neutral)}` +
      ` / 亮 ${pct(m.centroidDistribution.bright)}（谱重心 ~${(m.centroidMean / 1000).toFixed(1)}kHz）`,
  );
  return `\n${lines.join("\n")}\n`;
}

function pct(x: number): string {
  return `${Math.round(x * 100)}%`;
}

// ---------- JSON 宽松解析 ----------

function parseJsonObject(raw: string): unknown {
  let s = raw.trim();
  if (s.startsWith("```")) {
    s = s.replace(/^```(?:json)?\s*/i, "").replace(/```$/, "").trim();
  }
  const first = s.indexOf("{");
  const last = s.lastIndexOf("}");
  if (first === -1 || last === -1 || last <= first) return null;
  try {
    return JSON.parse(s.slice(first, last + 1));
  } catch (e) {
    console.warn("[claudio] discovery JSON 解析失败", e, s.slice(0, 200));
    return null;
  }
}
