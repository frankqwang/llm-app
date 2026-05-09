param(
  [string]$AndroidDir = (Resolve-Path (Join-Path $PSScriptRoot "..\android")).Path,
  [string]$Adb = "",
  [string]$Serial = "",
  [string]$Package = "com.google.aiedge.gallery",
  [string]$Maestro = "maestro",
  [switch]$SkipInstall,
  [switch]$SkipMaestro,
  [switch]$RunMaestro,
  [switch]$AllowClearState,
  [switch]$AllowMaestroHelperInstall
)

$ErrorActionPreference = "Stop"
$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$outputDir = Join-Path $repoRoot ".debug\android-regression\$timestamp"
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null

function Write-Step {
  param([string]$Message)
  Write-Host "==> $Message" -ForegroundColor Cyan
}

function Resolve-Adb {
  param([string]$Requested)
  if ($Requested -and (Test-Path -LiteralPath $Requested)) {
    return (Resolve-Path -LiteralPath $Requested).Path
  }
  $candidates = @()
  if ($env:ANDROID_HOME) {
    $candidates += (Join-Path $env:ANDROID_HOME "platform-tools\adb.exe")
  }
  if ($env:ANDROID_SDK_ROOT) {
    $candidates += (Join-Path $env:ANDROID_SDK_ROOT "platform-tools\adb.exe")
  }
  $candidates += (Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe")
  foreach ($candidate in $candidates) {
    if ($candidate -and (Test-Path -LiteralPath $candidate)) {
      return (Resolve-Path -LiteralPath $candidate).Path
    }
  }
  throw "adb.exe not found. Pass -Adb C:\path\to\adb.exe"
}

function Resolve-Device {
  param([string]$AdbPath, [string]$RequestedSerial)
  $lines = & $AdbPath devices
  $devices = @($lines | Select-String -Pattern "^\S+\s+device$" | ForEach-Object {
    ($_ -split "\s+")[0]
  })
  $unauthorized = @($lines | Select-String -Pattern "unauthorized")
  if ($unauthorized.Count -gt 0) {
    throw "Device is connected but unauthorized. Unlock the phone and accept the USB debugging prompt."
  }
  if ($RequestedSerial) {
    if ($devices -notcontains $RequestedSerial) {
      throw "Requested device '$RequestedSerial' is not connected. Connected: $($devices -join ', ')"
    }
    return $RequestedSerial
  }
  if ($devices.Count -eq 0) {
    throw "No authorized adb device found. Enable USB debugging and run this again."
  }
  if ($devices.Count -gt 1) {
    throw "Multiple devices connected. Pass -Serial <device-id>. Connected: $($devices -join ', ')"
  }
  return $devices[0]
}

function Save-DeviceArtifacts {
  param([string]$AdbPath, [string]$DeviceSerial, [string]$OutDir, [string]$Prefix)
  $remotePng = "/sdcard/vlogpilot-$Prefix.png"
  $remoteXml = "/sdcard/vlogpilot-$Prefix.xml"
  & $AdbPath -s $DeviceSerial shell screencap -p $remotePng | Out-Null
  & $AdbPath -s $DeviceSerial pull $remotePng (Join-Path $OutDir "$Prefix.png") | Out-Null
  & $AdbPath -s $DeviceSerial shell uiautomator dump $remoteXml | Out-Null
  & $AdbPath -s $DeviceSerial pull $remoteXml (Join-Path $OutDir "$Prefix.xml") | Out-Null
}

$adbPath = Resolve-Adb -Requested $Adb
$device = Resolve-Device -AdbPath $adbPath -RequestedSerial $Serial
$sdkRoot = Split-Path -Parent (Split-Path -Parent $adbPath)
$env:ANDROID_HOME = $sdkRoot
$env:ANDROID_SDK_ROOT = $sdkRoot
$env:ANDROID_SERIAL = $device

Write-Step "Device: $device"
Write-Step "Artifacts: $outputDir"

Write-Step "Waking device and clearing logcat"
& $adbPath -s $device shell input keyevent 224 | Out-Null
& $adbPath -s $device shell wm dismiss-keyguard | Out-Null
& $adbPath -s $device logcat -c | Out-Null

if (-not $SkipInstall) {
  Write-Step "Installing debug APK without clearing app data"
  $installer = Join-Path $PSScriptRoot "install-debug-vivo.ps1"
  if (-not (Test-Path -LiteralPath $installer)) {
    throw "Installer script not found: $installer"
  }
  & powershell -ExecutionPolicy Bypass -File $installer -AndroidDir $AndroidDir -Adb $adbPath
} else {
  Write-Step "Skipping APK install"
}

Write-Step "Launching $Package"
$activity = (& $adbPath -s $device shell cmd package resolve-activity --brief $Package | Select-Object -Last 1).Trim()
if (-not $activity -or $activity -match "No activity found") {
  throw "Could not resolve launcher activity for $Package"
}
& $adbPath -s $device shell am start -n $activity | Out-Null
Start-Sleep -Seconds 2
Save-DeviceArtifacts -AdbPath $adbPath -DeviceSerial $device -OutDir $outputDir -Prefix "launch"

if ($SkipMaestro) {
  Write-Step "Skipping Maestro because -SkipMaestro was passed"
} elseif (-not $RunMaestro) {
  Write-Step "Skipping Maestro by default. Pass -RunMaestro to run UI flows."
} else {
  $flows = Join-Path $repoRoot ".maestro\flows"
  $destructiveFlows = @(
    Get-ChildItem -Path $flows -Filter "*.yaml" -File |
      Select-String -Pattern "clearState:\s*true" |
      ForEach-Object { $_.Path } |
      Sort-Object -Unique
  )
  if ($destructiveFlows.Count -gt 0 -and -not $AllowClearState) {
    throw "Refusing to run Maestro because these flows clear app data: $($destructiveFlows -join ', '). Remove clearState:true or pass -AllowClearState deliberately."
  }

  $maestroCmd = Get-Command $Maestro -ErrorAction SilentlyContinue
  if (-not $maestroCmd) {
    Write-Warning "Maestro not found on PATH. Install it, then rerun without -SkipMaestro."
  } else {
    $maestroHelperInstalled = (& $adbPath -s $device shell pm path dev.mobile.maestro 2>$null) -match "package:"
    if (-not $maestroHelperInstalled -and -not $AllowMaestroHelperInstall) {
      throw "Maestro will install its helper app dev.mobile.maestro on the phone. Rerun with -AllowMaestroHelperInstall if that is OK."
    }
    $maestroLog = Join-Path $outputDir "maestro.log"
    Write-Step "Running Maestro flows"
    & $maestroCmd.Source test $flows 2>&1 | Tee-Object -FilePath $maestroLog
    if ($LASTEXITCODE -ne 0) {
      Save-DeviceArtifacts -AdbPath $adbPath -DeviceSerial $device -OutDir $outputDir -Prefix "maestro-failure"
      & $adbPath -s $device logcat -d > (Join-Path $outputDir "logcat.txt")
      throw "Maestro regression failed. See $outputDir"
    }
  }
}

Write-Step "Collecting final logcat and screenshot"
& $adbPath -s $device logcat -d > (Join-Path $outputDir "logcat.txt")
Save-DeviceArtifacts -AdbPath $adbPath -DeviceSerial $device -OutDir $outputDir -Prefix "final"
Write-Step "Android regression complete"
