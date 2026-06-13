//! 网易云 weapi 响应的强类型 shape（只解构我们真正消费的字段）。
//!
//! 返回里常有 camelCase / 奇怪缩写（pc/ar/al），前端拿不到 snake_case 字段名没关系 ——
//! 我们这里重新塑形成 claudio 视角的 `TrackInfo`, `PlaylistInfo` 等等，再给前端。

use serde::{Deserialize, Deserializer, Serialize};
use serde_json::Value;
use std::collections::{HashMap, HashSet};

//
// --- 写接口通用响应 ---
//

#[derive(Debug, Deserialize)]
pub struct SimpleCodeResp {
    pub code: i32,
    #[serde(default)]
    pub message: Option<String>,
}

//
// --- 二维码登录 ---
//

#[derive(Debug, Deserialize)]
pub struct QrUnikeyResp {
    pub code: i32,
    #[serde(default)]
    pub unikey: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct QrCheckResp {
    pub code: i32,
    #[serde(default)]
    pub message: Option<String>,
    /// 803 时可能带昵称
    #[serde(default, rename = "nickname")]
    pub nickname: Option<String>,
}

//
// --- 手机号验证码登录 ---
//

#[derive(Debug, Deserialize)]
pub struct CaptchaResp {
    pub code: i32,
    #[serde(default)]
    pub message: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct CellphoneLoginResp {
    pub code: i32,
    #[serde(default)]
    pub message: Option<String>,
    /// 200 时返回，复用 UserProfile shape（部分字段缺失也能解析）
    #[serde(default)]
    pub profile: Option<UserProfile>,
}

//
// --- 用户 / 账号 ---
//

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct UserProfile {
    #[serde(rename = "userId")]
    pub user_id: i64,
    pub nickname: String,
    #[serde(default, rename = "avatarUrl")]
    pub avatar_url: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct AccountGetResp {
    pub code: i32,
    #[serde(default)]
    pub profile: Option<UserProfile>,
}

//
// --- 用户歌单列表 ---
//

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct PlaylistInfo {
    pub id: i64,
    pub name: String,
    #[serde(default, rename = "trackCount")]
    pub track_count: i64,
    #[serde(default, rename = "coverImgUrl")]
    pub cover_img_url: Option<String>,
    #[serde(default, rename = "userId")]
    pub user_id: Option<i64>,
    /// weapi `updateTime`（ms），增量同步的关键字段。
    /// 对比本地缓存 update_time：没变就跳过 playlist_detail 拉取。
    #[serde(default, rename = "updateTime")]
    pub update_time: Option<i64>,
}

#[derive(Debug, Deserialize)]
pub struct UserPlaylistsResp {
    pub code: i32,
    #[serde(default)]
    pub playlist: Vec<PlaylistInfo>,
}

//
// --- 歌单详情 + 歌曲 ---
//

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct ArtistShort {
    // id/name 给 default —— 网易云某些用户自建/翻唱条目的 ar/al 是空的或缺字段，
    // 之前强类型直接 panic 整张歌单挂掉。容忍它们，前端展示时拿到 0/"未知"也不会崩。
    #[serde(default)]
    pub id: i64,
    #[serde(default = "unknown_artist")]
    pub name: String,
}

fn unknown_artist() -> String {
    "未知艺人".into()
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct AlbumShort {
    #[serde(default)]
    pub id: i64,
    #[serde(default = "unknown_album")]
    pub name: String,
    #[serde(default, rename = "picUrl")]
    pub pic_url: Option<String>,
}

fn unknown_album() -> String {
    "未知专辑".into()
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct TrackInfo {
    pub id: i64,
    #[serde(default = "unknown_track_name")]
    pub name: String,
    /// weapi 原生字段：`dt`（毫秒时长）/ `ar`（艺人）/ `al`（专辑）。
    /// 为了前端可读我们对外序列化成 `durationMs / artists / album`，
    /// 但 `alias = "dt/ar/al"` 让反序列化同时吃 weapi 原名和 claudio 重命名 ——
    /// 这样 `cache_save_playlist_detail` 把前端传回来的 `TrackInfo` 也能正确解回来。
    /// 用户上传到云盘的曲目这里有时是 `null`，所以走容错解析当 0 处理，不然整条 track
    /// 解析失败、整个云盘条目被跳过。
    #[serde(
        default,
        rename = "durationMs",
        alias = "dt",
        deserialize_with = "deserialize_i64_lenient"
    )]
    pub duration_ms: i64,
    #[serde(default, rename = "artists", alias = "ar")]
    pub artists: Vec<ArtistShort>,
    #[serde(default, rename = "album", alias = "al")]
    pub album: Option<AlbumShort>,
}

fn deserialize_i64_lenient<'de, D>(d: D) -> Result<i64, D::Error>
where
    D: Deserializer<'de>,
{
    let v = Option::<Value>::deserialize(d)?;
    Ok(match v {
        Some(Value::Number(n)) => n.as_i64().unwrap_or(0),
        Some(Value::String(s)) => s.parse().unwrap_or(0),
        _ => 0,
    })
}

impl TrackInfo {
    pub fn by_id(tracks: &[TrackInfo]) -> HashMap<i64, TrackInfo> {
        tracks
            .iter()
            .cloned()
            .map(|track| (track.id, track))
            .collect()
    }
}

fn unknown_track_name() -> String {
    "未知曲目".into()
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct PlaylistDetail {
    pub id: i64,
    pub name: String,
    #[serde(default, rename = "trackCount")]
    pub track_count: i64,
    #[serde(default, rename = "coverImgUrl")]
    pub cover_img_url: Option<String>,
    #[serde(default, rename = "updateTime")]
    pub update_time: Option<i64>,
    // tracks 用宽松反序列化：null / 缺字段 → 空 vec；
    // 单条 track 解析失败时跳过那一条，不让整张歌单挂掉。
    #[serde(default, deserialize_with = "deserialize_tracks_lenient")]
    pub tracks: Vec<TrackInfo>,
    // 大歌单的 tracks 可能被接口按 n 截断，完整顺序在 trackIds 里。
    #[serde(
        default,
        rename = "trackIds",
        deserialize_with = "deserialize_track_ids_lenient"
    )]
    pub track_ids: Vec<i64>,
    #[serde(default, rename = "hydrationDiagnostics")]
    pub hydration_diagnostics: PlaylistHydrationDiagnostics,
}

#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PlaylistHydrationDiagnostics {
    pub playlist_detail_source: String,
    pub playlist_detail_fallback_error: String,
    pub original_tracks_count: usize,
    pub track_ids_count: usize,
    pub song_detail_requested: usize,
    pub song_detail_resolved: usize,
    pub cloud_by_ids_requested: usize,
    pub cloud_by_ids_returned: usize,
    pub cloud_by_ids_resolved: usize,
    pub cloud_by_ids_error: String,
    pub cloud_scan_requested: usize,
    pub cloud_scan_pages: usize,
    pub cloud_scan_returned: usize,
    pub cloud_scan_resolved: usize,
    pub cloud_scan_error: String,
    pub final_tracks_count: usize,
    pub missing_after_hydration: usize,
    pub missing_id_sample: Vec<i64>,
}

/// "宽松反序列化"：先吃成 Value 数组，再逐个尝试转 TrackInfo，
/// 失败的丢掉并打日志，成功的留下来 —— 避免一首畸形 track 让整张歌单 500。
fn deserialize_tracks_lenient<'de, D>(d: D) -> Result<Vec<TrackInfo>, D::Error>
where
    D: Deserializer<'de>,
{
    let v = Option::<Value>::deserialize(d)?;
    let arr = match v {
        Some(Value::Array(a)) => a,
        // null / 不是数组 → 空 vec
        _ => return Ok(Vec::new()),
    };
    let mut out = Vec::with_capacity(arr.len());
    for item in arr {
        match serde_json::from_value::<TrackInfo>(item) {
            Ok(t) => out.push(t),
            Err(e) => {
                eprintln!("[netease] skip malformed track: {e}");
            }
        }
    }
    Ok(out)
}

fn deserialize_track_ids_lenient<'de, D>(d: D) -> Result<Vec<i64>, D::Error>
where
    D: Deserializer<'de>,
{
    let v = Option::<Value>::deserialize(d)?;
    let arr = match v {
        Some(Value::Array(a)) => a,
        _ => return Ok(Vec::new()),
    };
    let mut out = Vec::with_capacity(arr.len());
    for item in arr {
        match item {
            Value::Number(n) => {
                if let Some(id) = n.as_i64() {
                    out.push(id);
                }
            }
            Value::Object(o) => {
                let mut picked_id = None;
                for key in ["id", "songId", "matchedSongId"] {
                    if let Some(id) = o.get(key).and_then(Value::as_i64) {
                        picked_id = Some(id);
                        break;
                    }
                }
                if picked_id.is_none() {
                    picked_id = o
                        .get("simpleSong")
                        .and_then(Value::as_object)
                        .and_then(|song| song.get("id"))
                        .and_then(Value::as_i64);
                }
                if picked_id.is_none() {
                    picked_id = o
                        .get("pc")
                        .and_then(Value::as_object)
                        .and_then(|pc| pc.get("songId").or_else(|| pc.get("id")))
                        .and_then(Value::as_i64);
                }
                if let Some(id) = picked_id {
                    out.push(id);
                }
            }
            _ => {}
        }
    }
    Ok(out)
}

#[derive(Debug, Deserialize)]
pub struct PlaylistDetailResp {
    pub code: i32,
    pub playlist: PlaylistDetail,
}

#[derive(Debug, Deserialize)]
pub struct SongDetailResp {
    pub code: i32,
    #[serde(default)]
    pub songs: Vec<TrackInfo>,
}

//
// --- 用户云盘 ---
//
// 网易云的云盘接口有两套 shape：
//   - `v1/cloud/get/byids`: data[].simpleSong 是匹配到的官方曲目；纯用户上传无匹配时
//     依然返回 simpleSong（但里面字段会缺、id 可能是 null）。
//   - `v1/cloud/get`:       分页扫整个云盘，data[].privateCloud 是用户上传的元数据
//     （songId 才是 NeteaseCloud 给的歌曲 id），通常还伴随 simpleSong。
// 历史上我们只解 simpleSong + camelCase 顶层字段，遇到上述任一种 shape 异常都会
// 直接 serde 失败 → 整张歌单 hydration 静默掉 N 首云盘歌。
// 这里把 `code/data` 都做成容错解析，并新增 privateCloud 兜底字段。

#[derive(Debug, Deserialize)]
pub struct UserCloudResp {
    /// 部分接口返回里 `code` 可能缺失或是 null；不要因此拒掉整个响应。
    #[serde(default)]
    pub code: Option<i32>,
    #[serde(default, deserialize_with = "deserialize_cloud_tracks_lenient")]
    pub data: Vec<UserCloudTrack>,
    #[serde(default)]
    pub count: Option<i64>,
    #[serde(default, rename = "hasMore")]
    pub has_more: bool,
}

impl UserCloudResp {
    pub fn code_or_ok(&self) -> i32 {
        self.code.unwrap_or(200)
    }
}

/// data 里允许个别条目脏掉，跳过即可——一颗坏苹果不能拖垮整页。
fn deserialize_cloud_tracks_lenient<'de, D>(d: D) -> Result<Vec<UserCloudTrack>, D::Error>
where
    D: Deserializer<'de>,
{
    let v = Option::<Value>::deserialize(d)?;
    let arr = match v {
        Some(Value::Array(a)) => a,
        _ => return Ok(Vec::new()),
    };
    let mut out = Vec::with_capacity(arr.len());
    for item in arr {
        match serde_json::from_value::<UserCloudTrack>(item) {
            Ok(t) => out.push(t),
            Err(e) => eprintln!("[netease] skip malformed cloud track: {e}"),
        }
    }
    Ok(out)
}

#[derive(Debug, Deserialize)]
pub struct UserCloudTrack {
    #[serde(default)]
    pub id: Option<i64>,
    #[serde(default, rename = "songId")]
    pub song_id: Option<i64>,
    #[serde(default, rename = "fileId")]
    pub file_id: Option<i64>,
    /// simpleSong 里 `id` 可能为 null（纯用户上传 + 没对上库），所以走 lenient 解析。
    #[serde(
        default,
        rename = "simpleSong",
        deserialize_with = "deserialize_optional_track"
    )]
    pub simple_song: Option<TrackInfo>,
    /// `v1/cloud/get` 返回的纯网盘元数据 —— 这里 `songId` 才是真正的 NetEase 曲目 id。
    #[serde(default, rename = "privateCloud")]
    pub private_cloud: Option<PrivateCloudInfo>,
    #[serde(default, rename = "songName")]
    pub song_name: Option<String>,
    #[serde(default)]
    pub artist: Option<String>,
    #[serde(default)]
    pub album: Option<String>,
    #[serde(default, rename = "fileName")]
    pub file_name: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct PrivateCloudInfo {
    #[serde(default, rename = "songId")]
    pub song_id: Option<i64>,
    #[serde(default)]
    pub song: Option<String>,
    #[serde(default)]
    pub artist: Option<String>,
    #[serde(default)]
    pub album: Option<String>,
    #[serde(default, rename = "fileName")]
    pub file_name: Option<String>,
}

