# ── rust/gate — Android cross-compile driver ────────────────────────────────
#
# What this does and why:
#
#  1. Sets ANDROID_NDK_HOME so `android.toolchain.cmake` can be found by the
#     `cmake-rs` driver that `btls-sys` (BoringSSL) builds through.
#
#  2. Prepends the NDK LLVM `bin/` and the Android SDK CMake 3.22 `bin/` to
#     PATH. `android.toolchain.cmake` is incompatible with CMake 4; the NDK
#     ships one with Android Studio, we use that. Ninja comes in the same
#     folder.
#
#  3. Explicitly UNSETS CC_<target> / CXX_<target> / AR_<target> for the
#     three Android targets. `btls-sys` builds `libssl.a` first and then
#     `libcrypto.a`; if any of those env vars is set, the first pass picks
#     up the host compiler and emits a COFF x86_64 archive. The linker
#     silently throws those objects away, and the resulting .so is missing
#     `SSL_CTX_free`, `EVP_*`, etc. Device loader then reports
#     `dlopen: cannot locate symbol SSL_CTX_free`. The compiler must come
#     from `android.toolchain.cmake` and nowhere else — see the user guide.
#
#  4. CFLAGS_<target> / CXXFLAGS_<target> / BINDGEN_EXTRA_CLANG_ARGS_<target>
#     stay available (used by `cc` and `bindgen`; `btls-sys` ignores them)
#     and are not stripped here.
#
#  5. For each ABI runs `cargo build --release --target <t>` and copies the
#     output `.so` into `app/src/main/jniLibs/<abi>/libhaven.so`.
#
# Usage:
#   pwsh -ExecutionPolicy Bypass -File rust/gate/build-android.ps1
#
# The script does not install anything. Prerequisites:
#   * Rust (stable-x86_64-pc-windows-gnu or msvc, both work for host builds)
#   * MinGW-w64 UCRT in PATH (for the x86_64-pc-windows-gnu host build)
#   * Android SDK with NDK 28.x and CMake 3.22.x installed
#     (`sdkmanager "ndk;28.2.13676358" "cmake;3.22.1"`)

$ErrorActionPreference = 'Stop'

# ─── Toolchain discovery ────────────────────────────────────────────────────

$sdkRoot    = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$ndkRoot    = Join-Path $sdkRoot 'ndk'
$cmakeRoot  = Join-Path $sdkRoot 'cmake'

if (-not (Test-Path $ndkRoot)) {
    throw "Android NDK root not found at $ndkRoot. Install one via Android Studio or sdkmanager."
}
if (-not (Test-Path $cmakeRoot)) {
    throw "Android SDK CMake root not found at $cmakeRoot. Install cmake;3.22.x via sdkmanager."
}

# Pick the newest NDK with major <= 27. NDK 28's clang refuses to find
# `crtbegin_dynamic.o` when a crate (`btls-sys`) appends a bare
# `--target=<triple>` without the API suffix — the second `--target` wins and
# strips the API that `android.toolchain.cmake` had baked in. NDK 27's clang
# falls back to a default API level in that case, which is what BoringSSL
# needs to configure.
$ndkDir = Get-ChildItem $ndkRoot -Directory |
    Where-Object { ($_.Name -split '\.')[0] -as [int] -le 27 } |
    Sort-Object { [version]$_.Name } -Descending |
    Select-Object -First 1
if (-not $ndkDir) {
    throw "No NDK <= 27 under $ndkRoot. Install ndk;27.x via sdkmanager (NDK 28's clang breaks the btls-sys cmake flow)."
}

# Pick the newest CMake with major < 4. 4.x is incompatible with
# android.toolchain.cmake and refuses to configure.
$cmakeDir = Get-ChildItem $cmakeRoot -Directory |
    Where-Object { ($_.Name -split '\.')[0] -as [int] -lt 4 } |
    Sort-Object { [version]$_.Name } -Descending |
    Select-Object -First 1
if (-not $cmakeDir) {
    throw "No CMake < 4 under $cmakeRoot. Install cmake;3.22.1 via sdkmanager."
}

