//! 音频缓存层 + 自定义 URI scheme `claudio-audio`。
//!
//! 主问题：网易云直链是签名过期的 CDN URL，每次切歌都重新走网络拉，回放体验差，
//! 流量也浪费。解决方案：
//!
//! 1. **磁盘缓存**：原始字节（mp3/flac/m4a，不解码）落到 `app_cache_dir/audio/`。
//!    SQLite 索引：track_id → path + bytes + last_used + format。
//! 2. **`claudio-audio://` scheme**：前端用 `claudio-audio://localhost/?id=<trackId>&u=<encoded_url>`
//!    访问音频。命中缓存直接读本地文件；miss 时走 cdn helper 拉上游 + 写盘。
//! 3. **LRU 上限**：cache 总字节超过用户配置的上限（默认 1024 MB）就按 last_used 淘汰最旧的。
//! 4. **配置项**：上限通过 `audio_cache_max_mb` 命令读写，落在 cache.db 的 app_state KV。
//!
//! 为什么缓存"原始字节"而不是"解码后 PCM"：
//!   - 一首 4 分钟 44.1kHz/Float32 立体声 PCM ≈ 80MB；mp3 ≈ 4-8MB；FLAC ≈ 25MB。
//!   - 落 PCM 占盘 10×+，而前端 `decodeAudioData` 解一次 mp3 ~100ms，没必要省。
//!   - 后续 Rust 端的声学分析（BPM / 能量 / 谱）会自己用 Symphonia 解一遍 —— 不影响缓存设计。

pub mod analysis;
pub mod cache;
pub mod cmds;
pub mod scheme;

pub use cache::AudioCache;
