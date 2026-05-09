param(
  [string]$AndroidDir = (Resolve-Path (Join-Path $PSScriptRoot "..\android")).Path,
  [string]$Adb = "",
  [int]$PollSeconds = 90
)

$ErrorActionPreference = "Stop"

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

function Tap-Point {
  param([string]$AdbPath, [int]$X, [int]$Y)
  & $AdbPath shell input tap $X $Y | Out-Null
}

function Tap-BoundsCenter {
  param([string]$AdbPath, [string]$Bounds)
  if ($Bounds -match "\[(\d+),(\d+)\]\[(\d+),(\d+)\]") {
    $x = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
    $y = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
    Tap-Point -AdbPath $AdbPath -X $x -Y $y
    return $true
  }
  return $false
}

function Tap-FirstMatchingBounds {
  param([string]$AdbPath, [string]$Xml, [string[]]$Patterns)
  foreach ($pattern in $Patterns) {
    $match = [regex]::Match($Xml, $pattern, [System.Text.RegularExpressions.RegexOptions]::Singleline)
    if ($match.Success) {
      $bounds = $match.Groups["bounds"].Value
      if (Tap-BoundsCenter -AdbPath $AdbPath -Bounds $bounds) {
        return $true
      }
    }
  }
  return $false
}

function Tap-VivoInstallPrompt {
  param([string]$AdbPath)

  & $AdbPath shell uiautomator dump /sdcard/install.xml 2>$null | Out-Null
  $xml = (& $AdbPath shell cat /sdcard/install.xml 2>$null | Out-String)
  if ([string]::IsNullOrWhiteSpace($xml)) {
    return $false
  }

  # This script is saved as UTF-8 with BOM so Windows PowerShell 5 reads
  # Chinese installer text correctly.
  $hasChineseInstallerText =
    $xml -match '继续安装' -or
    $xml -match '已了解应用的风险检测结果' -or
    $xml -match '外部来源应用' -or
    $xml -match '安全守护提示您' -or
    $xml -match '风险检测结果'
  if (-not $hasChineseInstallerText) {
    return $false
  }

  $checkboxTapped = Tap-FirstMatchingBounds -AdbPath $AdbPath -Xml $xml -Patterns @(
    'class="android\.widget\.CheckBox"[^>]*checked="false"[^>]*bounds="(?<bounds>\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\])"',
    'checkable="true"[^>]*checked="false"[^>]*bounds="(?<bounds>\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\])"'
  )

  if ($checkboxTapped) {
    Start-Sleep -Milliseconds 300
    & $AdbPath shell uiautomator dump /sdcard/install.xml 2>$null | Out-Null
    $xml = (& $AdbPath shell cat /sdcard/install.xml 2>$null | Out-String)
  }

  $buttonTapped = Tap-FirstMatchingBounds -AdbPath $AdbPath -Xml $xml -Patterns @(
    'text="继续安装"[^>]*bounds="(?<bounds>\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\])"'
  )

  return ($checkboxTapped -or $buttonTapped)
}

$adbPath = Resolve-Adb -Requested $Adb
$gradle = Join-Path $AndroidDir "gradlew.bat"
if (-not (Test-Path -LiteralPath $gradle)) {
  throw "gradlew.bat not found under $AndroidDir"
}

$deviceLine = (& $adbPath devices | Select-String -Pattern "`tdevice" | Select-Object -First 1)
if (-not $deviceLine) {
  throw "No adb device is connected"
}

$stdout = Join-Path $env:TEMP "vlogpilot-install-debug.out.log"
$stderr = Join-Path $env:TEMP "vlogpilot-install-debug.err.log"
Remove-Item -LiteralPath $stdout, $stderr -Force -ErrorAction SilentlyContinue

Write-Host "Starting Gradle installDebug..."
$proc = Start-Process `
  -FilePath $gradle `
  -ArgumentList ":app:installDebug" `
  -WorkingDirectory $AndroidDir `
  -PassThru `
  -RedirectStandardOutput $stdout `
  -RedirectStandardError $stderr

$deadline = (Get-Date).AddSeconds($PollSeconds)
$tapCount = 0
$lastStatus = Get-Date
while (-not $proc.HasExited -and (Get-Date) -lt $deadline) {
  Start-Sleep -Milliseconds 600
  if (Tap-VivoInstallPrompt -AdbPath $adbPath) {
    $tapCount++
    Write-Host "Handled installer prompt ($tapCount)"
  }
  if (((Get-Date) - $lastStatus).TotalSeconds -ge 5) {
    Write-Host "installDebug still running... installer prompts handled: $tapCount"
    $lastStatus = Get-Date
  }
}

if (-not $proc.HasExited) {
  Write-Host "Install process still running after $PollSeconds seconds; waiting for Gradle to finish..."
}
$proc.WaitForExit()
$proc.Refresh()

$outText = if (Test-Path -LiteralPath $stdout) { Get-Content -LiteralPath $stdout -Raw } else { "" }
$errText = if (Test-Path -LiteralPath $stderr) { Get-Content -LiteralPath $stderr -Raw } else { "" }

if ($outText) { Write-Host $outText }
if ($errText) { Write-Host $errText }

$gradleSucceeded = $outText -match "BUILD SUCCESSFUL"
if ($null -ne $proc.ExitCode -and $proc.ExitCode -ne 0 -and -not $gradleSucceeded) {
  throw "installDebug failed with exit code $($proc.ExitCode)"
}
if ($null -eq $proc.ExitCode -and -not $gradleSucceeded) {
  throw "installDebug finished without an exit code and Gradle success was not detected"
}

Write-Host "installDebug completed successfully."
