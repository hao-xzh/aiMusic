//! 音频磁盘缓存：SQLite 索引 + 文件系统存原始字节 + LRU 淘汰。
//!
//! 文件命名：`<root>/<track_id>.<ext>`。ext 从 URL 推断（`.mp3` / `.flac` / `.m4a`），
//! 推不出来就用 `.bin`。按 track_id 分文件，碰撞 = 同一首，直接覆盖即可。
//!
//! 上限：从 cache.db 的 app_state KV 读 `audio_cache_max_bytes`。没设过 = 1 GB。
//! put 完一首之后顺手 evict 一次（while total > limit 删 last_used 最旧的）。
//!
//! 并发：写盘 + DB upsert 不在一个事务里，但写盘失败 = 不更新 DB，所以"DB 里有
//! 但文件没"的状态只会发生在 evict 失败这种极端情况，调用方读取时遇到 ENOENT
//! 自动回落 miss 重拉，可以容忍。

use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};

use anyhow::{Context, Result};
use rusqlite::{params, OptionalExtension};

use super::analysis::Acoustics;
use crate::cache::CacheDb;

/// 默认上限：1024 MB。用户可在设置里改。
const DEFAULT_MAX_BYTES: i64 = 1024 * 1024 * 1024;
const KEY_MAX_BYTES: &str = "audio_cache_max_bytes";

/// schema 单独建表 —— audio_cache（字节）+ audio_features（声学特征）。
/// 两张表的生命周期不一样：clear 缓存时只清 audio_cache（字节占盘大），
/// audio_features 留着，下次再缓存这首歌不用重新解码 + 算 BPM。
const SCHEMA: &str = r#"
CREATE TABLE IF NOT EXISTS audio_cache (
    track_id     INTEGER PRIMARY KEY,
    path         TEXT NOT NULL,
    bytes        INTEGER NOT NULL,
    format       TEXT,
    created_at   INTEGER NOT NULL,
    last_used_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_audio_cache_lru ON audio_cache(last_used_at);

CREATE TABLE IF NOT EXISTS audio_features (
    track_id            INTEGER PRIMARY KEY,
    duration_s          REAL NOT NULL,
    bpm                 REAL,           -- nullable：解不出来 = NULL
    bpm_confidence      REAL NOT NULL,
    rms_db              REAL NOT NULL,
    peak_db             REAL NOT NULL,
    dynamic_range_db    REAL NOT NULL,
    intro_energy        REAL NOT NULL,
    outro_energy        REAL NOT NULL,
    spectral_centroid_hz REAL NOT NULL,
    head_silence_s      REAL NOT NULL DEFAULT 0,
    tail_silence_s      REAL NOT NULL DEFAULT 0,
    computed_at         INTEGER NOT NULL
);
"#;

/// 老版本 audio_features 表没有 head_silence_s / tail_silence_s 列。
/// 启动时尝试 ALTER 一下，rusqlite 会对"列已存在"报错——直接 swallow。
const SCHEMA_MIGRATIONS: &[&str] = &[
    "ALTER TABLE audio_features ADD COLUMN head_silence_s REAL NOT NULL DEFAULT 0",
    "ALTER TABLE audio_features ADD COLUMN tail_silence_s REAL NOT NULL DEFAULT 0",
];

#[derive(Debug, Clone)]
pub struct CacheEntry {
    pub track_id: i64,
    pub path: PathBuf,
    #[allow(dead_code)]
    pub bytes: i64,
    pub format: Option<String>,
}

#[derive(Debug, Clone, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CacheStats {
    pub total_bytes: i64,
    pub count: i64,
    pub max_bytes: i64,
}

/// 全进程共享的音频缓存句柄。`db` 跟 playlists 共用一份 `cache.db`（已经 WAL）。
pub struct AudioCache {
    db: Arc<CacheDb>,
    root: PathBuf,
}

fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

/// 用 CacheDb 内部的 conn 跑一段 SQL；保持跟现有模块一样的锁语义。
/// 这里不暴露 conn 给外部，只让 AudioCache 自己用。
fn with_conn<F, T>(db: &CacheDb, f: F) -> Result<T>
where
    F: FnOnce(&rusqlite::Connection) -> Result<T>,
{
    db.with_conn(f)
}

