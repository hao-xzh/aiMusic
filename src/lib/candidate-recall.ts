"use client";

/**
 * 多路候选召回 —— 取代 sampleForPrompt(library, 800) 的随机采样。
 *
 * 设计原则：
 *   1) 多路并行，宁可重叠也不要遗漏。每路单独写规则，互不耦合。
 *   2) 每条候选记录命中了哪些路（sources），让 ranker / 调试日志能看到"为什么这首歌进了候选"。
 *   3) 召回阶段不限严格匹配 —— 模糊命中也允许进入，让 ranker 决胜负。
 *
 * 召回路径：
 *   - text：用户句子里直接点名的歌名/艺人/专辑（最高优先级，单路命中就保送）
 *   - tag：TrackSemanticProfile 硬标签召回（语言/地区/流派/人声）
 *   - semantic：TrackSemanticProfile embeddingText/summary 的轻量语义召回
 *   - profile：从 taste-profile 的 topArtists / genres / moods 派生
 *   - audio：musicHints.energy / transitionStyle 推出的 BPM/能量过滤
 *   - behavior：行为日志里 liked / completed 多的（近 90 天）
 *   - transition：当前播放中的那首接得上的（transitionRisk 低）
 *   - explore：很久没听但 profile 匹配的（默认权重低，加入是为了不让推荐困在最近播放）
 */

import type { TrackInfo } from "./tauri";
import type { MusicIntent } from "./music-intent";
import type { TasteProfile } from "./taste-profile";
import { loadTasteProfile } from "./taste-profile";
import { loadAnalysis, type TrackAnalysis } from "./audio-analysis";
import { readBehaviorLog, type BehaviorEvent } from "./behavior-log";
import { transitionRisk, type ScoredTrack } from "./transition-score";
import {
  getSemanticProfiles,
  type TrackSemanticProfile,
} from "./track-semantic-profile";
import { recallByTags } from "./tag-recall";
import {
  recallBroadSemanticCandidates,
  recallBySemantics,
} from "./semantic-recall";

export type CandidateSource =
  | "text"
  | "tag"
  | "semantic"
  | "semantic_broad"
  | "profile"
  | "audio"
  | "behavior"
  | "transition"
  | "explore";

export type Candidate = {
  track: TrackInfo;
  analysis: TrackAnalysis | null;
  semanticProfile: TrackSemanticProfile | null;
  /** 命中了哪些召回路，给 ranker / 调试日志看 */
  sources: CandidateSource[];
  /** 各路的命中分（不是最终排序分；ranker 会再加权） */
  sourceScores: Partial<Record<CandidateSource, number>>;
};

export type RecallContext = {
  intent: MusicIntent;
  library: TrackInfo[];
  /** 当前在播曲目（如果有），用于 transition 路 */
  currentTrack?: TrackInfo | null;
  /** 召回上限。默认 200 —— 给 ranker 留下足够空间二次筛选 */
  limit?: number;
};

const DEFAULT_LIMIT = 200;

export async function recallCandidates(
  ctx: RecallContext,
): Promise<Candidate[]> {
  const { intent, library } = ctx;
  if (library.length === 0) return [];

  // 各路并行：profile / behavior / analysis 都涉及 await（cache I/O）
  const [profile, behaviorLog] = await Promise.all([
    loadTasteProfile().catch(() => null),
    readBehaviorLog().catch(() => [] as BehaviorEvent[]),
  ]);

  // 一次性把 analysis 全读出来 —— audio / transition 路都要用，避免重复 I/O
  const analysisMap = await loadAllAnalyses(library);
  const semanticMap = await getSemanticProfiles(library, {
    includeRuleBasedFallback: true,
    analyses: analysisMap,
  });

  const buckets = new Map<number, Candidate>();
  const ensure = (track: TrackInfo): Candidate => {
    let c = buckets.get(track.id);
    if (!c) {
      c = {
        track,
        analysis: analysisMap.get(track.id) ?? null,
        semanticProfile: semanticMap.get(track.id) ?? null,
        sources: [],
        sourceScores: {},
      };
      buckets.set(track.id, c);
    }
    return c;
  };
  const hit = (track: TrackInfo, source: CandidateSource, score: number) => {
    const c = ensure(track);
    if (!c.sources.includes(source)) c.sources.push(source);
    const prev = c.sourceScores[source] ?? 0;
    if (score > prev) c.sourceScores[source] = score;
  };

  // 1) text：用户直接点名 —— 最高优先级
  for (const track of recallByText(intent, library)) {
    hit(track.track, "text", track.score);
  }

  // 2) tag：语言/地区/流派/人声等硬标签
  for (const track of recallByTags(intent, library, semanticMap)) {
    hit(track.track, "tag", track.score);
  }

  // 3) semantic：自然语言轻量语义召回（先用 embeddingText/summary 文本匹配）
  for (const track of recallBySemantics(intent, intent.queryText || "", library, semanticMap)) {
    hit(track.track, "semantic", track.score);
  }

  // 语义广域兜底：开放表达时给 LLM 留候选面，不让固定标签把池子缩死。
  for (const track of recallBroadSemanticCandidates(intent, library, semanticMap)) {
    hit(track.track, "semantic_broad", track.score);
  }

  // 4) profile：长期画像
  for (const track of recallByProfile(intent, library, profile)) {
    hit(track.track, "profile", track.score);
  }

  // 5) audio：musicHints 推 BPM/能量过滤
  for (const track of recallByAudio(intent, library, analysisMap)) {
    hit(track.track, "audio", track.score);
  }

  // 6) behavior：近期 liked / completed 多的
  for (const track of recallByBehavior(library, behaviorLog)) {
    hit(track.track, "behavior", track.score);
  }

  // 7) transition：当前播放接得上的
  if (ctx.currentTrack) {
    const cur = ensure(ctx.currentTrack);
    for (const track of recallByTransition(cur, library, analysisMap)) {
      hit(track.track, "transition", track.score);
    }
  }

  // 8) explore：很久没听但 profile 匹配的
  for (const track of recallByExplore(library, profile, behaviorLog)) {
    hit(track.track, "explore", track.score);
  }

  return Array.from(buckets.values()).slice(0, ctx.limit ?? DEFAULT_LIMIT);
}

