#[path = "../../../../src-tauri/src/netease/api.rs"]
pub mod api;
#[path = "../../../../src-tauri/src/netease/client.rs"]
pub mod client;
#[path = "../../../../src-tauri/src/netease/crypto.rs"]
pub mod crypto;
#[path = "../../../../src-tauri/src/netease/models.rs"]
pub mod models;

pub use client::NeteaseClient;

/// Android online search uses the current PC search endpoint. The older web
/// cloudsearch endpoint returns code=50000005 even for valid anonymous queries.
pub async fn search_tracks(
    client: &NeteaseClient,
    query: &str,
    limit: i64,
) -> anyhow::Result<Vec<models::TrackInfo>> {
    let response: models::CloudSearchResp = client
        .eapi("cloudsearch/pc", serde_json::json!({
            "s": query.trim(), "type": 1, "limit": limit.clamp(1, 100),
            "offset": 0, "total": true,
        }))
        .await?;
    if response.code != 200 {
        return Err(anyhow::anyhow!("search_tracks code={}", response.code));
    }
    Ok(response.result.map(|result| result.songs).unwrap_or_default())
}