/// simpleSong 解析失败（最常见：`id: null`）就当 None 处理，不要废掉整个 UserCloudTrack —
/// 我们还能从 privateCloud / songId 等兄弟字段把这条云盘歌补齐。
fn deserialize_optional_track<'de, D>(d: D) -> Result<Option<TrackInfo>, D::Error>
where
    D: Deserializer<'de>,
{
    let v = Option::<Value>::deserialize(d)?;
    match v {
        None | Some(Value::Null) => Ok(None),
        Some(value) => match serde_json::from_value::<TrackInfo>(value) {
            Ok(track) => Ok(Some(track)),
            Err(e) => {
                eprintln!("[netease] skip malformed simpleSong: {e}");
                Ok(None)
            }
        },
    }
}

impl UserCloudTrack {
    /// 云盘"列表"用：每条 cloud entry 只产出一个 (id, TrackInfo)。
    /// 优先级：simpleSong.id > privateCloud.songId > songId > id > fileId。
    /// hydration 那条路径要用 [`Self::into_track_candidates`] 拿到所有可能的 id（
    /// 因为 NetEase 一条云盘记录在歌单 trackIds 里可能出现多种 id 形式）。
    pub fn into_primary_track(self) -> Option<(i64, TrackInfo)> {
        let private_id = self.private_cloud.as_ref().and_then(|p| p.song_id);
        let simple_id = self.simple_song.as_ref().map(|t| t.id);
        let primary_id = simple_id
            .or(private_id)
            .or(self.song_id)
            .or(self.id)
            .or(self.file_id)
            .filter(|id| *id != 0)?;

        let base = if let Some(track) = self.simple_song {
            track
        } else {
            let pc = self.private_cloud.as_ref();
            let name = self
                .song_name
                .or_else(|| pc.and_then(|p| p.song.clone()))
                .or(self.file_name)
                .or_else(|| pc.and_then(|p| p.file_name.clone()))
                .filter(|name| !name.is_empty())
                .unwrap_or_else(|| "未知云盘歌曲".into());
            let artist_name = self
                .artist
                .or_else(|| pc.and_then(|p| p.artist.clone()))
                .filter(|name| !name.is_empty());
            let album_name = self
                .album
                .or_else(|| pc.and_then(|p| p.album.clone()))
                .filter(|name| !name.is_empty());
            TrackInfo {
                id: primary_id,
                name,
                duration_ms: 0,
                artists: artist_name
                    .map(|name| vec![ArtistShort { id: 0, name }])
                    .unwrap_or_default(),
                album: album_name.map(|name| AlbumShort {
                    id: 0,
                    name,
                    pic_url: None,
                }),
            }
        };
        let mut track = base;
        track.id = primary_id;
        Some((primary_id, track))
    }

