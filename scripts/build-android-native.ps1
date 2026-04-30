$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$androidDir = Join-Path $root "android-native"

if (-not (Get-Command gradle -ErrorAction SilentlyContinue)) {
  throw "gradle is not installed or not on PATH. Install Android Studio or Gradle, then rerun this script."
}

if (-not $env:ANDROID_HOME -and -not $env:ANDROID_SDK_ROOT) {
  throw "ANDROID_HOME or ANDROID_SDK_ROOT is not set. Point it at the Android SDK directory."
}

Push-Location $androidDir
try {
  gradle ":app:assembleDebug"
} finally {
  Pop-Location
}
