use anyhow::{anyhow, Context, Result};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::path::PathBuf;
use std::time::Duration;
use tokio::io::AsyncWriteExt;

#[path = "../../../../src-tauri/src/audio/analysis.rs"]
mod analysis;
mod transition;

const DEFAULT_MAX_BYTES: i64 = 2 * 1024 * 1024 * 1024;
const MIN_MAX_BYTES: i64 = 64 * 1024 * 1024;
const FEATURE_CACHE_VERSION: &str = "v2";
const MAX_TRANSITION_CACHE_BYTES: i64 = 256 * 1024 * 1024;

#[derive(Debug, Clone)]
pub struct NativeAudioStore {
    root: PathBuf,
    audio_dir: PathBuf,
    feature_dir: PathBuf,
    transition_dir: PathBuf,
    config_path: PathBuf,
    client: reqwest::Client,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AudioConfig {
    max_bytes: i64,
}

struct AudioFile {
    path: PathBuf,
    temporary: bool,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AudioCacheStats {
    total_bytes: i64,
    count: i64,
    max_bytes: i64,
}

impl NativeAudioStore {
    pub fn new(root: PathBuf) -> Result<Self> {
        let root = root.join("native-audio");
        let audio_dir = root.join("audio-cache");
        let feature_dir = root.join("audio-features");
        let transition_dir = root.join("transition-cache");
        std::fs::create_dir_all(&audio_dir)
            .with_context(|| format!("create audio cache dir {}", audio_dir.display()))?;
        std::fs::create_dir_all(&feature_dir)
            .with_context(|| format!("create audio feature dir {}", feature_dir.display()))?;
        std::fs::create_dir_all(&transition_dir)
            .with_context(|| format!("create transition cache dir {}", transition_dir.display()))?;
        let client = reqwest::Client::builder()
            .connect_timeout(Duration::from_secs(10))
            .timeout(Duration::from_secs(45))
            .build()
            .context("build audio http client")?;
        Ok(Self {
            config_path: root.join("audio-config.json"),
            root,
            audio_dir,
            feature_dir,
            transition_dir,
            client,
        })
    }

    pub fn stats(&self) -> Result<AudioCacheStats> {
        let mut total_bytes = 0_i64;
        let mut count = 0_i64;
        for entry in std::fs::read_dir(&self.audio_dir)
            .with_context(|| format!("read audio cache dir {}", self.audio_dir.display()))?
        {
            let entry = entry?;
            let meta = match entry.metadata() {
                Ok(meta) if meta.is_file() => meta,
                _ => continue,
            };
            total_bytes += meta.len() as i64;
            count += 1;
        }
        Ok(AudioCacheStats {
            total_bytes,
            count,
            max_bytes: self.max_bytes(),
        })
    }

    pub fn set_max_mb(&self, mb: i64) -> Result<()> {
        let max_bytes = (mb * 1024 * 1024).max(MIN_MAX_BYTES);
        self.write_config(AudioConfig { max_bytes })?;
        self.evict_to_fit()?;
        Ok(())
    }

    pub fn clear_cache(&self) -> Result<()> {
        if let Ok(entries) = std::fs::read_dir(&self.audio_dir) {
            for entry in entries.flatten() {
                let path = entry.path();
                if path.is_file() {
                    let _ = std::fs::remove_file(path);
                }
            }
        }
        Ok(())
    }

    pub async fn features_json(
        &self,
        track_id: i64,
        url: String,
        cache_bytes: bool,
    ) -> Result<Value> {
        if let Some(features) = self.read_features(track_id)? {
            return Ok(serde_json::to_value(features)?);
        }

        let audio_file = self.ensure_audio_file(track_id, &url, cache_bytes).await?;
        let features_result = analysis::analyze_file(track_id, &audio_file.path)
            .with_context(|| format!("analyze audio features for track {track_id}"));
        if audio_file.temporary {
            let _ = std::fs::remove_file(&audio_file.path);
        }
        let features = features_result?;
        self.write_features(track_id, &features)?;
        Ok(serde_json::to_value(features)?)
    }

