//! AI 配置持久化（多 provider 版）。
//!
//! 落盘位置：`<app_config_dir>/ai_config.json`，跟 netease cookie 同目录。
//! 写入策略：tmp + rename 原子换；unix 下把 tmp 文件 chmod 0600 再 rename。
//!
//! 多 provider 设计：
//!   - 每个 provider 有自己的一份 `ProviderSlot`（key + 选中的 model）
//!   - 顶层 `provider` 字段记录当前用谁；切 provider 不会清掉别家的 key，让用户能反复切回去
//!   - base_url 写死在 `default_base_url()`（全部 OpenAI 兼容），不暴露给用户改 ——
//!     避免用户填错把流量打到不存在的端点
//!
//! 兼容老配置：
//!   早版本只存了 `deepseek_api_key + model + base_url` 三个 flat 字段。读到老 schema
//!   时把它当作 deepseek slot 的内容迁移进去，保留原 model 选择，避免用户掉 key。

use std::path::PathBuf;
use std::sync::RwLock;

use anyhow::{Context, Result};
use serde::{Deserialize, Serialize};

/// 当前支持的 provider。新增就加一项 + 在 default_base_url / default_model 各加一支。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum Provider {
    Deepseek,
    Openai,
    XiaomiMimo,
}

impl Provider {
    pub const ALL: [Provider; 3] = [Provider::Deepseek, Provider::Openai, Provider::XiaomiMimo];

    pub fn as_key(self) -> &'static str {
        match self {
            Provider::Deepseek => "deepseek",
            Provider::Openai => "openai",
            Provider::XiaomiMimo => "xiaomi-mimo",
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ProviderSlot {
    #[serde(default)]
    pub api_key: Option<String>,
    pub model: String,
}

impl ProviderSlot {
    pub fn new(default_model: &str) -> Self {
        Self {
            api_key: None,
            model: default_model.to_string(),
        }
    }
}

/// 用户可配置的 AI 参数。
///
/// 注意：base_url 不再可改 —— 走 default_base_url(provider)，前端只暴露 provider + model。
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AiConfig {
    #[serde(default = "default_provider")]
    pub provider: Provider,
    pub deepseek: ProviderSlot,
    pub openai: ProviderSlot,
    #[serde(rename = "xiaomi_mimo")]
    pub xiaomi_mimo: ProviderSlot,
}

fn default_provider() -> Provider {
    Provider::Deepseek
}

impl Default for AiConfig {
    fn default() -> Self {
        Self {
            provider: default_provider(),
            deepseek: ProviderSlot::new(default_model(Provider::Deepseek)),
            openai: ProviderSlot::new(default_model(Provider::Openai)),
            xiaomi_mimo: ProviderSlot::new(default_model(Provider::XiaomiMimo)),
        }
    }
}

/// 各家 provider 的官方 OpenAI 兼容入口。
///
/// 注意路径细节：
///   - DeepSeek 官方 base 是 `https://api.deepseek.com`（**不带 /v1**），
///     openai_compat::chat 会拼出 `/chat/completions`。早版本配的 /v1 在 V4 端点上
///     直接被拒，是 ai_ping 失败的根因。
///   - OpenAI 标准就是 `/v1`。
///   - 小米 MiMo 用 `/v1`（官网文档示例就是 `https://api.xiaomimimo.com/v1`）。
pub fn default_base_url(provider: Provider) -> &'static str {
    match provider {
        Provider::Deepseek => "https://api.deepseek.com",
        Provider::Openai => "https://api.openai.com/v1",
        Provider::XiaomiMimo => "https://api.xiaomimimo.com/v1",
    }
}

/// 各家 provider 的「兜底默认模型」—— 当用户没选时用这个。
/// 选的是性价比 / 速度均衡的中档款，不是最贵的旗舰，避免一上手就烧钱。
pub fn default_model(provider: Provider) -> &'static str {
    match provider {
        Provider::Deepseek => "deepseek-v4-flash",
        Provider::Openai => "gpt-5.4-mini",
        Provider::XiaomiMimo => "mimo-v2-flash",
    }
}

/// 已知的可选模型列表（高 → 低）。前端 dropdown 用。
///
/// 来源：各家 2026-04 官网 / 文档（详见 issue 里的搜索记录）。
/// 顺序刻意从「旗舰最贵」排到「速度便宜」，让用户一眼看出梯队。
pub fn known_models(provider: Provider) -> &'static [(&'static str, &'static str)] {
    match provider {
        Provider::Deepseek => &[
            ("deepseek-v4-pro", "V4 Pro · 旗舰"),
            ("deepseek-v4-flash", "V4 Flash · 性价比"),
        ],
        Provider::Openai => &[
            ("gpt-5.5-pro", "GPT-5.5 Pro · 旗舰"),
            ("gpt-5.5", "GPT-5.5 · 旗舰"),
            ("gpt-5.4-pro", "GPT-5.4 Pro"),
            ("gpt-5.4", "GPT-5.4"),
            ("gpt-5.4-mini", "GPT-5.4 Mini · 性价比"),
            ("gpt-5.4-nano", "GPT-5.4 Nano · 速度优先"),
        ],
        Provider::XiaomiMimo => &[
            ("mimo-v2-pro", "MiMo V2 Pro · 旗舰 · 1M 上下文"),
            ("mimo-v2-omni", "MiMo V2 Omni · 多模态"),
            ("mimo-v2-flash", "MiMo V2 Flash · 性价比"),
        ],
    }
}

