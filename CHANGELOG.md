# Changelog

Cumulative engineering log for the on-device pipeline. Each version block
covers WHAT changed, WHY (the bug or constraint that drove it), and where
to find it in the tree.

---

## v4.4 — 5-agent prompt audit (3-loop pass) + EventScout 2-pass selection (2026-05-08)

Pushed as commit [`f22ba50`](https://github.com/frankqwang/llm-app/commit/f22ba50).

The selected events were "都很垃圾" (all junk) on real albums even after
the EventSelector content scoring landed — coarse signals (asset count,
GPS spread, faces) are the right shape but can't tell apart "kid's
birthday party" from "kitchen breakfast" without semantic context. And
the 5 agent prompts had drifted: each one had grown free-form over
multiple iterations, with worked examples that contradicted the schema,
fields the parser ignored, and missing constraints (Director picking
durations outside the audience's pace band; Editor never explaining
why_not_others; Critic emitting full ShotRequest rewrites that Gemma 4
E2B got wrong about half the time).

Two threads landed in one commit:

### EventScout — 2-pass event selection with VLM signal

- **Before:** EventSelector ranks all candidate events using cheap
  signals (asset/video count, GPS spread, faces, scene diversity) and
  picks the top `maxEvents`. Two events with identical coarse stats —
  one being "晨练公园" (good vlog material) and one being "外卖等餐"
  (stationary indoor selfies) — score the same.
- **After:** Two-pass.
  1. Coarse pre-rank: same EventSelector with no VLM signal. Top 4-6
     events go to scout.
  2. Scout pass: each top-K event gets a 3×3 contact sheet from its
     non-junk assets; Gemma 4 reads it and returns a `value`/`signal`
     pair (story arc strength, person presence, pacing potential).
  3. Final rank: EventSelector reruns with `scoutMap` injected,
     re-ranks, and picks `maxEvents`.
- **Cost:** 1 Gemma call per pre-ranked candidate (~3-5s on D9400 GPU),
  amortized via JSON cache in `filesDir/event_scout_cache/<eventId>.json`.
- **Files:** new
  [agents/EventScoutAgent.kt](android/app/src/main/java/com/google/ai/edge/gallery/customtasks/vlogpilot/agents/EventScoutAgent.kt),
  [pipeline/EventScoutSheetBuilder.kt](android/app/src/main/java/com/google/ai/edge/gallery/customtasks/vlogpilot/pipeline/EventScoutSheetBuilder.kt),
  [schemas/EventScout.kt](android/app/src/main/java/com/google/ai/edge/gallery/customtasks/vlogpilot/schemas/EventScout.kt),
  [worker/EventScoutRunner.kt](android/app/src/main/java/com/google/ai/edge/gallery/customtasks/vlogpilot/worker/EventScoutRunner.kt) +
  modified
  [worker/EventSelectionPlanner.kt](android/app/src/main/java/com/google/ai/edge/gallery/customtasks/vlogpilot/worker/EventSelectionPlanner.kt),
  [pipeline/EventSelector.kt](android/app/src/main/java/com/google/ai/edge/gallery/customtasks/vlogpilot/pipeline/EventSelector.kt).

### Prompt audit — 3-loop pass

All 7 prompts (VLM image, VLM video, Browser, Audience, Director,
Editor, Critic) were rewritten ground-up with:

- **One concrete worked example per prompt.** Gemma 4 E2B's in-context
  conformance to a fully-filled JSON example is much higher than to
  schema descriptions of `<placeholder>`. The example uses a concrete
  event (烧烤聚会) so the model can't safely copy it.
- **Tighter schema constraints.** Char limits for every string field,
  enum lists for every closed-set field, fallback-to-empty rules for
  optional fields.
- **Cross-stage signal flow:**
  - Audience emits `pace` enum (`snappy`/`balanced`/`lingering`).
  - Director's `target_duration_sec` is clamped to the corresponding
    pace band (15-18 / 18-22 / 22-28 sec) — Gemma can no longer pick
    20s for a "snappy" brief.
  - Director consumes `available_signals` (orchestrator-built summary
    of what assets actually exist by VLM scene/long-video/salient) so
    `visual_requirements` stays grounded in real footage instead of
    hallucinated.
  - Director emits `color_grade` enum directly; Editor/renderer use it
    as-is, falling back to `ColorGradeFromTone.pick(tone)` only when
    NEUTRAL.
  - Editor emits `chosen_index` + `runner_up_index` + `why_not_others`;
    rationale combines all three for richer breadcrumbs.
  - Critic emits `verdict` (`accept`/`revise`/`abort`) + `patches` (a
    small `field → value` map) instead of full `new_request` rewrites.
    Patch mode is much smaller per-token and Gemma handles it ~3× more
    reliably.

### Schema additions

- `Pace` enum, `CriticVerdict` enum, `RevisedRequest.patches:
  Map<String, String>`, `DirectorBrief.colorGrade: ColorGrade`,
  `VideoInsight.bestMomentWindowStart/End` (Editor.expandWindows uses
  this to seed candidate trim windows spanning the model-identified
  peak action, not just a single frame).
- All new fields have defaults — existing perception_cache JSONs
  deserialize cleanly, no cache wipe needed.

### Orchestrator changes

- `runEvent`'s critic loop now branches on verdict: `ACCEPT` breaks,
  `ABORT` writes a `critic_abort` breadcrumb and breaks (don't burn
  more model calls on a known dead end), `REVISE` applies patches.
- New `applyPatches(base, patches)` applies field-level updates: blank
  values are skipped for narrative fields (mood/visual_requirements)
  but honored for cosmetic fields (caption/transition/ken_burns) since
  empty IS a valid "remove this" intent.
- New `buildAvailableSignals(eventAssets, perceptions)` produces the
  scene-grouped + long-video + salient summary fed into the Director
  prompt.
- Removed 192 lines of dead duplicate (MutableMap-typed) overloads of
  `runAnnotationPass` / `annotateAsset` / `runEvent`. The HashMap
  variants were the live ones; the MutableMap copies were
  build-tolerated dead code.

### Defensive parsing hardening

- `AudienceAgent`: partial JSON ({"hook_strategy":"X",
  "emotional_payoff":""}) gets blank fields patched from the fallback
  rather than propagating empty strings into the Director prompt.
- `EditorAgent`: `chosen_index` is clamped to `[0, candidates.size-1]`
  before use — Gemma occasionally emits 0 (which would underflow to
  -1) or out-of-range numbers when it hallucinates entries.
- `CriticAgent`: revised requests with empty patches AND null
  newRequest are filtered out via `mapNotNull`.

### Verified

- Build green (`compileDebugKotlin` 4s).
- Tests green (13/13: 10 JsonExtractor + 3 EventSelector).
- Schema-cache compatible (no required fields added without defaults).

---

## v4.3 — Event selection: intent + power profile + pinned/excluded events + manifest (2026-05-08)

Pushed as commit [`d20e190`](https://github.com/frankqwang/llm-app/commit/d20e190).

The user wanted to bias event selection ("only travel events" / "skip
the kitchen ones I didn't like") and to throttle for thermal headroom
on long runs. Added:

- **`VlogPilotRunConfig`** — runtime knobs: `intent` (NORMAL / TRAVEL /
  KIDS / FOOD / etc.), `powerProfile` (BALANCED / EFFICIENT / TURBO),
  `pinnedEventIds` (always include), `excludedEventIds` (never
  include). Persisted to filesDir between runs so the UI can remember.
- **`PowerPacer`** — Inserts paced sleeps after perception batches and
  VLM calls. EFFICIENT adds ~200ms after each VLM call (smoother
  thermal); TURBO removes pacing; BALANCED is the middle.
- **Intent-aware EventSelector** — `intent.eventBias()` adjusts
  `valueScore` per event based on heuristics (TRAVEL boosts GPS
  spread + outdoor scene tags, KIDS boosts face count, FOOD boosts
  stationary-indoor + close-range scene tags).
- **EventSelectionManifest** — Persisted JSON manifest of every
  selection decision: which events were considered, their valueScore
  breakdown, why each was selected/excluded, plus the run config.
  Lives at `filesDir/decisions/_event_selection.json`. Lets the UI
  show "why these events" without re-running selection.
- **DecisionStore.writeEventSelection** — atomic write to the manifest
  path; surfaces "Selecting events..." progress to the UI.
- **Files:** new
  [runtime/VlogPilotRunConfig.kt](android/app/src/main/java/com/google/ai/edge/gallery/customtasks/vlogpilot/runtime/VlogPilotRunConfig.kt),
  [runtime/PowerPacer.kt](android/app/src/main/java/com/google/ai/edge/gallery/customtasks/vlogpilot/runtime/PowerPacer.kt),
  [worker/EventSelectionPlanner.kt](android/app/src/main/java/com/google/ai/edge/gallery/customtasks/vlogpilot/worker/EventSelectionPlanner.kt),
  [worker/EventSelectionManifest.kt](android/app/src/main/java/com/google/ai/edge/gallery/customtasks/vlogpilot/worker/EventSelectionManifest.kt) +
  modified [pipeline/EventSelector.kt](android/app/src/main/java/com/google/ai/edge/gallery/customtasks/vlogpilot/pipeline/EventSelector.kt).

---

## v4.2 — Content-scored event selection + thermal pacing (2026-05-08)

Pushed as commit [`2f05f66`](https://github.com/frankqwang/llm-app/commit/2f05f66).

Replaced recency-only `events.take(maxEvents)` with content-scored
`EventSelector.rank()`. valueScore weights:

- Asset count (log-scaled — a 30-photo trip > a 100-burst-shot session)
- Video presence (count + total duration in seconds)
- Time span (8h trip > 30min coffee)
- GPS spread (real travel vs at-home)
- Story signals (face count, scene-cut diversity)
- Recency only as small tiebreaker

Plus thermal pacing — Dimensity 9400 GPU heat-throttles past ~7 min
sustained inference. Inserts ~150-200ms paced sleeps in the perception
loop and after each VLM call to keep junction temp below 80°C.

- **Files:** new
  [pipeline/EventSelector.kt](android/app/src/main/java/com/google/ai/edge/gallery/customtasks/vlogpilot/pipeline/EventSelector.kt) +
  test
  [pipeline/EventSelectorTest.kt](android/app/src/test/java/com/google/ai/edge/gallery/customtasks/vlogpilot/pipeline/EventSelectorTest.kt) +
  modified
  [worker/PipelineOrchestrator.kt](android/app/src/main/java/com/google/ai/edge/gallery/customtasks/vlogpilot/worker/PipelineOrchestrator.kt).

---

## v4.0 / v4.1 — VLM-only perception architecture + 64K bytecode fix (2026-05-08)

Pushed as commits
[`35a1ed9`](https://github.com/frankqwang/llm-app/commit/35a1ed9),
[`4ab249e`](https://github.com/frankqwang/llm-app/commit/4ab249e),
[`1ccb38f`](https://github.com/frankqwang/llm-app/commit/1ccb38f).

### v4.0 — VLM annotation replaces CLIP/YOLO/FaceNet TFLite stack

The TFLite small-model stack (CLIP, YOLO, MobileFaceNet) was always a
known gap from v3.0 — the public sub-50MB int8 sources we wanted to
ship never materialized. Switched to one Gemma VLM call per asset.

- **VlmAnnotator** runs once per asset, produces `VlmTags` (scene,
  subjects, action, mood, time_feel, salient, narrative_role_hint).
  Per-video also produces `VideoInsight` (frame timestamps, summary,
  action_arc, best_moment_index, bad_moment_indices).
- **Recall.kt** scores via `semanticOverlap()` on tags rather than
  cosine over CLIP embeddings. Comparable signal, much richer
  context (model can reason about the tags during curate).
- **Cost:** ~3-5s/asset on D9400 GPU + MTP. Per-asset Perception is
  JSON-cached in `filesDir/perception_cache/<assetId>.json` — repeat
  runs over the same album are essentially free.
- **VideoFrameSheetBuilder** builds N-frame contact sheets per video
  (uniform sampling + scene-cut anchors), Gemma annotates the sheet
  in one call.
- **`Workspace` tabs** — `Process` / `Decisions` / `Results` UI for
  watching the run and inspecting per-event outputs. Polls
  `decisions/<eventId>/` every 3s on Dispatchers.IO.
- **Deleted:** `ClipEmbedder`, `ClipTokenizer`, `YoloDetector`,
  `YoloObj`, `FaceEmbedder`, `FaceClusterer`, `TfliteLoader`,
  `SharpestWindowPicker`. Don't re-add them. The semantic signal
  CLIP was supposed to provide now comes from VlmAnnotator.

### v4.1 — Pipeline restart loop fix (the real "v4 ships" milestone)

- **Symptom:** A single `doWork()` invocation ran download → ingest →
  perceive → init → download → ... in a tight 2-second loop on the
  same worker. JobScheduler showed ONE WorkSpec, run_attempt_count=1.
  Three wrong theories burned an hour: "Gemma 5min cold start" (false,
  it's ~15s), "vivo logcat ACL" (false, just HWUI overflow), "vivo BTM
  kills FGS" (false, not even a kill).
- **Root cause:** `PipelineOrchestrator.run()`'s coroutine state
  machine exceeded ART's 64K bytecode limit. The line was in logcat
  the whole time: `Method exceeds compiler instruction limit: 64126`.
  Past 64K, ART falls back to interpreter mode and the
  resume-from-saved-label dispatch silently breaks — every
  `progress(...)` suspension resumes at label 0 = the start of
  `run()`.
- **Fix:** Extract per-event body into `runEvent()` and per-asset
  annotation into `runAnnotationPass()` — each suspend point now
  lives in its own state machine, none individually exceeds 64K.
  **This rule is permanent: keep `run()` thin.** The compile warning
  (`Method exceeds compiler instruction limit: NNNNN`) is just a
  warning, not an error, so the build still passes — watch for it.
- **Files:** [worker/PipelineOrchestrator.kt](android/app/src/main/java/com/google/ai/edge/gallery/customtasks/vlogpilot/worker/PipelineOrchestrator.kt).

### Observability — disk-backed breadcrumbs

vivo OriginOS HWUI / SurfaceComposerClient floods the 4MB logcat ring
buffer fast enough to overwrite our `Log.d` entries within ~7 minutes.
To survive that, every state transition writes to disk:

- `_state.txt` — single-line current pipeline state
- `_state_history.jsonl` — append-only trail of every PipelineProgress
- `_state_fatal.txt` — stack trace if `run()` throws above the
  per-event catch
- `agent_log/agent.jsonl` — per-LLM-call timing + outcome
- `decisions/<eventId>/*.json` — every agent's structured output
- `perception_cache/<assetId>.json` — per-asset Perception
- `candidates/<eventId>.mp4` — final rendered vlogs

Read via `adb shell run-as com.google.aiedge.gallery cat files/<path>`.
Survives logcat overflow AND process death.

---

## v3.2 — Recall time-window, Critic 2-iter, ColorGrade, Sharpest-window picker (2026-05-08)

Pushed as commit [`0f797d0`](https://github.com/frankqwang/llm-app/commit/0f797d0).

Targeted audit pass on the 5-agent chain after v3.1 went end-to-end.

- **Recall time-window** — Per-shot recall is constrained to
  ±1.5 hours from the slot's predicted timestamp (instead of "any
  asset in the event"). Stops the Editor from picking the same
  morning shot for every slot in an evening event.
- **Critic 2-iter cap** — Was 3, now 2. The third iteration almost
  never improved the cut on Gemma 4 E2B; it just shuffled assets.
  `MAX_CRITIC_ITER = 2` in PipelineOrchestrator.
- **ColorGrade** — Director picks one of {warm, cool, vibrant, muted,
  cinematic, vintage, neutral}, applied via ffmpeg `colorchannelmixer`
  / `eq` chains in ColorGradeFilter. (v4.4 made this explicit-emit
  rather than keyword-inferred.)
- **Sharpest-window picker** — Video shot trims pick the sharpest
  N-second window inside the asset's duration, not "first N seconds".
  (v4.0 superseded this with VideoWindowPicker that consumes VLM
  best_moment_index + window hints.)

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
