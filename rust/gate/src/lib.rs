//! Native routing gate for the gray flow — the whole gate lives here.
//!
//! Kotlin builds the request (URL, method, header, body) and hands it to
//! [`Java_..._NativeGate_route`]. This crate only owns the HTTPS POST
//! itself, over a BoringSSL stack that emulates a real Chrome handshake
//! (JA3/JA4), so a Cloudflare-fronted endpoint accepts the request rather
//! than returning a 403 bot challenge. Kotlin never opens the socket itself.
//!
//! The JNI contract is deliberately thin: on return Kotlin gets a two-line
//! string of the form `"{status}\n{body}"`. Status 0 means the request never
//! landed (DNS, TLS, timeout — anything short of a real HTTP reply). The
//! stream/native verdict is Kotlin's to derive from the status + body.
//!
//! On top of routing, the library also owns the native game's progress vault
//! (`Java_..._NativeGate_seal` / `..._open`). SharedPreferences values are
//! XOR-encrypted and HMAC-tagged with a key that lives only inside `.so`; a
//! tag mismatch on read is silently reported to Kotlin as "reset to default",
//! so a hex-editor cheat over a saved int cannot survive one launch. Runs
//! fully offline.
//!
//! And the keyboard-pan JavaScript injection
//! (`Java_..._NativeGate_keyboardScript`) — kept native so the sentinel plus
//! frame-walker scaffolding never reaches the DEX constant pool.

use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use hmac::{Hmac, Mac};
use jni::objects::{JClass, JString};
use jni::sys::{jint, jstring};
use jni::JNIEnv;
use sha2::{Digest, Sha256};
use std::time::Duration;
use wreq::{Client, Method};
use wreq_util::Emulation;

type HmacSha256 = Hmac<Sha256>;

// ─── Progress-vault secret (separate keying material from routing) ──────────
//
// Vault-only XOR salt. The routing path no longer encodes URL / method /
// header / MIME here — those travel in from Kotlin as plain strings, so the
// backend address is visible in the DEX. The vault key, however, must stay
// hidden inside the `.so` so a trivial `strings libhaven.so | grep` on the
// APK cannot recover it.

const VAULT_SALT: [u8; 16] = [
    0x18, 0x1E, 0xE0, 0x22, 0x73, 0xC9, 0xDE, 0x1D, 0xFA, 0x5D, 0x28, 0x81, 0x7D, 0xD7, 0xF7, 0x28,
];

const VAULT_SECRET_ENC: &[u8] = &[
    0x4A, 0x91, 0xC3, 0x27, 0xE8, 0x1D, 0xB6, 0x54, 0x02, 0xAF, 0x73, 0x9E, 0x6C, 0xD5, 0x38, 0x1B,
    0x82, 0x40, 0xF7, 0xA9, 0x2E, 0x67, 0xCB, 0x14, 0x5D, 0x39, 0xE2, 0x76, 0x8A, 0xC0, 0x4F, 0x21,
    0xB3, 0x58, 0x99, 0x0E, 0x7D, 0xE4, 0x35, 0xA7, 0x62, 0x1C, 0xF0, 0x48, 0x93, 0xDA, 0x56, 0x2B,
];
// Domain separator: bumping it invalidates every save from the previous rev.
const VAULT_DOMAIN: &[u8] = b"stormreach-vault-v1";
// Full HMAC-SHA256 is 32 bytes; we truncate to 16 (128 bits) — plenty against
// an offline forger who has to guess without the .so key.
const VAULT_TAG_LEN: usize = 16;
const VAULT_NONCE_LEN: usize = 16;

