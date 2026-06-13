/**
 * 网易云 yrc（逐字 / karaoke）格式解析。
 *
 * 这份解析器跟 Android-native 的 YrcParser 保持同一套语义：YRC 负责逐词/逐字
 * 动画，LRC 只做行级兜底；同一时间戳附近的副唱 / ad-lib 会挂到主行的
 * companionLines 里，避免把翻译或副词当成下一句把滚动和 sweep 打乱。
 */

export type YrcTimingPart = {
  startSec: number;
  durSec: number;
  text: string;
};

export type YrcChar = {
  /** 该字 / 词在歌曲里的绝对秒数起点 */
  startSec: number;
  /** 持续秒数 */
  durSec: number;
  /** 文本（可能含尾空格 / 标点） */
  text: string;
  /** 细分时间片。没有时退回到自身时间。 */
  timingParts?: YrcTimingPart[];
};

export type YrcLineRole = "primary" | "companion" | "translation" | "romaji";
export type YrcLineAlignment = "start" | "end";

export type YrcLine = {
  /** 行起点秒 */
  time: number;
  /** 行持续秒 */
  durSec: number;
  /** 整行拼起来 */
  text: string;
  /** 字 / 词时间块。空数组 = 这行没解到逐字，按 LRC 走 */
  chars: YrcChar[];
  companionLines?: YrcLine[];
  role?: YrcLineRole;
  alignment?: YrcLineAlignment;
};

const LINE_HEADER_RE = /^\[(\d+),(\d+)\]/;
const TOKEN_RE = /\((\d+),(\d+),(?:-?\d+)\)([^()[\]]*)/g;
const OFFSET_TAG_RE = /\[(?:offset|offsetMs)\s*:\s*([+-]?\d+)]/i;
const JSON_OFFSET_RE = /"offset"\s*:\s*([+-]?\d+)/i;

const NEAR_SIMULTANEOUS_LINE_SEC = 0.08;
const MAX_COMPANION_LYRIC_LINES = 2;
const COMPANION_HOST_SLOP_SEC = 0.65;
const LONG_TAIL_AFTER_LAST_TOKEN_SEC = 1.8;
const LINE_END_SLOP_SEC = 0.12;
const DEFAULT_TOKEN_VISUAL_SEC = 0.52;
const MIN_LAST_TOKEN_VISUAL_SEC = 0.32;
const MAX_LAST_TOKEN_VISUAL_SEC = 1.55;

export function parseYrc(raw: string | null | undefined): YrcLine[] {
  if (!raw) return [];
  const lines: YrcLine[] = [];
  const offsetSec = parseOffsetMs(raw) / 1000;

  for (const rawLine of raw.split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line) continue;
    if (line.startsWith("{")) continue;

    const header = LINE_HEADER_RE.exec(line);
    if (!header) continue;
    const lineStartSec = Math.max(0, Number(header[1]) / 1000 + offsetSec);
    const lineDurSec = Number(header[2]) / 1000;
    if (!Number.isFinite(lineStartSec) || !Number.isFinite(lineDurSec)) continue;

    const rest = line.slice(header[0].length);
    TOKEN_RE.lastIndex = 0;
    const charsRaw: YrcChar[] = [];
    let m: RegExpExecArray | null;
    while ((m = TOKEN_RE.exec(rest)) !== null) {
      const tokenStartSec = Math.max(0, Number(m[1]) / 1000 + offsetSec);
      const tokenDurSec = Number(m[2]) / 1000;
      const text = m[3];
      if (!Number.isFinite(tokenStartSec) || !Number.isFinite(tokenDurSec)) continue;
      if (!text) continue;

      const groups = splitIntoVisualChars(text);
      if (groups.length === 0) continue;
      const perDurSec = tokenDurSec / groups.length;
      groups.forEach((charText, idx) => {
        const startSec = tokenStartSec + perDurSec * idx;
        const durSec = idx === groups.length - 1 ? tokenDurSec - perDurSec * idx : perDurSec;
        charsRaw.push({
          startSec,
          durSec: Math.max(0.001, durSec),
          text: charText,
          timingParts: [{ startSec, durSec: Math.max(0.001, durSec), text: charText }],
        });
      });
    }

    const mergedChars = normalizeYrcLineTimings(
      mergeAdjacentAsciiLyricChars(charsRaw),
      lineStartSec,
      lineDurSec,
    );
    const text = mergedChars.map((c) => c.text).join("");
    if (mergedChars.length === 0 && !text) continue;

    lines.push({
      time: lineStartSec,
      durSec: lineDurSec,
      text,
      chars: mergedChars,
      role: "primary",
    });
  }

  lines.sort((a, b) => a.time - b.time);
  return mergeSimultaneousYrcLines(lines);
}

