"use client";

import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";

import { type LrcLine } from "@/lib/lrc";
import { type PlayerAPI } from "@/lib/player-state";
import { type YrcChar, type YrcLine } from "@/lib/yrc";
import {
  APPLE_DESKTOP_ARTWORK_SHADOW,
  APPLE_DESKTOP_ARTWORK_RADIOSITY_FILTER,
  APPLE_DESKTOP_BACKDROP_ARTWORK_FILTER,
  APPLE_DESKTOP_BACKDROP_ARTWORK_OPACITY,
  APPLE_DESKTOP_BACKDROP_VEIL,
  APPLE_DESKTOP_COVER_HALO_OPACITY,
  APPLE_DESKTOP_LYRIC_COLUMN_VEIL_BLEED,
  APPLE_DESKTOP_LYRIC_COLUMN_VEIL,
  APPLE_LYRIC_FONT_FAMILY,
  ImmersiveLyrics,
  LyricColumn,
  appleArtworkRadiusCss,
  appleBackdropBaseRgb,
  computeLayout,
  cssUrl,
} from "@/components/PlayerCard";

declare global {
  interface Window {
    __setAppleLyricsFixturePosition?: (sec: number) => void;
    __setAppleLyricsFixtureSupplementaryVisible?: (visible: boolean) => void;
    __openAppleLyricsTransitionFixture?: () => void;
    __closeAppleLyricsTransitionFixture?: () => void;
  }
}

function appleLyricsPerformanceNow(): number {
  return typeof performance !== "undefined" ? performance.now() : Date.now();
}

const APPLE_LYRICS_VERIFY_COVER =
  "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 1000 1000'%3E%3Cdefs%3E%3CradialGradient id='a' cx='34%25' cy='41%25' r='58%25'%3E%3Cstop offset='0%25' stop-color='%23ff3b7a'/%3E%3Cstop offset='45%25' stop-color='%2300d6ff'/%3E%3Cstop offset='100%25' stop-color='%2308101e'/%3E%3C/radialGradient%3E%3ClinearGradient id='b' x1='0' x2='1' y1='0' y2='1'%3E%3Cstop offset='0' stop-color='%231d1243'/%3E%3Cstop offset='.5' stop-color='%23f2448f' stop-opacity='.72'/%3E%3Cstop offset='1' stop-color='%2300e0d3' stop-opacity='.86'/%3E%3C/linearGradient%3E%3C/defs%3E%3Crect width='1000' height='1000' fill='url(%23a)'/%3E%3Ccircle cx='312' cy='390' r='220' fill='url(%23b)' opacity='.92'/%3E%3Cpath d='M120 650c240-160 480 130 760-80' fill='none' stroke='%23ffffff' stroke-opacity='.25' stroke-width='34'/%3E%3Cpath d='M166 736c250-118 504 78 704-52' fill='none' stroke='%2300f5ff' stroke-opacity='.32' stroke-width='22'/%3E%3C/svg%3E";
const APPLE_LYRICS_VERIFY_START_SEC = 4.6;
const APPLE_LYRICS_VERIFY_END_SEC = 34.5;
const APPLE_LYRICS_VERIFY_DEFAULT_SONG_ID = "mixed";
type AppleLyricsFixtureMode = "fullscreen" | "transition";
type AppleLyricsFixtureClock = "raf" | "held" | "coarse";

type AppleLyricsFixtureSong = {
  id: string;
  title: string;
  subtitle: string;
  startSec: number;
  endSec: number;
  yrcLines: YrcLine[];
  lrcLines: LrcLine[];
};

function appleLyricsFixtureInitialState(): { songId: string; positionSec: number; isPlaying: boolean } {
  if (typeof window === "undefined") {
    return {
      songId: APPLE_LYRICS_VERIFY_DEFAULT_SONG_ID,
      positionSec: APPLE_LYRICS_VERIFY_START_SEC,
      isPlaying: true,
    };
  }
  const params = new URLSearchParams(window.location.search);
  const songId = params.get("song") ?? APPLE_LYRICS_VERIFY_DEFAULT_SONG_ID;
  const song = appleLyricsFixtureSong(songId);
  const requestedPosition = Number(params.get("position"));
  const positionSec = Number.isFinite(requestedPosition)
    ? Math.max(song.startSec, Math.min(song.endSec, requestedPosition))
    : song.startSec;
  const isPlaying = params.get("playing") !== "0";
  return { songId: song.id, positionSec, isPlaying };
}

function appleLyricsFixtureMode(): AppleLyricsFixtureMode {
  if (typeof window === "undefined") return "fullscreen";
  return new URLSearchParams(window.location.search).get("mode") === "transition"
    ? "transition"
    : "fullscreen";
}

