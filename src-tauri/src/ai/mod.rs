//! AI 接入层。
//!
//! 当前接四类 OpenAI 兼容 provider：DeepSeek / OpenAI / 小米 MiMo / 自定义中转。
//! 设计原则：
//!   - API key 在本机 `ai_config.json` 里，跟 netease cookie 同一个 app_config_dir，
//!     unix 下 chmod 0600。不上传、不走远端存储。
//!   - key 永远不整体暴露给前端 —— 前端只拿 `hasKey` + `sk-***1234` 预览。
//!   - 切 provider 不会清掉别家 key，让用户能反复切回来对比。
//!   - 所有 provider 走同一份 `openai_compat::chat`（OpenAI 兼容协议），官方 provider
//!     走固定 base_url，自定义 provider 使用用户填写的中转站地址。

pub mod cmds;
pub mod config;
pub mod openai_compat;

pub use config::AiConfigStore;
