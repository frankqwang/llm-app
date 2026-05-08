# Changelog

Cumulative engineering log for the on-device pipeline. Each version block
covers WHAT changed, WHY (the bug or constraint that drove it), and where
to find it in the tree.

---

## v3.1 — c-end UX + reliability + perf pass (2026-05-07)

Pushed as commit [`ef3ecb3`](https://github.com/frankqwang/llm-app/commit/ef3ecb3).

This is the "make it actually work end-to-end on a phone" pass. v3.0 wired
the pipeline up but ran into a wall of "almost works" issues: every shot
failed render, every event went through full pipeline, the UI stalled
during long runs, and crashes from missing native models bricked the
worker. After v3.1 the app opens straight onto VlogPilot, produces real
MP4s, and shows the AI's per-stage output as it happens.

Verified on vivo X200 Pro (Dimensity 9400, 8 GB RAM, ~150 photos / 30 days).

### UX

#### App opens directly on VlogPilot

- **Before:** Open app → gallery home → find VlogPilot card → tap → pick
  a model → enter task screen. Three taps before any work.
- **Why:** c-end users don't care about gallery's AI Chat / Agent Skills /
  Models management. They want a vlog from photos, full stop.
- **After:** `startDestination = ROUTE_VLOGPILOT`. App opens straight onto
  the VlogPilot screen with one Generate button. Top-right Apps icon goes
  to the gallery home for advanced features.
- **Files:** [GalleryNavGraph.kt](android/app/src/main/java/com/google/ai/edge/gallery/ui/navigation/GalleryNavGraph.kt) +
  new [VlogPilotRootScreen.kt](android/app/src/main/java/com/google/ai/edge/gallery/customtasks/vlogpilot/VlogPilotRootScreen.kt).

#### One button instead of two

- **Before:** "Scan album" + "Generate vlog candidates" — user had to scan
  before they could generate.
- **Why:** The orchestrator does its own ingest+segment internally. The
  preview scan was redundant work.
- **After:** Single Generate button. Doubles as Cancel during a run.
- **Files:** [VlogPilotScreen.kt](android/app/src/main/java/com/google/ai/edge/gallery/customtasks/vlogpilot/VlogPilotScreen.kt).

#### Decision-chain viewer per event

- **Before:** Single status line "evt_005: director" was the only visibility.
- **After:** One expandable card per event. Inside: Browser story summary,
  Audience emotional brief, Director title + tone + narrative arc, Editor
  per-shot list with caption + duration + rationale, Critic issues, Render
  output. Auto-refreshes every 3 s by polling
  `filesDir/decisions/<eventId>/`.
- **Files:** `EventDecisionCard` in
  [VlogPilotScreen.kt](android/app/src/main/java/com/google/ai/edge/gallery/customtasks/vlogpilot/VlogPilotScreen.kt) +
  new [DecisionStore.kt](android/app/src/main/java/com/google/ai/edge/gallery/customtasks/vlogpilot/worker/DecisionStore.kt).

#### Embedded MP4 player

- **Before:** Rendered MP4 only viewable via `adb pull`.
- **After:** Decision card's Render section embeds a `VideoView` (9:16,
  standard MediaController). Tap-to-play inline. No extra dependency.
- **Files:** `MiniVideoPlayer` in [VlogPilotScreen.kt](android/app/src/main/java/com/google/ai/edge/gallery/customtasks/vlogpilot/VlogPilotScreen.kt).

### Reliability — 8 real bugs

#### FGS type required on Android 14+

- **Symptom:** Tap Generate → instant `InvalidForegroundServiceTypeException`
  → app crash.
- **Root cause:** Android 14+ requires `foregroundServiceType` to be
  declared at runtime in the `ForegroundInfo`, not just in the manifest.
  We were using the 2-arg constructor which defaults to type=NONE.
- **Fix:** 3-arg `ForegroundInfo(notifId, notif, FOREGROUND_SERVICE_TYPE_DATA_SYNC)`.
- **Files:** [VlogPipelineWorker.kt:85-93](android/app/src/main/java/com/google/ai/edge/gallery/customtasks/vlogpilot/worker/VlogPipelineWorker.kt).

#### Native SEGV when face_landmarker.task missing

- **Symptom:** Pipeline starts, immediately crashes in
  `Java_com_google_mediapipe_framework_Graph_nativeStartRunningGraph` →
  `strlen(null)`.
- **Root cause:** MediaPipe's `createFromOptions` does NOT throw a Java
  exception when the model file is missing. It SEGVs in native code, which
  Kotlin try/catch cannot intercept.
