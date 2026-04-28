//! 每个我们用到的 weapi 端点一个函数，返回强类型。
//!
//! 二维码登录的流程：
//!   1) qr_unikey()                -> 拿到 unikey
//!   2) 前端画二维码，内容是 `https://music.163.com/login?codekey=<unikey>`
//!   3) qr_check(unikey) 每 2 秒调一次：
//!        800 = key 过期，重新 unikey
//!        801 = 等待扫码
//!        802 = 已扫码，等手机上点"确认登录"
//!        803 = 确认完成，此时 reqwest 的 cookie jar 里已经自动装上 MUSIC_U + __csrf 等

use anyhow::{anyhow, Result};
use serde_json::json;

use super::client::NeteaseClient;
use super::models::*;

impl NeteaseClient {
    pub async fn qr_unikey(&self) -> Result<String> {
        let resp: QrUnikeyResp = self
            .weapi("login/qrcode/unikey", json!({ "type": 1 }))
            .await?;
        if resp.code != 200 {
            return Err(anyhow!("qr_unikey code={}", resp.code));
        }
        resp.unikey.ok_or_else(|| anyhow!("qr_unikey missing unikey"))
    }

    pub async fn qr_check(&self, key: &str) -> Result<QrCheckResp> {
        let resp: QrCheckResp = self
            .weapi(
                "login/qrcode/client/login",
                json!({ "type": 1, "key": key }),
            )
            .await?;
        // 登录确认成功那一刻，MUSIC_U / __csrf 已经落到 cookie jar 里。
        // 立即 persist，否则用户重启 app 又得重扫一次。
        if resp.code == 803 {
            if let Err(e) = self.persist() {
                eprintln!("[netease] cookie persist failed: {e:#}");
            }
        }
        Ok(resp)
    }

    pub async fn account(&self) -> Result<Option<UserProfile>> {
        let resp: AccountGetResp = self.weapi("w/nuser/account/get", json!({})).await?;
        if resp.code != 200 {
            return Err(anyhow!("account code={}", resp.code));
        }
        Ok(resp.profile)
    }

    pub async fn user_playlists(&self, uid: i64, limit: i64) -> Result<Vec<PlaylistInfo>> {
        let resp: UserPlaylistsResp = self
            .weapi(
                "user/playlist",
                json!({ "uid": uid, "limit": limit, "offset": 0 }),
            )
            .await?;
        if resp.code != 200 {
            return Err(anyhow!("user_playlists code={}", resp.code));
        }
        Ok(resp.playlist)
    }

    pub async fn playlist_detail(&self, id: i64) -> Result<PlaylistDetail> {
        let resp: PlaylistDetailResp = self
            .weapi("v6/playlist/detail", json!({ "id": id, "n": 1000 }))
            .await?;
        if resp.code != 200 {
            return Err(anyhow!("playlist_detail code={}", resp.code));
        }
        Ok(resp.playlist)
    }

    /// 拿歌曲直链。`level` 可取 "standard" / "higher" / "exhigh" / "lossless"...
    /// VIP 级别由当前 cookie 决定。
    pub async fn song_urls(&self, ids: &[i64], level: &str) -> Result<Vec<SongUrl>> {
        let resp: SongUrlResp = self
            .weapi(
                "song/enhance/player/url/v1",
                json!({
                    "ids": ids,
                    "level": level,
                    "encodeType": "mp3",
                }),
            )
            .await?;
        if resp.code != 200 {
            return Err(anyhow!("song_urls code={}", resp.code));
        }
        Ok(resp.data)
    }

    /// 拿一首歌的歌词。
    ///
    /// weapi `song/lyric` 的惯用参数：`lv=-1 kv=-1 tv=-1` 告诉后端"我全要，不分页"。
    /// 返回的 `lrc.lyric` / `tlyric.lyric` 是原生 LRC 文本（含 `[mm:ss.xx]` 时间戳），
    /// 前端自己解析、按 positionSec 高亮对应行，不在 Rust 侧搞时轴对齐 ——
    /// 一是省带宽，二是 TS 侧和 audio.currentTime 同帧做对齐更稳。
    /// 关键词搜索单曲。Phase B 库外推荐用 ——
    /// AI 出关键词 / 艺人 → 这里搜回真实可播的 TrackInfo 候选池，
    /// 再让 AI re-rank 选最贴口味的，避免幻觉推荐拉空。
    ///
    /// type=1（单曲），返回 result.songs。
    pub async fn search_tracks(&self, query: &str, limit: i64) -> Result<Vec<TrackInfo>> {
        let resp: CloudSearchResp = self
            .weapi(
                "cloudsearch/get/web",
                json!({
                    "s": query,
                    "type": 1,
                    "limit": limit,
                    "offset": 0,
                }),
            )
            .await?;
        if resp.code != 200 {
            return Err(anyhow!("search_tracks code={}", resp.code));
        }
        Ok(resp.result.map(|r| r.songs).unwrap_or_default())
    }

    pub async fn song_lyric(&self, id: i64) -> Result<LyricData> {
        let resp: LyricResp = self
            .weapi(
                "song/lyric",
                json!({
                    "id": id,
                    "lv": -1,
                    "kv": -1,
                    "tv": -1,
                }),
            )
            .await?;
        if resp.code != 200 {
            return Err(anyhow!("song_lyric code={}", resp.code));
        }
        Ok(LyricData {
            lyric: resp.lrc.and_then(|l| l.lyric).filter(|s| !s.is_empty()),
            translation: resp.tlyric.and_then(|l| l.lyric).filter(|s| !s.is_empty()),
            instrumental: resp.nolyric,
            uncollected: resp.uncollected,
        })
    }
}
