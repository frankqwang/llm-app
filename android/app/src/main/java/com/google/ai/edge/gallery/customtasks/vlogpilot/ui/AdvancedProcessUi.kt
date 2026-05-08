/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Process viewer for the on-device VlogPilot pipeline. The screen is built as
 * a compact editing console: input assets, agent outputs, timeline decisions,
 * critique notes, and the rendered candidate stay in one inspectable flow.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.google.ai.edge.gallery.customtasks.vlogpilot.agents.PromptStrings
import com.google.ai.edge.gallery.customtasks.vlogpilot.agents.EventScoutAgent
import com.google.ai.edge.gallery.customtasks.vlogpilot.agents.VlmAnnotator
import com.google.ai.edge.gallery.customtasks.vlogpilot.perception.MediaLoader
import com.google.ai.edge.gallery.customtasks.vlogpilot.runtime.GenerationIntent
import com.google.ai.edge.gallery.customtasks.vlogpilot.runtime.PowerProfile
import com.google.ai.edge.gallery.customtasks.vlogpilot.runtime.VlogPilotRunConfig
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Asset
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Event
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.MediaType
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Perception
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.ShotSpec
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Timeline
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.VlmTags
import com.google.ai.edge.gallery.customtasks.vlogpilot.worker.EventDecisions
import com.google.ai.edge.gallery.customtasks.vlogpilot.worker.EventCandidateSnapshot
import com.google.ai.edge.gallery.customtasks.vlogpilot.worker.EventSelectionManifest
import com.google.ai.edge.gallery.customtasks.vlogpilot.worker.EventSelectionStatus
import com.google.ai.edge.gallery.customtasks.vlogpilot.worker.StagePerf
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun AssetManagementCard(d: EventDecisions) {
  val timeline = d.timelineFinal ?: d.timelineV1
  val usedOrders = remember(timeline) {
    timeline?.shots.orEmpty().groupBy { it.assetId }.mapValues { entry -> entry.value.map { it.order } }
  }
  val tagged = d.inputPerceptions.values.count { it.vlmTags.scene.isNotBlank() }

  PanelCard {
    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      SectionHeader(
        icon = Icons.Outlined.PhotoLibrary,
        title = "事件 ${shortId(d.eventId)} 素材",
        subtitle = "${d.inputAssets.size} 个输入 / $tagged 个有 VLM 标签",
      )
      MetricGrid(
        items = listOf(
          MetricDatum("输入", d.inputAssets.size.toString()),
          MetricDatum("已标注", tagged.toString(), tagged > 0),
          MetricDatum("入片", usedOrders.size.toString(), usedOrders.isNotEmpty()),
        ),
        columns = 3,
      )
      if (d.inputAssets.isNotEmpty()) {
        AssetStrip(assets = d.inputAssets.take(24), totalCount = d.inputAssets.size)
      }
      d.inputAssets.forEach { asset ->
        AssetAnnotationRow(
          asset = asset,
          perception = d.inputPerceptions[asset.id],
          usedOrders = usedOrders[asset.id].orEmpty(),
        )
      }
    }
  }
}

