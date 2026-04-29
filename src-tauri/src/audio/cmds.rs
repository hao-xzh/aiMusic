//! 前端可调的 audio cache command。
//!
//! 暴露给设置页用：看当前用量、调上限、一键清空。

use std::sync::Arc;

use tauri::State;
use url::Url;

use super::analysis::{analyze_file, Acoustics};
use super::cache::{ext_from_content_type, infer_ext_from_url, AudioCache, CacheStats};
use crate::cdn::cdn_get;

/// 调用方有时会传 `claudio-audio://localhost/?id=...&u=<encoded_cdn_url>` —— scheme
/// 自己用的 URL —— 而 cmds 期望的是真正的上游 CDN URL。这里把两种都接住：
///
///   - 直接 https://m701.music.126.net/... 原样返回
///   - claudio-audio://...?u=<encoded> 抠出来 `u=` 反编码
///   - http://claudio-audio.localhost/...?u=... （Windows 下 webview 转换后的形态）
///     同样抠出 u=
fn unwrap_url_arg(input: &str) -> Result<String, String> {
    // 启发式：扫到非 http(s) scheme 或 host 是 claudio-audio.localhost 都按 wrapped 处理
    let parsed = Url::parse(input).map_err(|e| format!("parse url {input}: {e}"))?;
    let host = parsed.host_str().unwrap_or("").to_ascii_lowercase();
    let is_wrapped = parsed.scheme() == "claudio-audio" || host == "claudio-audio.localhost";
    if !is_wrapped {
        return Ok(input.to_string());
    }
    parsed
        .query_pairs()
        .find(|(k, _)| k == "u")
        .map(|(_, v)| v.into_owned())
        .ok_or_else(|| "missing ?u= in wrapped audio url".to_string())
}

pub type AudioCacheState<'a> = State<'a, Arc<AudioCache>>;

fn to_err<E: std::fmt::Display>(e: E) -> String {
    e.to_string()
}

#[tauri::command]
pub fn audio_cache_stats(state: AudioCacheState<'_>) -> Result<CacheStats, String> {
    state.stats().map_err(to_err)
}

#[tauri::command]
pub fn audio_cache_set_max_mb(state: AudioCacheState<'_>, mb: i64) -> Result<(), String> {
    if mb <= 0 {
        return Err("max 必须为正数".into());
    }
    let bytes = mb.saturating_mul(1024 * 1024);
    state.set_max_bytes(bytes).map_err(to_err)
}

#[tauri::command]
pub fn audio_cache_clear(state: AudioCacheState<'_>) -> Result<(), String> {
    state.clear().map_err(to_err)
}

/// 预取：确保 trackId 对应的字节已经在磁盘缓存里。已命中就立即返回；
/// 没命中走 cdn_get 拉一遍 + 落盘。
///
/// 跟 `claudio-audio://` scheme 不同：scheme 会把字节穿过 webview 回流给前端
/// （fetch 拿到 ArrayBuffer），prefetch 不需要前端拿到字节，所以走 cmd 更省。
#[tauri::command]
pub async fn audio_prefetch(
    state: AudioCacheState<'_>,
    track_id: i64,
    url: String,
) -> Result<bool, String> {
    if state.has(track_id) {
        return Ok(true); // 已命中
    }
    let raw = unwrap_url_arg(&url)?;
    let upstream =
        Url::parse(&raw).map_err(|e| format!("parse url {raw}: {e}"))?;
    let resp = cdn_get(&upstream, None, None, None)
        .await
        .map_err(to_err)?;
    if resp.status >= 400 {
        return Err(format!("upstream {} for {track_id}", resp.status));
    }
    let format = infer_ext_from_url(&raw).or_else(|| {
        resp.headers
            .get(reqwest::header::CONTENT_TYPE)
            .and_then(|v| v.to_str().ok())
            .and_then(ext_from_content_type)
    });
    state
        .put(track_id, &resp.body, format.as_deref())
        .map_err(to_err)?;
    Ok(false) // 不是命中，刚拉的
}

