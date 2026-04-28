//! 带 cookie jar 的 reqwest 客户端 + weapi POST 辅助方法。
//!
//! 关键细节：
//!   - 种子 cookie `os=pc` —— weapi 以此判断走桌面端分支（返回结构更干净，直链更全）。
//!   - 保留 cookie jar 的 Arc，后面 `netease_login_cookies()` 要能把 MUSIC_U 之类的 cookie
//!     dump 出来（持久化登录用）。
//!   - **持久化登录**：构造时传入一个 `cookies.json` 路径，启动时读回，登录成功后 `persist()`
//!     把所有 cookie（**含 session cookie**）写回去。用 `save_incl_expired_and_nonpersistent`
//!     是因为网易云的 `__csrf` 在 Set-Cookie 里没有 Expires，属于 session cookie；
//!     如果只 `save()` 就会漏，下次开启又得重扫码。
//!   - 未登录时 `__csrf` cookie 不存在，weapi 接受空 csrf_token，不用特殊处理。

use std::path::PathBuf;
use std::sync::Arc;

use anyhow::{anyhow, Context, Result};
use reqwest::Client;
use reqwest_cookie_store::CookieStoreMutex;
use serde::de::DeserializeOwned;
use serde_json::{json, Value};
use url::Url;

use super::crypto::weapi_encrypt;

// 模拟官方 PC 客户端的 UA —— Safari UA 会被部分端点 (-462) 打回。
const UA: &str = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 \
                  (KHTML, like Gecko) Chrome/118.0.0.0 Safari/537.36";
const HOST: &str = "https://music.163.com";

/// "realIP" 业务参数：网易云风控信这个参数里的 IP，不看 TCP 源。
/// 随便填一个中国大陆公网 IP 就能绕过 `-462 风控触发`。
/// （这是社区通行做法，YesPlayMusic / NeteaseCloudMusicApi 也这么干。）
const FAKE_REAL_IP: &str = "116.25.146.177";

pub struct NeteaseClient {
    http: Client,
    cookies: Arc<CookieStoreMutex>,
    /// 持久化 cookie 的目标文件。None = 不落盘（单元测试 / 临时实例）。
    persist_path: Option<PathBuf>,
}

impl NeteaseClient {
    /// 不带持久化 —— 测试 / 探针用。
    pub fn new() -> Result<Self> {
        Self::new_with_persist(None)
    }

    /// 带持久化。第一次启动 `persist_path` 指向的文件不存在时，视作"未登录"。
    pub fn new_with_persist(persist_path: Option<PathBuf>) -> Result<Self> {
        // 1. 先尝试从磁盘 load 之前保存下来的 cookie（MUSIC_U / __csrf 等）。
        //    必须用 load_all：__csrf 是 session cookie，普通 load() 会跳过它。
        let mut store = match persist_path.as_ref().filter(|p| p.exists()) {
            Some(p) => match std::fs::File::open(p)
                .context("open cookies.json")
                .and_then(|f| {
                    cookie_store::serde::json::load_all(std::io::BufReader::new(f))
                        .map_err(|e| anyhow!("parse cookies.json: {e}"))
                }) {
                Ok(s) => s,
                Err(e) => {
                    // 坏掉的 cookies.json 不应该让 app 起不来；打个 warn 重新建空 store。
                    eprintln!("[netease] cookie load failed, starting fresh: {e:#}");
                    cookie_store::CookieStore::new(None)
                }
            },
            _ => cookie_store::CookieStore::new(None),
        };

        // 2. 再把 PC 身份种子 cookie 插进去（存在就覆盖；不存在就新增）。
        //    这些是网易云 PC 客户端每次请求都带的"身份信标"，跟 realIP 一起骗过 -462。
        let seed_url = Url::parse(HOST)?;
        let seeds = [
            "os=pc",
            "osver=Microsoft-Windows-10-Professional-build-22631",
            "appver=2.10.2",
            "channel=netease",
            "versioncode=140",
            "buildver=1700000000",
            "mobilename=",
            "resolution=1920x1080",
        ];
        for c in seeds {
            store
                .parse(&format!("{c}; Path=/"), &seed_url)
                .map_err(|e| anyhow!("seed {c}: {e}"))?;
        }

        let cookies = Arc::new(CookieStoreMutex::new(store));

        let http = Client::builder()
            .cookie_provider(cookies.clone())
            .user_agent(UA)
            .gzip(true)
            .build()
            .context("build reqwest client")?;

        Ok(Self {
            http,
            cookies,
            persist_path,
        })
    }

    /// 把当前 cookie jar 写回磁盘。登录成功（qr_check 803）后调用一次即可。
    /// 用 `save_incl_expired_and_nonpersistent` 是因为 `__csrf` 没有 Expires，
    /// 属于 session cookie；普通 save 会漏掉它，下次开启 weapi 又得重新登录。
    pub fn persist(&self) -> Result<()> {
        let Some(path) = self.persist_path.as_ref() else {
            return Ok(());
        };
        if let Some(dir) = path.parent() {
            std::fs::create_dir_all(dir).ok();
        }
        let store = self
            .cookies
            .lock()
            .map_err(|_| anyhow!("cookie store poisoned"))?;
        // 写到 tmp 再 rename，避免写一半被 kill 导致 json 损坏。
        let tmp = path.with_extension("json.tmp");
        let mut file =
            std::fs::File::create(&tmp).with_context(|| format!("create {}", tmp.display()))?;
        cookie_store::serde::json::save_incl_expired_and_nonpersistent(&store, &mut file)
            .map_err(|e| anyhow!("serialize cookies: {e}"))?;
        drop(file);
        std::fs::rename(&tmp, path)
            .with_context(|| format!("rename {} -> {}", tmp.display(), path.display()))?;
        Ok(())
    }

