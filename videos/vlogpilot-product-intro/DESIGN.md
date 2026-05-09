# VlogPilot Product Intro - Design Reference

## Overview

VlogPilot is an on-device AI vlog generator. The video should feel like a polished product demo for a real mobile app, not a generic AI montage. The visual language comes from the app code: iOS Photos / Settings style, inset grouped surfaces, quiet system chrome, and blue as the main action color.

Core promise: turn a user's recent photo album into finished vertical vlog candidates on the phone, with no upload, no cloud, and no account.

## Palette

- Page background: `#F2F2F7`
- Surface: `#FFFFFF`
- Dark surface: `#1C1C1E`
- Raised dark surface: `#2C2C2E`
- Primary text: `#0B0B0F`
- Secondary text: `#6B6B72`
- Hairline: `#C6C6C8`
- Accent blue: `#007AFF`
- Accent tint: `#E5F1FF`
- Success green: `#34C759`
- Warm orange: `#FF9500`
- Product purple: `#AF52DE`
- Product pink: `#FF2D55`

Use light grouped backgrounds for most scenes. Use the dark surface only inside preview cards, phone glass, or final contrast panels. Avoid a blue-only look by pairing system blue with green, orange, purple, and pink status accents.

## Typography

- Primary UI font: system sans-serif with Nunito fallback from `android/app/src/main/res/font/`.
- Chinese headline font: Source Han Sans SC Bold from `android/app/src/main/assets/fonts/SourceHanSansSC-Bold.otf`.
- Headlines should be strong but not oversized: this is a product workflow video, not a launch keynote.
- Body text must be 28 px or larger in the 1920x1080 composition.
- Labels and chips must be 18 px or larger.

## Components

- Phone frame: rounded 46 px, white surface, 1 px hairline, subtle shadow.
- Inset grouped panel: `#FFFFFF` on `#F2F2F7`, 24 px radius, compact rows.
- Capsule chip: accent tint background, blue or semantic text.
- Agent card: icon square, title, short summary, stage dot.
- Contact sheet: 3x3 media tiles with tiny labels and selection rings.
- Chat bubble: user message on the right in blue, agent status on the left in white.
- Result preview: dark 9:16 frame with vertical-video crop and save/share actions.

## Motion

- Snappy iOS motion: short entrances, soft spring-like easing, no aggressive glitch.
- Elements slide from nearby positions and settle quickly.
- Phones and cards should drift subtly after landing.
- The pipeline scene should have staggered cards so the agent chain is readable.
- Transitions should feel like smooth camera moves between product states.

## What Not To Do

- Do not use neon cyberpunk or generic purple-blue AI gradients.
- Do not show cloud upload metaphors as a feature; the product promise is local processing.
- Do not make the video text-only. Every beat needs product UI, phone surfaces, or real app assets.
- Do not use giant marketing hero cards. Keep the product UI itself as the first signal.
- Do not over-explain implementation details like WorkManager or ffmpeg-kit unless they support the viewer promise.

