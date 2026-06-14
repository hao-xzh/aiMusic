use std::env;
use std::ffi::OsStr;
use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::sync::Arc;
use std::sync::OnceLock;

use anyhow::{anyhow, bail, Context, Result};
use netease::models::{SongUrl, TrackInfo, UserProfile};
use netease::NeteaseClient;
use reqwest::header;
use serde::{Deserialize, Serialize};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use url::Url;

mod netease;

const DEFAULT_PORT: u16 = 4577;
const DEFAULT_LEVEL: &str = "exhigh";
const MAX_BODY_BYTES: usize = 128 * 1024;
const MAX_UPSTREAM_BYTES: usize = 64 * 1024 * 1024;
const NETEASE_REFERER: &str = "https://music.163.com/";
const NETEASE_UA: &str = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 \
                          (KHTML, like Gecko) Chrome/118.0.0.0 Safari/537.36";

#[derive(Debug)]
struct Args {
    port: u16,
    cookie_path: Option<PathBuf>,
}

#[derive(Clone)]
struct AppState {
    client: Arc<NeteaseClient>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AccountOut {
    logged_in: bool,
    profile: Option<UserProfile>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct SearchOut {
    tracks: Vec<TrackInfo>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct SongUrlOut {
    id: i64,
    br: i64,
    size: i64,
    playable: bool,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ExportRequest {
    id: i64,
    out_dir: Option<String>,
    level: Option<String>,
    ffmpeg: Option<String>,
    overwrite: Option<bool>,
    keep_source: Option<bool>,
    name: Option<String>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct ExportOut {
    id: i64,
    path: String,
    source_path: Option<String>,
    source_format: String,
    converted: bool,
    bytes: i64,
    opened_folder: bool,
    open_folder_error: Option<String>,
}

#[tokio::main]
async fn main() -> Result<()> {
    let args = parse_args(env::args().skip(1))?;
    let cookie_path = resolve_cookie_path(args.cookie_path.as_deref())?;
    let client = NeteaseClient::new_with_persist(Some(cookie_path.clone()))
        .with_context(|| format!("load cookies from {}", cookie_path.display()))?;
    let logged_in = client.cookie_value("MUSIC_U").is_some();
    if !logged_in {
        eprintln!(
            "warning: cookie file found, but MUSIC_U is missing: {}",
            cookie_path.display()
        );
        eprintln!("open the main Pipo app and log in first, or pass --cookie <path>");
    }

    let state = AppState {
        client: Arc::new(client),
    };
    let addr = format!("127.0.0.1:{}", args.port);
    let listener = TcpListener::bind(&addr)
        .await
        .with_context(|| format!("bind {addr}"))?;

    println!("NetEase export test page");
    println!("cookie: {}", cookie_path.display());
    println!("open:   http://{addr}");

    loop {
        let (stream, _) = listener.accept().await?;
        let state = state.clone();
        tokio::spawn(async move {
            if let Err(e) = handle_connection(stream, state).await {
                eprintln!("request failed: {e:#}");
            }
        });
    }
}

async fn handle_connection(mut stream: TcpStream, state: AppState) -> Result<()> {
    let request = read_request(&mut stream).await?;
    let result = route(request, state).await;
    match result {
        Ok(response) => write_response(&mut stream, response).await,
        Err(e) => {
            let body = serde_json::json!({ "error": user_facing_error(&e) }).to_string();
            write_response(
                &mut stream,
                Response::json(500, "Internal Server Error", body.into_bytes()),
            )
            .await
        }
    }
}

async fn route(request: Request, state: AppState) -> Result<Response> {
    let url = Url::parse(&format!("http://local{}", request.target))
        .with_context(|| format!("parse request target {}", request.target))?;
    let path = url.path();
    match (request.method.as_str(), path) {
        ("GET", "/") => Ok(Response::html(INDEX_HTML.as_bytes().to_vec())),
        ("GET", "/api/account") => account(state).await,
        ("GET", "/api/search") => search(url, state).await,
        ("GET", "/api/song-url") => song_url(url, state).await,
        ("GET", "/audio") => audio(url, state).await,
        ("POST", "/api/export") => export(request.body, state).await,
        _ => Ok(Response::text(404, "Not Found", b"not found".to_vec())),
    }
}

async fn account(state: AppState) -> Result<Response> {
    let logged_in = state.client.cookie_value("MUSIC_U").is_some();
    let profile = if logged_in {
        state.client.account().await.ok().flatten()
    } else {
        None
    };
    Response::json_value(200, "OK", &AccountOut { logged_in, profile })
}

async fn search(url: Url, state: AppState) -> Result<Response> {
    let q = query_param(&url, "q").unwrap_or_default();
    if q.trim().is_empty() {
        return Response::json_value(200, "OK", &SearchOut { tracks: Vec::new() });
    }
    let limit = query_param(&url, "limit")
        .and_then(|v| v.parse::<i64>().ok())
        .unwrap_or(20)
        .clamp(1, 50);
    let tracks = state.client.search_tracks(&q, limit).await?;
    Response::json_value(200, "OK", &SearchOut { tracks })
}

async fn song_url(url: Url, state: AppState) -> Result<Response> {
    let id = required_i64(&url, "id")?;
    let level = query_param(&url, "level").unwrap_or_else(|| DEFAULT_LEVEL.to_string());
    let item = resolve_song_url(&state.client, id, &level).await?;
    Response::json_value(
        200,
        "OK",
        &SongUrlOut {
            id: item.id,
            br: item.br,
            size: item.size,
            playable: item.url.as_deref().is_some_and(|u| !u.is_empty()),
        },
    )
}

async fn audio(url: Url, state: AppState) -> Result<Response> {
    let id = required_i64(&url, "id")?;
    let level = query_param(&url, "level").unwrap_or_else(|| DEFAULT_LEVEL.to_string());
    let item = resolve_song_url(&state.client, id, &level).await?;
    let raw_url = item
        .url
        .as_deref()
        .filter(|u| !u.is_empty())
        .ok_or_else(|| anyhow!("歌曲 {id} 没有可播放地址，可能是权限不足或当前音质不可用"))?;
    let upstream = Url::parse(raw_url)?;
    let upstream_resp = cdn_get(&upstream).await?;
    if upstream_resp.status >= 400 {
        bail!("音频下载源返回状态码 {}", upstream_resp.status);
    }
    let ext = infer_ext_from_url(raw_url)
        .or_else(|| {
            upstream_resp
                .headers
                .get("content-type")
                .and_then(|v| v.to_str().ok())
                .and_then(ext_from_content_type)
        })
        .unwrap_or_else(|| "bin".to_string());
    Ok(Response::binary(
        200,
        "OK",
        content_type_from_ext(&ext),
        upstream_resp.body,
    ))
}

async fn export(body: Vec<u8>, state: AppState) -> Result<Response> {
    let req: ExportRequest = serde_json::from_slice(&body).context("解析导出请求失败")?;
    let out = export_track(&state.client, req).await?;
    Response::json_value(200, "OK", &out)
}

async fn export_track(client: &NeteaseClient, req: ExportRequest) -> Result<ExportOut> {
    let level = req.level.as_deref().unwrap_or(DEFAULT_LEVEL);
    let item = resolve_song_url(client, req.id, level).await?;
    let raw_url = item
        .url
        .as_deref()
        .filter(|u| !u.is_empty())
        .ok_or_else(|| {
            anyhow!(
                "歌曲 {} 没有可播放地址，可能是权限不足或当前音质不可用",
                req.id
            )
        })?;
    let upstream = Url::parse(raw_url)?;
    let upstream_resp = cdn_get(&upstream).await?;
    if upstream_resp.status >= 400 {
        bail!("音频下载源返回状态码 {}", upstream_resp.status);
    }

    let ext = infer_ext_from_url(raw_url)
        .or_else(|| {
            upstream_resp
                .headers
                .get("content-type")
                .and_then(|v| v.to_str().ok())
                .and_then(ext_from_content_type)
        })
        .unwrap_or_else(|| "bin".to_string());

    let details = client
        .song_detail(&[req.id])
        .await
        .ok()
        .and_then(|mut tracks| tracks.pop());
    let stem = req
        .name
        .filter(|s| !s.trim().is_empty())
        .or_else(|| details.as_ref().map(track_file_stem))
        .unwrap_or_else(|| req.id.to_string());
    let stem = sanitize_file_stem(&stem);
    let out_dir = PathBuf::from(req.out_dir.unwrap_or_else(|| "netease-export".to_string()));
    fs::create_dir_all(&out_dir)
        .with_context(|| format!("创建输出目录失败：{}", out_dir.display()))?;
    let target = out_dir.join(format!("{stem}.mp3"));
    let overwrite = req.overwrite.unwrap_or(false);
    let keep_source = req.keep_source.unwrap_or(false);
    let bytes = upstream_resp.body.len() as i64;

    if ext == "mp3" {
        write_file_atomic(&target, &upstream_resp.body, overwrite)?;
        let open_folder_error = reveal_in_file_manager(&target)
            .err()
            .map(|e| format!("{e:#}"));
        let opened_folder = open_folder_error.is_none();
        return Ok(ExportOut {
            id: req.id,
            path: target.to_string_lossy().to_string(),
            source_path: None,
            source_format: ext,
            converted: false,
            bytes,
            opened_folder,
            open_folder_error,
        });
    }

    let source = if keep_source {
        out_dir.join(format!("{stem}.{ext}"))
    } else {
        env::temp_dir().join(format!(
            "netease_export_test_{}_{}.{}",
            std::process::id(),
            req.id,
            ext
        ))
    };
    write_file_atomic(&source, &upstream_resp.body, true)?;
    let ffmpeg = PathBuf::from(req.ffmpeg.unwrap_or_else(|| "ffmpeg".to_string()));
    convert_to_mp3(&ffmpeg, &source, &target, overwrite)?;
    let source_path = keep_source.then(|| source.to_string_lossy().to_string());
    if !keep_source {
        let _ = fs::remove_file(&source);
    }
    let open_folder_error = reveal_in_file_manager(&target)
        .err()
        .map(|e| format!("{e:#}"));
    let opened_folder = open_folder_error.is_none();

    Ok(ExportOut {
        id: req.id,
        path: target.to_string_lossy().to_string(),
        source_path,
        source_format: ext,
        converted: true,
        bytes,
        opened_folder,
        open_folder_error,
    })
}

async fn resolve_song_url(client: &NeteaseClient, id: i64, level: &str) -> Result<SongUrl> {
    let urls = client.song_urls(&[id], level).await?;
    urls.into_iter()
        .find(|item| item.id == id)
        .ok_or_else(|| anyhow!("网易云没有返回歌曲 {id} 的播放地址"))
}

fn convert_to_mp3(ffmpeg: &Path, input: &Path, output: &Path, overwrite: bool) -> Result<()> {
    if output.exists() && !overwrite {
        return Err(existing_file_error(output));
    }
    ensure_parent(output)?;
    let status = Command::new(ffmpeg)
        .arg(if overwrite { "-y" } else { "-n" })
        .arg("-hide_banner")
        .arg("-loglevel")
        .arg("error")
        .arg("-i")
        .arg(input)
        .arg("-vn")
        .arg("-codec:a")
        .arg("libmp3lame")
        .arg("-b:a")
        .arg("320k")
        .arg(output)
        .status()
        .with_context(|| format!("启动 ffmpeg 失败：{}", ffmpeg.display()))?;
    if !status.success() {
        bail!("ffmpeg 转码失败，退出状态：{status}");
    }
    Ok(())
}

fn write_file_atomic(path: &Path, bytes: &[u8], overwrite: bool) -> Result<()> {
    if path.exists() && !overwrite {
        return Err(existing_file_error(path));
    }
    ensure_parent(path)?;
    let tmp = path.with_extension(format!(
        "{}.tmp",
        path.extension()
            .and_then(OsStr::to_str)
            .unwrap_or("download")
    ));
    {
        let mut file = fs::File::create(&tmp)?;
        file.write_all(bytes)?;
        file.flush()?;
    }
    if overwrite && path.exists() {
        fs::remove_file(path)?;
    }
    fs::rename(&tmp, path)?;
    Ok(())
}

fn existing_file_error(path: &Path) -> anyhow::Error {
    anyhow!(
        "文件已存在：{}。如需覆盖，请把“覆盖选项”改成“覆盖已有文件”。",
        path.display()
    )
}

fn reveal_in_file_manager(path: &Path) -> Result<()> {
    let absolute = fs::canonicalize(path).unwrap_or_else(|_| path.to_path_buf());

    #[cfg(target_os = "windows")]
    {
        Command::new("explorer")
            .arg(format!("/select,{}", absolute.to_string_lossy()))
            .spawn()
            .with_context(|| format!("打开文件所在文件夹失败：{}", absolute.display()))?;
    }

    #[cfg(target_os = "macos")]
    {
        Command::new("open")
            .arg("-R")
            .arg(&absolute)
            .spawn()
            .with_context(|| format!("打开文件所在文件夹失败：{}", absolute.display()))?;
    }

    #[cfg(all(unix, not(target_os = "macos")))]
    {
        let dir = absolute.parent().unwrap_or_else(|| Path::new("."));
        Command::new("xdg-open")
            .arg(dir)
            .spawn()
            .with_context(|| format!("打开文件夹失败：{}", dir.display()))?;
    }

    Ok(())
}

fn user_facing_error(error: &anyhow::Error) -> String {
    let message = format!("{error:#}");
    if message.contains("启动 ffmpeg 失败") {
        return format!("{message}\n如果返回的音频不是 MP3，需要安装 ffmpeg，或在页面里填写 ffmpeg.exe 的完整路径。");
    }
    message
}

fn ensure_parent(path: &Path) -> Result<()> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    Ok(())
}

fn track_file_stem(track: &TrackInfo) -> String {
    let artists = track
        .artists
        .iter()
        .map(|artist| artist.name.as_str())
        .filter(|name| !name.trim().is_empty())
        .collect::<Vec<_>>()
        .join(", ");
    if artists.is_empty() {
        track.name.clone()
    } else {
        format!("{artists} - {}", track.name)
    }
}

fn sanitize_file_stem(input: &str) -> String {
    let mut out = String::new();
    for ch in input.chars() {
        match ch {
            '<' | '>' | ':' | '"' | '/' | '\\' | '|' | '?' | '*' => out.push('_'),
            ch if ch.is_control() => out.push('_'),
            ch => out.push(ch),
        }
    }
    let out = out.trim().trim_matches('.').to_string();
    if out.is_empty() {
        "netease-track".to_string()
    } else {
        out
    }
}

fn content_type_from_ext(ext: &str) -> &'static str {
    match ext {
        "mp3" => "audio/mpeg",
        "flac" => "audio/flac",
        "m4a" => "audio/mp4",
        "aac" => "audio/aac",
        "ogg" => "audio/ogg",
        "wav" => "audio/wav",
        _ => "application/octet-stream",
    }
}

struct UpstreamResp {
    status: u16,
    headers: reqwest::header::HeaderMap,
    body: Vec<u8>,
}

fn cdn_client() -> &'static reqwest::Client {
    static CLIENT: OnceLock<reqwest::Client> = OnceLock::new();
    CLIENT.get_or_init(|| {
        reqwest::Client::builder()
            .gzip(true)
            .build()
            .expect("build cdn reqwest client")
    })
}

fn validate_upstream(url: &Url) -> Result<()> {
    let host = url.host_str().unwrap_or("").to_ascii_lowercase();
    let allowed = host == "music.163.com"
        || host.ends_with(".music.163.com")
        || host.ends_with(".music.126.net");
    if !allowed {
        bail!("host not in allowlist: {host}");
    }
    match url.scheme() {
        "http" | "https" => Ok(()),
        other => bail!("upstream scheme not allowed: {other}"),
    }
}

async fn cdn_get(url: &Url) -> Result<UpstreamResp> {
    validate_upstream(url)?;
    let mut resp = cdn_client()
        .get(url.as_str())
        .header(header::REFERER, NETEASE_REFERER)
        .header(header::USER_AGENT, NETEASE_UA)
        .send()
        .await
        .map_err(|e| anyhow!("upstream send: {e}"))?;

    let status = resp.status().as_u16();
    let headers = resp.headers().clone();
    let content_length = headers
        .get(header::CONTENT_LENGTH)
        .and_then(|v| v.to_str().ok())
        .and_then(|s| s.parse::<usize>().ok());
    if let Some(len) = content_length {
        if len > MAX_UPSTREAM_BYTES {
            bail!("upstream body too large: {len}");
        }
    }

    let mut body = Vec::with_capacity(content_length.unwrap_or(0));
    while let Some(chunk) = resp
        .chunk()
        .await
        .map_err(|e| anyhow!("read upstream body: {e}"))?
    {
        if body.len() + chunk.len() > MAX_UPSTREAM_BYTES {
            bail!("upstream body exceeded {MAX_UPSTREAM_BYTES} bytes");
        }
        body.extend_from_slice(&chunk);
    }
    Ok(UpstreamResp {
        status,
        headers,
        body,
    })
}

fn infer_ext_from_url(raw: &str) -> Option<String> {
    let path = raw.split('?').next().unwrap_or(raw);
    let ext = Path::new(path)
        .extension()
        .and_then(OsStr::to_str)?
        .to_ascii_lowercase();
    match ext.as_str() {
        "mp3" | "flac" | "m4a" | "aac" | "ogg" | "wav" => Some(ext),
        _ => None,
    }
}

fn ext_from_content_type(ct: &str) -> Option<String> {
    let ct = ct
        .split(';')
        .next()
        .unwrap_or(ct)
        .trim()
        .to_ascii_lowercase();
    match ct.as_str() {
        "audio/mpeg" | "audio/mp3" => Some("mp3".to_string()),
        "audio/flac" | "audio/x-flac" => Some("flac".to_string()),
        "audio/mp4" | "audio/x-m4a" => Some("m4a".to_string()),
        "audio/aac" => Some("aac".to_string()),
        "audio/ogg" | "application/ogg" => Some("ogg".to_string()),
        "audio/wav" | "audio/wave" | "audio/x-wav" => Some("wav".to_string()),
        _ => None,
    }
}

fn query_param(url: &Url, name: &str) -> Option<String> {
    url.query_pairs()
        .find(|(key, _)| key == name)
        .map(|(_, value)| value.into_owned())
}

fn required_i64(url: &Url, name: &str) -> Result<i64> {
    query_param(url, name)
        .ok_or_else(|| anyhow!("missing query param {name}"))?
        .parse::<i64>()
        .with_context(|| format!("invalid {name}"))
}

#[derive(Debug)]
struct Request {
    method: String,
    target: String,
    body: Vec<u8>,
}

async fn read_request(stream: &mut TcpStream) -> Result<Request> {
    let mut buf = Vec::new();
    let mut tmp = [0u8; 4096];
    loop {
        let n = stream.read(&mut tmp).await?;
        if n == 0 {
            bail!("connection closed");
        }
        buf.extend_from_slice(&tmp[..n]);
        if buf.windows(4).any(|w| w == b"\r\n\r\n") {
            break;
        }
        if buf.len() > MAX_BODY_BYTES {
            bail!("request headers too large");
        }
    }

    let header_end = buf
        .windows(4)
        .position(|w| w == b"\r\n\r\n")
        .map(|pos| pos + 4)
        .ok_or_else(|| anyhow!("missing header terminator"))?;
    let header_text = String::from_utf8_lossy(&buf[..header_end]);
    let mut lines = header_text.lines();
    let request_line = lines
        .next()
        .ok_or_else(|| anyhow!("missing request line"))?;
    let mut request_parts = request_line.split_whitespace();
    let method = request_parts
        .next()
        .ok_or_else(|| anyhow!("missing method"))?
        .to_string();
    let target = request_parts
        .next()
        .ok_or_else(|| anyhow!("missing target"))?
        .to_string();
    let mut content_length = 0usize;
    for line in lines {
        if let Some((name, value)) = line.split_once(':') {
            if name.eq_ignore_ascii_case("content-length") {
                content_length = value.trim().parse::<usize>().unwrap_or(0);
            }
        }
    }
    if content_length > MAX_BODY_BYTES {
        bail!("request body too large");
    }

    let mut body = buf[header_end..].to_vec();
    while body.len() < content_length {
        let n = stream.read(&mut tmp).await?;
        if n == 0 {
            break;
        }
        body.extend_from_slice(&tmp[..n]);
    }
    body.truncate(content_length);

    Ok(Request {
        method,
        target,
        body,
    })
}

struct Response {
    status: u16,
    reason: &'static str,
    content_type: &'static str,
    body: Vec<u8>,
}

impl Response {
    fn html(body: Vec<u8>) -> Self {
        Self::binary(200, "OK", "text/html; charset=utf-8", body)
    }

    fn text(status: u16, reason: &'static str, body: Vec<u8>) -> Self {
        Self::binary(status, reason, "text/plain; charset=utf-8", body)
    }

    fn json(status: u16, reason: &'static str, body: Vec<u8>) -> Self {
        Self::binary(status, reason, "application/json; charset=utf-8", body)
    }

    fn json_value<T: Serialize>(status: u16, reason: &'static str, value: &T) -> Result<Self> {
        Ok(Self::json(status, reason, serde_json::to_vec(value)?))
    }

    fn binary(
        status: u16,
        reason: &'static str,
        content_type: &'static str,
        body: Vec<u8>,
    ) -> Self {
        Self {
            status,
            reason,
            content_type,
            body,
        }
    }
}

async fn write_response(stream: &mut TcpStream, response: Response) -> Result<()> {
    let header = format!(
        "HTTP/1.1 {} {}\r\nContent-Type: {}\r\nContent-Length: {}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n",
        response.status,
        response.reason,
        response.content_type,
        response.body.len()
    );
    stream.write_all(header.as_bytes()).await?;
    stream.write_all(&response.body).await?;
    Ok(())
}

fn parse_args<I>(args: I) -> Result<Args>
where
    I: IntoIterator<Item = String>,
{
    let mut port = DEFAULT_PORT;
    let mut cookie_path = None;
    let mut args = args.into_iter();
    while let Some(arg) = args.next() {
        match arg.as_str() {
            "-h" | "--help" => {
                print_usage();
                std::process::exit(0);
            }
            "--port" => {
                let value = args
                    .next()
                    .ok_or_else(|| anyhow!("--port requires a value"))?;
                port = value.parse::<u16>().context("parse --port")?;
            }
            "--cookie" => {
                let value = args
                    .next()
                    .ok_or_else(|| anyhow!("--cookie requires a value"))?;
                cookie_path = Some(PathBuf::from(value));
            }
            other => bail!("unknown argument: {other}"),
        }
    }
    Ok(Args { port, cookie_path })
}

fn print_usage() {
    println!(
        "Usage: cargo run --manifest-path tools/netease-export-test/Cargo.toml -- [--port 4577] [--cookie path]"
    );
}

fn resolve_cookie_path(explicit: Option<&Path>) -> Result<PathBuf> {
    if let Some(path) = explicit {
        if path.exists() {
            return Ok(path.to_path_buf());
        }
        bail!("cookie file not found: {}", path.display());
    }
    let candidates = default_cookie_paths();
    for path in &candidates {
        if path.exists() {
            return Ok(path.clone());
        }
    }
    let shown = candidates
        .iter()
        .map(|path| format!("  {}", path.display()))
        .collect::<Vec<_>>()
        .join("\n");
    bail!("could not find netease_cookies.json. Tried:\n{shown}\nPass --cookie <path>.");
}

fn default_cookie_paths() -> Vec<PathBuf> {
    let mut paths = Vec::new();
    if let Ok(path) = env::var("PIPO_NETEASE_COOKIE_PATH") {
        paths.push(PathBuf::from(path));
    }
    if let Ok(appdata) = env::var("APPDATA") {
        for name in ["app.claudio.desktop", "Pipo", "claudio"] {
            paths.push(
                PathBuf::from(&appdata)
                    .join(name)
                    .join("netease_cookies.json"),
            );
        }
    }
    if let Ok(home) = env::var("HOME").or_else(|_| env::var("USERPROFILE")) {
        let home = PathBuf::from(home);
        for name in ["app.claudio.desktop", "Pipo", "claudio"] {
            paths.push(
                home.join("Library")
                    .join("Application Support")
                    .join(name)
                    .join("netease_cookies.json"),
            );
        }
        let config = env::var("XDG_CONFIG_HOME")
            .map(PathBuf::from)
            .unwrap_or_else(|_| home.join(".config"));
        for name in ["app.claudio.desktop", "Pipo", "claudio"] {
            paths.push(config.join(name).join("netease_cookies.json"));
        }
    }
    paths
}

const INDEX_HTML: &str = r#"<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>网易云导出测试</title>
  <style>
    :root { color-scheme: dark; font-family: Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; }
    body { margin: 0; background: #07090f; color: #e9efff; }
    main { max-width: 1040px; margin: 0 auto; padding: 28px clamp(16px, 4vw, 40px) 60px; }
    h1 { margin: 0; font-size: clamp(28px, 5vw, 48px); line-height: 1; letter-spacing: 0; }
    .sub { color: #8a93a8; margin-top: 10px; line-height: 1.55; max-width: 760px; }
    .panel { border-top: 1px solid rgba(233,239,255,.12); border-bottom: 1px solid rgba(233,239,255,.12); padding: 18px 0; margin-top: 24px; }
    .grid { display: grid; grid-template-columns: minmax(220px, 1.4fr) repeat(3, minmax(140px, .6fr)); gap: 12px; }
    .search-field { grid-column: span 2; }
    label { display: flex; flex-direction: column; gap: 6px; color: #8a93a8; font-size: 12px; font-weight: 700; }
    input, select { height: 42px; box-sizing: border-box; border-radius: 0; border: 1px solid rgba(233,239,255,.12); background: rgba(10,13,20,.76); color: #e9efff; padding: 0 12px; font: inherit; outline: none; }
    .actions { display: flex; gap: 10px; flex-wrap: wrap; margin-top: 14px; }
    button { height: 38px; border-radius: 0; border: 1px solid rgba(233,239,255,.14); background: rgba(233,239,255,.04); color: #e9efff; padding: 0 15px; font-weight: 800; cursor: pointer; }
    button.primary { background: rgba(233,239,255,.94); color: #05060a; border-color: rgba(233,239,255,.94); }
    button:disabled { opacity: .45; cursor: default; }
    .status { margin-top: 14px; border: 1px solid rgba(233,239,255,.12); padding: 10px 12px; color: #b7bfd3; white-space: pre-wrap; word-break: break-word; }
    .status.ok { color: #c9f0dc; border-color: rgba(155,227,198,.28); background: rgba(155,227,198,.08); }
    .status.warn { color: #ffe1a6; border-color: rgba(255,210,138,.28); background: rgba(255,210,138,.08); }
    .status.err { color: #ffb4b4; border-color: rgba(255,180,180,.28); background: rgba(255,180,180,.08); }
    .results { display: grid; gap: 8px; margin-top: 18px; }
    .track { display: grid; grid-template-columns: minmax(0,1fr) auto; gap: 12px; align-items: center; padding: 12px 0; border-top: 1px solid rgba(233,239,255,.08); }
    .title { font-weight: 800; }
    .meta { color: #8a93a8; font-size: 12px; margin-top: 4px; }
    .id { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; color: #647089; }
    audio { width: 100%; margin-top: 18px; height: 42px; }
    .export-row { display: grid; grid-template-columns: minmax(220px, 1fr) minmax(120px, .4fr) minmax(120px, .4fr); gap: 12px; margin-top: 18px; }
    .tiny { color: #647089; font-size: 12px; line-height: 1.5; margin-top: 10px; }
    @media (max-width: 760px) {
      .grid, .export-row { grid-template-columns: 1fr; }
      .search-field { grid-column: auto; }
      .track { grid-template-columns: 1fr; }
      .actions { align-items: stretch; }
      button { width: 100%; }
    }
  </style>
</head>
<body>
  <main>
    <h1>网易云导出测试</h1>
    <div class="sub">输入歌名或歌手搜索，先试听，再导出 MP3。不需要你手动找歌曲 ID。</div>
    <div id="account" class="status">正在检查登录状态...</div>

    <section class="panel">
      <div class="grid">
        <label class="search-field">搜索
          <input id="query" placeholder="歌曲名 / 歌手" />
        </label>
        <label>音质
          <select id="level">
            <option value="exhigh">exhigh - 320k MP3</option>
            <option value="higher">higher</option>
            <option value="standard">standard</option>
            <option value="lossless">lossless</option>
          </select>
        </label>
        <label>数量
          <input id="limit" value="20" inputmode="numeric" />
        </label>
      </div>
      <div class="actions">
        <button id="searchBtn" class="primary">搜索</button>
        <button id="clearBtn">清空</button>
      </div>
      <div id="status" class="status">准备好了。</div>
    </section>

    <section class="panel">
      <div class="export-row">
        <label>保存目录
          <input id="outDir" value="netease-export" />
        </label>
        <label>ffmpeg
          <input id="ffmpeg" value="ffmpeg" />
        </label>
        <label>覆盖选项
          <select id="overwrite">
            <option value="false">不覆盖已有文件</option>
            <option value="true">覆盖已有文件</option>
          </select>
        </label>
      </div>
      <div class="tiny">提示：优先选择 exhigh，通常能直接拿到 MP3。如果返回的是 FLAC/M4A/AAC，转 MP3 需要 ffmpeg。</div>
    </section>

    <audio id="player" controls hidden></audio>
    <div id="results" class="results"></div>
  </main>
  <script>
    const $ = (id) => document.getElementById(id);
    let selectedId = null;

    function setStatus(text, tone = '') {
      const el = $('status');
      el.textContent = text;
      el.className = `status ${tone}`;
    }
    function setAccount(text, tone = '') {
      const el = $('account');
      el.textContent = text;
      el.className = `status ${tone}`;
    }
    function artistText(track) {
      return (track.artists || []).map((a) => a.name).filter(Boolean).join(', ') || '未知歌手';
    }
    function albumText(track) {
      return track.album?.name ? ` · ${track.album.name}` : '';
    }
    function durationText(ms) {
      if (!ms) return '';
      const s = Math.round(ms / 1000);
      return ` · ${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
    }
    function artistLabel(track) {
      return (track.artists || []).map((a) => a.name).filter(Boolean).join(', ') || '未知歌手';
    }
    function albumLabel(track) {
      return track.album?.name ? ` · ${track.album.name}` : '';
    }
    function durationLabel(ms) {
      if (!ms) return '';
      const s = Math.round(ms / 1000);
      return ` · ${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
    }
    async function api(path, options) {
      const res = await fetch(path, options);
      const text = await res.text();
      let data;
      try { data = text ? JSON.parse(text) : null; } catch { data = text; }
      if (!res.ok) throw new Error(data?.error || text || `HTTP ${res.status}`);
      return data;
    }
    async function loadAccount() {
      try {
        const data = await api('/api/account');
        if (data.loggedIn) setAccount(data.profile ? `已登录：${data.profile.nickname}` : '已登录', 'ok');
        else setAccount('还没有登录。请先打开主应用登录网易云，或者用 --cookie 指定 cookie 文件后重启测试工具。', 'warn');
      } catch (e) {
        setAccount(e.message || String(e), 'err');
      }
    }
    async function search() {
      const q = $('query').value.trim();
      if (!q) return setStatus('请先输入歌曲名或歌手。', 'warn');
      setStatus('正在搜索...');
      $('results').innerHTML = '';
      $('player').hidden = true;
      const limit = encodeURIComponent($('limit').value || '20');
      try {
        const data = await api(`/api/search?q=${encodeURIComponent(q)}&limit=${limit}`);
        renderTracks(data.tracks || []);
        setStatus(`找到 ${(data.tracks || []).length} 首候选歌曲。`, 'ok');
      } catch (e) {
        setStatus(e.message || String(e), 'err');
      }
    }
    function renderTracks(tracks) {
      const root = $('results');
      root.innerHTML = '';
      for (const track of tracks) {
        const row = document.createElement('div');
        row.className = 'track';
        row.innerHTML = `
          <div>
            <div class="title"></div>
            <div class="meta"></div>
          </div>
          <div class="actions">
            <button data-action="preview">试听</button>
            <button data-action="export" class="primary">导出 MP3</button>
          </div>`;
        row.querySelector('.title').textContent = track.name;
        row.querySelector('.meta').innerHTML =
          `${artistLabel(track)}${albumLabel(track)}${durationLabel(track.durationMs)} <span class="id">#${track.id}</span>`;
        row.querySelector('[data-action="preview"]').onclick = () => preview(track.id);
        row.querySelector('[data-action="export"]').onclick = () => exportMp3(track);
        root.appendChild(row);
      }
    }
    async function preview(id) {
      selectedId = id;
      const level = $('level').value;
      setStatus(`正在获取 #${id} 的播放地址...`);
      try {
        const data = await api(`/api/song-url?id=${id}&level=${encodeURIComponent(level)}`);
        if (!data.playable) return setStatus(`#${id} 没有可播放地址，可能没有权限或当前音质不可用。`, 'warn');
        const player = $('player');
        player.src = `/audio?id=${id}&level=${encodeURIComponent(level)}`;
        player.hidden = false;
        await player.play().catch(() => {});
        setStatus(`#${id} 已开始试听。`, 'ok');
      } catch (e) {
        setStatus(e.message || String(e), 'err');
      }
    }
    async function exportMp3(track) {
      const id = track.id || selectedId;
      if (!id) return setStatus('请先选择一首歌。', 'warn');
      const body = {
        id,
        level: $('level').value,
        outDir: $('outDir').value || 'netease-export',
        ffmpeg: $('ffmpeg').value || 'ffmpeg',
        overwrite: $('overwrite').value === 'true',
        name: `${artistLabel(track)} - ${track.name}`,
      };
      setStatus(`正在导出 #${id}...`);
      try {
        const data = await api('/api/export', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(body),
        });
        const detail = data.converted ? `已从 ${data.sourceFormat} 转码为 MP3` : '已直接保存 MP3';
        const folderLine = data.openedFolder
          ? '已打开文件所在文件夹。'
          : `文件已保存，但自动打开文件夹失败：${data.openFolderError || '未知原因'}`;
        setStatus(`已保存：${data.path}\n${detail}\n${folderLine}`, data.openedFolder ? 'ok' : 'warn');
      } catch (e) {
        setStatus(e.message || String(e), 'err');
      }
    }
    $('searchBtn').onclick = search;
    $('clearBtn').onclick = () => {
      $('query').value = '';
      $('results').innerHTML = '';
      $('player').hidden = true;
      setStatus('准备好了。');
    };
    $('query').addEventListener('keydown', (e) => {
      if (e.key === 'Enter') search();
    });
    loadAccount();
  </script>
</body>
</html>
"#;
