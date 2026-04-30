$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$bridgeDir = Join-Path $root "android-native\native-bridge"
$jniLibs = Join-Path $root "android-native\app\src\main\jniLibs"

if (-not (Get-Command cargo -ErrorAction SilentlyContinue)) {
  throw "cargo is not installed or not on PATH."
}

if (-not (Get-Command cargo-ndk -ErrorAction SilentlyContinue)) {
  throw "cargo-ndk is not installed. Install it with: cargo install cargo-ndk"
}

if (-not $env:ANDROID_NDK_HOME -and -not $env:ANDROID_NDK_ROOT) {
  throw "ANDROID_NDK_HOME or ANDROID_NDK_ROOT is not set. Point it at the Android NDK directory."
}

Push-Location $bridgeDir
try {
  cargo ndk `
    -t arm64-v8a `
    -t armeabi-v7a `
    -t x86_64 `
    -o $jniLibs `
    build --release
} finally {
  Pop-Location
}
