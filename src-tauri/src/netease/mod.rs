//! 网易云音乐原生客户端。
//!
//! 我们不嵌入网易云网页来播放，而是走它私有的 weapi —— 和官方桌面客户端是同一批端点。
//! 这让主窗口可以：
//!   1. 用二维码登录，不再需要第二个 webview
//!   2. 拿到歌曲直链，在主窗口的 <audio> 里原生放
//!   3. 把 <audio> 接 AnalyserNode，让点阵场吃到真实振幅
//!
//! 模块划分：
//!   - crypto:  weapi 双层 AES-CBC + 定长 RSA 加密
//!   - client:  带 cookie jar 的 reqwest，暴露 `weapi(endpoint, params)` 便捷方法
//!   - api:     每个我们用到的端点一个函数（二维码登录、用户歌单、歌曲直链...）
//!   - models:  JSON 响应的强类型 shape
//!   - cmds:    Tauri #[command] 外壳，把 api::xxx 暴露给前端

pub mod api;
pub mod client;
pub mod cmds;
pub mod crypto;
pub mod models;

pub use client::NeteaseClient;
