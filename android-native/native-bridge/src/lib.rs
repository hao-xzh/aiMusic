use jni::objects::{JObject, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use serde_json::{json, Value};
use std::path::PathBuf;
use std::sync::OnceLock;
use tokio::runtime::Runtime;

mod netease;
mod ai;
mod audio;

static APP_DATA_DIR: OnceLock<PathBuf> = OnceLock::new();

fn runtime() -> &'static Runtime {
    static RUNTIME: OnceLock<Runtime> = OnceLock::new();
    RUNTIME.get_or_init(|| Runtime::new().expect("create pipo native tokio runtime"))
}

fn netease_client() -> &'static netease::NeteaseClient {
    static CLIENT: OnceLock<netease::NeteaseClient> = OnceLock::new();
    CLIENT.get_or_init(|| {
        let cookie_path = APP_DATA_DIR
            .get()
            .map(|dir| dir.join("netease_cookies.json"));
        netease::NeteaseClient::new_with_persist(cookie_path).expect("create netease client")
    })
}

fn ai_store() -> &'static ai::config::AiConfigStore {
    static STORE: OnceLock<ai::config::AiConfigStore> = OnceLock::new();
    STORE.get_or_init(|| {
        let path = APP_DATA_DIR
            .get()
            .cloned()
            .unwrap_or_else(std::env::temp_dir)
            .join("ai_config.json");
        ai::config::AiConfigStore::load(path).expect("create ai config store")
    })
}

fn audio_store() -> &'static audio::NativeAudioStore {
    static STORE: OnceLock<audio::NativeAudioStore> = OnceLock::new();
    STORE.get_or_init(|| {
        let dir = APP_DATA_DIR
            .get()
            .cloned()
            .unwrap_or_else(std::env::temp_dir);
        audio::NativeAudioStore::new(dir).expect("create native audio store")
    })
}

#[no_mangle]
pub extern "system" fn Java_app_pipo_nativeapp_data_JsonRustPipoBridge_invokeNative(
    mut env: JNIEnv,
    _this: JObject,
    command: JString,
    args_json: JString,
) -> jstring {
    let command = match env.get_string(&command) {
        Ok(s) => s.to_string_lossy().to_string(),
        Err(e) => return string_or_null(&mut env, &error_json(format!("read command: {e}"))),
    };
    let args_json = match env.get_string(&args_json) {
        Ok(s) => s.to_string_lossy().to_string(),
        Err(e) => return string_or_null(&mut env, &error_json(format!("read args: {e}"))),
    };
    let args = serde_json::from_str::<Value>(&args_json).unwrap_or(Value::Null);
    let out = dispatch(&command, args);
    string_or_null(&mut env, &out)
}

