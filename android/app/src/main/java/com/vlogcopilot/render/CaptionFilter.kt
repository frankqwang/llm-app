/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Build the ffmpeg drawtext filter chain for a caption. Mirrors the
 * pc-pilot/step5_render.py xiaohongshu-style pill: white rounded box, black
 * bold text, slide-up entrance during first 0.4s, hold, then fade out 0.3s
 * before shot end.
 */
package com.vlogcopilot.render

object CaptionFilter {

  /**
   * @param text caption text (ffmpeg-escaped externally)
   * @param shotDurationSec total duration of the shot
   * @param fontPath absolute path to a TTF/OTF, e.g. <filesDir>/fonts/SourceHanSans-Bold.otf
   * @return the drawtext filter string, ready to be appended after a comma into a filter_complex
   */
  fun build(text: String, shotDurationSec: Float, fontPath: String?): String {
    // No font, no drawtext. ffmpeg's `drawtext` without `fontfile=` aborts the
    // whole filter graph (exit 1) on Android because ffmpeg-kit ships without
    // any default font. Until we bundle a CJK font, render the shot silent on
    // captions rather than fail the entire MP4. Better degraded video than no
    // video at all.
    if (text.isBlank() || fontPath.isNullOrEmpty()) return ""
    val escaped = escape(text)
    val font = "fontfile='${fontPath.replace("'", "\\'")}'"
    val enterEnd = 0.4f
    val exitStart = (shotDurationSec - 0.3f).coerceAtLeast(enterEnd + 0.1f)
    return buildString {
      append("drawtext=$font:")
      append("text='$escaped'")
      append(":fontcolor=black")
      append(":fontsize=h*0.040")
      append(":box=1:boxcolor=white@0.88:boxborderw=18")
      append(":x=(w-text_w)/2")
      append(":y='if(lt(t,$enterEnd),h-(h-h*0.80)*(t/$enterEnd),h*0.80)'")
      append(":alpha='if(lt(t,$exitStart),1,max(0,1-(t-$exitStart)/0.3))'")
    }
  }

  private fun escape(s: String): String =
    s.replace("\\", "\\\\")
     .replace(":", "\\:")
     .replace("'", "’")              // ASCII apostrophe → typographic, sidesteps the inner-quote bug
     .replace("%", "\\%")            // drawtext treats %{...} as expansion
     .replace(",", "\\,")            // commas separate filters in filter_complex; must be escaped
     .replace("\n", " ")
}