- **Fix:** Probe for the file (filesDir/models then bundled assets) BEFORE
  calling MediaPipe. If absent, set `landmarker = null` and
  `detect()` returns empty list. Pipeline gracefully degrades (no person
  IDs) instead of crashing.
- **Files:** [FaceDetector.kt:32-71](android/app/src/main/java/com/google/ai/edge/gallery/customtasks/vlogpilot/perception/FaceDetector.kt).

#### Agent JSON parse can kill an event

- **Symptom:** Random events fail with
  `JsonDecodingException: Unexpected JSON token at offset 349`.
- **Root cause:** Gemma 4 E2B occasionally emits malformed JSON for the
  longer schemas (Critic was the worst offender). The unhandled throw
  propagated out and the whole event was marked failed.
- **Fix:** Each of the 5 agents wraps `parseToJsonElement` in try/catch
  and falls back to its sane default on parse failure. Pipeline continues.
- **Files:** [CriticAgent.kt](android/app/src/main/java/com/google/ai/edge/gallery/customtasks/vlogpilot/agents/CriticAgent.kt),
  [AudienceAgent.kt](android/app/src/main/java/com/google/ai/edge/gallery/customtasks/vlogpilot/agents/AudienceAgent.kt),
  [DirectorAgent.kt](android/app/src/main/java/com/google/ai/edge/gallery/customtasks/vlogpilot/agents/DirectorAgent.kt),
  [EditorAgent.kt](android/app/src/main/java/com/google/ai/edge/gallery/customtasks/vlogpilot/agents/EditorAgent.kt),
  [MontageAgent.kt](android/app/src/main/java/com/google/ai/edge/gallery/customtasks/vlogpilot/agents/MontageAgent.kt).

#### CaptionFilter without font kills ffmpeg

- **Symptom:** Every shot render fails with `ffmpeg failed: 1 null`.
- **Root cause:** ffmpeg's `drawtext` filter REQUIRES `fontfile=` — without
  it, the entire filter graph aborts. We were emitting `drawtext=text='...'`
  unconditionally even when fontPath was null.
- **Fix:** CaptionFilter returns empty string when fontPath is null.
  ShotRenderer then skips the comma-prefix, ffmpeg renders without
  captions instead of failing. Also bundled Source Han Sans CN Bold
  (8.5 MB) so the happy path covers CJK captions.
- **Files:** [CaptionFilter.kt](android/app/src/main/java/com/google/ai/edge/gallery/customtasks/vlogpilot/render/CaptionFilter.kt) +
  [assets/fonts/SourceHanSansSC-Bold.otf](android/app/src/main/assets/fonts/).

#### libx264 not available in ffmpeg-kit-16kb min

- **Symptom:** Every shot render fails with
  `Unknown encoder 'libx264'`.
- **Root cause:** The `ffmpeg-kit-16kb` min variant doesn't bundle libx264
  (GPL license). We hardcoded `-c:v libx264 -preset veryfast -crf 22`.
- **Fix:** Runtime-probe ffmpeg's `-encoders` list, pick the best
  available: `h264_mediacodec` (hardware H.264, ~10x faster, low battery)
  → `libx264` → `mpeg4` (LGPL fallback, always present). Cached for the
  process lifetime.
- **Files:** new [EncoderProbe.kt](android/app/src/main/java/com/google/ai/edge/gallery/customtasks/vlogpilot/render/EncoderProbe.kt) +
  [ShotRenderer.kt](android/app/src/main/java/com/google/ai/edge/gallery/customtasks/vlogpilot/render/ShotRenderer.kt) +
  [CompositeRenderer.kt](android/app/src/main/java/com/google/ai/edge/gallery/customtasks/vlogpilot/render/CompositeRenderer.kt).

#### Worker static-var lost on process death

- **Symptom:** OOM kill / force-stop / vendor power-saver kills the
  foreground service. WorkManager retries the worker. The worker reads
  `VlogPilotModelRegistry.resolvedModel` → null → fails with a generic
  "no model" message.
- **Root cause:** A `@Volatile var` is process-local. Process dies, var
  dies with it.
- **Fix (partial):** Also persist the model name to SharedPreferences. The
  in-process fast path still uses the static var. On restart, the worker
  reads the persisted name and surfaces a precise error: "Pipeline
  interrupted (process killed). Tap Generate again — model 'gemma-4-E2B-it'
  is still imported."
- **Pragmatic compromise:** A complete fix requires HiltWorker plumbing +
  fully reconstructing the gallery's `Model` from disk. Deferred — the
  user-visible behaviour now is "one extra tap to recover" instead of
  silent failure.
