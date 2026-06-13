import type { YrcChar, YrcLine, YrcLineAlignment, YrcLineRole, YrcTimingPart } from "./yrc";

const XML_NS = "http://www.w3.org/XML/1998/namespace";
const TTM_NS = "http://www.w3.org/ns/ttml#metadata";
const DEFAULT_AGENT_ID = "v1";
const AGENT_TYPE_GROUP = "group";

type AgentInfo = {
  id: string;
  type: string | null;
};

type ParsedP = {
  beginSec: number;
  endSec: number;
  agent: string | null;
  text: string;
  chars: YrcChar[];
  companions: YrcLine[];
};

export function parseAmllTtml(ttml: string): YrcLine[] {
  if (!ttml.trim()) return [];
  if (typeof DOMParser === "undefined") return [];
  const doc = new DOMParser().parseFromString(ttml, "application/xml");
  if (doc.getElementsByTagName("parsererror").length > 0) return [];

  const agents = new Map<string, AgentInfo>();
  for (const agent of elementsByLocalName(doc, "agent")) {
    const parsed = parseAgent(agent);
    if (parsed) agents.set(parsed.id, parsed);
  }

  const primaries: YrcLine[] = [];
  let baseAgentId: string | null = null;
  for (const p of elementsByLocalName(doc, "p")) {
    const parsed = parseP(p);
    if (!parsed) continue;
    const agentId = parsed.agent ?? DEFAULT_AGENT_ID;
    const agent = agents.get(agentId);
    const isGroup = agent?.type === AGENT_TYPE_GROUP;
    let alignment: YrcLineAlignment = "start";
    if (!isGroup) {
      if (baseAgentId == null) baseAgentId = agentId;
      alignment = agentId !== baseAgentId ? "end" : "start";
    }
    primaries.push(toYrcLine(parsed, "primary", alignment));
  }

  return primaries.sort((a, b) => a.time - b.time);
}

function parseAgent(el: Element): AgentInfo | null {
  const id = attr(el, "id", XML_NS) ?? attr(el, "id");
  if (!id) return null;
  return {
    id,
    type: attr(el, "type", TTM_NS) ?? attr(el, "type"),
  };
}

function parseP(el: Element): ParsedP | null {
  const beginSec = parseTimeAttr(el, "begin");
  const endSec = parseTimeAttr(el, "end");
  if (beginSec == null || endSec == null || endSec <= beginSec) return null;

  const chars: YrcChar[] = [];
  const companions: YrcLine[] = [];
  let text = "";

  for (const node of Array.from(el.childNodes)) {
    if (node.nodeType === Node.TEXT_NODE) {
      const value = node.textContent ?? "";
      if (!value) continue;
      text += value;
      appendTextToLastChar(chars, value);
      continue;
    }
    if (node.nodeType !== Node.ELEMENT_NODE) continue;
    const child = node as Element;
    if (child.localName !== "span") continue;

    const role = attr(child, "role", TTM_NS) ?? attr(child, "role");
    if (role === "x-translation" || role === "x-roman") {
      const companionText = directText(child).trim();
      if (companionText) {
        companions.push({
          time: beginSec,
          durSec: endSec - beginSec,
          text: companionText,
          chars: [],
          role: role === "x-roman" ? "romaji" : "translation",
        });
      }
      continue;
    }

    if (role === "x-bg") {
      const bgBegin = parseTimeAttr(child, "begin") ?? beginSec;
      const bgEnd = parseTimeAttr(child, "end") ?? endSec;
      const bgLine = parseBackgroundVocalSpan(child, bgBegin, bgEnd);
      if (bgLine) companions.push(bgLine);
      continue;
    }

    if (role == null) {
      const spanText = directText(child);
      if (!spanText) continue;
      text += spanText;
      const spanBegin = parseTimeAttr(child, "begin");
      const spanEnd = parseTimeAttr(child, "end");
      if (spanBegin != null && spanEnd != null && spanEnd > spanBegin) {
        chars.push({
          startSec: spanBegin,
          durSec: spanEnd - spanBegin,
          text: spanText,
          timingParts: [{ startSec: spanBegin, durSec: spanEnd - spanBegin, text: spanText }],
        });
      }
    }
  }

  const mergedChars = mergeAdjacentAsciiLyricChars(chars);
  const finalText = text || mergedChars.map((char) => char.text).join("");
  if (!finalText.trim()) return null;
  return {
    beginSec,
    endSec,
    agent: attr(el, "agent", TTM_NS) ?? attr(el, "agent"),
    text: finalText,
    chars: mergedChars,
    companions,
  };
}

