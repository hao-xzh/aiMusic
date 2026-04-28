//! 本地缓存层。
//!
//! 为什么要这一层：
//!   用户的歌单有几百上千首，每次进 `/distill` 都重拉 user/playlist + playlist/detail
//!   = 又慢又浪费。歌词一首可能好几百行 LRC，重复拉更没意义（歌词几乎不变）。
//!
//! 策略（cache-first，后台 SWR）：
//!   - 列表 / 详情：前端先从缓存读（瞬时），同时拉最新；对比 `updateTime`，变了才 upsert。
//!   - 歌词：命中就用，永远不再拉；TTL = 30 天（软失效，后续再做）。
//!   - 歌曲直链 (`song_urls`)：**不缓存**，因为是签名过期的 CDN 地址。
//!   - 播放位置 / 上次歌曲：走 app_state KV。
//!
//! 存储：SQLite，bundled。文件路径 `<app_config_dir>/cache.db`。

pub mod cmds;
pub mod db;

pub use db::CacheDb;
