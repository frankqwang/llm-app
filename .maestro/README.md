# Maestro UI tests

End-to-end smoke + regression tests for VlogPilot, run via [Maestro](https://maestro.mobile.dev/).

## Why Maestro

Project already has a unit-test layer (`./gradlew :app:testDebugUnitTest` — 46 cases covering JSON extractor, event selector, iteration planner, user curation). Those cover the algorithmic paths.

Maestro fills the gap above that: **UI shell and routing**. Things like:

- Does the bottom-tab IA still hang together after a refactor?
- Does the chat input + send flow produce a user bubble + AI reply?
- Do all 4 tabs render their primary content?

It runs on a real device or emulator, drives the actual Compose UI, asserts on visible text or accessibility labels — no coordinates, robust to layout drift.

## Install (one-time)

Mac / Linux / Git Bash on Windows:

```bash
curl -Ls "https://get.maestro.mobile.dev" | bash
# Then add ~/.maestro/bin to PATH
```

Or grab a release jar from <https://github.com/mobile-dev-inc/maestro/releases>.

Smoke check:

```bash
maestro --version
```

## Running flows

Connect a real device or boot an emulator first (`adb devices` should list one). Then from repo root:

```bash
# All flows in one go
maestro test .maestro/flows/

# Single flow
maestro test .maestro/flows/smoke.yaml

# Filter by tag
maestro test --include-tags=smoke .maestro/flows/
```

Outputs land under `.maestro/output/` (gitignored via `.debug/` rules).

## Flows shipped

| Flow | Tag | What it validates |
|---|---|---|
| `smoke.yaml` | smoke | App launches, 4 bottom tabs render, primary copy visible on each. |
| `tab-walk.yaml` | quick | Lightweight tab roundtrip — useful when iterating on IA. |
| `chat-fallback.yaml` | regression | The "no silent failure" guarantee: typing a generation command on a fresh emulator (no model imported) produces an AI fallback reply instead of dead air. |

## When to add a flow

Add a flow when you're shipping UI behavior that:

- A unit test can't cover (it lives at the screen level)
- A user would notice if it broke (so the regression cost is real)
- Has stable text or accessibility labels you can match against

Don't add a flow for transient state, animations, or anything that depends on a Gemma model being imported (model-bound paths are too long for typical CI budgets — verify those manually on a real device once per release).

## CI integration (future)

Recipe to wire into GitHub Actions when we want to gate PRs on UI tests:

```yaml
- name: Set up emulator
  uses: reactivecircus/android-emulator-runner@v2
  with:
    api-level: 35
    target: google_apis
    arch: x86_64

- name: Install Maestro
  run: curl -Ls "https://get.maestro.mobile.dev" | bash

- name: Run smoke flows
  run: maestro test --include-tags=smoke .maestro/flows/
```

Smoke tag only — full regression suite is too long for PR loops; run on nightly.