    pub fn into_track_candidates(self) -> Vec<(i64, TrackInfo)> {
        let mut ids = Vec::new();
        for id in [self.id, self.song_id, self.file_id] {
            if let Some(id) = id {
                ids.push(id);
            }
        }
        if let Some(pc) = self.private_cloud.as_ref() {
            if let Some(id) = pc.song_id {
                ids.push(id);
            }
        }

        let base = if let Some(track) = self.simple_song {
            ids.push(track.id);
            track
        } else {
            let id = ids.first().copied();
            let pc = self.private_cloud.as_ref();
            let name = self
                .song_name
                .or_else(|| pc.and_then(|p| p.song.clone()))
                .or(self.file_name)
                .or_else(|| pc.and_then(|p| p.file_name.clone()))
                .filter(|name| !name.is_empty())
                .unwrap_or_else(|| "未知云盘歌曲".into());
            let artist_name = self
                .artist
                .or_else(|| pc.and_then(|p| p.artist.clone()))
                .filter(|name| !name.is_empty());
            let album_name = self
                .album
                .or_else(|| pc.and_then(|p| p.album.clone()))
                .filter(|name| !name.is_empty());
            TrackInfo {
                id: id.unwrap_or_default(),
                name,
                duration_ms: 0,
                artists: artist_name
                    .map(|name| vec![ArtistShort { id: 0, name }])
                    .unwrap_or_default(),
                album: album_name.map(|name| AlbumShort {
                    id: 0,
                    name,
                    pic_url: None,
                }),
            }
        };

        ids.into_iter()
            .filter(|id| *id != 0)
            .collect::<HashSet<_>>()
            .into_iter()
            .map(|id| {
                let mut track = base.clone();
                track.id = id;
                (id, track)
            })
            .collect()
    }
}

