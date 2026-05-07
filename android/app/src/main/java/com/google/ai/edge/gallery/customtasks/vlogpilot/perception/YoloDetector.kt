/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Ultralytics YOLO26n int8 TFLite. Output is (1, 4+nc, 8400) — first 4 rows are
 * cxcywh in input space, then nc class scores. NMS is end-to-end on the model
 * itself in YOLO26's NMS-free design, but we still apply a confidence threshold
 * + (defensive) class-wise NMS for robustness across export variants.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.perception

import android.content.Context
import android.graphics.Bitmap
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.YoloObj
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.tensorflow.lite.Interpreter

class YoloDetector(
  context: Context,
  modelPath: String,
  private val side: Int = 640,
  private val confThresh: Float = 0.25f,
  private val iouThresh: Float = 0.45f,
) : Closeable {

  private val interpreter: Interpreter? = TfliteLoader.load(context, modelPath)
  private val outShape: IntArray = interpreter?.getOutputTensor(0)?.shape() ?: intArrayOf(1, 84, 8400)
  private val numClasses: Int = (outShape[1] - 4).coerceAtLeast(1)
  private val numAnchors: Int = outShape[outShape.size - 1]

  fun detect(bmp: Bitmap): List<YoloObj> {
    val itp = interpreter ?: return emptyList()
    val lb = BitmapPrep.letterbox(bmp, side)
    val input = BitmapPrep.toNhwcFloat01(lb.bitmap, side)
    val output = ByteBuffer.allocateDirect(outShape.fold(1) { a, b -> a * b } * 4)
      .order(ByteOrder.nativeOrder())
    try {
      itp.run(input, output)
    } catch (_: Throwable) {
      return emptyList()
    }
    output.rewind()
    val raw = FloatArray(outShape.fold(1) { a, b -> a * b }).also { output.asFloatBuffer().get(it) }

    // Flatten (1, 4+nc, A) → list of detections in input-space cxcywh
    val dets = mutableListOf<RawDet>()
    for (a in 0 until numAnchors) {
      val cx = raw[0 * numAnchors + a]
      val cy = raw[1 * numAnchors + a]
      val w = raw[2 * numAnchors + a]
      val h = raw[3 * numAnchors + a]
      var bestCls = -1
      var bestScore = 0f
      for (c in 0 until numClasses) {
        val s = raw[(4 + c) * numAnchors + a]
        if (s > bestScore) { bestScore = s; bestCls = c }
      }
      if (bestScore >= confThresh && bestCls >= 0) {
        dets += RawDet(cx, cy, w, h, bestScore, bestCls)
      }
    }

    // Project from letterbox space back to original-bitmap normalized coords
    val kept = nms(dets, iouThresh)
    val origW = bmp.width.toFloat(); val origH = bmp.height.toFloat()
    return kept.map { d ->
      val nx = (d.cx - d.w / 2 - lb.padX) / lb.scale / origW
      val ny = (d.cy - d.h / 2 - lb.padY) / lb.scale / origH
      val nw = d.w / lb.scale / origW
      val nh = d.h / lb.scale / origH
      YoloObj(
        cls = COCO_CLASSES.getOrElse(d.cls) { "cls_${d.cls}" },
        conf = d.conf,
        x = nx.coerceIn(0f, 1f),
        y = ny.coerceIn(0f, 1f),
        w = nw.coerceIn(0f, 1f),
        h = nh.coerceIn(0f, 1f),
      )
    }
  }

  override fun close() { try { interpreter?.close() } catch (_: Throwable) {} }

  private data class RawDet(val cx: Float, val cy: Float, val w: Float, val h: Float, val conf: Float, val cls: Int)

  private fun nms(dets: List<RawDet>, iouThresh: Float): List<RawDet> {
    val sorted = dets.sortedByDescending { it.conf }.toMutableList()
    val kept = mutableListOf<RawDet>()
    while (sorted.isNotEmpty()) {
      val best = sorted.removeAt(0)
      kept += best
      sorted.removeAll { other ->
        other.cls == best.cls && iou(best, other) > iouThresh
      }
    }
    return kept
  }

  private fun iou(a: RawDet, b: RawDet): Float {
    val ax1 = a.cx - a.w / 2; val ay1 = a.cy - a.h / 2; val ax2 = a.cx + a.w / 2; val ay2 = a.cy + a.h / 2
    val bx1 = b.cx - b.w / 2; val by1 = b.cy - b.h / 2; val bx2 = b.cx + b.w / 2; val by2 = b.cy + b.h / 2
    val ix1 = maxOf(ax1, bx1); val iy1 = maxOf(ay1, by1)
    val ix2 = minOf(ax2, bx2); val iy2 = minOf(ay2, by2)
    val iw = (ix2 - ix1).coerceAtLeast(0f); val ih = (iy2 - iy1).coerceAtLeast(0f)
    val inter = iw * ih
    val union = a.w * a.h + b.w * b.h - inter
    return if (union <= 0f) 0f else inter / union
  }

  companion object {
    /** COCO 80-class names — YOLO26 ships with the standard COCO label set. */
    val COCO_CLASSES = listOf(
      "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
      "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat",
      "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack",
      "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball",
      "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket",
      "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
      "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair",
      "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse",
      "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink", "refrigerator",
      "book", "clock", "vase", "scissors", "teddy bear", "hair drier", "toothbrush",
    )
  }
}