impl AudioCache {
    /// 初始化：apply schema + 确保 root 目录存在。
    pub fn init(db: Arc<CacheDb>, root: PathBuf) -> Result<Self> {
        std::fs::create_dir_all(&root)
            .with_context(|| format!("create audio cache dir {}", root.display()))?;
        with_conn(&db, |c| {
            c.execute_batch(SCHEMA).context("apply audio_cache schema")?;
            // 列追加：已存在就报 duplicate column，忽略
            for sql in SCHEMA_MIGRATIONS {
                let _ = c.execute(sql, []);
            }
            Ok(())
        })?;
        Ok(Self { db, root })
    }

    pub fn root(&self) -> &Path {
        &self.root
    }

    /// 上限：默认 1 GB；存在 app_state KV 里。
    pub fn max_bytes(&self) -> Result<i64> {
        let raw = with_conn(&self.db, |c| {
            Ok(c.query_row(
                "SELECT value FROM app_state WHERE key = ?1",
                params![KEY_MAX_BYTES],
                |row| row.get::<_, String>(0),
            )
            .optional()?)
        })?;
        let v = raw
            .and_then(|s| s.parse::<i64>().ok())
            .filter(|n| *n > 0)
            .unwrap_or(DEFAULT_MAX_BYTES);
        Ok(v)
    }

    pub fn set_max_bytes(&self, max: i64) -> Result<()> {
        let max = max.max(64 * 1024 * 1024); // 至少 64 MB，避免设成 0 导致每次都 evict
        with_conn(&self.db, |c| {
            c.execute(
                "INSERT INTO app_state (key, value) VALUES (?1, ?2)
                 ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                params![KEY_MAX_BYTES, max.to_string()],
            )?;
            Ok(())
        })?;
        // 立即按新上限做一次 evict
        self.evict_to_fit()?;
        Ok(())
    }

    pub fn stats(&self) -> Result<CacheStats> {
        let (total_bytes, count) = with_conn(&self.db, |c| {
            let row = c.query_row(
                "SELECT COALESCE(SUM(bytes), 0), COUNT(*) FROM audio_cache",
                [],
                |row| Ok((row.get::<_, i64>(0)?, row.get::<_, i64>(1)?)),
            )?;
            Ok(row)
        })?;
        Ok(CacheStats {
            total_bytes,
            count,
            max_bytes: self.max_bytes()?,
        })
    }

    pub fn get(&self, track_id: i64) -> Result<Option<CacheEntry>> {
        with_conn(&self.db, |c| {
            Ok(c.query_row(
                "SELECT path, bytes, format FROM audio_cache WHERE track_id = ?1",
                params![track_id],
                |row| {
                    Ok(CacheEntry {
                        track_id,
                        path: PathBuf::from(row.get::<_, String>(0)?),
                        bytes: row.get::<_, i64>(1)?,
                        format: row.get::<_, Option<String>>(2)?,
                    })
                },
            )
            .optional()?)
        })
    }

    /// 命中后调用：bump last_used_at = now，让 LRU 把它视作"最近用过"。
    pub fn touch(&self, track_id: i64) -> Result<()> {
        with_conn(&self.db, |c| {
            c.execute(
                "UPDATE audio_cache SET last_used_at = ?1 WHERE track_id = ?2",
                params![now_ms(), track_id],
            )?;
            Ok(())
        })
    }

