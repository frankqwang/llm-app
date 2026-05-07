# pc-pilot v2 — Agentic Vlog Editor

**核心思路**：模仿剪辑师的"粗到细"工作流，把"标注"从创作核心降级为检索缓存，把创作核心转移到 **事件级理解 + 叙事规划 + 按需视觉选择 + 迭代审片**。

> 不是流水线，是 Agent 接力：**Browser → Director → Editor → Critic**。

## 架构 (9 step)

```
inbox/                  手机相册原始素材（图/视频/Live Photo）
   ↓
[step0] step0_ingest          解析 + Live Photo 配对 + EXIF
[step1] step1_perceive        感知层（YOLO + InsightFace 全局聚类 + SigLIP + PySceneDetect + 质量过滤）
[step2] step2_segment         事件分段（时间 + GPS）
                              ─── 以下纯 Agent 阶段，依赖 LM Studio (Qwen3.5-9B) ───
[step3] step3_montage         🧠 Browser Agent: contact sheet → EventMemory（事件级一次浏览）
[step4] step4_director        🎬 Director Agent: → DirectorBrief（自由 tone, 字幕, 运镜, 转场, 调色全决策）
[step5] step5_editor          ✂️ Editor Agent: 召回 + VLM 精选 → Timeline v1
[step6] step6_critic          🔍 Critic Agent: 审片 + 局部修订 → Timeline final
[step7] step7_render          FFmpeg 真渲染 (xfade + drawtext animated + colorbalance/eq)
[step8] step8_index           HTML 决策链可视化
```

## v2 vs M0 (废弃)

| 维度 | M0 流水线 | v2 Agentic |
|---|---|---|
| step3 VLM 调用次数 | 34 次/事件 | **1 次/事件** (contact sheet) |
| 事件理解耗时 | ~140s | **~10s** |
| 风格选择 | 7 个固定 style | **完全自由** (director 写 tone) |
| 字幕 | 无 | Director 自定，关键节点配 |
| 运镜 | 全 zoom_in | 7 种 (zoom/pan/static) |
| 转场 | 只有 crossfade | 11 种 xfade (slide/circle/wipe/fadeblack...) |
| 调色 | 无 | 7 种风格 (warm/cool/vibrant/cinematic_teal_orange...) |
| 选片决策 | LLM 读文本表格 | **VLM 看真图比较** |
| 审片 | 无 | Critic 找问题 + 重新召回 |
| 连拍识别 | 无 | EventMemory subgroups 标记 |

## 硬件 / 环境

- NVIDIA GPU (推荐 16GB+，5070 Ti 实测够)
- LM Studio + Qwen3.5-9B (Q4_K_M ~5.5GB) **关闭 thinking** (jinja template 改 `enable_thinking is true` → `is not true`)
- LM Studio Context Length **设到 16K+** (默认 4K 太小，会截断)
- Python 3.12+ via uv
- ffmpeg (`winget install -e --id Gyan.FFmpeg`)
- Chinese font at `assets/fonts/msyh.ttc` (auto-loaded; Windows 已自动复制)

## 一次性设置

```powershell
cd pc-pilot
uv sync                                # 装 Python deps（torch/ultralytics/insightface/clip/transformers...）
uv pip install --reinstall --index-url https://download.pytorch.org/whl/cu128 torch torchvision   # 可选: 装 CUDA 版 torch（CPU 版也能跑）

# LM Studio 端：
# 1. 下载并加载 qwen/qwen3.5-9b
# 2. 模型设置 → Context Length 调到 32768
# 3. 模型设置 → Jinja template 改 thinking 默认开关
# 4. 启动 server (port 1234)
```

## 跑

```bash
# 一次性导入相册并端到端
bash run_all.sh --clean --days 30 --max-assets 80

# 或单独跑某 step
bash run_all.sh --from step3 --until step3 --event evt_002
bash run_all.sh --from step6 --event evt_002
```

## 关键 Agent 决策的可观测性

每个 Agent 调用的 prompt + raw response + 耗时，都落到 `workspace/agent_logs/`：

```
workspace/
├── agent_logs/                    每个 LLM/VLM 调用的完整 prompt+response+latency
│   ├── step3_montage_evt_002_*.json
│   ├── step4_director_evt_002_*.json
│   ├── step5_curate_evt_002_*.json   # 每个 slot 一次 VLM 比较
│   └── step6_critic_evt_002_*.json
├── event_memory/<event>.json      Browser Agent 输出
├── director/<event>.json          Director Agent 输出
├── timeline/<event>_v1.json       Editor 粗剪
├── timeline/<event>_final.json    Critic 修订后
├── candidates_v2/<event>.mp4      最终成片
├── render_cache_v2/               每个 shot 的中间 mp4 (debug 用)
└── index_v2.html                  完整决策链 HTML
```

## 项目结构

