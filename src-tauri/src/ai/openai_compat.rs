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