@Composable
internal fun AssetAnnotationRow(asset: Asset, perception: Perception?, usedOrders: List<Int>) {
  var expanded by remember { mutableStateOf(false) }
  val tags = perception?.vlmTags
  val videoInsight = perception?.videoInsight
  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .clickable { expanded = !expanded },
    shape = RoundedCornerShape(12.dp),
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.24f),
  ) {
    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
      ) {
        AssetThumb(
          asset = asset,
          modifier = Modifier.width(56.dp).height(72.dp),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
          Text(
            asset.displayName.ifBlank { shortId(asset.id) },
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            assetMeta(asset),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
            annotationSummary(tags, videoInsight?.summary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) 4 else 2,
            overflow = TextOverflow.Ellipsis,
          )
          LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            item { SignalTag(if (tags?.scene?.isNotBlank() == true) "已标注" else "未标注", Icons.Outlined.Search, tags?.scene?.isNotBlank() == true) }
            if (usedOrders.isNotEmpty()) item { SignalTag("入片 #${usedOrders.joinToString(",")}", Icons.Outlined.CheckCircle, true) }
            if ((videoInsight?.bestMomentSec ?: 0f) > 0f) item { SignalTag("best ${"%.1fs".format(Locale.US, videoInsight!!.bestMomentSec)}", Icons.Outlined.PlayArrow, true) }
            item { SignalTag("face ${perception?.faces?.size ?: 0}", Icons.Outlined.Person) }
          }
        }
        Icon(
          imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
          contentDescription = if (expanded) "收起" else "展开",
          modifier = Modifier.size(22.dp),
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      if (expanded) {
        HorizontalDivider()
        KeyValue("assetId", asset.id)
        perception?.let { p ->
          KeyValue("质量", "sharp=${"%.2f".format(Locale.US, p.sharpness)} / bright=${"%.2f".format(Locale.US, p.brightness)} / nsfw=${"%.2f".format(Locale.US, p.nsfwScore)}")
          KeyValue("过滤", if (p.isJunk) "junk: ${p.junkReason}" else "可用")
          if (p.sceneCuts.isNotEmpty()) KeyValue("切点", p.sceneCuts.take(8).joinToString(", ") { "%.1fs".format(Locale.US, it) })
          AnnotationKeyValues(p.vlmTags)
          VideoInsightKeyValues(p)
        } ?: Text(
          "还没有 perception_cache 记录。通常是该素材还没进入感知/标注阶段，或缓存被清理。",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
internal fun AnnotationKeyValues(tags: VlmTags) {
  if (tags.scene.isBlank() && tags.subjects.isEmpty() && tags.action.isBlank() && tags.mood.isBlank()) {
    Text("VLM 标签为空。Recall 会退化到质量/时序/时长信号。", style = MaterialTheme.typography.bodySmall)
    return
  }
  KeyValue("scene", tags.scene)
  if (tags.subjects.isNotEmpty()) KeyValue("subjects", tags.subjects.joinToString("、"))
  KeyValue("action", tags.action)
  KeyValue("mood", tags.mood)
  KeyValue("time", tags.timeFeel)
  KeyValue("salient", tags.salient)
  KeyValue("role", tags.narrativeRoleHint)
}

@Composable
internal fun VideoInsightKeyValues(perception: Perception) {
  val insight = perception.videoInsight
  if (insight.frameTimestampsSec.isEmpty() && insight.summary.isBlank()) return
  KeyValue("video summary", insight.summary)
  KeyValue("action arc", insight.actionArc)
  if (insight.bestMomentSec > 0f) KeyValue("best moment", "%.1fs (#%d)".format(Locale.US, insight.bestMomentSec, insight.bestMomentIndex))
  if (insight.badMomentIndices.isNotEmpty()) KeyValue("avoid frames", insight.badMomentIndices.joinToString(", "))
  if (insight.frameTimestampsSec.isNotEmpty()) KeyValue("sample frames", insight.frameTimestampsSec.joinToString(", ") { "%.1fs".format(Locale.US, it) })
}

@Composable
internal fun EventDecisionCard(d: EventDecisions) {
  var expanded by remember { mutableStateOf(false) }
  val timeline = d.timelineFinal ?: d.timelineV1
  val assetMap = remember(d.inputAssets) { d.inputAssets.associateBy { it.id } }
  val title = d.director?.title?.takeIf { it.isNotBlank() }
    ?: d.memory?.storylineSummary?.takeIf { it.isNotBlank() }?.let { it.take(34) }
    ?: "事件 ${shortId(d.eventId)}"
  val shotCount = timeline?.shots?.size ?: 0
  val durationSec = timeline?.shots?.sumOf { it.durationSec.toDouble() } ?: 0.0

  PanelCard {
    Column(
      modifier = Modifier.padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(12.dp))
          .clickable { expanded = !expanded }
          .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            eventSubtitle(d),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        IconButton(onClick = { expanded = !expanded }) {
          Icon(
            imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = if (expanded) "收起" else "展开",
          )
        }
      }

      MetricGrid(
        items = listOf(
          MetricDatum("素材", d.inputAssets.size.toString()),
          MetricDatum("镜头", shotCount.toString()),
          MetricDatum("时长", formatSec(durationSec)),
          MetricDatum("渲染", if (d.mp4Path != null) "完成" else "未完成", d.mp4Path != null),
        ),
        columns = 2,
      )

      StageRail(d)

      if (d.inputAssets.isNotEmpty()) {
        SectionHeader(
          icon = Icons.Outlined.Search,
          title = "本事件扫描素材",
          subtitle = "${d.inputAssets.size} 个输入，按时间线参与 browse / recall",
        )
        AssetStrip(assets = d.inputAssets.take(24), totalCount = d.inputAssets.size)
      }

      if (expanded) {
        HorizontalDivider()
        d.mp4Path?.let { path ->
          DecisionSection(icon = Icons.Outlined.PlayArrow, title = "Render", subtitle = "成片预览") {
            VideoPreview(mp4Path = path)
            Text(
              File(path).name,
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
        ProcessOutputs(d = d, timeline = timeline, assetMap = assetMap)
      } else {
        CollapsedSummary(d = d, timeline = timeline)
      }
    }
  }
}

@Composable
internal fun StageRail(d: EventDecisions) {
  val timeline = d.timelineFinal ?: d.timelineV1
  val stages = listOf(
    StageUi("扫描", d.inputAssets.isNotEmpty()),
    StageUi("浏览", d.memory != null),
    StageUi("观众", d.audience != null),
    StageUi("导演", d.director != null),
    StageUi("剪辑", timeline != null),
    StageUi("审片", d.critique != null || timeline?.critiqueHistory?.isNotEmpty() == true),
    StageUi("渲染", d.mp4Path != null),
  )
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(12.dp),
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.20f),
  ) {
    LazyRow(
      modifier = Modifier.padding(8.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      items(stages) { stage ->
        StageStep(stage)
      }
    }
  }
}

@Composable
internal fun StageStep(stage: StageUi) {
  val container = if (stage.done) {
    MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
  } else {
    Color.Transparent
  }
  val content = if (stage.done) {
    MaterialTheme.colorScheme.primary
  } else {
    MaterialTheme.colorScheme.onSurfaceVariant
  }
  Surface(shape = RoundedCornerShape(8.dp), color = container, contentColor = content) {
    Row(
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
      horizontalArrangement = Arrangement.spacedBy(5.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        imageVector = if (stage.done) Icons.Outlined.CheckCircle else Icons.Outlined.Timer,
        contentDescription = null,
        modifier = Modifier.size(14.dp),
      )
      Text(stage.label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
    }
  }
}

@Composable
internal fun CollapsedSummary(d: EventDecisions, timeline: Timeline?) {
  val text = when {
    d.memory?.storylineSummary?.isNotBlank() == true -> d.memory.storylineSummary
    d.director?.narrativeArc?.isNotEmpty() == true -> d.director.narrativeArc.joinToString(" / ")
    timeline != null -> "已生成 ${timeline.shots.size} 个镜头，展开查看选片和理由。"
    d.inputAssets.isNotEmpty() -> "已记录输入素材，等待 Agent 输出。"
    else -> "等待事件输入。"
  }
  Text(
    text,
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    maxLines = 2,
    overflow = TextOverflow.Ellipsis,
  )
}

@Composable
internal fun ProcessOutputs(
  d: EventDecisions,
  timeline: Timeline?,
  assetMap: Map<String, Asset>,
) {
  d.memory?.let { memory ->
    DecisionSection(icon = Icons.Outlined.Visibility, title = "Browse", subtitle = "事件记忆") {
      Text(memory.storylineSummary, style = MaterialTheme.typography.bodySmall)
      if (memory.emotionalArc.isNotBlank()) KeyValue("情绪弧线", memory.emotionalArc)
      if (memory.charactersObserved.isNotEmpty()) KeyValue("人物", memory.charactersObserved.joinToString(", "))
      if (memory.visualStyleSignals.isNotBlank()) KeyValue("视觉信号", memory.visualStyleSignals)
      memory.keyMoments.take(4).forEach { moment ->
        Text(
          "#${moment.imageIndex} ${shortId(moment.assetId)} · ${moment.why}",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }

  d.audience?.let { audience ->
    DecisionSection(icon = Icons.Outlined.Person, title = "Audience", subtitle = "观看诉求") {
      if (audience.emotionalPayoff.isNotBlank()) KeyValue("情绪回报", audience.emotionalPayoff)
      if (audience.hookStrategy.isNotBlank()) KeyValue("开头钩子", audience.hookStrategy)
      if (audience.povVoice.isNotBlank()) KeyValue("视角", audience.povVoice)
      if (audience.pacingGuidance.isNotBlank()) KeyValue("节奏", audience.pacingGuidance)
      if (audience.avoidList.isNotEmpty()) KeyValue("避免", audience.avoidList.joinToString(", "))
    }
  }

  d.director?.let { director ->
    DecisionSection(icon = Icons.Outlined.Movie, title = "Director", subtitle = "叙事脚本") {
      if (director.title.isNotBlank()) {
        Text(director.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
      }
      KeyValue("基调", director.tone)
      KeyValue("目标时长", formatSec(director.targetDurationSec.toDouble()))
      if (director.narrativeArc.isNotEmpty()) KeyValue("叙事弧线", director.narrativeArc.joinToString(" -> "))
      director.shotBlueprint.take(8).forEach { req ->
        Text(
          "${req.position}. ${req.role.name.lowercase()} · ${"%.1f".format(Locale.US, req.durationSec)}s · ${req.visualRequirements}",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }

  timeline?.let { tl ->
    DecisionSection(icon = Icons.Outlined.Edit, title = "Editor", subtitle = "最终时间线 ${tl.shots.size} shots") {
      tl.shots.forEach { shot ->
        ShotRow(shot = shot, asset = assetMap[shot.assetId])
      }
    }
  }

  val critiqueHistory = timeline?.critiqueHistory.orEmpty()
  if (d.critique != null || critiqueHistory.isNotEmpty()) {
    DecisionSection(icon = Icons.Outlined.Search, title = "Critic", subtitle = "审片反馈") {
      val critique = d.critique ?: critiqueHistory.lastOrNull()
      if (critique == null || (critique.issues.isEmpty() && critique.revisedRequests.isEmpty())) {
        Text("没有发现需要修改的问题。", style = MaterialTheme.typography.bodySmall)
      } else {
        critique.issues.forEach { issue ->
          Text(
            "• $issue",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        if (critique.revisedRequests.isNotEmpty()) {
          KeyValue("重选镜头", "${critique.revisedRequests.size} 个")
        }
      }
      if (critiqueHistory.size > 1) {
        KeyValue("迭代次数", critiqueHistory.size.toString())
      }
    }
  }

  d.perf?.let { perf ->
    DecisionSection(icon = Icons.Outlined.Timer, title = "Timing", subtitle = "阶段耗时") {
      PerfGrid(perf)
    }
  }
}

@Composable
internal fun ShotRow(shot: ShotSpec, asset: Asset?) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 6.dp),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
    verticalAlignment = Alignment.Top,
  ) {
    if (asset != null) {
      AssetThumb(
        asset = asset,
        modifier = Modifier
          .width(48.dp)
          .height(64.dp),
        showType = false,
      )
    } else {
      Box(
        modifier = Modifier
          .width(48.dp)
          .height(64.dp)
          .clip(RoundedCornerShape(10.dp))
          .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
      ) {
        Icon(Icons.Outlined.PhotoLibrary, contentDescription = null, modifier = Modifier.size(20.dp))
      }
    }
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(
        "#${shot.order} · ${shot.mediaType.name.lowercase()} · ${"%.1f".format(Locale.US, shot.durationSec)}s · ${shot.transitionIn.name.lowercase()}",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      if (shot.caption.isNotBlank()) {
        Text(shot.caption, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
      }
      Text(
        "${shortId(shot.assetId)} · ${shot.rationale.ifBlank { "已选入时间线" }}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