function appleLyricsFixtureClock(): AppleLyricsFixtureClock {
  if (typeof window === "undefined") return "raf";
  const clock = new URLSearchParams(window.location.search).get("clock");
  return clock === "held" || clock === "coarse" ? clock : "raf";
}

function advanceAppleLyricsFixturePosition(
  positionSec: number,
  dtSec: number,
  song: AppleLyricsFixtureSong,
): number {
  const next = positionSec + dtSec;
  return next > song.endSec ? song.startSec : next;
}

function appleLyricsFixtureSupplementaryVisible(): boolean {
  if (typeof window === "undefined") return true;
  const params = new URLSearchParams(window.location.search);
  return params.get("supplementary") !== "0";
}

function appleLyricsFixtureSongWithSupplementary(
  song: AppleLyricsFixtureSong,
  visible: boolean,
): AppleLyricsFixtureSong {
  if (visible) return song;
  return {
    ...song,
    yrcLines: song.yrcLines.map((line) => ({
      ...line,
      companionLines: (line.companionLines ?? []).filter(
        (companion) =>
          companion.role !== "translation" &&
          companion.role !== "romaji" &&
          companion.role !== "background-translation",
      ),
    })),
  };
}

function makeFixtureChar(startSec: number, durSec: number, text: string, supplementaryText?: string): YrcChar {
  return {
    startSec,
    durSec,
    text,
    supplementaryText,
    timingParts: [{ startSec, durSec, text }],
  };
}

function makeFixtureLine(
  time: number,
  tokens: Array<[number, number, string, string?]>,
  companionLines: YrcLine[] = [],
  alignment: "start" | "end" = "start",
): YrcLine {
  const chars = tokens.map(([startSec, durSec, text, supplementaryText]) =>
    makeFixtureChar(startSec, durSec, text, supplementaryText));
  const text = chars.map((char) => char.text).join("");
  const last = chars[chars.length - 1];
  const durSec = last ? Math.max(0.001, last.startSec + last.durSec - time) : 2;
  return { time, durSec, text, chars, companionLines, alignment, role: "primary" };
}

function makeStaticFixtureLine(
  time: number,
  durSec: number,
  text: string,
  role: "translation" | "romaji" | "companion" | "background-translation" = "translation",
  alignment: "start" | "end" = "start",
): YrcLine {
  return { time, durSec, text, chars: [], role, alignment };
}

function makeTimedFixtureCompanion(
  time: number,
  tokens: Array<[number, number, string]>,
  role: "companion" | "translation" = "companion",
): YrcLine {
  const chars = tokens.map(([startSec, durSec, text]) => makeFixtureChar(startSec, durSec, text));
  const text = chars.map((char) => char.text).join("");
  const last = chars[chars.length - 1];
  return {
    time,
    durSec: last ? Math.max(0.001, last.startSec + last.durSec - time) : 2,
    text,
    chars,
    role,
    alignment: "start",
  };
}

const APPLE_LYRICS_VERIFY_YRC_LINES: YrcLine[] = [
  makeFixtureLine(5.0, [
    [5.0, 0.36, "You "],
    [5.38, 0.44, "see "],
    [5.86, 0.64, "through"],
  ], [makeStaticFixtureLine(5.0, 1.65, "你看得太透", "translation")]),
  makeFixtureLine(8.0, [
    [8.0, 0.34, "That "],
    [8.36, 0.34, "I "],
    [8.74, 0.42, "just "],
    [9.18, 1.18, "wanna "],
    [10.42, 0.32, "get "],
    [10.78, 0.36, "with "],
    [11.18, 1.26, "you"],
  ], [makeStaticFixtureLine(8.0, 4.7, "我是想和你在一起", "translation")]),
  makeFixtureLine(13.0, [
    [13.0, 0.44, "You "],
    [13.52, 1.2, "right "],
    [14.78, 0.56, "I"],
  ], [makeStaticFixtureLine(13.0, 2.6, "You right I", "romaji")]),
  makeFixtureLine(17.0, [
    [17.0, 0.35, "Got "],
    [17.4, 0.34, "my "],
    [17.8, 1.08, "guy"],
  ], [
    makeTimedFixtureCompanion(17.18, [
      [17.18, 0.32, "(got "],
      [17.54, 0.4, "my "],
      [18.0, 0.88, "guy)"],
    ]),
    makeStaticFixtureLine(17.18, 1.9, "背景：我已经有人了", "background-translation"),
  ]),
  makeFixtureLine(21.2, [
    [21.2, 0.36, "But "],
    [21.6, 0.58, "I, "],
    [22.24, 1.12, "I"],
  ], [makeStaticFixtureLine(21.2, 2.4, "可是我，我", "translation")], "end"),
  makeFixtureLine(25.0, [
    [25.0, 0.46, "Can't "],
    [25.5, 0.34, "help "],
    [25.88, 0.32, "it "],
    [26.24, 0.62, "I "],
    [26.9, 1.35, "want "],
    [28.34, 1.15, "you"],
  ], [makeStaticFixtureLine(25.0, 4.9, "控制不住，我想要你", "translation")]),
  makeFixtureLine(31.0, [
    [31.0, 1.16, "慢"],
    [32.18, 1.12, "词"],
    [33.34, 0.42, " now"],
  ], [makeStaticFixtureLine(31.0, 3.0, "中文慢词验证", "translation")]),
];

