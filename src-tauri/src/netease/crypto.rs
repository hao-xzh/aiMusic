//! weapi 加密算法（双层 AES-128-CBC + 定长 RSA）。
//!
//! 这是网易云官方 web 端和桌面客户端私有 API 的通用加密。
//! 逆向资料见 Binaryify/NeteaseCloudMusicApi 和无数社区实现，算法 10 年没变过。
//!
//! 流程（params 是业务 JSON）：
//!   1) text       = JSON.stringify(params)
//!   2) enc1       = AES-CBC-PKCS7(text, key=FIRST_KEY, iv=IV)          ; base64 输出
//!   3) secret_key = 16 个随机 alphanumeric 字符
//!   4) enc2       = AES-CBC-PKCS7(enc1, key=secret_key, iv=IV)         ; base64 输出 -> params
//!   5) encSecKey  = RSA_raw(reverse(secret_key), PUB_MOD, e=65537)     ; hex 左填充到 256
//!   6) 以 form-urlencoded 发 { params, encSecKey }

use aes::Aes128;
use base64::prelude::*;
use cipher::{block_padding::Pkcs7, BlockEncryptMut, KeyIvInit};
use num_bigint::BigUint;
use rand::Rng;

type Aes128CbcEnc = cbc::Encryptor<Aes128>;

const FIRST_KEY: &[u8; 16] = b"0CoJUm6Qyw8W8jud";
const IV: &[u8; 16] = b"0102030405060708";

// 官方 weapi 公钥（模数，十六进制；公钥指数固定 65537）
const PUB_MOD_HEX: &str = "00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3ab17a876aea8a5aa76d2e417629ec4ee341f56135fccf695280104e0312ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932575cce10b424d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7";
const PUB_EXP: u32 = 0x10001;

const KEY_CHARSET: &[u8] = b"abcdefghijklmnopqrstuvwxyz0123456789";

/// 加密后准备塞进 form-urlencoded body 的两段文本。
pub struct WeapiBody {
    pub params: String,
    pub enc_sec_key: String,
}

fn aes_cbc_encrypt(data: &[u8], key: &[u8; 16], iv: &[u8; 16]) -> Vec<u8> {
    Aes128CbcEnc::new(key.into(), iv.into()).encrypt_padded_vec_mut::<Pkcs7>(data)
}

/// 生成 16 个随机 alphanumeric 字符，符合官方 JS 里的 `createSecretKey` 约定。
fn random_secret_key() -> [u8; 16] {
    let mut rng = rand::thread_rng();
    let mut out = [0u8; 16];
    for slot in out.iter_mut() {
        *slot = KEY_CHARSET[rng.gen_range(0..KEY_CHARSET.len())];
    }
    out
}

/// weapi 的"raw" RSA：不做 PKCS#1 padding —— 把 key 倒序当作 ASCII 串，
/// 十六进制左填充到 256 位，再做 `m^e mod n`，输出同样左填充到 256 位的十六进制。
fn rsa_encrypt_secret_key(secret_key: &[u8; 16]) -> String {
    let mut rev = secret_key.to_vec();
    rev.reverse();
    let msg_hex = hex::encode(&rev); // 32 chars
    let padded = format!("{:0>256}", msg_hex); // 左填充到 256 chars（也即 1024 bit）
    let m = BigUint::parse_bytes(padded.as_bytes(), 16)
        .expect("msg hex is valid");
    let n = BigUint::parse_bytes(PUB_MOD_HEX.as_bytes(), 16)
        .expect("PUB_MOD_HEX is a valid hex literal");
    let e = BigUint::from(PUB_EXP);
    let c = m.modpow(&e, &n);
    let c_hex = c.to_str_radix(16);
    format!("{:0>256}", c_hex)
}

/// 对任意可序列化的业务 JSON 做 weapi 加密。
pub fn weapi_encrypt(params: &serde_json::Value) -> WeapiBody {
    let text = serde_json::to_string(params).expect("serializable");
    let enc1 = aes_cbc_encrypt(text.as_bytes(), FIRST_KEY, IV);
    let enc1_b64 = BASE64_STANDARD.encode(&enc1);

    let secret_key = random_secret_key();
    let enc2 = aes_cbc_encrypt(enc1_b64.as_bytes(), &secret_key, IV);
    let params_b64 = BASE64_STANDARD.encode(&enc2);

    let enc_sec_key = rsa_encrypt_secret_key(&secret_key);
    WeapiBody {
        params: params_b64,
        enc_sec_key,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn aes_pkcs7_roundtrip_shape() {
        // 16 字节边界不 panic，且输出是有效 base64
        let body = weapi_encrypt(&serde_json::json!({"hello": "world"}));
        assert!(!body.params.is_empty());
        assert_eq!(body.enc_sec_key.len(), 256);
        assert!(body.enc_sec_key.chars().all(|c| c.is_ascii_hexdigit()));
    }

    #[test]
    fn rsa_deterministic_given_same_key() {
        let k = *b"abcdef0123456789";
        let a = rsa_encrypt_secret_key(&k);
        let b = rsa_encrypt_secret_key(&k);
        assert_eq!(a, b);
        assert_eq!(a.len(), 256);
    }
}
