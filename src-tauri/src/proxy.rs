//! 自定义 URI scheme `claudio-cdn`：给网易云 CDN 资源做 Referer 代理。
//!
//! 背景：
//!   - 网易云图片（`p*.music.126.net`）和音频（`m*.music.126.net`）CDN 都开了防盗链，
//!     Referer 不是 `http(s)://music.163.com/` 直接回 403。
//!   - Tauri webview 里 `<img>` / `<audio>` 发的 Referer 要么是 `tauri://localhost`，
//!     要么是 `http://localhost:4321/`（dev 时），CDN 一律拒。
//!   - `<meta name="referrer" content="no-referrer">` 在 webview 里表现不稳定，
//!     加 `referrerpolicy="no-referrer"` 也时灵时不灵（实测打脸过一次）。
//!
//! 方案：注册一个 `claudio-cdn` 自定义 scheme，前端把 CDN URL 改写成
//! `claudio-cdn://localhost/?u=<encoded>`。Tauri 接到后走这个 handler，
//! 由 Rust 侧用 reqwest 拉上游（带上官方 Referer + UA），再把响应 body + 关键头
//! 透传回 webview。对 `<img>` / `<audio>` 完全透明，CDN 也不再 403。
//!
//! 安全：只放行 `music.163.com` / `*.music.163.com` / `*.music.126.net`，
//! 别变成开放代理。Range 头透传保障音频 seek。

use tauri::http::{header, Request, Response, StatusCode};
use tauri::{UriSchemeContext, UriSchemeResponder, Wry};
use url::Url;

use crate::cdn::{cdn_client, validate_upstream};

/// Tauri 的异步 URI scheme handler 入口。
/// 拿到 request -> 提取 `?u=` -> 远端 fetch -> 把响应塞给 responder。
pub fn handle_cdn(
    _ctx: UriSchemeContext<'_, Wry>,
    request: Request<Vec<u8>>,
    responder: UriSchemeResponder,
) {
    // tokio 任务里跑 async，别阻塞 webview 主线程。
    tauri::async_runtime::spawn(async move {
        let resp = match fetch(request).await {
            Ok(r) => r,
            Err(e) => error_response(StatusCode::BAD_GATEWAY, &format!("{e:#}")),
        };
        responder.respond(resp);
    });
}

