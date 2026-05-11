# 变更日志

设备端流水线的工程变更记录。每个版本块记录**改了什么**、**为什么**（驱动变更的缺陷或约束），以及简要影响范围。

---

## v6.1 — 对话式 Agent UX + 标注流改造 (2026-05-10)

聊天 Tab 从「关键词路由」升级为**模型驱动的规划智能体**：Gemma 读完整 App 状态后用自然语言回答，并返回一个结构化动作给 UI 执行。同时把语音输入接进对话（按住麦克风 → 系统识别 / 本地音频），并整理了模型管理和单素材标注的流程。

**A. ChatPlannerAgent — 模型驱动的对话规划**
- 新增 `agents/ChatPlannerAgent.kt`：将用户输入 + `ChatContext`（模型是否就绪、相册数、流水线状态、活跃 eventId、视频/候选摘要）打包后给 Gemma，要求输出一个 JSON `Plan`：`action ∈ {ANSWER, SUGGEST_EDITS, SCAN_ALBUM, MAKE_CANDIDATE, ITERATE, OPEN_ALBUM, OPEN_MODELS, CLARIFY}` + 自然语言回复 + 可选 `targetEventId` / `feedbackText` / `confidence`。
- 解析失败时退化到关键词 fallback；模型未就绪时直接返回引导导入模型的固定 Plan。
- `planAudio()` 直接吃 16 kHz PCM WAV，复用同一 SYSTEM_PROMPT —— 用户既可以打字也可以按住说话，对话上下文不分叉。
- ChatViewModel / ChatStore / ChatModels 同步：Plan 结果驱动 UI 跳转（Album / Models 页）、`SUGGEST_EDITS` 自动把 `feedbackText` 投递到目标 vlog 的迭代面板。

**B. 语音输入接入对话**
- `HoldToDictateViewModel` 去掉硬编码 `en-US`，改读 `Locale.getDefault().toLanguageTag()`；新增 `recognitionAvailable` 探测，无识别器时不再无声失败，UI 拿到中文错误提示并直接回填空字符串。
- `cancelSpeechRecognition()` 现在会真正调用 `speechRecognizer.cancel()`，避免回调泄漏。
- ChatScreen 新增麦克风按钮 + 录音过程的实时波形 + Toast 错误兜底；录到的 WAV 走 `planAudio()` 直送 Gemma，跳过 STT。

**C. 单素材标注流（VlogIndexWorker）**
- 新增「按住相册某张图 → 手动重标」入口：`VlogIndexWorker` 新支持 `KEY_FORCE_ANNOTATION` 强制重跑 VLM；新增 `singleAssetWorkName(assetId)` 作为每素材独立 WORK_NAME，避免与全量后台索引互相取消。
- 完成后通过新的 `vlog_pilot_asset_annotation` 通知通道弹一条「素材标注完成」系统通知，三种文案分支：无模型 / 拿到语义标签 / 仅完成基础检测无语义。
- `PerceptionCache.get` 现在双键查找（asset 对象 + asset.id 字符串），允许从聊天/相册不同入口命中同一缓存条目。

**D. `VlmAnnotator.hasSignal()` 覆盖完整 schema**
- 旧实现只看 `scene` / `action` / `salient` / `video.summary` 四个字段，导致只产生 `subjects` / `mood` / `composition` / `lighting` / `narrativeRoleHint` / `actionArc` 等其他标签的样本被判为"无信号"白白重跑。新实现把 schema 里所有可能字段都纳入判断，节省一次 Gemma 调用。
- `VlogIndexWorker.hasSemanticAnnotation()` 也升级到同样的覆盖面，决定「是否触发 VLM 标注」时不再过保守。

**E. 模型管理瘦身（VlogPilot 视角）**
- `GlobalModelManager` 抽屉标题从 gallery 通用 `drawer_models_label` 改为「VlogPilot 模型」；点击模型不再弹任务选择 bottom sheet（Gallery 残留），改为 snackbar 提示「<模型> 已可在 VlogPilot 中使用」。
- 排序调整：**已导入模型在前**，内置模型在后 —— 用户刚成功导入的 `.litertlm` 立即可见，不再被内置占位条目挤到屏幕外。
- 新增一行说明文案：「导入本地 .litertlm 模型后，回到对话即可扫描相册和生成分镜。这个页面只管理模型，不会跳转到 Gallery 的任务页。」
- 列表重渲触发器从单一 `modelImportingUpdateTrigger` 扩展到 `(trigger, loadingAllowlist, modelListVersion)`，导入后 UI 不再需要手动下拉。

**F. CLAUDE.md 重写**
- 326 行 → 140 行，去掉冗长的"为什么这是地雷"叙述，保留可执行规则。把架构详解、调试观测、测试命令从 README 抽出到 `docs/` 后，CLAUDE.md 现在只列地雷、house rules 和 Windows/ADB 注意事项，新对话冷启动更快。

**测试**：编译通过，vivo X200 Pro 真机验证语音输入 → ChatPlanner → 触发 SCAN_ALBUM / MAKE_CANDIDATE / OPEN_MODELS 三条路径，单素材重标弹通知。

---

## v6.0 — 智能剪辑 + Apple 风格 UI + 导出闭环 (2026-05-09)