/**
 * 算某个字 / 词在 positionSec 时刻的播放进度：
 *   - 还没到 -> 0
 *   - 已唱完 -> 1
 *   - 正在唱 -> 0..1
 *
 * 如果 token 有 timingParts，按 Android 端同款「文本分数」推进，英文合并词的
 * sweep 不会因为内部小片段被粗暴拉成一整段而抖动。
 */
export function charProgress(c: YrcChar, positionSec: number): number {
  const parts = timingPartsForProgress(c);
  if (parts.length <= 1) {
    if (positionSec <= c.startSec) return 0;
    if (positionSec >= c.startSec + c.durSec) return 1;
    return (positionSec - c.startSec) / Math.max(0.001, c.durSec);
  }

  const totalTextLength = Math.max(1, parts.reduce((sum, p) => sum + Math.max(1, p.text.length), 0));
  let consumed = 0;
  let progress = 0;
  for (let i = 0; i < parts.length; i++) {
    const part = parts[i]!;
    const partLength = Math.max(1, part.text.length);
    const partStartProgress = consumed / totalTextLength;
    const partEndProgress = (consumed + partLength) / totalTextLength;
    const nextStartSec = parts[i + 1]?.startSec;
    const effectiveDurSec =
      nextStartSec != null
        ? Math.min(Math.max(0.001, nextStartSec - part.startSec), Math.max(0.001, part.durSec))
        : Math.max(0.001, part.durSec);
    const partEndSec = part.startSec + effectiveDurSec;
    if (positionSec < part.startSec) return partStartProgress;
    if (positionSec >= partEndSec) progress = partEndProgress;
    else {
      const t = (positionSec - part.startSec) / effectiveDurSec;
      return partStartProgress + (partEndProgress - partStartProgress) * Math.max(0, Math.min(1, t));
    }
    consumed += partLength;
  }
  return Math.max(0, Math.min(1, progress));
}

function parseOffsetMs(raw: string): number {
  return Number(OFFSET_TAG_RE.exec(raw)?.[1] ?? JSON_OFFSET_RE.exec(raw)?.[1] ?? 0) || 0;
}

function mergeSimultaneousYrcLines(lines: YrcLine[]): YrcLine[] {
  if (lines.length <= 1) return lines;

  const primaryLines: YrcLine[] = [];
  const companionCandidates: YrcLine[] = [];
  for (const line of lines) {
    if (isParentheticalLine(line.text)) {
      companionCandidates.push(line);
      continue;
    }

    const previous = primaryLines[primaryLines.length - 1];
    if (!previous || Math.abs(line.time - previous.time) >= NEAR_SIMULTANEOUS_LINE_SEC) {
      primaryLines.push(line);
    } else if (!sameLyricText(line.text, previous.text)) {
      companionCandidates.push(line);
    }
  }
  if (primaryLines.length === 0) return lines;

  const attached = primaryLines.map(() => [] as YrcLine[]);
  const orphans: YrcLine[] = [];
  for (const companion of companionCandidates) {
    const hostIndex = findCompanionHostIndex(companion, primaryLines);
    if (hostIndex >= 0) attached[hostIndex]!.push(companion);
    else orphans.push(companion);
  }

  return [
    ...primaryLines.map((line, idx) => {
      const companions = attached[idx]!
        .sort((a, b) => yrcAudioStart(a) - yrcAudioStart(b))
        .slice(0, MAX_COMPANION_LYRIC_LINES)
        .map((companion) => ({ ...companion, role: "companion" as const }));
      return companions.length > 0
        ? { ...line, companionLines: [...(line.companionLines ?? []), ...companions] }
        : line;
    }),
    ...orphans,
  ].sort((a, b) => a.time - b.time);
}

function findCompanionHostIndex(companion: YrcLine, primaryLines: YrcLine[]): number {
  const companionStart = yrcAudioStart(companion);
  const companionEnd = yrcAudioEnd(companion);
  let bestIndex = -1;
  let bestScore = Number.NEGATIVE_INFINITY;
  primaryLines.forEach((primary, idx) => {
    const primaryStart = yrcAudioStart(primary);
    const primaryEnd = yrcAudioEnd(primary);
    const nextPrimaryStart = primaryLines[idx + 1] ? yrcAudioStart(primaryLines[idx + 1]!) : null;
    const hostWindowEnd =
      nextPrimaryStart == null
        ? primaryEnd + COMPANION_HOST_SLOP_SEC
        : Math.min(primaryEnd + COMPANION_HOST_SLOP_SEC, nextPrimaryStart - 0.001);
    const overlap =
      Math.min(companionEnd, hostWindowEnd) -
      Math.max(companionStart, primaryStart - COMPANION_HOST_SLOP_SEC);
    if (overlap <= 0) return;

    const midpointDistance = Math.abs(
      (companionStart + companionEnd) / 2 - (primaryStart + hostWindowEnd) / 2,
    );
    const score = overlap * 10 - midpointDistance;
    if (score > bestScore) {
      bestScore = score;
      bestIndex = idx;
    }
  });
  return bestIndex;
}

