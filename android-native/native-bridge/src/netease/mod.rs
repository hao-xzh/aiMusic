#[path = "../../../../src-tauri/src/netease/crypto.rs"]
pub mod crypto;
#[path = "../../../../src-tauri/src/netease/models.rs"]
pub mod models;
#[path = "../../../../src-tauri/src/netease/client.rs"]
pub mod client;
#[path = "../../../../src-tauri/src/netease/api.rs"]
pub mod api;

pub use client::NeteaseClient;
