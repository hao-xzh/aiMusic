//! AI 接入层。
//!
//! 当前只接 DeepSeek（OpenAI 兼容 chat/completions，便宜、国内延迟低）。
//! 设计原则：
//!   - API key 在本机 `ai_config.json` 里，跟 netease cookie 同一个 app_config_dir，
//!     unix 下 chmod 0600。不上传、不走远端存储。
//!   - key 永远不整体暴露给前端 —— 前端只拿 `hasKey` + `sk-***1234` 预览。
//!   - 后续要接别的模型（Anthropic / OpenAI 亲儿子），加一个 provider 字段即可。

pub mod cmds;
pub mod config;
pub mod deepseek;

pub use config::AiConfigStore;
