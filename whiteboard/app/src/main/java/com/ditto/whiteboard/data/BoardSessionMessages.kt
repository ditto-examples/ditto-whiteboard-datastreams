package com.ditto.whiteboard.data

/**
 * Resource-resolved copy shown by [BoardSession].
 *
 * Keeping Android resource lookup at the composition root lets the session remain straightforward
 * to unit test while ensuring production-facing text comes from `strings.xml`.
 */
data class BoardSessionMessages(
  val syncFinishing: String,
  val sessionStarting: String,
  val textCannotBeBlank: String,
  val operationClockExhausted: String,
  val logicalTimeLimit: String,
  val editOutsideSafetyLimits: String,
  val terminalResourceLimit: String,
  val peerLogicalTimeLimit: String,
  val visibleObjectLimit: String,
  val clockReservationFailed: String,
)
