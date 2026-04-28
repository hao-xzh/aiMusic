//! 真网易云端点的探针。默认 `#[ignore]`，不阻塞 `cargo test`；
//! 手动跑：`cargo test --test netease_probe -- --ignored --nocapture`
//!
//! 用这个快速迭代 -462 风控绕过方案 + 验证 cmds.rs 序列化 shape，
//! 不用每次 Claudio 窗口点一遍。

use claudio_lib as _;
use serde_json::Value;

use claudio_lib::*;

#[tokio::test(flavor = "current_thread")]
#[ignore]
async fn qr_unikey_probe() {
    let client = netease::NeteaseClient::new().expect("build");

    match client.qr_unikey().await {
        Ok(k) => println!("✅ unikey ok: {k}"),
        Err(e) => println!("❌ qr_unikey FAILED: {e:#}"),
    }

    let raw: Result<Value, _> = client
        .weapi("login/qrcode/unikey", serde_json::json!({ "type": 1 }))
        .await;
    match raw {
        Ok(v) => println!(
            "raw response:\n{}",
            serde_json::to_string_pretty(&v).unwrap_or_else(|_| v.to_string())
        ),
        Err(e) => println!("❌ raw weapi FAILED: {e:#}"),
    }
}

/// **序列化 shape 检查** —— 本地单元测试，不需要网络。
/// 前端 TS 类型假设所有结构体都是 camelCase。这里把每个 #[command] 返回的结构体
/// 都转成 JSON，如果有 snake_case 字段漏掉重命名就会被抓到。
///
/// 以后加新 struct 的时候，顺手追加到下面即可。
#[test]
fn serialized_shapes_are_camel_case() {
    use claudio_lib::netease::models::*;

    fn keys_of(v: &Value) -> Vec<String> {
        v.as_object()
            .map(|o| o.keys().cloned().collect())
            .unwrap_or_default()
    }
    fn assert_camel(name: &str, v: &Value) {
        for k in keys_of(v) {
            assert!(
                !k.contains('_'),
                "{name} field `{k}` has underscore — 前端 TS 期望 camelCase"
            );
        }
    }

    // UserProfile
    let user = UserProfile {
        user_id: 1,
        nickname: "x".into(),
        avatar_url: Some("u".into()),
    };
    assert_camel("UserProfile", &serde_json::to_value(&user).unwrap());

    // PlaylistInfo
    let pl = PlaylistInfo {
        id: 1,
        name: "n".into(),
        track_count: 0,
        cover_img_url: None,
        user_id: Some(1),
    };
    assert_camel("PlaylistInfo", &serde_json::to_value(&pl).unwrap());

    // TrackInfo (最值得怀疑的，因为 weapi 用 dt/ar/al)
    let tr = TrackInfo {
        id: 1,
        name: "n".into(),
        duration_ms: 1000,
        artists: vec![],
        album: None,
    };
    let tr_json = serde_json::to_value(&tr).unwrap();
    assert_camel("TrackInfo", &tr_json);
    assert!(
        tr_json.get("durationMs").is_some(),
        "TrackInfo 应该把 dt 序列化成 durationMs"
    );

    // AlbumShort
    let al = AlbumShort {
        id: 1,
        name: "n".into(),
        pic_url: Some("u".into()),
    };
    assert_camel("AlbumShort", &serde_json::to_value(&al).unwrap());

    // PlaylistDetail
    let pd = PlaylistDetail {
        id: 1,
        name: "n".into(),
        track_count: 0,
        tracks: vec![],
    };
    assert_camel("PlaylistDetail", &serde_json::to_value(&pd).unwrap());

    // SongUrl
    let su = SongUrl {
        id: 1,
        url: None,
        br: 0,
        size: 0,
    };
    assert_camel("SongUrl", &serde_json::to_value(&su).unwrap());

    // QrStartOut / QrCheckOut —— 这俩就是之前"No input text"现场的元凶，
    // 死活盯着，不允许再漏 camelCase。
    use claudio_lib::netease::cmds::{QrCheckOut, QrStartOut};

    let qs = QrStartOut {
        key: "k".into(),
        qr_content: "c".into(),
    };
    let qs_json = serde_json::to_value(&qs).unwrap();
    assert_camel("QrStartOut", &qs_json);
    assert!(
        qs_json.get("qrContent").is_some(),
        "QrStartOut 必须把 qr_content 序列化成 qrContent —— 前端 r.qrContent 依赖它"
    );

    let qc = QrCheckOut {
        code: 800,
        message: None,
        nickname: None,
    };
    assert_camel("QrCheckOut", &serde_json::to_value(&qc).unwrap());
}