const APPLE_LYRICS_PURE_YRC_LINES: YrcLine[] = [
  makeFixtureLine(5.0, [
    [5.0, 0.32, "Lights "],
    [5.38, 0.42, "go "],
    [5.88, 0.68, "low"],
  ]),
  makeFixtureLine(8.0, [
    [8.0, 0.36, "Hold "],
    [8.4, 0.44, "on "],
    [8.92, 1.22, "slowly "],
    [10.22, 0.38, "now"],
  ]),
  makeFixtureLine(13.0, [
    [13.0, 0.4, "Rise "],
    [13.46, 0.36, "and "],
    [13.88, 0.78, "fall"],
  ]),
  makeFixtureLine(17.0, [
    [17.0, 0.38, "Back "],
    [17.44, 0.42, "to "],
    [17.94, 0.9, "ground"],
  ]),
];

const APPLE_LYRICS_TOKEN_SUPPLEMENTARY_LINES: YrcLine[] = [
  makeFixtureLine(5.0, [
    [5.0, 0.34, "Falling ", "fall ing"],
    [5.42, 0.52, "through ", "through"],
    [6.02, 0.58, "starlight", "star light"],
  ]),
  makeFixtureLine(9.0, [
    [9.0, 0.36, "Kimi ", "ki mi"],
    [9.44, 0.38, "no ", "no"],
    [9.9, 0.72, "soba ", "so ba"],
    [10.7, 0.64, "now", "now"],
  ]),
  makeFixtureLine(13.0, [
    [13.0, 0.34, "Next "],
    [13.42, 0.48, "plain "],
    [13.98, 0.62, "line"],
  ]),
];

const APPLE_LYRICS_TRANSLATED_ROMAJI_LINES: YrcLine[] = [
  makeFixtureLine(5.0, [
    [5.0, 0.36, "Blue "],
    [5.42, 0.42, "moon"],
  ], [makeStaticFixtureLine(5.0, 1.4, "蓝色月亮", "translation")]),
  makeFixtureLine(8.0, [
    [8.0, 0.34, "That "],
    [8.36, 0.34, "I "],
    [8.74, 0.42, "just "],
    [9.18, 1.18, "wanna "],
    [10.42, 0.32, "stay"],
  ], [makeStaticFixtureLine(8.0, 2.9, "我只是想留下", "translation")]),
  makeFixtureLine(13.0, [
    [13.0, 0.38, "Kimi ", "ki mi"],
    [13.46, 0.44, "no ", "no"],
    [13.96, 0.82, "soba", "so ba"],
  ], [makeStaticFixtureLine(13.0, 2.4, "ki mi no so ba", "romaji")]),
  makeFixtureLine(17.0, [
    [17.0, 1.1, "慢"],
    [18.18, 1.08, "慢"],
  ], [makeStaticFixtureLine(17.0, 2.7, "中文慢词", "translation")]),
];

const APPLE_LYRICS_LONG_SUPPLEMENTARY_LINES: YrcLine[] = [
  makeFixtureLine(5.0, [
    [5.0, 0.34, "Every "],
    [5.4, 0.38, "little "],
    [5.84, 0.58, "signal "],
    [6.48, 0.42, "keeps "],
    [6.96, 0.64, "glowing"],
  ], [
    makeStaticFixtureLine(
      5.0,
      2.6,
      "这是一段很长的翻译，用来覆盖真实歌曲里会换成两行甚至更多行的情况",
      "translation",
    ),
  ]),
  makeFixtureLine(9.0, [
    [9.0, 0.34, "Nothing "],
    [9.42, 0.36, "stays "],
    [9.84, 0.56, "quiet "],
    [10.48, 0.4, "when "],
    [10.96, 0.8, "you"],
  ], [
    makeStaticFixtureLine(
      9.0,
      2.8,
      "no thing stays qui et when you",
      "romaji",
    ),
  ]),
  makeFixtureLine(13.2, [
    [13.2, 0.32, "Pull "],
    [13.58, 0.34, "the "],
    [13.98, 0.66, "whole "],
    [14.72, 0.78, "line"],
  ], [
    makeStaticFixtureLine(
      13.2,
      2.9,
      "继续显示翻译，但不能溢出到下一句歌词上",
      "translation",
    ),
  ]),
];

