"use client";

import { useEffect, useState } from "react";
import { getAppSettingsSnapshot } from "./app-settings";

export type AiAnnouncementKind = "pet" | "session" | "mix" | "system";

export type AiAnnouncement = {
  id: number;
  text: string;
  kind: AiAnnouncementKind;
  createdAt: number;
};

type AnnounceOptions = {
  kind?: AiAnnouncementKind;
  ttlMs?: number;
  force?: boolean;
};

let current: AiAnnouncement | null = null;
let nextId = 1;
let clearTimer: ReturnType<typeof setTimeout> | null = null;
let lastText = "";
let lastAt = 0;
const listeners = new Set<(item: AiAnnouncement | null) => void>();

function emit() {
  for (const listener of listeners) listener(current);
}

export function announceAi(text: string, options: AnnounceOptions = {}): void {
  const clean = compactText(text);
  if (!clean) return;
  const settings = getAppSettingsSnapshot();
  if (!settings.aiNarration && !options.force) return;

  const now = Date.now();
  if (!options.force && clean === lastText && now - lastAt < 10_000) return;
  lastText = clean;
  lastAt = now;

  if (clearTimer) {
    clearTimeout(clearTimer);
    clearTimer = null;
  }

  current = {
    id: nextId++,
    text: clean,
    kind: options.kind ?? "pet",
    createdAt: now,
  };
  emit();

  clearTimer = setTimeout(() => {
    current = null;
    clearTimer = null;
    emit();
  }, options.ttlMs ?? 5600);
}

export function clearAiAnnouncement(): void {
  if (clearTimer) clearTimeout(clearTimer);
  clearTimer = null;
  current = null;
  emit();
}

export function useAiAnnouncement(): AiAnnouncement | null {
  const [item, setItem] = useState<AiAnnouncement | null>(current);
  useEffect(() => {
    listeners.add(setItem);
    setItem(current);
    return () => {
      listeners.delete(setItem);
    };
  }, []);
  return item;
}

function compactText(text: string): string {
  return text
    .replace(/\s+/g, " ")
    .replace(/[。！？!?,，、；;：:]+$/g, "。")
    .trim()
    .slice(0, 34);
}