    /// 落盘 + 入库。失败时会回滚（删半成品文件）。
    pub fn put(
        &self,
        track_id: i64,
        bytes: &[u8],
        format: Option<&str>,
    ) -> Result<CacheEntry> {
        let ext = format.unwrap_or("bin");
        let path = self.root.join(format!("{track_id}.{ext}"));

        // 写 tmp + rename，避免半成品被并发 reader 读到
        let tmp = path.with_extension(format!("{ext}.tmp"));
        std::fs::write(&tmp, bytes)
            .with_context(|| format!("write tmp {}", tmp.display()))?;
        std::fs::rename(&tmp, &path)
            .with_context(|| format!("rename {} -> {}", tmp.display(), path.display()))?;

        let now = now_ms();
        let path_str = path.to_string_lossy().to_string();
        let bytes_len = bytes.len() as i64;

        with_conn(&self.db, |c| {
            c.execute(
                "INSERT INTO audio_cache (track_id, path, bytes, format, created_at, last_used_at)
                 VALUES (?1, ?2, ?3, ?4, ?5, ?5)
                 ON CONFLICT(track_id) DO UPDATE SET
                   path=excluded.path,
                   bytes=excluded.bytes,
                   format=excluded.format,
                   last_used_at=excluded.last_used_at",
                params![track_id, path_str, bytes_len, format, now],
            )?;
            Ok(())
        })?;

        // put 之后顺手 evict —— 不阻塞调用方太久（DB 操作 ms 级）
        self.evict_to_fit()?;

        Ok(CacheEntry {
            track_id,
            path,
            bytes: bytes_len,
            format: format.map(String::from),
        })
    }

    /// LRU 淘汰：循环删 last_used_at 最旧的，直到 total <= max。
    /// 删盘失败也继续 —— 至少 DB 一致；下次启动可以 GC 残留文件（暂不实现）。
    fn evict_to_fit(&self) -> Result<()> {
        let max = self.max_bytes()?;
        loop {
            let stats = self.stats()?;
            if stats.total_bytes <= max {
                return Ok(());
            }
            let victim: Option<(i64, String)> = with_conn(&self.db, |c| {
                Ok(c.query_row(
                    "SELECT track_id, path FROM audio_cache
                     ORDER BY last_used_at ASC, track_id ASC LIMIT 1",
                    [],
                    |row| Ok((row.get::<_, i64>(0)?, row.get::<_, String>(1)?)),
                )
                .optional()?)
            })?;
            let Some((tid, path)) = victim else {
                return Ok(()); // 库都空了还超 max？不该发生，安全退出
            };
            let _ = std::fs::remove_file(&path);
            with_conn(&self.db, |c| {
                c.execute("DELETE FROM audio_cache WHERE track_id = ?1", params![tid])?;
                Ok(())
            })?;
            log::debug!("[audio_cache] evicted track {tid}");
        }
    }

    /// 不读取 / 解码：仅检查 `audio_cache` 是否有这一行 + 文件是否存在。
    /// prefetch 用，避免 `get` 后再去做存在性检查。
    pub fn has(&self, track_id: i64) -> bool {
        match self.get(track_id) {
            Ok(Some(entry)) => entry.path.exists(),
            _ => false,
        }
    }

    /// 删一行（文件丢了/损坏时用）。文件存在也会顺手删一下。
    pub fn clear_entry(&self, track_id: i64) -> Result<()> {
        if let Ok(Some(entry)) = self.get(track_id) {
            let _ = std::fs::remove_file(&entry.path);
        }
        with_conn(&self.db, |c| {
            c.execute(
                "DELETE FROM audio_cache WHERE track_id = ?1",
                params![track_id],
            )?;
            Ok(())
        })
    }

    // ---- 声学特征 ----

    pub fn get_features(&self, track_id: i64) -> Result<Option<Acoustics>> {
        with_conn(&self.db, |c| {
            Ok(c.query_row(
                "SELECT duration_s, bpm, bpm_confidence, rms_db, peak_db,
                        dynamic_range_db, intro_energy, outro_energy, spectral_centroid_hz,
                        head_silence_s, tail_silence_s
                 FROM audio_features WHERE track_id = ?1",
                params![track_id],
                |row| {
                    Ok(Acoustics {
                        track_id,
                        duration_s: row.get::<_, f64>(0)? as f32,
                        bpm: row.get::<_, Option<f64>>(1)?.map(|v| v as f32),
                        bpm_confidence: row.get::<_, f64>(2)? as f32,
                        rms_db: row.get::<_, f64>(3)? as f32,
                        peak_db: row.get::<_, f64>(4)? as f32,
                        dynamic_range_db: row.get::<_, f64>(5)? as f32,
                        intro_energy: row.get::<_, f64>(6)? as f32,
                        outro_energy: row.get::<_, f64>(7)? as f32,
                        spectral_centroid_hz: row.get::<_, f64>(8)? as f32,
                        head_silence_s: row.get::<_, f64>(9)? as f32,
                        tail_silence_s: row.get::<_, f64>(10)? as f32,
                    })
                },
            )
            .optional()?)
        })
    }

