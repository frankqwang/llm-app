# AI-Built On-Device AI App - Design Reference

## Style Prompt

Create a polished Bilibili-style tech explainer for VlogPilot: clear, fast, product-first, and practical. The visual identity follows the provided real Android screenshots: black canvas, dark rounded panels, blue primary controls, green model-ready states, orange pending states, and purple manual-curation accents. Real product screenshots are the credibility anchor; diagrams only clarify AI development, on-device AI, and Agent flow.

## Core Message

AI is now participating in app development, and the app itself can run on-device AI to serve the user. VlogPilot is the first experiment: a local photo/video understanding and creative planning assistant.

## Palette

- Canvas: `#050506`
- Surface: `#1C1C1E`
- Raised surface: `#25262A`
- Text: `#F5F5F7`
- Secondary text: `#A1A1AA`
- Hairline: `#34343A`
- Editor background: `#101114`
- Primary blue: `#0A84FF`
- Success green: `#34C759`
- Warm orange: `#FF9F0A`
- Product purple: `#BF5AF2`
- Product pink: `#FF375F`

Use blue as the main action color, but keep the video from becoming blue-only by mixing green for generated/model-ready states, orange for pending/selection, and purple for manual curation/iteration.

## Typography

- Chinese headlines: `SourceHanVlog`, loaded from `android/app/src/main/assets/fonts/SourceHanSansSC-Bold.otf`.
- Body/UI text: system sans-serif, with local Nunito only as fallback because it is part of the existing app assets.
- Code text: `Consolas`, `Cascadia Mono`, monospace.
- Body copy must be 28 px or larger in the 1920x1080 composition.
- On-screen captions must be short enough to read in two seconds.

## Components

- Phone frame: 9:16 vertical device, rounded 46 px, showing the provided real screenshots.
- Editor panel: dark window with file tabs, code rows, and AI task cards.
- Local model panel: real VLM annotation screenshot plus lock/local-processing chips.
- Agent graph: visible chain from media understanding to planning to creation.
- Result preview: real generated-work detail page with save/share/edit/story controls.

## Motion

- Snappy, product-like motion. Use 0.35-0.7s entrances with varied easing.
- Use cover transitions between scenes. The outgoing scene remains visible until a transition overlay covers it.
- Avoid jump cuts. Avoid neon glitch effects.
- Let phone frames and product surfaces drift subtly during longer narration holds.

## What Not To Do

- Do not present the project as only a smart album.
- Do not imply every photo uploads to a cloud service.
- Do not over-explain implementation details at the cost of product clarity.
- Do not use giant generic AI gradient heroes.
- Do not make private user photos the main subject. The product UI and local AI workflow are the subject.
