# llm-app — VlogPilot

On-device AI vlog editor. Point it at your phone's last 30 days of photos
and it produces curated short vlogs (one per detected event), running
Gemma 4 E2B-it locally via LiteRT-LM. No frame leaves the device.

```
inbox (MediaStore)
  ↓
ingest → segment events (8h gap / 72h span)
  ↓
perceive each photo (YOLO + face + CLIP + scene cuts + sharpness + NSFW)
  ↓
for each event:
  Browser   → EventMemory       (Gemma 4 reads the contact sheet)
  Audience  → AudienceBrief     (emotional payoff)
  Director  → DirectorBrief     (free-form tone, captions, shot blueprint)
  Editor    → Timeline v1       (per shot: CLIP+person+time recall → VLM picks 1)
  Critic    → Critique          (review + revise up to 3 shots)
  Render    → ffmpeg-kit         (xfade chain + drawtext caption + BGM)
  ↓
candidates/<eventId>.mp4
```

This repo is a fork of [google-ai-edge/gallery](https://github.com/google-ai-edge/gallery).
The gallery's model-management infra (HF download, `__imports/`, model
allowlist) is reused as-is; VlogPilot lives as a custom task at
[`android/.../customtasks/vlogpilot/`](android/app/src/main/java/com/google/ai/edge/gallery/customtasks/vlogpilot/).

The PC-side reference implementation lives at [`pc-pilot/`](pc-pilot/)
(Python, LM Studio + Qwen 3.5 9B). It's the design source of truth and
useful for prompt iteration; the Android port mirrors its semantics.

For the engineering log of what changed and why between versions see
[CHANGELOG.md](CHANGELOG.md).

---

## Quick start (vivo X200 Pro / any Android 12+ device, 8 GB RAM)

### 1. Build the APK

```powershell
cd C:\dev\llm-app\android
.\gradlew.bat :app:assembleDebug
# → android/app/build/outputs/apk/debug/app-debug.apk  (~243 MB)
```

JDK 21 required (LiteRT-LM 0.11.0 ships Java 21 class files; KAPT can't
read them on JDK 17). The Gradle daemon-toolchain auto-discovers an
installed Adoptium 21:

- Windows: `winget install EclipseAdoptium.Temurin.21.JDK`
- macOS: `brew install --cask temurin@21`
- Linux: distro package or Adoptium tarball

### 2. Sideload Gemma 4 E2B (one-time, ~2.6 GB)

The model is gated on HuggingFace; you need an HF account that has
accepted the Gemma 4 terms first.

```powershell
hf download litert-community/gemma-4-E2B-it-litert-lm `
  gemma-4-E2B-it.litertlm `
  --local-dir C:\dev\llm-app\models
```

Then push to the phone:

```powershell
adb push C:\dev\llm-app\models\gemma-4-E2B-it.litertlm /sdcard/Download/
```

### 3. Install the APK

```powershell
adb install -r android\app\build\outputs\apk\debug\app-debug.apk
```

Or use the bundled helper:

```powershell
.\scripts\setup-device.ps1   # uninstall + install + push model in one go
```

### 4. Import the model in-app

1. Open the app — you land on **VlogPilot** directly.
2. Tap the top-right **🟦 Apps** icon → gallery home → side drawer →
   **Models** → **+** → **From local model file** → pick
   `Downloads/gemma-4-E2B-it.litertlm`.
3. In the import dialog, before confirming, toggle:
   - ☑ **Support speculative decoding** (required for MTP perf — without it
     the runtime toggle never appears in chat config and you lose ~2× decode)
   - ☐ **Support thinking** (off; `<think>` tokens inflate latency)
   - **Compatible accelerators: GPU** (CPU fallback OK if GPU init fails)
   - `Support image` and `Support audio` are not required for VlogPilot
     (AgentRuntime asks LiteRT-LM for vision at runtime regardless of
     this flag). Leaving them off doesn't break VlogPilot. Other gallery
     tasks like LLM Ask Image do need them on.
4. Confirm. Wait for "Model imported successfully".

### 5. Tap Generate

Back on the VlogPilot screen:

1. Grant album permissions when prompted.
2. Pipeline runs as a foreground service. Lock the screen / put the app
   in the background — it keeps going. Notification stays on while running.
3. Decision cards below the button show the AI's per-stage output as it
   happens (Browser → Audience → Director → Editor → Critic → Render).
4. When `Render — 成片预览` shows up, the embedded VideoView plays the
   MP4 inline.