function normalizeYrcLineTimings(chars: YrcChar[], lineStartSec: number, lineDurSec: number): YrcChar[] {
  if (chars.length === 0) return chars;
  const normalized = clampDurationsToNextTokenStart(chars);
  const lineEndSec = lineStartSec + lineDurSec;
  const last = normalized[normalized.length - 1]!;
  const lastEndSec = last.startSec + last.durSec;
  const tailFromLastStartSec = lineEndSec - last.startSec;
  const previousTypicalSec = typicalPreviousTokenDurationSec(normalized.slice(0, -1));
  const estimatedLastSec = estimateSungTokenDurationSec(last.text);
  const maxVisualLastSec = Math.max(estimatedLastSec, previousTypicalSec * 1.55);
  const cappedMaxVisualLastSec = Math.max(
    MIN_LAST_TOKEN_VISUAL_SEC,
    Math.min(MAX_LAST_TOKEN_VISUAL_SEC, maxVisualLastSec),
  );

  const looksLikeLineTailPackedIntoLast =
    tailFromLastStartSec >= LONG_TAIL_AFTER_LAST_TOKEN_SEC &&
    Math.abs(lastEndSec - lineEndSec) <= LINE_END_SLOP_SEC &&
    last.durSec > Math.max(cappedMaxVisualLastSec * 2, previousTypicalSec * 2);

  if (!looksLikeLineTailPackedIntoLast) return normalized;

  const cappedDuration = Math.max(0.001, cappedMaxVisualLastSec);
  return [
    ...normalized.slice(0, -1),
    {
      ...last,
      durSec: cappedDuration,
      timingParts: [{ startSec: last.startSec, durSec: cappedDuration, text: last.text }],
    },
  ];
}

function clampDurationsToNextTokenStart(chars: YrcChar[]): YrcChar[] {
  if (chars.length <= 1) return chars;
  return chars.map((char, idx) => {
    const next = chars[idx + 1];
    if (!next) return char;
    const maxDurationSec = Math.max(0.001, next.startSec - char.startSec);
    return char.durSec > maxDurationSec ? { ...char, durSec: maxDurationSec } : char;
  });
}

function typicalPreviousTokenDurationSec(chars: YrcChar[]): number {
  const values = chars.map((c) => c.durSec).filter((d) => d >= 0.08 && d <= 2.2).sort((a, b) => a - b);
  if (values.length === 0) return DEFAULT_TOKEN_VISUAL_SEC;
  return values[Math.floor(values.length / 2)]!;
}

function estimateSungTokenDurationSec(text: string): number {
  const trimmed = text.trim();
  if (!trimmed) return DEFAULT_TOKEN_VISUAL_SEC;
  const asciiCount = Array.from(trimmed).filter((c) => /[a-zA-Z0-9]/.test(c)).length;
  const cjkCount = Array.from(trimmed).filter(isCjkWordChar).length;
  const raw =
    asciiCount > 0
      ? 0.52 + Math.min(12, asciiCount) * 0.038
      : cjkCount > 0
        ? cjkCount * 0.28
        : DEFAULT_TOKEN_VISUAL_SEC;
  return Math.max(MIN_LAST_TOKEN_VISUAL_SEC, Math.min(MAX_LAST_TOKEN_VISUAL_SEC, raw));
}

function timingPartsForProgress(c: YrcChar): YrcTimingPart[] {
  const tokenEndSec = c.startSec + Math.max(0.001, c.durSec);
  const parts = (c.timingParts ?? [])
    .filter((part) => {
      return (
        part.text.length > 0 &&
        part.startSec < tokenEndSec &&
        part.startSec + Math.max(0.001, part.durSec) > c.startSec
      );
    })
    .sort((a, b) => a.startSec - b.startSec);
  return parts.length > 0 ? parts : [{ startSec: c.startSec, durSec: c.durSec, text: c.text }];
}

