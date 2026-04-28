"use client";

/**
 * LLM JSON 宽容解析器。
 *
 * 实际模型偶尔会把 JSON 包在 markdown、前后加解释、留下 trailing comma，
 * 或者输出多个对象。这里做“尽量提取一个完整 JSON 对象”的轻量修复，
 * 让业务层不用因为格式毛刺直接失败。
 */

export function parseJsonObjectLike(raw: string): unknown | null {
  for (const candidate of extractJsonCandidates(raw)) {
    const parsed = parseCandidate(candidate);
    if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) {
      return parsed;
    }
  }
  return null;
}

function parseCandidate(candidate: string): unknown | null {
  const variants = [
    candidate,
    stripCodeFence(candidate),
    repairJson(candidate),
    repairJson(stripCodeFence(candidate)),
  ];
  for (const text of variants) {
    try {
      return JSON.parse(text);
    } catch {
      // try next variant
    }
  }
  return null;
}

function extractJsonCandidates(raw: string): string[] {
  const cleaned = stripCodeFence(raw.replace(/^\uFEFF/, "").trim());
  const out: string[] = [];
  const direct = cleaned.trim();
  if (direct.startsWith("{") && direct.endsWith("}")) out.push(direct);

  for (let i = 0; i < cleaned.length; i++) {
    if (cleaned[i] !== "{") continue;
    const end = findBalancedObjectEnd(cleaned, i);
    if (end > i) out.push(cleaned.slice(i, end + 1));
  }

  return [...new Set(out)].sort((a, b) => b.length - a.length);
}

function findBalancedObjectEnd(text: string, start: number): number {
  let depth = 0;
  let inString = false;
  let escaped = false;

  for (let i = start; i < text.length; i++) {
    const ch = text[i];
    if (inString) {
      if (escaped) {
        escaped = false;
      } else if (ch === "\\") {
        escaped = true;
      } else if (ch === "\"") {
        inString = false;
      }
      continue;
    }

    if (ch === "\"") {
      inString = true;
      continue;
    }
    if (ch === "{") depth++;
    else if (ch === "}") {
      depth--;
      if (depth === 0) return i;
    }
  }
  return -1;
}

function stripCodeFence(text: string): string {
  let s = text.trim();
  if (s.startsWith("```")) {
    s = s.replace(/^```(?:json|JSON)?\s*/i, "").replace(/```\s*$/i, "");
  }
  return s.trim();
}

function repairJson(text: string): string {
  return text
    .trim()
    .replace(/[“”]/g, "\"")
    .replace(/[‘’]/g, "'")
    .replace(/,\s*([}\]])/g, "$1");
}
