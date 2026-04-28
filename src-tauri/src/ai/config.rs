//! AI 配置持久化。
//!
//! 落盘位置：`<app_config_dir>/ai_config.json`，跟 netease cookie 同目录。
//! 写入策略：tmp + rename 原子换；unix 下把 tmp 文件 chmod 0600 再 rename。
//! 读取策略：启动时 load 一次；运行期内改是更新内存 + 再写一次文件。

use std::path::PathBuf;
use std::sync::RwLock;

use anyhow::{Context, Result};
use serde::{Deserialize, Serialize};

/// 用户可配置的 AI 参数。`deepseek_api_key` 为 None / 空串时视作"没配"。
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AiConfig {
    #[serde(default)]
    pub deepseek_api_key: Option<String>,
    #[serde(default = "default_model")]
    pub model: String,
    #[serde(default = "default_base_url")]
    pub base_url: String,
}

fn default_model() -> String {
    // 按用户指定：走 DeepSeek V4 flash（快、便宜，适合 DJ 旁白这类短 prompt）
    "deepseek-v4-flash".to_string()
}
fn default_base_url() -> String {
    // DeepSeek 官方 OpenAI 兼容入口。/v1 可省可留，留着语义更清楚。
    "https://api.deepseek.com/v1".to_string()
}

impl Default for AiConfig {
    fn default() -> Self {
        Self {
            deepseek_api_key: None,
            model: default_model(),
            base_url: default_base_url(),
        }
    }
}

/// 全进程共享的 AI 配置。外部拿 Arc 克隆引用，内部 RwLock 保护。
pub struct AiConfigStore {
    inner: RwLock<AiConfig>,
    path: PathBuf,
}

impl AiConfigStore {
    pub fn load(path: PathBuf) -> Result<Self> {
        let cfg = if path.exists() {
            match std::fs::read_to_string(&path) {
                Ok(txt) => serde_json::from_str::<AiConfig>(&txt).unwrap_or_else(|e| {
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
        Ok(Self {
            inner: RwLock::new(cfg),
            path,
        })
    }

    pub fn snapshot(&self) -> AiConfig {
        self.inner.read().expect("ai config poisoned").clone()
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