function splitIntoVisualChars(text: string): string[] {
  if (!text) return [];
  const out: string[] = [];
  let lastWasAsciiWord = false;
  for (const c of Array.from(text)) {
    const isCjk = isCjkWordChar(c);
    const isAsciiWord = /[a-zA-Z0-9]/.test(c);
    if (isCjk) {
      out.push(c);
      lastWasAsciiWord = false;
    } else if (isAsciiWord) {
      if (lastWasAsciiWord && out.length > 0) out[out.length - 1] += c;
      else out.push(c);
      lastWasAsciiWord = true;
    } else {
      if (out.length === 0) out.push(c);
      else out[out.length - 1] += c;
      lastWasAsciiWord = lastWasAsciiWord && isAsciiInlineWordJoiner(c);
    }
  }
  return out;
}

function mergeAdjacentAsciiLyricChars(chars: YrcChar[]): YrcChar[] {
  if (chars.length <= 1) return chars;
  const merged: YrcChar[] = [];
  for (const char of chars) {
    const prev = merged[merged.length - 1];
    if (prev && shouldAttachTrailingPunctuation(prev.text, char.text)) {
      merged[merged.length - 1] = {
        ...prev,
        text: prev.text + char.text,
      };
    } else if (prev && shouldMergeAsciiFragments(prev.text, char.text)) {
      const start = Math.min(prev.startSec, char.startSec);
      const end = Math.max(prev.startSec + prev.durSec, char.startSec + char.durSec);
      const text = prev.text + char.text;
      const durSec = Math.max(0.001, end - start);
      merged[merged.length - 1] = {
        startSec: start,
        durSec,
        text,
        timingParts: [{ startSec: start, durSec, text }],
      };
    } else {
      merged.push(char);
    }
  }
  return merged;
}

function shouldAttachTrailingPunctuation(left: string, right: string): boolean {
  if (!left.trim() || !right) return false;
  if (Array.from(right).some(isWordLikeChar)) return false;
  return true;
}

function shouldMergeAsciiFragments(left: string, right: string): boolean {
  if (!isAsciiWordFragment(left) || !isAsciiWordFragment(right)) return false;
  if (/\s$/.test(left) || /^\s/.test(right)) return false;
  const leftTail = left.at(-1);
  const rightHead = right.at(0);
  if (!leftTail || !rightHead) return false;
  return isAsciiWordJoiner(leftTail) || isAsciiWordJoiner(rightHead);
}

function isParentheticalLine(text: string): boolean {
  const s = text.trim();
  if (s.length < 2) return false;
  const first = s[0];
  const last = s.at(-1);
  return (
    (first === "(" && last === ")") ||
    (first === "（" && last === "）") ||
    (first === "[" && last === "]") ||
    (first === "【" && last === "】")
  );
}

function sameLyricText(left: string, right: string): boolean {
  return left.replace(/\s+/g, " ").trim().toLowerCase() === right.replace(/\s+/g, " ").trim().toLowerCase();
}

function yrcAudioStart(line: YrcLine): number {
  return line.chars[0]?.startSec ?? line.time;
}

function yrcAudioEnd(line: YrcLine): number {
  const charEnd = Math.max(0, ...line.chars.map((char) => char.startSec + Math.max(0.001, char.durSec)));
  const companionEnd = Math.max(0, ...(line.companionLines ?? []).map(yrcAudioEnd));
  return Math.max(line.time + Math.max(0, line.durSec), charEnd || line.time, companionEnd || line.time);
}

export function lrcLinesToYrcLines(lines: readonly { time: number; text: string }[]): YrcLine[] {
  return lines
    .filter((line) => line.text.trim().length > 0)
    .map((line, idx) => {
      const nextTime = lines[idx + 1]?.time;
      return {
        time: line.time,
        durSec: Math.max(0.4, (nextTime ?? line.time + 4) - line.time),
        text: line.text,
        chars: [],
        role: "primary" as const,
      };
    });
}

export function attachTimedCompanionLines(
  primaryLines: readonly YrcLine[],
  companionLines: readonly { time: number; text: string }[],
  role: Exclude<YrcLineRole, "primary">,
): YrcLine[] {
  if (primaryLines.length === 0 || companionLines.length === 0) return [...primaryLines];
  const next = primaryLines.map((line) => ({
    ...line,
    companionLines: [...(line.companionLines ?? [])],
  }));
  for (const companion of companionLines) {
    const text = companion.text.trim();
    if (!text) continue;
    const hostIndex = findLineCompanionHostIndex(companion.time, next);
    if (hostIndex < 0) continue;
    const host = next[hostIndex]!;
    host.companionLines = [
      ...(host.companionLines ?? []),
      {
        time: companion.time,
        durSec: host.durSec,
        text,
        chars: [],
        role,
      },
    ];
  }
  return next;
}

