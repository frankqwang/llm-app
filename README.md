# llm-app — VlogPilot, an on-device AI vlog generator

Forked from [google-ai-edge/gallery](https://github.com/google-ai-edge/gallery) (Apache-2.0). The app this repo ships is **VlogPilot**: tap one button and Gemma 4 E2B-IT, running fully on-device via [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM), turns the last 30 days of your photo album into AI-curated vlog candidates — no upload, no cloud, no account.

The 5-agent reasoning chain (Browser → Audience → Director → Editor → Critic) is ported from the [pc-pilot](pc-pilot/) Python reference. Per-asset semantic tags (scene/subjects/action/mood/salient + per-video best-moment frame index) come from a single VLM annotation call per asset. Render uses ffmpeg-kit with hardware H.264 (h264_mediacodec) on supported SoCs.

Verified end-to-end on **vivo X200 Pro** (Dimensity 9400 + GPU + MTP). Two events / 12 assets / ~7 minutes wall-clock to produce two ~6 MB 1080×1920 MP4s; perception + annotation cache make subsequent runs much faster.

For the engineering log of what changed and why between versions see [CHANGELOG.md](CHANGELOG.md). Project landmines and contributor notes live in [CLAUDE.md](CLAUDE.md).

## Layout

```
llm-app/
  android/                                                    # Gradle project (Kotlin + Compose + Hilt)
    app/src/main/java/com/google/ai/edge/gallery/
      customtasks/vlogpilot/                                  # the VlogPilot module — see Architecture below
        agents/                                               # Browser/Audience/Director/Editor/Critic + VlmAnnotator
        perception/                                           # cheap signals: faces, sharpness, NSFW
        pipeline/                                             # EventSegmenter, EventSelector, Recall, MontageBuilder, …
        render/                                               # ffmpeg-kit shot/composite renderers
        runtime/                                              # PowerPacer, VlogPilotModelRegistry
        schemas/                                              # @Serializable Asset/Perception/VlmTags/Timeline/…
        worker/                                               # PipelineOrchestrator + WorkManager Worker + breadcrumbs
  pc-pilot/                                                    # Python reference (server-side parity, debugging)
  README.md                                                    # this file
```

## Local changes vs upstream gallery

- **VlogPilot module under `customtasks/vlogpilot/`** is original to this fork (not in upstream).
- `android/gradle/libs.versions.toml`: bumped `litertlm` `0.10.0` → `0.11.0` (released 2026-05-05). v0.11.0 enables Multi-Token Prediction (MTP) for Gemma 4, >2× decode on mobile GPUs.
- `android/app/.../ui/llmchat/LlmChatModelHelper.kt`, `ui/common/ModelPageAppBar.kt`: **bug fix** — upstream hardcodes a local `supportsSpeculativeDecoding = false` stub that strips the MTP toggle from the import dialog and silently disables MTP even when a model declares the capability. Patched to read `model.capabilityToTaskTypes` instead.
- `ui/navigation/GalleryNavGraph.kt`: app launches directly into VlogPilot (`ROUTE_VLOGPILOT`); the gallery side-drawer is reachable but not the home screen.

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

### Tail relevant logcat during a pipeline run

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

## VlogPilot architecture

The pipeline is one `WorkManager` `CoroutineWorker` (`VlogPipelineWorker`) hosting a `PipelineOrchestrator` that drives 7+ stages. All state writes go to `filesDir/` so observers (a CLI debugger, the in-app Process tab, or a unit test) can replay any run.

```
PhotoIngest ──► EventSegmenter ──► EventSelectionPlanner (coarse rank)
                                       │
                                       ▼
                            Gemma 4 E2B-IT boots once
                                       │
                                       ▼
                       EventScout (3×3 contact sheet → VLM signal,
                        story arc / person / pacing per top-K event)
                                       │
                                       ▼
                       EventSelectionPlanner reruns with scoutMap,
                        picks top-N events
                                       │
                                       ▼
                       Cheap perception on assets in selected events
                        (faces / sharpness / NSFW — no LLM, JSON-cached)
                                       │
                                       ▼
                       VlmAnnotator per asset
                        (scene / subjects / action / mood / salient
                         + per-video best_moment_index + window)
                                       │
                                       ▼
                       For each top-N event, runEvent():
                         Browser  → EventMemory       (montage VLM call)
                         Audience → AudienceBrief + pace
                                    (snappy / balanced / lingering)
                         Director → DirectorBrief + shot blueprint + color_grade
                                    (target_duration clamped to pace band;
                                     visual_requirements grounded in
                                     buildAvailableSignals output)
                         Editor   → Timeline v1
                                    (tag-based recall + per-shot VLM curate
                                     with runner-up + why-not-others)
                         Critic   → Critique
                                    (verdict accept/revise/abort + patches;
                                     up to 2 iterations)
                         Render   → MP4 via ffmpeg-kit
                                    (h264_mediacodec when available)
```

Key design points:

- **Method extraction is load-bearing**, not cosmetic. Inlined, `PipelineOrchestrator.run()`'s coroutine state machine exceeds ART's 64K bytecode limit and the resume-from-saved-label dispatch breaks (every progress suspension restarts run() from the top, looping forever). Per-event body lives in `runEvent()`; annotation pass lives in `runAnnotationPass()`. **If you add another pass, keep it in its own suspend function.** See [CHANGELOG.md v4.1](CHANGELOG.md) for the full debug story.
- **Two-pass event selection** (v4.4). EventSelectionPlanner ranks twice — once on cheap signals (asset count, video, GPS spread, faces, scene diversity, intent bias) to pick top-K candidates, then EventScout reads each top-K event's contact sheet and Gemma returns story-arc / person / pacing signals. Final rank uses both. Coarse signals alone confused "kid's birthday party" vs "kitchen breakfast"; the scout pass disambiguates with one ~3-5s VLM call per candidate, JSON-cached.
- **Caching is per-asset, two-tier**: cheap perception JSON in `filesDir/perception_cache/<assetId>.json`; the VLM tags get patched into the same file once Gemma annotates. EventScout has its own `event_scout_cache/<eventId>.json`. A second run over the same album skips all VLM work.
- **Pace + color flow from Audience → Director → renderer.** Audience emits a `pace` enum; Director's `target_duration_sec` is clamped to that pace's band (snappy 15-18s / balanced 18-22s / lingering 22-28s). Director also emits `color_grade` enum directly so the Editor + renderer don't have to keyword-infer from `tone`.
- **Critic uses patches, not rewrites** (v4.4). Instead of re-emitting full ShotRequest objects, Critic returns `{shot_order, patches: {field: value}}` — much smaller per-token and ~3× more reliable on Gemma 4 E2B. Verdict is explicit: `accept` ships as-is, `revise` applies patches, `abort` writes a breadcrumb and ships the working timeline (no more wasted critic iterations on dead-end events).
- **Available signals fed to Director.** Before Director writes the shot blueprint, the orchestrator computes a scene-grouped + long-video + salient summary of what assets actually exist. Director's prompt requires `visual_requirements` to match a real available scene — stops hallucinated shots like "举杯特写" when no such asset exists in the event.
- **Per-VLM-call timeout** (90s, in `AgentRuntime.ask`): a stuck Gemma call returns the partial text instead of hanging the worker forever.

## Observability — debugging on device without logcat

Vivo OriginOS (and similar OEMs) flood logcat from `HWUI`/`SurfaceComposerClient` fast enough to overwrite the 4 MB ring buffer in ~7 minutes, drowning `Log.d` entries. To survive that, every run writes to disk:

| File (under `/data/data/com.google.aiedge.gallery/files/`) | Content |
|---|---|
| `_state.txt` | Single line: `<unix_millis>\t<stage>\t<detail>` — current pipeline state |
| `_state_history.jsonl` | Append-only trail of every PipelineProgress event |
| `_state_fatal.txt` | Stack trace if `run()` throws above the per-event catch |
| `agent_log/agent.jsonl` | Per-LLM-call timing + outcome (`ok` / `timeout` / `error:Foo`) |
| `decisions/<eventId>/*.json` | EventMemory / AudienceBrief / DirectorBrief / Timeline / Critique / perf |
| `perception_cache/<assetId>.json` | Per-asset Perception (cheap signals + VLM tags) |
| `candidates/<eventId>.mp4` | Final rendered vlog |

Read any of them via:
```powershell
adb shell run-as com.google.aiedge.gallery cat files/_state.txt
adb shell run-as com.google.aiedge.gallery cat files/_state_history.jsonl
adb shell run-as com.google.aiedge.gallery cat files/agent_log/agent.jsonl
```

Or stream live (without sleep-polling on Windows):
```bash
while true; do
  adb shell run-as com.google.aiedge.gallery cat files/_state.txt
  echo
  sleep 2
done
```

If you have unrestricted logcat (you can verify with `adb logcat -d -t 5` returning *anything* tagged `PipelineOrchestrator`), bump the buffer first to keep our `Log.d` entries from being flushed by HWUI:
```
adb logcat -G 16M
adb logcat -v threadtime PipelineOrchestrator:D AgentRuntime:D EditorAgent:D *:S
```

## VlogPilot user flow

1. Open the app (lands directly on the VlogPilot screen, not the gallery home).
2. Tap **生成** (Generate). Permission prompt for `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` if not yet granted.
3. Watch the Process tab. Stages: download → ingest → perceive → annotate → event_start → browse / audience / director / editor / critic / render → event_done.
4. When `all_done` lands, MP4 candidates appear in the Results tab. Tap any one to play inline.

Cancel any time via the **取消** button (calls `WorkManager.cancelUniqueWork`).

## Future cleanup (does NOT block VlogPilot working)

This fork still carries upstream gallery code paths VlogPilot doesn't use. They cost APK size + build time but aren't currently broken; cleanup is an APK-size optimization, not a correctness need.

- `customtasks/{agentchat,tinygarden,mobileactions,examplecustomtask}/` — other tasks the gallery shipped, dead in our app
- `ui/{llmaskimage,llmpromptlab,auth}/` — non-VlogPilot UIs
- `data/SkillAllowlist.kt` + `skills/` directory at repo root — Skills system
- `runtime/aicore/AICoreModelHelper.kt` — AICore (Gemini Nano via Android System Intelligence); we only use `litert_lm`
- HF OAuth flow (`common/ProjectConfig.kt`'s OAuth bits, `worker/DownloadWorker.kt` HF parts) — VlogPilot reuses the gallery's local-file import, not OAuth download
- `app/build.gradle.kts` deps that fall out: `libs.tflite*`, `libs.camerax.*`, `libs.firebase.*`, `libs.openid.appauth`, `libs.play.services.oss.licenses`, the `google-services` plugin

Don't do this until VlogPilot is locked in. Each removal needs a build + smoke test.

## Upstream reference

- Gallery (this app's origin): https://github.com/google-ai-edge/gallery
- LiteRT-LM (inference framework): https://github.com/google-ai-edge/LiteRT-LM
- LiteRT-LM Kotlin getting started: https://github.com/google-ai-edge/LiteRT-LM/blob/main/docs/api/kotlin/getting_started.md
- Gemma 4 E2B-IT model card: https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm
- LiteRT-LM v0.11.0 release notes: https://github.com/google-ai-edge/LiteRT-LM/releases/tag/v0.11.0
