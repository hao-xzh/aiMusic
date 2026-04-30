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
 *   - 字是整字，不是字符 —— 网易云已经分好（"Some "  比如带后置空格）
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

export function parseYrc(raw: string | null | undefined): YrcLine[] {
  if (!raw) return [];
  const out: YrcLine[] = [];
  for (const rawLine of raw.split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line) continue;
    // 跳过 JSON 元信息行
    if (line.startsWith("{")) continue;

    const header = LINE_HEADER_RE.exec(line);
    if (!header) continue;
    const lineStartMs = Number(header[1]);
    const lineDurMs = Number(header[2]);
    if (!Number.isFinite(lineStartMs) || !Number.isFinite(lineDurMs)) continue;

    // 把 header 后面的 token 都抠出来
    const rest = line.slice(header[0].length);
    TOKEN_RE.lastIndex = 0;
    const chars: YrcChar[] = [];
    let m: RegExpExecArray | null;
    while ((m = TOKEN_RE.exec(rest)) !== null) {
      const startMs = Number(m[1]);
      const durMs = Number(m[2]);
      const text = m[3];
      if (!Number.isFinite(startMs) || !Number.isFinite(durMs)) continue;
      if (text == null) continue;
      // 空字也保留（有时候 yrc 拿一个空块占位歌词的呼吸点）
      chars.push({
        startSec: startMs / 1000,
        durSec: durMs / 1000,
        text,
      });
    }

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
  return (positionSec - c.startSec) / c.durSec;
}