流水线获得了更丰富的 **AI 剪辑语言**（逐镜头速度 / Ken Burns 缩放 / 剪辑理由），相册浏览器的滚动卡顿修复，结果页支持发布（保存到相册 + 分享至小红书/微信），整个 VlogPilot 模块进行了 iOS 风格视觉重塑，并新增了 Claude 风格的**工作进程时间线**，让用户实时观看智能体链的执行过程。

**A. V1 剪辑增强**
- `EditorAgent.transitionFor()` 不再将 7 种高级转场降级为交叉淡化——`CompositeRenderer` 已支持全部转场，降级是遗留的保守门控。
- 实现 `pan_left` / `pan_right` Ken Burns 效果（此前仅在归一化中接受，但缩放滤镜只接入了 `in` / `out`，导致 AI 意图被静默丢弃）。
- 新增 `travel_long`（8  slot）和 `event_recap`（9 slot）两种长结构模板，多日游和婚礼等场景获得比原 5 slot 日常/人物模板更丰富的叙事弧。
- 新增 `enforceTransitionDiversity` 后处理：3 个以上连续镜头使用相同转场时，第 3 个会被提升为硬切（短镜头）或淡化（长镜头）。Director 提示词也增加了角色 × 转场推荐表。
- `TimelineStoryboardBuilder` 新增可视化符号：时长 + Ken Burns 箭头、进入转场色 pill、CJK 字幕预览。Critic 提示词同步更新，新增 4 条评审规则（转场单调、开场弱、缺少高潮、字幕长度）。
- 新增 `softTimelineWarnings` 仅日志级别的审查门控，检测单调转场、连续同角色镜头、弱开场、缺少高潮，暂写入 `_state_history.jsonl`（计划观察一周后提升为硬触发）。

**B. V2.1 AI 剪辑意图 Schema**
- `ShotRequest` / `ShotSpec` 新增三个向后兼容的字段：
  - `speedHint` / `speedFactor`（0.5–1.75）— 逐镜头播放速率，渲染器首次支持加速（setpts < 1，上限 2×）。
  - `kenBurnsIntensity` / `kenBurnsZoom`（1.0–1.20）— 漂移幅度，从硬编码 1.08 上限改为线性插值到 Director 指定值。
  - `cutReason` — Director 的剪辑自由文本理由， surfaced 给 Critic 评审，不渲染。
- Critic 补丁字典、IntentParserAgent 反馈 schema、`applyPatches` 同步更新——提示词 + 解析器 + 应用补丁必须协同编辑（地雷 #8），现由测试保障。
- `ShotRenderCache.signature()` 升级到 `CACHE_VERSION = 2`，包含 `speedFactor` + `kbZoom`，避免语义变更后复用旧缓存 MP4。

**C. Apple 风格 UI 重塑**
- 新增主题模块 `ui/theme/`：`VlogPilotTokens`（iOS 系统色板、连续圆角、弹簧动画规格）、`VlogPilotTheme`（Material3 覆盖，不污染 gallery 其他部分）、`AppleComponents`（大标题、分组卡片、 hairline 分割线、主按钮、胶囊芯片、按压缩放等）。
- 所有主要屏幕重新换肤：VideoResultsUi（大标题 + 胶囊分类芯片 + 分组卡片）、WorkspaceTabs（iOS 分段控件）、IterationSheet（拖拽把手 + 语义色芯片）、EmptyStateUi / CuratorScreen / StoryUi / SettingsUi / AdvancedPickerUi 等。
- 标注标签从英文（scene / subjects / action...）改为中文（场景 / 主体 / 动作 / 情绪 / 时间感 / 亮点 / 叙事角色 / 镜头运动），与用户界面语言一致。

**D. 相册虚拟化 + 缩略图缓存**
- 新增 `ThumbnailCache`：LRU 内存缓存（约 1/8 堆内存，~32 MB），按 (assetId, maxSide) 分桶。`peek()` 提供同步缓存命中，`loadOrDecode()` 处理缺失时的 IO+解码。
- `MediaLoader.loadImage()` 新增 `preferRgb565`（网格缩略图使用），解码快 ~30%，内存省 ~50%。
- `AssetLibraryTab` 从非虚拟化 `Column + forEach` 重构为 `LazyColumn` 扩展，每行获得稳定 `key` 和 `contentType` 以回收 ViewHolder。
- `AssetThumb` 简化：去掉常驻 "IMG" pill，改为仅视频显示时长 pill（Apple Photos 风格），首次构图时同步查询缓存避免闪烁。

**E. 结果页可发布 + Claude 风格工作进程时间线**
- 新增 `VlogExporter`：
  - `saveToGallery()` 通过 MediaStore 写入 `Movies/VlogPilot/<title>.mp4`
  - `buildShareIntent()` 用 FileProvider URI 启动系统分享选择器
  - 结果页新增 4 个并排按钮：保存到相册 / 分享 / 改一改 / 换故事