    /// 内部用：发一个 weapi POST。`endpoint` 不带前导 `/`，例如 `"login/qrcode/unikey"`。
    pub async fn weapi<T: DeserializeOwned>(
        &self,
        endpoint: &str,
        mut params: Value,
    ) -> Result<T> {
        // 注入两个"身份"参数：
        //   - csrf_token: 未登录时空串，登录后从 cookie 里拿
        //   - realIP:     告诉风控这是大陆来源，否则 -462
        if let Some(obj) = params.as_object_mut() {
            if !obj.contains_key("csrf_token") {
                let csrf = self.cookie_value("__csrf").unwrap_or_default();
                obj.insert("csrf_token".into(), json!(csrf));
            }
            if !obj.contains_key("realIP") {
                obj.insert("realIP".into(), json!(FAKE_REAL_IP));
            }
        }

        let body = weapi_encrypt(&params);
        let url = format!("{HOST}/weapi/{}", endpoint.trim_start_matches('/'));

        let resp = self
            .http
            .post(&url)
            .header("Referer", format!("{HOST}/"))
            .header("Origin", HOST)
            .form(&[("params", body.params), ("encSecKey", body.enc_sec_key)])
            .send()
            .await
            .with_context(|| format!("POST {url}"))?;

        let status = resp.status();
        let text = resp.text().await.context("read body")?;
        if !status.is_success() {
            return Err(anyhow!(
                "netease {endpoint} -> {status}: {}",
                truncate(&text, 400)
            ));
        }
        serde_json::from_str::<T>(&text)
            .with_context(|| format!("parse {endpoint} response: {}", truncate(&text, 400)))
    }

    /// 从 cookie jar 里读某个 cookie 的 value。
    pub fn cookie_value(&self, name: &str) -> Option<String> {
        let store = self.cookies.lock().ok()?;
        // 先 materialize 到 owned String，避免 iter_any() 返回的临时迭代器
        // 在 `store` 被 drop 之后还被访问（rustc 的借用检查会报 E0597）。
        let found = store
            .iter_any()
            .find(|c| c.name() == name)
            .map(|c| c.value().to_string());
        found
    }

    /// 导出所有 music.163.com 下的 cookie，给前端做登录状态判定 / 将来持久化。
    pub fn dump_cookies(&self) -> Vec<(String, String)> {
        let Ok(store) = self.cookies.lock() else {
            return vec![];
        };
        store
            .iter_any()
            .map(|c| (c.name().to_string(), c.value().to_string()))
            .collect()
    }
}

fn truncate(s: &str, n: usize) -> String {
    if s.len() <= n {
        s.to_string()
    } else {
        format!("{}…", &s[..n])
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 模拟"登录成功 → 重启 app → 能认出已登录"这个链路：
    ///   1) 拿一个带持久化路径的 client
    ///   2) 往 cookie jar 里塞一个 MUSIC_U（session cookie，没有 Max-Age）
    ///   3) persist() 写盘
    ///   4) 新建第二个 client 指同一路径
    ///   5) 断言能读出 MUSIC_U
    ///
    /// 如果持久化的是 `save()` 而不是 `save_incl_expired_and_nonpersistent()`，
    /// MUSIC_U / __csrf 这类 session cookie 就会丢，这个测试会挂。
    #[test]
    fn session_cookies_survive_restart() {
        let tmp = std::env::temp_dir().join(format!(
            "claudio-cookies-test-{}.json",
            std::process::id()
        ));
        let _ = std::fs::remove_file(&tmp);

        // round 1: 塞 cookie，落盘
        {
            let c = NeteaseClient::new_with_persist(Some(tmp.clone())).unwrap();
            {
                let mut store = c.cookies.lock().unwrap();
                let url = Url::parse("https://music.163.com").unwrap();
                store
                    .parse("MUSIC_U=fake_music_u_value; Path=/", &url)
                    .unwrap();
                store
                    .parse("__csrf=fake_csrf_value; Path=/", &url)
                    .unwrap();
            }
            c.persist().expect("persist");
        }

        // round 2: 重建 client，应该看得到这两个 cookie
        {
            let c = NeteaseClient::new_with_persist(Some(tmp.clone())).unwrap();
            assert_eq!(
                c.cookie_value("MUSIC_U").as_deref(),
                Some("fake_music_u_value"),
                "MUSIC_U 没从 cookies.json 恢复 —— 持久化链路断了"
            );
            assert_eq!(
                c.cookie_value("__csrf").as_deref(),
                Some("fake_csrf_value"),
                "__csrf 是 session cookie，必须用 save_incl_expired_and_nonpersistent"
            );
            // 种子 cookie 也应该还在（被重新 seed 一次）
            assert_eq!(c.cookie_value("os").as_deref(), Some("pc"));
        }

        let _ = std::fs::remove_file(&tmp);
    }

    /// 文件不存在 = 第一次启动 = 未登录。不应该 panic。
    #[test]
    fn fresh_start_no_cookie_file() {
        let tmp = std::env::temp_dir().join(format!(
            "claudio-cookies-none-{}.json",
            std::process::id()
        ));
        let _ = std::fs::remove_file(&tmp);
        let c = NeteaseClient::new_with_persist(Some(tmp)).unwrap();
        assert!(c.cookie_value("MUSIC_U").is_none());
        assert_eq!(c.cookie_value("os").as_deref(), Some("pc")); // seed 仍在
    }
}
