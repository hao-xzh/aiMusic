"use client";

/**
 * 跨 session 宠物记忆 —— Claudio 不再每次启动都失忆。
 *
 * 设计原则(很重要,以后改的人请读):
 *
 *   ❌ **不要**把"偏爱艺人 / 跳过率高艺人"喂给 AI prompt。
 *      用户的库本身就是 TA 已经亲自挑选的 —— 库里的歌默认都是 TA 喜欢的。
 *      把 skip 解释成"不喜欢"会把"瞬时心情不对"误读成"长期排斥",
 *      AI 会越来越避开那个艺人 → TA 再也听不到 → 库丰富度被悄悄削平。
 *      反向同理: 把多次完整播完当"偏爱"会让 AI 反复推荐,造成单调。
 *      这是推荐系统经典的死亡螺旋。
 *
 *   ✅ **可以**用 behavior 数据做"近期已播过 → 暂时降权"(变化,不是偏好)。
 *      这种已经在 candidate-ranker 里做了,用于增加多样性,**不是**让 AI 学口味。
 *
 *   ✅ **可以**记累积事实: 总播放数、首次相识时间、用户最近说过的话。
 *      这些是"陪伴的痕迹",不是把 TA 框成某种类型。
 *
 * 数据来源:
 *   1. 持久化的"最近 N 条用户说过的话"(去掉招呼/感谢) —— 让它能说"上次你提过…"
 *   2. 累积统计 —— 总播放数 / 首次相识日期
 *
 * 输出: 一句话 digest, 注入到 AI 三个调用点的 USER prompt 里(SYSTEM 不能动,会破坏缓存)。
 */

import { cache } from "./tauri";
import { readBehaviorLog } from "./behavior-log";

const MEM_KEY = "pet_memory_v1";
const MAX_UTTERANCES = 30; // 最多记 30 条用户原话, 老的丢
const UTTERANCE_TTL_DAYS = 30; // 30 天没更新的彻底过期

type PersistedMemory = {
  version: 1;
  /** 用户跟 Claudio 说过的有意义的话(过滤掉招呼/感谢/单字) */
  utterances: { ts: number; text: string }[];
  /** 第一次见到 TA 的时间(unix sec) */
  firstSeenAt: number;
  /** 最后一次见到的时间 */
  lastSeenAt: number;
  /**
   * 用户自述的关于自己的情况:工作时间、作息、习惯、爱好...
   * 用户在设置里手动填的,不是 AI 推断的。**TA 自己说的**是 ground truth,
   * 应该比从行为日志反推的"偏好"权重高得多。
   */
  userFacts?: string;
};

let memo: PersistedMemory | null = null;

async function loadMemory(): Promise<PersistedMemory> {
  if (memo) return memo;
  try {
    const raw = await cache.getState(MEM_KEY);
    if (raw) {
      const parsed = JSON.parse(raw) as PersistedMemory;
      if (parsed.version === 1) {
        memo = parsed;
        return memo;
      }
    }
  } catch {
    /* 坏缓存 → 重置 */
  }
  const now = Math.floor(Date.now() / 1000);
  memo = {
    version: 1,
    utterances: [],
    firstSeenAt: now,
    lastSeenAt: now,
  };
  return memo;
}

async function saveMemory(): Promise<void> {
  if (!memo) return;
  try {
    await cache.setState(MEM_KEY, JSON.stringify(memo));
  } catch (e) {
    console.debug("[claudio] pet-memory 落盘失败", e);
  }
}

/**
 * 用户每次说话后调一次。**自动过滤**掉无意义短句(招呼/感谢/单字),不污染记忆。
 */
export async function recordUserUtterance(text: string): Promise<void> {
  const trimmed = text.trim();
  if (!trimmed) return;
  if (trimmed.length <= 2) return; // 单字短句没价值
  if (/^(嗯|哦|好|谢谢|你好|您好|早|晚安|hi|hello|thanks|thx)$/i.test(trimmed)) return;

  const m = await loadMemory();
  const now = Math.floor(Date.now() / 1000);
  m.utterances.push({ ts: now, text: trimmed.slice(0, 80) });
  // 滚动 cap + 过期清理
  const cutoff = now - UTTERANCE_TTL_DAYS * 86400;
  m.utterances = m.utterances.filter((u) => u.ts >= cutoff).slice(-MAX_UTTERANCES);
  m.lastSeenAt = now;
  await saveMemory();
}

/**
 * 给 AI prompt 用的一行 digest。
 * 包含:陪伴痕迹(总播放数 / 上次说过的话) + 用户自述事实(若有)。
 *
 * 例: "听过 156 首 · 上次说'今天好累'(2 小时前) · 自述:工作日 9-18:30,双休"
 *
 * 故意**不**包含 loveArtists / skipHotArtists —— 见模块顶部注释。
 */
export async function getMemoryDigest(): Promise<string> {
  const [m, events] = await Promise.all([loadMemory(), readBehaviorLog()]);
  const parts: string[] = [];

  if (events.length > 0) {
    parts.push(`听过 ${events.length} 首`);
  }

  // 最近一句有意义的话 —— 这是真正能让 Claudio 显得"记得"的信号
  const lastUtt = m.utterances[m.utterances.length - 1];
  if (lastUtt) {
    const ageMin = Math.floor((Date.now() / 1000 - lastUtt.ts) / 60);
    let ageLabel: string;
    if (ageMin < 5) ageLabel = "刚刚";
    else if (ageMin < 60) ageLabel = `${ageMin} 分钟前`;
    else if (ageMin < 24 * 60) ageLabel = `${Math.floor(ageMin / 60)} 小时前`;
    else ageLabel = `${Math.floor(ageMin / (60 * 24))} 天前`;
    parts.push(`上次说"${lastUtt.text.slice(0, 30)}"(${ageLabel})`);
  }

  // 用户自述事实——TA 自己说的,比任何推断都准
  if (m.userFacts && m.userFacts.trim()) {
    parts.push(`自述:${m.userFacts.trim()}`);
  }

  return parts.join(" · ");
}

// ---- 用户自述 (settings 页编辑) ----

export async function getUserFacts(): Promise<string> {
  const m = await loadMemory();
  return m.userFacts ?? "";
}

export async function setUserFacts(text: string): Promise<void> {
  const m = await loadMemory();
  // 留点空间但不要无限大 —— 400 字够说工作时间、作息、爱好、几个小习惯
  m.userFacts = text.trim().slice(0, 400);
  await saveMemory();
}

/** 调试 / 设置页用 —— 拿到完整记忆。 */
export async function readPetMemory(): Promise<PersistedMemory> {
  return loadMemory();
}

/** 用户主动清记忆(以后设置页可以加按钮)。 */
export async function clearPetMemory(): Promise<void> {
  const now = Math.floor(Date.now() / 1000);
  memo = {
    version: 1,
    utterances: [],
    firstSeenAt: now,
    lastSeenAt: now,
  };
  await saveMemory();
}