- 结果页从行内展开收起改为独立详情导航页 `VlogDetailScreen`（iOS Photos / Settings 钻取模式），列表保持干净行 +  Chevron 指示器。
- 新增 `AgentTimelineCard`：以 Claude 工具调用风格渲染工作进程（着色圆点 + 连接线 + 智能体标签 + 时间戳 + 详情片段）。两处展示：
  1. 创作过程中 — StoryProgressCard 内嵌实时时间线（自动滚动到最新步骤，最大 320dp，可滚动查看历史）。
  2. 详情页 — `ProcessOutputs` 新增"工作时间线"区域，从 `_state_history.jsonl` 重放完整链路，运行结束后永久保留。

**F. 剪辑性能 + 可观测性**
- 逐资产 VLM 标注的瓶颈在联系人表尺寸——Gemma 视觉编码器与像素大致线性。单元格尺寸从 320×426 降至 240×320（~45% 像素缩减），最大帧数 20→16（HIGH）/ 16→12（LOW），预计每资产推理加速 ~30-40%。
- 新增 `vlm_step` 面包屑：逐资产性能拆分 `image <id> WxH load=Xms ask=Yms` / `video <id> sheet=WxH frames=N build=Xms ask=Yms`，可通过 `adb ... grep vlm_step` 读取。

**测试**：新增 `EditorAgentTest`（6 例）、`TemplateCatalogTest`（6 例）、`ShotFieldDefaultsTest`（3 例），共 67 例通过。vivo X200 Pro 上 `:app:installDebug` 构建通过。

---

## v5.0 — 自选故事 + 可逆反馈迭代 (2026-05-08)

用户可**挑选自己的素材并告诉 AI 想要什么样的视频**（不再"AI 只猜我过去 30 天"），且任何生成的视频都有**改一改**按钮，打开反馈面板——输入一句话、点击芯片、或点击故事板上的具体镜头，AI 迭代而无需重新跑完 5 分钟流水线。

核心架构洞察：**用户反馈是 Critic 反馈的特殊形式**。Critic 已输出 `RevisedRequest.patches: Map<String, String>` —— 迭代系统复用完全相同的 schema，80% 新代码是 plumbing 而非新流水线阶段。

**A. 迭代骨架 + RENDER_ONLY 范围**
- 视频卡片上的"改一改"按钮打开 `ModalBottomSheet`。本阶段接入两个快捷芯片：**去字幕** / **换调色** —— 均产生 `RenderOnlyPatch`，编排器的新 `runRenderOnly()` 应用到缓存时间线（字幕条 / 逐镜头调色覆盖）并重新渲染，无 Gemma 启动、无 LLM 调用，约 30 秒。
- 旧版 MP4 自动归档到 `candidates/archive/<eventId>__<timestamp>.mp4`。`ResultEventCard` 新增 History 图标按钮——存在 2 个以上版本时，点击在当前版和上一版之间切换播放。
- `IterationStore` 管理暂存 + 历史索引：`_pending_iteration/<eventId>.json`、`decisions/<eventId>/_history.json`、`decisions/<eventId>/iter_<N>/` 下的反馈输入/解析/时间线/性能文件。
- WorkRequests 现支持三种模式：`no key` → 完整流水线；`KEY_ITERATION_EVENT_ID` → 迭代（仅在需要时加载 Gemma）；`KEY_CURATION_REQUEST_ID` → 自选故事创作（加载 Gemma）。
- `ACTIVE` AtomicBoolean 仍序列化加载 Gemma 的运行（完整 + 自选共享锁）；RENDER_ONLY 迭代绕过 ACTIVE，因为它不加载 Gemma——不同事件的多个迭代 WorkRequest 可并发运行。

**B. 自选故事创作**
- Stories 主卡片新增第二个按钮"或者，我自己挑素材"，进入全屏 `CuratorScreen`。
- CuratorScreen 刻意极简：一个标题文案、一个多行文本框（可选，留空触发引导对话框）、一个最近素材网格（点击多选）、一个粘性底栏（实时计数 + 开始制作按钮）。**没有时长/节奏/调色/字幕的芯片轨道**——IntentParserAgent 从文本框提取这些。高级用户输入"30秒 / 慢节奏 / 温暖"即可达到相同效果。
- 选择 < 3 个素材时硬禁用开始按钮。
- `IntentParserAgent.parseInitial()` 调用 Gemma 解析用户文本，提取结构化 `UserBrief`（hook/payoff/tone/节奏/时长/必须出现的主体/避免内容/字幕策略/调色/原始文本）。
- `UserCurationPlanner` 将请求转换为合成 `Event`（`eventId = "user_<requestId>"`，requestId = sha1(排序后 asset_ids + intentText)[0..12]），稳定哈希保证相同自选请求命中缓存输出。
- 编排器新增 `runFromCuration` + `runUserCuratedEvent`，启动 Gemma、解析意图，然后调用现有 `runEvent` 并在线程化 `userBrief`。智能体提示词在 audience / director / 渲染交接点应用用户覆盖。
- Recall 评分公式新增 `subjectMatchBoost`：读取 `mustHaveSubjects`，当资产 VLM 标签提及关键词时，候选分数最多 +0.36（每项匹配 +0.18，上限）。
- 自选故事与自动发现事件并列显示，带小型"你做的"三级容器着色徽章。