const APPLE_LYRICS_CROWDED_LINES: YrcLine[] = [
  makeFixtureLine(5.0, [
    [5.0, 0.34, "You "],
    [5.42, 0.4, "see "],
    [5.9, 0.58, "through "],
    [6.56, 0.38, "all "],
    [7.02, 0.56, "of "],
    [7.66, 0.8, "this"],
  ], [
    makeStaticFixtureLine(
      5.0,
      3.0,
      "这是一句非常长的上一行翻译，用来逼出真实窗口里多行叠到一起的问题，还会继续补上一段文字，确保它超过 Apple reveal 的五十像素高度",
      "translation",
    ),
  ]),
  makeFixtureLine(8.0, [
    [8.0, 0.32, "That "],
    [8.38, 0.34, "I "],
    [8.78, 0.38, "just "],
    [9.22, 1.08, "wanna "],
    [10.42, 0.32, "get "],
    [10.82, 0.36, "with "],
    [11.26, 0.42, "you, "],
    [11.76, 0.3, "I "],
    [12.14, 0.34, "just "],
    [12.56, 0.92, "wanna "],
    [13.56, 0.38, "stay"],
  ], [
    makeStaticFixtureLine(
      8.0,
      6.0,
      "我是想和你在一起，也想留下来，把这行翻译撑到多行去验证占位，尤其是在窄窗口里不能继续往下一句歌词身上画出去",
      "translation",
    ),
    makeStaticFixtureLine(
      8.0,
      6.0,
      "that i just wan na get with you i just wan na stay and keep this romanized helper long enough to exceed the reveal box",
      "romaji",
    ),
  ]),
  makeFixtureLine(14.6, [
    [14.6, 0.38, "You "],
    [15.06, 0.9, "right "],
    [16.04, 0.42, "I"],
  ], [
    makeStaticFixtureLine(
      14.6,
      2.5,
      "下一行也有翻译，不能贴到当前行底部，也不能被上一行超长副文本覆盖",
      "translation",
    ),
  ]),
  makeFixtureLine(18.0, [
    [18.0, 0.34, "Got "],
    [18.42, 0.34, "my "],
    [18.84, 0.92, "guy"],
  ], [
    makeStaticFixtureLine(18.0, 2.4, "我已经有人了", "translation"),
  ]),
];

const APPLE_LYRICS_COMPANION_DUET_LINES: YrcLine[] = [
  makeFixtureLine(5.0, [
    [5.0, 0.35, "Got "],
    [5.4, 0.34, "my "],
    [5.8, 1.08, "guy"],
  ], [
    makeTimedFixtureCompanion(5.18, [
      [5.18, 0.32, "(got "],
      [5.54, 0.4, "my "],
      [6.0, 0.88, "guy)"],
    ]),
    makeStaticFixtureLine(5.18, 1.9, "背景：我已经有人了", "background-translation"),
  ]),
  makeFixtureLine(9.2, [
    [9.2, 0.36, "But "],
    [9.6, 0.58, "I, "],
    [10.24, 1.12, "I"],
  ], [makeStaticFixtureLine(9.2, 2.4, "可是我，我", "translation")], "end"),
  makeFixtureLine(13.0, [
    [13.0, 0.44, "Call "],
    [13.5, 0.42, "me "],
    [14.0, 0.9, "back"],
  ], [makeStaticFixtureLine(13.0, 2.1, "回我电话", "translation")]),
];

const APPLE_LYRICS_OVERLAP_CURRENT_LINES: YrcLine[] = [
  {
    ...makeFixtureLine(5.0, [
      [5.0, 0.38, "Long "],
      [5.46, 0.52, "hold"],
    ]),
    durSec: 5.0,
  },
  makeFixtureLine(6.0, [
    [6.0, 0.24, "Short "],
    [6.28, 0.26, "pop"],
  ]),
  makeFixtureLine(11.0, [
    [11.0, 0.34, "Return "],
    [11.42, 0.5, "home"],
  ]),
];

const APPLE_LYRICS_RAPID_SWITCH_LINES: YrcLine[] = [
  makeFixtureLine(5.0, [
    [5.0, 0.12, "Quick "],
    [5.14, 0.12, "one"],
  ]),
  makeFixtureLine(5.32, [
    [5.32, 0.12, "Quick "],
    [5.46, 0.12, "two"],
  ]),
  makeFixtureLine(5.64, [
    [5.64, 0.12, "Quick "],
    [5.78, 0.18, "three"],
  ]),
  makeFixtureLine(6.2, [
    [6.2, 0.18, "Settle "],
    [6.42, 0.2, "down"],
  ]),
];