$env:ANDROID_NDK_HOME = $ndkDir.FullName
$env:ANDROID_NDK_ROOT = $ndkDir.FullName

$ndkBin   = Join-Path $env:ANDROID_NDK_HOME 'toolchains\llvm\prebuilt\windows-x86_64\bin'
$cmakeBin = Join-Path $cmakeDir.FullName 'bin'

$env:Path = "$cmakeBin;$ndkBin;$env:Path"

# cmake-rs defaults to `nmake` on Windows hosts, which is absent on a bare
# Android dev machine and which `android.toolchain.cmake` does not drive
# anyway. The NDK's bundled Ninja is in the same bin folder we just added
# to PATH.
$env:CMAKE_GENERATOR = 'Ninja'

Write-Host "NDK   : $env:ANDROID_NDK_HOME" -ForegroundColor Cyan
Write-Host "CMake : $($cmakeDir.FullName)" -ForegroundColor Cyan
Write-Host "Gen   : $env:CMAKE_GENERATOR" -ForegroundColor Cyan

# ─── Strip CC_/CXX_/AR_ overrides for the Android targets ───────────────────

$androidTargets = @(
    @{ Rust = 'aarch64-linux-android';     Abi = 'arm64-v8a';   SysrootTriple = 'aarch64-linux-android'     },
    @{ Rust = 'armv7-linux-androideabi';   Abi = 'armeabi-v7a'; SysrootTriple = 'arm-linux-androideabi'     },
    @{ Rust = 'x86_64-linux-android';      Abi = 'x86_64';      SysrootTriple = 'x86_64-linux-android'      }
)

foreach ($t in $androidTargets) {
    $suffix = ($t.Rust -replace '-', '_')
    foreach ($prefix in @('CC_', 'CXX_', 'AR_')) {
        $name = $prefix + $suffix
        if (Test-Path "Env:$name") {
            Write-Host "Removing env $name" -ForegroundColor Yellow
            Remove-Item "Env:$name"
        }
    }
}

# ─── Build each ABI ─────────────────────────────────────────────────────────

$repoRoot  = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$cargoDir  = $PSScriptRoot
$jniLibs   = Join-Path $repoRoot 'app\src\main\jniLibs'

Push-Location $cargoDir
try {
    foreach ($t in $androidTargets) {
        Write-Host "`n=== cargo build --release --target $($t.Rust) ===" -ForegroundColor Green
        cargo build --release --target $t.Rust
        if ($LASTEXITCODE -ne 0) { throw "cargo build failed for $($t.Rust) (exit $LASTEXITCODE)" }

        $src = Join-Path $cargoDir ("target\{0}\release\libgate.so" -f $t.Rust)
        $dstDir = Join-Path $jniLibs $t.Abi
        $dst = Join-Path $dstDir 'libhaven.so'
        New-Item -ItemType Directory -Force -Path $dstDir | Out-Null
        Copy-Item -Force $src $dst
        Write-Host "copied -> $dst" -ForegroundColor Cyan

        # libc++_shared.so sibling. BoringSSL + wreq's C++ bits are built
        # against the NDK shared libc++, so `libhaven.so` has
        # `NEEDED libc++_shared.so` and will not `dlopen` without it. The
        # per-ABI sysroot ships the exact matching copy — never pull it
        # from elsewhere, versions must agree.
        $cxxSrc = Join-Path $ndkBin ("..\sysroot\usr\lib\{0}\libc++_shared.so" -f $t.SysrootTriple)
        $cxxSrc = (Resolve-Path $cxxSrc -ErrorAction Stop).Path
        $cxxDst = Join-Path $dstDir 'libc++_shared.so'
        Copy-Item -Force $cxxSrc $cxxDst
        Write-Host "copied -> $cxxDst" -ForegroundColor Cyan
    }
}
finally {
    Pop-Location
}

Write-Host "`nAll ABIs built." -ForegroundColor Green
Write-Host "Verify with:"
Write-Host "  llvm-nm --dynamic app\src\main\jniLibs\arm64-v8a\libhaven.so | findstr /i NativeGate" -ForegroundColor Gray