**C. 完整反馈（SHOT_LEVEL / GLOBAL / MIXED）**
- IterationSheet 暴露完整反馈界面：3 列故事板网格（视频镜头显示修剪中点帧）、自由文本输入（占位符根据是否选中单元格自适应）、4 个芯片（更快 / 更慢 / 去字幕 / 换调色）。
- `IntentParserAgent.parseFeedback()`：
  - 用户文本为空且未点击单元格时完全跳过（纯芯片路径零 LLM 成本）
  - 否则 Gemma 读取当前时间线摘要 + 用户文本 + 目标序号 + 芯片，输出四种范围之一：RENDER_ONLY / SHOT_LEVEL / GLOBAL / MIXED
- 编排器新增三个独立子路径：`runShotLevel`（~30-60s）、`runGlobal`（~2-3min）、`runMixed`（~3-4min）。均为独立 `private suspend fun`，共享 `loadIterationContext` + `renderAndRecord` 辅助函数（遵守地雷 #1——每个方法字节码低于 64K）。迭代期间不运行 Critic——用户反馈即权威评审。

**D. 加固**
- `AgentRuntime.setContextTag(tag)` 让迭代路径在每条 `agent.jsonl` 标签前添加 `iter_v<N>/` 前缀，便于归因。
- 崩溃安全迭代恢复：VM 初始化时 `resumePendingIterations()` 扫描 `_pending_iteration/` 并重新入队孤立反馈文件（KEEP 策略）。`resumedIterations` 集合防止失败循环——结构损坏的迭代每冷启动仅一次恢复尝试。
- 每次迭代的 `perf.json` 记录 parse / editor / render 壁钟时间，`iterate()` 在所有 3 个 try/catch 分支（成功、取消、致命）中完成计时。
- CuratorScreen 内联错误横幅：未导入 LLM 时提交不再静默返回 Stories，而是保持页面并显示红色 `errorContainer` 横幅说明下一步操作，保留用户选择和意图文本。

**测试**：单元测试从 13 → 46 例。新增 `IterationPlannerTest`（21 例，覆盖芯片→补丁映射、applyRenderOnly 突变、applyGlobalToDirector 时长缩放+限制+色调/调色覆盖、applyGlobalToTimeline 字幕条+逐镜头调色、describeChanges 三范围摘要、更快/更慢芯片节奏过渡等）、`UserCurationPlannerTest`（10 例，覆盖 Event 构造、缺失资产绕过、<3 可用资产返回 null、requestId 哈希确定性+排序稳定性+输入敏感性、eventIdFor 前缀）。

**验证**：`compileDebugKotlin` / `assembleDebug` / `testDebugUnitTest`（46/46 通过）均绿色。vivo X200 Pro E2E 验证 8 个场景：自动生成、自选生成、空意图引导、纯芯片 RENDER_ONLY 迭代、点击目标镜头的 SHOT_LEVEL 迭代、纯文本 GLOBAL 迭代、上一版箭头版本回滚、强制停止中断迭代后的崩溃恢复。

---

## v4.4 — 5 智能体 Prompt 审计（3 轮通过）+ EventScout 两遍选择 (2026-05-08)

推送提交 `f22ba50`。

真实相册上选中的事件"都很垃圾"——粗排信号（资产数量、GPS 分布、人脸）形状正确但无法区分"孩子生日派对"和"厨房早餐"。且 5 个智能体提示词在多轮迭代中漂移：每个都积累了自由格式内容，示例与 schema 矛盾，解析器忽略的字段，以及缺失约束（Director 选择超出 audience 节奏区间的时长；Editor 从不解释 why_not_others；Critic 输出完整 ShotRequest 重写，Gemma 4 E2B 约一半时间出错）。

**EventScout — 两遍事件选择**
- **以前**：EventSelector 用廉价信号对所有候选事件排序，取 top `maxEvents`。两个粗排统计相同的事件——一个是"晨练公园"（好 vlog 素材），一个是"外卖等餐"（静止室内自拍）——分数相同。
- **现在**：两遍流程。
  1. 粗预排：相同 EventSelector，无 VLM 信号。Top 4-6 事件进入 Scout。
  2. Scout 通道：每个 top-K 事件从其非垃圾资产生成 3×3 联系人表；Gemma 4 读取并返回价值/信号对（故事弧强度、人物出现、节奏潜力）。
  3. 终排：EventSelector 注入 `scoutMap` 重新排序，取 `maxEvents`。
- **成本**：每个预排候选 1 次 Gemma 调用（天玑 9400 GPU 上约 3-5 秒），通过 `filesDir/event_scout_cache/<eventId>.json` JSON 缓存摊销。

**Prompt 审计 — 3 轮通过**
全部 7 个提示词（VLM 图片、VLM 视频、Browser、Audience、Director、Editor、Critic）从零重写：
- 每个提示词一个具体工作示例（Gemma 4 E2B 对完整填充的 JSON 示例的上下文遵循度远高于 `<占位符>` 的 schema 描述）。示例使用具体事件（烧烤聚会）以防模型安全复制。
- 更紧的 schema 约束：每个字符串字段的字符限制、每个闭集字段的枚举列表、可选字段的回退到空规则。
- 跨阶段信号流：
  - Audience 输出 `pace` 枚举（紧凑/均衡/悠长）。
  - Director 的 `target_duration_sec` 限制在对应节奏区间（15-18 / 18-22 / 22-28 秒）。
  - Director 消费 `available_signals`（编排器构建的资产实际存在的场景/长视频/显著性摘要），`visual_requirements`  grounded 于真实素材而非幻觉。
  - Director 直接输出 `color_grade` 枚举；Editor/渲染器原样使用，仅当 NEUTRAL 时回退到 `ColorGradeFromTone.pick(tone)`。
  - Editor 输出 `chosen_index` + `runner_up_index` + `why_not_others`。
  - Critic 输出 `verdict`（接受/修改/中止）+ `patches`（小字段→值映射）而非完整 `new_request` 重写。Patch 模式 Token 占用更小，Gemma 处理可靠性约 3 倍。

