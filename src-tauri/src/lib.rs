//! Claudio 主入口。
//!
//! 架构决定：**不嵌入网易云网页播放器**。
//! 音乐源通过 `netease` 模块走官方私有 API（二维码登录 + weapi 拿直链），
//! 直链塞给主窗口里一个普通 `<audio>` 元素原生播放，再接 WebAudio AnalyserNode
//! 驱动点阵场。这样主窗口就是所有视觉 + 音频的唯一居所。
//!
//! AI 层支持多 provider（DeepSeek / OpenAI / 小米 MiMo），三家都走 OpenAI 兼容接口。
//! API key 由用户在设置里自填，本机 `ai_config.json` 持久化（unix 下 0600 权限）。
//! 切 provider 不会清掉别家的 key，方便对比。
//!
//! 缓存层走 SQLite（bundled），`cache.db`。歌单 / 曲目 / 歌词 / 上次播放状态
//! 都在这里。`updateTime` 做增量比对，避免重复拉取。

pub mod ai;
pub mod cache;
pub mod netease;
pub mod proxy;

use std::sync::Arc;

use ai::cmds::*;
use ai::AiConfigStore;
use cache::cmds::*;
use cache::CacheDb;
use netease::cmds::*;
use netease::NeteaseClient;
use tauri::Manager;

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let builder = tauri::Builder::default()
        // 网易云 CDN 防盗链代理：把 claudio-cdn://localhost/?u=<encoded>
        // 转成带 Referer: music.163.com/ 的真请求。
        // 这样前端 <img src> / <audio src> 都可以透明走代理，不再 403。
        .register_asynchronous_uri_scheme_protocol("claudio-cdn", proxy::handle_cdn);

    // Windows: 跑 decorum 在 NCCALCSIZE 层把原生标题栏拿掉，
    // 但保留右上角 min/max/close（系统画的，行为完全原生）。
    #[cfg(windows)]
    let builder = builder.plugin(tauri_plugin_decorum::init());

    builder
        .invoke_handler(tauri::generate_handler![
            // netease
            netease_qr_start,
            netease_qr_check,
            netease_account,
            netease_user_playlists,
            netease_playlist_detail,
            netease_song_urls,
            netease_song_lyric,
            netease_search,
            netease_is_logged_in,
            // ai (multi-provider: deepseek / openai / xiaomi-mimo)
            ai_get_config,
            ai_list_models,
            ai_set_provider,
            ai_set_api_key,
            ai_clear_api_key,
            ai_set_model,
            ai_ping,
            ai_chat,
            // cache
            cache_get_playlists,
            cache_save_playlists,
            cache_get_playlist_detail,
            cache_save_playlist_detail,
            cache_get_lyric,
            cache_save_lyric,
            cache_get_state,
            cache_set_state,
        ])
        .setup(|app| {
            // 所有持久化都落在 app_config_dir（macOS: ~/Library/Application Support/claudio/）
            // 卸载 app 默认不会被清掉，符合"用户级配置"语义。
            let dir = app
                .path()
                .app_config_dir()
                .expect("resolve app_config_dir");
            std::fs::create_dir_all(&dir).ok();

            // --- netease cookie ---
            let cookie_path = dir.join("netease_cookies.json");
            let client = NeteaseClient::new_with_persist(Some(cookie_path))
                .expect("build netease client");
            app.manage(Arc::new(client));

            // --- ai config ---
            let ai_config_path = dir.join("ai_config.json");
            let ai_store =
                AiConfigStore::load(ai_config_path).expect("build ai config store");
            app.manage(Arc::new(ai_store));

            // --- cache db ---
            let cache_path = dir.join("cache.db");
            let cache = CacheDb::open(&cache_path).expect("open cache db");
            app.manage(Arc::new(cache));

            // Windows: 把主窗口的原生标题栏拿掉，留下三个系统键。
            // 必须在 setup 里拿到 webview window 之后调用一次。
            #[cfg(windows)]
            {
                use tauri_plugin_decorum::WebviewWindowExt;
                if let Some(win) = app.get_webview_window("main") {
                    let _ = win.create_overlay_titlebar();
                }
            }

            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("error while running claudio");
}
