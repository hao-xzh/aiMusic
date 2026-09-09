#[path = "../../../../src-tauri/src/netease/api.rs"]
pub mod api;
#[path = "../../../../src-tauri/src/netease/client.rs"]
pub mod client;
#[path = "../../../../src-tauri/src/netease/crypto.rs"]
pub mod crypto;
#[path = "../../../../src-tauri/src/netease/models.rs"]
pub mod models;

pub use client::NeteaseClient;

use serde_json::Value;

/// Android online search uses the current PC search endpoint. The older web
/// cloudsearch endpoint returns code=50000005 even for valid anonymous queries.
pub async fn search_tracks(
    client: &NeteaseClient,
    query: &str,
    limit: i64,
) -> anyhow::Result<Vec<models::TrackInfo>> {
    search_tracks_page(client, query, limit, 0).await
}

pub async fn search_tracks_page(
    client: &NeteaseClient,
    query: &str,
    limit: i64,
    offset: i64,
) -> anyhow::Result<Vec<models::TrackInfo>> {
    let response: models::CloudSearchResp = client
        .eapi(
            "cloudsearch/pc",
            serde_json::json!({
                "s": query.trim(), "type": 1, "limit": limit.clamp(1, 100),
                "offset": offset.clamp(0, 10_000), "total": true,
            }),
        )
        .await?;
    if response.code != 200 {
        return Err(anyhow::anyhow!("search_tracks code={}", response.code));
    }
    Ok(response
        .result
        .map(|result| result.songs)
        .unwrap_or_default())
}

pub async fn daily_recommended_tracks(
    client: &NeteaseClient,
) -> anyhow::Result<Vec<models::TrackInfo>> {
    let response: Value = client
        .weapi("v3/discovery/recommend/songs", serde_json::json!({}))
        .await?;
    let tracks = response
        .get("data")
        .and_then(|data| data.get("dailySongs"))
        .and_then(Value::as_array)
        .or_else(|| response.get("recommend").and_then(Value::as_array));
    parse_recommendation_tracks(&response, tracks, "daily_recommended_tracks")
}

pub async fn personal_fm_tracks(client: &NeteaseClient) -> anyhow::Result<Vec<models::TrackInfo>> {
    let response: Value = client.weapi("v1/radio/get", serde_json::json!({})).await?;
    let tracks = response.get("data").and_then(Value::as_array);
    parse_recommendation_tracks(&response, tracks, "personal_fm_tracks")
}

pub async fn similar_tracks(
    client: &NeteaseClient,
    track_id: i64,
) -> anyhow::Result<Vec<models::TrackInfo>> {
    if track_id <= 0 {
        return Err(anyhow::anyhow!(
            "similar_tracks requires a positive trackId"
        ));
    }
    let response: Value = client
        .weapi(
            "v1/discovery/simiSong",
            serde_json::json!({ "songid": track_id, "limit": 50, "offset": 0 }),
        )
        .await?;
    let tracks = response.get("songs").and_then(Value::as_array);
    parse_recommendation_tracks(&response, tracks, "similar_tracks")
}

fn parse_recommendation_tracks(
    response: &Value,
    tracks: Option<&Vec<Value>>,
    source: &str,
) -> anyhow::Result<Vec<models::TrackInfo>> {
    if response.get("code").and_then(Value::as_i64) != Some(200) {
        return Err(anyhow::anyhow!(
            "{source} code={}",
            response.get("code").unwrap_or(&Value::Null)
        ));
    }
    let tracks = tracks.ok_or_else(|| anyhow::anyhow!("{source} missing tracks array"))?;
    let parsed = tracks
        .iter()
        .filter_map(|raw| parse_recommendation_track(raw))
        .collect::<Vec<_>>();
    if !tracks.is_empty() && parsed.is_empty() {
        return Err(anyhow::anyhow!("{source} had no valid tracks"));
    }
    Ok(parsed)
}

fn parse_recommendation_track(raw: &Value) -> Option<models::TrackInfo> {
    if raw.get("name")?.as_str()?.trim().is_empty() {
        return None;
    }
    let mut normalized = raw.clone();
    let object = normalized.as_object_mut()?;
    if !object.contains_key("durationMs") && !object.contains_key("dt") {
        if let Some(duration) = object.get("duration").cloned() {
            object.insert("durationMs".to_string(), duration);
        }
    }
    let track = serde_json::from_value::<models::TrackInfo>(normalized).ok()?;
    (track.id > 0
        && !track.name.trim().is_empty()
        && track
            .artists
            .iter()
            .any(|artist| !artist.name.trim().is_empty() && artist.name != "未知艺人"))
    .then_some(track)
}
