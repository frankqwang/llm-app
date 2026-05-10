# Project context for Claude

## What this is

`llm-app` is an Android Kotlin/Compose app forked from
[google-ai-edge/gallery](https://github.com/google-ai-edge/gallery). The product
is **VlogPilot** — Gemma 4 E2B-IT runs fully on-device via LiteRT-LM, turning
the user's photo album into ~6 MB 1080×1920 MP4 vlogs. No cloud, no upload.

Three pipeline entry points, all routed through `VlogPipelineWorker`:
- `run()` — auto: scan album → segment → select → vlog
- `runFromCuration()` — user picks assets + writes a sentence
- `iterate()` — user taps "改一改" on a generated vlog

The 5-agent reasoning chain (Browser → Audience → Director → Editor → Critic)
is ported from the Python reference under `pc-pilot/`.

`README.md` has the layout. `docs/架构详解.md` has the pipeline diagram.
`docs/调试与观测.md` has the on-device file paths and ADB workflow.
`docs/测试.md` has the test commands. `CHANGELOG.md` is the version log —
read the most recent entry first.

## Landmines

Gotchas that have already cost time. Read before debugging.

### 1. `run()` and `iterate()` sub-paths must stay thin (ART 64K bytecode)

If a coroutine's compiled state machine exceeds ART's 64K-per-method limit,
every `progress(...)` suspension makes the function re-enter from the top —
visible as `download → ingest → perceive → init → download → ...` looping
every 2 seconds. The compile WARNING `Method exceeds compiler instruction
limit: NNNNN` is the only signal; the build still passes.

**Rule:** each per-event / per-asset / per-iteration-scope block is its own
`private suspend fun`. Already extracted: `runEvent`, `runAnnotationPass`,
`annotateAsset`, `buildTimeline`, `applyRevisions`, `applyPatches`,
`buildAvailableSignals`, and the four iteration sub-paths `runRenderOnly` /
`runShotLevel` / `runGlobal` / `runMixed`. Don't inline two for "clarity".

### 2. The CLIP / YOLO / FaceNet TFLite stack is gone (v4)

`ClipEmbedder`, `YoloDetector`, `FaceEmbedder`, `TfliteLoader`,
`SharpestWindowPicker` — all deleted. Don't re-add. Semantic signal now comes
from `VlmAnnotator` (one Gemma call per asset → `Perception.vlmTags`). Recall
is tag-based via `semanticOverlap()` in `Recall.kt`.

### 3. Don't sync `LlmChatModelHelper.kt` from upstream blindly

Upstream hardcodes `supportsSpeculativeDecoding = false`, which strips the MTP
toggle from the import dialog AND silently disables MTP at runtime. We patched
it to read `model.capabilityToTaskTypes`. Re-apply on sync.

### 4. ffmpeg-kit-16kb-min has no libx264

Only `mpeg4` (LGPL) and `h264_mediacodec` are bundled. `EncoderProbe` picks at
runtime — don't assume `libx264`.

### 5. Schema changes need cache-friendly defaults

`Perception` / `VlmTags` are JSON-cached in `filesDir/perception_cache/`. New
required fields without defaults break old caches (`ignoreUnknownKeys` doesn't
help for missing required keys). Always default new fields, or bump a schema
version + delete the cache dir in migration. Same applies to
`Event.userCuration` / `Event.userBrief` — keep them nullable.

### 6. `requestId` hash recipe is load-bearing

`requestId = sha1(sorted(selectedAssetIds) + intentText)[0..12]` →
`eventId = "user_<requestId>"`. Always sort asset IDs before hashing. Changing
the recipe orphans every prior user-curated event from the UI.

### 7. Event selection is two-pass, content-scored

`PipelineOrchestrator` runs `EventSelectionPlanner` twice: coarse rank
(`EventSelector.rank()` weights asset count, video seconds, time span, GPS
spread, story signals, intent bias; recency only as tiebreaker) → top-K to
`EventScoutRunner` (one Gemma VLM call per candidate, 3×3 contact sheet) →
final rank. NOT `.take(maxEvents)` of recent events. Audit trail at
`filesDir/decisions/_event_selection.json` — `scoutSignal` is non-null when
scout actually ran.

### 8. Prompt ↔ parser coupling is tight

The 8 `PromptStrings.*` and the parsers in `agents/` are tightly coupled —
adding a field needs both sides or it silently drops. Specifics:
- `Pace` enum drives Director's `target_duration_sec`; bands are clamped by
  `DirectorAgent.clampToPaceBand()` (snappy 15-18s / balanced 18-22s /
  lingering 22-28s).
- `ColorGrade` enum: Editor uses Director's value as-is unless NEUTRAL, then
  falls back to `ColorGradeFromTone.pick()`. Don't reintroduce keyword-only
  inference.
- `RevisedRequest.patches` is reused by Critic AND
  `IntentParserAgent.parseFeedback` — audit both callers when changing.
  `applyPatches()` honors blanks for cosmetic fields (caption / transition /
  ken_burns) but skips blanks for narrative fields (mood / visual_requirements)
  because Gemma occasionally emits "" as model error.
- `CriticVerdict.ABORT` means "patches won't fix this" — log breadcrumb and
  ship the working timeline. Don't add "skip the event entirely on ABORT".
- Director's `available_signals` is built by `buildAvailableSignals()`. If
  you change its format, update the prompt's example or Gemma drifts. When a
  UserBrief is present, `runEvent` appends `用户原话需求：「...」`.

### 9. Worker has 3 modes; iteration bypasses ACTIVE lock

`VlogPipelineWorker` inspects two input keys: `KEY_ITERATION_EVENT_ID` →
iterate mode (per-event WORK_NAME), `KEY_CURATION_REQUEST_ID` → curated mode,
neither → full pipeline. Static `ACTIVE: AtomicBoolean` serializes Gemma loads
for full + curate; **iterate bypasses** (RENDER_ONLY needs no Gemma; the rare
race for SHOT_LEVEL / GLOBAL / MIXED is accepted).

Pending iterations persist to `_pending_iteration/<eid>.json` and replay
ONCE per VM lifetime via `resumePendingIterations()`. Critic does NOT run
during iteration — user feedback IS the critique. `runIterationWithAgent`
opens AgentRuntime once; don't create two per iteration (Gemma init ≈ 15s).

## House rules

- **Smoke test on real device after refactors.** The 64K bug only shows at
  runtime. v3.2 → v4 broke because we stacked changes without an end-to-end run.
- **Don't speculate; read the data.** When the worker looped every 2s, three
  wrong theories burned an hour ("Gemma cold start", "vivo logcat ACL", "vivo
  BTM kills FGS") before we read the actual logcat warning.
- **Never sleep+poll for state changes.** Use `Bash run_in_background` with an
  `until` loop, or `Monitor` for streaming. The user notices.
- **Don't wipe `decisions/` / `candidates/` / `perception_cache/` on the
  device without asking.** Session artifacts have value.
- **Debug scratch goes under `.debug/`** (gitignored). NOT at repo root —
  no `.run*`, `.ui*.xml`, `.screen.png`, `.sample_*.json` at root.

## Windows / ADB notes

`adb` isn't on PATH: `ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"`.
Test device: vivo X200 Pro (`PD2405A` / `V2405A`), package
`com.google.aiedge.gallery`, worker name `vlog_pilot_pipeline`.

`adb pull` of `/data/data/.../files/` does NOT work (app uid). Use
`exec-out run-as <pkg> cat <relative-path>` and redirect locally. Git Bash
translates `/sdcard/...` to Windows paths — use PowerShell, double the leading
slash (`//sdcard//foo`), or stay relative inside `run-as`.
