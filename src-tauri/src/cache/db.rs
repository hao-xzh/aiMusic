//! SQLite 读写层 —— schema、连接、所有 CRUD。
//!
//! 并发：单 `Mutex<Connection>`。单用户桌面 app，无争用。SQL 调用放在 Tauri command
//! 里跑（本身就在 tokio task 上），每次持锁时间 <10ms，不会阻塞 UI。

use std::path::Path;
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};

use anyhow::{Context, Result};
use rusqlite::{params, Connection, OptionalExtension};
use serde::{Deserialize, Serialize};

use crate::netease::models::{AlbumShort, ArtistShort, LyricData, PlaylistDetail, PlaylistInfo, TrackInfo};

// --------- schema ---------

const SCHEMA_V1: &str = r#"
CREATE TABLE IF NOT EXISTS playlists (
    id          INTEGER PRIMARY KEY,
    uid         INTEGER NOT NULL,
    name        TEXT NOT NULL,
    cover_url   TEXT,
    track_count INTEGER NOT NULL,
    update_time INTEGER,
    synced_at   INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_playlists_uid ON playlists(uid);

CREATE TABLE IF NOT EXISTS tracks (
    id           INTEGER PRIMARY KEY,
    name         TEXT NOT NULL,
    duration_ms  INTEGER NOT NULL,
    album_id     INTEGER,
    album_name   TEXT,
    album_cover  TEXT,
    artists_json TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS playlist_tracks (
    playlist_id INTEGER NOT NULL,
    position    INTEGER NOT NULL,
    track_id    INTEGER NOT NULL,
    PRIMARY KEY (playlist_id, position)
);
CREATE INDEX IF NOT EXISTS idx_playlist_tracks_track_id ON playlist_tracks(track_id);

CREATE TABLE IF NOT EXISTS lyrics (
    track_id     INTEGER PRIMARY KEY,
    lyric        TEXT,
    translation  TEXT,
    yrc          TEXT,           -- 逐字 yrc 原文（karaoke）
    instrumental INTEGER NOT NULL DEFAULT 0,
    uncollected  INTEGER NOT NULL DEFAULT 0,
    synced_at    INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS app_state (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
"#;

fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

// --------- cache view types（前端直接消费，形状和 netease 原 types 对齐） ---------

/// 带 `syncedAt` 的 playlist —— 前端可以提示"上次同步 3 分钟前"。
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CachedPlaylist {
    pub id: i64,
    pub name: String,
    pub cover_img_url: Option<String>,
    pub track_count: i64,
    pub update_time: Option<i64>,
    pub synced_at: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CachedPlaylistDetail {
    pub id: i64,
    pub name: String,
    pub cover_img_url: Option<String>,
    pub track_count: i64,
    pub update_time: Option<i64>,
    pub tracks: Vec<TrackInfo>,
    pub synced_at: i64,
}

// --------- DB 封装 ---------

pub struct CacheDb {
    conn: Mutex<Connection>,
}

impl CacheDb {
    pub fn open(path: &Path) -> Result<Self> {
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent).ok();
        }
        let conn = Connection::open(path)
            .with_context(|| format!("open cache db: {}", path.display()))?;
        // WAL：降低写入时的读阻塞；NORMAL：牺牲一点 crash safety 换写吞吐。
        conn.execute_batch("PRAGMA journal_mode=WAL; PRAGMA synchronous=NORMAL;")?;
        conn.execute_batch(SCHEMA_V1)
            .context("apply schema v1")?;
        // 老 lyrics 表没 yrc 列；ALTER 已存在就报错忽略
        let _ = conn.execute("ALTER TABLE lyrics ADD COLUMN yrc TEXT", []);
        Ok(Self {
            conn: Mutex::new(conn),
        })
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, Connection> {
        self.conn.lock().expect("cache db poisoned")
    }

    /// 让 crate 内别的模块（比如 `audio::cache`）借这一份连接 + 锁，
    /// 避免再开第二个 Connection。返回值生命周期跟 closure 绑定。
    pub fn with_conn<F, T>(&self, f: F) -> anyhow::Result<T>
    where
        F: FnOnce(&Connection) -> anyhow::Result<T>,
    {
        let conn = self.lock();
        f(&conn)
    }

    // ----- playlists -----

    pub fn get_playlists(&self, uid: i64) -> Result<Vec<CachedPlaylist>> {
        let conn = self.lock();
        let mut stmt = conn.prepare(
            "SELECT id, name, cover_url, track_count, update_time, synced_at
             FROM playlists WHERE uid = ?1
             ORDER BY COALESCE(update_time, 0) DESC, id DESC",
        )?;
        let rows = stmt.query_map(params![uid], |row| {
            Ok(CachedPlaylist {
                id: row.get(0)?,
                name: row.get(1)?,
                cover_img_url: row.get(2)?,
                track_count: row.get(3)?,
                update_time: row.get(4)?,
                synced_at: row.get(5)?,
            })
        })?;
        Ok(rows.collect::<Result<Vec<_>, _>>()?)
    }

    /// 用最新的 netease user/playlist 响应同步 playlists 表。
    /// - upsert 所有传入的 playlists
    /// - 删除本地有但远端没有的（即"用户删了这个歌单"）
    pub fn save_playlists(&self, uid: i64, items: &[PlaylistInfo]) -> Result<()> {
        let mut conn = self.lock();
        let tx = conn.transaction()?;
        let synced_at = now_ms();

        // 先记下来传入的 id 集合，后面清理用
        let ids: std::collections::HashSet<i64> = items.iter().map(|p| p.id).collect();

        {
            let mut upsert = tx.prepare(
                "INSERT INTO playlists (id, uid, name, cover_url, track_count, update_time, synced_at)
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)
                 ON CONFLICT(id) DO UPDATE SET
                   uid=excluded.uid,
                   name=excluded.name,
                   cover_url=excluded.cover_url,
                   track_count=excluded.track_count,
                   update_time=excluded.update_time,
                   synced_at=excluded.synced_at",
            )?;
            for p in items {
                upsert.execute(params![
                    p.id,
                    uid,
                    p.name,
                    p.cover_img_url,
                    p.track_count,
                    p.update_time,
                    synced_at,
                ])?;
            }
        }

        // 清理该 uid 下不在本次响应里的 playlist（连带 playlist_tracks）
        {
            let mut stmt = tx.prepare("SELECT id FROM playlists WHERE uid = ?1")?;
            let existing: Vec<i64> = stmt
                .query_map(params![uid], |row| row.get::<_, i64>(0))?
                .collect::<Result<Vec<_>, _>>()?;
            for id in existing {
                if !ids.contains(&id) {
                    tx.execute("DELETE FROM playlist_tracks WHERE playlist_id = ?1", params![id])?;
                    tx.execute("DELETE FROM playlists WHERE id = ?1", params![id])?;
                }
            }
        }

        tx.commit()?;
        Ok(())
    }

    // ----- playlist detail + tracks -----

    pub fn get_playlist_detail(&self, id: i64) -> Result<Option<CachedPlaylistDetail>> {
        let conn = self.lock();
        let header: Option<(String, Option<String>, i64, Option<i64>, i64)> = conn
            .query_row(
                "SELECT name, cover_url, track_count, update_time, synced_at
                 FROM playlists WHERE id = ?1",
                params![id],
                |row| {
                    Ok((
                        row.get::<_, String>(0)?,
                        row.get::<_, Option<String>>(1)?,
                        row.get::<_, i64>(2)?,
                        row.get::<_, Option<i64>>(3)?,
                        row.get::<_, i64>(4)?,
                    ))
                },
            )
            .optional()?;
        let Some((name, cover_url, track_count, update_time, synced_at)) = header else {
            return Ok(None);
        };

        let mut stmt = conn.prepare(
            "SELECT t.id, t.name, t.duration_ms, t.album_id, t.album_name, t.album_cover, t.artists_json
             FROM playlist_tracks pt
             JOIN tracks t ON t.id = pt.track_id
             WHERE pt.playlist_id = ?1
             ORDER BY pt.position ASC",
        )?;
        let tracks = stmt
            .query_map(params![id], |row| {
                let artists_json: String = row.get(6)?;
                let artists: Vec<ArtistShort> = serde_json::from_str(&artists_json)
                    .unwrap_or_default();
                let album_id: Option<i64> = row.get(3)?;
                let album_name: Option<String> = row.get(4)?;
                let album_cover: Option<String> = row.get(5)?;
                let album = album_id.map(|aid| AlbumShort {
                    id: aid,
                    name: album_name.unwrap_or_default(),
                    pic_url: album_cover,
                });
                Ok(TrackInfo {
                    id: row.get(0)?,
                    name: row.get(1)?,
                    duration_ms: row.get(2)?,
                    artists,
                    album,
                })
            })?
            .collect::<Result<Vec<_>, _>>()?;

        // 如果有 header 但 tracks 是空 —— 说明只存了 header 没存详情（比如只从 user/playlist 同步过）。
        // 让调用方判：Option::Some 里 tracks.is_empty() = 需要拉详情。
        Ok(Some(CachedPlaylistDetail {
            id,
            name,
            cover_img_url: cover_url,
            track_count,
            update_time,
            tracks,
            synced_at,
        }))
    }

    /// 存/更新整张歌单详情。
    /// 步骤：
    ///   1. upsert playlists 表（用 detail 带来的 updateTime / cover / trackCount）
    ///   2. upsert 每首 tracks 到 tracks 表
    ///   3. 把 playlist_tracks 里属于这个 playlist 的关系**全部替换**
    pub fn save_playlist_detail(&self, uid: i64, detail: &PlaylistDetail) -> Result<()> {
        let mut conn = self.lock();
        let tx = conn.transaction()?;
        let synced_at = now_ms();

        // 1) playlist header
        tx.execute(
            "INSERT INTO playlists (id, uid, name, cover_url, track_count, update_time, synced_at)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)
             ON CONFLICT(id) DO UPDATE SET
               uid=excluded.uid,
               name=excluded.name,
               cover_url=excluded.cover_url,
               track_count=excluded.track_count,
               update_time=excluded.update_time,
               synced_at=excluded.synced_at",
            params![
                detail.id,
                uid,
                detail.name,
                detail.cover_img_url,
                detail.track_count,
                detail.update_time,
                synced_at,
            ],
        )?;

        // 2) tracks
        {
            let mut upsert = tx.prepare(
                "INSERT INTO tracks (id, name, duration_ms, album_id, album_name, album_cover, artists_json)
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)
                 ON CONFLICT(id) DO UPDATE SET
                   name=excluded.name,
                   duration_ms=excluded.duration_ms,
                   album_id=excluded.album_id,
                   album_name=excluded.album_name,
                   album_cover=excluded.album_cover,
                   artists_json=excluded.artists_json",
            )?;
            for t in &detail.tracks {
                let artists_json = serde_json::to_string(&t.artists)
                    .unwrap_or_else(|_| "[]".into());
                let (album_id, album_name, album_cover) = match &t.album {
                    Some(a) => (Some(a.id), Some(a.name.clone()), a.pic_url.clone()),
                    None => (None, None, None),
                };
                upsert.execute(params![
                    t.id,
                    t.name,
                    t.duration_ms,
                    album_id,
                    album_name,
                    album_cover,
                    artists_json,
                ])?;
            }
        }

        // 3) 全量替换 playlist_tracks
        tx.execute(
            "DELETE FROM playlist_tracks WHERE playlist_id = ?1",
            params![detail.id],
        )?;
        {
            let mut insert = tx.prepare(
                "INSERT INTO playlist_tracks (playlist_id, position, track_id)
                 VALUES (?1, ?2, ?3)",
            )?;
            for (pos, t) in detail.tracks.iter().enumerate() {
                insert.execute(params![detail.id, pos as i64, t.id])?;
            }
        }

        tx.commit()?;
        Ok(())
    }

    // ----- lyrics -----

    pub fn get_lyric(&self, track_id: i64) -> Result<Option<LyricData>> {
        let conn = self.lock();
        // yrc 是后加的列，老库可能没有这列。读不到就是 None，跟"没收录"等价。
        let row: Option<(Option<String>, Option<String>, Option<String>, i64, i64)> = conn
            .query_row(
                "SELECT lyric, translation, yrc, instrumental, uncollected
                 FROM lyrics WHERE track_id = ?1",
                params![track_id],
                |row| {
                    Ok((
                        row.get::<_, Option<String>>(0)?,
                        row.get::<_, Option<String>>(1)?,
                        // yrc 列可能不存在（老 schema），fallback to None
                        row.get::<_, Option<String>>(2).unwrap_or(None),
                        row.get::<_, i64>(3)?,
                        row.get::<_, i64>(4)?,
                    ))
                },
            )
            .optional()?;
        Ok(row.map(|(lyric, translation, yrc, instrumental, uncollected)| LyricData {
            lyric,
            translation,
            yrc,
            instrumental: instrumental != 0,
            uncollected: uncollected != 0,
        }))
    }

    pub fn save_lyric(&self, track_id: i64, ly: &LyricData) -> Result<()> {
        let conn = self.lock();
        conn.execute(
            "INSERT INTO lyrics (track_id, lyric, translation, yrc, instrumental, uncollected, synced_at)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)
             ON CONFLICT(track_id) DO UPDATE SET
               lyric=excluded.lyric,
               translation=excluded.translation,
               yrc=excluded.yrc,
               instrumental=excluded.instrumental,
               uncollected=excluded.uncollected,
               synced_at=excluded.synced_at",
            params![
                track_id,
                ly.lyric,
                ly.translation,
                ly.yrc,
                ly.instrumental as i64,
                ly.uncollected as i64,
                now_ms(),
            ],
        )?;
        Ok(())
    }

    // ----- app_state (简单 KV) -----

    pub fn get_state(&self, key: &str) -> Result<Option<String>> {
        let conn = self.lock();
        Ok(conn
            .query_row(
                "SELECT value FROM app_state WHERE key = ?1",
                params![key],
                |row| row.get::<_, String>(0),
            )
            .optional()?)
    }

    pub fn set_state(&self, key: &str, value: &str) -> Result<()> {
        let conn = self.lock();
        conn.execute(
            "INSERT INTO app_state (key, value) VALUES (?1, ?2)
             ON CONFLICT(key) DO UPDATE SET value=excluded.value",
            params![key, value],
        )?;
        Ok(())
    }
}