- **Files:** [VlogPilotModelRegistry.kt](android/app/src/main/java/com/google/ai/edge/gallery/customtasks/vlogpilot/runtime/VlogPilotModelRegistry.kt) +
  [VlogPipelineWorker.kt](android/app/src/main/java/com/google/ai/edge/gallery/customtasks/vlogpilot/worker/VlogPipelineWorker.kt).

#### ClipTokenizer was reading the wrong directory

- **Symptom:** Text embeddings always all-zeros (silent), CLIP-based
  recall degraded to sharpness-only ranking.
- **Root cause:** ModelDownloader writes vocab/merges to
  `<filesDir>/models/`. ClipTokenizer.tryLoad only looked at
  `assets/models/`. Files were on disk but invisible to the tokenizer.
- **Fix:** Two-tier resolution — filesDir first, then bundled assets.
- **Files:** [ClipTokenizer.kt:62-83](android/app/src/main/java/com/google/ai/edge/gallery/customtasks/vlogpilot/perception/ClipTokenizer.kt).

#### MediaLoader couldn't decode video URIs

- **Symptom:** Every video asset logged `loadImage failed:
  content://media/external/video/...` — videos silently dropped from
  perception.
- **Root cause:** `BitmapFactory.decodeStream` cannot decode an MP4
  container. We were sending all asset types through it.
- **Fix:** Branch on `asset.mediaType` — IMAGE/LIVE_PHOTO use
  BitmapFactory on the still, VIDEO uses `MediaMetadataRetriever.getFrameAtTime`
  on the middle of the clip.
- **Files:** [MediaLoader.kt:23-50](android/app/src/main/java/com/google/ai/edge/gallery/customtasks/vlogpilot/perception/MediaLoader.kt).

### Performance — 7 wins

#### ABI splits

- **Before:** APK 520 MB. Native libs (ffmpeg-kit, MediaPipe, TFLite,
  ONNX Runtime, LiteRT-LM) shipped for arm64-v8a + armeabi-v7a + x86 +
  x86_64 = 4× the size for zero benefit on modern phones.