**Schema 新增**：`Pace` 枚举、`CriticVerdict` 枚举、`RevisedRequest.patches`、`DirectorBrief.colorGrade`、`VideoInsight.bestMomentWindowStart/End`（Editor.expandWindows 用此种子候选修剪窗口）。所有新字段均有默认值——现有 perception_cache JSON 干净反序列化，无需清除缓存。

**编排器变更**：`runEvent` 的 Critic 循环现按裁决分支：`ACCEPT` 中断，`ABORT` 写入 `critic_abort` 面包屑并中断（不在已知死胡同上浪费更多模型调用），`REVISE` 应用补丁。新增 `applyPatches(base, patches)`：空白值对叙事字段（mood/visual_requirements）跳过，但对装饰字段（caption/transition/ken_burns）生效（空即"移除"的有效意图）。新增 `buildAvailableSignals` 生成输入 Director 提示的信号摘要。删除 192 行死的 `MutableMap` 重载副本。

**防御性解析加固**：`AudienceAgent` 空白字段从回退修补；`EditorAgent` `chosen_index` 限制在 `[0, candidates.size-1]`；`CriticAgent` 空补丁且 null newRequest 的修订请求通过 `mapNotNull` 过滤。

**验证**：构建绿色（`compileDebugKotlin` 4s），测试绿色（13/13），Schema-cache 兼容。

---

## v4.3 — 事件选择：意图 + 功耗配置 + 固定/排除事件 + 清单 (2026-05-08)

推送提交 `d20e190`。

用户希望对事件选择施加偏置（"只要旅行事件" / "跳过我不喜欢的厨房照片"）并在长运行时根据热裕量节流。

- **`VlogPilotRunConfig`** — 运行时旋钮：`intent`（普通/旅行/儿童/美食等）、`powerProfile`（均衡/节能/极速）、`pinnedEventIds`（始终包含）、`excludedEventIds`（始终排除）。持久化到 filesDir，UI 可记住设置。
- **`PowerPacer`** — 在感知批次和 VLM 调用后插入 paced 睡眠。节能模式在每个 VLM 调用后增加 ~200ms（更平滑的热曲线）；极速模式移除 pacing；均衡为中间态。
- **意图感知 EventSelector** — `intent.eventBias()` 基于启发式调整每事件的 `valueScore`（旅行提升 GPS 分布+户外场景标签，儿童提升人脸计数，美食提升静止室内+近距离场景标签）。
- **EventSelectionManifest** — 持久化 JSON 清单，记录每次选择的决策：考虑了哪些事件、它们的 valueScore 分解、每个被选/排除的原因，以及运行配置。位于 `filesDir/decisions/_event_selection.json`。UI 可展示"为什么选这些事件"而无需重新运行选择。

---

## v4.2 — 内容评分事件选择 + 热控 pacing (2026-05-08)

推送提交 `2f05f66`。

将仅按recency的 `events.take(maxEvents)` 替换为内容评分的 `EventSelector.rank()`。valueScore 权重：资产数量（对数缩放）、视频存在（数量+总时长秒数）、时间跨度、GPS 分布、故事信号（人脸计数、场景切换多样性）、recency 仅作为小 tiebreaker。

热控 pacing — 天玑 9400 GPU 在 ~7 分钟持续推理后热节流。在感知循环和每次 VLM 调用后插入 ~150-200ms paced 睡眠，使结温保持在 80°C 以下。

---

## v4.0 / v4.1 — VLM-only 感知架构 + 64K 字节码修复 (2026-05-08)

推送提交 `35a1ed9`、`4ab249e`、`1ccb38f`。

**v4.0 — VLM 标注取代 CLIP/YOLO/FaceNet TFLite 栈**

TFLite 小模型栈（CLIP、YOLO、MobileFaceNet）从 v3.0 起就是已知缺口——计划中的公开 sub-50MB int8 来源从未出现。切换为逐资产一次 Gemma VLM 调用。

