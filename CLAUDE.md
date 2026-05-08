# Project context for Claude

This file is loaded into context at the start of each session. Read it before
making non-trivial changes.

## What this project is

`llm-app` is an Android Kotlin/Compose app forked from
[google-ai-edge/gallery](https://github.com/google-ai-edge/gallery).
The product is **VlogPilot** — tap one button, Gemma 4 E2B-IT runs fully on-device
via LiteRT-LM, and the user's recent photo album becomes one or two ~6 MB 1080×1920
MP4 vlog candidates. No upload, no cloud, no account.

The 5-agent reasoning chain (Browser → Audience → Director → Editor → Critic) is
ported from the Python reference under `pc-pilot/`. Per-asset semantic tagging
uses one Gemma VLM call per asset (`VlmAnnotator`); the cheap perception layer
(faces, sharpness, NSFW) is no-LLM and parallelizable. Event selection is itself
a **two-pass VLM workflow** — coarse rank → EventScout (3×3 contact sheet read
by Gemma per top-K candidate) → final rank — see landmine #4.

End-to-end verified on **vivo X200 Pro** (Dimensity 9400, GPU + MTP). 12 assets /
2 events / ~7 min wall-clock for two MP4s. Cache makes repeat runs much faster.

## Where to read the docs

`README.md` has the layout, sideload flow, **Architecture** section with the
pipeline diagram, **Observability** section with the breadcrumb file paths,
and the **Future cleanup** list of upstream-gallery code we still carry.
`CHANGELOG.md` is the version-by-version engineering log — read the most recent
entry for what shipped last.

## Project landmines

These are gotchas that have already cost time. Read before debugging.

### 1. `PipelineOrchestrator.run()` cannot exceed ART's 64K bytecode limit

If you add another stage / progress event / try-catch / loop directly inside
`run()`, the coroutine state machine can grow past 64KB and ART's resume-from-
saved-label dispatch silently breaks. Symptom: every `progress(...)` suspension
makes `run()` re-enter from the top — visible as `download → ingest → perceive →
init → download → ingest → ...` looping every 2 seconds on a single doWork
invocation. JobScheduler shows ONE WorkSpec, run_attempt_count=1 — it is NOT
WorkManager retrying, NOT vivo BTM, NOT FGS. It's a Kotlin compiler edge case.

**Rule:** keep `run()` thin. Each per-event / per-asset / per-stage block lives
in its own `private suspend fun`. Currently extracted: `runEvent`, `runAnnotationPass`,
`annotateAsset`, `buildTimeline`, `applyRevisions`, `applyPatches`,
`buildAvailableSignals`. The compile error you see when you exceed the limit is
just a warning (`Method exceeds compiler instruction limit: NNNNN`), not an error,
so the build still passes. Watch for that line in logcat.

### 2. Vivo logcat appears suppressed but isn't

Vivo HWUI / SurfaceComposerClient floods the 4 MB ring buffer fast enough to
overwrite Log.d entries within ~7 minutes. The buffer says it has 5 MiB readable
but our tagged logs are gone. Solutions, in order:
1. `adb logcat -G 16M` to grow the buffer
2. Filter aggressively: `adb logcat -v threadtime PipelineOrchestrator:D *:S`
3. Use the disk-backed observability instead — see `_state.txt`, `agent_log/agent.jsonl`
   under filesDir. They survive logcat overflow AND process death.

### 3. The whole CLIP / YOLO / FaceNet TFLite stack is gone (v4)

If you find references to:
- `ClipEmbedder`, `ClipTokenizer`
- `YoloDetector`, `YoloObj`
- `FaceEmbedder`, `FaceClusterer`
- `TfliteLoader` (only those models used it)
- `SharpestWindowPicker` (replaced by `VideoWindowPicker` which consumes VLM hints)

…they're deleted. Don't re-add them. The semantic signal CLIP was supposed to
provide now comes from `VlmAnnotator` (single Gemma call per asset, output stored
in `Perception.vlmTags` + `Perception.videoInsight`). Recall is tag-based via
`semanticOverlap()` in `Recall.kt`.

### 4. Event selection is two-pass and content-scored, not recency-only

`PipelineOrchestrator` does NOT just `.take(maxEvents)` of the most-recent events.
It runs **two passes** through `EventSelectionPlanner`:

1. **Coarse rank** (no VLM signal). `EventSelector.rank()` weights asset count
   (log-scaled), video count + seconds, time span, GPS spread, story signals
   (faces + scene diversity), `intent.eventBias()`, recency only as small
   tiebreaker. Top-K (default 6) candidates pass to step 2.
2. **EventScout** (one Gemma VLM call per coarse candidate). Reads a 3×3 contact
   sheet of the candidate's non-junk assets and returns story-arc strength /
   person presence / pacing potential. JSON-cached at
   `filesDir/event_scout_cache/<eventId>.json`.
3. **Final rank.** EventSelector reruns with `scoutMap` injected, picks
   `maxEvents`. The scout signal lets the system tell apart "kid's birthday"
   (good) from "kitchen breakfast" (bad) when the coarse stats look the same.

If users complain "the system picked junk":
- First check the manifest at `filesDir/decisions/_event_selection.json` to see
  which events scored what.
- Check `EventSelector` weights (`pipeline/EventSelector.kt`).
- Check that `EventScoutRunner.scout()` actually ran — the manifest's
  `scoutSignal` field is non-null on real runs and null when scout was skipped
  (e.g. early-exit on empty cache + cancelled).

### 5. LlmChatModelHelper has a hardcoded MTP stub upstream

The gallery's `LlmChatModelHelper.kt` shipped with `supportsSpeculativeDecoding = false`
hardcoded, which strips the MTP toggle from the import dialog AND silently disables
MTP at runtime. We patched it to read `model.capabilityToTaskTypes`. **Don't sync
this file from upstream without re-applying the patch.**

### 6. ffmpeg-kit-16kb-min has no libx264

It bundles only `mpeg4` (LGPL) and `h264_mediacodec` (hardware on supported SoCs).
`EncoderProbe` chooses at runtime; expect `libx264` to NOT be available. If shot
render fails with "Unknown encoder libx264", check `EncoderProbe.videoEncoderArgs()`.

### 7. Schema changes need cache invalidation by users

`Perception` and `VlmTags` are JSON-cached in `filesDir/perception_cache/`. If you
change the schema in a way that adds required fields with no defaults, existing
caches may fail to deserialize — `kotlinx.serialization Json { ignoreUnknownKeys = true }`
helps for unknown keys but not missing required keys. Always default new fields.
If you change semantics of an existing field, document it and either: bump a
schema version, or delete `filesDir/perception_cache/` in the migration path.

### 8. Prompt-audit signal flow (Pace / ColorGrade / patches)

The 7 prompts (`PromptStrings.*`) and their parsers in the agents are tightly
coupled — adding a field requires changes in BOTH the prompt and the parser, or
the field is silently dropped. Specific cross-stage flows:

- **`Pace` enum** (`AudienceBrief.pace`) → Director's `target_duration_sec` must
  fall in the corresponding band (snappy 15-18s / balanced 18-22s / lingering
  22-28s). `DirectorAgent.clampToPaceBand()` enforces this even if the model
  picks outside the band.
- **`ColorGrade` enum** (`DirectorBrief.colorGrade`) → Editor uses it as-is when
  not NEUTRAL; falls back to `ColorGradeFromTone.pick(directorTone)` only when
  NEUTRAL. Don't reintroduce keyword-only inference.
- **Critic patches** (`RevisedRequest.patches: Map<String, String>`) → preferred
  over `newRequest`. `PipelineOrchestrator.applyPatches()` honors blank values
  for cosmetic fields (caption / transition / ken_burns) but skips blanks for
  narrative fields (mood / visual_requirements) — Gemma occasionally emits
  empty strings as model error, not as intentional clear.
- **`CriticVerdict.ABORT`** → `runEvent`'s critic loop logs a `critic_abort`
  breadcrumb and breaks (no more model calls), but ships the working timeline.
  Don't add "skip the event entirely on ABORT" — abort means "patches won't fix
  this", not "don't ship anything".
- **Director's `available_signals`** → built by `buildAvailableSignals()` from
  the orchestrator. Director prompt requires `visual_requirements` to match
  something in this summary. If you change `buildAvailableSignals` output
  format, update the prompt's example accordingly or Gemma will drift.

## Working effectively in this codebase

- **Smoke test on real device after every refactor.** v3.2 → v4 broke because we
  refactored heavily and didn't run end-to-end before stacking observability
  changes. The 64K state-machine bug only shows up at runtime.
- **Don't speculate on platform behavior.** When the worker was looping every 2s,
  3 wrong theories burned an hour: "Gemma 5min cold start" (false, it's ~15s),
  "vivo logcat ACL" (false, just HWUI overflow), "vivo BTM kills FGS" (false,
  not even a kill). The real answer was in logcat all along: `Method exceeds
  compiler instruction limit: 64126`. Look at the data first.
- **Use `Agent` subagents for systematic diagnosis.** When stuck on platform
  weirdness, dispatch a sub-agent to gather `dumpsys jobscheduler` / `dumpsys
  activity service` / `am get-standby-bucket` / WorkManager DB introspection in
  one shot. Do not poll-and-guess.
- **Don't wipe `decisions/` / `candidates/` / `perception_cache/` on the user's
  device without asking.** They have value as session artifacts.
- **Never sleep+poll for state changes.** Use `Bash run_in_background` with an
  `until` loop for a single notification, or `Monitor` for streaming. The user
  notices and complains when you sleep.

## ADB on Windows

The bash environment doesn't have `adb` on PATH. Use:
```bash
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
"$ADB" devices -l
```

The vivo X200 Pro shows up as `PD2405A` / `V2405A`. The package is
`com.google.aiedge.gallery`. Worker WORK_NAME is `vlog_pilot_pipeline`.

Files under `/data/data/com.google.aiedge.gallery/files/` are reachable via
`run-as`:
```bash
"$ADB" exec-out run-as com.google.aiedge.gallery cat files/_state.txt
"$ADB" shell run-as com.google.aiedge.gallery ls files/decisions/
```

`adb pull` of files inside `/data/data/.../files/` does NOT work directly (that
path is owned by the app uid). Use `exec-out run-as ... cat` and redirect to a
local file instead.

## Git Bash on Windows path translation gotcha

Git Bash translates `/sdcard/foo` and `/data/data/...` to Windows paths
(`C:\Program Files\Git\sdcard\foo`). Workarounds:
- Double the leading slash: `//sdcard//ui.xml`
- Use PowerShell for adb commands that involve absolute device paths
- `exec-out run-as <pkg> cat <relative-path>` works because the path stays
  relative inside `run-as`

## Test infrastructure

Two unit-test files, 13 cases total:

- `agents/JsonExtractorTest.kt` (10 cases) — the agent JSON extractor is the
  hot path covering every prompt parse. Cases cover: clean object, nested
  braces, code-fence wrapper, `<think>` tag, prose prefix/suffix, escaped
  quotes inside strings, unclosed braces, garbage, array extraction.
  **If you add new JSON-parsing logic, add cases here.**
- `pipeline/EventSelectorTest.kt` (3 cases) — covers content-scored event
  ranking with intent bias. Add cases here if you change weights in
  `valueScore` / `eventBias` / `EventScoutRunner` integration.

Run: `./gradlew :app:testDebugUnitTest`. Every other component is tested only
by end-to-end runs on a real device.
