package com.ditto.whiteboard.ui

import kotlin.math.pow
import org.junit.Assert.assertTrue
import org.junit.Test

class WhiteboardPaletteTest {
  @Test
  fun everyDrawingColorContrastsAgainstTheFixedPaper() {
    val paper = 0xFFFFFEFC.toInt()
    WHITEBOARD_COLORS.forEachIndexed { index, color ->
      assertTrue(
        "Palette color $index must remain visible on the board paper",
        contrastRatio(color, paper) >= 3.0,
      )
    }
  }

  private fun contrastRatio(first: Int, second: Int): Double {
    val lighter = maxOf(luminance(first), luminance(second))
    val darker = minOf(luminance(first), luminance(second))
    return (lighter + 0.05) / (darker + 0.05)
  }

  private fun luminance(color: Int): Double {
    fun channel(shift: Int): Double {
      val srgb = ((color ushr shift) and 0xFF) / 255.0
      return if (srgb <= 0.04045) srgb / 12.92 else ((srgb + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
  }
}