//
// --- 歌曲直链（播放 URL） ---
//

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct SongUrl {
    pub id: i64,
    /// 注意：非会员歌曲或下架歌曲会返回 null
    #[serde(default)]
    pub url: Option<String>,
    /// 码率
    #[serde(default)]
    pub br: i64,
    /// 文件大小（字节）
    #[serde(default)]
    pub size: i64,
}

#[derive(Debug, Deserialize)]
pub struct SongUrlResp {
    pub code: i32,
    #[serde(default)]
    pub data: Vec<SongUrl>,
}

//
// --- 歌词 ---
//
// weapi 的 /song/lyric 返回结构：
//   {
//     "code": 200,
//     "lrc":    { "lyric": "[00:01.23]..." },     // 原词（LRC 时间戳格式）
//     "tlyric": { "lyric": "[00:01.23]..." },     // 翻译（如果有）
//     "nolyric": true?,                           // 纯音乐标记
//     "uncollected": true?                        // 收录不全
//   }
// 我们抹平成一个 claudio 侧结构。

#[derive(Debug)]
pub struct LyricInner {
    pub lyric: Option<String>,
}

impl<'de> Deserialize<'de> for LyricInner {
    fn deserialize<D>(deserializer: D) -> Result<Self, D::Error>
    where
        D: Deserializer<'de>,
    {
        let value = Value::deserialize(deserializer)?;
        let lyric = match value {
            Value::String(s) => Some(s),
            Value::Object(mut obj) => obj
                .remove("lyric")
                .and_then(|v| v.as_str().map(ToOwned::to_owned)),
            _ => None,
        };
        Ok(Self { lyric })
    }
}