const APPLE_LYRICS_INTERLUDE_GAP_LINES: YrcLine[] = [
  makeFixtureLine(5.0, [
    [5.0, 0.42, "Before "],
    [5.48, 0.56, "gap"],
  ], [makeStaticFixtureLine(5.0, 1.4, "空白之前", "translation")]),
  makeFixtureLine(20.0, [
    [20.0, 0.4, "After "],
    [20.46, 0.58, "gap"],
  ], [makeStaticFixtureLine(20.0, 1.7, "空白之后", "translation")]),
];

const APPLE_LYRICS_VERIFY_SONGS: Record<string, AppleLyricsFixtureSong> = {
  mixed: makeFixtureSong(
    "mixed",
    "Apple Lyrics Fixture",
    "translation / romaji / companion / duet",
    APPLE_LYRICS_VERIFY_YRC_LINES,
    APPLE_LYRICS_VERIFY_START_SEC,
    APPLE_LYRICS_VERIFY_END_SEC,
  ),
  "pure-yrc": makeFixtureSong(
    "pure-yrc",
    "Pure YRC Fixture",
    "timed words / slow emphasis",
    APPLE_LYRICS_PURE_YRC_LINES,
    4.6,
    20.5,
  ),
  "token-supplementary": makeFixtureSong(
    "token-supplementary",
    "Token Supplementary Fixture",
    "word-level pronunciation only",
    APPLE_LYRICS_TOKEN_SUPPLEMENTARY_LINES,
    4.6,
    16.5,
  ),
  "translated-romaji": makeFixtureSong(
    "translated-romaji",
    "Translated Fixture",
    "translation / romaji / CJK slow words",
    APPLE_LYRICS_TRANSLATED_ROMAJI_LINES,
    4.6,
    20.5,
  ),
  "long-supplementary": makeFixtureSong(
    "long-supplementary",
    "Long Supplementary Fixture",
    "long translation / romaji clipping",
    APPLE_LYRICS_LONG_SUPPLEMENTARY_LINES,
    4.6,
    18.0,
  ),
  "crowded-lines": makeFixtureSong(
    "crowded-lines",
    "Crowded Lines Fixture",
    "wrapped primary / translation spacing",
    APPLE_LYRICS_CROWDED_LINES,
    4.6,
    21.0,
  ),
  "companion-duet": makeFixtureSong(
    "companion-duet",
    "Companion Fixture",
    "background vocal / right duet",
    APPLE_LYRICS_COMPANION_DUET_LINES,
    4.6,
    16.5,
  ),
  "overlap-current": makeFixtureSong(
    "overlap-current",
    "Overlap Timing Fixture",
    "begin/end current-index parity",
    APPLE_LYRICS_OVERLAP_CURRENT_LINES,
    4.6,
    13.0,
  ),
  "rapid-switch": makeFixtureSong(
    "rapid-switch",
    "Rapid Switch Fixture",
    "overlapping Apple scroll animations",
    APPLE_LYRICS_RAPID_SWITCH_LINES,
    4.6,
    8.0,
  ),
  "interlude-gap": makeFixtureSong(
    "interlude-gap",
    "Interlude Gap Fixture",
    "Apple collapsible instrumental line",
    APPLE_LYRICS_INTERLUDE_GAP_LINES,
    4.6,
    23.0,
  ),
};

function makeFixtureSong(
  id: string,
  title: string,
  subtitle: string,
  yrcLines: YrcLine[],
  startSec: number,
  endSec: number,
): AppleLyricsFixtureSong {
  return {
    id,
    title,
    subtitle,
    startSec,
    endSec,
    yrcLines,
    lrcLines: yrcLines.map((line) => ({ time: line.time, text: line.text })),
  };
}

function appleLyricsFixtureSong(id: string): AppleLyricsFixtureSong {
  return APPLE_LYRICS_VERIFY_SONGS[id] ?? APPLE_LYRICS_VERIFY_SONGS[APPLE_LYRICS_VERIFY_DEFAULT_SONG_ID]!;
}

export default function DevOnlyLyricsFixture() {
  const [mode, setMode] = useState<AppleLyricsFixtureMode>(appleLyricsFixtureMode);
  useEffect(() => {
    setMode(appleLyricsFixtureMode());
  }, []);
  return mode === "transition"
    ? <AppleLyricsTransitionVerificationScene />
    : <AppleLyricsFullscreenVerificationScene />;
}