fn dispatch(command: &str, args: Value) -> String {
    match command {
        "bridge_init" => {
            if let Some(dir) = args.get("appDataDir").and_then(Value::as_str) {
                let _ = APP_DATA_DIR.set(PathBuf::from(dir));
            }
            "null".to_string()
        }
        "netease_account" => run_json(async {
            let account = netease_client().account().await?;
            Ok(serde_json::to_value(account)?)
        }),
        "netease_user_playlists" => {
            let uid = args.get("uid").and_then(Value::as_i64).unwrap_or(0);
            let limit = args.get("limit").and_then(Value::as_i64).unwrap_or(1000);
            run_json(async move {
                let playlists = netease_client().user_playlists(uid, limit).await?;
                Ok(serde_json::to_value(playlists)?)
            })
        }
        "netease_playlist_detail" => {
            let id = args.get("id").and_then(Value::as_i64).unwrap_or(0);
            run_json(async move {
                let detail = netease_client().playlist_detail(id).await?;
                Ok(serde_json::to_value(detail)?)
            })
        }
        "netease_search" => {
            let query = args.get("query").and_then(Value::as_str).unwrap_or_default().to_string();
            let limit = args.get("limit").and_then(Value::as_i64).unwrap_or(30);
            run_json(async move {
                let tracks = netease_client().search_tracks(&query, limit).await?;
                Ok(serde_json::to_value(tracks)?)
            })
        }
        "netease_qr_start" => run_json(async {
            let key = netease_client().qr_unikey().await?;
            Ok(json!({
                "key": key,
                "qrContent": format!("https://music.163.com/login?codekey={key}")
            }))
        }),
        "netease_qr_check" => {
            let key = args.get("key").and_then(Value::as_str).unwrap_or_default().to_string();
            run_json(async move {
                let r = netease_client().qr_check(&key).await?;
                Ok(json!({
                    "code": r.code,
                    "message": r.message,
                    "nickname": r.nickname
                }))
            })
        }
        "netease_captcha_sent" => {
            let phone = args.get("phone").and_then(Value::as_str).unwrap_or_default().to_string();
            let ctcode = args.get("ctcode").and_then(Value::as_i64).unwrap_or(86) as i32;
            run_json(async move {
                let r = netease_client().captcha_sent(&phone, ctcode).await?;
                Ok(json!({
                    "code": r.code,
                    "message": r.message
                }))
            })
        }
        "netease_phone_login" => {
            let phone = args.get("phone").and_then(Value::as_str).unwrap_or_default().to_string();
            let captcha = args.get("captcha").and_then(Value::as_str).unwrap_or_default().to_string();
            let ctcode = args.get("ctcode").and_then(Value::as_i64).unwrap_or(86) as i32;
            run_json(async move {
                let r = netease_client().login_cellphone(&phone, &captcha, ctcode).await?;
                Ok(json!({
                    "code": r.code,
                    "message": r.message,
                    "nickname": r.profile.map(|p| p.nickname)
                }))
            })
        }
        "netease_song_urls" => {
            let ids = args
                .get("ids")
                .and_then(Value::as_array)
                .map(|arr| arr.iter().filter_map(Value::as_i64).collect::<Vec<_>>())
                .unwrap_or_default();
            let level = args.get("level").and_then(Value::as_str).unwrap_or("lossless").to_string();
            run_json(async move {
                let urls = netease_client().song_urls(&ids, &level).await?;
                Ok(serde_json::to_value(urls)?)
            })
        }
        "netease_song_lyric" => {
            let id = args.get("id").and_then(Value::as_i64).unwrap_or(0);
            run_json(async move {
                let lyric = netease_client().song_lyric(id).await?;
                Ok(serde_json::to_value(lyric)?)
            })
        }
        "audio_cache_set_max_mb" => {
            let mb = args.get("mb").and_then(Value::as_i64).unwrap_or(2048);
            match audio_store().set_max_mb(mb) {
                Ok(()) => "null".to_string(),
                Err(e) => error_json(e.to_string()),
            }
        }
        "audio_cache_clear" => match audio_store().clear_cache() {
            Ok(()) => "null".to_string(),
            Err(e) => error_json(e.to_string()),
        },
        "audio_cache_stats" => match audio_store().stats() {
            Ok(stats) => serde_json::to_string(&stats).unwrap_or_else(|e| error_json(e.to_string())),
            Err(e) => error_json(e.to_string()),
        },
        "audio_get_features" => {
            let track_id = args.get("trackId").and_then(Value::as_i64).unwrap_or(0);
            let url = args.get("url").and_then(Value::as_str).unwrap_or_default().to_string();
            let cache_bytes = args.get("cacheBytes").and_then(Value::as_bool).unwrap_or(true);
            run_json(async move {
                match audio_store().features_json(track_id, url, cache_bytes).await {
                    Ok(v) => Ok(v),
                    Err(_) => Ok(audio::zero_features(track_id)),
                }
            })
        }
        "ai_set_provider" => {
            let provider = args.get("provider").and_then(Value::as_str).unwrap_or_default();
            match parse_provider(provider).and_then(|p| ai_store().update(|c| c.provider = p).map_err(|e| e.to_string())) {
                Ok(()) => "null".to_string(),
                Err(e) => error_json(e),
            }
        }
        "ai_get_config" => {
            let cfg = ai_store().snapshot();
            let providers = vec![
                provider_view(ai::config::Provider::Deepseek, &cfg.deepseek),
                provider_view(ai::config::Provider::Openai, &cfg.openai),
                provider_view(ai::config::Provider::XiaomiMimo, &cfg.xiaomi_mimo),
            ];
            json!({
                "activeProvider": provider_key(cfg.provider),
                "providers": providers
            })
            .to_string()
        }
        "ai_set_api_key" => {
            let provider = args.get("provider").and_then(Value::as_str).unwrap_or_default();
            let key = args.get("key").and_then(Value::as_str).unwrap_or_default().to_string();
            match parse_provider(provider).and_then(|p| {
                ai_store()
                    .update(|c| slot_mut(c, p).api_key = if key.trim().is_empty() { None } else { Some(key.trim().to_string()) })
                    .map_err(|e| e.to_string())
            }) {
                Ok(()) => "null".to_string(),
                Err(e) => error_json(e),
            }
        }
        "ai_clear_api_key" => {
            let provider = args.get("provider").and_then(Value::as_str).unwrap_or_default();
            match parse_provider(provider).and_then(|p| {
                ai_store()
                    .update(|c| slot_mut(c, p).api_key = None)
                    .map_err(|e| e.to_string())
            }) {
                Ok(()) => "null".to_string(),
                Err(e) => error_json(e),
            }
        }
        "ai_set_model" => {
            let provider = args.get("provider").and_then(Value::as_str).unwrap_or_default();
            let model = args.get("model").and_then(Value::as_str).unwrap_or_default().to_string();
            match parse_provider(provider).and_then(|p| {
                ai_store()
                    .update(|c| slot_mut(c, p).model = model)
                    .map_err(|e| e.to_string())
            }) {
                Ok(()) => "null".to_string(),
                Err(e) => error_json(e),
            }
        }
        "ai_list_models" => {
            let provider = args.get("provider").and_then(Value::as_str).unwrap_or_default();
            match parse_provider(provider) {
                Ok(p) => json!(
                    ai::config::known_models(p)
                        .iter()
                        .map(|(id, label)| json!({ "id": id, "label": label }))
                        .collect::<Vec<_>>()
                ).to_string(),
                Err(e) => error_json(e),
            }
        }
        "ai_ping" => run_json(async {
            let (key, base_url, model) = ai_store().active();
            let key = key.ok_or_else(|| anyhow::anyhow!("missing API key"))?;
            let reply = ai::openai_compat::chat(
                &key,
                base_url,
                &model,
                &[ai::openai_compat::ChatMessage {
                    role: "user".to_string(),
                    content: "Say a very short hello as a music DJ.".to_string(),
                }],
                0.8,
                80,
            )
            .await?;
            Ok(json!(reply))
        }),
        "ai_chat" => {
            let system = args.get("system").and_then(Value::as_str).map(str::to_string);
            let user = args.get("user").and_then(Value::as_str).unwrap_or_default().to_string();
            let temperature = args.get("temperature").and_then(Value::as_f64).unwrap_or(0.8) as f32;
            let max_tokens = args.get("maxTokens").and_then(Value::as_u64).unwrap_or(400) as u32;
            run_json(async move {
                let (key, base_url, model) = ai_store().active();
                let key = key.ok_or_else(|| anyhow::anyhow!("missing API key"))?;
                let mut messages = Vec::new();
                if let Some(system) = system {
                    messages.push(ai::openai_compat::ChatMessage { role: "system".to_string(), content: system });
                }
                messages.push(ai::openai_compat::ChatMessage { role: "user".to_string(), content: user });
                let reply = ai::openai_compat::chat(&key, base_url, &model, &messages, temperature, max_tokens).await?;
                Ok(json!(reply))
            })
        }
        other => error_json(format!("unknown command: {other}")),
    }
}