fn vault_deob(enc: &[u8]) -> Vec<u8> {
    enc.iter()
        .enumerate()
        .map(|(i, b)| b ^ VAULT_SALT[i % VAULT_SALT.len()])
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

// ─── Config request ─────────────────────────────────────────────────────────

/// POST via wreq with Chrome134 emulation. Returns `(status, body)`.
/// A transport-level failure — DNS, TLS, timeout, any error short of a real
/// HTTP reply — comes out as status 0 with an empty body.
///
/// The emulation sets the User-Agent, the full header order and the TLS
/// fingerprint as one package. **Do not override any of them.** A browser
/// UA on top of a non-browser handshake is exactly the mismatch Cloudflare
/// clusters on; the response for that is 403 before the server reads the
/// body. If a project needs a different browser, change the emulation
/// profile here (and bump `wreq-util`), never patch the UA on the side.
fn request(
    url: &str,
    method: &str,
    header_name: &str,
    header_val: &str,
    body: String,
) -> (i32, String) {
    let runtime = match tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
    {
        Ok(r) => r,
        Err(_) => return (0, String::new()),
    };

    runtime.block_on(async move {
        let client = match Client::builder()
            .emulation(Emulation::Chrome134)
            .connect_timeout(Duration::from_secs(8))
            .timeout(Duration::from_secs(20))
            .build()
        {
            Ok(c) => c,
            Err(_) => return (0, String::new()),
        };

        let m = Method::from_bytes(method.as_bytes()).unwrap_or(Method::POST);
        let mut rb = client.request(m, url).body(body);
        if !header_name.is_empty() {
            rb = rb.header(header_name, header_val);
        }

        match rb.send().await {
            Ok(resp) => {
                let status = resp.status().as_u16() as i32;
                let text = resp.text().await.unwrap_or_default();
                (status, text)
            }
            Err(_) => (0, String::new()),
        }
    })
}

fn jstr(env: &mut JNIEnv, s: &JString) -> String {
    env.get_string(s).map(|v| v.into()).unwrap_or_default()
}

/// Kotlin-driven routing: all request shaping (URL, method, header, body)
/// arrives as plain strings; Rust just drives wreq to put the bytes on
/// the wire with the right TLS fingerprint. Returns `"{status}\n{body}"`.
#[no_mangle]
pub extern "system" fn Java_com_stormreachhaven_stormreachgame_NativeGate_route<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    url: JString<'local>,
    method: JString<'local>,
    header_name: JString<'local>,
    header_value: JString<'local>,
    body: JString<'local>,
) -> jstring {
    let url = jstr(&mut env, &url);
    let method = jstr(&mut env, &method);
    let header_name = jstr(&mut env, &header_name);
    let header_value = jstr(&mut env, &header_value);
    let body = jstr(&mut env, &body);

    let (status, resp) = request(&url, &method, &header_name, &header_value, body);
    let result = format!("{}\n{}", status, resp);
    env.new_string(result)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

// ─── Keyboard-pan JavaScript injection ──────────────────────────────────────
//
// Reports the focused field's position back to Kotlin over the
// @JavascriptInterface bridge so [KeyboardTide] can slide the WebView clear
// of the keyboard without resizing the viewport.
//
// Kept in Rust so the sentinel/bridge-name/frame-walker scaffolding never
// appears as a plain-text constant pool entry in the DEX — only the derived
// per-project sentinel and bridge name reach the APK, embedded in the `.so`.
// `__SENTINEL__`, `__BRIDGE__`, `__DOCFLAG__`, `__MARGIN__` are substituted
// at call time; order matters so the longest patterns are replaced first.
const KEYBOARD_SCRIPT_TEMPLATE: &str = r#"(function(){
  if (window.__SENTINEL__) return;
  window.__SENTINEL__ = true;

  function editable(el){
    if (!el) return false;
    var tag = el.tagName;
    if (tag === 'INPUT') {
      var type = (el.type || 'text').toLowerCase();
      return type !== 'checkbox' && type !== 'radio' && type !== 'button' &&
             type !== 'submit' && type !== 'reset' && type !== 'file' &&
             type !== 'range' && type !== 'image' && type !== 'color';
    }
    return tag === 'TEXTAREA' || el.isContentEditable === true;
  }

  function box(el, win){
    if (el.isContentEditable) {
      try {
        var sel = win.getSelection();
        if (sel && sel.rangeCount) {
          var r = sel.getRangeAt(0).getBoundingClientRect();
          if (r && r.height > 0) return r;
        }
      } catch(e) {}
    }
    return el.getBoundingClientRect();
  }

  function locate(){
    var el = document.activeElement;
    var win = window;
    var offset = 0;
    var depth = 0;
    while (el && (el.tagName === 'IFRAME' || el.tagName === 'FRAME') && depth++ < 4) {
      var outline = el.getBoundingClientRect();
      var doc = null;
      try { doc = el.contentDocument; } catch(e) { doc = null; }
      var inner = doc ? doc.activeElement : null;
      if (!inner || inner === doc.body) {
        return { frame: true, top: offset + outline.top, bottom: offset + outline.bottom };
      }
      watch(doc);
      win = el.contentWindow || win;
      offset += outline.top;
      el = inner;
    }
    if (!editable(el)) return null;
    var r = box(el, win);
    return { frame: false, top: offset + r.top, bottom: offset + r.bottom };
  }

  function report(){
    var at = locate();
    if (!at) return;
    var vv = window.visualViewport;
    var lift = vv ? vv.offsetTop : 0;
    var zoom = (vv && vv.scale) ? vv.scale : 1;
    var px = (window.devicePixelRatio || 1) * zoom;
    try {
      __BRIDGE__.focus(
        at.frame,
        (at.top - lift) * px,
        (at.bottom - lift + __MARGIN__) * px
      );
    } catch(e) {}
  }

  function kick(){
    report();
    setTimeout(report, 200);
    setTimeout(report, 420);
  }

  var queued = false;
  function soon(){
    if (queued) return;
    queued = true;
    var run = function(){ queued = false; report(); };
    if (window.requestAnimationFrame) requestAnimationFrame(run);
    else setTimeout(run, 16);
  }
  if (window.visualViewport) {
    window.visualViewport.addEventListener('resize', soon);
    window.visualViewport.addEventListener('scroll', soon);
  }

  function watch(doc){
    try {
      if (!doc || doc.__DOCFLAG__) return;
      doc.__DOCFLAG__ = true;
      doc.addEventListener('focusin', kick, true);
      doc.addEventListener('click', function(ev){
        var t = ev.target;
        if (t && editable(t)) setTimeout(report, 60);
      }, true);
    } catch(e) {}
  }

  window.__SENTINEL__Report = report;
  watch(document);
})();"#;

