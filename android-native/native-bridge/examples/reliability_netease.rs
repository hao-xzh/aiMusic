//! Read-only live probe using the same Netease client as the Android bridge.
//! A temporary anonymous session is used; no account, cookies or credentials are persisted.
#![allow(dead_code, unused_imports)]
#[path = "../src/netease/mod.rs"]
mod netease;

use anyhow::{anyhow, Result};
use serde_json::{json, Value};
use std::io::{self, Read};

#[tokio::main]
async fn main() {
    let result = run().await;
    match result {
        Ok(value) => println!("{}", json!({"ok":true,"data":value})),
        Err(error) => {
            println!("{}", json!({"ok":false,"error":error.to_string()}));
            std::process::exit(1);
        }
    }
}

async fn run() -> Result<Value> {
    let mut text = String::new();
    io::stdin().read_to_string(&mut text)?;
    let args: Value = serde_json::from_str(&text)?;
    let client = netease::NeteaseClient::new()?;
    match args["operation"].as_str().unwrap_or("") {
        "search" => Ok(serde_json::to_value(netease::search_tracks(
            &client, args["query"].as_str().unwrap_or(""), args["limit"].as_i64().unwrap_or(20),
        ).await?)?),
        "search_evidence" => client.weapi("cloudsearch/get/web", json!({
            "s":args["query"], "type":1, "limit":args["limit"].as_i64().unwrap_or(20), "offset":0,
        })).await,
        "search_pc_evidence" => client.eapi("cloudsearch/pc", json!({
            "s":args["query"], "type":1, "limit":args["limit"].as_i64().unwrap_or(20), "offset":0, "total":true,
        })).await,
        "urls" => {
            let ids: Vec<i64> = args["ids"].as_array().into_iter().flatten().filter_map(Value::as_i64).collect();
            let level = args["level"].as_str().unwrap_or("lossless");
            Ok(serde_json::to_value(client.song_urls(&ids, level).await?)?)
        }
        "url_evidence" => {
            // The production SongUrl projection drops entitlement/trial status. Retain
            // the raw read-only response separately so nonempty URL is not called full play.
            client.weapi("song/enhance/player/url/v1", json!({
                "ids":args["ids"], "level":args["level"].as_str().unwrap_or("lossless"), "encodeType":"mp3",
            })).await
        }
        "lyrics" => Ok(serde_json::to_value(client.song_lyric(args["id"].as_i64().ok_or_else(||anyhow!("id required"))?).await?)?),
        _ => Err(anyhow!("Only read-only search, search_evidence, urls, url_evidence and lyrics are supported")),
    }
}
