//! 前端可调的 AI command（多 provider 版）。
//!
//! 公开给前端的 surface：
//!   - `ai_get_config`       — 返回所有 provider 的状态 + 当前选哪个
//!   - `ai_list_models`      — 给某 provider 拉已知模型列表（高→低）
//!   - `ai_set_provider`     — 切换激活 provider
//!   - `ai_set_api_key`      — 给某 provider 写 key（key 不上传、不回传）
//!   - `ai_clear_api_key`    — 清空某 provider 的 key
//!   - `ai_set_model`        — 给某 provider 选模型（持久化）
//!   - `ai_ping` / `ai_chat` — 都走当前激活 provider
//!
//! 设计细节：
//!   - 永远不回传完整 key，只给 `has_key` + `sk-***1234` 预览
//!   - 切 provider 不会清掉别家的 key（用户可以反复切回来对比）

use std::sync::Arc;

use serde::Serialize;
use tauri::State;

use super::config::{
    default_base_url, default_model, known_models, AiConfigStore, Provider, ProviderSlot,
};
use super::openai_compat::{self, ChatMessage};

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
pub struct ProviderView {
    pub id: String,
    pub label: String,
    pub has_key: bool,
    pub key_preview: Option<String>,
    pub model: String,
    pub base_url: String,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AiConfigPublic {
    /// 当前激活 provider 的 id（"deepseek" / "openai" / "xiaomi-mimo"）
    pub active_provider: String,
    pub providers: Vec<ProviderView>,
}

fn provider_label(p: Provider) -> &'static str {
    match p {
        Provider::Deepseek => "DeepSeek",
        Provider::Openai => "OpenAI",
        Provider::XiaomiMimo => "小米 MiMo",
    }
}

fn parse_provider(id: &str) -> Result<Provider, String> {
    match id {
        "deepseek" => Ok(Provider::Deepseek),
        "openai" => Ok(Provider::Openai),
        "xiaomi-mimo" => Ok(Provider::XiaomiMimo),
        other => Err(format!("未知 provider: {other}")),
    }
}

fn slot_view<'a>(p: Provider, slot: &'a ProviderSlot) -> ProviderView {
    let key_preview = slot
        .api_key
        .as_deref()
        .filter(|s| !s.trim().is_empty())
        .map(key_preview);
    ProviderView {
        id: p.as_key().to_string(),
        label: provider_label(p).to_string(),
        has_key: key_preview.is_some(),
        key_preview,
        model: slot.model.clone(),
        base_url: default_base_url(p).to_string(),
    }
}

#[tauri::command]
pub fn ai_get_config(state: AiState<'_>) -> AiConfigPublic {
    let cfg = state.snapshot();
    let providers = vec![
        slot_view(Provider::Deepseek, &cfg.deepseek),
        slot_view(Provider::Openai, &cfg.openai),
        slot_view(Provider::XiaomiMimo, &cfg.xiaomi_mimo),
    ];
    AiConfigPublic {
        active_provider: cfg.provider.as_key().to_string(),
        providers,
    }
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ModelOption {
    pub id: String,
    pub label: String,
}

/// 返回某 provider 已知模型列表（高→低排序）。
#[tauri::command]
pub fn ai_list_models(provider: String) -> Result<Vec<ModelOption>, String> {
    let p = parse_provider(&provider)?;
    Ok(known_models(p)
        .iter()
        .map(|(id, label)| ModelOption {
            id: id.to_string(),
            label: label.to_string(),
        })
        .collect())
}

#[tauri::command]
pub fn ai_set_provider(state: AiState<'_>, provider: String) -> Result<(), String> {
    let p = parse_provider(&provider)?;
    state.update(|c| c.provider = p).map_err(to_err)
}

#[tauri::command]
pub fn ai_set_api_key(
    state: AiState<'_>,
    provider: String,
    key: String,
) -> Result<(), String> {
    let p = parse_provider(&provider)?;
    state
        .update(|c| {
            let trimmed = key.trim().to_string();
            let new_key = if trimmed.is_empty() { None } else { Some(trimmed) };
            slot_mut(c, p).api_key = new_key;
        })
        .map_err(to_err)
}

#[tauri::command]
pub fn ai_clear_api_key(state: AiState<'_>, provider: String) -> Result<(), String> {
    let p = parse_provider(&provider)?;
    state
        .update(|c| {
            slot_mut(c, p).api_key = None;
        })
        .map_err(to_err)
}

#[tauri::command]
pub fn ai_set_model(
    state: AiState<'_>,
    provider: String,
    model: String,
) -> Result<(), String> {
    let p = parse_provider(&provider)?;
    let m = model.trim().to_string();
    if m.is_empty() {
        return Err("model 不能为空".into());
    }
    state
        .update(|c| {
            slot_mut(c, p).model = m;
        })
        .map_err(to_err)
}

fn slot_mut(c: &mut super::config::AiConfig, p: Provider) -> &mut ProviderSlot {
    match p {
        Provider::Deepseek => &mut c.deepseek,
        Provider::Openai => &mut c.openai,
        Provider::XiaomiMimo => &mut c.xiaomi_mimo,
    }
}

/// 测试 key 可用性：让模型说一句很短的中文问候。
#[tauri::command]
pub async fn ai_ping(state: AiState<'_>) -> Result<String, String> {
    let (key, base_url, model) = state.active();
    let key = key.ok_or_else(|| {
        let cfg = state.snapshot();
        format!("还没填 {} 的 API key", provider_label(cfg.provider))
    })?;
    // active provider 可能跟 default model 不一致，用用户选的 model
    let _ = (base_url, &model);

    let messages = vec![ChatMessage {
        role: "user".into(),
        content: "用不超过 12 个汉字跟我打个招呼，像电台 DJ 的开场白。".into(),
    }];

    openai_compat::chat(&key, base_url, &model, &messages, 0.8, 80)
        .await
        .map_err(to_err)
}

/// 通用 chat 入口。system/temp/max_tokens 可选，给未来旁白/选曲留门。
/// 永远走当前激活 provider。
#[tauri::command]
pub async fn ai_chat(
    state: AiState<'_>,
    system: Option<String>,
    user: String,
    temperature: Option<f32>,
    max_tokens: Option<u32>,
) -> Result<String, String> {
    let (key, base_url, model) = state.active();
    let key = key.ok_or_else(|| {
        let cfg = state.snapshot();
        format!("还没填 {} 的 API key", provider_label(cfg.provider))
    })?;

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

    openai_compat::chat(
        &key,
        base_url,
        &model,
        &messages,
        temperature.unwrap_or(0.8),
        max_tokens.unwrap_or(400),
    )
    .await
    .map_err(to_err)
}

// 给初始化默认值用，避免 Provider/ProviderSlot 没在外部 use
#[allow(dead_code)]
fn _unused_default_model_marker() -> &'static str {
    default_model(Provider::Deepseek)
}
