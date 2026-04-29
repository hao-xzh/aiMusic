//! 前端可调的 audio cache command。
//!
//! 暴露给设置页用：看当前用量、调上限、一键清空。

use std::sync::Arc;

use tauri::State;
use url::Url;

use super::analysis::{analyze_file, Acoustics};
use super::cache::{ext_from_content_type, infer_ext_from_url, AudioCache, CacheStats};
use crate::cdn::cdn_get;

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
    let upstream =
        Url::parse(&url).map_err(|e| format!("parse url {url}: {e}"))?;
    let resp = cdn_get(&upstream, None, None, None)
        .await
        .map_err(to_err)?;
    if resp.status >= 400 {
        return Err(format!("upstream {} for {track_id}", resp.status));
    }
    let format = infer_ext_from_url(&url).or_else(|| {
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
///   2. miss → ensure 缓存命中（可能要先拉一遍上游）→ Symphonia 解码 + 算
///   3. 把结果存进 features 表，下次秒回
///
/// 在 blocking 线程跑（解码 + DSP 是 CPU 密集，不能占着 tokio runtime）。
#[tauri::command]
pub async fn audio_get_features(
    state: AudioCacheState<'_>,
    track_id: i64,
    url: String,
) -> Result<Acoustics, String> {
    // 1) features 表命中
    if let Ok(Some(a)) = state.get_features(track_id) {
        return Ok(a);
    }

    // 2) 确保字节在磁盘 —— 复用 prefetch 路径
    if !state.has(track_id) {
        let upstream = Url::parse(&url).map_err(|e| format!("parse url: {e}"))?;
        let resp = cdn_get(&upstream, None, None, None)
            .await
            .map_err(to_err)?;
        if resp.status >= 400 {
            return Err(format!("upstream {} for {track_id}", resp.status));
        }
        let format = infer_ext_from_url(&url).or_else(|| {
            resp.headers
                .get(reqwest::header::CONTENT_TYPE)
                .and_then(|v| v.to_str().ok())
                .and_then(ext_from_content_type)
        });
        state
            .put(track_id, &resp.body, format.as_deref())
            .map_err(to_err)?;
    }

    let entry = state
        .get(track_id)
        .map_err(to_err)?
        .ok_or_else(|| format!("expected cache entry for {track_id}"))?;

    // 3) 解码 + 算特征 —— blocking 线程
    let cache_clone = Arc::clone(&state);
    let acoustics = tauri::async_runtime::spawn_blocking(move || {
        analyze_file(track_id, &entry.path)
    })
    .await
    .map_err(|e| format!("blocking join: {e}"))?
    .map_err(|e| format!("analyze: {e:#}"))?;

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