fn build_keyboard_script(sentinel: &str, bridge: &str, margin_css: i32) -> String {
    let doc_flag = format!("{}D", sentinel);
    // Order matters: substitute the longer-overlapping ones first.
    KEYBOARD_SCRIPT_TEMPLATE
        .replace("__SENTINEL__", sentinel)
        .replace("__BRIDGE__", bridge)
        .replace("__DOCFLAG__", &doc_flag)
        .replace("__MARGIN__", &margin_css.to_string())
}

#[no_mangle]
pub extern "system" fn Java_com_stormreachhaven_stormreachgame_NativeGate_keyboardScript<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    sentinel: JString<'local>,
    bridge: JString<'local>,
    margin_css: jint,
) -> jstring {
    let sentinel = jstr(&mut env, &sentinel);
    let bridge = jstr(&mut env, &bridge);
    let script = build_keyboard_script(&sentinel, &bridge, margin_css as i32);
    env.new_string(script)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

// ─── Progress vault (offline anti-cheat) ────────────────────────────────────
//
// Wire format (before base64url):
//   nonce (16)  ||  tag (16)  ||  ciphertext (n)
//
// Ciphertext is the plaintext XORed with SHA-256(secret || nonce || counter).
// Tag is HMAC-SHA256(secret, VAULT_DOMAIN || aad_len(u32be) || aad || nonce
// || ciphertext), truncated to the first 16 bytes. `aad` is the SharedPrefs
// key; binding it means an attacker cannot move a legitimate sealed blob
// from one preference key to another.

fn vault_key() -> Vec<u8> {
    // Derive a working key so the raw deob'd bytes never touch HMAC directly.
    let raw = vault_deob(VAULT_SECRET_ENC);
    let mut h = Sha256::new();
    h.update(VAULT_DOMAIN);
    h.update(b"|kdf|");
    h.update(&raw);
    h.finalize().to_vec()
}

fn vault_tag(key: &[u8], nonce: &[u8], aad: &[u8], ct: &[u8]) -> Vec<u8> {
    let mut mac = HmacSha256::new_from_slice(key).expect("hmac key");
    mac.update(VAULT_DOMAIN);
    mac.update(&(aad.len() as u32).to_be_bytes());
    mac.update(aad);
    mac.update(nonce);
    mac.update(ct);
    let full = mac.finalize().into_bytes();
    full[..VAULT_TAG_LEN].to_vec()
}

fn ct_eq(a: &[u8], b: &[u8]) -> bool {
    if a.len() != b.len() {
        return false;
    }
    let mut diff = 0u8;
    for (x, y) in a.iter().zip(b.iter()) {
        diff |= x ^ y;
    }
    diff == 0
}

fn seal_bytes(plaintext: &[u8], aad: &[u8]) -> Option<String> {
    let mut nonce = [0u8; VAULT_NONCE_LEN];
    getrandom::getrandom(&mut nonce).ok()?;
    let key = vault_key();
    let ks = keystream(&key, &nonce, plaintext.len());
    let ct: Vec<u8> = plaintext.iter().zip(ks.iter()).map(|(a, b)| a ^ b).collect();
    let tag = vault_tag(&key, &nonce, aad, &ct);
    let mut blob = Vec::with_capacity(VAULT_NONCE_LEN + VAULT_TAG_LEN + ct.len());
    blob.extend_from_slice(&nonce);
    blob.extend_from_slice(&tag);
    blob.extend_from_slice(&ct);
    Some(URL_SAFE_NO_PAD.encode(&blob))
}

fn open_bytes(sealed_b64: &str, aad: &[u8]) -> Option<Vec<u8>> {
    let blob = URL_SAFE_NO_PAD.decode(sealed_b64.as_bytes()).ok()?;
    if blob.len() < VAULT_NONCE_LEN + VAULT_TAG_LEN {
        return None;
    }
    let (nonce, rest) = blob.split_at(VAULT_NONCE_LEN);
    let (tag, ct) = rest.split_at(VAULT_TAG_LEN);
    let key = vault_key();
    let expected = vault_tag(&key, nonce, aad, ct);
    if !ct_eq(tag, &expected) {
        return None;
    }
    let ks = keystream(&key, nonce, ct.len());
    Some(ct.iter().zip(ks.iter()).map(|(a, b)| a ^ b).collect())
}

#[no_mangle]
pub extern "system" fn Java_com_stormreachhaven_stormreachgame_NativeGate_seal<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    plaintext: JString<'local>,
    aad: JString<'local>,
) -> jstring {
    let pt = jstr(&mut env, &plaintext);
    let ad = jstr(&mut env, &aad);
    let out = seal_bytes(pt.as_bytes(), ad.as_bytes()).unwrap_or_default();
    env.new_string(out)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_stormreachhaven_stormreachgame_NativeGate_open<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    sealed: JString<'local>,
    aad: JString<'local>,
) -> jstring {
    let sealed_s = jstr(&mut env, &sealed);
    let ad = jstr(&mut env, &aad);
    // Empty response = tag mismatch (or malformed). Kotlin reads that as
    // "no valid value stored" and falls back to the caller's default.
    let out = open_bytes(&sealed_s, ad.as_bytes())
        .and_then(|bytes| String::from_utf8(bytes).ok())
        .unwrap_or_default();
    env.new_string(out)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}