/// 拉声学特征（BPM / 响度 / 动态范围 / 谱重心 / 进出能量）。
///
/// 流程：
///   1. SQLite features 表命中 → 直接返回
///   2. miss → 拿到字节（命中 audio_cache 直接读，否则现拉）→ Symphonia 解码 + 算
///   3. 把结果存进 features 表，下次秒回
///
/// `cache_bytes`：
///   - true（播放时）：拉到的原始字节也写进 audio_cache，下次播放秒开
///   - false（库扫描时）：分析完就扔，不污染播放缓存（避免 VIP 用户后续播放时
///     拿到 mp3 而不是 lossless）
///
/// `url` 接受两种形态：原始 https://... 或 wrapped claudio-audio://...?u=
///
/// 在 blocking 线程跑（解码 + DSP 是 CPU 密集，不能占着 tokio runtime）。
#[tauri::command]
pub async fn audio_get_features(
    state: AudioCacheState<'_>,
    track_id: i64,
    url: String,
    cache_bytes: Option<bool>,
) -> Result<Acoustics, String> {
    // 1) features 表命中
    if let Ok(Some(a)) = state.get_features(track_id) {
        return Ok(a);
    }

    let cache_bytes = cache_bytes.unwrap_or(true);
    let raw = unwrap_url_arg(&url)?;

    // 2) 拿字节：命中 audio_cache 直接走，否则现拉。
    let path = if state.has(track_id) {
        state
            .get(track_id)
            .map_err(to_err)?
            .ok_or_else(|| format!("expected cache entry for {track_id}"))?
            .path
    } else {
        let upstream = Url::parse(&raw).map_err(|e| format!("parse url: {e}"))?;
        let resp = cdn_get(&upstream, None, None, None)
            .await
            .map_err(to_err)?;
        if resp.status >= 400 {
            return Err(format!("upstream {} for {track_id}", resp.status));
        }
        let format = infer_ext_from_url(&raw).or_else(|| {
            resp.headers
                .get(reqwest::header::CONTENT_TYPE)
                .and_then(|v| v.to_str().ok())
                .and_then(ext_from_content_type)
        });

        if cache_bytes {
            // 落 audio_cache，path 由 put 返回
            state
                .put(track_id, &resp.body, format.as_deref())
                .map_err(to_err)?
                .path
        } else {
            // 不落 audio_cache —— 写一个临时文件，分析完删掉。
            // 不直接在内存里 decode 因为 Symphonia 要 MediaSource，反正写到 cache_dir 也很快。
            let tmp = std::env::temp_dir()
                .join(format!("claudio_analyze_{track_id}.{}", format.as_deref().unwrap_or("bin")));
            std::fs::write(&tmp, &resp.body)
                .map_err(|e| format!("write tmp: {e}"))?;
            tmp
        }
    };

    // 3) 解码 + 算特征 —— blocking 线程
    let cache_clone = Arc::clone(&state);
    let path_clone = path.clone();
    let acoustics = tauri::async_runtime::spawn_blocking(move || {
        analyze_file(track_id, &path_clone)
    })
    .await
    .map_err(|e| format!("blocking join: {e}"))?
    .map_err(|e| format!("analyze: {e:#}"))?;

    // 临时文件用完即删
    if !cache_bytes {
        let _ = std::fs::remove_file(&path);
    }

    // 4) 落库
    let _ = cache_clone.put_features(&acoustics);

    Ok(acoustics)
}

/// 仅查 features 表，不拉网络也不解码。给 mix-planner / taste-profile 用：
/// 已分析的就拿，没分析的就 None，不强制立即开销。
#[tauri::command]
pub fn audio_get_cached_features(
    state: AudioCacheState<'_>,
    track_id: i64,
) -> Result<Option<Acoustics>, String> {
    state.get_features(track_id).map_err(to_err)
}

/// 批量版：传一组 trackId，返回每个的 Option<Acoustics>。
/// taste-profile 蒸馏时一次性拿几百首的特征用。
#[tauri::command]
pub fn audio_get_cached_features_bulk(
    state: AudioCacheState<'_>,
    track_ids: Vec<i64>,
) -> Result<Vec<Option<Acoustics>>, String> {
    let mut out = Vec::with_capacity(track_ids.len());
    for id in track_ids {
        out.push(state.get_features(id).map_err(to_err)?);
    }
    Ok(out)
}

/// 清掉**所有**声学特征 + JS 端 analysis:v3:* 缓存。
/// 让用户能"重新开始分析"——比如想试试新版本算法、或者旧数据不准。
/// 这条不动 audio_cache（字节文件），那是另一档生命周期。
#[tauri::command]
pub fn audio_clear_features(state: AudioCacheState<'_>) -> Result<(), String> {
    state.clear_features().map_err(to_err)
}