function AppleLyricsFullscreenVerificationScene() {
  const initialFixtureState = useMemo(() => appleLyricsFixtureInitialState(), []);
  const [songId, setSongId] = useState(initialFixtureState.songId);
  const [positionSec, setPositionSec] = useState(initialFixtureState.positionSec);
  const [isPlaying, setIsPlaying] = useState(initialFixtureState.isPlaying);
  const [showSupplementary, setShowSupplementary] = useState(appleLyricsFixtureSupplementaryVisible);
  const clockMode = appleLyricsFixtureClock();
  const song = appleLyricsFixtureSong(songId);
  const renderedSong = useMemo(
    () => appleLyricsFixtureSongWithSupplementary(song, showSupplementary),
    [showSupplementary, song],
  );
  const layout = computeLayout(
    true,
    "26,20,42",
    "7,13,23",
    "72,22,54",
    "8,28,48",
    "rgba(255,255,255,0.86)",
    "rgba(255,255,255,0.25)",
  );
  const fixtureBackdropColor = `rgb(${appleBackdropBaseRgb(
    "26,20,42",
    "7,13,23",
    "72,22,54",
    "8,28,48",
  )})`;
  const fixtureBackdropImage = "none";

  useEffect(() => {
    if (!isPlaying) return;
    if (clockMode === "held") return;
    let raf = 0;
    let lastMs = appleLyricsPerformanceNow();
    let coarseCarrySec = 0;
    const tick = (nowMs: number) => {
      const dtSec = Math.min(0.08, Math.max(0, (nowMs - lastMs) / 1000));
      lastMs = nowMs;
      if (clockMode === "coarse") {
        coarseCarrySec += dtSec;
        if (coarseCarrySec >= 0.05) {
          const stepSec = coarseCarrySec;
          coarseCarrySec = 0;
          setPositionSec((prev) => advanceAppleLyricsFixturePosition(prev, stepSec, song));
        }
      } else {
        setPositionSec((prev) => advanceAppleLyricsFixturePosition(prev, dtSec, song));
      }
      raf = window.requestAnimationFrame(tick);
    };
    raf = window.requestAnimationFrame(tick);
    return () => window.cancelAnimationFrame(raf);
  }, [clockMode, isPlaying, song.endSec, song.startSec]);

  const seek = useCallback((sec: number) => setPositionSec(sec), []);

  useEffect(() => {
    window.__setAppleLyricsFixturePosition = (sec: number) => {
      setIsPlaying(false);
      setPositionSec(sec);
    };
    window.__setAppleLyricsFixtureSupplementaryVisible = (visible: boolean) => {
      setShowSupplementary(visible);
    };
    return () => {
      delete window.__setAppleLyricsFixturePosition;
      delete window.__setAppleLyricsFixtureSupplementaryVisible;
    };
  }, []);

  return (
    <main
      data-apple-lyrics-fixture="true"
      data-song-id={song.id}
      data-position-sec={positionSec.toFixed(3)}
      className="appleLyricsFullscreen"
      style={{
        position: "fixed",
        inset: 0,
        overflow: "hidden",
        backgroundColor: fixtureBackdropColor,
        backgroundImage: fixtureBackdropImage,
        color: "white",
      }}
    >
      <div
        aria-hidden
        data-apple-lyrics-backdrop="true"
        style={{
          position: "absolute",
          inset: 0,
          overflow: "hidden",
          backgroundColor: fixtureBackdropColor,
          backgroundImage: fixtureBackdropImage,
          backgroundPosition: "50%",
          backgroundRepeat: "no-repeat",
          backgroundSize: "cover",
        }}
      >
        <div
          aria-hidden
          data-apple-lyrics-backdrop-artwork="true"
          style={{
            position: "absolute",
            inset: "-16%",
            backgroundImage: cssUrl(APPLE_LYRICS_VERIFY_COVER),
            backgroundSize: "cover",
            backgroundPosition: "center",
            filter: APPLE_DESKTOP_BACKDROP_ARTWORK_FILTER,
            opacity: APPLE_DESKTOP_BACKDROP_ARTWORK_OPACITY,
            transform: "scale(1.22)",
            pointerEvents: "none",
          }}
        />
        <div
          aria-hidden
          data-apple-lyrics-backdrop-veil="true"
          style={{
            position: "absolute",
            inset: 0,
            background: APPLE_DESKTOP_BACKDROP_VEIL,
            pointerEvents: "none",
          }}
        />
      </div>

      <div
        aria-hidden
        data-apple-lyrics-cover-halo="true"
        style={{
          position: "absolute",
          ...layout.cover,
          zIndex: 1,
          pointerEvents: "none",
          opacity: APPLE_DESKTOP_COVER_HALO_OPACITY,
          backgroundImage: cssUrl(APPLE_LYRICS_VERIFY_COVER),
          backgroundSize: "cover",
          backgroundPosition: "center",
          filter: APPLE_DESKTOP_ARTWORK_RADIOSITY_FILTER,
          transform: "scale(0.92)",
          transformOrigin: "bottom center",
          WebkitMaskImage: layout.coverHaloMask,
          maskImage: layout.coverHaloMask,
          borderRadius: appleArtworkRadiusCss(),
        }}
      />

      <div
        data-apple-lyrics-cover="true"
        style={{
          position: "absolute",
          ...layout.cover,
          overflow: "hidden",
          zIndex: 2,
          borderRadius: appleArtworkRadiusCss(),
          boxShadow: APPLE_DESKTOP_ARTWORK_SHADOW,
          WebkitMaskImage: layout.coverMask,
          WebkitMaskComposite: "source-in",
          maskImage: layout.coverMask,
          maskComposite: layout.coverMaskComposite,
        }}
      >
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img
          src={APPLE_LYRICS_VERIFY_COVER}
          alt=""
          style={{ width: "100%", height: "100%", objectFit: "cover", display: "block" }}
        />
      </div>

      <div
        data-apple-lyrics-title="true"
        style={{
          position: "absolute",
          left: layout.title.left,
          width: layout.title.width,
          top: layout.title.top,
          zIndex: 4,
          textAlign: "center",
          fontFamily: APPLE_LYRIC_FONT_FAMILY,
        }}
      >
        <div style={{ fontSize: 24, lineHeight: 1.15, fontWeight: 800 }}>{song.title}</div>
        <div style={{ marginTop: 8, fontSize: 14, lineHeight: 1.2, fontWeight: 700, opacity: 0.5 }}>
          {song.subtitle}
        </div>
      </div>

      <div
        data-apple-lyrics-column-veil="true"
        aria-hidden
          style={{
            position: "absolute",
            top: layout.lyric.top,
            left: `calc(${layout.lyric.left} - ${APPLE_DESKTOP_LYRIC_COLUMN_VEIL_BLEED})`,
            right: 0,
            height: layout.lyric.height,
            overflow: "hidden",
            zIndex: 4,
            background: APPLE_DESKTOP_LYRIC_COLUMN_VEIL,
            opacity: 0,
          pointerEvents: "none",
        }}
      />

      <div
        data-apple-lyrics-column="true"
        style={{
          position: "absolute",
          ...layout.lyric,
          maxWidth: 972.8,
          overflow: "hidden",
          zIndex: 5,
          backgroundColor: "transparent",
          backgroundImage: "none",
          mixBlendMode: "plus-lighter",
        }}
      >
        <div
          data-apple-lyrics-text-blend="true"
          style={{
            position: "relative",
            width: "100%",
            height: "100%",
          }}
        >
          <LyricColumn
            lines={renderedSong.lrcLines}
            yrcLines={renderedSong.yrcLines}
            positionSec={positionSec}
            isPlaying={isPlaying}
            sessionId={`apple-lyrics-verification-${song.id}`}
            meta={{
              lines: renderedSong.lrcLines,
              yrcLines: renderedSong.yrcLines,
              instrumental: false,
              uncollected: false,
            }}
            desktopAppleMotion={true}
            fgColor="rgba(255,255,255,1)"
            fgDimColor="rgba(255,255,255,0.25)"
            fgUnsungColor="rgba(255,255,255,0.35)"
            onSeekToSec={seek}
          />
        </div>
      </div>

      <div
        style={{
          position: "fixed",
          right: 24,
          bottom: 22,
          zIndex: 8,
          display: "flex",
          alignItems: "center",
          gap: 12,
          padding: "10px 12px",
          borderRadius: 8,
          background: "rgba(0,0,0,0.42)",
          backdropFilter: "blur(18px)",
          fontFamily: APPLE_LYRIC_FONT_FAMILY,
        }}
      >
        <button
          type="button"
          onClick={() => setIsPlaying((value) => !value)}
          style={{
            width: 34,
            height: 30,
            border: 0,
            borderRadius: 6,
            color: "white",
            background: "rgba(255,255,255,0.14)",
            cursor: "pointer",
          }}
        >
          {isPlaying ? "II" : "▶"}
        </button>
        <input
          aria-label="fixture position"
          type="range"
          min={song.startSec}
          max={song.endSec}
          step={0.01}
          value={positionSec}
          onChange={(event) => setPositionSec(Number(event.currentTarget.value))}
          style={{ width: 260 }}
        />
        <span style={{ minWidth: 52, fontSize: 12, opacity: 0.72 }}>{positionSec.toFixed(2)}s</span>
      </div>
    </main>
  );
}

