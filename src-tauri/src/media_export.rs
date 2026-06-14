use std::ffi::OsStr;
use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::sync::Arc;

use anyhow::{anyhow, bail, Context, Result};
use serde::Serialize;
use tauri::{AppHandle, Manager, State};
use url::Url;

use crate::audio::cache::{ext_from_content_type, infer_ext_from_url};
use crate::cdn::cdn_get;
use crate::netease::NeteaseClient;

pub type NeteaseState<'a> = State<'a, Arc<NeteaseClient>>;

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ExportedTrack {
    pub id: i64,
    pub path: String,
    pub source_path: Option<String>,
    pub source_format: String,
    pub converted: bool,
    pub bytes: i64,
    pub opened_folder: bool,
    pub open_folder_error: Option<String>,
}

#[tauri::command]
pub async fn netease_export_mp3(
    app: AppHandle,
    state: NeteaseState<'_>,
    id: i64,
    level: Option<String>,
    out_dir: Option<String>,
    ffmpeg: Option<String>,
    overwrite: Option<bool>,
    name: Option<String>,
    open_folder: Option<bool>,
) -> Result<ExportedTrack, String> {
    export_track(
        &app,
        state.inner().as_ref(),
        ExportRequest {
            id,
            level,
            out_dir,
            ffmpeg,
            overwrite: overwrite.unwrap_or(false),
            name,
            open_folder: open_folder.unwrap_or(true),
        },
    )
    .await
    .map_err(user_facing_error)
}

struct ExportRequest {
    id: i64,
    level: Option<String>,
    out_dir: Option<String>,
    ffmpeg: Option<String>,
    overwrite: bool,
    name: Option<String>,
    open_folder: bool,
}

async fn export_track(
    app: &AppHandle,
    client: &NeteaseClient,
    req: ExportRequest,
) -> Result<ExportedTrack> {
    let level = req.level.as_deref().unwrap_or("exhigh");
    let item = client
        .song_urls(&[req.id], level)
        .await?
        .into_iter()
        .find(|item| item.id == req.id)
        .ok_or_else(|| anyhow!("网易云没有返回歌曲 {} 的播放地址", req.id))?;
    let raw_url = item
        .url
        .as_deref()
        .filter(|url| !url.is_empty())
        .ok_or_else(|| {
            anyhow!(
                "歌曲 {} 没有可播放地址，可能是权限不足或当前音质不可用",
                req.id
            )
        })?;

    let upstream = Url::parse(raw_url).context("解析音频地址失败")?;
    let upstream_resp = cdn_get(&upstream, None, None, None).await?;
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
    let out_dir = resolve_output_dir(app, req.out_dir.as_deref())?;
    fs::create_dir_all(&out_dir)
        .with_context(|| format!("创建输出目录失败：{}", out_dir.display()))?;
    let target = out_dir.join(format!("{stem}.mp3"));
    let bytes = upstream_resp.body.len() as i64;

    if ext == "mp3" {
        write_file_atomic(&target, &upstream_resp.body, req.overwrite)?;
        return build_result(req.id, target, None, ext, false, bytes, req.open_folder);
    }

    let source = std::env::temp_dir().join(format!(
        "pipo_export_{}_{}.{}",
        std::process::id(),
        req.id,
        ext
    ));
    write_file_atomic(&source, &upstream_resp.body, true)?;
    let ffmpeg = PathBuf::from(req.ffmpeg.unwrap_or_else(|| "ffmpeg".to_string()));
    let converted = convert_to_mp3(&ffmpeg, &source, &target, req.overwrite);
    let _ = fs::remove_file(&source);
    converted?;

    build_result(req.id, target, None, ext, true, bytes, req.open_folder)
}

fn build_result(
    id: i64,
    target: PathBuf,
    source_path: Option<PathBuf>,
    source_format: String,
    converted: bool,
    bytes: i64,
    open_folder: bool,
) -> Result<ExportedTrack> {
    let open_folder_error = if open_folder {
        reveal_in_file_manager(&target)
            .err()
            .map(|e| format!("{e:#}"))
    } else {
        None
    };
    let opened_folder = open_folder && open_folder_error.is_none();

    Ok(ExportedTrack {
        id,
        path: target.to_string_lossy().to_string(),
        source_path: source_path.map(|path| path.to_string_lossy().to_string()),
        source_format,
        converted,
        bytes,
        opened_folder,
        open_folder_error,
    })
}

fn resolve_output_dir(app: &AppHandle, out_dir: Option<&str>) -> Result<PathBuf> {
    if let Some(path) = out_dir.map(str::trim).filter(|path| !path.is_empty()) {
        return Ok(PathBuf::from(path));
    }

    let base = app
        .path()
        .audio_dir()
        .or_else(|_| app.path().download_dir())
        .or_else(|_| app.path().app_data_dir())
        .context("无法解析默认保存目录")?;
    Ok(base.join("Pipo Exports"))
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

fn ensure_parent(path: &Path) -> Result<()> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    Ok(())
}

fn existing_file_error(path: &Path) -> anyhow::Error {
    anyhow!(
        "文件已存在：{}。如需覆盖，请把覆盖选项改成“覆盖已有文件”。",
        path.display()
    )
}

fn track_file_stem(track: &crate::netease::models::TrackInfo) -> String {
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
        "pipo-track".to_string()
    } else {
        out
    }
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

fn user_facing_error(error: anyhow::Error) -> String {
    let message = format!("{error:#}");
    if message.contains("启动 ffmpeg 失败") {
        return format!(
            "{message}\n如果返回的音频不是 MP3，需要安装 ffmpeg，或填写 ffmpeg.exe 的完整路径。"
        );
    }
    message
}
