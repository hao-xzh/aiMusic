//! OpenAI 兼容 chat/completions 调用。
//!
//! 这些 provider 都是 OpenAI-compatible（DeepSeek / OpenAI / 小米 MiMo / 自定义中转）：
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
use serde_json::{json, Value};

const CHAT_TIMEOUT_SECS: u64 = 180;

#[derive(Debug, Clone, Serialize)]
pub struct ChatMessage {
    pub role: String,
    pub content: String,
}

#[derive(Debug, Serialize)]
struct ChatReq<'a> {
    model: &'a str,
    messages: &'a [ChatMessage],
    // OpenAI GPT-5 / o 系只认 max_completion_tokens 且拒绝自定义 temperature；
    // DeepSeek / MiMo / 老款 GPT 用 max_tokens + temperature。按模型族二选一，None 不发。
    #[serde(skip_serializing_if = "Option::is_none")]
    temperature: Option<f32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    max_tokens: Option<u32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    max_completion_tokens: Option<u32>,
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

/// OpenAI 的 GPT-5 系与 o 系推理模型有两条与 DeepSeek 不同的硬约束：
///   - 不收 `max_tokens`，必须用 `max_completion_tokens`
///   - 不收自定义 `temperature`，只接受默认值（必须整个不发，发了 0.x 直接 400）
/// DeepSeek / MiMo / 老款 GPT 不受此限。靠模型名前缀判定即可——DeepSeek=deepseek-*、
/// MiMo=mimo-* 都不会误命中 gpt-5 / o1 / o3 / o4。
fn is_openai_restricted(model: &str) -> bool {
    let m = model.trim().to_ascii_lowercase();
    m.starts_with("gpt-5") || m.starts_with("o1") || m.starts_with("o3") || m.starts_with("o4")
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

    let url = format!("{}/chat/completions", base_url.trim_end_matches('/'));

    let client = Client::builder()
        .timeout(Duration::from_secs(CHAT_TIMEOUT_SECS))
        .build()
        .map_err(|e| anyhow!("构造 http client 失败：{e}"))?;

    let restricted = is_openai_restricted(model);
    let body = ChatReq {
        model,
        messages,
        temperature: if restricted { None } else { Some(temperature) },
        max_tokens: if restricted { None } else { Some(max_tokens) },
        max_completion_tokens: if restricted { Some(max_tokens) } else { None },
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

    let parsed: ChatResp =
        serde_json::from_str(&text).map_err(|e| anyhow!("响应解析失败：{e} / body={text}"))?;

    parsed
        .choices
        .into_iter()
        .next()
        .map(|c| c.message.content)
        .filter(|s| !s.is_empty())
        .ok_or_else(|| anyhow!("AI 返回了 0 条内容"))
}

// ---------------- tool calling ----------------

/// 原生 function-calling 版的 chat。和 [`chat`] 共用 `/chat/completions` 端点，
/// 但 body 带 `tools` + `tool_choice:"auto"`，并把 `choices[0].message` **原样**回传
/// （含 `content` + `tool_calls`），让调用方在 Kotlin 侧驱动多轮工具循环。
///
/// `messages` / `tools` 由调用方以原始 JSON（数组）传入——schema 归调用方所有，
/// 这里只做透传，避免在 Rust 端重复建模每个工具字段。
///
/// 注意：assistant 决定调用工具时 `content` 可能为空但 `tool_calls` 非空，这是**合法**
/// 状态，所以这里不像 [`chat`] 那样过滤空 content；只要存在 `choices[0].message` 就返回。
///
/// 为什么和 [`chat`] 分开而不是加可选参数：`chat` 是 Android + 桌面 Tauri 共享调用，
/// 签名稳定优先；新增独立函数对老调用方零影响。
pub async fn chat_tools(
    api_key: &str,
    base_url: &str,
    model: &str,
    messages: Value,
    tools: Value,
    temperature: f32,
    max_tokens: u32,
) -> Result<Value> {
    let key = api_key.trim();
    if key.is_empty() {
        return Err(anyhow!("还没填 API key，请在设置里填上"));
    }

    let url = format!("{}/chat/completions", base_url.trim_end_matches('/'));

    let client = Client::builder()
        .timeout(Duration::from_secs(CHAT_TIMEOUT_SECS))
        .build()
        .map_err(|e| anyhow!("构造 http client 失败：{e}"))?;

    let restricted = is_openai_restricted(model);
    let mut body = json!({
        "model": model,
        "messages": messages,
        "stream": false,
    });
    if let Some(obj) = body.as_object_mut() {
        // GPT-5 / o 系只认 max_completion_tokens 且拒绝 temperature；其余用 max_tokens + temperature。
        if restricted {
            obj.insert("max_completion_tokens".to_string(), json!(max_tokens));
        } else {
            obj.insert("max_tokens".to_string(), json!(max_tokens));
            obj.insert("temperature".to_string(), json!(temperature));
        }
        // tools 为空数组 / null 时不带，等价于普通 chat（让最后一轮"纯文本收尾"也能走这条）
        let has_tools = tools.as_array().map(|a| !a.is_empty()).unwrap_or(false);
        if has_tools {
            obj.insert("tools".to_string(), tools);
            obj.insert("tool_choice".to_string(), json!("auto"));
        }
        // 对齐 chat()：DeepSeek 关掉 reasoning，省 latency / token
        if base_url.trim_end_matches('/') == "https://api.deepseek.com" {
            obj.insert("thinking".to_string(), json!({ "type": "disabled" }));
        }
    }

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
        return Err(anyhow!("{status}：{text}"));
    }

    let parsed: Value =
        serde_json::from_str(&text).map_err(|e| anyhow!("响应解析失败：{e} / body={text}"))?;

    parsed
        .get("choices")
        .and_then(|c| c.get(0))
        .and_then(|c| c.get("message"))
        .cloned()
        .ok_or_else(|| anyhow!("AI 返回里没有 choices[0].message / body={text}"))
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

    let body = EmbedReq {
        model,
        input: inputs,
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
        return Err(anyhow!("{status}：{text}"));
    }

    let parsed: EmbedResp =
        serde_json::from_str(&text).map_err(|e| anyhow!("响应解析失败：{e} / body={text}"))?;

    // 按 index 排回原顺序——OpenAI 实际返回是按 input 顺序，但保险起见显式排
    let mut indexed: Vec<(usize, Vec<f32>)> = parsed
        .data
        .into_iter()
        .map(|d| (d.index, d.embedding))
        .collect();
    indexed.sort_by_key(|(i, _)| *i);
    Ok(indexed.into_iter().map(|(_, v)| v).collect())
}
