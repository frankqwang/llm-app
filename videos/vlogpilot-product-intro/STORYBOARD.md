# VlogPilot Product Intro - Storyboard

**Format:** 1920x1080 landscape  
**Duration:** 37.6 seconds  
**Audio:** Voiceover script in `SCRIPT.md`; no rendered narration file yet because HyperFrames CLI/TTS is blocked in the current sandbox.  
**VO direction:** Calm, confident Chinese product narration. Practical, not salesy.  
**Style basis:** `DESIGN.md`, derived from VlogPilot Compose tokens and UI files.

## Asset Audit

| Asset | Type | Assign to Beat | Role |
| --- | --- | --- | --- |
| `../../android/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png` | PNG app icon | Beat 1, Beat 5 | Product mark |
| `../../android/app/src/main/assets/fonts/SourceHanSansSC-Bold.otf` | Font | All beats | Chinese headline weight |
| `../../android/app/src/main/res/font/nunito_regular.ttf` | Font | All beats | UI text fallback |
| HTML phone mockups | Generated UI | Beats 1, 2, 4 | Realistic product surface based on Compose UI |
| HTML agent cards | Generated UI | Beat 3 | 5-agent workflow visualization |

## Beat 1 - Cold Open (0:00-0:06.8)

**VO cue:** "你的相册里，其实已经有一条好看的 vlog。"

**Concept:** Start inside the product, not outside it. A bright iOS-style canvas opens with a VlogPilot phone interface already alive: album thumbnails, a large title, and the product mark. The viewer immediately understands this is a phone app about turning personal media into video.

**Visual:** Large headline on the left. Two layered phone mockups on the right: front phone shows the Works tab with a "扫描推荐" action; rear phone shows a contact sheet of recent memories. Small semantic chips orbit the phones: 家人, 旅行, 美食, 视频.

**Motion:** Headline slides up and resolves. Phone stack floats in. Album tiles cascade in. Chips drift slowly.

**Transition:** Soft upward camera move into Beat 2.

## Beat 2 - Local Understanding (0:06.8-0:14.4)

**VO cue:** "VlogPilot 会在手机本地读取最近的照片和视频。不上传，不登录，先把事件、人物、节奏和情绪看懂。"

**Concept:** Make privacy and local intelligence tangible. The phone turns into an analysis console: no cloud, no account, just structured understanding.

**Visual:** Contact sheet expands into three analysis lanes: Event, People, Rhythm. A lock badge says "本地处理". Three counters show 最近 90 天, 12 个素材, 约 7 分钟出片, matching the README's verified story.

**Motion:** Contact sheet tiles fan out. Counters count up. The lock badge drops in with a clean chime moment.

**Transition:** Cards slide left into the agent pipeline.

## Beat 3 - Agent Chain (0:14.4-0:22.2)

**VO cue:** "然后五个 AI 角色接力：找故事，想观众，写分镜，选镜头，审片。"

**Concept:** The product is not a black box. It is a visible chain of roles. The beat should feel like watching a careful editor work, not a magic sparkle effect.

**Visual:** Five agent cards in sequence: Browse, Audience, Director, Editor, Critic. Each card has a status dot, icon block, summary line, and a small output artifact. A thin blue path connects the stages.

**Motion:** Cards enter one by one. The path draws across. Status dots pulse and then turn green.

**Transition:** The last card becomes a video preview in Beat 4.

## Beat 4 - User Control (0:22.2-0:29.8)

**VO cue:** "你可以一键扫描，也可以自己挑素材。成片后，直接对它说：快一点，换这个镜头，少一点字幕。"

**Concept:** Show the product's ergonomics: auto, curated, iterate. The user is not locked into the first result.

**Visual:** Three workflow tabs sit above a chat surface: 扫描推荐, 我自己挑素材, 改一改. A blue user bubble sends "快一点，少一点字幕". A result preview updates from v1 to v2.

**Motion:** Tabs switch in a segmented control. Chat bubble sends. Result preview flips from 初版 to 已优化.

**Transition:** Result preview scales down into final brand frame.

## Beat 5 - Close (0:29.8-0:37.6)

**VO cue:** "VlogPilot 会重新剪一版，仍然在手机上完成。让回忆，不再只躺在相册里。"

**Concept:** End with the full promise: private, iterative, finished video. The last frame should be simple and ownable.

**Visual:** Product mark, final headline, three proof chips: No upload, On-device Gemma, 1080x1920 MP4. A vertical result card glows gently.

**Motion:** Proof chips settle in. The final headline breathes once. The product mark stays steady.

**Transition:** Fade to a clean final hold.

## Production Architecture

```
videos/vlogpilot-product-intro/
  DESIGN.md
  SCRIPT.md
  STORYBOARD.md
  index.html
```