Default behaviour: cap to the 2 most-recent events for new full-pipeline
work; resume cached events (timeline ready but render previously failed)
without re-running the agents. See [CHANGELOG.md § Performance](CHANGELOG.md#performance--7-wins)
for the rationale.

### Output paths

All under the app's private dir `/data/data/com.google.aiedge.gallery/files/`:

| Path | Contents |
|---|---|
| `candidates/<eventId>.mp4` | Final vlog MP4 |
| `decisions/<eventId>/event_memory.json` | Browser output |
| `decisions/<eventId>/audience.json` | Audience brief |
| `decisions/<eventId>/director.json` | Director brief |
| `decisions/<eventId>/timeline_v1.json` | Editor's first cut |
| `decisions/<eventId>/timeline_final.json` | Post-Critic revisions |
| `decisions/<eventId>/critique.json` | Critic issues |
| `decisions/<eventId>/perf.json` | Per-stage timing |
| `perception_cache/<assetId>.json` | Cached per-photo Perception |
| `models/` | OTA-downloaded perception models |

To pull an MP4 to PC:

```powershell
adb exec-out run-as com.google.aiedge.gallery `
  cat files/candidates/evt_002.mp4 > $env:USERPROFILE\Desktop\evt_002.mp4
```

---

## Tested device

vivo X200 Pro (MediaTek Dimensity 9400, 8 GB RAM, FunTouch / OriginOS).
Other 8 GB+ phones with arm64-v8a + Mali / Adreno GPU should work; the
APK is arm64-only by default (see [CHANGELOG § ABI splits](CHANGELOG.md#abi-splits)).

Expected wall-clock times (1080p output, ~150-asset album):

| Phase | Cold first run | Warm re-run (cache hit) |
|---|---|---|
| Ingest + segment | ~2 s | ~2 s |
| Perception (148 assets) | ~3 min | ~10 s |
| Per event Agent loop | ~10–15 min | (cached) |
| Per event Render (h264_mediacodec) | ~30–60 s | ~30–60 s |
| Per event Render (mpeg4 fallback) | ~2–3 min | ~2–3 min |

Battery: ~30 % over a full 2-event run (~25 min). Phone gets warm,
should be plugged in for longer runs.

---

## Architecture overview

```
android/app/src/main/java/com/google/ai/edge/gallery/customtasks/vlogpilot/
├── VlogPilotRootScreen.kt          App-launch landing (Scaffold + TopAppBar)
├── VlogPilotScreen.kt              Generate button + decision card list + video player
├── VlogPilotViewModel.kt           Pipeline state + decisions polling
├── VlogPilotTask.kt                CustomTask registration (Hilt @IntoSet)
├── runtime/
│   └── VlogPilotModelRegistry.kt   Static + SharedPreferences model handoff
├── ingest/
│   └── PhotoIngest.kt              MediaStore query + EXIF + Live Photo pairing + filters
├── perception/
│   ├── PerceptionEngine.kt         Orchestrates all per-asset perception
│   ├── PerceptionCache.kt          Per-asset JSON cache
│   ├── YoloDetector.kt             Object detection (graceful no-op if model missing)
│   ├── FaceDetector.kt             MediaPipe Face Landmarker (with native-SEGV guard)
│   ├── FaceEmbedder.kt             MobileFaceNet embeddings
│   ├── ClipEmbedder.kt             MobileCLIP image + text encoders
│   ├── ClipTokenizer.kt            CLIP BPE (filesDir + assets dual lookup)
│   ├── NsfwClassifier.kt           ONNX ViT
│   ├── ImageQuality.kt             Sharpness + brightness
│   ├── SceneCutDetector.kt         Histogram chi-square scene boundary
│   ├── BitmapPrep.kt               Letterbox + tensor preprocessing (with recycle)
│   ├── MediaLoader.kt              Image/video/live-photo to Bitmap
│   └── TfliteLoader.kt             Generic TFLite interpreter loader
├── pipeline/
│   ├── EventSegmenter.kt           8h gap / 72h span / merge-runts
│   ├── MontageBuilder.kt           Contact sheet generator (numbered cells)
│   └── Recall.kt                   Top-K candidate ranker for editor
├── agents/
│   ├── AgentRuntime.kt             Wraps LlmChatModelHelper for suspend API
│   ├── PromptStrings.kt            5 system prompts
│   ├── JsonExtractor.kt            Lenient JSON extraction (10-test-covered)
│   ├── MontageAgent.kt             Browser (multi-page sheet support)
│   ├── AudienceAgent.kt            Emotional brief
│   ├── DirectorAgent.kt            Free-form vlog script
│   ├── EditorAgent.kt              Per-shot VLM curate
│   └── CriticAgent.kt              Review + revise
├── render/
│   ├── VideoRenderer.kt            Top-level Timeline → MP4
│   ├── ShotRenderer.kt             Per-shot intermediate (image: zoompan / video: blurred-bg compose)
│   ├── CompositeRenderer.kt        xfade chain + BGM mux
│   ├── EncoderProbe.kt             Runtime detect best video codec
│   ├── CaptionFilter.kt            ffmpeg drawtext (no-font safe)
│   ├── ColorGradeFilter.kt         Per-shot grade + polish
│   ├── BgmManager.kt               Tone → bundled BGM mp3
│   ├── FFmpegProbe.kt              Duration + fps probe
│   └── VersionArchive.kt           Move previous candidate to .v1.mp4 etc
├── worker/
│   ├── VlogPipelineWorker.kt       CoroutineWorker host + foreground notification
│   ├── PipelineOrchestrator.kt     The actual sequence (resume + new event split)
│   ├── PipelineEventBus.kt         Hilt singleton state bus
│   ├── PipelineProgress.kt         Sealed progress events
│   ├── ModelDownloader.kt          OTA fetch face_landmarker, NSFW, CLIP vocab
│   ├── DecisionStore.kt            Persist + read all 6 JSONs per event
│   └── PerfTimer.kt                Per-stage wall-clock measurement
└── schemas/
    └── Schemas.kt                  All @Serializable data classes
```

Total: ~45 Kotlin files. The orchestrator runs entirely off the UI thread
inside a `CoroutineWorker` registered via WorkManager unique-work
(KEEP policy → only one pipeline run at a time).

---

## Development

```powershell
# Run unit tests
cd C:\dev\llm-app\android
.\gradlew.bat :app:testDebugUnitTest

# Build + install in one shot
.\gradlew.bat :app:assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk

# Stream pipeline logs
adb logcat -v time | findstr /R "VlogPilot PipelineOrch EncoderProbe ShotRenderer EventDone EventFailed"

# Watch frame-drop signals
adb logcat | findstr "Choreographer.*Skipped"

# Per-event timing summary
adb logcat | findstr "perf evt_"
```

To clear caches and run the pipeline from scratch:

```powershell
adb shell run-as com.google.aiedge.gallery rm -rf `
  files/decisions files/perception_cache files/candidates
```

The font / BGM / Gemma 4 imported model survive — only AI decisions and
per-asset perception are flushed.

---

## Known limitations (status quo)

These are tracked in [CHANGELOG.md § Known gaps](CHANGELOG.md#known-gaps-deferred-to-v32):

- **No yolo26n / mobilefacenet / MobileCLIP small-int8** delivered yet.
  Pipeline degrades gracefully (CLIP recall → sharpness ranking,
  faces → no person clustering, YOLO → sceneClass="unknown") but quality
  is well below PC reference.
- **eventId is a per-run ordinal**, so the same event in two consecutive
  Generate runs has the same ID only if segmentation is identical (i.e.
  album hasn't changed). On future runs with new photos the IDs shift.
- **Worker model recovery on process kill is one-tap-retry**, not
  seamless. Foreground service usually survives, but vendor power-saving
  on FunTouch / MIUI / EMUI may kill the worker — re-tap Generate to
  recover. SharedPreferences holds enough to surface a clear error.

---

## PC-side reference (`pc-pilot/`)

Python implementation of the same Browser → Director → Editor → Critic
loop, but using LM Studio (local Qwen 3.5 9B) + ACE-Step (text-to-music)
+ ultralytics + InsightFace + SigLIP + PySceneDetect. Faster to iterate
on prompts because everything runs on a desktop GPU; the Android port
mirrors its semantics. See [pc-pilot/README.md](pc-pilot/README.md) for
its own quickstart.

---

## Upstream reference

- Gallery (this app's origin): https://github.com/google-ai-edge/gallery
- LiteRT-LM: https://github.com/google-ai-edge/LiteRT-LM
- LiteRT-LM Kotlin getting started: https://github.com/google-ai-edge/LiteRT-LM/blob/main/docs/api/kotlin/getting_started.md
- Gemma 4 E2B-IT model card: https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm
- LiteRT-LM v0.11.0 (MTP enablement): https://github.com/google-ai-edge/LiteRT-LM/releases/tag/v0.11.0

## License

Same as upstream gallery (Apache 2.0). Bundled binary assets (font, BGM)
are licensed independently — see their respective sources.
