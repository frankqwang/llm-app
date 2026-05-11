# VlogCopilot UI Optimization Plan

## Review scope

This plan is based on the current Compose UI structure for VlogCopilot, especially:

- `VlogCopilotRootScreen.kt`
- `UnifiedWorksFeed.kt`
- `ChatScreen.kt`
- `AgentWorkPanel.kt`
- `VideoResultsUi.kt`
- `VlogCopilotTokens.kt`

The current direction is solid: the app already feels like a quiet, iOS-inspired creation tool rather than a generic AI chat shell. The next pass should improve first-use clarity, action hierarchy, and product-specific identity without rewriting the whole UI system.

## Goals

1. Make the first useful action obvious within 3 seconds.
2. Make `Works` feel like both a finished-video library and an AI opportunity feed.
3. Make `Chat` feel like a director/editing conversation, not a second home page.
4. Keep the Agent process visible enough to build trust, but phrase it in user-facing language.
5. Make the video result page the strongest emotional and action moment.
6. Add a light VlogCopilot-specific visual signature while preserving the current clean system style.

## Non-goals

- No broad navigation rewrite unless review decides the current tab model is wrong.
- No change to model, export, or media pipeline behavior.
- No large brand redesign.
- No animation-heavy pass before the core information architecture is fixed.

## Priority plan

### P0 - Clarify the creation path

Problem: `Works` and `Chat` can both imply "start here", which creates decision friction.

Planned changes:

- Add a compact creation/action zone at the top of `Works`.
- Split feed content into clearer states:
  - Finished videos
  - AI-recommended stories
  - Empty or first-run state
- Give candidate rows a direct action such as `做成 vlog` instead of relying only on navigation.
- Make one primary CTA visually dominant on each screen.

Candidate files:

- `UnifiedWorksFeed.kt`
- `VlogCopilotRootScreen.kt`
- `VlogCopilotTokens.kt`

Review decision:

- Should the primary first-run action be `扫描相册`, `选择素材`, or `让 AI 推荐故事`?

### P1 - Separate Works and Chat roles

Problem: `Chat` currently risks feeling like another entry point instead of a directed editing surface.

Planned changes:

- Position `Works` as the place to discover, resume, and manage videos.
- Position `Chat` as the place to refine an active story or generated video.
- Consider renaming the tab from `Chat / 对话` to a more product-specific label such as `导演` or `改片`.
- Update empty/welcome prompts to concrete vlog use cases:
  - Weekend recap
  - Travel story
  - Birthday memory
  - Recent album highlight

Candidate files:

- `ChatScreen.kt`
- `VlogCopilotRootScreen.kt`

Review decision:

- Do we keep `对话`, or change the tab to `导演` / `改片`?

### P1 - Make Agent progress user-facing

Problem: The Agent panel is a strong differentiator, but technical labels can feel abstract.

Planned changes:

- Keep the current agent transparency, but map agent roles to user-facing production steps:
  - Browse -> 读相册
  - Audience -> 找故事
  - Director -> 写分镜
  - Editor -> 选镜头
  - Critic -> 审片
  - Render -> 出片
- Default to a concise production summary.
- Keep detailed agent output behind expand/collapse.
- Use status dots or a small production timeline as a repeated visual motif.

Candidate files:

- `AgentWorkPanel.kt`
- `ChatScreen.kt`
- `VlogCopilotTokens.kt`

Review decision:

- Should agent details be visible by default, or collapsed under `查看制作过程`?

### P1 - Strengthen the result page

Problem: The generated video page should be the most rewarding moment in the app.

Planned changes:

- Make the vertical video preview the dominant element.
- Add a sticky or visually dominant result action area:
  - Save to album
  - Share
  - Ask AI to revise
- Show lightweight version context when a video is regenerated.
- Make successful generation feel distinct from normal list/detail screens.

Candidate files:

- `VideoResultsUi.kt`
- `CommonUi.kt`
- `VlogCopilotTokens.kt`

Review decision:

- Which action should be primary after render: `保存`, `分享`, or `继续修改`?

### P2 - Add a VlogCopilot visual signature

Problem: The current UI is clean, but still borrows heavily from generic iOS system styling.

Planned changes:

- Add one subtle recurring visual idea:
  - Contact-sheet album grid
  - Vertical video frame
  - Timeline strip
  - Agent production path
- Reserve system blue for primary creation actions.
- Use semantic colors consistently:
  - Green for completed/saved
  - Orange for processing or needs attention
  - Purple/pink only for AI/story accents
- Avoid turning the app into a decorative or marketing-style UI.

Candidate files:

- `VlogCopilotTokens.kt`
- `UnifiedWorksFeed.kt`
- `ChatScreen.kt`
- `VideoResultsUi.kt`

Review decision:

- Should the product feel closer to Apple Photos, CapCut, or a new hybrid identity?

### P2 - Density, accessibility, and polish

Problem: Chips, rows, and tool buttons can become crowded on smaller Android screens.

Planned changes:

- Verify touch targets are at least 44-48dp.
- Check text wrapping for Chinese and English labels.
- Make chip groups horizontally scrollable only when the remaining action still stays clear.
- Ensure top bars, bottom bars, and input bars do not crowd content.
- Add screenshot checks for common device sizes.

Candidate files:

- `CommonUi.kt`
- `UnifiedWorksFeed.kt`
- `ChatScreen.kt`
- `.maestro/flows/*`

Review decision:

- Which screen sizes are the must-pass targets for the next UI pass?

## Suggested implementation order

1. Commit the current baseline.
2. Add before screenshots for Works, Chat, result, and empty states.
3. Implement Works first-screen clarity.
4. Update Chat welcome and role language.
5. Simplify Agent progress presentation.
6. Strengthen result page actions.
7. Apply visual token polish and product motif.
8. Run screenshot/flow checks and compare before/after.

## Acceptance criteria

- A first-time user can identify the next action without reading explanatory copy.
- Generated videos and AI recommendations are visually distinct.
- Each primary screen has one clear dominant action.
- Agent progress is understandable without knowing the internal architecture.
- Result page makes save/share/revise actions obvious.
- No small-screen overlap in top bars, chips, input bars, or result actions.
- Existing core Compose architecture remains intact.

## Canva review deck outline

If this plan is turned into a Canva review board, use this structure:

1. Current UI structure and strengths
2. Main UX problems and priority ranking
3. Proposed Works-first flow
4. Proposed Chat and Agent language
5. Result page and visual identity direction