```
pc-pilot/
├── pyproject.toml
├── README.md                              本文件
├── schemas.py                             所有 Pydantic 模型 (Asset/Perception/Event/EventMemory/DirectorBrief/ShotRequest/Timeline/Critique/...)
├── vlm_client.py                          OpenAI 兼容 LM Studio 客户端 (vision + json_schema + thinking-tolerant)
├── agent_log.py                           统一 Agent 调用日志
├── montage.py                             contact sheet 生成器（自适应网格 + 序号 overlay）
├── prepare_inbox.py                       从 vivo/Apple 相册筛素材到 inbox/
├── perception/
│   ├── quality.py                         拉普拉斯模糊 + 曝光 + 截图过滤
│   ├── yolo.py                            YOLOv11n 物体检测 (auto-fallback CPU)
│   ├── face.py                            InsightFace + 全局 DBSCAN → 稳定 person_id
│   ├── clip_emb.py                        SigLIP 图像/文本 embedding
│   └── scene.py                           PySceneDetect 视频分镜 + 关键帧
├── styles/                                7 个 vlog 风格 yaml (作 director 灵感 hint)
├── step0_ingest.py
├── step1_perceive.py
├── step2_segment.py
├── step3_montage.py                       🧠 Browser
├── step4_director.py                      🎬 Director
├── step5_editor.py                        ✂️ Editor
├── step5_render.py                        ffmpeg helpers (lib only)
├── step6_critic.py                        🔍 Critic
├── step7_render.py                        Timeline → MP4 (xfade chain + color grade)
├── step8_index.py                         HTML 决策可视化
├── run_all.sh
├── inbox/                                 你的素材
├── assets/
│   ├── bgm/                               (空) 放一个 mp3 启用 BGM
│   └── fonts/msyh.ttc                     字幕字体 (Windows 自动)
└── workspace/                             所有输出 (gitignored)
```

## 评估 v2 输出的几个维度

打开 `workspace/index_v2.html`，每个事件展开后能看到：

1. **EventMemory storyline_summary** —— AI 看了一次缩略图后的"观后感"，是否抓住了你心里的故事？
2. **DirectorBrief tone** —— 是否突破了 7 个固定风格、写出了原创描述？
3. **DirectorBrief 字幕** —— 是否只在关键节点（开场/转场/高潮/收尾）配字幕，而非啰嗦满屏？
4. **shot rationale** —— editor 为每个 shot 写的 rationale 是否真的"对题"？
5. **Critique issues** —— critic 找到的问题是否成立？修订后的 timeline 是否真的更好？
6. **MP4 整体** —— 节奏、转场、字幕、调色综合质感

## AI 作曲 BGM（ACE-Step 集成）

我们用 [ACE-Step v1.5](https://github.com/ace-step/ACE-Step-1.5)（MIT，本地跑，5070 Ti 16GB OK）作为 BGM 生成器。
ACE-Step 是 **完全独立** 的项目（独立 venv），通过 HTTP API 跟 pc-pilot 通信。

### 一次性安装

```bash
cd D:/dev/llm-app
git clone https://github.com/ace-step/ACE-Step-1.5.git
cd ACE-Step-1.5
uv sync                          # 几分钟，装 torch 2.7.1+cu128 + diffusers + nano-vllm
```

### 启动 ACE-Step server（每次开机要做）

```bash
cd D:/dev/llm-app/ACE-Step-1.5
bash start_gradio_ui.sh          # 默认 :7860；第一次会自动下模型 ~2-5GB
# Windows cmd: start_gradio_ui.bat
```

看到 `Running on local URL: http://127.0.0.1:7860` 就 ready。

### 已修的 ACE-Step 上游 bug

`start_gradio_ui.sh` 在 `set -u` 下引用未定义的 `LANGUAGE` 报错——已 patch（line 83 加 `: "${LANGUAGE:=}"`）。如重新 `git pull` 上游需重打补丁。

### 跑 pc-pilot 端（自动调用 ACE-Step）

```bash
cd D:/dev/llm-app/pc-pilot
# 单事件
uv run python step6b_compose_bgm.py --event evt_004
# 全部事件
uv run python step6b_compose_bgm.py
```

输入：`workspace/director/<event>.json` 的 `tone` + `color_grade` + `workspace/audience/<event>.json` 的 `pacing_guidance` + `workspace/timeline/<event>_final.json` 的 `total_duration_sec`
输出：
- `assets/bgm/<event>.mp3` （ACE-Step 生成）
- `assets/bgm/<event>.mp3.beats.json` （librosa BPM + onset）

`step7_render` 会自动用 `assets/bgm/<event>.mp3` 作为该事件的 BGM。

### ACE-Step 提供的 Skills（可选辅助）

ACE-Step 安装后还附带这些 skills，**主线 vlog 流程只用前 2 个**，其他可选：

| Skill | 项目里用不用 | 用途 |
|---|---|---|
| **acestep** | ✅ 主线 | text-to-music / 歌曲编辑 / remix 的 API |
| **acestep-songwriting** | ✅ 调 prompt | 写 caption / 选 BPM / 结构化指南 |
| acestep-docs | 故障排查时 | 安装 / GPU / VRAM / CUDA 问题 |
| acestep-lyrics-transcription | ❌ 不用 | 音频→LRC（vlog BGM 无人声） |
| acestep-simplemv | ❌ 不用 | 音频+歌词→MV（我们自己渲染 vlog） |
| acestep-thumbnail | ❌ 不用 | Gemini 生成封面 |

### 故障排查

- **`LANGUAGE: unbound variable`**：见上面 patch
- **`gradio_client cannot connect`**：检查 server 是否真起来，`curl http://127.0.0.1:7860`
- **第一次生成超慢（>1 分钟）**：在下载模型，看 server 终端进度
- **VRAM OOM**：在 `start_gradio_ui.sh` 里改 `MODEL_NAME` 选 2B turbo（默认是更大的）

## 已知限制 (TODO)

- BGM 节拍对齐 (M3.5)：用户在 `assets/bgm/` 放 mp3 后，自动用 librosa 检测 BPM 让 cut 落到节拍上
- 多事件全局优化 (M5)：年度回顾 / 跨事件叙事
- 用户反馈 ("再来一版" / 替换 shot)
- 端侧迁移 (Android via LiteRT-LM)
