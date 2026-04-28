//! 网易云 weapi 响应的强类型 shape（只解构我们真正消费的字段）。
//!
//! 返回里常有 camelCase / 奇怪缩写（pc/ar/al），前端拿不到 snake_case 字段名没关系 ——
//! 我们这里重新塑形成 claudio 视角的 `TrackInfo`, `PlaylistInfo` 等等，再给前端。

use serde::{Deserialize, Deserializer, Serialize};
use serde_json::Value;

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
    #[serde(default)]
    pub cookie: Option<String>,
    /// 803 时可能带昵称
    #[serde(default, rename = "nickname")]
    pub nickname: Option<String>,
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
    #[serde(default, rename = "durationMs", alias = "dt")]
    pub duration_ms: i64,
    #[serde(default, rename = "artists", alias = "ar")]
    pub artists: Vec<ArtistShort>,
    #[serde(default, rename = "album", alias = "al")]
    pub album: Option<AlbumShort>,
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

#[derive(Debug, Deserialize)]
pub struct PlaylistDetailResp {
    pub code: i32,
    pub playlist: PlaylistDetail,
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

#[derive(Debug, Deserialize)]
pub struct LyricInner {
    #[serde(default)]
    pub lyric: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct LyricResp {
    pub code: i32,
    #[serde(default)]
    pub lrc: Option<LyricInner>,
    #[serde(default)]
    pub tlyric: Option<LyricInner>,
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
    #[serde(default, rename = "songCount")]
    pub song_count: i64,
}

#[derive(Debug, Deserialize)]
pub struct CloudSearchResp {
    pub code: i32,
    #[serde(default)]
    pub result: Option<CloudSearchResult>,
}
