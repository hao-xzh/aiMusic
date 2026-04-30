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

/** 发短信结果。code=200 已发，其它如 503 = 触发风控（频繁请求），message 给前端展示。 */
export type CaptchaSent = { code: number; message?: string | null };
/** 验证码登录结果。code=200 = 成功，cookie 已落盘；其它附带 message。 */
export type PhoneLogin = {
  code: number;
  message?: string | null;
  nickname?: string | null;
};

/** 歌词原始包：Rust 侧从 weapi `song/lyric` 直接 shape 过来 */
export type LyricData = {
  /** LRC 格式原词（含 `[mm:ss.xx]` 时间戳）。纯音乐 / 无歌词 = null */
  lyric: string | null;
  /** 译词，可空 */
  translation: string | null;
  /**
   * 逐字 yrc 原文（karaoke）。每行 `[lineMs,durMs](charMs,durMs,0)c(...)c` 格式。
   * 网易云对收录较好的主流流行歌返回；冷门 / 民谣常常没有，null。
   * 解析见 [yrc.ts](yrc.ts)。
   */
  yrc?: string | null;
  /** 纯音乐（网易云 `nolyric`）*/
  instrumental: boolean;
  /** 网易云自己标的"收录不全" */
  uncollected: boolean;
};

// ---------- commands ----------

