"use client";

import type { MusicIntent } from "./music-intent";
import type { TrackInfo } from "./tauri";

const VERSION_WORDS = ["live", "remix", "伴奏", "纯音乐", "cover", "翻唱"];

export function normalizeTitle(name: string): string {
  return name
    .toLowerCase()
    .replace(/\s+/g, "")
    .replace(/（.*?）|\(.*?\)|\[.*?\]/g, "")
    .replace(/live|remix|伴奏|纯音乐|cover|翻唱/g, "");
}

export function songKey(t: TrackInfo): string {
  const title = normalizeTitle(t.name);
  const artist = normalizeTitle(t.artists[0]?.name ?? "");
  return `${title}::${artist}`;
}

export function dedupeSimilarTracks<T extends { track: TrackInfo }>(items: T[]): T[] {
  const seen = new Set<string>();
  const out: T[] = [];

  for (const item of items) {
    const key = songKey(item.track);
    if (seen.has(key)) continue;
    seen.add(key);
    out.push(item);
  }

  return out;
}

export function dedupeTrackInfos(items: TrackInfo[]): TrackInfo[] {
  const seen = new Set<string>();
  const out: TrackInfo[] = [];

  for (const track of items) {
    const key = songKey(track);
    if (seen.has(key)) continue;
    seen.add(key);
    out.push(track);
  }

  return out;
}

export function queryExplicitlyMentionsTrack(track: TrackInfo, intent: MusicIntent): boolean {
  const title = normalizeTitle(track.name);
  const artist = normalizeTitle(track.artists[0]?.name ?? "");
  const album = normalizeTitle(track.album?.name ?? "");
  const trackHints = intent.textHints.tracks.map(normalizeTitle).filter(Boolean);
  const artistHints = intent.textHints.artists.map(normalizeTitle).filter(Boolean);
  const albumHints = intent.textHints.albums.map(normalizeTitle).filter(Boolean);

  return (
    (title.length > 0 &&
      trackHints.some((hint) => title.includes(hint) || hint.includes(title))) ||
    (artist.length > 0 &&
      artistHints.some((hint) => artist.includes(hint) || hint.includes(artist))) ||
    (album.length > 0 &&
      albumHints.some((hint) => album.includes(hint) || hint.includes(album)))
  );
}

export function queryAsksForSpecificVersion(intent: MusicIntent): boolean {
  const text = [
    ...intent.textHints.tracks,
    ...intent.textHints.artists,
    ...intent.textHints.albums,
    ...intent.musicHints.genres,
    ...intent.musicHints.moods,
  ]
    .join(" ")
    .toLowerCase();
  return VERSION_WORDS.some((word) => text.includes(word));
}
