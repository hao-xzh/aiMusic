//! DeepSeek chat/completions 调用。
//!
//! 走 OpenAI 兼容协议，POST `{base_url}/chat/completions`，Bearer 认证。
//! 当前不做流式（stream=false），一次性拿 content 文本。后续要接"边生成边
//! TTS"再升级到 SSE。

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
        return Err(anyhow!("还没填 DeepSeek API key，请在设置里填上"));
    }

    let url = format!(
        "{}/chat/completions",
        base_url.trim_end_matches('/')
    );

    let client = Client::builder()
        .timeout(Duration::from_secs(30))
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
        .map_err(|e| anyhow!("DeepSeek 请求失败：{e}"))?;

    let status = resp.status();
    let text = resp
        .text()
        .await
        .map_err(|e| anyhow!("读取 DeepSeek 响应体失败：{e}"))?;

    if !status.is_success() {
        // 给出 4xx/5xx 原文，便于用户定位（比如 401 = key 错）
        return Err(anyhow!("DeepSeek {status}：{text}"));
    }

    let parsed: ChatResp = serde_json::from_str(&text).map_err(|e| {
        anyhow!("DeepSeek 响应解析失败：{e} / body={text}")
    })?;

    parsed
        .choices
        .into_iter()
        .next()
        .map(|c| c.message.content)
        .filter(|s| !s.is_empty())
        .ok_or_else(|| anyhow!("DeepSeek 返回了 0 条内容"))
}
