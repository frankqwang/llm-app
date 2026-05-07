/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Build the ffmpeg drawtext filter chain for a caption. Mirrors the
 * pc-pilot/step5_render.py xiaohongshu-style pill: white rounded box, black
 * bold text, slide-up entrance during first 0.4s, hold, then fade out 0.3s
 * before shot end.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.render

object CaptionFilter {

  /**
   * @param text caption text (ffmpeg-escaped externally)
   * @param shotDurationSec total duration of the shot
   * @param fontPath absolute path to a TTF/OTF, e.g. <filesDir>/fonts/SourceHanSans-Bold.otf
   * @return the drawtext filter string, ready to be appended after a comma into a filter_complex
   */
  fun build(text: String, shotDurationSec: Float, fontPath: String?): String {
    if (text.isBlank()) return ""
    val escaped = escape(text)
    val font = fontPath?.let { "fontfile='${it.replace("'", "\\'")}'" }.orEmpty()
    val enterEnd = 0.4f
    val exitStart = (shotDurationSec - 0.3f).coerceAtLeast(enterEnd + 0.1f)
    // box=1: filled rounded background; we approximate via boxborderw + boxcolor.
    return buildString {
      append("drawtext=$font")
      if (font.isNotEmpty()) append(":")
      append("text='$escaped'")
      append(":fontcolor=black")
      append(":fontsize=h*0.046")
      append(":box=1:boxcolor=white@0.92:boxborderw=24")
      append(":x=(w-text_w)/2")
      // Slide up from bottom: at t=0 y=h; at t=enterEnd y=h*0.78
      append(":y='if(lt(t,$enterEnd),h-(h-h*0.78)*(t/$enterEnd),h*0.78)'")
      append(":alpha='if(lt(t,$exitStart),1,max(0,1-(t-$exitStart)/0.3))'")
    }
  }

  private fun escape(s: String): String =
    s.replace("\\", "\\\\")
     .replace(":", "\\:")
     .replace("'", "’")
     .replace(",", ",")
     .replace("\n", " ")
}