    pub async fn transition_clip_json(
        &self,
        current_track_id: i64,
        current_url: String,
        next_track_id: i64,
        next_url: String,
        current_duration_ms: i64,
        mix_ms: i64,
        next_start_position_ms: i64,
        next_tempo_scale: f32,
        current_gain: f32,
        next_gain: f32,
    ) -> Result<Value> {
        let current = self
            .ensure_audio_file(current_track_id, &current_url, true)
            .await?;
        let next = self
            .ensure_audio_file(next_track_id, &next_url, true)
            .await?;
        let safe_next_tempo_scale = next_tempo_scale.clamp(0.965, 1.035);
        let safe_current_gain = current_gain.clamp(0.05, 1.0);
        let safe_next_gain = next_gain.clamp(0.05, 1.0);
        // 文件名带上 gain 分量 —— 改了响度对齐后旧的无增益 clip 缓存不会被错误命中。
        let output_path = self.transition_dir.join(format!(
            "{}-{}-{}-{}-{}-{}-{}-{}.wav",
            current_track_id,
            next_track_id,
            current_duration_ms.max(0),
            mix_ms.max(0),
            next_start_position_ms.max(0),
            (safe_next_tempo_scale * 10_000.0).round() as i32,
            (safe_current_gain * 1_000.0).round() as i32,
            (safe_next_gain * 1_000.0).round() as i32,
        ));
        let clip = transition::build_transition_clip(transition::TransitionClipSpec {
            current_path: current.path,
            next_path: next.path,
            output_path,
            current_duration_ms,
            mix_ms,
            next_start_position_ms,
            next_tempo_scale: safe_next_tempo_scale,
            current_gain: safe_current_gain,
            next_gain: safe_next_gain,
        })?;
        self.evict_transition_cache()?;
        Ok(serde_json::to_value(clip)?)
    }

    fn max_bytes(&self) -> i64 {
        self.read_config()
            .map(|cfg| cfg.max_bytes.max(MIN_MAX_BYTES))
            .unwrap_or(DEFAULT_MAX_BYTES)
    }

    fn read_config(&self) -> Result<AudioConfig> {
        let raw = std::fs::read_to_string(&self.config_path)?;
        Ok(serde_json::from_str(&raw)?)
    }

    fn write_config(&self, cfg: AudioConfig) -> Result<()> {
        std::fs::create_dir_all(&self.root)?;
        std::fs::write(&self.config_path, serde_json::to_vec_pretty(&cfg)?)?;
        Ok(())
    }

    async fn ensure_audio_file(
        &self,
        track_id: i64,
        url: &str,
        cache_bytes: bool,
    ) -> Result<AudioFile> {
        if let Some(path) = self.cached_audio_path(track_id)? {
            return Ok(AudioFile {
                path,
                temporary: false,
            });
        }
        if url.trim().is_empty() {
            return Err(anyhow!("missing audio url for track {track_id}"));
        }

        let response = self
            .client
            .get(url)
            .send()
            .await
            .with_context(|| format!("download audio {url}"))?
            .error_for_status()
            .with_context(|| format!("download audio status {url}"))?;
        let content_type = response
            .headers()
            .get(reqwest::header::CONTENT_TYPE)
            .and_then(|v| v.to_str().ok())
            .map(str::to_string);
        let ext = infer_ext_from_url(url)
            .or_else(|| content_type.as_deref().and_then(ext_from_content_type))
            .unwrap_or_else(|| "bin".to_string());
        let path = self.audio_dir.join(format!("{track_id}.{ext}"));

        if cache_bytes {
            let tmp = path.with_extension(format!("{ext}.tmp"));
            write_response_to_file(response, &tmp).await?;
            tokio::fs::rename(&tmp, &path)
                .await
                .with_context(|| format!("rename audio cache {}", path.display()))?;
            self.evict_to_fit()?;
            Ok(AudioFile {
                path,
                temporary: false,
            })
        } else {
            let tmp = self
                .audio_dir
                .join(format!("{track_id}.analysis.{ext}.tmp"));
            write_response_to_file(response, &tmp).await?;
            Ok(AudioFile {
                path: tmp,
                temporary: true,
            })
        }
    }

    fn cached_audio_path(&self, track_id: i64) -> Result<Option<PathBuf>> {
        let prefix = format!("{track_id}.");
        for entry in std::fs::read_dir(&self.audio_dir)? {
            let entry = entry?;
            let path = entry.path();
            if !path.is_file() {
                continue;
            }
            let Some(name) = path.file_name().and_then(|v| v.to_str()) else {
                continue;
            };
            if name.starts_with(&prefix) && !name.ends_with(".tmp") {
                return Ok(Some(path));
            }
        }
        Ok(None)
    }

