//! Pipo 主入口。
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
pub mod audio;
pub mod cache;
pub mod cdn;
pub mod media_export;
pub mod netease;
pub mod proxy;

use std::sync::Arc;

use ai::cmds::*;
use ai::AiConfigStore;
use audio::cmds::*;
use audio::AudioCache;
use cache::cmds::*;
use cache::CacheDb;
use media_export::netease_export_mp3;
use netease::cmds::*;
use netease::NeteaseClient;
use tauri::Manager;

#[tauri::command]
#[cfg(debug_assertions)]
fn apple_lyrics_desktop_probe(payload: String) -> Result<(), String> {
    let path = std::env::var("PIPO_APPLE_LYRICS_DESKTOP_PROBE_OUT")
        .map_err(|_| "apple lyrics desktop probe disabled".to_string())?;
    std::fs::write(path, payload).map_err(|e| e.to_string())
}

#[tauri::command]
#[cfg(not(debug_assertions))]
fn apple_lyrics_desktop_probe(_payload: String) -> Result<(), String> {
    Err("apple lyrics desktop probe disabled".to_string())
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let builder = tauri::Builder::default()
        // 网易云 CDN 防盗链代理：把 claudio-cdn://localhost/?u=<encoded>
        // 转成带 Referer: music.163.com/ 的真请求。
        // 这样前端 <img src> / <audio src> 都可以透明走代理，不再 403。
        .register_asynchronous_uri_scheme_protocol("claudio-cdn", proxy::handle_cdn)
        // 音频缓存：claudio-audio://localhost/?id=<trackId>&u=<encoded_url>
        // 命中本地文件就直接喂；miss 就拉上游 + 落盘 + LRU 淘汰。
        // 前端只用换一处 URL 就行，<audio>/AudioBuffer 那边完全透明。
        .register_asynchronous_uri_scheme_protocol("claudio-audio", audio::scheme::handle_audio);

    // Windows: 跑 decorum 在 NCCALCSIZE 层把原生标题栏拿掉，
    // 但保留右上角 min/max/close（系统画的，行为完全原生）。
    #[cfg(windows)]
    let builder = builder.plugin(tauri_plugin_decorum::init());

    builder
        .invoke_handler(tauri::generate_handler![
            // netease
            netease_qr_start,
            netease_qr_check,
            netease_captcha_sent,
            netease_captcha_verify,
            netease_phone_login,
            netease_account,
            netease_user_playlists,
            netease_playlist_detail,
            netease_song_urls,
            netease_song_lyric,
            netease_cloud_lyric,
            netease_search,
            netease_like_song,
            netease_playlist_modify_tracks,
            netease_is_logged_in,
            netease_export_mp3,
            // ai (multi-provider: deepseek / openai / xiaomi-mimo / custom)
            ai_get_config,
            ai_list_models,
            ai_set_provider,
            ai_set_api_key,
            ai_clear_api_key,
            ai_set_model,
            ai_set_base_url,
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
            // audio cache
            audio_cache_stats,
            audio_cache_set_max_mb,
            audio_cache_clear,
            audio_cache_clear_entry,
            audio_prefetch,
            audio_get_features,
            audio_get_cached_features,
            audio_get_cached_features_bulk,
            audio_clear_features,
            apple_lyrics_desktop_probe,
        ])
        .setup(|app| {
            // 所有持久化都落在 app_config_dir（macOS: ~/Library/Application Support/claudio/）
            // 卸载 app 默认不会被清掉，符合"用户级配置"语义。
            let dir = app.path().app_config_dir().expect("resolve app_config_dir");
            std::fs::create_dir_all(&dir).ok();

            // --- netease cookie ---
            let cookie_path = dir.join("netease_cookies.json");
            let client =
                NeteaseClient::new_with_persist(Some(cookie_path)).expect("build netease client");
            app.manage(Arc::new(client));

            // --- ai config ---
            let ai_config_path = dir.join("ai_config.json");
            let ai_store = AiConfigStore::load(ai_config_path).expect("build ai config store");
            app.manage(Arc::new(ai_store));

            // --- cache db ---
            let cache_path = dir.join("cache.db");
            let cache = Arc::new(CacheDb::open(&cache_path).expect("open cache db"));
            app.manage(cache.clone());

            // --- audio cache（磁盘缓存，原始字节）---
            // 落在 app_cache_dir（macOS: ~/Library/Caches/claudio/）—— 是 OS 级别
            // "可清理" 缓存目录，跟 app_config_dir（不会被清）的 cache.db 索引分开
            let audio_root = app
                .path()
                .app_cache_dir()
                .unwrap_or_else(|_| dir.clone())
                .join("audio");
            let audio_cache = AudioCache::init(cache, audio_root).expect("init audio cache");
            app.manage(Arc::new(audio_cache));

            // 启动时先把网易云 CDN 的 TLS 连接建好 —— 用户第一次切歌时
            // 不用再等 TLS 握手（~150-300ms）。fire-and-forget，失败不致命。
            tauri::async_runtime::spawn(async {
                let url = url::Url::parse("https://music.163.com/").unwrap();
                if let Err(e) = crate::cdn::cdn_get(&url, None, None, None).await {
                    log::debug!("[pipo] TLS warmup skipped: {e}");
                }
            });

            // Windows: 把主窗口的原生标题栏拿掉，留下三个系统键。
            // 必须在 setup 里拿到 webview window 之后调用一次。
            #[cfg(windows)]
            {
                use tauri_plugin_decorum::WebviewWindowExt;
                if let Some(win) = app.get_webview_window("main") {
                    let _ = win.create_overlay_titlebar();
                }
            }

            #[cfg(debug_assertions)]
            {
                if std::env::var("PIPO_APPLE_LYRICS_DESKTOP_PROBE").as_deref() == Ok("1") {
                    if let Some(win) = app.get_webview_window("main") {
                        println!("[apple-lyrics-desktop-probe] enabled");
                        tauri::async_runtime::spawn(async move {
                            std::thread::sleep(std::time::Duration::from_millis(5000));
                            match win.eval(APPLE_LYRICS_DESKTOP_PROBE_SCRIPT) {
                                Ok(_) => println!("[apple-lyrics-desktop-probe] eval submitted"),
                                Err(e) => println!("[apple-lyrics-desktop-probe] eval failed: {e}"),
                            }
                        });
                    } else {
                        println!("[apple-lyrics-desktop-probe] main window missing");
                    }
                }
            }

            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("error while running claudio");
}

#[cfg(debug_assertions)]
const APPLE_LYRICS_DESKTOP_PROBE_SCRIPT: &str = r#"
(async () => {
  try {
  const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
  const sendPayload = async (payload) => {
    const text = JSON.stringify(payload);
    const compact = {
      href: payload.href,
      probeError: payload.probeError ?? null,
      probeStack: payload.probeStack ?? null,
      fixtureSongId: payload.fixtureSongId ?? null,
      viewport: payload.viewport ?? null,
      active: payload.active?.text ?? null,
      column: payload.column ? {
        left: payload.column.rect?.left ?? null,
        width: payload.column.rect?.width ?? null,
        mixBlendMode: payload.column.mixBlendMode ?? null,
      } : null,
      backdrop: payload.backdrop ? {
        backgroundColor: payload.backdrop.backgroundColor,
        backgroundPosition: payload.backdrop.backgroundPosition,
        backgroundSize: payload.backdrop.backgroundSize,
      } : null,
      translation: payload.translation ? {
        fontSize: payload.translation.fontSize,
        opacity: payload.translation.opacity,
        marginTop: payload.translation.marginTop,
      } : null,
      companion: payload.companion ? {
        fontSize: payload.companion.fontSize,
        marginTop: payload.companion.marginTop,
      } : null,
      backgroundTranslation: payload.backgroundTranslation ? {
        fontSize: payload.backgroundTranslation.fontSize,
        marginTop: payload.backgroundTranslation.marginTop,
        opacity: payload.backgroundTranslation.opacity,
      } : null,
      motion: payload.motion ? {
        scrollFrames: payload.motion.scrollFrames?.map((frame) => ({
          timingMs: frame.timingMs,
          text: frame.text,
          activeScale: frame.activeScale,
          activePaddingTop: frame.activePaddingTop,
          previousHeight: frame.previousHeight,
          innerY: frame.innerY,
        })),
        englishSlowPeak: payload.motion.englishSlowPeak ? {
          text: payload.motion.englishSlowPeak.text,
          slowToken: payload.motion.englishSlowPeak.slowToken,
          slowLetter: payload.motion.englishSlowPeak.slowLetter,
        } : null,
        cjkSlowPeak: payload.motion.cjkSlowPeak ? {
          text: payload.motion.cjkSlowPeak.text,
          slowToken: payload.motion.cjkSlowPeak.slowToken,
          slowLetter: payload.motion.cjkSlowPeak.slowLetter,
        } : null,
        seekCompanion: payload.motion.seekCompanion ? {
          text: payload.motion.seekCompanion.text,
          companion: payload.motion.seekCompanion.companion ? {
            fontSize: payload.motion.seekCompanion.companion.fontSize,
            marginTop: payload.motion.seekCompanion.companion.marginTop,
          } : null,
          backgroundTranslation: payload.motion.seekCompanion.backgroundTranslation ? {
            fontSize: payload.motion.seekCompanion.backgroundTranslation.fontSize,
            marginTop: payload.motion.seekCompanion.backgroundTranslation.marginTop,
            opacity: payload.motion.seekCompanion.backgroundTranslation.opacity,
          } : null,
        } : null,
        seekReturn: payload.motion.seekReturn ? {
          text: payload.motion.seekReturn.text,
          translation: payload.motion.seekReturn.translation ? {
            fontSize: payload.motion.seekReturn.translation.fontSize,
            opacity: payload.motion.seekReturn.translation.opacity,
            marginTop: payload.motion.seekReturn.translation.marginTop,
          } : null,
        } : null,
      } : null,
    };
    const invoke =
      window.__TAURI_INTERNALS__?.invoke ??
      window.__TAURI__?.core?.invoke;
    let sent = false;
    if (invoke) {
      try {
        await Promise.race([
          invoke("apple_lyrics_desktop_probe", { payload: text }).then(() => {
            sent = true;
          }),
          delay(1000),
        ]);
      } catch (_) {}
    }
    if (!sent) {
      await fetch(`/__apple_lyrics_desktop_probe?payload=${encodeURIComponent(JSON.stringify(compact))}`, {
        cache: "no-store",
      }).catch(() => {});
    }
  };
  const styleOf = (el) => {
    if (!el) return null;
    const cs = getComputedStyle(el);
    const rect = el.getBoundingClientRect();
    return {
      rect: {
        x: rect.x,
        y: rect.y,
        width: rect.width,
        height: rect.height,
        top: rect.top,
        left: rect.left,
        right: rect.right,
        bottom: rect.bottom,
      },
      backgroundColor: cs.backgroundColor,
      backgroundImage: cs.backgroundImage,
      backgroundPosition: cs.backgroundPosition,
      backgroundSize: cs.backgroundSize,
      boxShadow: cs.boxShadow,
      color: cs.color,
      filter: cs.filter,
      fontFamily: cs.fontFamily,
      fontSize: cs.fontSize,
      fontWeight: cs.fontWeight,
      lineHeight: cs.lineHeight,
      marginTop: cs.marginTop,
      marginBottom: cs.marginBottom,
      maxHeight: cs.maxHeight,
      mixBlendMode: cs.mixBlendMode,
      opacity: cs.opacity,
      paddingTop: cs.paddingTop,
      paddingBottom: cs.paddingBottom,
      textAlign: cs.textAlign,
      textWrap: cs.textWrap,
      transform: cs.transform,
      transformOrigin: cs.transformOrigin,
      transition: cs.transition,
      whiteSpace: cs.whiteSpace,
    };
  };
  const q = (selector, root = document) => root.querySelector(selector);
  for (let i = 0; i < 80; i += 1) {
    if (q("[data-apple-lyrics-fixture]") && q("[data-lyric-row-kind='yrc'][data-active='1']")) break;
    await delay(100);
  }
  const root = q("[data-apple-lyrics-fixture]");
  const active = q("[data-lyric-row-kind='yrc'][data-active='1']");
  const vars = getComputedStyle(q(".appleLyricsFullscreen") ?? document.documentElement);
  const payload = {
    href: location.href,
    userAgent: navigator.userAgent,
    viewport: {
      width: window.innerWidth,
      height: window.innerHeight,
      devicePixelRatio: window.devicePixelRatio,
    },
    fixtureSongId: root?.getAttribute("data-song-id") ?? null,
    fixturePositionSec: root?.getAttribute("data-position-sec") ?? null,
    cssVars: {
      pagePadding: vars.getPropertyValue("--apple-lyrics-page-padding").trim(),
      coverColumnWidth: vars.getPropertyValue("--apple-lyrics-cover-column-width").trim(),
      lyricsColumnWidth: vars.getPropertyValue("--apple-lyrics-column-width").trim(),
    },
    backdrop: styleOf(q("[data-apple-lyrics-backdrop]")),
    cover: styleOf(q("[data-apple-lyrics-cover]")),
    coverHalo: styleOf(q("[data-apple-lyrics-cover-halo]")),
    column: styleOf(q("[data-apple-lyrics-column]")),
    active: {
      text: active?.getAttribute("data-lyric-text") ?? null,
      style: styleOf(active),
      innerStyle: styleOf(active?.firstElementChild ?? null),
    },
    translation: styleOf(q("[data-companion-role='translation']", active ?? document)),
    romaji: styleOf(q("[data-companion-role='romaji']", active ?? document)),
    companion: styleOf(q("[data-companion-role='companion']", active ?? document)),
    backgroundTranslation: styleOf(q("[data-companion-role='background-translation']", active ?? document)),
    companionRoles: Array.from(document.querySelectorAll("[data-companion-role]"))
      .map((node) => node.getAttribute("data-companion-role"))
      .filter(Boolean),
  };
  const number = (value) => {
    const n = Number.parseFloat(String(value ?? "0").replace("px", ""));
    return Number.isFinite(n) ? n : 0;
  };
  const scaleOf = (transform) => {
    if (!transform || transform === "none") return 1;
    const match = String(transform).match(/matrix\(([^,]+)/);
    return match ? Number.parseFloat(match[1]) || 1 : 1;
  };
  const summarizeMotion = () => {
    const rows = Array.from(document.querySelectorAll("[data-lyric-row-kind='yrc']"));
    const current = q("[data-lyric-row-kind='yrc'][data-active='1']");
    const activeIndex = rows.indexOf(current);
    const activeStyle = styleOf(current);
    const previousStyle = styleOf(activeIndex > 0 ? rows[activeIndex - 1] : null);
    const slowToken = q("[data-yrc-token-kind='slow']", current ?? document);
    const slowLetter = q("[data-yrc-token-kind='slow'] [data-yrc-letter-index='0']", current ?? document);
    return {
      text: current?.getAttribute("data-lyric-text") ?? null,
      innerY: styleOf(q("[data-apple-lyrics-inner]"))?.rect?.top ?? 0,
      activeTop: activeStyle?.rect?.top ?? 0,
      activeHeight: activeStyle?.rect?.height ?? 0,
      activeScale: scaleOf(activeStyle?.transform),
      activePaddingTop: number(activeStyle?.paddingTop),
      activePaddingBottom: number(activeStyle?.paddingBottom),
      activeTransition: activeStyle?.transition ?? null,
      previousHeight: previousStyle?.rect?.height ?? null,
      previousScale: scaleOf(previousStyle?.transform),
      previousPaddingTop: number(previousStyle?.paddingTop),
      slowToken: slowToken ? {
        text: slowToken.getAttribute("data-yrc-token"),
        transform: styleOf(slowToken)?.transform,
      } : null,
      slowLetter: slowLetter ? {
        text: slowLetter.getAttribute("data-yrc-letter"),
        transform: styleOf(slowLetter)?.transform,
        textShadow: styleOf(slowLetter)?.textShadow,
      } : null,
    };
  };
  const setPosition = async (sec) => {
    window.__setAppleLyricsFixturePosition?.(sec);
    await delay(40);
  };
  const waitForActiveText = async (needle) => {
    for (let i = 0; i < 80; i += 1) {
      const text = q("[data-lyric-row-kind='yrc'][data-active='1']")?.getAttribute("data-lyric-text") ?? "";
      if (text.includes(needle)) return true;
      await delay(50);
    }
    return false;
  };
  if (payload.fixtureSongId === "mixed" && typeof window.__setAppleLyricsFixturePosition === "function") {
    const scrollTimings = [0, 100, 175, 350, 400];
    await setPosition(7.70);
    await delay(520);
    const scrollBefore = summarizeMotion();
    await setPosition(7.76);
    await waitForActiveText("That I just wanna get with you");
    const startedAt = performance.now();
    const scrollFrames = [];
    for (const timingMs of scrollTimings) {
      const waitMs = Math.max(0, startedAt + timingMs - performance.now());
      if (waitMs > 0) await delay(waitMs);
      scrollFrames.push(Object.assign({ timingMs }, summarizeMotion()));
    }
    await delay(180);
    const scrollAfter = summarizeMotion();

    await setPosition(9.68);
    await waitForActiveText("That I just wanna get with you");
    await delay(80);
    const englishSlowPeak = summarizeMotion();

    await setPosition(31.50);
    await waitForActiveText("慢词 now");
    await delay(80);
    const cjkSlowPeak = summarizeMotion();

    payload.motion = {
      scrollBefore,
      scrollFrames,
      scrollAfter,
      englishSlowPeak,
      cjkSlowPeak,
    };
  }
  await sendPayload(payload);
  } catch (error) {
    try {
      await sendPayload({
        href: location.href,
        probeError: String(error),
        probeStack: error?.stack ?? null,
      });
    } catch (_) {}
  }
})();
"#;
