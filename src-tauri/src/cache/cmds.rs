//! Tauri commands —— 把缓存表的 CRUD 暴露给前端。
//!
//! 这些命令都是"纯缓存"，不 touch 网络；前端自己决定什么时候 read cache、
//! 什么时候调 netease_* 拉新、什么时候 save 回缓存。

use std::sync::Arc;

use tauri::State;

use super::db::{CachedPlaylist, CachedPlaylistDetail};
use super::CacheDb;
use crate::netease::models::{LyricData, PlaylistDetail, PlaylistInfo};

pub type CacheState<'a> = State<'a, Arc<CacheDb>>;

fn to_err<E: std::fmt::Display>(e: E) -> String {
    e.to_string()
}

// --------- playlists ---------

#[tauri::command]
pub fn cache_get_playlists(
    state: CacheState<'_>,
    uid: i64,
) -> Result<Vec<CachedPlaylist>, String> {
    state.get_playlists(uid).map_err(to_err)
}

#[tauri::command]
pub fn cache_save_playlists(
    state: CacheState<'_>,
    uid: i64,
    items: Vec<PlaylistInfo>,
) -> Result<(), String> {
    state.save_playlists(uid, &items).map_err(to_err)
}

// --------- playlist detail ---------

#[tauri::command]
pub fn cache_get_playlist_detail(
    state: CacheState<'_>,
    id: i64,
) -> Result<Option<CachedPlaylistDetail>, String> {
    state.get_playlist_detail(id).map_err(to_err)
}

#[tauri::command]
pub fn cache_save_playlist_detail(
    state: CacheState<'_>,
    uid: i64,
    detail: PlaylistDetail,
) -> Result<(), String> {
    state.save_playlist_detail(uid, &detail).map_err(to_err)
}

// --------- lyrics ---------

#[tauri::command]
pub fn cache_get_lyric(
    state: CacheState<'_>,
    track_id: i64,
) -> Result<Option<LyricData>, String> {
    state.get_lyric(track_id).map_err(to_err)
}

#[tauri::command]
pub fn cache_save_lyric(
    state: CacheState<'_>,
    track_id: i64,
    lyric: LyricData,
) -> Result<(), String> {
    state.save_lyric(track_id, &lyric).map_err(to_err)
}

// --------- app_state KV（上次播放位置等） ---------

#[tauri::command]
pub fn cache_get_state(
    state: CacheState<'_>,
    key: String,
) -> Result<Option<String>, String> {
    state.get_state(&key).map_err(to_err)
}

#[tauri::command]
pub fn cache_set_state(
    state: CacheState<'_>,
    key: String,
    value: String,
) -> Result<(), String> {
    state.set_state(&key, &value).map_err(to_err)
}
