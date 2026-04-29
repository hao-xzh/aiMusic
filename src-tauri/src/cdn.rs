//! 网易云 CDN 共享层 —— 复用的 reqwest client + Referer/UA 注入 fetch。
//!
//! 之前 `proxy.rs` 一家做了"referer 注入 + 透传"。后来加了磁盘缓存（`audio` 模块），
//! 它命中失败时也要走同样的"带 referer 拉上游"逻辑 —— 不能直接 reqwest，否则
//! 网易云 CDN 一律 403。把这部分抽出来这两边共用。

use std::sync::OnceLock;

use anyhow::Result;
use tauri::http::header;
use url::Url;

/// 官方客户端的 Referer。网易云 CDN 认这个值。
const REFERER: &str = "https://music.163.com/";

const UA: &str = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 \
                  (KHTML, like Gecko) Chrome/118.0.0.0 Safari/537.36";

/// 全局复用的 reqwest client：连接池 + DNS 缓存跨请求复用。
pub fn cdn_client() -> &'static reqwest::Client {
    static CLIENT: OnceLock<reqwest::Client> = OnceLock::new();
    CLIENT.get_or_init(|| {
        reqwest::Client::builder()
            .gzip(true)
            .build()
            .expect("build cdn reqwest client")
    })
}

/// 校验上游 URL 在白名单里 —— 避免被当成开放代理。
pub fn validate_upstream(url: &Url) -> Result<()> {
    let host = url.host_str().unwrap_or("").to_ascii_lowercase();
    let allowed = host == "music.163.com"
        || host.ends_with(".music.163.com")
        || host.ends_with(".music.126.net");
    if !allowed {
        anyhow::bail!("host not in allowlist: {host}");
    }
    match url.scheme() {
        "http" | "https" => Ok(()),
        other => anyhow::bail!("upstream scheme not allowed: {other}"),
    }
}

/// 单次 HTTP 响应 —— status + 响应头 + body。
pub struct UpstreamResp {
    pub status: u16,
    pub headers: reqwest::header::HeaderMap,
    pub body: Vec<u8>,
}

/// GET 上游（带 Referer/UA + 可选 Range/缓存条件头）。读尽 body 一次性返回。
///
/// 上限 64MB：网易云单首很少超过 15MB。Content-Length 异常大会直接拒。
pub async fn cdn_get(
    url: &Url,
    range: Option<&str>,
    if_none_match: Option<&str>,
    if_modified_since: Option<&str>,
) -> Result<UpstreamResp> {
    validate_upstream(url)?;

    let mut req = cdn_client()
        .get(url.as_str())
        .header("Referer", REFERER)
        .header("User-Agent", UA);
    if let Some(r) = range {
        req = req.header(header::RANGE, r);
    }
    if let Some(v) = if_none_match {
        req = req.header(header::IF_NONE_MATCH, v);
    }
    if let Some(v) = if_modified_since {
        req = req.header(header::IF_MODIFIED_SINCE, v);
    }

    let mut resp = req
        .send()
        .await
        .map_err(|e| anyhow::anyhow!("upstream send: {e}"))?;

    let status = resp.status().as_u16();
    let headers = resp.headers().clone();

    const MAX_BODY: usize = 64 * 1024 * 1024;
    let content_length = headers
        .get(header::CONTENT_LENGTH)
        .and_then(|v| v.to_str().ok())
        .and_then(|s| s.parse::<usize>().ok());
    if let Some(len) = content_length {
        if len > MAX_BODY {
            anyhow::bail!("upstream body too large: {len}");
        }
    }
    let mut body: Vec<u8> = Vec::with_capacity(content_length.unwrap_or(0));
    while let Some(chunk) = resp
        .chunk()
        .await
        .map_err(|e| anyhow::anyhow!("read upstream body: {e}"))?
    {
        if body.len() + chunk.len() > MAX_BODY {
            anyhow::bail!("upstream body exceeded {MAX_BODY} bytes");
        }
        body.extend_from_slice(&chunk);
    }

    Ok(UpstreamResp {
        status,
        headers,
        body,
    })
}
