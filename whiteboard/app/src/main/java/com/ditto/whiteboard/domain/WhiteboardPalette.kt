package com.ditto.whiteboard.domain

/**
 * The protocol-approved drawing palette.
 *
 * Keeping this in the domain layer gives persistence, local editing, and wire validation one
 * source of truth. Every entry is opaque and has at least 3:1 contrast against the fixed board
 * paper.
 */
val WHITEBOARD_PALETTE: List<Int> = listOf(
  0xFF1D1B20.toInt(),
  0xFF0057B8.toInt(),
  0xFF007A3D.toInt(),
  0xFFC62828.toInt(),
  0xFF7B1FA2.toInt(),
  0xFFA94700.toInt(),
  0xFF00838F.toInt(),
  0xFFAD1457.toInt(),
)

val DEFAULT_WHITEBOARD_COLOR: Int = WHITEBOARD_PALETTE[1]

fun isApprovedWhiteboardColor(colorArgb: Int): Boolean = colorArgb in WHITEBOARD_PALETTE

/** Maps colors saved by older versions (including invisible white) to a visible default. */
fun normalizeWhiteboardColor(colorArgb: Int): Int =
  colorArgb.takeIf(::isApprovedWhiteboardColor) ?: DEFAULT_WHITEBOARD_COLOR