- **VlmAnnotator** 每资产运行一次，产出 `VlmTags`（场景、主体、动作、氛围、时间感、显著性、叙事角色提示）。视频额外产出 `VideoInsight`（帧时间戳、摘要、动作弧、最佳时刻索引、糟糕时刻索引）。
- **Recall.kt** 通过标签上的 `semanticOverlap()` 评分，取代 CLIP 嵌入的余弦相似度。信号可比，上下文丰富得多（模型可在精选时推理标签）。
- **成本**：天玑 9400 GPU + MTP 上约 3-5 秒/资产。逐资产 Perception JSON 缓存在 `filesDir/perception_cache/<assetId>.json`——同一相册重复运行基本免费。
- **VideoFrameSheetBuilder** 为每视频构建 N 帧联系人表（均匀采样 + 场景切换锚点），Gemma 一次调用标注整表。
- **Workspace 标签页** — Process / Decisions / Results UI，用于观看运行和检查逐事件输出。每 3 秒在 Dispatchers.IO 上轮询 `decisions/<eventId>/`。
- **删除**：`ClipEmbedder`、`ClipTokenizer`、`YoloDetector`、`YoloObj`、`FaceEmbedder`、`FaceClusterer`、`TfliteLoader`、`SharpestWindowPicker`。CLIP 本应提供的语义信号现由 VlmAnnotator 提供。不要重新添加它们。

**v4.1 — 流水线重启循环修复（真正的"v4 发布"里程碑）**

- **症状**：单次 `doWork()` 调用在下载→导入→感知→初始化→下载…之间以 2 秒 tight loop 运行。JobScheduler 显示一个 WorkSpec，run_attempt_count=1。三个错误理论浪费了一小时："Gemma 5 分钟冷启动"（假，约 15 秒）、"vivo logcat ACL"（假，只是 HWUI 溢出）、"vivo BTM 杀死 FGS"（假，甚至不是 kill）。
- **根因**：`PipelineOrchestrator.run()` 的协程状态机超出 ART 的 64K 字节码限制。Logcat 里一直存在这行：`Method exceeds compiler instruction limit: 64126`。超过 64K 后 ART 回退到解释器模式，且从保存标签恢复的调度静默中断——每次 `progress(...)` 挂起都在标签 0 = `run()` 的开头恢复。
- **修复**：将逐事件主体提取到 `runEvent()`，逐资产标注提取到 `runAnnotationPass()`——每个挂起点现在位于自己的状态机中，没有一个单独超过 64K。**此规则是永久性的：保持 `run()` 精简。** 编译警告（`Method exceeds compiler instruction limit: NNNNN`）只是警告而非错误，因此构建仍会通过——请注意观察。

**可观测性 — 磁盘面包屑**

vivo OriginOS 的 HWUI / SurfaceComposerClient 淹没 4MB logcat 环形缓冲区的速度快到约 7 分钟内覆盖我们的 `Log.d` 条目。为此，每次状态转换都写入磁盘：`_state.txt`、`_state_history.jsonl`、`_state_fatal.txt`、`agent_log/agent.jsonl`、`decisions/<eventId>/*.json`、`perception_cache/<assetId>.json`、`candidates/<eventId>.mp4`。通过 `adb shell run-as com.google.aiedge.gallery cat files/<path>` 读取。 survive logcat 溢出 AND 进程死亡。

---

## v3.2 — Recall 时间窗、Critic 2 轮迭代、ColorGrade、最清晰窗口选择器 (2026-05-08)

推送提交 `0f797d0`。

v3.1 端到端后的 5 智能体链定向审计。

- **Recall 时间窗** — 逐镜头召回限制在槽位预测时间戳的 ±1.5 小时内（而非"事件中的任意资产"）。阻止 Editor 为晚间事件的每个槽位都挑选同一张早晨照片。
- **Critic 2 轮上限** — 原为 3，现为 2。第 3 轮迭代在 Gemma 4 E2B 上几乎从不改善剪辑，只是重新洗牌资产。`MAX_CRITIC_ITER = 2`。
- **ColorGrade** — Director 从 {warm, cool, vibrant, muted, cinematic, vintage, neutral} 中挑选，通过 ffmpeg `colorchannelmixer` / `eq` 链应用。（v4.4 改为显式输出而非关键词推断。）
- **最清晰窗口选择器** — 视频镜头修剪在资产时长内选择最清晰的 N 秒窗口，而非"前 N 秒"。（v4.0 被 VideoWindowPicker 取代，后者消费 VLM best_moment_index + 窗口提示。）

---

## v3.1 — C 端 UX + 可靠性 + 性能优化 (2026-05-07)

推送提交 `ef3ecb3`。

"让它在手机上真正端到端工作"的优化 pass。v3.0 接通了流水线，但撞上了一堵"几乎能工作"的墙：每个镜头渲染失败、每个事件都跑完整流水线、UI 在长运行时卡住、缺失原生模型的崩溃导致 Worker 变砖。v3.1 后应用直接打开到 VlogPilot，产出真实 MP4，并实时展示 AI 的逐阶段输出。

已在 vivo X200 Pro（天玑 9400，8 GB RAM，~150 张照片 / 30 天）上验证。

### UX

- **应用直接打开到 VlogPilot**：`startDestination = ROUTE_VLOGPILOT`。应用打开直接进入 VlogPilot 屏幕，一个生成按钮。右上角 Apps 图标前往 gallery 首页获取高级功能。
- **一个按钮代替两个**："扫描相册" + "生成 vlog 候选" 合并为单个生成按钮，运行期间兼作取消按钮。
- **逐事件决策链查看器**：从单行状态"evt_005: director"升级为每个事件一张可展开卡片。内部：Browser 故事摘要、Audience 情感简报、Director 标题+色调+叙事弧、Editor 逐镜头列表（字幕+时长+理由）、Critic 问题、渲染输出。每 3 秒通过轮询 `filesDir/decisions/<eventId>/` 自动刷新。
- **内嵌 MP4 播放器**：决策卡片的渲染区域嵌入 `VideoView`（9:16，标准 MediaController），点击内联播放。无需额外依赖。

