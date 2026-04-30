use anyhow::{anyhow, Context, Result};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::path::PathBuf;

#[path = "../../../../src-tauri/src/audio/analysis.rs"]
mod analysis;

const DEFAULT_MAX_BYTES: i64 = 2 * 1024 * 1024 * 1024;
const MIN_MAX_BYTES: i64 = 64 * 1024 * 1024;

#[derive(Debug, Clone)]
pub struct NativeAudioStore {
    root: PathBuf,
    audio_dir: PathBuf,
    feature_dir: PathBuf,
    config_path: PathBuf,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AudioConfig {
    max_bytes: i64,
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
        std::fs::create_dir_all(&audio_dir)
            .with_context(|| format!("create audio cache dir {}", audio_dir.display()))?;
        std::fs::create_dir_all(&feature_dir)
            .with_context(|| format!("create audio feature dir {}", feature_dir.display()))?;
        Ok(Self {
            config_path: root.join("audio-config.json"),
            root,
            audio_dir,
            feature_dir,
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

    pub async fn features_json(&self, track_id: i64, url: String, cache_bytes: bool) -> Result<Value> {
        if let Some(features) = self.read_features(track_id)? {
            return Ok(serde_json::to_value(features)?);
        }

        let path = self.ensure_audio_file(track_id, &url, cache_bytes).await?;
        let features = analysis::analyze_file(track_id, &path)
            .with_context(|| format!("analyze audio features for track {track_id}"))?;
        self.write_features(track_id, &features)?;
        Ok(serde_json::to_value(features)?)
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

    async fn ensure_audio_file(&self, track_id: i64, url: &str, cache_bytes: bool) -> Result<PathBuf> {
        if let Some(path) = self.cached_audio_path(track_id)? {
            return Ok(path);
        }
        if url.trim().is_empty() {
            return Err(anyhow!("missing audio url for track {track_id}"));
        }

        let response = reqwest::Client::new()
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
        let bytes = response.bytes().await.context("read audio response bytes")?;
        let ext = infer_ext_from_url(url)
            .or_else(|| content_type.as_deref().and_then(ext_from_content_type))
            .unwrap_or_else(|| "bin".to_string());
        let path = self.audio_dir.join(format!("{track_id}.{ext}"));

        if cache_bytes {
            let tmp = path.with_extension(format!("{ext}.tmp"));
            std::fs::write(&tmp, &bytes)
                .with_context(|| format!("write audio cache tmp {}", tmp.display()))?;
            std::fs::rename(&tmp, &path)
                .with_context(|| format!("rename audio cache {}", path.display()))?;
            self.evict_to_fit()?;
            Ok(path)
        } else {
            let tmp = self.audio_dir.join(format!("{track_id}.analysis.{ext}.tmp"));
            std::fs::write(&tmp, &bytes)
                .with_context(|| format!("write temp analysis audio {}", tmp.display()))?;
            Ok(tmp)
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
        let path = self.feature_dir.join(format!("{track_id}.json"));
        if !path.exists() {
            return Ok(None);
        }
        let raw = std::fs::read_to_string(path)?;
        Ok(Some(serde_json::from_str(&raw)?))
    }

    fn write_features(&self, track_id: i64, features: &analysis::Acoustics) -> Result<()> {
        let path = self.feature_dir.join(format!("{track_id}.json"));
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
}

pub fn zero_features(track_id: i64) -> Value {
    json!({
        "trackId": track_id,
        "durationS": 0.0,
        "bpm": null,
        "bpmConfidence": 0.0,
        "rmsDb": 0.0,
        "peakDb": 0.0,
        "dynamicRangeDb": 0.0,
        "introEnergy": 0.0,
        "outroEnergy": 0.0,
        "spectralCentroidHz": 0.0,
        "headSilenceS": 0.0,
        "tailSilenceS": 0.0
    })
}

fn infer_ext_from_url(url: &str) -> Option<String> {
    let path_part = url.split(['?', '#']).next().unwrap_or(url);
    let last = path_part.rsplit('/').next().unwrap_or("");
    let dot = last.rfind('.')?;
    let ext = last[dot + 1..].to_ascii_lowercase();
    matches!(ext.as_str(), "mp3" | "flac" | "m4a" | "aac" | "ogg" | "wav").then_some(ext)
}

fn ext_from_content_type(ct: &str) -> Option<String> {
    let main = ct.split(';').next().unwrap_or(ct).trim().to_ascii_lowercase();
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
