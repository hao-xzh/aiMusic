/**
 * 网易云 yrc（逐字 / karaoke）格式解析。
 *
 * 一行长这样：
 *   [39820,3170](39820,500,0)Some (40320,460,0)words (40780,720,0)here
 *
 * 含义：
 *   - `[39820,3170]`  这行从 39.82s 开始，持续 3.17s
 *   - `(start,dur,0)` 后面紧跟的 token 是一个字 / 一个词的时间块
 *     - start: 该 token 在歌曲里的**绝对**起始毫秒
 *     - dur:   该 token 持续毫秒（一拍内被唱完的时间）
 *     - 第三段 `0` 是网易云某种 hint 标记，前端不消费
 *
 * 一些细节：
 *   - 行首可能有 metadata 行 `{"t":"..."}` 之类的 JSON 元信息，跳过
 *   - 中文会切到视觉字；连续英文/数字按词保留，避免逐字母跳动
 *   - 同一拍可以塞多个字（罕见但合法）
 *   - 失败 / 行没字 token 时降级成"行级"单 token，让上层照样能渲染
 *
 * 输出按行的开始时间升序排列，用 [findActiveLineIdx](lrc.ts) 同样的二分查找定位。
 */

export type YrcChar = {
  /** 该字在歌曲里的绝对秒数起点 */
  startSec: number;
  /** 持续秒数 */
  durSec: number;
  /** 字的文本（可能含尾空格 / 标点） */
  text: string;
};

export type YrcLine = {
  /** 行起点秒 */
  time: number;
  /** 行持续秒 */
  durSec: number;
  /** 整行拼起来 —— 没有逐字数据时给 LRC 风格回退用 */
  text: string;
  /** 字时间块。空数组 = 这行没解到逐字，按 LRC 走 */
  chars: YrcChar[];
};

const LINE_HEADER_RE = /^\[(\d+),(\d+)\]/;
const TOKEN_RE = /\((\d+),(\d+),(?:-?\d+)\)([^()[\]]*)/g;
const OFFSET_TAG_RE = /\[(?:offset|offsetMs)\s*:\s*([+-]?\d+)]/i;
const JSON_OFFSET_RE = /"offset"\s*:\s*([+-]?\d+)/i;

export function parseYrc(raw: string | null | undefined): YrcLine[] {
  if (!raw) return [];
  const out: YrcLine[] = [];
  const offsetMs = parseOffsetMs(raw);
  for (const rawLine of raw.split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line) continue;
    // 跳过 JSON 元信息行
    if (line.startsWith("{")) continue;

    const header = LINE_HEADER_RE.exec(line);
    if (!header) continue;
    const lineStartMs = Math.max(0, Number(header[1]) + offsetMs);
    const lineDurMs = Number(header[2]);
    if (!Number.isFinite(lineStartMs) || !Number.isFinite(lineDurMs)) continue;

    // 把 header 后面的 token 都抠出来
    const rest = line.slice(header[0].length);
    TOKEN_RE.lastIndex = 0;
    const charsRaw: YrcChar[] = [];
    let m: RegExpExecArray | null;
    while ((m = TOKEN_RE.exec(rest)) !== null) {
      const startMs = Math.max(0, Number(m[1]) + offsetMs);
      const durMs = Number(m[2]);
      const text = m[3];
      if (!Number.isFinite(startMs) || !Number.isFinite(durMs)) continue;
      if (text == null) continue;
      if (text.length === 0) continue;
      const groups = splitIntoVisualChars(text);
      if (groups.length === 0) continue;
      const perDur = durMs / groups.length;
      groups.forEach((charText, idx) => {
        charsRaw.push({
          startSec: (startMs + perDur * idx) / 1000,
          durSec: (idx === groups.length - 1 ? durMs - perDur * idx : perDur) / 1000,
          text: charText,
        });
      });
    }

    const chars = mergeAdjacentAsciiLyricChars(charsRaw);
    const text = chars.length > 0 ? chars.map((c) => c.text).join("") : "";
    if (chars.length === 0 && !text) continue; // 行解析失败且没文本

    out.push({
      time: lineStartMs / 1000,
      durSec: lineDurMs / 1000,
      text,
      chars,
    });
  }
  out.sort((a, b) => a.time - b.time);

  // 翻译行去重：网易云对带翻译的英文歌会把翻译塞进同一个 yrc 字段，表现为
  // 下一行起点时间戳和前一行几乎相同（差 < 50ms）。这种叠加会让逐字 wipe
  // 在原词刚结束就被翻译行的"假逐字"覆盖一遍 —— 字进度乱跳，体验很糟。
  // 同一时间点只保留第一行（原词），翻译彻底丢掉。
  const dedup: YrcLine[] = [];
  for (const line of out) {
    const prev = dedup[dedup.length - 1];
    if (prev && Math.abs(line.time - prev.time) < 0.05) continue;
    dedup.push(line);
  }
  return dedup;
}