fn parse_provider(id: &str) -> Result<ai::config::Provider, String> {
    match id {
        "deepseek" => Ok(ai::config::Provider::Deepseek),
        "openai" => Ok(ai::config::Provider::Openai),
        "xiaomi-mimo" => Ok(ai::config::Provider::XiaomiMimo),
        other => Err(format!("unknown provider: {other}")),
    }
}

fn slot_mut(c: &mut ai::config::AiConfig, p: ai::config::Provider) -> &mut ai::config::ProviderSlot {
    match p {
        ai::config::Provider::Deepseek => &mut c.deepseek,
        ai::config::Provider::Openai => &mut c.openai,
        ai::config::Provider::XiaomiMimo => &mut c.xiaomi_mimo,
    }
}

fn provider_view(provider: ai::config::Provider, slot: &ai::config::ProviderSlot) -> Value {
    json!({
        "id": provider_key(provider),
        "label": provider_label(provider),
        "hasKey": slot.api_key.as_ref().map(|s| !s.trim().is_empty()).unwrap_or(false),
        "keyPreview": slot.api_key.as_deref().filter(|s| !s.trim().is_empty()).map(key_preview),
        "model": slot.model,
        "baseUrl": ai::config::default_base_url(provider),
    })
}

fn provider_key(provider: ai::config::Provider) -> &'static str {
    match provider {
        ai::config::Provider::Deepseek => "deepseek",
        ai::config::Provider::Openai => "openai",
        ai::config::Provider::XiaomiMimo => "xiaomi-mimo",
    }
}

fn provider_label(provider: ai::config::Provider) -> &'static str {
    match provider {
        ai::config::Provider::Deepseek => "DeepSeek",
        ai::config::Provider::Openai => "OpenAI",
        ai::config::Provider::XiaomiMimo => "MiMo",
    }
}

fn key_preview(key: &str) -> String {
    let len = key.len();
    if len <= 8 {
        "*".repeat(len)
    } else {
        format!("{}...{}", &key[..4], &key[len - 4..])
    }
}

fn run_json<F>(future: F) -> String
where
    F: std::future::Future<Output = anyhow::Result<Value>>,
{
    match runtime().block_on(future) {
        Ok(v) => v.to_string(),
        Err(e) => error_json(e.to_string()),
    }
}

fn error_json(message: String) -> String {
    json!({ "error": message }).to_string()
}

fn string_or_null(env: &mut JNIEnv, value: &str) -> jstring {
    match env.new_string(value) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}
