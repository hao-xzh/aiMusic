//! Tauri `#[command]` 外壳：把 `NeteaseClient` 的方法一对一暴露给前端。
//!
//! 所有 command 都 `async`，内部用 `State<Arc<NeteaseClient>>` 拿到共享客户端。
//! 错误统一序列化成 String，前端 try/catch 直接拿到可读信息。

use std::sync::Arc;

use serde::Serialize;
use tauri::State;

use super::client::NeteaseClient;
use super::models::{
    LyricData, PlaylistDetail, PlaylistInfo, SongUrl, TrackInfo, UserProfile,
};

pub type NeteaseState<'a> = State<'a, Arc<NeteaseClient>>;

fn to_err<E: std::fmt::Display>(e: E) -> String {
    e.to_string()
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct QrStartOut {
    /// 透传给前端用来轮询的 key
    pub key: String,
    /// 用来生成二维码图像的字符串（官方 app 扫这个）
    pub qr_content: String,
}

#[tauri::command]
pub async fn netease_qr_start(state: NeteaseState<'_>) -> Result<QrStartOut, String> {
    let key = state.qr_unikey().await.map_err(to_err)?;
    let qr_content = format!("https://music.163.com/login?codekey={key}");
    Ok(QrStartOut { key, qr_content })
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct QrCheckOut {
    pub code: i32,
    pub message: Option<String>,
    pub nickname: Option<String>,
}

#[tauri::command]
pub async fn netease_qr_check(
    state: NeteaseState<'_>,
    key: String,
) -> Result<QrCheckOut, String> {
    let r = state.qr_check(&key).await.map_err(to_err)?;
    Ok(QrCheckOut {
        code: r.code,
        message: r.message,
        nickname: r.nickname,
    })
}

/// 手机号验证码登录第一步：发短信。
/// `ctcode` 国家区号；不传默认 86（中国大陆）。
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CaptchaSentOut {
    pub code: i32,
    pub message: Option<String>,
}

#[tauri::command]
pub async fn netease_captcha_sent(
    state: NeteaseState<'_>,
    phone: String,
    ctcode: Option<i32>,
) -> Result<CaptchaSentOut, String> {
    let r = state
        .captcha_sent(&phone, ctcode.unwrap_or(86))
        .await
        .map_err(to_err)?;
    Ok(CaptchaSentOut {
        code: r.code,
        message: r.message,
    })
}

/// 手机号验证码登录第二步：拿验证码换 cookie。
/// code=200 表示登录成功，cookie 已落盘，前端可以直接走主流程。
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PhoneLoginOut {
    pub code: i32,
    pub message: Option<String>,
    pub nickname: Option<String>,
}

#[tauri::command]
pub async fn netease_phone_login(
    state: NeteaseState<'_>,
    phone: String,
    captcha: String,
    ctcode: Option<i32>,
) -> Result<PhoneLoginOut, String> {
    let r = state
        .login_cellphone(&phone, &captcha, ctcode.unwrap_or(86))
        .await
        .map_err(to_err)?;
    Ok(PhoneLoginOut {
        code: r.code,
        message: r.message,
        nickname: r.profile.map(|p| p.nickname),
    })
}

#[tauri::command]
pub async fn netease_account(
    state: NeteaseState<'_>,
) -> Result<Option<UserProfile>, String> {
    state.account().await.map_err(to_err)
}

#[tauri::command]
pub async fn netease_user_playlists(
    state: NeteaseState<'_>,
    uid: i64,
    limit: Option<i64>,
) -> Result<Vec<PlaylistInfo>, String> {
    state
        .user_playlists(uid, limit.unwrap_or(1000))
        .await
        .map_err(to_err)
}

#[tauri::command]
pub async fn netease_playlist_detail(
    state: NeteaseState<'_>,
    id: i64,
) -> Result<PlaylistDetail, String> {
    state.playlist_detail(id).await.map_err(to_err)
}

#[tauri::command]
pub async fn netease_song_urls(
    state: NeteaseState<'_>,
    ids: Vec<i64>,
    level: Option<String>,
) -> Result<Vec<SongUrl>, String> {
    // 默认 "lossless"（FLAC 无损）—— 网易云 API 对超出权限的请求会自动降档：
    //   - 黑胶 VIP / SVIP：拿到 lossless FLAC（CD 级 16bit/44.1k）
    //   - 普通用户：自动降到 exhigh 320kbps MP3
    // 所以 lossless 当默认对所有人都安全，VIP 直接拿到最高音质。
    let level = level.as_deref().unwrap_or("lossless");
    state.song_urls(&ids, level).await.map_err(to_err)
}

#[tauri::command]
pub async fn netease_song_lyric(
    state: NeteaseState<'_>,
    id: i64,
) -> Result<LyricData, String> {
    state.song_lyric(id).await.map_err(to_err)
}

/// 关键词搜索单曲 —— Phase B "库外推荐" 流水线第一站。
/// AI 给的关键词 / 艺人组合 → 这里搜回真实可播的曲目集 → AI 再 rerank。
#[tauri::command]
pub async fn netease_search(
    state: NeteaseState<'_>,
    query: String,
    limit: Option<i64>,
) -> Result<Vec<TrackInfo>, String> {
    state
        .search_tracks(&query, limit.unwrap_or(30))
        .await
        .map_err(to_err)
}

/// 退出登录。当前实现只是丢掉 cookie jar，用 new() 建一个新的。
/// 因为 Arc<NeteaseClient> 被 Tauri 当作 managed state，没法原地替换；
/// 这里先返回一个提示，让前端自己 state reset —— 持久化/清空在 Phase 1 做。
#[tauri::command]
pub fn netease_is_logged_in(state: NeteaseState<'_>) -> bool {
    state.cookie_value("MUSIC_U").is_some()
}