function findLineCompanionHostIndex(time: number, primaryLines: readonly YrcLine[]): number {
  let bestIndex = -1;
  let bestDistance = Number.POSITIVE_INFINITY;
  primaryLines.forEach((line, idx) => {
    const start = yrcAudioStart(line);
    const end = primaryLines[idx + 1] ? yrcAudioStart(primaryLines[idx + 1]!) : yrcAudioEnd(line) + 1.2;
    const windowStart = start - 0.3;
    const windowEnd = Math.max(windowStart, end - 0.001);
    if (time < windowStart || time > windowEnd) return;
    const distance = Math.abs(time - start);
    if (distance < bestDistance) {
      bestDistance = distance;
      bestIndex = idx;
    }
  });
  return bestIndex;
}

export function stripLeadingLyricCredits<T extends { time: number; text: string }>(lines: readonly T[]): T[] {
  if (lines.length === 0) return [...lines];
  let drop = 0;
  let sawKeywordCredit = false;
  lines.forEach((line, idx) => {
    if (idx !== drop) return;
    const text = line.text.trim();
    const prevTime = idx > 0 ? lines[idx - 1]!.time : 0;
    let isCredit = false;
    if (
      CREDIT_PREFIX_RE.test(text) ||
      CREDIT_PREFIX_SHORT_RE.test(text) ||
      ENGLISH_CREDIT_PREFIX_RE.test(text)
    ) {
      sawKeywordCredit = true;
      isCredit = true;
    } else if (idx === 0 && line.time <= 1 && TITLE_HEADER_RE.test(text)) {
      isCredit = true;
    } else if (sawKeywordCredit && line.time - prevTime <= 3 && GENERIC_LABEL_RE.test(text)) {
      isCredit = true;
    }
    if (isCredit) drop = idx + 1;
  });
  if (drop === 0 || drop >= lines.length) return [...lines];
  return lines.slice(drop);
}

const CREDIT_PREFIX_RE =
  /^[（(\[【]?\s*(作词|作詞|填词|填詞|作曲|谱曲|譜曲|编曲|編曲|制作人|製作人|监制|監製|出品人|出品|发行|發行|录音师|錄音師|录音室|錄音室|录音棚|錄音棚|录音|錄音|混音师|混音師|混音|母带|母帶|和声|和聲|合声|合聲|配唱|演唱|原唱|翻唱|演奏|吉他|贝斯|貝斯|贝司|键盘|鍵盤|钢琴|鋼琴|弦乐|弦樂|打击乐|打擊樂|人声|人聲|制作|製作|企划|企劃|统筹|統籌|文案|封面|插画|插畫|设计|設計|总监|總監|工程师|工程師)[一-鿿A-Za-z ./&-]{0,20}[:：]/;
const CREDIT_PREFIX_SHORT_RE = /^[（(\[【]?\s*(词|詞|曲|鼓|OP|SP)\s*[A-Za-z ./&-]{0,16}[:：]/;
const ENGLISH_CREDIT_PREFIX_RE =
  /^(lyrics?|lyricist|written|composers?|composed|arrangers?|arranged|producers?|produced|mix(?:ing|ed)?|master(?:ing|ed)?|record(?:ing|ed)?|vocals?|backing vocals?|guitars?|bass|drums?|keyboards?|piano|strings|engineer(?:ed)?|label|published?|publisher)\s*(?:by)?\s*[:：]/i;
const TITLE_HEADER_RE = /^\S[^:：]{0,40}\s[-—–]\s.{1,40}$/;
const GENERIC_LABEL_RE = /^[^:：。，！？!?,]{1,16}[:：].{0,60}$/;

function isAsciiWordFragment(value: string): boolean {
  let hasAsciiWord = false;
  for (const c of Array.from(value)) {
    if (isCjkWordChar(c)) return false;
    if (/[a-zA-Z0-9]/.test(c)) hasAsciiWord = true;
  }
  return hasAsciiWord;
}

function isWordLikeChar(c: string): boolean {
  return /[a-zA-Z0-9]/.test(c) || isCjkWordChar(c);
}

function isCjkWordChar(c: string): boolean {
  return /[\u3400-\u9fff\u3040-\u30ff\uac00-\ud7af]/u.test(c);
}

function isAsciiWordJoiner(c: string): boolean {
  return c === "'" || c === "’" || c === "-" || /[a-zA-Z0-9]/.test(c);
}

function isAsciiInlineWordJoiner(c: string): boolean {
  return c === "'" || c === "’" || c === "-";
}