// ---------- analysis 批量读 ----------

async function loadAllAnalyses(
  library: TrackInfo[],
): Promise<Map<number, TrackAnalysis>> {
  const out = new Map<number, TrackAnalysis>();
  // 串行就够 —— loadAnalysis 是 cache 读，单次 ms 级
  // 并行 1800 个 promise 反而给 SQLite 锁带来压力
  await Promise.all(
    library.map(async (t) => {
      const a = await loadAnalysis(t.id).catch(() => null);
      if (a) out.set(t.id, a);
    }),
  );
  return out;
}

// ---------- recall paths ----------

type Hit = { track: TrackInfo; score: number };

function normalize(s: string): string {
  return s.toLowerCase().replace(/[\s'"·・·-－—]+/g, "");
}

function recallByText(intent: MusicIntent, library: TrackInfo[]): Hit[] {
  const { artists, tracks, albums } = intent.textHints;
  if (artists.length === 0 && tracks.length === 0 && albums.length === 0) {
    return [];
  }
  const artistKeys = artists.map(normalize).filter(Boolean);
  const trackKeys = tracks.map(normalize).filter(Boolean);
  const albumKeys = albums.map(normalize).filter(Boolean);

  const out: Hit[] = [];
  for (const t of library) {
    const titleN = normalize(t.name);
    const artistN = t.artists.map((a) => normalize(a.name)).join("/");
    const albumN = t.album ? normalize(t.album.name) : "";
    let score = 0;

    for (const k of trackKeys) {
      if (!k) continue;
      if (titleN === k) score += 1.0;
      else if (titleN.includes(k) || k.includes(titleN)) score += 0.6;
    }
    for (const k of artistKeys) {
      if (!k) continue;
      if (artistN.split("/").some((a) => a === k)) score += 0.9;
      else if (artistN.includes(k)) score += 0.5;
    }
    for (const k of albumKeys) {
      if (!k) continue;
      if (albumN === k) score += 0.7;
      else if (albumN.includes(k) || k.includes(albumN)) score += 0.4;
    }
    if (score > 0) out.push({ track: t, score });
  }
  out.sort((a, b) => b.score - a.score);
  // text 路放宽上限 —— 用户点名了就尽量都给
  return out.slice(0, 80);
}

function recallByProfile(
  intent: MusicIntent,
  library: TrackInfo[],
  profile: TasteProfile | null,
): Hit[] {
  if (!profile) return [];

  const topArtistKeys = new Map(
    profile.topArtists.slice(0, 40).map((a) => [normalize(a.name), a.affinity]),
  );
  // moods/genres/scenes 都是字符串标签 —— 库里没有结构化 tag，
  // 先做最粗的"标签出现在歌名里"匹配，作为弱信号。
  // 真要做精细匹配需要给曲目打 tag（属于 Stage 3 之后的事）。
  const tagWords = new Set(
    [...intent.musicHints.moods, ...intent.musicHints.genres]
      .map(normalize)
      .filter(Boolean),
  );

  const out: Hit[] = [];
  for (const t of library) {
    let score = 0;
    for (const a of t.artists) {
      const aff = topArtistKeys.get(normalize(a.name));
      if (aff !== undefined) score += aff * 0.45;
    }
    if (tagWords.size > 0) {
      const titleN = normalize(t.name);
      for (const tag of tagWords) {
        if (titleN.includes(tag)) {
          score += 0.2;
          break;
        }
      }
    }
    if (score > 0) out.push({ track: t, score });
  }
  out.sort((a, b) => b.score - a.score);
  return out.slice(0, 120);
}

function recallByAudio(
  intent: MusicIntent,
  library: TrackInfo[],
  analyses: Map<number, TrackAnalysis>,
): Hit[] {
  const energy = intent.musicHints.energy;
  if (energy === "any") return []; // 无诉求就不参与，省一路噪声

  // 把 energy 离散标签翻译成 outroEnergy 的近似阈值（基于 introEnergy/outroEnergy 都是 0~1 的经验分布）
  const range: [number, number] =
    energy === "low" ? [0.0, 0.25]
    : energy === "mid" ? [0.2, 0.55]
    : /* high */          [0.45, 1.0];

  // tight / party 倾向高 BPM
  const bpmFloor =
    intent.musicHints.transitionStyle === "party" ? 110
    : intent.musicHints.transitionStyle === "tight" ? 100
    : 0;

  const out: Hit[] = [];
  for (const t of library) {
    const a = analyses.get(t.id);
    if (!a) continue;
    const eMid = (a.introEnergy + a.outroEnergy) / 2;
    if (eMid < range[0] || eMid > range[1]) continue;
    if (bpmFloor > 0 && (a.bpm ?? 0) < bpmFloor) continue;
    // 离区间中点越近分越高
    const mid = (range[0] + range[1]) / 2;
    const score = 1 - Math.min(1, Math.abs(eMid - mid) / Math.max(0.01, mid));
    out.push({ track: t, score: 0.4 + score * 0.4 }); // baseline 0.4 防止全靠 audio 主导
  }
  out.sort((a, b) => b.score - a.score);
  return out.slice(0, 120);
}

function recallByBehavior(
  library: TrackInfo[],
  log: BehaviorEvent[],
): Hit[] {
  if (log.length === 0) return [];
  const cutoff = Date.now() / 1000 - 90 * 24 * 3600; // 近 90 天
  const now = Date.now() / 1000;
  const score = new Map<number, number>();
  for (const ev of log) {
    if (ev.ts < cutoff) continue;
    const ageHours = Math.max(0, (now - ev.ts) / 3600);
    const decay = Math.exp(-ageHours / 72);
    const w =
      ev.kind === "liked" ? 1.0
      : ev.kind === "completed" ? 0.25
      : ev.kind === "skipped" ? -0.6
      : ev.kind === "disliked" ? -1.5
      : /* manual_cut */         -0.15;
    score.set(ev.trackId, (score.get(ev.trackId) ?? 0) + w * decay);
  }
  const byId = new Map(library.map((t) => [t.id, t]));
  const out: Hit[] = [];
  for (const [id, s] of score) {
    if (s <= 0) continue;
    const track = byId.get(id);
    if (!track) continue;
    out.push({ track, score: Math.min(1.5, s) });
  }
  out.sort((a, b) => b.score - a.score);
  return out.slice(0, 80);
}

function recallByTransition(
  current: Candidate,
  library: TrackInfo[],
  analyses: Map<number, TrackAnalysis>,
): Hit[] {
  if (!current.analysis) return [];
  const out: Hit[] = [];
  const fromScored: ScoredTrack = {
    track: current.track,
    analysis: current.analysis,
  };
  for (const t of library) {
    if (t.id === current.track.id) continue;
    const a = analyses.get(t.id);
    if (!a) continue;
    const risk = transitionRisk(
      fromScored,
      { track: t, analysis: a },
      { sameArtistPenalty: 0 },
    );
    // 风险越低分越高，只取风险 < 1.0 的
    if (risk >= 1.0) continue;
    out.push({ track: t, score: 1.0 - risk });
  }
  out.sort((a, b) => b.score - a.score);
  return out.slice(0, 60);
}

function recallByExplore(
  library: TrackInfo[],
  profile: TasteProfile | null,
  log: BehaviorEvent[],
): Hit[] {
  if (!profile) return [];
  // 最近 30 条触过的 trackId
  const recentlyTouched = new Set(
    log.slice(-30).map((ev) => ev.trackId),
  );
  const topArtistKeys = new Set(
    profile.topArtists.slice(0, 30).map((a) => normalize(a.name)),
  );
  const out: Hit[] = [];
  for (const t of library) {
    if (recentlyTouched.has(t.id)) continue;
    const matches = t.artists.some((a) => topArtistKeys.has(normalize(a.name)));
    if (!matches) continue;
    out.push({ track: t, score: 0.4 }); // explore 是低权重默认
  }
  // 随机洗一下，让 explore 路有变化感
  for (let i = out.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [out[i], out[j]] = [out[j], out[i]];
  }
  return out.slice(0, 40);
}