/// 全进程共享的 AI 配置。外部拿 Arc 克隆引用，内部 RwLock 保护。
pub struct AiConfigStore {
    inner: RwLock<AiConfig>,
    path: PathBuf,
}

impl AiConfigStore {
    pub fn load(path: PathBuf) -> Result<Self> {
        let mut cfg = if path.exists() {
            match std::fs::read_to_string(&path) {
                Ok(txt) => parse_with_legacy_fallback(&txt).unwrap_or_else(|e| {
                    eprintln!(
                        "[ai] {} 解析失败，回落到 default：{e}",
                        path.display()
                    );
                    AiConfig::default()
                }),
                Err(e) => {
                    eprintln!("[ai] {} 读取失败：{e}", path.display());
                    AiConfig::default()
                }
            }
        } else {
            AiConfig::default()
        };
        migrate_legacy_models(&mut cfg);
        let store = Self {
            inner: RwLock::new(cfg),
            path,
        };
        // 迁移完落盘一次，下次启动直接干净状态
        let _ = store.persist();
        Ok(store)
    }

    pub fn snapshot(&self) -> AiConfig {
        self.inner.read().expect("ai config poisoned").clone()
    }

    /// 当前激活 provider 的运行时三元组：(api_key, base_url, model)
    /// chat 调用都走这个，统一一处。
    pub fn active(&self) -> (Option<String>, &'static str, String) {
        let cfg = self.snapshot();
        let slot = match cfg.provider {
            Provider::Deepseek => cfg.deepseek,
            Provider::Openai => cfg.openai,
            Provider::XiaomiMimo => cfg.xiaomi_mimo,
        };
        (
            slot.api_key.filter(|s| !s.trim().is_empty()),
            default_base_url(cfg.provider),
            slot.model,
        )
    }

    pub fn update<F: FnOnce(&mut AiConfig)>(&self, f: F) -> Result<()> {
        {
            let mut g = self.inner.write().expect("ai config poisoned");
            f(&mut g);
        }
        self.persist()
    }

    fn persist(&self) -> Result<()> {
        let cfg = self.snapshot();
        let tmp = self.path.with_extension("json.tmp");
        if let Some(parent) = self.path.parent() {
            std::fs::create_dir_all(parent).ok();
        }
        let txt = serde_json::to_string_pretty(&cfg)
            .context("serialize ai config")?;
        std::fs::write(&tmp, &txt)
            .with_context(|| format!("write tmp {}", tmp.display()))?;

        // unix：把文件权限收到 0600，避免别的进程读到 key
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let perms = std::fs::Permissions::from_mode(0o600);
            if let Err(e) = std::fs::set_permissions(&tmp, perms) {
                eprintln!("[ai] chmod 0600 失败（不致命）：{e}");
            }
        }

        std::fs::rename(&tmp, &self.path).with_context(|| {
            format!("rename {} -> {}", tmp.display(), self.path.display())
        })?;
        Ok(())
    }
}

/// 老 schema：`{ "deepseek_api_key": "...", "model": "...", "base_url": "..." }`
/// 读出来当作 deepseek slot 用，不丢用户原来的 key。
#[derive(Deserialize)]
struct LegacyConfig {
    #[serde(default)]
    deepseek_api_key: Option<String>,
    #[serde(default)]
    model: Option<String>,
}

/// V4 上线后 deepseek-chat / deepseek-reasoner 在 API 端是 v4-flash 的兼容别名，
/// 但前端 dropdown 里已经看不到了，会显示成"（自定义）"。统一推到 v4-flash，
/// 让设置页里看着干净；这俩别名 V4 API 仍然接受，所以即使没被本地迁移，测试也不会挂。
fn migrate_legacy_models(cfg: &mut AiConfig) {
    if matches!(
        cfg.deepseek.model.as_str(),
        "deepseek-chat" | "deepseek-reasoner"
    ) {
        cfg.deepseek.model = default_model(Provider::Deepseek).to_string();
    }
}

fn parse_with_legacy_fallback(txt: &str) -> Result<AiConfig> {
    // 先按新 schema 试
    if let Ok(c) = serde_json::from_str::<AiConfig>(txt) {
        return Ok(c);
    }
    // 回落老 schema 迁移
    let legacy: LegacyConfig = serde_json::from_str(txt)
        .context("ai_config.json 既不是新 schema 也不是老 schema")?;
    let mut cfg = AiConfig::default();
    if let Some(k) = legacy.deepseek_api_key.filter(|s| !s.trim().is_empty()) {
        cfg.deepseek.api_key = Some(k);
    }
    if let Some(m) = legacy.model.filter(|s| !s.trim().is_empty()) {
        cfg.deepseek.model = m;
    }
    eprintln!("[ai] 检测到老版本 ai_config.json，迁移到 multi-provider schema");
    Ok(cfg)
}
