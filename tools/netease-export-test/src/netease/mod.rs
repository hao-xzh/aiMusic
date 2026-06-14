#![allow(dead_code)]

#[path = "../../../../src-tauri/src/netease/api.rs"]
pub mod api;
#[path = "../../../../src-tauri/src/netease/client.rs"]
pub mod client;
#[path = "../../../../src-tauri/src/netease/crypto.rs"]
pub mod crypto;
#[path = "../../../../src-tauri/src/netease/models.rs"]
pub mod models;

pub use client::NeteaseClient;
