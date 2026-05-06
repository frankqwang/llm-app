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

## Prerequisites

- Android Studio Ladybug+ (or Hedgehog with AGP 8.7+)
- JDK 17
- Android SDK Platform 35 + Build-Tools 35
- Test device: Android 12+ (minSdk 31), **8 GB RAM minimum** for Gemma 4 E2B-IT, ideally Snapdragon 8 Gen 2+ / Dimensity 9000+ / Tensor G3+ for usable GPU decode speed
- A Hugging Face account that has accepted the Gemma 4 license at https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm

## Three paths to first run

### 0. Sanity check (5 min, no build needed)

Before sinking time into building from source, install the **official prebuilt APK** to confirm Gemma 4 E2B-IT runs on your test device:

- Play Store: https://play.google.com/store/apps/details?id=com.google.ai.edge.gallery
- Or APK from https://github.com/google-ai-edge/gallery/releases/latest

Sign in with HF, download "Gemma-4-E2B-it" inside the app, run a chat. If the device is too weak or model download fails because of HF license, you'll learn that *before* investing in the build pipeline.

### 1. Build from source with Hugging Face OAuth (the official path)

Required if you want the in-app HF login + model download flow to work end-to-end.

1. **Register an OAuth app on Hugging Face**: https://huggingface.co/settings/applications/new
   - Redirect URI: pick a custom scheme like `com.example.gemmaapp://oauthredirect` (any scheme you control)
   - Scopes: `read-repos`, `manage-repos` (latter only if you want push, optional)
2. **Replace placeholders**:
   - `android/app/src/main/java/com/google/ai/edge/gallery/common/ProjectConfig.kt`
     - `clientId` → your HF OAuth client ID
     - `redirectUri` → the full URI you registered (e.g. `com.example.gemmaapp://oauthredirect`)
   - `android/app/build.gradle.kts`
     - `manifestPlaceholders["appAuthRedirectScheme"]` → just the scheme part (e.g. `com.example.gemmaapp`)
3. Open `android/` in Android Studio, let Gradle sync, hit Run.
4. In the app, sign in to HF and download "Gemma-4-E2B-it".

### 2. Build from source + sideload model (skips OAuth setup)

Faster if you don't want to deal with HF OAuth right now. Build the app, manually push the `.litertlm` to the device.

1. Build & install the APK (you can leave the placeholders alone — the OAuth flow only triggers on download). Run once so the app's data dir exists.
2. From your laptop, `huggingface-cli login` (or just `wget` if you have a token), then download `gemma-4-E2B-it.litertlm` from `https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm`.
3. Push to the device's app-specific external storage:
   ```
   adb push gemma-4-E2B-it.litertlm /sdcard/Android/data/com.google.aiedge.gallery/files/<expected-subpath>/
   ```
   The exact subpath the app expects can be verified by opening the app's "Models" screen — it shows where it's looking. (TODO: pin this once we run it once.)
4. Restart the app; the Models screen should mark Gemma 4 E2B-IT as already downloaded.

### 3. Trim to a focused chat app (post-validation)

Once paths 1 or 2 are verified working, candidates to remove for a leaner prototype:
- CameraX + image-input flows (`llm_ask_image` task type)
- Audio-input flows (`llm_ask_audio`)
- TFLite (used for non-LLM tasks like image classification)
- Firebase Analytics + Messaging (`apply false` already, but pull dependencies)
- ML Kit GenAI prompt
- Agent Skills system (`skills/` folder, `SkillAllowlist`)
- Prompt Lab + Tiny Garden + Mobile Actions tasks
- HF OAuth flow (replace with hardcoded model path / sideload)

## Hardware reality check

| Device tier | Expected experience with Gemma 4 E2B-IT |
|---|---|
| Snapdragon 8 Gen 3 / 8 Elite, 12+ GB RAM | Fast (>20 tok/s decode with GPU + MTP) |
| Snapdragon 8 Gen 2 / Dimensity 9300 / Tensor G3, 8 GB RAM | Usable (10–20 tok/s) |
| 6 GB RAM phones | Likely OOM or very slow (model is 2.4 GB on disk, peak ~5–6 GB) |

## Upstream reference

- Gallery (this app's origin): https://github.com/google-ai-edge/gallery
- LiteRT-LM (inference framework): https://github.com/google-ai-edge/LiteRT-LM
- LiteRT-LM Kotlin getting started: https://github.com/google-ai-edge/LiteRT-LM/blob/main/docs/api/kotlin/getting_started.md
- Gemma 4 E2B-IT model card: https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm
