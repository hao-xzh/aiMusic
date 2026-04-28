/**
 * LRC 解析。
 *
 * LRC 格式：每行一个或多个 `[mm:ss.xx]` 时间戳 + 文本。
 * 同一句话可能对应多个时间戳（副歌复现），要展开成独立记录。
 *
 * 例：
 *   [00:12.00][00:34.20]So we back in the club
 *   -> [{time: 12.0, text: "So we back..."},
 *       {time: 34.2, text: "So we back..."}]
 *
 * 返回的数组按 time 升序，前端 O(log n) 二分找当前行。
 */

export type LrcLine = {
  time: number; // seconds
  text: string;
};

const TS_RE = /\[(\d+):(\d+(?:\.\d+)?)\]/g;

export function parseLrc(raw: string | null | undefined): LrcLine[] {
  if (!raw) return [];
  const out: LrcLine[] = [];
  for (const rawLine of raw.split(/\r?\n/)) {
    // 扫所有时间戳
    const stamps: Array<{ time: number; end: number }> = [];
    let lastEnd = 0;
    TS_RE.lastIndex = 0;
    let m: RegExpExecArray | null;
    while ((m = TS_RE.exec(rawLine)) !== null) {
      const min = Number(m[1]);
      const sec = Number(m[2]);
      if (Number.isFinite(min) && Number.isFinite(sec)) {
        stamps.push({ time: min * 60 + sec, end: m.index + m[0].length });
        lastEnd = m.index + m[0].length;
      }
    }
    if (stamps.length === 0) continue;
    const text = rawLine.slice(lastEnd).trim();
    if (!text) continue; // 跳过空行 / metadata 行（[ti:..] [ar:..] 之类）
    for (const s of stamps) {
      out.push({ time: s.time, text });
    }
  }
  out.sort((a, b) => a.time - b.time);
  return out;
}

/**
 * 二分查找当前 positionSec 对应的 LRC 行索引。
 * "当前行" = 最后一个 time <= positionSec 的行。
 * 没有命中（positionSec 在第一行之前）返回 -1。
 */
export function findActiveLineIdx(lines: LrcLine[], positionSec: number): number {
  if (lines.length === 0) return -1;
  let lo = 0;
  let hi = lines.length - 1;
  let ans = -1;
  while (lo <= hi) {
    const mid = (lo + hi) >> 1;
    if (lines[mid].time <= positionSec) {
      ans = mid;
      lo = mid + 1;
    } else {
      hi = mid - 1;
    }
  }
  return ans;
}
