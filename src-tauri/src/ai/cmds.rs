//! 前端可调的 AI command。
//!
//! 设计细节：
//!   - `ai_get_config` 返回给前端的是 **public view** —— 永远不回传完整 key，
//!     只给 `has_key` + `sk-***1234` 预览，避免前端 state / 调试面板泄漏。
//!   - `ai_set_api_key` / `ai_clear_api_key` 直接写盘，成功后 snapshot 立即生效。
//!   - `ai_ping` 用一句极短提示验证 key 能跑通（也顺便能看模型风格对不对）。
//!   - `ai_chat` 是通用入口，后续 DJ 旁白 / 选曲都走这里。

use std::sync::Arc;

use serde::Serialize;
use tauri::State;

use super::config::AiConfigStore;
use super::deepseek::{self, ChatMessage};

pub type AiState<'a> = State<'a, Arc<AiConfigStore>>;

fn to_err<E: std::fmt::Display>(e: E) -> String {
    e.to_string()
}

fn key_preview(key: &str) -> String {
    let len = key.len();
    if len <= 8 {
        "•".repeat(len)
    } else {
        format!("{}•••{}", &key[..4], &key[len - 4..])
    }
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AiConfigPublic {
    pub has_key: bool,
    /// "sk-xx•••abcd" 预览，没 key 时为 None
    pub key_preview: Option<String>,
    pub model: String,
    pub base_url: String,
}

#[tauri::command]
pub fn ai_get_config(state: AiState<'_>) -> AiConfigPublic {
    let cfg = state.snapshot();
    let has_key = cfg
        .deepseek_api_key
        .as_deref()
        .map(|s| !s.trim().is_empty())
        .unwrap_or(false);
    AiConfigPublic {
        has_key,
        key_preview: cfg
            .deepseek_api_key
            .as_deref()
            .filter(|s| !s.trim().is_empty())
            .map(key_preview),
        model: cfg.model,
        base_url: cfg.base_url,
    }
}

#[tauri::command]
pub fn ai_set_api_key(state: AiState<'_>, key: String) -> Result<(), String> {
    state
        .update(|c| {
            let trimmed = key.trim().to_string();
            c.deepseek_api_key = if trimmed.is_empty() { None } else { Some(trimmed) };
        })
        .map_err(to_err)
}

#[tauri::command]
pub fn ai_clear_api_key(state: AiState<'_>) -> Result<(), String> {
    state
        .update(|c| {
            c.deepseek_api_key = None;
        })
        .map_err(to_err)
}

/// 测试 key 可用性：让模型说一句很短的中文问候。
/// 返回的字符串直接给前端展示。
#[tauri::command]
pub async fn ai_ping(state: AiState<'_>) -> Result<String, String> {
    let cfg = state.snapshot();
    let key = cfg
        .deepseek_api_key
        .filter(|s| !s.trim().is_empty())
        .ok_or_else(|| "还没填 DeepSeek API key".to_string())?;

    let messages = vec![ChatMessage {
        role: "user".into(),
        content: "用不超过 12 个汉字跟我打个招呼，像电台 DJ 的开场白。".into(),
    }];

    deepseek::chat(&key, &cfg.base_url, &cfg.model, &messages, 0.8, 80)
        .await
        .map_err(to_err)
}

/// 通用 chat 入口。system/temp/max_tokens 可选，给未来旁白/选曲留门。
#[tauri::command]
pub async fn ai_chat(
    state: AiState<'_>,
    system: Option<String>,
    user: String,
    temperature: Option<f32>,
    max_tokens: Option<u32>,
) -> Result<String, String> {
    let cfg = state.snapshot();
    let key = cfg
        .deepseek_api_key
        .filter(|s| !s.trim().is_empty())
        .ok_or_else(|| "还没填 DeepSeek API key".to_string())?;

    let mut messages: Vec<ChatMessage> = Vec::new();
    if let Some(sys) = system.filter(|s| !s.trim().is_empty()) {
        messages.push(ChatMessage {
            role: "system".into(),
            content: sys,
        });
    }
    messages.push(ChatMessage {
        role: "user".into(),
        content: user,
    });

    deepseek::chat(
        &key,
        &cfg.base_url,
        &cfg.model,
        &messages,
        temperature.unwrap_or(0.8),
        max_tokens.unwrap_or(400),
    )
    .await
    .map_err(to_err)
}
