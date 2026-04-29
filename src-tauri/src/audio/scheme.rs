//! `claudio-audio://` URI scheme handler。
//!
//! 用法：前端把 `<audio>.src` 或 `fetch()` 的 URL 改写成
//!    `claudio-audio://localhost/?id=<trackId>&u=<encoded_netease_cdn_url>`
//!
//! 行为：
//!   1. 解析 id 和 u（缺一不可）
//!   2. 查 audio_cache 索引：命中 + 文件还在 → 读本地文件；同时 touch last_used_at
//!   3. miss / 文件丢了 → 走 `crate::cdn::cdn_get` 拉上游 → 写 cache → 用刚下到的字节响应
//!   4. Range 头：本地命中时在内存里切；miss 时直接整段下，本次响应不切
//!      （网易云直链不长，整段下完用 200 OK 返回；webview 自己会在后续 seek 时再发 Range）
//!
//! 失败回落：缓存层抛错（写盘满 / DB 损坏等）只 log 不阻断 —— 直接拿到的字节回给
//! webview，宁可这首没缓存上，也不能播不出来。

use std::sync::Arc;

use tauri::http::{header, Request, Response, StatusCode};
use tauri::{Manager, UriSchemeContext, UriSchemeResponder, Wry};
use url::Url;

use super::cache::{ext_from_content_type, infer_ext_from_url, AudioCache};
use crate::cdn::cdn_get;

pub fn handle_audio(
    ctx: UriSchemeContext<'_, Wry>,
    request: Request<Vec<u8>>,
    responder: UriSchemeResponder,
) {
    // 提前抓 state（在主线程 ctx 还活着时拿）
    let cache: Option<Arc<AudioCache>> = ctx.app_handle().try_state::<Arc<AudioCache>>()
        .map(|s| s.inner().clone());

    tauri::async_runtime::spawn(async move {
        let resp = match handle(request, cache).await {
            Ok(r) => r,
            Err(e) => error_response(StatusCode::BAD_GATEWAY, &format!("{e:#}")),
        };
        responder.respond(resp);
    });
}