async fn fetch(req: Request<Vec<u8>>) -> anyhow::Result<Response<Vec<u8>>> {
    // ---- 1) 从 URI 取上游 URL（?u= 参数）----
    //
    // macOS/Linux 上 webview 送进来的 URI 形如：
    //   claudio-cdn://localhost/?u=https%3A%2F%2Fp1.music.126.net%2Fxyz.jpg
    // Windows 下 tauri 会把它翻译成：
    //   http://claudio-cdn.localhost/?u=...
    // 两种都被 url crate 当成合法 URL 解析。
    let uri_str = req.uri().to_string();
    let parsed = Url::parse(&uri_str)
        .map_err(|e| anyhow::anyhow!("parse scheme uri {uri_str}: {e}"))?;
    let upstream = parsed
        .query_pairs()
        .find(|(k, _)| k == "u")
        .map(|(_, v)| v.into_owned())
        .ok_or_else(|| anyhow::anyhow!("missing ?u= query"))?;

    // ---- 2) 校验域名白名单，禁止当开放代理 ----
    let upstream_url = Url::parse(&upstream)
        .map_err(|e| anyhow::anyhow!("parse upstream {upstream}: {e}"))?;
    validate_upstream(&upstream_url)?;

    // ---- 3) 发给上游，注入官方 Referer + UA ----
    // `<audio>` 会用 GET，也可能预检 HEAD。其它方法拒绝。
    let method = match req.method().as_str() {
        "GET" => reqwest::Method::GET,
        "HEAD" => reqwest::Method::HEAD,
        other => anyhow::bail!("method not allowed: {other}"),
    };
    const REFERER: &str = "https://music.163.com/";
    const UA: &str = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 \
                      (KHTML, like Gecko) Chrome/118.0.0.0 Safari/537.36";
    let mut upstream_req = cdn_client()
        .request(method, upstream_url.as_str())
        .header("Referer", REFERER)
        .header("User-Agent", UA);

    // Range 必须透传，否则音频 seek 和渐进加载都会坏。
    if let Some(range) = req.headers().get(header::RANGE) {
        upstream_req = upstream_req.header(header::RANGE, range);
    }
    if let Some(inm) = req.headers().get(header::IF_NONE_MATCH) {
        upstream_req = upstream_req.header(header::IF_NONE_MATCH, inm);
    }
    if let Some(ims) = req.headers().get(header::IF_MODIFIED_SINCE) {
        upstream_req = upstream_req.header(header::IF_MODIFIED_SINCE, ims);
    }

    let mut upstream_resp = upstream_req
        .send()
        .await
        .map_err(|e| anyhow::anyhow!("upstream send: {e}"))?;

    let status = upstream_resp.status().as_u16();
    let mut builder = Response::builder().status(status);

    // ---- 4) 透传关键响应头 ----
    //
    // <audio> 跟 <img> 要正确渲染，至少需要 Content-Type。
    // Range 请求要 Content-Range + Accept-Ranges。
    // 其它都尽量透传一份，不要让 webview 过度缓存也不要让它不缓存。
    let upstream_headers = upstream_resp.headers().clone();
    for name in [
        header::CONTENT_TYPE,
        header::CONTENT_LENGTH,
        header::CONTENT_RANGE,
        header::ACCEPT_RANGES,
        header::CACHE_CONTROL,
        header::ETAG,
        header::LAST_MODIFIED,
        header::EXPIRES,
    ] {
        if let Some(v) = upstream_headers.get(&name) {
            builder = builder.header(name, v);
        }
    }
    // webview 对自定义 scheme 的 origin 判定很玄，直接放宽 CORS，保证不被卡。
    builder = builder.header(header::ACCESS_CONTROL_ALLOW_ORIGIN, "*");

    // ---- 5) 流式读 body，按 Content-Length 一次性预分配 ----
    //
    // Tauri 的 UriSchemeResponder 最终需要 Vec<u8>，没法真正流式回传。
    // 但用 `chunk()` 直接 append 进预分配 Vec，可以避免
    // `bytes().to_vec()` 的"先 Bytes 缓冲、再整块拷贝到 Vec"的双倍峰值。
    // 大歌（5MB+）的内存峰值会从 ~2× 文件大小降到 ~1×。
    //
    // 上限 64MB：网易云 320kbps mp3 单首很少超过 15MB，留 4× 余量。
    // 防止 Content-Length 是攻击/异常值时无限分配。
    const MAX_BODY: usize = 64 * 1024 * 1024;
    let content_length = upstream_resp
        .headers()
        .get(header::CONTENT_LENGTH)
        .and_then(|v| v.to_str().ok())
        .and_then(|s| s.parse::<usize>().ok());
    if let Some(len) = content_length {
        if len > MAX_BODY {
            anyhow::bail!("upstream body too large: {len}");
        }
    }
    let mut body: Vec<u8> = Vec::with_capacity(content_length.unwrap_or(0));
    while let Some(chunk) = upstream_resp
        .chunk()
        .await
        .map_err(|e| anyhow::anyhow!("read upstream body: {e}"))?
    {
        if body.len() + chunk.len() > MAX_BODY {
            anyhow::bail!("upstream body exceeded {MAX_BODY} bytes");
        }
        body.extend_from_slice(&chunk);
    }

    builder
        .body(body)
        .map_err(|e| anyhow::anyhow!("build response: {e}"))
}

fn error_response(code: StatusCode, msg: &str) -> Response<Vec<u8>> {
    Response::builder()
        .status(code)
        .header(header::CONTENT_TYPE, "text/plain; charset=utf-8")
        .header(header::ACCESS_CONTROL_ALLOW_ORIGIN, "*")
        .body(format!("claudio-cdn: {msg}").into_bytes())
        .expect("build error response")
}
