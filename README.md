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

## Prerequisites

- Android Studio Ladybug+ (or Hedgehog with AGP 8.7+)
- JDK 17
- Android SDK Platform 35 + Build-Tools 35
- Test device: Android 12+ (minSdk 31), 8 GB RAM minimum for Gemma 4 E2B-IT
- A Hugging Face account that has accepted the Gemma 4 license at https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm

## Sideload run-through (chosen path)

This skips HuggingFace OAuth entirely. We build the app with placeholders untouched, push the `.litertlm` to the phone, and use the app's built-in **Import Model** flow — which registers it as an "imported" model and adds it to the LLM Chat task automatically.

### Step 1 — download the model on your laptop

The model is gated, so you must be logged in with an HF account that's accepted the Gemma 4 terms first.

```powershell
# PowerShell, after `pip install -U huggingface_hub` and `huggingface-cli login`
huggingface-cli download `
  litert-community/gemma-4-E2B-it-litert-lm `
  gemma-4-E2B-it.litertlm `
  --local-dir C:\dev\llm-app\models
```

This writes `C:\dev\llm-app\models\gemma-4-E2B-it.litertlm` (~2.4 GB). The `models/` folder is gitignored.

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
6. Confirm in the import dialog. The app copies the file into its own storage (`/sdcard/Android/data/com.google.aiedge.gallery/files/gemma-4-E2B-it.litertlm`) and registers it.
7. After "Model imported successfully", the model appears under "Imported Models" and is available in the **AI Chat** task.

After step 6 you can `adb shell rm /sdcard/Download/gemma-4-E2B-it.litertlm` to reclaim 2.4 GB on the phone (the app keeps its own copy).

### Step 5 — make it fast (vivo X200 Pro / Dimensity 9400)

Default settings on first launch may run on CPU. In the chat config (gear icon in chat screen):

- **Accelerator**: GPU (CPU fallback OK if GPU init fails)
- **Multi-token prediction (MTP)**: ON — this is the v0.11.0 feature; biggest win
- **Thinking mode**: OFF for raw decode benchmarks (it injects thinking tokens which inflate per-response latency even though tok/s itself is fine)

Expected on Dimensity 9400 + GPU + MTP: 20–30+ tok/s decode. If you're still seeing single digits, check logcat for backend init errors (`adb logcat -s LiteRT:*,LlmModelHelper:*`).

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

Once Gemma 4 E2B-IT runs at expected speed on the X200 Pro, candidates to remove:

- CameraX + `llm_ask_image` flow
- Audio + `llm_ask_audio` flow
- TFLite (used for non-LLM tasks)
- Firebase Analytics + Messaging
- ML Kit GenAI prompt
- Agent Skills (`skills/` folder, `SkillAllowlist`)
- Prompt Lab + Tiny Garden + Mobile Actions tasks
- AICore runtime path (we only need `litert_lm` runtime)
- HF OAuth flow + download worker (since we sideload)

## Upstream reference

- Gallery (this app's origin): https://github.com/google-ai-edge/gallery
- LiteRT-LM (inference framework): https://github.com/google-ai-edge/LiteRT-LM
- LiteRT-LM Kotlin getting started: https://github.com/google-ai-edge/LiteRT-LM/blob/main/docs/api/kotlin/getting_started.md
- Gemma 4 E2B-IT model card: https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm
- LiteRT-LM v0.11.0 release notes: https://github.com/google-ai-edge/LiteRT-LM/releases/tag/v0.11.0
