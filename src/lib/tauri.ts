// 薄 Tauri IPC 包装：SSR 安全 + 强类型 netease 命令。
// 之前那一批 webview 控制（openMusicSite / musicCtl）已经被扔掉 —— 音乐走原生 API。

import type { InvokeArgs } from "@tauri-apps/api/core";

function isTauri(): boolean {
  return typeof window !== "undefined" && "__TAURI_INTERNALS__" in window;
}

export async function invoke<T = unknown>(
  cmd: string,
  args?: InvokeArgs,
): Promise<T> {
  if (!isTauri()) {
    throw new Error(
      `[claudio] "${cmd}" 只能在 Tauri 桌面壳里运行。当前是浏览器预览。`,
    );
  }
  const { invoke } = await import("@tauri-apps/api/core");
  return invoke<T>(cmd, args);
}

// ---------- netease 强类型绑定 ----------

export type UserProfile = {
  userId: number;
  nickname: string;
  avatarUrl?: string | null;
};

export type PlaylistInfo = {
  id: number;
  name: string;
  trackCount: number;
  coverImgUrl?: string | null;
  userId?: number | null;
  /** weapi `updateTime`（ms）。做增量同步：缓存里存的对不上才拉详情。 */
  updateTime?: number | null;
};

export type ArtistShort = { id: number; name: string };
export type AlbumShort = { id: number; name: string; picUrl?: string | null };

export type TrackInfo = {
  id: number;
  name: string;
  /** duration in ms (weapi's `dt`) */
  durationMs: number;
  artists: ArtistShort[];
  album?: AlbumShort | null;
};

export type PlaylistDetail = {
  id: number;
  name: string;
  trackCount: number;
  coverImgUrl?: string | null;
  /** 同 PlaylistInfo.updateTime；详情页也带，好让缓存层落盘时一并更新 */
  updateTime?: number | null;
  tracks: TrackInfo[];
};

export type SongUrl = {
  id: number;
  url: string | null;
  br: number;
  size: number;
};

export type QrStart = { key: string; qrContent: string };
export type QrCheck = { code: number; message?: string | null; nickname?: string | null };

/** 歌词原始包：Rust 侧从 weapi `song/lyric` 直接 shape 过来 */
export type LyricData = {
  /** LRC 格式原词（含 `[mm:ss.xx]` 时间戳）。纯音乐 / 无歌词 = null */
  lyric: string | null;
  /** 译词，可空 */
  translation: string | null;
  /** 纯音乐（网易云 `nolyric`）*/
  instrumental: boolean;
  /** 网易云自己标的"收录不全" */
  uncollected: boolean;
};

// ---------- commands ----------

export const netease = {
  qrStart: () => invoke<QrStart>("netease_qr_start"),
  qrCheck: (key: string) => invoke<QrCheck>("netease_qr_check", { key }),
  account: () => invoke<UserProfile | null>("netease_account"),
  userPlaylists: (uid: number, limit?: number) =>
    invoke<PlaylistInfo[]>("netease_user_playlists", { uid, limit }),
  playlistDetail: (id: number) =>
    invoke<PlaylistDetail>("netease_playlist_detail", { id }),
  songUrls: (ids: number[], level?: string) =>
    invoke<SongUrl[]>("netease_song_urls", { ids, level }),
  songLyric: (id: number) => invoke<LyricData>("netease_song_lyric", { id }),
  /** 关键词搜歌（type=1 单曲）—— 库外推荐流水线用 */
  search: (query: string, limit?: number) =>
    invoke<TrackInfo[]>("netease_search", { query, limit }),
  isLoggedIn: () => invoke<boolean>("netease_is_logged_in"),
};

// ---------- AI (DeepSeek) ----------

/**
 * 前端能拿到的 AI 配置视图 —— 永远不包含完整 key。
 * `keyPreview` 形如 "sk-a•••xyz9"，只给用户确认"这是我填的那把"。
 */
export type AiConfigPublic = {
  hasKey: boolean;
  keyPreview: string | null;
  model: string;
  baseUrl: string;
};

export const ai = {
  getConfig: () => invoke<AiConfigPublic>("ai_get_config"),
  setApiKey: (key: string) => invoke<void>("ai_set_api_key", { key }),
  clearApiKey: () => invoke<void>("ai_clear_api_key"),
  /** 快速 ping：DeepSeek 回一句 DJ 口吻的短问候，用来验 key 能跑通 */
  ping: () => invoke<string>("ai_ping"),
  /** 通用 chat。system 可省；temperature / maxTokens 不给时后端默认 0.8 / 400 */
  chat: (args: {
    system?: string;
    user: string;
    temperature?: number;
    maxTokens?: number;
  }) => invoke<string>("ai_chat", args),
};

// ---------- 本地缓存 ----------
//
// 为什么要这个：
//   用户歌单几十张、每张几百首，每次进 /distill 都重新拉 weapi = 又慢又浪费。
//   Rust 侧用 SQLite（bundled）落盘。TS 侧走 SWR：先 `cache.*` 给界面灌显示，
//   再异步 `netease.*` 拉最新，对比 `updateTime` 变了才 upsert + 重绘。

export type CachedPlaylist = {
  id: number;
  name: string;
  coverImgUrl: string | null;
  trackCount: number;
  updateTime: number | null;
  /** unix seconds（Rust 里拿 SystemTime::now().duration_since(UNIX_EPOCH)）*/
  syncedAt: number;
};

export type CachedPlaylistDetail = CachedPlaylist & {
  tracks: TrackInfo[];
};

export const cache = {
  // --- playlists ---
  getPlaylists: (uid: number) =>
    invoke<CachedPlaylist[]>("cache_get_playlists", { uid }),
  savePlaylists: (uid: number, items: PlaylistInfo[]) =>
    invoke<void>("cache_save_playlists", { uid, items }),

  // --- playlist detail ---
  getPlaylistDetail: (id: number) =>
    invoke<CachedPlaylistDetail | null>("cache_get_playlist_detail", { id }),
  savePlaylistDetail: (uid: number, detail: PlaylistDetail) =>
    invoke<void>("cache_save_playlist_detail", { uid, detail }),

  // --- lyrics（永远命中就用，歌词基本不变） ---
  getLyric: (trackId: number) =>
    invoke<LyricData | null>("cache_get_lyric", { trackId }),
  saveLyric: (trackId: number, lyric: LyricData) =>
    invoke<void>("cache_save_lyric", { trackId, lyric }),

  // --- app_state KV（上次播放歌曲 / 位置 等） ---
  getState: (key: string) =>
    invoke<string | null>("cache_get_state", { key }),
  setState: (key: string, value: string) =>
    invoke<void>("cache_set_state", { key, value }),
};
