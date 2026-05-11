# Install APK + push model to phone in one shot.
# Usage: powershell -ExecutionPolicy Bypass -File .\scripts\setup-device.ps1
#
# Run this AFTER `gradlew.bat assembleDebug` succeeds and a device is plugged in
# with USB debugging on.
param(
  [switch]$ResetApp
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot

$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$apk = Join-Path $repoRoot 'android\app\build\outputs\apk\debug\app-debug.apk'
$model = Join-Path $repoRoot 'models\gemma-4-E2B-it.litertlm'

if (-not (Test-Path $adb))  { throw "adb not found at $adb" }
if (-not (Test-Path $apk))  { throw "APK not found at $apk -- run gradlew.bat assembleDebug first" }
if (-not (Test-Path $model)) { throw "Model not found at $model" }

Write-Host "==> waiting for device..." -ForegroundColor Cyan
& $adb wait-for-device
$devices = & $adb devices | Select-String -Pattern '\sdevice$'
if ($devices.Count -eq 0) { throw "no device authorised. Check USB-debug auth dialog on phone." }
Write-Host ($devices -join "`n")

if ($ResetApp) {
  Write-Host "==> resetting app by uninstalling old build..." -ForegroundColor Yellow
  & $adb uninstall com.google.aiedge.gallery 2>&1 | Out-Null
} else {
  Write-Host "==> preserving existing app data (pass -ResetApp to uninstall first)." -ForegroundColor Cyan
}

Write-Host "==> installing APK..." -ForegroundColor Cyan
& $adb install -r $apk
if ($LASTEXITCODE -ne 0) { throw "APK install failed" }

Write-Host "==> pushing 2.59 GB model to /sdcard/Download/ (~20-60s on USB 3)..." -ForegroundColor Cyan
& $adb push $model /sdcard/Download/
if ($LASTEXITCODE -ne 0) { throw "model push failed" }

Write-Host ""
Write-Host "==> DONE. Now on your phone:" -ForegroundColor Green
Write-Host "  1. Open the app (look for 'AI Edge Gallery')"
Write-Host "  2. Side drawer -> Models"
Write-Host "  3. Tap '+' FAB -> 'From local model file'"
Write-Host "  4. Pick Downloads -> gemma-4-E2B-it.litertlm"
Write-Host "  5. In import dialog, BEFORE confirming, toggle ON:" -ForegroundColor Yellow
Write-Host "       [x] Support speculative decoding   <-- REQUIRED for MTP" -ForegroundColor Yellow
Write-Host "       [ ] Support thinking               <-- optional, off for raw decode bench"
Write-Host "       [.] Support image                  <-- not required for VlogCopilot (it asks for vision at runtime"
Write-Host "                                              regardless), but ON keeps LLM Ask Image happy too"
Write-Host "       [ ] Support audio                  <-- skip unless testing audio"
Write-Host "       Compatible accelerators: GPU"
Write-Host "  6. Confirm. Wait for 'Model imported successfully'."
Write-Host "  7. Open chat with the imported model."
Write-Host "  8. (optional) tail logs in another terminal:"
Write-Host "       powershell -File .\scripts\logcat.ps1"
