package com.ditto.whiteboard.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf

const val BOARD_ID: String = "demo"
const val BOARD_WIDTH: Int = 3840
const val BOARD_HEIGHT: Int = 2160
const val DEFAULT_STROKE_WIDTH: Int = 10
const val DEFAULT_ERASER_RADIUS: Int = 40
const val DEFAULT_TEXT_SIZE: Int = 48
const val MAX_OPERATION_COUNTER: Long = 1L shl 60
const val MAX_RENDERED_BOARD_OBJECTS: Int = 512

@Serializable
data class LogicalPoint(val x: Int, val y: Int) {
  fun clamped(): LogicalPoint = copy(x = x.coerceIn(0, BOARD_WIDTH), y = y.coerceIn(0, BOARD_HEIGHT))
}

@Serializable
data class OperationId(val senderPeerKey: String, val senderSequence: Long)

@Serializable
data class ObjectId(
  val origin: OperationId,
  /** Fixed-size SHA-256 lineage for an eraser-derived fragment; blank for the committed root. */
  val fragmentDigest: String = "",
)

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
    require(displayName.none(Char::isISOControl)) { "Display name cannot contain control characters" }
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
    val gestureId: String = "${id.senderPeerKey}:${id.senderSequence}",
  ) : BoardOperation

  @Serializable
  @SerialName("erase")
  data class Erase(
    override val id: OperationId,
    override val stamp: OperationStamp,
    val path: List<LogicalPoint>,
    val radius: Int = DEFAULT_ERASER_RADIUS,
    val gestureId: String = "${id.senderPeerKey}:${id.senderSequence}",
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

data class BoardState(
  val objects: PersistentMap<ObjectId, BoardObject> = persistentMapOf(),
  val erasures: PersistentList<BoardOperation.Erase> = persistentListOf(),
  val clearWatermark: OperationStamp? = null,
  val profiles: PersistentMap<String, UserProfile> = persistentMapOf(),
  val highWaterMarks: PersistentMap<String, Long> = persistentMapOf(),
  val operations: PersistentMap<OperationId, BoardOperation> = persistentMapOf(),
  /** Cached maximum stamp, so the common in-order reducer path does not rescan the full log. */
  val latestStamp: OperationStamp? = null,
  /**
   * True once the post-clear fold has touched the render cap. Out-of-order commits can only use
   * the incremental path while this is false; after the cap has influenced admission order, a full
   * fold is required to preserve deterministic convergence.
   */
  val renderCapacityReachedSinceClear: Boolean = false,
)

enum class DrawingTool { Pen, Line, Rectangle, Ellipse, Text, Eraser }

data class LivePreview(
  val peerKey: String,
  val tool: DrawingTool,
  val colorArgb: Int,
  val points: List<LogicalPoint>,
  val expiresAtMillis: Long,
  val gestureId: String = "",
  val frameSequence: Long = 0L,
  val senderSessionId: String = "",
)

private val canonicalOperationJson = Json {
  encodeDefaults = true
  classDiscriminator = "kind"
}

/**
 * Resolves an equivocated operation id identically on every peer. The lexicographically smaller
 * canonical JSON value wins, so exchanging merge-only snapshots heals peers that initially saw
 * different content for the same sender sequence instead of trapping them in a reject/retry loop.
 */
internal fun canonicalOperation(first: BoardOperation, second: BoardOperation): BoardOperation {
  require(first.id == second.id)
  if (first == second) return first
  val firstKey = canonicalOperationJson.encodeToString(first)
  val secondKey = canonicalOperationJson.encodeToString(second)
  return if (firstKey <= secondKey) first else second
}