#[derive(Debug, Deserialize)]
pub struct LyricResp {
    pub code: i32,
    #[serde(default)]
    pub lrc: Option<LyricInner>,
    #[serde(default)]
    pub tlyric: Option<LyricInner>,
    /// 逐字（karaoke）歌词。网易云对收录较好的主流流行歌返回，独立 / 民谣常常没有。
    /// 格式：每行 `[lineStartMs,lineDurMs](charStartMs,charDurMs,0)char(charStartMs,charDurMs,0)char...`
    /// 用 lyric 字段透传给前端解析。
    #[serde(default, alias = "krc")]
    pub yrc: Option<LyricInner>,
    #[serde(default)]
    pub nolyric: bool,
    #[serde(default)]
    pub uncollected: bool,
}

/// 给前端的整形结构：只要两段纯文本 + 两个 flag，其余字段由 TS 侧解析 LRC。
/// 同时派生 `Deserialize`，因为缓存层的 `cache_save_lyric` 命令要把前端传回的这个
/// shape 直接反序列化回来存 SQLite —— 不想在 TS 侧再手动拆字段。
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LyricData {
    /// 原词，LRC 格式（含 `[mm:ss.xx]` 时间戳）。无歌词则为 None。
    pub lyric: Option<String>,
    /// 译词（可能为 None 或空串）
    pub translation: Option<String>,
    /// 逐字 yrc 原文（karaoke 时间）。冷门歌没收录，None。
    /// 格式见 [yrc.ts](src/lib/yrc.ts) parser。
    #[serde(default)]
    pub yrc: Option<String>,
    /// 纯音乐（KTV 背景 / 无人声）
    pub instrumental: bool,
    /// 网易云自己标的"收录不全" —— 前端可以顺手提示一下
    pub uncollected: bool,
}