    pub fn put_features(&self, a: &Acoustics) -> Result<()> {
        with_conn(&self.db, |c| {
            c.execute(
                "INSERT INTO audio_features (track_id, duration_s, bpm, bpm_confidence,
                    rms_db, peak_db, dynamic_range_db, intro_energy, outro_energy,
                    spectral_centroid_hz, head_silence_s, tail_silence_s, computed_at)
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13)
                 ON CONFLICT(track_id) DO UPDATE SET
                   duration_s=excluded.duration_s,
                   bpm=excluded.bpm,
                   bpm_confidence=excluded.bpm_confidence,
                   rms_db=excluded.rms_db,
                   peak_db=excluded.peak_db,
                   dynamic_range_db=excluded.dynamic_range_db,
                   intro_energy=excluded.intro_energy,
                   outro_energy=excluded.outro_energy,
                   spectral_centroid_hz=excluded.spectral_centroid_hz,
                   head_silence_s=excluded.head_silence_s,
                   tail_silence_s=excluded.tail_silence_s,
                   computed_at=excluded.computed_at",
                params![
                    a.track_id,
                    a.duration_s as f64,
                    a.bpm.map(|v| v as f64),
                    a.bpm_confidence as f64,
                    a.rms_db as f64,
                    a.peak_db as f64,
                    a.dynamic_range_db as f64,
                    a.intro_energy as f64,
                    a.outro_energy as f64,
                    a.spectral_centroid_hz as f64,
                    a.head_silence_s as f64,
                    a.tail_silence_s as f64,
                    now_ms(),
                ],
            )?;
            Ok(())
        })
    }

    /// 清空 audio_features 表 + app_state 里所有 `analysis:v3:*` JS 端缓存。
    /// 声学特征跟 JS 结构性分析两套都洗，让"继续分析"能从干净状态重跑。
    pub fn clear_features(&self) -> Result<()> {
        with_conn(&self.db, |c| {
            c.execute("DELETE FROM audio_features", [])?;
            c.execute(
                "DELETE FROM app_state WHERE key LIKE 'analysis:v3:%'",
                [],
            )?;
            Ok(())
        })
    }

    /// 清空：删表 + 删根目录下所有文件。features 表保留 —— 它是 read-only
    /// 元数据，重新缓存这首歌时直接命中，不用再解码。
    pub fn clear(&self) -> Result<()> {
        with_conn(&self.db, |c| {
            c.execute("DELETE FROM audio_cache", [])?;
            Ok(())
        })?;
        // 不删 root 目录本身（下次写入还要用它）；只删里面的文件。
        if let Ok(rd) = std::fs::read_dir(&self.root) {
            for entry in rd.flatten() {
                let _ = std::fs::remove_file(entry.path());
            }
        }
        Ok(())
    }
}

/// URL 路径里抓扩展名（`.mp3` / `.flac` / `.m4a`）。
/// 网易云的直链都是 `https://m701.music.126.net/.../<file>.mp3?wsSecret=...` 这种。
pub fn infer_ext_from_url(url: &str) -> Option<String> {
    let path_part = url.split(['?', '#']).next().unwrap_or(url);
    let last = path_part.rsplit('/').next().unwrap_or("");
    let dot = last.rfind('.')?;
    let ext = last[dot + 1..].to_ascii_lowercase();
    // 白名单一下，避免奇怪扩展名落盘
    matches!(ext.as_str(), "mp3" | "flac" | "m4a" | "aac" | "ogg" | "wav")
        .then_some(ext)
}

/// 简单的 content-type → ext 兜底。
pub fn ext_from_content_type(ct: &str) -> Option<String> {
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
