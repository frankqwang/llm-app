# Maestro UI Tests

End-to-end smoke and regression tests for VlogPilot.

## Recommended Local Run

Connect one Android phone with USB debugging enabled, unlock it, accept the USB debugging prompt, then run from the repository root:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\run-android-regression.ps1
```

The script:

- resolves `adb.exe` from the Android SDK
- checks that exactly one authorized device is connected
- wakes/unlocks the device where possible
- installs the debug APK through `scripts/install-debug-vivo.ps1`
- launches VlogPilot
- saves screenshots, UI XML, and logcat under `.debug/android-regression/<timestamp>/`

By default this does **not** run Maestro, because Maestro may install its helper app (`dev.mobile.maestro`) on a real phone. To run UI flows deliberately:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\run-android-regression.ps1 -RunMaestro -AllowMaestroHelperInstall
```

The script refuses to run any flow containing `clearState: true` unless `-AllowClearState` is explicitly passed.

For a run against an already installed build:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\run-android-regression.ps1 -SkipInstall
```

## One-Time Requirements

Install Maestro:

```bash
curl -Ls "https://get.maestro.mobile.dev" | bash
```

On Windows, make sure Maestro is available on `PATH`, then verify:

```powershell
maestro --version
```

ADB does not need to be on `PATH`; the regression script resolves it from:

```text
%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe
```

## Flows

| Flow | Tag | Coverage |
|---|---|---|
| `smoke.yaml` | `smoke` | App launches, the four main tabs render, key screen copy appears. |
| `tab-walk.yaml` | `quick` | Fast bottom-tab round trip. |
| `chat-fallback.yaml` | `regression` | Chat never goes silent after a generation command with no model imported. |
| `chat-layout.yaml` | `regression` | Chat input stays anchored above bottom navigation and replies appear. |
| `works-clean.yaml` | `regression` | Works remains a browse surface, not a full process/editor surface. |

## Test Strategy

Use three layers:

1. Unit tests for pure Kotlin logic.
2. Maestro flows for user-visible navigation and interaction regressions.
3. ADB artifacts for debugging failures: screenshot, UI tree, and logcat.

Model-bound generation can be covered on the same phone once the Gemma model is imported, but those tests should be kept out of fast PR smoke runs because they are long-running and device-dependent.
