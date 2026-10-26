import { pwaInvoke } from "./api";
import type { AiConfigPublic, LyricData, PlaylistInfo, TrackInfo, UserProfile } from "@/lib/tauri";

export type NativeTrack = {
  id: string; neteaseId?: number; title: string; artist: string; album: string;
  artworkUrl?: string | null; durationMs: number; streamUrl?: string;
};
export type NativePlaylist = PlaylistInfo & { specialType?: number };
export type NativeSettings = {
  hideAiPetOrb: boolean; lyricTranslation: boolean; aiNarration: boolean;
  playbackMode: "PlaylistLoop" | "OrderOnce" | "ShufflePlay" | "SingleLoop" | "AiRadio";
  userFacts: string; personaId: string;
};
export const defaultSettings: NativeSettings = {
  hideAiPetOrb: true, lyricTranslation: false, aiNarration: false,
  playbackMode: "PlaylistLoop", userFacts: "", personaId: "toxic",
};
export function nativeTrack(track: TrackInfo): NativeTrack {
  return { id: String(track.id), neteaseId: track.id, title: track.name || "Unknown track", artist: track.artists?.map(artist => artist.name).filter(Boolean).join(", ") || "Unknown artist", album: track.album?.name || "Unknown album", artworkUrl: track.album?.picUrl, durationMs: track.durationMs || 0 };
}
export function songKey(track: NativeTrack) {
  return `${normalizeTitle(track.title)}::${normalizeTitle(track.artist.split(/\/|&|,| feat\.|feat\./)[0]?.trim() || "")}`;
}
export const normalizeTitle=(value:string) => value.toLocaleLowerCase().replace(/\s+/g,"").replace(/（.*?）|\(.*?\)|\[.*?\]/g,"").replace(/live|remix|伴奏|纯音乐|cover|翻唱/gi,"");
export const titleFamily=(value:string) => /(?<![a-z])(?:city\s*[-_]?\s*)?pop(?![a-z])/i.test(value.toLocaleLowerCase().replace(/（.*?）|\(.*?\)|\[.*?\]/g," ").replace(/live|remix|伴奏|纯音乐|cover|翻唱/gi," ")) ? "family:pop" : normalizeTitle(value);
async function tracksCommand(command: string, args?: unknown): Promise<NativeTrack[]> {
  return (await pwaInvoke<TrackInfo[]>(command, args)).map(nativeTrack);
}
export const repository = {
  account: () => pwaInvoke<UserProfile | null>("netease_account"),
  logout: () => pwaInvoke<void>("netease_logout"),
  playlists: (uid: number) => pwaInvoke<NativePlaylist[]>("netease_user_playlists", { uid, limit: 1000 }),
  playlistTrackIds: (id: number) => pwaInvoke<number[]>("netease_playlist_track_ids", { id }),
  playlistTracks: async (id: number) => (await pwaInvoke<{ tracks: TrackInfo[] }>("netease_playlist_detail", { id })).tracks.map(nativeTrack),
  cloudTracks: () => tracksCommand("netease_user_cloud_tracks"),
  search: async (query: string, limit = 30, offset = 0, signal?: AbortSignal) => (await pwaInvoke<TrackInfo[]>("netease_search", { query, limit, offset }, signal)).map(nativeTrack),
  daily: () => tracksCommand("netease_daily_recommended_tracks"),
  personalFm: () => tracksCommand("netease_personal_fm_tracks"),
  similar: (trackId: number) => tracksCommand("netease_similar_tracks", { trackId }),
  songUrls: (ids: number[], level = "lossless") => pwaInvoke<Array<{ id: number; url: string | null; br: number; size: number }>>("netease_song_urls", { ids, level }),
  lyrics: (id: number) => pwaInvoke<LyricData>("netease_song_lyric", { id }),
  like: async (id: number, like: boolean) => { const result = await pwaInvoke<{ ok: boolean }>("netease_like_song", { id, like }); if (!result.ok) throw new Error("收藏操作未成功，请重试。"); },
  createPlaylist: async (name: string) => (await pwaInvoke<{ id: number }>("netease_playlist_create", { name })).id,
  modifyPlaylist: async (playlistId: number, op: "add" | "del", trackIds: number[]) => { const result = await pwaInvoke<{ ok: boolean }>("netease_playlist_modify_tracks", { playlistId, op, trackIds }); if (!result.ok) throw new Error("歌单更新未成功，请重试。"); },
  aiConfig: () => pwaInvoke<AiConfigPublic>("ai_get_config"),
};