function parseBackgroundVocalSpan(el: Element, beginSec: number, endSec: number): YrcLine | null {
  const chars: YrcChar[] = [];
  let text = "";
  for (const node of Array.from(el.childNodes)) {
    if (node.nodeType === Node.TEXT_NODE) {
      const value = node.textContent ?? "";
      if (!value) continue;
      text += value;
      appendTextToLastChar(chars, value);
      continue;
    }
    if (node.nodeType !== Node.ELEMENT_NODE) continue;
    const child = node as Element;
    if (child.localName !== "span") continue;
    const role = attr(child, "role", TTM_NS) ?? attr(child, "role");
    if (role != null) continue;

    const spanText = directText(child);
    if (!spanText) continue;
    text += spanText;
    const spanBegin = parseTimeAttr(child, "begin");
    const spanEnd = parseTimeAttr(child, "end");
    if (spanBegin != null && spanEnd != null && spanEnd > spanBegin) {
      chars.push({
        startSec: spanBegin,
        durSec: spanEnd - spanBegin,
        text: spanText,
        timingParts: [{ startSec: spanBegin, durSec: spanEnd - spanBegin, text: spanText }],
      });
    }
  }

  const mergedChars = mergeAdjacentAsciiLyricChars(chars);
  const finalText = text || mergedChars.map((char) => char.text).join("");
  if (!finalText.trim()) return null;
  return {
    time: beginSec,
    durSec: Math.max(0.001, endSec - beginSec),
    text: finalText,
    chars: mergedChars,
    role: "companion",
  };
}

function toYrcLine(parsed: ParsedP, role: YrcLineRole, alignment: YrcLineAlignment): YrcLine {
  return {
    time: parsed.beginSec,
    durSec: Math.max(0.001, parsed.endSec - parsed.beginSec),
    text: parsed.text,
    chars: parsed.chars,
    role,
    alignment,
    companionLines: parsed.companions.length > 0 ? parsed.companions : undefined,
  };
}

function elementsByLocalName(doc: Document, name: string): Element[] {
  return Array.from(doc.getElementsByTagName("*")).filter((el) => el.localName === name);
}

function attr(el: Element, name: string, namespace?: string): string | null {
  return (
    (namespace ? el.getAttributeNS(namespace, name) : null) ??
    el.getAttribute(name) ??
    el.getAttribute(`ttm:${name}`) ??
    el.getAttribute(`xml:${name}`)
  );
}

function parseTimeAttr(el: Element, name: string): number | null {
  return parseTtmlTime(attr(el, name));
}

function parseTtmlTime(value: string | null): number | null {
  if (!value) return null;
  const s = value.trim();
  if (!s) return null;
  if (/^\d+(?:\.\d+)?ms$/i.test(s)) return Number.parseFloat(s) / 1000;
  if (/^\d+(?:\.\d+)?s$/i.test(s)) return Number.parseFloat(s);
  if (/^\d+$/.test(s)) return Number.parseFloat(s) / 1000;
  const parts = s.split(":");
  if (parts.length < 2 || parts.length > 3) return null;
  const nums = parts.map((part) => Number.parseFloat(part));
  if (nums.some((n) => !Number.isFinite(n))) return null;
  return parts.length === 3 ? nums[0]! * 3600 + nums[1]! * 60 + nums[2]! : nums[0]! * 60 + nums[1]!;
}

function directText(el: Element): string {
  let text = "";
  for (const node of Array.from(el.childNodes)) {
    if (node.nodeType === Node.TEXT_NODE) text += node.textContent ?? "";
  }
  return text;
}

function appendTextToLastChar(chars: YrcChar[], text: string) {
  if (chars.length === 0) return;
  const last = chars[chars.length - 1]!;
  chars[chars.length - 1] = { ...last, text: last.text + text };
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
        timingParts: [...timingPartsOrSelf(prev), ...timingPartsOrSelf(char)],
      };
    } else if (prev && shouldMergeAsciiFragments(prev.text, char.text)) {
      const start = Math.min(prev.startSec, char.startSec);
      const parts = [...timingPartsOrSelf(prev), ...timingPartsOrSelf(char)].sort((a, b) => a.startSec - b.startSec);
      const end = Math.max(...parts.map((part) => part.startSec + Math.max(0.001, part.durSec)));
      merged[merged.length - 1] = {
        startSec: start,
        durSec: Math.max(0.001, end - start),
        text: prev.text + char.text,
        timingParts: parts,
      };
    } else {
      merged.push(char);
    }
  }
  return merged;
}

function timingPartsOrSelf(char: YrcChar): YrcTimingPart[] {
  return char.timingParts && char.timingParts.length > 0
    ? char.timingParts
    : [{ startSec: char.startSec, durSec: char.durSec, text: char.text }];
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
