$ErrorActionPreference = "Stop"

function Test-Tool($Name) {
  $cmd = Get-Command $Name -ErrorAction SilentlyContinue
  if ($cmd) {
    Write-Host "[ok] $Name -> $($cmd.Source)"
    return $true
  }
  Write-Host "[missing] $Name"
  return $false
}

function Test-EnvPath($Name) {
  $value = [Environment]::GetEnvironmentVariable($Name)
  if ($value -and (Test-Path $value)) {
    Write-Host "[ok] $Name -> $value"
    return $true
  }
  if ($value) {
    Write-Host "[bad] $Name points to missing path: $value"
  } else {
    Write-Host "[missing] $Name"
  }
  return $false
}

$ok = $true
$ok = (Test-Tool "java") -and $ok
$ok = (Test-Tool "gradle") -and $ok
$ok = (Test-Tool "cargo") -and $ok
$ok = (Test-Tool "cargo-ndk") -and $ok

$sdkOk = (Test-EnvPath "ANDROID_HOME") -or (Test-EnvPath "ANDROID_SDK_ROOT")
$ndkOk = (Test-EnvPath "ANDROID_NDK_HOME") -or (Test-EnvPath "ANDROID_NDK_ROOT")
$ok = $sdkOk -and $ndkOk -and $ok

if (-not $ok) {
  Write-Host ""
  Write-Host "Required setup:"
  Write-Host "  1. Install Android Studio with Android SDK platform 36 and NDK."
  Write-Host "  2. Set ANDROID_HOME or ANDROID_SDK_ROOT to the SDK directory."
  Write-Host "  3. Set ANDROID_NDK_HOME or ANDROID_NDK_ROOT to the NDK directory."
  Write-Host "  4. Install Gradle or add a Gradle wrapper."
  Write-Host "  5. Run: cargo install cargo-ndk"
  exit 1
}

Write-Host ""
Write-Host "Android native environment is ready."