- **After:** `ndk { abiFilters += listOf("arm64-v8a") }`. APK 243 MB.
  x86 emulator users would need to comment this out (we don't target it).
- **Files:** [build.gradle.kts](android/app/build.gradle.kts).

#### maxEvents=2 cap

- **Before:** 7 detected events × ~15 min full pipeline = ~90 min wall
  clock.
- **After:** New events capped to 2 most-recent (~20 min total). Resume
  events bypass the cap (see next).
- **Files:** [PipelineOrchestrator.kt](android/app/src/main/java/com/google/ai/edge/gallery/customtasks/vlogpilot/worker/PipelineOrchestrator.kt).

#### Resume mode

- **Before:** A cancelled run loses the 10–15 min of VLM work per event
  (cached decision JSONs are re-derived from scratch on the next Generate).
- **After:** Events with `timeline_final.json` cached but no MP4 yet
  (i.e. previous render failed) skip all 5 agent stages and go straight
  to ffmpeg. Bypasses the maxEvents cap (free, why not).
- **Files:** `resumeEvents` / `newEvents` split in
  [PipelineOrchestrator.kt](android/app/src/main/java/com/google/ai/edge/gallery/customtasks/vlogpilot/worker/PipelineOrchestrator.kt).

#### Per-asset Perception cache

- **Before:** Every Generate re-runs YOLO + face + CLIP + NSFW + sharpness
  on every photo (~1–2s each, 100 photos = ~3 min).
- **After:** Per-asset Perception JSON-cached at
  `filesDir/perception_cache/<assetId>.json`. Same album re-run = full
  cache hit = perception phase ~30s instead of ~3 min.
- **Files:** new [PerceptionCache.kt](android/app/src/main/java/com/google/ai/edge/gallery/customtasks/vlogpilot/perception/PerceptionCache.kt).

#### DecisionStore.loadAll moved to Dispatchers.IO

- **Symptom:** UI scrolling/expanding cards mid-run was visibly laggy.
  Logcat showed `Choreographer: Skipped 144 frames` (≈ 2.4 s freeze).
- **Root cause:** Polling 30+ JSON files every 2 s on the Main thread.
- **Fix:** `withContext(Dispatchers.IO) { DecisionStore.loadAll(...) }`.
  Polling interval also relaxed from 2 s → 3 s.
- **Files:** [VlogPilotViewModel.kt:64-72](android/app/src/main/java/com/google/ai/edge/gallery/customtasks/vlogpilot/VlogPilotViewModel.kt).

#### Bitmap recycle

- **Before:** Per-asset Perception creates ~6 Bitmaps (cover, face crops,
  scaled intermediates, contact-sheet cells). Native pixel buffers held
  until GC. 100 assets = ~1.3 GB peak heap → low-memory-killer risk.
- **After:** Three explicit recycle points:
  - PerceptionEngine.analyze: `cover.recycle()` at end + face crops
  - BitmapPrep.letterbox/toX: scaled intermediates
  - MontageBuilder.renderCell: cell after compositing
- **Result:** Peak heap roughly halved (~700 MB).
- **Files:** [PerceptionEngine.kt](android/app/src/main/java/com/google/ai/edge/gallery/customtasks/vlogpilot/perception/PerceptionEngine.kt),
  [BitmapPrep.kt](android/app/src/main/java/com/google/ai/edge/gallery/customtasks/vlogpilot/perception/BitmapPrep.kt),
  [MontageBuilder.kt](android/app/src/main/java/com/google/ai/edge/gallery/customtasks/vlogpilot/pipeline/MontageBuilder.kt).

#### MontageAgent sends all contact-sheet pages

- **Before:** Events with >121 photos (one sheet's capacity) lost
  context past the first page — agent only saw `sheets.first()`.
- **After:** One VLM call per sheet, then merge results (concat
  storyline_summary, dedupe key_moments, union characters_observed).
- **Files:** [MontageAgent.kt](android/app/src/main/java/com/google/ai/edge/gallery/customtasks/vlogpilot/agents/MontageAgent.kt).

### Ingest filters

#### PhotoIngest.Filter

- New filter parameters with sensible defaults:
  - `cameraOnly = true` — MediaStore RELATIVE_PATH whitelist on `DCIM/`,
    drops Pictures/Screenshots, Pictures/WeChat, BaiduNetdisk, Telegram,
    Download, etc. Cuts asset count by ~50% on a typical phone.
  - `maxVideoSizeBytes = 100 MB` — skip oversized videos that would burn
    CPU on frame extraction + transcoding.
  - `maxImageSizeBytes = 50 MB` — skip RAW exports / panoramas.
  - `minImageSizeBytes = 50 KB` — skip thumbnail-sized chat fragments.
- Uses `MediaStore.MediaColumns.RELATIVE_PATH` (API 29+, our minSdk is 31).
- **Files:** [PhotoIngest.kt](android/app/src/main/java/com/google/ai/edge/gallery/customtasks/vlogpilot/ingest/PhotoIngest.kt).

### Prompts — anti-example-leak

- **Before:** MONTAGE / AUDIENCE / DIRECTOR system prompts contained
  concrete example values inside the JSON schema:
  ```
  "characters_observed": ["主角A：穿蓝衣女孩", "主角B：父亲"],
  "narrative_arc": ["开场远景", "主角入场", "关键互动", "情绪高潮", "余韵收尾"],
  ```
- **Symptom:** Gemma 4 E2B (2B params) treated these as ground truth and
  copied them verbatim into outputs regardless of actual photo content.
  Every event had the same characters and arc.
- **After:** Replaced concrete values with `<...>` placeholder syntax +
  added explicit "do not copy schema placeholders" instruction.
- **Files:** [PromptStrings.kt](android/app/src/main/java/com/google/ai/edge/gallery/customtasks/vlogpilot/agents/PromptStrings.kt).

### Monitoring

#### PerfTimer + perf.json

- Wraps each agent call + render in `System.nanoTime` measurements.
  Persisted to `filesDir/decisions/<eventId>/perf.json`.
- UI shows per-event one-line summary: "总 52s · 感知 3s (148 张, 缓存 72) ·
  browse 15s audience 4s ...".
- **Files:** new [PerfTimer.kt](android/app/src/main/java/com/google/ai/edge/gallery/customtasks/vlogpilot/worker/PerfTimer.kt).

#### Per-event perf log line

- One line in logcat per event: `perf evt_002:
  encoder=h264_mediacodec total=52808ms render=2400ms ...`
- Greppable via `adb logcat | grep perf`.

### Tests

#### JsonExtractorTest

- 10 cases: clean object, nested braces, code-fence wrapper, `<think>`
  tag, prose prefix/suffix, escaped quotes inside strings, unclosed braces,
  garbage, array extraction.
- Run via `./gradlew :app:testDebugUnitTest`. Catches regressions in the
  every-agent JSON-extract codepath. 10/10 passed in 25 s.
- **Files:** new [test/.../JsonExtractorTest.kt](android/app/src/test/java/com/google/ai/edge/gallery/customtasks/vlogpilot/agents/JsonExtractorTest.kt).

### Documentation

#### README + setup-device.ps1 wording

- **Before:** "Support image — only if you want vision inputs (off keeps
  things simpler)" implied it was optional for VlogPilot too.
- **After:** Now correct: VlogPilot does not gate on the import-time
  `Support image` flag at all (AgentRuntime always passes
  `supportImage = true` to LiteRT-LM at runtime). Other vision tasks like
  LLM Ask Image still want it on, so keeping it on is recommended.
- **Files:** [README.md](README.md), [scripts/setup-device.ps1](scripts/setup-device.ps1).

### Bundled assets

- `assets/fonts/SourceHanSansSC-Bold.otf` — 8.5 MB, Adobe Source Han
  Sans CN Bold subset, for CJK captions in ffmpeg drawtext.
- `assets/bgm/{warm,cool,vibrant,muted,cinematic,vintage,neutral}.mp3` —
  47 MB total, 7 royalty-free tracks copied over from
  `pc-pilot/assets/bgm/`. BgmManager picks based on Director's tone.

### Files

- 5 new Kotlin sources:
  - [VlogPilotRootScreen.kt](android/app/src/main/java/com/google/ai/edge/gallery/customtasks/vlogpilot/VlogPilotRootScreen.kt)
  - [perception/PerceptionCache.kt](android/app/src/main/java/com/google/ai/edge/gallery/customtasks/vlogpilot/perception/PerceptionCache.kt)
  - [render/EncoderProbe.kt](android/app/src/main/java/com/google/ai/edge/gallery/customtasks/vlogpilot/render/EncoderProbe.kt)
  - [worker/DecisionStore.kt](android/app/src/main/java/com/google/ai/edge/gallery/customtasks/vlogpilot/worker/DecisionStore.kt)
  - [worker/PerfTimer.kt](android/app/src/main/java/com/google/ai/edge/gallery/customtasks/vlogpilot/worker/PerfTimer.kt)
- 1 new test:
  [agents/JsonExtractorTest.kt](android/app/src/test/java/com/google/ai/edge/gallery/customtasks/vlogpilot/agents/JsonExtractorTest.kt)
- 26 modified Kotlin sources, 1 modified Gradle build script,
  1 modified PowerShell setup script, 1 modified README.

### Known gaps (deferred to v3.2+)

| Gap | Workaround now | Plan |
|---|---|---|
| `yolo26n_int8.tflite` not bundled / sourced | sceneClass = "unknown" for everyone, fine for downstream | Find or train a small YOLO TFLite |
| `mobilefacenet.tflite` not bundled / sourced | personId = UNK, no person-based shot filtering | Same |
| `mobileclip2_*.tflite` only available as 340 MB+ float files | CLIP image/text embeddings empty, recall falls back to sharpness | Find a sub-50 MB int8 variant |
| eventId is a per-run ordinal, not content-stable | Resume works only when segmentation is identical | Switch to `evt_<date>_<contentHash>` |
| Worker model recovery on process kill is "tap retry" | SharedPreferences-backed clear error, manual retry | Wire HiltWorker + reconstruct Model from gallery state |
| Single-line ffmpeg encoder probe runs on every fresh process | <100 ms cost, fine | Could persist probe result across app launches |

---

## v3.0 — Initial Android port (2026-05-07)

Pushed as commit [`dc46efe`](https://github.com/frankqwang/llm-app/commit/dc46efe).

The PC-side pc-pilot Python pipeline (Browser → Director → Editor →
Critic) ported to Android as a new VlogPilot custom task. All inference
runs on-device once Gemma 4 is imported. 38 Kotlin files, ~3,800 LoC.

Stack:
- VLM: Gemma 4 E2B-it int4 via litertlm 0.11.0 (MTP-accelerated)
- Object detection: Ultralytics YOLO26n int8 TFLite
- Face: MediaPipe FaceLandmarker + MobileFaceNet TFLite
- CLIP: MobileCLIP2-S1 dual-tower TFLite
- NSFW: AdamCodd ViT int8 ONNX
- Render: ffmpeg-kit-16kb 6.1.1

A v3.0.1 follow-up commit [`9b841f8`](https://github.com/frankqwang/llm-app/commit/9b841f8)
removed the parallel ModelDownloader for Gemma 4 in favour of the
gallery's existing `ModelAllowlist` + `DownloadWorker` infra, and made
AgentRuntime reuse an already-initialized engine if the user opened
Gemma 4 from another task first.

See the commit message for v3.0 for the full M1-M5 milestone breakdown.