### 可靠性 — 8 个真实缺陷

| 缺陷 | 症状 | 根因 | 修复 |
|---|---|---|---|
| Android 14+ FGS 类型缺失 | 点击生成 → 立即 `InvalidForegroundServiceTypeException` 崩溃 | Android 14+ 要求 `foregroundServiceType` 在运行时 `ForegroundInfo` 中声明，仅 manifest 不够；使用了默认 type=NONE 的 2 参数构造器 | 3 参数 `ForegroundInfo(..., FOREGROUND_SERVICE_TYPE_DATA_SYNC)` |
| face_landmarker.task 缺失时原生 SEGV | 流水线启动后立即崩溃于 `strlen(null)` | MediaPipe `createFromOptions` 在模型文件缺失时不抛 Java 异常，而是在 native 代码 SEGV，Kotlin try/catch 无法拦截 | 调用 MediaPipe 前探测文件（filesDir/models 然后 bundled assets）；缺失时 `landmarker = null`，`detect()` 返回空列表，流水线优雅降级 |
| 智能体 JSON 解析可杀死事件 | 随机事件失败于 `JsonDecodingException` | Gemma 4 E2B 偶尔对较长 schema（Critic 最严重）输出畸形 JSON；未处理的异常传播出去，整个事件标记为失败 | 5 个智能体均将 `parseToJsonElement` 包裹在 try/catch 中，解析失败时回退到合理默认值 |
| CaptionFilter 无字体时杀死 ffmpeg | 每个镜头渲染失败于 `ffmpeg failed: 1 null` | ffmpeg `drawtext` 过滤器**需要** `fontfile=`；fontPath 为 null 时仍无条件输出 `drawtext=text='...'`，导致整个滤镜图中止 | fontPath 为 null 时 CaptionFilter 返回空字符串；ShotRenderer 跳过逗号前缀，ffmpeg 无字幕渲染而非失败。同时捆绑 Source Han Sans CN Bold（8.5 MB）覆盖 CJK 字幕 happy path |
| ffmpeg-kit-16kb min 中无 libx264 | 每个镜头渲染失败于 `Unknown encoder 'libx264'` | `ffmpeg-kit-16kb` 精简版不捆绑 libx264（GPL 许可证）；硬编码了 `-c:v libx264 -preset veryfast -crf 22` | 运行时探测 ffmpeg 的 `-encoders` 列表，选择最佳可用编码器：`h264_mediacodec`（硬件 H.264，~10 倍更快，低耗电）→ `libx264` → `mpeg4`（LGPL 回退，始终存在）。进程生命周期内缓存 |
| Worker 静态变量在进程死亡时丢失 | OOM kill / 强制停止 / 厂商省电杀死前台服务后，WorkManager 重试 Worker，`VlogPilotModelRegistry.resolvedModel` → null → 失败 | `@Volatile var` 是进程局部的。进程死亡，变量随之死亡 | 同时将模型名称持久化到 SharedPreferences。重启后 Worker 读取持久化名称并给出精确错误："流水线中断（进程被杀死）。再次点击生成——模型 'gemma-4-E2B-it' 仍已导入。" 用户可见行为现为"多点击一次恢复"而非静默失败 |
| ClipTokenizer 读取了错误目录 | 文本嵌入始终全零（静默），CLIP 召回降级为仅清晰度排序 | ModelDownloader 将 vocab/merges 写入 `<filesDir>/models/`，但 ClipTokenizer.tryLoad 只查找 `assets/models/`。文件在磁盘上但对 tokenizer 不可见 | 双层解析——filesDir 优先，然后 bundled assets |
| MediaLoader 无法解码视频 URI | 每个视频资产记录 `loadImage failed: content://media/external/video/...`，视频从感知中静默丢弃 | `BitmapFactory.decodeStream` 无法解码 MP4 容器。所有资产类型都通过它发送 | 按 `asset.mediaType` 分支——IMAGE/LIVE_PHOTO 对静态图使用 BitmapFactory，VIDEO 对片段中部使用 `MediaMetadataRetriever.getFrameAtTime` |

### 性能 — 7 项提升