export const netease = {
  qrStart: () => invoke<QrStart>("netease_qr_start"),
  qrCheck: (key: string) => invoke<QrCheck>("netease_qr_check", { key }),
  /** 给手机发验证码。ctcode 不传 = 86（中国大陆）。 */
  captchaSent: (phone: string, ctcode?: number) =>
    invoke<CaptchaSent>("netease_captcha_sent", { phone, ctcode }),
  /** 拿验证码换 cookie。code=200 后跟扫码登录成功一样，已自动 persist。 */
  phoneLogin: (phone: string, captcha: string, ctcode?: number) =>
    invoke<PhoneLogin>("netease_phone_login", { phone, captcha, ctcode }),
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

// ---------- AI (多 provider: DeepSeek / OpenAI / 小米 MiMo) ----------

/** 后端固定支持的 provider id */
export type ProviderId = "deepseek" | "openai" | "xiaomi-mimo";

/**
 * 单个 provider 的状态视图。永远不回传完整 key，只给 hasKey + 预览。
 */
export type ProviderView = {
  id: ProviderId;
  label: string;
  hasKey: boolean;
  keyPreview: string | null;
  model: string;
  baseUrl: string;
};

export type AiConfigPublic = {
  /** 当前激活的 provider id */
  activeProvider: ProviderId;
  providers: ProviderView[];
};

export type ModelOption = {
  id: string;
  label: string;
};

export const ai = {
  getConfig: () => invoke<AiConfigPublic>("ai_get_config"),
  /** 拉某 provider 的已知模型列表（高 → 低排序） */
  listModels: (provider: ProviderId) =>
    invoke<ModelOption[]>("ai_list_models", { provider }),
  /** 切换激活 provider —— 切完 chat/ping 自动走新 provider */
  setProvider: (provider: ProviderId) =>
    invoke<void>("ai_set_provider", { provider }),
  /** 给某 provider 写 / 改 / 清 key（传空串等同 clear） */
  setApiKey: (provider: ProviderId, key: string) =>
    invoke<void>("ai_set_api_key", { provider, key }),
  clearApiKey: (provider: ProviderId) =>
    invoke<void>("ai_clear_api_key", { provider }),
  /** 给某 provider 选模型 —— 持久化，下次启动也记得 */
  setModel: (provider: ProviderId, model: string) =>
    invoke<void>("ai_set_model", { provider, model }),
  /** 快速 ping：当前激活 provider 回一句 DJ 口吻的短问候 */
  ping: () => invoke<string>("ai_ping"),
  /** 通用 chat（走当前激活 provider）。temperature/maxTokens 不传时后端默认 0.8/400 */
  chat: (args: {
    system?: string;
    user: string;
    temperature?: number;
    maxTokens?: number;
  }) => invoke<string>("ai_chat", args),
};

// ---------- 音频磁盘缓存 ----------
//
// 后端会自动接管：URL 一旦走 `claudio-audio://localhost/?id=...&u=...` scheme，
// 命中本地直接读、miss 才拉网络 + 落盘。前端只需要：
//   1) 用 `wrapAudioUrl(trackId, neteaseUrl)` 把直链包起来
//   2) 在设置页用下面这三个 cmd 看用量 / 改上限 / 清空

export type AudioCacheStats = {
  totalBytes: number;
  count: number;
  maxBytes: number;
};

/**
 * 原生（Symphonia）解出来的声学特征。AI 选曲、画像 prompt 都吃这份 ground truth。
 * 跟 [audio-analysis.ts](audio-analysis.ts) 里 JS 算出来的更细的"接歌信号"是两套：
 * 这层管"歌的物理属性"，那层管"接歌的具体策略"。
 */
export type AudioFeatures = {
  trackId: number;
  durationS: number;
  /** 60-200 BPM；解不出来 = null */
  bpm: number | null;
  /** 0..1 自相关主峰强度 */
  bpmConfidence: number;
  /** 整曲 RMS dBFS（负数） */
  rmsDb: number;
  /** 整曲峰值 dBFS（≤0） */
  peakDb: number;
  /** peak - rms，越大动态范围越大 */
  dynamicRangeDb: number;
  /** 头 8 秒线性能量 0..1 */
  introEnergy: number;
  /** 尾 8 秒线性能量 0..1 */
  outroEnergy: number;
  /** 谱重心（Hz）—— 音色亮度。男声 ~1500，女声 ~2500，金属 4000+ */
  spectralCentroidHz: number;
  /** 头部连续静音长度（秒）—— natural silence，不含编码器 padding */
  headSilenceS: number;
  /** 尾部连续静音长度（秒） */
  tailSilenceS: number;
};

export const audio = {
  cacheStats: () => invoke<AudioCacheStats>("audio_cache_stats"),
  setCacheMaxMb: (mb: number) =>
    invoke<void>("audio_cache_set_max_mb", { mb }),
  clearCache: () => invoke<void>("audio_cache_clear"),
  clearCacheEntry: (trackId: number) =>
    invoke<void>("audio_cache_clear_entry", { trackId }),
  /** 预取：把字节灌进磁盘缓存，不返回字节。返回 true=本来就有，false=刚拉的。 */
  prefetch: (trackId: number, url: string) =>
    invoke<boolean>("audio_prefetch", { trackId, url }),
  /**
   * 拿声学特征。SQLite 命中秒回；miss 走 Symphonia 解码 + DSP，单首 ~50-200ms。
   * `cacheBytes`：默认 true，把拉到的字节顺手落 audio_cache（playback 路径都用这个）。
   * 库扫描场景传 false，分析完字节就扔，不污染播放缓存。
   */
  getFeatures: (trackId: number, url: string, cacheBytes: boolean = true) =>
    invoke<AudioFeatures>("audio_get_features", { trackId, url, cacheBytes }),
  /** 仅查缓存：已分析的拿走，没分析的 null。不发起任何网络/解码。 */
  getCachedFeatures: (trackId: number) =>
    invoke<AudioFeatures | null>("audio_get_cached_features", { trackId }),
  /** 批量缓存查询。taste-profile 蒸馏时一次性拿几百首。 */
  getCachedFeaturesBulk: (trackIds: number[]) =>
    invoke<(AudioFeatures | null)[]>("audio_get_cached_features_bulk", { trackIds }),
  /** 清掉所有声学特征 + JS analysis:v3:* 缓存。让"继续分析"能干净重跑。 */
  clearFeatures: () => invoke<void>("audio_clear_features"),
};

/**
 * 把网易云直链包成走 audio cache scheme 的 URL。
 * trackId = neteaseId；url = 网易云原始 mp3/flac CDN URL（不要预先 cdn() 包裹，
 * Rust 侧 referer 注入会自己处理）。
 */
export function wrapAudioUrl(trackId: number, url: string): string {
  const base = pickAudioBase();
  return `${base}?id=${trackId}&u=${encodeURIComponent(url)}`;
}

// 跟 cdn.ts 的 pickProtoBase 同源 —— Tauri 2 在三个平台对自定义 scheme 的处理：
//   - macOS / iOS / Linux：保留 `<scheme>://localhost/...`
//   - Windows / Android：wry 改写成 `http://<scheme>.localhost/...`
//     （Android 经 WebViewAssetLoader 拦截，Windows 绕 Edge scheme 黑名单）
//     默认 useHttpsScheme=false，要走 https 得在 tauri.conf.json 显式打开。
function pickAudioBase(): string {
  if (typeof navigator === "undefined") return "claudio-audio://localhost/";
  const ua = navigator.userAgent;
  if (/Android/i.test(ua) || /Windows/i.test(ua)) {
    return "http://claudio-audio.localhost/";
  }
  return "claudio-audio://localhost/";
}

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
