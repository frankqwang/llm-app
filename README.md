# 相册挖掘-本地agent版 (AlnumCopilot)

基于 [Google AI Edge Gallery](https://github.com/google-ai-edge/gallery) (Apache-2.0) 的分支。本仓库交付的应用是 **VlogCopilot**：一键点击，Gemma 4 E2B-IT 通过 [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) 在设备端本地运行，将相册最近 30 天的照片自动剪辑为 AI 策划的 vlog 候选——不上传、不上云、不需要账号。

5 智能体推理链（Browser → Audience → Director → Editor → Critic）移植自 [pc-pilot](pc-pilot/) Python 参考实现。渲染使用 ffmpeg-kit，在支持的 SoC 上启用硬件 H.264（h264_mediacodec）。

已在 **vivo X200 Pro**（天玑 9400 + GPU + MTP）上端到端验证。

> 版本变更记录见 [CHANGELOG.md](CHANGELOG.md)，项目注意事项和贡献者指南见 [CLAUDE.md](CLAUDE.md)。

---

## 核心特性

三种 vlog 制作方式：

- **自动** — 点击"开始挑故事"，AI 从相册中挑选最佳 1-2 个事件。
- **自选** — 点击"我自己挑素材"，选择素材并（可选）写下需求，AI 据此剪辑。
- **迭代** — 对任何已生成的 vlog 点击"改一改"，发送一句话反馈（如"整体太慢"）或点击快捷芯片/具体镜头，30 秒~3 分钟内重新剪辑。

---

## 快速开始

### 环境准备

- Android Studio Ladybug+（或 Hedgehog + AGP 8.7+）
- **JDK 21**（Temurin）— LiteRT-LM 0.11.0 需要 Java 21 类文件
  - Windows: `winget install EclipseAdoptium.Temurin.21.JDK`
  - macOS: `brew install --cask temurin@21`
- Android SDK Platform 35 + Build-Tools 35
- 测试设备：Android 12+（minSdk 31），运行 Gemma 4 E2B-IT 建议 8GB 以上内存
- 接受 Gemma 4 许可的 Hugging Face 账号

### 1. 下载模型

```powershell
# 先安装 huggingface_hub 并登录
hf download litert-community/gemma-4-E2B-it-litert-lm `
  gemma-4-E2B-it.litertlm `
  --local-dir C:\dev\llm-app\models
```

只需下载通用的 `.litertlm` 文件（约 2.6 GB），跳过其他后缀（web.task、Qualcomm NPU 专用版）。

### 2. 构建并安装 APK

```powershell
cd C:\dev\llm-app\android
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

### 3. 推送模型到手机

```powershell
adb push C:\dev\llm-app\models\gemma-4-E2B-it.litertlm /sdcard/Download/
```

### 4. 在应用内导入模型

1. 打开应用，进入侧边栏 → **Models**
2. 点击右下角 **+** → **From local model file**
3. 在文件选择器中找到 **Downloads** → `gemma-4-E2B-it.litertlm`
4. **关键步骤** — 导入前打开以下开关：
   - **Support speculative decoding** — 启用 MTP 多 Token 预测（v0.11.0 核心性能提升）
   - **Compatible accelerators** — 选择 GPU
5. 点击确认导入

### 5. 性能优化（首次运行）

进入聊天界面的设置（齿轮图标）：
- **Accelerator**: GPU
- **Multi-token prediction (MTP)**: ON
- **Thinking mode**: OFF（纯解码基准测试时）

天玑 9400 + GPU + MTP 预期可达 40-60 tok/s。

> 完整侧载指南、HF OAuth 登录方式、故障排查见 [docs/快速开始.md](docs/快速开始.md)。

---

## 项目结构

```
llm-app/
  android/                    # Gradle 项目（Kotlin + Compose + Hilt）
    app/src/main/java/com/google/ai/edge/gallery/
      com/vlogcopilot/  # VlogCopilot 模块
        agents/               # Browser/Audience/Director/Editor/Critic + VlmAnnotator
        perception/           # 轻量感知：人脸、清晰度、NSFW
        pipeline/             # EventSegmenter, EventSelector, Recall, MontageBuilder…
        render/               # ffmpeg-kit 镜头/合成渲染器
        runtime/              # PowerPacer, VlogCopilotModelRegistry
        schemas/              # @Serializable Asset/Perception/VlmTags/Timeline/…
        worker/               # PipelineOrchestrator + WorkManager Worker + 面包屑日志
  pc-pilot/                   # Python 参考实现（服务端对照、调试）
  docs/                       # 详细文档
```

---

## 核心架构概览

流水线由 `WorkManager` 的 `CoroutineWorker`（`VlogPipelineWorker`）驱动，`PipelineOrchestrator` 协调 7 个以上阶段：

```
PhotoIngest → EventSegmenter → EventSelectionPlanner（粗排）
                                      │
                                      ▼
                           Gemma 4 E2B-IT 启动一次
                                      │
                                      ▼
                  EventScout（3×3 联系人表 → VLM 信号）
                                      │
                                      ▼
                  EventSelectionPlanner 精排，选出 top-N 事件
                                      │
                                      ▼
                  选中资产的轻量感知（人脸/清晰度/NSFW，无 LLM，JSON 缓存）
                                      │
                                      ▼
                  VlmAnnotator 逐资产标注（场景/主体/动作/氛围/显著性
                   + 视频最佳时刻索引 + 时间窗口）
                                      │
                                      ▼
                  对每个 top-N 事件执行 runEvent()：
                    Browser  → EventMemory（蒙太奇 VLM 调用）
                    Audience → AudienceBrief + 节奏（紧凑/均衡/悠长）
                    Director → DirectorBrief + 镜头蓝图 + 调色
                    Editor   → Timeline v1（标签召回 + 逐镜头 VLM 精选）
                    Critic   → Critique（接受/修改/中止 + 补丁，最多 2 轮迭代）
                    Render   → MP4（ffmpeg-kit，优先 h264_mediacodec）
```

v5.0+ 额外支持两条旁路入口：
- **自选入口**：用户挑选素材 + 撰写意图 → `UserCurationPlanner` → 合成事件 → 完整链路
- **迭代入口**：用户在故事板上点击/输入反馈 → `IntentParserAgent` 解析 → 按范围（仅渲染/镜头级/全局/混合）重新剪辑

> 完整架构说明、关键设计决策、调试方法见 [docs/架构详解.md](docs/架构详解.md)。

---

## 文档索引

| 文档 | 内容 |
|---|---|
| [CHANGELOG.md](CHANGELOG.md) | 版本变更记录 |
| [CLAUDE.md](CLAUDE.md) | 项目注意事项与贡献者指南 |
| [docs/快速开始.md](docs/快速开始.md) | 完整侧载指南、HF OAuth 配置、故障排查 |
| [docs/用户手册.md](docs/用户手册.md) | 自动/自选/迭代三种模式的详细使用说明 |
| [docs/架构详解.md](docs/架构详解.md) | 流水线架构、关键设计决策、缓存策略 |
| [docs/调试与观测.md](docs/调试与观测.md) | 不依赖 logcat 的设备端调试、文件结构说明 |
| [docs/测试.md](docs/测试.md) | 单元测试与 Maestro UI 测试 |
| [docs/上游差异与未来清理.md](docs/上游差异与未来清理.md) | 与上游 gallery 的差异、未来可清理的代码 |
| [docs/参考链接.md](docs/参考链接.md) | 上游仓库、模型卡片、Release Notes |

---

## 许可证

Apache-2.0，详见 [LICENSE](LICENSE)。
