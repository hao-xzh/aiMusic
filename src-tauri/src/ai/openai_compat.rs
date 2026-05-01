//! OpenAI 兼容 chat/completions 调用。
//!
//! 三家 provider 都是 OpenAI-compatible（DeepSeek / OpenAI / 小米 MiMo）：
//!   - POST `{base_url}/chat/completions`
//!   - `Authorization: Bearer <api_key>`
//!   - body: `{ model, messages, temperature, max_tokens, stream }`
//!
//! 所以这里只需要一份调用逻辑，按 caller 传进来的 base_url / api_key / model 路由即可。
//! 后续如果接 Anthropic / Gemini 这种不兼容协议的，再单独写一支。

use std::time::Duration;

use anyhow::{anyhow, Result};
use reqwest::Client;
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize)]
pub struct ChatMessage {
    pub role: String,
    pub content: String,
}

#[derive(Debug, Serialize)]
struct ChatReq<'a> {
    model: &'a str,
    messages: &'a [ChatMessage],
    temperature: f32,
    max_tokens: u32,
    stream: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    thinking: Option<ThinkingReq>,
}

#[derive(Debug, Serialize)]
struct ThinkingReq {
    #[serde(rename = "type")]
    kind: &'static str,
}

#[derive(Debug, Deserialize)]
struct ChatResp {
    #[serde(default)]
    choices: Vec<ChatChoice>,
}

#[derive(Debug, Deserialize)]
struct ChatChoice {
    message: ChatMessageResp,
}

#[derive(Debug, Deserialize)]
struct ChatMessageResp {
    #[serde(default)]
    content: String,
}

pub async fn chat(
    api_key: &str,
    base_url: &str,
    model: &str,
    messages: &[ChatMessage],
    temperature: f32,
    max_tokens: u32,
) -> Result<String> {
    let key = api_key.trim();
    if key.is_empty() {
        return Err(anyhow!("还没填 API key，请在设置里填上"));
    }

    let url = format!(
        "{}/chat/completions",
        base_url.trim_end_matches('/')
    );

    let client = Client::builder()
        .timeout(Duration::from_secs(60))
        .build()
        .map_err(|e| anyhow!("构造 http client 失败：{e}"))?;

    let body = ChatReq {
        model,
        messages,
        temperature,
        max_tokens,
        stream: false,
        thinking: if base_url.trim_end_matches('/') == "https://api.deepseek.com" {
            Some(ThinkingReq { kind: "disabled" })
        } else {
            None
        },
    };

    let resp = client
        .post(&url)
        .bearer_auth(key)
        .json(&body)
        .send()
        .await
        .map_err(|e| anyhow!("请求失败：{e}"))?;

    let status = resp.status();
    let text = resp
        .text()
        .await
        .map_err(|e| anyhow!("读取响应体失败：{e}"))?;

    if !status.is_success() {
        // 给出 4xx/5xx 原文，便于用户定位（比如 401 = key 错）
        return Err(anyhow!("{status}：{text}"));
    }

    let parsed: ChatResp = serde_json::from_str(&text).map_err(|e| {
        anyhow!("响应解析失败：{e} / body={text}")
    })?;

    parsed
        .choices
        .into_iter()
        .next()
        .map(|c| c.message.content)
        .filter(|s| !s.is_empty())
        .ok_or_else(|| anyhow!("AI 返回了 0 条内容"))
}

// ---------------- embeddings ----------------

#[derive(Debug, Serialize)]
struct EmbedReq<'a> {
    model: &'a str,
    input: &'a [String],
}

#[derive(Debug, Deserialize)]
struct EmbedResp {
    #[serde(default)]
    data: Vec<EmbedData>,
}

#[derive(Debug, Deserialize)]
struct EmbedData {
    #[serde(default)]
    embedding: Vec<f32>,
    #[serde(default)]
    index: usize,
}

/// 调用 `{base_url}/embeddings` 拿向量。
///
/// 协议是 OpenAI 标准（`{model, input: [strings]}` → `{data: [{embedding, index}]}`），
/// OpenAI / 兼容方都按这个出。DeepSeek 目前不提供 embedding 端点 —— 调用方应该用
/// OpenAI / Xiaomi MiMo provider 才能拿到结果。
///
/// 上限 1024 条 input：批量调用时切片由调用方负责，本函数只发一次。
pub async fn embeddings(
    api_key: &str,
    base_url: &str,
    model: &str,
    inputs: &[String],
) -> Result<Vec<Vec<f32>>> {
    let key = api_key.trim();
    if key.is_empty() {
        return Err(anyhow!("还没填 API key，请在设置里填上"));
    }
    if inputs.is_empty() {
        return Ok(Vec::new());
    }

    let url = format!("{}/embeddings", base_url.trim_end_matches('/'));
    let client = Client::builder()
        .timeout(Duration::from_secs(60))
        .build()
        .map_err(|e| anyhow!("构造 http client 失败：{e}"))?;

    let body = EmbedReq { model, input: inputs };
    let resp = client
        .post(&url)
        .bearer_auth(key)
        .json(&body)
        .send()
        .await
        .map_err(|e| anyhow!("请求失败：{e}"))?;

    let status = resp.status();
    let text = resp.text().await.map_err(|e| anyhow!("读取响应体失败：{e}"))?;
    if !status.is_success() {
        return Err(anyhow!("{status}：{text}"));
    }

    let parsed: EmbedResp = serde_json::from_str(&text)
        .map_err(|e| anyhow!("响应解析失败：{e} / body={text}"))?;

    // 按 index 排回原顺序——OpenAI 实际返回是按 input 顺序，但保险起见显式排
    let mut indexed: Vec<(usize, Vec<f32>)> = parsed
        .data
        .into_iter()
        .map(|d| (d.index, d.embedding))
        .collect();
    indexed.sort_by_key(|(i, _)| *i);
    Ok(indexed.into_iter().map(|(_, v)| v).collect())
}
