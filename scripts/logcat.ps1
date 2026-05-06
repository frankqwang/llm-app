# Tail relevant logcat tags while testing the chat.
# Usage: powershell -ExecutionPolicy Bypass -File .\scripts\logcat.ps1

$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { throw "adb not found at $adb" }

& $adb logcat -c
Write-Host "==> Tailing LiteRT-LM + chat helper logs. Ctrl-C to stop." -ForegroundColor Cyan
Write-Host "    Look for:"
Write-Host "      Preferred backend: GPU             (CPU = silent fallback)"
Write-Host "      Speculative decoding enabled: true (false = MTP off)"
Write-Host ""
& $adb logcat AGLlmChatModelHelper:D LiteRtLm:D Engine:D AndroidRuntime:E *:S
