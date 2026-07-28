package com.ditto.whiteboard.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val BOARD_ID: String = "demo"
const val BOARD_WIDTH: Int = 3840
const val BOARD_HEIGHT: Int = 2160
const val DEFAULT_STROKE_WIDTH: Int = 10
const val DEFAULT_ERASER_RADIUS: Int = 40
const val DEFAULT_TEXT_SIZE: Int = 48

@Serializable
data class LogicalPoint(val x: Int, val y: Int) {
  fun clamped(): LogicalPoint = copy(x = x.coerceIn(0, BOARD_WIDTH), y = y.coerceIn(0, BOARD_HEIGHT))
}

@Serializable
data class OperationId(val senderPeerKey: String, val senderSequence: Long)

@Serializable
data class ObjectId(val origin: OperationId, val fragment: Int = 0)

@Serializable
data class OperationStamp(
  val lamport: Long,
  val peerKey: String,
  val senderSequence: Long,
) : Comparable<OperationStamp> {
  override fun compareTo(other: OperationStamp): Int =
    compareValuesBy(this, other, OperationStamp::lamport, OperationStamp::peerKey, OperationStamp::senderSequence)
}

@Serializable
data class UserProfile(
  val peerKey: String,
  val displayName: String,
  val colorArgb: Int,
) {
  init {
    require(displayName.length in 1..24) { "Display name must be 1–24 characters" }
  }
}

@Serializable
sealed interface BoardObject {
  val id: ObjectId
  val stamp: OperationStamp
  val colorArgb: Int

  @Serializable
  @SerialName("freehand")
  data class Freehand(
    override val id: ObjectId,
    override val stamp: OperationStamp,
    override val colorArgb: Int,
    val width: Int = DEFAULT_STROKE_WIDTH,
    val points: List<LogicalPoint>,
  ) : BoardObject

  @Serializable
  @SerialName("line")
  data class Line(
    override val id: ObjectId,
    override val stamp: OperationStamp,
    override val colorArgb: Int,
    val width: Int = DEFAULT_STROKE_WIDTH,
    val start: LogicalPoint,
    val end: LogicalPoint,
  ) : BoardObject

  @Serializable
  @SerialName("rectangle")
  data class Rectangle(
    override val id: ObjectId,
    override val stamp: OperationStamp,
    override val colorArgb: Int,
    val width: Int = DEFAULT_STROKE_WIDTH,
    val start: LogicalPoint,
    val end: LogicalPoint,
  ) : BoardObject

  @Serializable
  @SerialName("ellipse")
  data class Ellipse(
    override val id: ObjectId,
    override val stamp: OperationStamp,
    override val colorArgb: Int,
    val width: Int = DEFAULT_STROKE_WIDTH,
    val start: LogicalPoint,
    val end: LogicalPoint,
  ) : BoardObject

  @Serializable
  @SerialName("text")
  data class Text(
    override val id: ObjectId,
    override val stamp: OperationStamp,
    override val colorArgb: Int,
    val anchor: LogicalPoint,
    val text: String,
    val size: Int = DEFAULT_TEXT_SIZE,
  ) : BoardObject
}

@Serializable
sealed interface BoardOperation {
  val id: OperationId
  val stamp: OperationStamp

  @Serializable
  @SerialName("commit")
  data class Commit(
    override val id: OperationId,
    override val stamp: OperationStamp,
    val boardObject: BoardObject,
  ) : BoardOperation

  @Serializable
  @SerialName("erase")
  data class Erase(
    override val id: OperationId,
    override val stamp: OperationStamp,
    val path: List<LogicalPoint>,
    val radius: Int = DEFAULT_ERASER_RADIUS,
  ) : BoardOperation

  @Serializable
  @SerialName("clear")
  data class Clear(
    override val id: OperationId,
    override val stamp: OperationStamp,
  ) : BoardOperation

  @Serializable
  @SerialName("profile")
  data class ProfileUpdate(
    override val id: OperationId,
    override val stamp: OperationStamp,
    val profile: UserProfile,
  ) : BoardOperation
}

@Serializable
data class BoardState(
  val objects: Map<ObjectId, BoardObject> = emptyMap(),
  val erasures: List<BoardOperation.Erase> = emptyList(),
  val clearWatermark: OperationStamp? = null,
  val profiles: Map<String, UserProfile> = emptyMap(),
  val highWaterMarks: Map<String, Long> = emptyMap(),
  val operations: Map<OperationId, BoardOperation> = emptyMap(),
)

enum class DrawingTool { Pen, Line, Rectangle, Ellipse, Text, Eraser }

data class LivePreview(
  val peerKey: String,
  val tool: DrawingTool,
  val colorArgb: Int,
  val points: List<LogicalPoint>,
  val expiresAtMillis: Long,
)