/**
 * 算某个字在 positionSec 时刻的"播放进度"，用于 wipe 动画。
 *   - 还没到 → 0
 *   - 已唱完 → 1
 *   - 正在唱 → 0..1，按 (now - start) / dur 线性
 */
export function charProgress(c: YrcChar, positionSec: number): number {
  if (positionSec <= c.startSec) return 0;
  if (positionSec >= c.startSec + c.durSec) return 1;
  return (positionSec - c.startSec) / Math.max(0.001, c.durSec);
}

function parseOffsetMs(raw: string): number {
  return Number(OFFSET_TAG_RE.exec(raw)?.[1] ?? JSON_OFFSET_RE.exec(raw)?.[1] ?? 0) || 0;
}

function splitIntoVisualChars(text: string): string[] {
  if (!text) return [];
  const out: string[] = [];
  let lastWasAsciiWord = false;
  for (const c of Array.from(text)) {
    const isCjk = /[\u3400-\u9fff\u3040-\u30ff\uac00-\ud7af]/u.test(c);
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
      lastWasAsciiWord = false;
    }
  }
  return out;
}

function mergeAdjacentAsciiLyricChars(chars: YrcChar[]): YrcChar[] {
  if (chars.length <= 1) return chars;
  const merged: YrcChar[] = [];
  for (const char of chars) {
    const prev = merged[merged.length - 1];
    if (prev && shouldMergeAsciiFragments(prev.text, char.text)) {
      const start = Math.min(prev.startSec, char.startSec);
      const end = Math.max(prev.startSec + prev.durSec, char.startSec + char.durSec);
      merged[merged.length - 1] = {
        startSec: start,
        durSec: Math.max(0.001, end - start),
        text: prev.text + char.text,
      };
    } else {
      merged.push(char);
    }
  }
  return merged;
}

function shouldMergeAsciiFragments(left: string, right: string): boolean {
  if (!isAsciiWordFragment(left) || !isAsciiWordFragment(right)) return false;
  if (/\s$/.test(left) || /^\s/.test(right)) return false;
  const leftTail = left.at(-1);
  const rightHead = right.at(0);
  if (!leftTail || !rightHead) return false;
  return isAsciiWordJoiner(leftTail) || isAsciiWordJoiner(rightHead);
}

function isAsciiWordFragment(value: string): boolean {
  let hasAsciiWord = false;
  for (const c of Array.from(value)) {
    if (/[\u3400-\u9fff\u3040-\u30ff\uac00-\ud7af]/u.test(c)) return false;
    if (/[a-zA-Z0-9]/.test(c)) hasAsciiWord = true;
  }
  return hasAsciiWord;
}

function isAsciiWordJoiner(c: string): boolean {
  return c === "'" || c === "’" || c === "-" || /[a-zA-Z0-9]/.test(c);
}
