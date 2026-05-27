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

use std::collections::HashSet;

use anyhow::{anyhow, Result};
use serde_json::json;

use super::client::NeteaseClient;
use super::models::*;

#[derive(Default)]
struct CloudHydrationResult {
    tracks: Vec<TrackInfo>,
    returned_count: usize,
    pages: usize,
    error: Option<String>,
}

impl NeteaseClient {
    pub async fn qr_unikey(&self) -> Result<String> {
        let resp: QrUnikeyResp = self
            .weapi("login/qrcode/unikey", json!({ "type": 1 }))
            .await?;
        if resp.code != 200 {
            return Err(anyhow!("qr_unikey code={}", resp.code));
        }
        resp.unikey
            .ok_or_else(|| anyhow!("qr_unikey missing unikey"))
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

    /// 给手机号发短信验证码。
    /// `ctcode` 是国家区号，默认 86（中国大陆）。
    /// 后端常见返回码：200 = 已发送 / 400 = 参数错误 / 503 = 频繁触发风控。
    pub async fn captcha_sent(&self, phone: &str, ctcode: i32) -> Result<CaptchaResp> {
        let resp: CaptchaResp = self
            .weapi(
                "sms/captcha/sent",
                json!({ "ctcode": ctcode, "cellphone": phone }),
            )
            .await?;
        Ok(resp)
    }

    /// 验证码登录。成功后 cookie jar 里就有 MUSIC_U + __csrf，立即 persist 落盘
    /// —— 跟 qr_check 803 一个套路，下次启动直接已登录。
    pub async fn login_cellphone(
        &self,
        phone: &str,
        captcha: &str,
        ctcode: i32,
    ) -> Result<CellphoneLoginResp> {
        let resp: CellphoneLoginResp = self
            .weapi(
                "login/cellphone",
                json!({
                    "phone": phone,
                    "countrycode": ctcode,
                    "captcha": captcha,
                    "rememberLogin": true,
                }),
            )
            .await?;
        if resp.code == 200 {
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
        let mut fallback_error = None;
        let (resp, source): (PlaylistDetailResp, &'static str) = match self
            .linuxapi::<PlaylistDetailResp>(
                "v3/playlist/detail",
                json!({ "id": id, "n": 100000, "s": 8 }),
            )
            .await
        {
            Ok(resp) if resp.code == 200 => (resp, "linuxapi_v3"),
            Ok(resp) => {
                fallback_error = Some(compact_error(format!("linuxapi_v3 code={}", resp.code)));
                let fallback: PlaylistDetailResp = self
                    .weapi("v6/playlist/detail", json!({ "id": id, "n": 1000 }))
                    .await?;
                if fallback.code != 200 {
                    return Err(anyhow!("playlist_detail fallback code={}", fallback.code));
                }
                (fallback, "weapi_v6")
            }
            Err(e) => {
                fallback_error = Some(compact_error(format!("{e:#}")));
                let fallback: PlaylistDetailResp = self
                    .weapi("v6/playlist/detail", json!({ "id": id, "n": 1000 }))
                    .await?;
                if fallback.code != 200 {
                    return Err(anyhow!("playlist_detail fallback code={}", fallback.code));
                }
                (fallback, "weapi_v6")
            }
        };
        self.hydrate_playlist_tracks(resp.playlist, source, fallback_error)
            .await
    }

    async fn hydrate_playlist_tracks(
        &self,
        mut playlist: PlaylistDetail,
        source: &'static str,
        fallback_error: Option<String>,
    ) -> Result<PlaylistDetail> {
        let mut diagnostics = PlaylistHydrationDiagnostics {
            playlist_detail_source: source.to_string(),
            playlist_detail_fallback_error: fallback_error.unwrap_or_default(),
            original_tracks_count: playlist.tracks.len(),
            track_ids_count: playlist.track_ids.len(),
            ..Default::default()
        };
        if playlist.track_ids.is_empty() || playlist.tracks.len() >= playlist.track_ids.len() {
            diagnostics.final_tracks_count = playlist.tracks.len();
            playlist.hydration_diagnostics = diagnostics;
            return Ok(playlist);
        }

        let mut by_id = TrackInfo::by_id(&playlist.tracks);
        let missing_ids = playlist
            .track_ids
            .iter()
            .copied()
            .filter(|id| !by_id.contains_key(id))
            .collect::<Vec<_>>();
        diagnostics.song_detail_requested = missing_ids.len();

        let song_detail_tracks = self.song_detail_best_effort(&missing_ids).await;
        diagnostics.song_detail_resolved = song_detail_tracks.len();
        for track in song_detail_tracks {
            by_id.insert(track.id, track);
        }

        let still_missing = playlist
            .track_ids
            .iter()
            .copied()
            .filter(|id| !by_id.contains_key(id))
            .collect::<Vec<_>>();
        if !still_missing.is_empty() {
            diagnostics.cloud_by_ids_requested = still_missing.len();
            let cloud_result = self.cloud_tracks_by_ids_best_effort(&still_missing).await;
            diagnostics.cloud_by_ids_returned = cloud_result.returned_count;
            diagnostics.cloud_by_ids_resolved = cloud_result.tracks.len();
            diagnostics.cloud_by_ids_error = cloud_result.error.unwrap_or_default();
            for track in cloud_result.tracks {
                by_id.insert(track.id, track);
            }
        }

        let still_missing = playlist
            .track_ids
            .iter()
            .copied()
            .filter(|id| !by_id.contains_key(id))
            .collect::<Vec<_>>();
        if !still_missing.is_empty() {
            diagnostics.cloud_scan_requested = still_missing.len();
            let cloud_result = self.cloud_tracks_best_effort(&still_missing).await;
            diagnostics.cloud_scan_pages = cloud_result.pages;
            diagnostics.cloud_scan_returned = cloud_result.returned_count;
            diagnostics.cloud_scan_resolved = cloud_result.tracks.len();
            diagnostics.cloud_scan_error = cloud_result.error.unwrap_or_default();
            for track in cloud_result.tracks {
                by_id.insert(track.id, track);
            }
        }

        let ordered = playlist
            .track_ids
            .iter()
            .filter_map(|id| by_id.get(id).cloned())
            .collect::<Vec<_>>();
        let final_missing = playlist
            .track_ids
            .iter()
            .copied()
            .filter(|id| !by_id.contains_key(id))
            .collect::<Vec<_>>();
        diagnostics.final_tracks_count = ordered.len();
        diagnostics.missing_after_hydration = final_missing.len();
        diagnostics.missing_id_sample = final_missing.into_iter().take(16).collect();
        if !ordered.is_empty() {
            playlist.tracks = ordered;
        }
        playlist.hydration_diagnostics = diagnostics;
        Ok(playlist)
    }

    async fn song_detail_best_effort(&self, ids: &[i64]) -> Vec<TrackInfo> {
        let mut out = Vec::new();
        let mut pending = ids.to_vec();
        for chunk_size in [200usize, 50, 10, 1] {
            if pending.is_empty() {
                break;
            }
            let mut still_missing = Vec::new();
            for chunk in pending.chunks(chunk_size) {
                match self.song_detail(chunk).await {
                    Ok(tracks) => {
                        let returned_ids =
                            tracks.iter().map(|track| track.id).collect::<HashSet<_>>();
                        out.extend(tracks);
                        for id in chunk {
                            if !returned_ids.contains(id) {
                                still_missing.push(*id);
                            }
                        }
                    }
                    Err(e) => {
                        eprintln!(
                            "[netease] song_detail chunk failed: size={} first_id={:?}: {e:#}",
                            chunk.len(),
                            chunk.first(),
                        );
                        still_missing.extend_from_slice(chunk);
                    }
                }
            }
            pending = still_missing;
        }
        out
    }

    async fn cloud_tracks_by_ids_best_effort(&self, ids: &[i64]) -> CloudHydrationResult {
        let needed = ids.iter().copied().collect::<HashSet<_>>();
        let mut found = HashSet::new();
        let mut result = CloudHydrationResult::default();

        for chunk in ids.chunks(100) {
            let page = match self.user_cloud_tracks_by_ids(chunk).await {
                Ok(page) => page,
                Err(e) => {
                    result.error = Some(compact_error(format!("{e:#}")));
                    eprintln!(
                        "[netease] user_cloud_byids failed: size={} first_id={:?}: {e:#}",
                        chunk.len(),
                        chunk.first(),
                    );
                    continue;
                }
            };
            result.pages += 1;
            result.returned_count += page.data.len();
            for item in page.data {
                for (candidate_id, track) in item.into_track_candidates() {
                    if needed.contains(&candidate_id) && found.insert(candidate_id) {
                        result.tracks.push(track);
                    }
                }
            }
            if found.len() >= needed.len() {
                break;
            }
        }

        result
    }

    async fn cloud_tracks_best_effort(&self, ids: &[i64]) -> CloudHydrationResult {
        let needed = ids.iter().copied().collect::<HashSet<_>>();
        let mut found = HashSet::new();
        let mut result = CloudHydrationResult::default();
        let limit = 200i64;
        let mut offset = 0i64;

        // The cloud library is private-user scoped and can be large. Page gently and stop
        // once all missing playlist ids are found.
        for _ in 0..25 {
            let page = match self.user_cloud_tracks_page(limit, offset).await {
                Ok(page) => page,
                Err(e) => {
                    result.error = Some(compact_error(format!("{e:#}")));
                    eprintln!("[netease] user_cloud page failed offset={offset}: {e:#}");
                    break;
                }
            };
            let has_more = page.has_more
                || page
                    .count
                    .map(|count| offset + (page.data.len() as i64) < count)
                    .unwrap_or(false);
            let page_len = page.data.len();
            result.pages += 1;
            result.returned_count += page_len;
            for item in page.data {
                for (candidate_id, track) in item.into_track_candidates() {
                    if needed.contains(&candidate_id) && found.insert(candidate_id) {
                        result.tracks.push(track);
                    }
                }
            }
            if found.len() >= needed.len() || !has_more || page_len == 0 {
                break;
            }
            offset += limit;
        }

        result
    }

    async fn user_cloud_tracks_page(&self, limit: i64, offset: i64) -> Result<UserCloudResp> {
        let resp: UserCloudResp = self
            .weapi(
                "v1/cloud/get",
                json!({
                    "limit": limit,
                    "offset": offset,
                }),
            )
            .await?;
        let code = resp.code_or_ok();
        if code != 200 {
            return Err(anyhow!("user_cloud code={}", code));
        }
        Ok(resp)
    }

    async fn user_cloud_tracks_by_ids(&self, ids: &[i64]) -> Result<UserCloudResp> {
        let song_ids = ids.iter().map(ToString::to_string).collect::<Vec<_>>();
        let resp: UserCloudResp = self
            .weapi(
                "v1/cloud/get/byids",
                json!({
                    "songIds": song_ids,
                }),
            )
            .await?;
        let code = resp.code_or_ok();
        if code != 200 {
            return Err(anyhow!("user_cloud_byids code={}", code));
        }
        Ok(resp)
    }

    pub async fn song_detail(&self, ids: &[i64]) -> Result<Vec<TrackInfo>> {
        if ids.is_empty() {
            return Ok(Vec::new());
        }
        let c = ids.iter().map(|id| json!({ "id": id })).collect::<Vec<_>>();
        let resp: SongDetailResp = self
            .weapi(
                "v3/song/detail",
                json!({
                    "c": serde_json::to_string(&c)?,
                    "ids": serde_json::to_string(ids)?,
                }),
            )
            .await?;
        if resp.code != 200 {
            return Err(anyhow!("song_detail code={}", resp.code));
        }
        Ok(resp.songs)
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
        // yv: -1 → 拉逐字（karaoke）。网易云对收录较好的歌会返回 yrc 字段；
        // 没有就照常返回（lrc 兜底，前端用插值假逐字）。
        let resp: LyricResp = self
            .weapi(
                "song/lyric",
                json!({
                    "id": id,
                    "lv": -1,
                    "kv": -1,
                    "tv": -1,
                    "yv": -1,
                }),
            )
            .await?;
        if resp.code != 200 {
            return Err(anyhow!("song_lyric code={}", resp.code));
        }
        Ok(LyricData {
            lyric: resp.lrc.and_then(|l| l.lyric).filter(|s| !s.is_empty()),
            translation: resp.tlyric.and_then(|l| l.lyric).filter(|s| !s.is_empty()),
            yrc: resp.yrc.and_then(|l| l.lyric).filter(|s| !s.is_empty()),
            instrumental: resp.nolyric,
            uncollected: resp.uncollected,
        })
    }
}

fn compact_error(message: String) -> String {
    const MAX_CHARS: usize = 240;
    let mut out = message.chars().take(MAX_CHARS).collect::<String>();
    if message.chars().count() > MAX_CHARS {
        out.push_str("...");
    }
    out
}