//
// --- 搜索（用于"库外推荐"流水线） ---
//
// weapi /cloudsearch/get/web 用法：
//   { "s": "<query>", "type": 1, "limit": 30, "offset": 0 }
// 返回：result.songs[] 形状跟 TrackInfo 相同（id/name/ar/al/dt）。

#[derive(Debug, Deserialize)]
pub struct CloudSearchResult {
    #[serde(default)]
    pub songs: Vec<TrackInfo>,
}

#[derive(Debug, Deserialize)]
pub struct CloudSearchResp {
    pub code: i32,
    #[serde(default)]
    pub result: Option<CloudSearchResult>,
}

#[cfg(test)]
mod tests {
    use super::*;

    /// `v1/cloud/get/byids` 真实回放：含 simpleSong 的纯用户上传，并故意混一条
    /// `simpleSong.id` 为 null 的脏数据来验证不会把整页废掉。
    #[test]
    fn cloud_byids_extracts_simple_song_even_when_one_entry_is_malformed() {
        let raw = r#"{
            "data": [
                {
                    "simpleSong": {
                        "name": "pG one 语音备忘录",
                        "id": 1815333569,
                        "ar": [{"id": 0, "name": "PG one"}],
                        "alia": [],
                        "dt": null,
                        "pst": 0
                    },
                    "songId": 1815333569,
                    "fileName": "voice_memo.m4a"
                },
                {
                    "simpleSong": {"name": "bad", "id": null},
                    "songId": 999
                }
            ]
        }"#;

        let resp: UserCloudResp = serde_json::from_str(raw).expect("lenient cloud parse");
        assert_eq!(resp.code_or_ok(), 200);
        assert_eq!(resp.data.len(), 2, "lenient skip should keep both entries");

        let candidates: Vec<_> = resp
            .data
            .into_iter()
            .flat_map(|t| t.into_track_candidates())
            .collect();
        assert!(
            candidates.iter().any(|(id, _)| *id == 1815333569),
            "1815333569 missing from {candidates:?}"
        );
    }

    /// `v1/cloud/get` 真实回放：data[].privateCloud 才是 songId 来源，旧代码会因为
    /// 没有该字段直接抽不到任何 id。
    #[test]
    fn cloud_scan_extracts_song_id_from_private_cloud() {
        let raw = r#"{
            "data": [
                {
                    "privateCloud": {
                        "id": 631007120181,
                        "userId": 344072423,
                        "songId": 414264836,
                        "song": "六月飞霜",
                        "artist": "陈奕迅",
                        "album": "",
                        "fileName": "陈奕迅_六月飞霜.mp3"
                    }
                }
            ],
            "code": 200,
            "hasMore": false,
            "count": 1
        }"#;

        let resp: UserCloudResp = serde_json::from_str(raw).expect("lenient cloud parse");
        let mut candidates: Vec<_> = resp
            .data
            .into_iter()
            .flat_map(|t| t.into_track_candidates())
            .collect();
        assert_eq!(candidates.len(), 1);
        let (id, track) = candidates.remove(0);
        assert_eq!(id, 414264836);
        assert_eq!(track.id, 414264836);
        assert_eq!(track.name, "六月飞霜");
        assert_eq!(
            track.artists.first().map(|a| a.name.as_str()),
            Some("陈奕迅")
        );
    }

    /// `code` 缺失或为 null 时也要解（之前会因为 `code: i32` 必填整体 reject）。
    #[test]
    fn cloud_resp_tolerates_missing_code() {
        let raw = r#"{"data": []}"#;
        let resp: UserCloudResp = serde_json::from_str(raw).expect("missing code should parse");
        assert_eq!(resp.code_or_ok(), 200);

        let raw = r#"{"code": null, "data": []}"#;
        let resp: UserCloudResp = serde_json::from_str(raw).expect("null code should parse");
        assert_eq!(resp.code_or_ok(), 200);
    }
}