async fn handle(
    req: Request<Vec<u8>>,
    cache: Option<Arc<AudioCache>>,
) -> anyhow::Result<Response<Vec<u8>>> {
    // ---- 1) 解析 ?id= 和 ?u= ----
    let uri_str = req.uri().to_string();
    let parsed =
        Url::parse(&uri_str).map_err(|e| anyhow::anyhow!("parse scheme uri {uri_str}: {e}"))?;
    let mut id_str: Option<String> = None;
    let mut upstream: Option<String> = None;
    for (k, v) in parsed.query_pairs() {
        match k.as_ref() {
            "id" => id_str = Some(v.into_owned()),
            "u" => upstream = Some(v.into_owned()),
            _ => {}
        }
    }
    let track_id: i64 = id_str
        .and_then(|s| s.parse().ok())
        .ok_or_else(|| anyhow::anyhow!("missing or invalid ?id="))?;
    let upstream = upstream.ok_or_else(|| anyhow::anyhow!("missing ?u= query"))?;
    let upstream_url =
        Url::parse(&upstream).map_err(|e| anyhow::anyhow!("parse upstream {upstream}: {e}"))?;

    let range_header = req
        .headers()
        .get(header::RANGE)
        .and_then(|v| v.to_str().ok())
        .map(String::from);

    // ---- 2) 缓存命中 ----
    if let Some(cache) = cache.as_ref() {
        if let Ok(Some(entry)) = cache.get(track_id) {
            match std::fs::read(&entry.path) {
                Ok(bytes) => {
                    let _ = cache.touch(track_id);
                    log::debug!(
                        "[audio_cache] HIT track={track_id} bytes={}",
                        bytes.len()
                    );
                    return Ok(local_response(
                        bytes,
                        entry.format.as_deref(),
                        range_header.as_deref(),
                    ));
                }
                Err(e) => {
                    // 文件丢了 —— DB 行也清掉，回落到 miss 流程
                    log::warn!(
                        "[audio_cache] DB hit but file missing for track {track_id}: {e}; refetching"
                    );
                    let _ = cache.clear_entry(track_id);
                }
            }
        }
    }

    // ---- 3) Miss：走 cdn helper 拉上游 ----
    log::debug!("[audio_cache] MISS track={track_id} fetching upstream");
    // 注意：缓存写入时不带 Range（要的是完整文件）。webview 给的 Range 在响应阶段单独处理。
    let resp = cdn_get(&upstream_url, None, None, None).await?;

    if resp.status >= 400 {
        // 上游错了，照样原样回给 webview，但**不**写缓存
        let mut builder = Response::builder().status(resp.status);
        builder = passthrough_headers(builder, &resp.headers);
        builder = builder.header(header::ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        return builder
            .body(resp.body)
            .map_err(|e| anyhow::anyhow!("build error response: {e}"));
    }

    let bytes = resp.body;
    let content_type = resp
        .headers
        .get(header::CONTENT_TYPE)
        .and_then(|v| v.to_str().ok())
        .map(String::from);

    // ---- 4) 写缓存（best-effort） ----
    let format = infer_ext_from_url(&upstream).or_else(|| {
        content_type
            .as_deref()
            .and_then(ext_from_content_type)
    });
    if let Some(cache) = cache.as_ref() {
        if let Err(e) = cache.put(track_id, &bytes, format.as_deref()) {
            log::warn!("[audio_cache] put failed for track {track_id}: {e}");
        } else {
            log::debug!(
                "[audio_cache] STORE track={track_id} bytes={} format={:?}",
                bytes.len(),
                format
            );
        }
    }

    // ---- 5) 回响应 ----
    Ok(local_response(bytes, format.as_deref(), range_header.as_deref()))
}

/// 用本地字节构造响应 —— 命中走它，新下完也走它。
/// Range 头：在内存里切片，状态码 206 + Content-Range/Content-Length。
fn local_response(
    full_bytes: Vec<u8>,
    format: Option<&str>,
    range: Option<&str>,
) -> Response<Vec<u8>> {
    let total = full_bytes.len() as u64;
    let content_type = format
        .and_then(content_type_from_ext)
        .unwrap_or("application/octet-stream");

    if let Some(r) = range.and_then(|s| parse_range(s, total)) {
        let body = &full_bytes[r.0 as usize..=r.1 as usize];
        let mut builder = Response::builder()
            .status(StatusCode::PARTIAL_CONTENT)
            .header(header::CONTENT_TYPE, content_type)
            .header(header::CONTENT_LENGTH, (r.1 - r.0 + 1).to_string())
            .header(header::ACCEPT_RANGES, "bytes")
            .header(
                header::CONTENT_RANGE,
                format!("bytes {}-{}/{}", r.0, r.1, total),
            )
            .header(header::ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        // 让 webview 缓存合理一点：cache.db 已经存了，再加 7 天 max-age
        builder = builder.header(header::CACHE_CONTROL, "public, max-age=604800");
        return builder.body(body.to_vec()).expect("build partial response");
    }

    Response::builder()
        .status(StatusCode::OK)
        .header(header::CONTENT_TYPE, content_type)
        .header(header::CONTENT_LENGTH, total.to_string())
        .header(header::ACCEPT_RANGES, "bytes")
        .header(header::ACCESS_CONTROL_ALLOW_ORIGIN, "*")
        .header(header::CACHE_CONTROL, "public, max-age=604800")
        .body(full_bytes)
        .expect("build full response")
}

fn passthrough_headers(
    mut builder: tauri::http::response::Builder,
    headers: &reqwest::header::HeaderMap,
) -> tauri::http::response::Builder {
    for name in [
        header::CONTENT_TYPE,
        header::CONTENT_LENGTH,
        header::CONTENT_RANGE,
        header::ACCEPT_RANGES,
        header::ETAG,
        header::LAST_MODIFIED,
    ] {
        if let Some(v) = headers.get(&name) {
            builder = builder.header(name, v);
        }
    }
    builder
}

fn content_type_from_ext(ext: &str) -> Option<&'static str> {
    match ext {
        "mp3" => Some("audio/mpeg"),
        "flac" => Some("audio/flac"),
        "m4a" => Some("audio/mp4"),
        "aac" => Some("audio/aac"),
        "ogg" => Some("audio/ogg"),
        "wav" => Some("audio/wav"),
        _ => None,
    }
}

/// 解析最简单的 `bytes=start-end` / `bytes=start-` —— 不支持多段 / suffix。
/// 返回 `(start, end_inclusive)`。失败 / 越界 → None，调用方退回完整响应。
fn parse_range(value: &str, total: u64) -> Option<(u64, u64)> {
    let v = value.trim();
    let v = v.strip_prefix("bytes=")?;
    let mut parts = v.split('-');
    let start_s = parts.next()?.trim();
    let end_s = parts.next()?.trim();
    if !start_s.is_empty() && !end_s.is_empty() {
        let start: u64 = start_s.parse().ok()?;
        let end: u64 = end_s.parse().ok()?;
        if start > end || end >= total {
            return None;
        }
        Some((start, end))
    } else if !start_s.is_empty() {
        let start: u64 = start_s.parse().ok()?;
        if start >= total {
            return None;
        }
        Some((start, total - 1))
    } else if !end_s.is_empty() {
        // suffix range: 末尾 N 字节
        let n: u64 = end_s.parse().ok()?;
        if n == 0 {
            return None;
        }
        let n = n.min(total);
        Some((total - n, total - 1))
    } else {
        None
    }
}

fn error_response(code: StatusCode, msg: &str) -> Response<Vec<u8>> {
    Response::builder()
        .status(code)
        .header(header::CONTENT_TYPE, "text/plain; charset=utf-8")
        .header(header::ACCESS_CONTROL_ALLOW_ORIGIN, "*")
        .body(format!("claudio-audio: {msg}").into_bytes())
        .expect("build error response")
}