    fn read_features(&self, track_id: i64) -> Result<Option<analysis::Acoustics>> {
        let path = self
            .feature_dir
            .join(format!("{track_id}.{FEATURE_CACHE_VERSION}.json"));
        if !path.exists() {
            return Ok(None);
        }
        let raw = std::fs::read_to_string(path)?;
        Ok(Some(serde_json::from_str(&raw)?))
    }

    fn write_features(&self, track_id: i64, features: &analysis::Acoustics) -> Result<()> {
        let path = self
            .feature_dir
            .join(format!("{track_id}.{FEATURE_CACHE_VERSION}.json"));
        std::fs::write(path, serde_json::to_vec_pretty(features)?)?;
        Ok(())
    }

    fn evict_to_fit(&self) -> Result<()> {
        let max_bytes = self.max_bytes();
        let mut entries = Vec::new();
        for entry in std::fs::read_dir(&self.audio_dir)? {
            let entry = entry?;
            let meta = entry.metadata()?;
            if !meta.is_file() {
                continue;
            }
            entries.push((entry.path(), meta.len() as i64, meta.modified().ok()));
        }

        let mut total: i64 = entries.iter().map(|(_, bytes, _)| *bytes).sum();
        if total <= max_bytes {
            return Ok(());
        }

        entries.sort_by_key(|(_, _, modified)| *modified);
        for (path, bytes, _) in entries {
            if total <= max_bytes {
                break;
            }
            if std::fs::remove_file(&path).is_ok() {
                total -= bytes;
            }
        }
        Ok(())
    }

    fn evict_transition_cache(&self) -> Result<()> {
        let mut entries = Vec::new();
        for entry in std::fs::read_dir(&self.transition_dir)? {
            let entry = entry?;
            let meta = entry.metadata()?;
            if !meta.is_file() {
                continue;
            }
            entries.push((entry.path(), meta.len() as i64, meta.modified().ok()));
        }
        let mut total: i64 = entries.iter().map(|(_, bytes, _)| *bytes).sum();
        if total <= MAX_TRANSITION_CACHE_BYTES {
            return Ok(());
        }
        entries.sort_by_key(|(_, _, modified)| *modified);
        for (path, bytes, _) in entries {
            if total <= MAX_TRANSITION_CACHE_BYTES {
                break;
            }
            if std::fs::remove_file(path).is_ok() {
                total -= bytes;
            }
        }
        Ok(())
    }
}

async fn write_response_to_file(mut response: reqwest::Response, path: &PathBuf) -> Result<()> {
    let mut file = tokio::fs::File::create(path)
        .await
        .with_context(|| format!("create audio file {}", path.display()))?;
    while let Some(chunk) = response
        .chunk()
        .await
        .context("read audio response chunk")?
    {
        file.write_all(&chunk)
            .await
            .with_context(|| format!("write audio file {}", path.display()))?;
    }
    file.flush()
        .await
        .with_context(|| format!("flush audio file {}", path.display()))?;
    Ok(())
}

fn infer_ext_from_url(url: &str) -> Option<String> {
    let path_part = url.split(['?', '#']).next().unwrap_or(url);
    let last = path_part.rsplit('/').next().unwrap_or("");
    let dot = last.rfind('.')?;
    let ext = last[dot + 1..].to_ascii_lowercase();
    matches!(ext.as_str(), "mp3" | "flac" | "m4a" | "aac" | "ogg" | "wav").then_some(ext)
}

fn ext_from_content_type(ct: &str) -> Option<String> {
    let main = ct
        .split(';')
        .next()
        .unwrap_or(ct)
        .trim()
        .to_ascii_lowercase();
    match main.as_str() {
        "audio/mpeg" => Some("mp3".into()),
        "audio/flac" | "audio/x-flac" => Some("flac".into()),
        "audio/mp4" | "audio/m4a" | "audio/x-m4a" => Some("m4a".into()),
        "audio/aac" => Some("aac".into()),
        "audio/ogg" => Some("ogg".into()),
        "audio/wav" | "audio/x-wav" => Some("wav".into()),
        _ => None,
    }
}