function AppleLyricsTransitionVerificationScene() {
  const song = appleLyricsFixtureSong(APPLE_LYRICS_VERIFY_DEFAULT_SONG_ID);
  const [open, setOpen] = useState(false);
  const [immersiveActive, setImmersiveActive] = useState(false);
  const [positionSec, setPositionSec] = useState(8.2);
  const [isPlaying, setIsPlaying] = useState(false);
  const coverRef = useRef<HTMLDivElement>(null);
  const lyricRef = useRef<HTMLButtonElement>(null);
  const sourceCoverRect = useCallback(() => coverRef.current?.getBoundingClientRect() ?? null, []);
  const sourceLyricRect = useCallback(() => lyricRef.current?.getBoundingClientRect() ?? null, []);

  const player = useMemo<PlayerAPI>(() => {
    const noopAsync = async () => {};
    return {
      current: {
        id: song.id,
        title: song.title,
        artist: song.subtitle,
        album: "Apple lyrics transition fixture",
        cover: APPLE_LYRICS_VERIFY_COVER,
        durationSec: song.endSec,
      },
      queue: [],
      isPlaying,
      positionSec,
      aiStatus: "播放中",
      error: null,
      lyric: {
        lines: song.lrcLines,
        yrcLines: song.yrcLines,
        instrumental: false,
        uncollected: false,
      },
      mood: null,
      playNetease: noopAsync,
      warmTrackUrls: () => {},
      insertNext: noopAsync,
      insertNextBatch: noopAsync,
      pause: () => setIsPlaying(false),
      resume: () => setIsPlaying(true),
      toggle: () => setIsPlaying((value) => !value),
      next: () => {},
      prev: () => {},
      seek: (sec: number) => {
        setIsPlaying(false);
        setPositionSec(sec);
      },
      setAiStatus: () => {},
      setMood: () => {},
      like: () => {},
      dislike: () => {},
    };
  }, [isPlaying, positionSec, song]);

  useEffect(() => {
    window.__openAppleLyricsTransitionFixture = () => setOpen(true);
    window.__closeAppleLyricsTransitionFixture = () => setOpen(false);
    return () => {
      delete window.__openAppleLyricsTransitionFixture;
      delete window.__closeAppleLyricsTransitionFixture;
    };
  }, []);

  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    if (params.get("autoOpen") !== "1") return;
    const timer = window.setTimeout(() => setOpen(true), 260);
    return () => window.clearTimeout(timer);
  }, []);

  return (
    <main
      data-apple-lyrics-transition-fixture="true"
      data-open={open ? "1" : "0"}
      style={{
        position: "fixed",
        inset: 0,
        overflow: "hidden",
        display: "grid",
        placeItems: "center",
        background: "#05060a",
        color: "white",
        fontFamily: APPLE_LYRIC_FONT_FAMILY,
      }}
    >
      <section
        style={{
          width: 420,
          display: "flex",
          flexDirection: "column",
          alignItems: "center",
          opacity: immersiveActive ? 0 : 1,
          transition: "opacity 200ms ease",
        }}
      >
        <div
          ref={coverRef}
          data-transition-source-cover="true"
          style={{
            width: 360,
            height: 360,
            overflow: "hidden",
            borderRadius: 12,
            boxShadow: "0 14px 40px rgba(0,0,0,.32)",
          }}
        >
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img
            src={APPLE_LYRICS_VERIFY_COVER}
            alt=""
            style={{ width: "100%", height: "100%", objectFit: "cover", display: "block" }}
          />
        </div>
        <div style={{ width: 360, marginTop: 24, textAlign: "left" }}>
          <div style={{ fontSize: 22, lineHeight: 1.2, fontWeight: 800 }}>{song.title}</div>
          <div style={{ marginTop: 6, fontSize: 14, fontWeight: 700, opacity: 0.54 }}>{song.subtitle}</div>
        </div>
        <button
          ref={lyricRef}
          data-transition-source-lyric="true"
          onClick={() => setOpen(true)}
          style={{
            marginTop: 30,
            width: 360,
            height: 52,
            border: 0,
            borderRadius: 8,
            background: "rgba(255,255,255,.08)",
            color: "rgba(255,255,255,.88)",
            font: "inherit",
            fontWeight: 800,
            cursor: "pointer",
          }}
        >
          打开歌词
        </button>
      </section>

      <ImmersiveLyrics
        open={open}
        sourceCoverRect={sourceCoverRect}
        sourceLyricRect={sourceLyricRect}
        player={player}
        onClose={() => setOpen(false)}
        onActiveChange={setImmersiveActive}
      />
    </main>
  );
}
