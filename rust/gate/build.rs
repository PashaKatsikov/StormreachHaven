// Android link glue for btls-sys.
//
// `btls-sys` builds BoringSSL in two passes (`ssl`, then `crypto`) and emits
// `cargo:rustc-link-lib=static=ssl` + `=crypto`. The default linker sees them
// as a straight list and runs through once, which leaves inter-archive
// symbols (`SSL_CTX_free` reaching into `crypto`) unresolved — on-device the
// `.so` loads and then `dlopen` fails with
// `cannot locate symbol SSL_CTX_free`.
//
// Wrapping the pair in `--start-group`/`--end-group` tells `lld` to resolve
// across the archives in a loop, which is the only way the group of two
// static archives links cleanly.
fn main() {
    let target = std::env::var("TARGET").unwrap_or_default();
    if target.contains("android") {
        println!("cargo:rustc-link-arg=-Wl,--start-group");
        println!("cargo:rustc-link-arg=-lssl");
        println!("cargo:rustc-link-arg=-lcrypto");
        println!("cargo:rustc-link-arg=-Wl,--end-group");

        // libhaven.so is NEEDED libc++_shared.so (BoringSSL + wreq's C++
        // bits built against the NDK shared libc++). `build-android.ps1`
        // copies the matching `libc++_shared.so` out of the NDK sysroot
        // into `app/src/main/jniLibs/<abi>/` so the APK ships both — if
        // either is missing, `System.loadLibrary("haven")` fails at
        // `dlopen` time and the gate silently reports "not loaded".
    }
    println!("cargo:rerun-if-changed=build.rs");
}
