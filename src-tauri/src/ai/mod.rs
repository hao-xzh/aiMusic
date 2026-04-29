//! AI 接入层。
//!
//! 当前接三家 OpenAI 兼容 provider：DeepSeek / OpenAI / 小米 MiMo。
//! 设计原则：
//!   - API key 在本机 `ai_config.json` 里，跟 netease cookie 同一个 app_config_dir，
//!     unix 下 chmod 0600。不上传、不走远端存储。
//!   - key 永远不整体暴露给前端 —— 前端只拿 `hasKey` + `sk-***1234` 预览。
//!   - 切 provider 不会清掉别家 key，让用户能反复切回来对比。
//!   - 所有 provider 走同一份 `openai_compat::chat`（OpenAI 兼容协议），新增 provider 只要
//!     在 `config::Provider` 加一项 + 在 `default_base_url`/`known_models` 各补一支即可。

pub mod cmds;
pub mod config;
pub mod openai_compat;

pub use config::AiConfigStore;
