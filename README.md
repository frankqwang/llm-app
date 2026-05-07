# llm-app — Gemma 4 E2B-IT on-device Android prototype

Forked from [google-ai-edge/gallery](https://github.com/google-ai-edge/gallery) (Apache-2.0). Goal: prototype-validate Gemma 4 E2B-IT running locally via [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) on Android, then trim to a focused chat app.

## Layout

```
llm-app/
  android/      # Gradle project copied from gallery/Android/src
    app/        # main application module (Kotlin + Compose + Hilt)
    ...
  README.md     # this file
```

## Local changes vs upstream

- `android/gradle/libs.versions.toml`: bumped `litertlm` from `0.10.0` → `0.11.0` (released 2026-05-05). v0.11.0 enables Multi-Token Prediction (MTP) for Gemma 4, claimed >2× decode speed on mobile GPUs.
- `android/app/.../ui/llmchat/LlmChatModelHelper.kt` and `ui/common/ModelPageAppBar.kt`: **bug fix** — upstream gallery hardcodes a local `supportsSpeculativeDecoding = false` stub that (a) strips the MTP toggle from the chat config dialog, and (b) silently disables MTP at runtime even if a model declares the capability. Patched to read from `model.capabilityToTaskTypes` instead.

## Prerequisites

- Android Studio Ladybug+ (or Hedgehog with AGP 8.7+)
- **JDK 21 from Adoptium / Temurin** — required because LiteRT-LM 0.11.0 ships Java 21 class files. The project uses Gradle Daemon Toolchain (`android/gradle/gradle-daemon-jvm.properties`), so on first build Gradle will auto-discover an installed Temurin 21 or auto-download one. Install manually on each machine:
  - Windows: `winget install EclipseAdoptium.Temurin.21.JDK` (or download `.msi` from https://adoptium.net/temurin/releases/?version=21)
  - macOS: `brew install --cask temurin@21`
  - Linux: distro package or Adoptium tarball
- Android SDK Platform 35 + Build-Tools 35 (Android Studio installs these)
- Test device: Android 12+ (minSdk 31), 8 GB RAM minimum for Gemma 4 E2B-IT
- A Hugging Face account that has accepted the Gemma 4 license at https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm

## Sideload run-through (chosen path)

This skips HuggingFace OAuth entirely. We build the app with placeholders untouched, push the `.litertlm` to the phone, and use the app's built-in **Import Model** flow — which registers it as an "imported" model and adds it to the LLM Chat task automatically.

### Step 1 — download the model on your laptop

The model is gated, so you must be logged in with an HF account that's accepted the Gemma 4 terms first.

```powershell
# PowerShell, after `pip install -U huggingface_hub` and `hf auth login`.
# Note: `huggingface-cli` is deprecated — the binary is now `hf`.
hf download litert-community/gemma-4-E2B-it-litert-lm `
  gemma-4-E2B-it.litertlm `
  --local-dir C:\dev\llm-app\models
```

This writes `C:\dev\llm-app\models\gemma-4-E2B-it.litertlm` (~2.6 GB). The `models/` folder is gitignored.

The HF repo has 4 files (10.9 GB total) — only download the generic `.litertlm`. Skip:
- `gemma-4-E2B-it-web.task` — MediaPipe runtime, not LiteRT-LM
- `gemma-4-E2B-it_qualcomm_qcs8275.litertlm` — Qualcomm QCS8275 NPU build
- `gemma-4-E2B-it_qualcomm_sm8750.litertlm` — Snapdragon 8 Elite NPU build

Qualcomm-specific NPU builds won't run on MediaTek (Dimensity) or Tensor SoCs.

### Step 2 — build & install the APK

```powershell
cd C:\dev\llm-app\android
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

You don't need to fill in the HF OAuth placeholders — they only matter for the in-app download flow, which we're bypassing. The build will succeed with the existing `REPLACE_WITH_YOUR_*` strings as long as you don't try to log in inside the app.

### Step 3 — push the model to the phone

```powershell
adb push C:\dev\llm-app\models\gemma-4-E2B-it.litertlm /sdcard/Download/
```

`/sdcard/Download` is the easiest place because the system file picker shows it under "Downloads".

### Step 4 — import inside the app

1. Open the app on your phone.
2. Open the side drawer / "Models" screen.
3. Tap the **+** floating action button in the bottom-right.
4. Pick **From local model file**.
5. In the system file picker, navigate to **Downloads** → `gemma-4-E2B-it.litertlm`.
6. **Critical** — in the import dialog, before tapping confirm, toggle these switches **ON**:
   - **Support speculative decoding** — required for MTP. If left off, the SPECULATIVE_DECODING capability never gets attached to the model and the chat config won't show the runtime toggle, killing the v0.11.0 perf win.
   - **Support thinking** — only if you want thinking mode (off by default is fine for raw decode benchmarks)
   - **Support image** — recommended ON. VlogPilot does NOT gate on this flag (it asks the engine for vision at runtime regardless), but the gallery's other vision tasks like LLM Ask Image do filter by it. Off is fine if you only care about VlogPilot.
   - **Support audio** — only if you want audio inputs (off keeps things simpler)
   - **Compatible accelerators** — make sure GPU is selected
7. Tap confirm. The app copies the file into its own storage at `/sdcard/Android/data/com.google.aiedge.gallery/files/__imports/gemma-4-E2B-it.litertlm` and registers it.
8. After "Model imported successfully", the model appears under "Imported Models" and is available in the **AI Chat** task.

After step 7 you can `adb shell rm /sdcard/Download/gemma-4-E2B-it.litertlm` to reclaim 2.6 GB on the phone (the app keeps its own copy under `__imports/`).

### Step 5 — make it fast (vivo X200 Pro / Dimensity 9400)

Default settings on first launch may run on CPU. In the chat config (gear icon in chat screen):

- **Accelerator**: GPU (CPU fallback OK if GPU init fails)
- **Multi-token prediction (MTP)**: ON — this is the v0.11.0 feature; biggest win
- **Thinking mode**: OFF for raw decode benchmarks (it injects thinking tokens which inflate per-response latency even though tok/s itself is fine)

Expected on Dimensity 9400 + GPU + MTP: 40–60 tok/s decode (extrapolating from Google's published S26 Ultra benchmarks for the same SoC class).

## Troubleshooting

### Tail relevant logcat during a chat session

```powershell
adb logcat -c                                                        # clear
adb logcat AGLlmChatModelHelper:D LiteRtLm:D Engine:D AndroidRuntime:E *:S
```

Look for:
- `Preferred backend: GPU` — confirms GPU was selected (CPU here means runtime fell back)
- `Speculative decoding enabled: true` — confirms our patch + import-toggle landed and MTP is on for this session
- Any `Failed to initialize` / `falling back to CPU` lines — usually means the GPU backend rejected the model or device

### Decode is still single-digit tok/s

In likely-cause order:
1. **MTP toggle wasn't enabled at import time.** Re-import the model and flip "Support speculative decoding" ON in the import dialog.
2. **Chat config has runtime MTP off.** Open the chat → gear icon → look for "Enable speculative decoding". If the toggle is missing entirely, the patch in `ModelPageAppBar.kt` didn't apply or the import-time capability didn't stick — re-check both.
3. **Backend silently fell back to CPU.** Logcat will show `Preferred backend: CPU`. Open chat config → Accelerator → GPU.
4. **Thinking mode on with verbose answers.** Doesn't change tok/s, but inflates per-response wall time. Toggle off for benchmarking.
5. **First-message warm-up.** GPU shader compilation happens lazily on first inference (~5–15s). Measure on the second message, not the first.

### The "+" import button doesn't show

Side drawer → Models. The FAB lives there, not on the home screen task tiles.

### Import dialog rejects the file

Check `adb logcat -s ModelImportDialog:*`. Common causes: filename has spaces or non-ASCII chars, or storage permission was denied.

## Other paths (kept for reference)

### Sanity check via official APK (already done by user)

- Play Store: `com.google.ai.edge.gallery`
- Or APK from https://github.com/google-ai-edge/gallery/releases/latest

### Build from source with Hugging Face OAuth

Use this only if you want the in-app HF login + auto-download flow to work end-to-end.

1. Register an OAuth app: https://huggingface.co/settings/applications/new
   - Redirect URI: pick a scheme you control, e.g. `com.example.gemmaapp://oauthredirect`
   - Scopes: `read-repos`
2. Replace placeholders in:
   - `android/app/src/main/java/com/google/ai/edge/gallery/common/ProjectConfig.kt` (`clientId`, `redirectUri`)
   - `android/app/build.gradle.kts` (`manifestPlaceholders["appAuthRedirectScheme"]`)
3. Open `android/` in Android Studio, sync, Run.

## Trim plan (post-validation)

Once Gemma 4 E2B-IT runs at expected speed on the X200 Pro, here are the concrete deletion targets ordered roughly by ROI (size × independence). Each item is a self-contained surgery — do them one at a time, build between, commit.

### Tier 1: easy & high-impact

**Custom tasks we don't need** (`android/app/src/main/java/com/google/ai/edge/gallery/customtasks/`):
- `agentchat/` — Agent Skills + Skill Manager (large, ~30 files)
- `tinygarden/` — Tiny Garden task
- `mobileactions/` — Mobile Actions task
- `examplecustomtask/` — example only

After deleting these dirs, also remove their registrations in `customtasks/CustomTasksRegistry.kt` (or wherever active tasks are listed) and any references in `data/Tasks.kt`.

**Built-in tasks beyond chat** — in `data/Tasks.kt` and `data/BuiltInTaskId.kt`, drop:
- `LLM_ASK_IMAGE` and its UI under `ui/llmaskimage/` (if exists; otherwise inside `ui/llmchat/` shared)
- `LLM_ASK_AUDIO` and its UI
- `LLM_PROMPT_LAB` and `ui/llmpromptlab/`
- `IMAGE_GENERATION`, `IMAGE_CLASSIFICATION` — if present (TFLite-based, not LLM)

**Skills system** — `skills/` folder at repo root (allowlist JSONs etc.) and `data/SkillAllowlist.kt`.

### Tier 2: dependency removals (in `app/build.gradle.kts`)

After deleting feature code, drop these dependencies (delete the lines, sync gradle, fix any remaining references):
- `libs.tflite` / `libs.tflite.gpu` / `libs.tflite.support` — non-LLM ML
- `libs.camerax.*` (4 lines) — only used for image input
- `libs.firebase.bom` / `libs.firebase.analytics` / `libs.firebase.messaging` + remove `firebaseAnalytics` references in `DownloadRepository.kt`, `GalleryEvent.kt`
- `libs.mlkit.genai.prompt` — ML Kit prompt
- `libs.openid.appauth` — only used for HF OAuth, irrelevant when sideloading
- `libs.play.services.oss.licenses` + `libs.plugins.oss.licenses` — OSS license screen

Also remove the `google-services` plugin and Firebase manifest entries in `AndroidManifest.xml` (`com.google.firebase.messaging.default_notification_channel_id`, the firebase MESSAGING_EVENT receiver).

### Tier 3: runtime trimming

- `runtime/aicore/AICoreModelHelper.kt` — AICore (Gemini Nano via Android System Intelligence) path. We only use the `litert_lm` runtime, so delete this and the `RuntimeType.AICORE` branch in `runtime/ModelHelperExt.kt`.
- HF OAuth + download flow: `data/DownloadRepository.kt`, `worker/DownloadWorker.kt`, `common/ProjectConfig.kt` (the OAuth bit), and the entire HF login UI under `ui/auth/` if it exists. Replace the model manager UI to only support import.

### Tier 4: ID / branding (do last, after everything else stable)

- `applicationId` in `app/build.gradle.kts` — change from `com.google.aiedge.gallery` to your own. **WARNING**: this changes the app's data dir, so any imported model on the device becomes invisible. Plan to re-import after this change. Same goes for `namespace`.
- App icon, name, splash screen
- Strings under `app/src/main/res/values/strings.xml`

## Upstream reference

- Gallery (this app's origin): https://github.com/google-ai-edge/gallery
- LiteRT-LM (inference framework): https://github.com/google-ai-edge/LiteRT-LM
- LiteRT-LM Kotlin getting started: https://github.com/google-ai-edge/LiteRT-LM/blob/main/docs/api/kotlin/getting_started.md
- Gemma 4 E2B-IT model card: https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm
- LiteRT-LM v0.11.0 release notes: https://github.com/google-ai-edge/LiteRT-LM/releases/tag/v0.11.0