| 优化 | 以前 | 以后 | 影响 |
|---|---|---|---|
| ABI 拆分 | APK 520 MB，原生库为 4 个 ABI 各发一份 | `ndk { abiFilters += listOf("arm64-v8a") }` | APK 243 MB |
| maxEvents=2 上限 | 7 个事件 × ~15 分钟 = ~90 分钟壁钟时间 | 新事件限制为 2 个最近（~20 分钟总计）。恢复事件绕过上限 | 显著缩短首次运行时间 |
| 恢复模式 | 取消运行丢失每事件 10-15 分钟 VLM 工作 | `timeline_final.json` 已缓存但无 MP4 的事件跳过全部 5 个智能体阶段直接进入 ffmpeg | 恢复事件免费 |
| 逐资产 Perception 缓存 | 每次生成对所有照片重新运行 YOLO+人脸+CLIP+NSFW+清晰度 | `filesDir/perception_cache/<assetId>.json` JSON 缓存 | 同一相册重新运行：感知阶段 ~30 秒而非 ~3 分钟 |
| DecisionStore.loadAll 移至 Dispatchers.IO | UI 滚动/展开卡片时明显卡顿，`Choreographer: Skipped 144 帧`（~2.4 秒冻结） | `withContext(Dispatchers.IO) { DecisionStore.loadAll(...) }`，轮询间隔从 2s 放宽到 3s | UI 不再卡顿 |
| Bitmap recycle | 逐资产 Perception 创建 ~6 个 Bitmap，原生像素缓冲区保持到 GC。100 资产 = ~1.3 GB 峰值堆 → 低内存杀手风险 | 三个显式回收点：PerceptionEngine.analyze 封面+人脸裁剪、BitmapPrep 缩放中间产物、MontageBuilder.renderCell 单元格合成后 | 峰值堆大致减半（~700 MB） |
| MontageAgent 发送所有联系人表页 | >121 张照片的事件（一页容量）丢失第一页后的上下文——智能体只看到 `sheets.first()` | 每页一次 VLM 调用，然后合并结果（拼接 storyline_summary，去重 key_moments，合并 characters_observed） | 大事件不再丢失上下文 |

### 其他

- **PhotoIngest.Filter**：新增过滤参数，`cameraOnly = true`（MediaStore RELATIVE_PATH 白名单 `DCIM/`，排除 Pictures/Screenshots/微信/百度网盘/Telegram/Download 等，典型手机上资产数量减少 ~50%）；`maxVideoSizeBytes = 100 MB`；`maxImageSizeBytes = 50 MB`；`minImageSizeBytes = 50 KB`。
- **Prompt 防示例泄漏**：MONTAGE / AUDIENCE / DIRECTOR 系统提示词中的具体示例值（如 `"characters_observed": ["主角A：穿蓝衣女孩", ...]`）被替换为 `<...>` 占位符语法 + 显式"不要复制 schema 占位符"指令。Gemma 4 E2B（2B 参数）此前将这些视为 ground truth 并逐字复制到输出中。
- **PerfTimer + perf.json**：包裹每个智能体调用 + 渲染的 `System.nanoTime` 测量，持久化到 `filesDir/decisions/<eventId>/perf.json`。UI 展示逐事件单行摘要。
- **测试**：新增 `JsonExtractorTest`（10 例），覆盖干净对象、嵌套大括号、代码围栏包装、`<think>` 标签、散文前缀/后缀、转义引号、未闭合大括号、垃圾、数组提取。`./gradlew :app:testDebugUnitTest` 运行，10/10 通过，25 秒。
- **资源**：捆绑 `assets/fonts/SourceHanSansSC-Bold.otf`（8.5 MB）用于 CJK 字幕；`assets/bgm/{warm,cool,...}.mp3`（47 MB，7 条免版税音轨）。

### 已知缺口（推迟到 v3.2+）

| 缺口 | 当前变通方案 | 计划 |
|---|---|---|
| `yolo26n_int8.tflite` 未捆绑/未获取 | sceneClass = "unknown"，对下游无影响 | 寻找或训练小型 YOLO TFLite |
| `mobilefacenet.tflite` 未捆绑/未获取 | personId = UNK，无基于人物的镜头过滤 | 同上 |
| `mobileclip2_*.tflite` 仅 340 MB+ float 文件可用 | CLIP 图像/文本嵌入为空，召回回退到清晰度 | 寻找 sub-50 MB int8 变体 |
| eventId 是每运行序数，非内容稳定 | 仅当分割完全相同时恢复才有效 | 切换到 `evt_<date>_<contentHash>` |
| 进程杀死时 Worker 模型恢复为"点击重试" | SharedPreferences 支撑的清晰错误，手动重试 | 接入 HiltWorker + 从 gallery 状态重建 Model |
| 单行 ffmpeg 编码器探测每次新进程都运行 | <100 ms 成本，可接受 | 可持久化探测结果跨应用启动 |

---

## v3.0 — 初始 Android 移植 (2026-05-07)

推送提交 `dc46efe`。

PC 端 pc-pilot Python 流水线（Browser → Director → Editor → Critic）移植到 Android 作为新的 VlogPilot 自定义任务。所有推理在 Gemma 4 导入后设备端运行。38 个 Kotlin 文件，~3,800 行代码。

技术栈：
- VLM: Gemma 4 E2B-it int4 via litertlm 0.11.0（MTP 加速）
- 目标检测: Ultralytics YOLO26n int8 TFLite
- 人脸: MediaPipe FaceLandmarker + MobileFaceNet TFLite
- CLIP: MobileCLIP2-S1 dual-tower TFLite
- NSFW: AdamCodd ViT int8 ONNX
- 渲染: ffmpeg-kit-16kb 6.1.1

v3.0.1 跟进提交 `9b841f8` 移除了 Gemma 4 的并行 ModelDownloader，改用 gallery 现有的 `ModelAllowlist` + `DownloadWorker` 基础设施，并使 AgentRuntime 在用户从其他任务先打开 Gemma 4 时复用已初始化的引擎。
