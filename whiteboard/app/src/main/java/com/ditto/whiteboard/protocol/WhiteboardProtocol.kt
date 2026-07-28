package com.ditto.whiteboard.protocol

import com.ditto.whiteboard.domain.BOARD_ID
import com.ditto.whiteboard.domain.BoardOperation
import com.ditto.whiteboard.domain.BoardState
import com.ditto.whiteboard.domain.DrawingTool
import com.ditto.whiteboard.domain.LivePreview
import com.ditto.whiteboard.domain.LogicalPoint
import com.ditto.whiteboard.domain.OperationStamp
import com.ditto.whiteboard.domain.UserProfile
import com.ditto.whiteboard.protocol.proto.Envelope
import com.ditto.whiteboard.protocol.proto.Hello
import com.ditto.whiteboard.protocol.proto.LiveEnvelope
import com.ditto.whiteboard.protocol.proto.ReliableOperation
import com.google.protobuf.ByteString
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Wire-format version. Peers on a different version are rejected rather than misread. */
const val PROTOCOL_VERSION: Int = 2

/** Data Streams topic for lossy, low-latency live drawing previews (bound as Unreliable). */
const val LIVE_STREAM_NAME: String = "wb_live"

/** Data Streams topic for durable, ordered board operations and snapshots (bound as Reliable). */
const val STATE_STREAM_NAME: String = "wb_state"

sealed interface ProtocolDecodeResult<out T> {
  data class Compatible<T>(val value: T) : ProtocolDecodeResult<T>
  data class Incompatible(val version: Int) : ProtocolDecodeResult<Nothing>
  data class Invalid(val reason: String) : ProtocolDecodeResult<Nothing>
}

/**
 * Encodes and decodes everything that crosses the two Data Streams topics.
 *
 * Reliable `wb_state` traffic is wrapped in protobuf [Envelope]s (operations, `Hello` handshakes
 * carrying state vectors, and snapshot framing). Live `wb_live` previews use a compact,
 * delta-encoded [LiveEnvelope] so a 30 Hz stream of strokes stays small. Every decode is
 * version- and board-gated, returning a [ProtocolDecodeResult] instead of throwing.
 */
object WhiteboardProtocol {
  private val json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    classDiscriminator = "kind"
  }

  fun operationEnvelope(operation: BoardOperation): ByteArray =
    Envelope.newBuilder()
      .setProtocolVersion(PROTOCOL_VERSION)
      .setBoardId(BOARD_ID)
      .setSenderPeerKey(operation.id.senderPeerKey)
      .setSenderSequence(operation.id.senderSequence)
      .setLamport(operation.stamp.lamport)
      .setOperation(
        ReliableOperation.newBuilder()
          .setOperationJson(ByteString.copyFromUtf8(json.encodeToString(operation))),
      )
      .build()
      .toByteArray()

  fun helloEnvelope(
    peerKey: String,
    sequence: Long,
    lamport: Long,
    profile: UserProfile,
    ready: Boolean,
    state: BoardState,
  ): ByteArray = Envelope.newBuilder()
    .setProtocolVersion(PROTOCOL_VERSION)
    .setBoardId(BOARD_ID)
    .setSenderPeerKey(peerKey)
    .setSenderSequence(sequence)
    .setLamport(lamport)
    .setHello(
      Hello.newBuilder()
        .setProfileJson(ByteString.copyFromUtf8(json.encodeToString(profile)))
        .setReady(ready)
        .setStateDigest(ByteString.copyFrom(stateDigest(state)))
        .putAllHighWaterMarks(state.highWaterMarks),
    )
    .build()
    .toByteArray()

  fun decodeEnvelope(bytes: ByteArray): ProtocolDecodeResult<Envelope> = try {
    val envelope = Envelope.parseFrom(bytes)
    when {
      envelope.protocolVersion != PROTOCOL_VERSION ->
        ProtocolDecodeResult.Incompatible(envelope.protocolVersion)
      envelope.boardId != BOARD_ID -> ProtocolDecodeResult.Invalid("Unexpected board ${envelope.boardId}")
      else -> ProtocolDecodeResult.Compatible(envelope)
    }
  } catch (exception: Exception) {
    ProtocolDecodeResult.Invalid(exception.message ?: "Malformed protobuf envelope")
  }

  fun decodeOperation(envelope: Envelope): BoardOperation =
    json.decodeFromString(envelope.operation.operationJson.toStringUtf8())

  fun decodeProfile(bytes: ByteArray): UserProfile = json.decodeFromString(bytes.decodeToString())

  fun encodeLive(preview: LivePreview, sequence: Long): ByteArray {
    val deltas = deltaEncode(preview.points)
    val protoPreview = com.ditto.whiteboard.protocol.proto.LivePreview.newBuilder()
      .setTool(preview.tool.ordinal)
      .setColorArgb(preview.colorArgb)
      .addAllCoordinateDeltas(deltas)
      .build()
    return LiveEnvelope.newBuilder()
      .setProtocolVersion(PROTOCOL_VERSION)
      .setBoardId(BOARD_ID)
      .setSenderPeerKey(preview.peerKey)
      .setSenderSequence(sequence)
      .setPreview(protoPreview)
      .build()
      .toByteArray()
  }

  fun decodeLive(bytes: ByteArray, nowMillis: Long): ProtocolDecodeResult<LivePreview> = try {
    val envelope = LiveEnvelope.parseFrom(bytes)
    when {
      envelope.protocolVersion != PROTOCOL_VERSION -> ProtocolDecodeResult.Incompatible(envelope.protocolVersion)
      envelope.boardId != BOARD_ID -> ProtocolDecodeResult.Invalid("Unexpected board ${envelope.boardId}")
      envelope.preview.tool !in DrawingTool.entries.indices -> ProtocolDecodeResult.Invalid("Unknown drawing tool")
      else -> ProtocolDecodeResult.Compatible(
        LivePreview(
          peerKey = envelope.senderPeerKey,
          tool = DrawingTool.entries[envelope.preview.tool],
          colorArgb = envelope.preview.colorArgb,
          points = deltaDecode(envelope.preview.coordinateDeltasList),
          expiresAtMillis = nowMillis + 2_000,
        ),
      )
    }
  } catch (exception: Exception) {
    ProtocolDecodeResult.Invalid(exception.message ?: "Malformed live envelope")
  }

  fun stateDigest(state: BoardState): ByteArray = sha256(snapshotBytes(state))

  fun snapshotBytes(state: BoardState): ByteArray =
    json.encodeToString(SnapshotDocument(state.operations.values.sortedBy { it.stamp })).encodeToByteArray()

  fun snapshotOperations(bytes: ByteArray): List<BoardOperation> =
    json.decodeFromString<SnapshotDocument>(bytes.decodeToString()).operations

  fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

  internal fun deltaEncode(points: List<LogicalPoint>): List<Int> {
    var previousX = 0
    var previousY = 0
    return buildList(points.size * 2) {
      points.forEach { point ->
        add(point.x - previousX)
        add(point.y - previousY)
        previousX = point.x
        previousY = point.y
      }
    }
  }

  internal fun deltaDecode(values: List<Int>): List<LogicalPoint> {
    if (values.size % 2 != 0) return emptyList()
    var x = 0
    var y = 0
    return values.chunked(2).map { delta ->
      x += delta[0]
      y += delta[1]
      LogicalPoint(x, y).clamped()
    }
  }
}

@Serializable
private data class SnapshotDocument(val operations: List<BoardOperation>)

enum class VectorRelation { LocalDominates, RemoteDominates, Equal, Concurrent }

fun compareStateVectors(local: Map<String, Long>, remote: Map<String, Long>): VectorRelation {
  val senders = local.keys + remote.keys
  val localAtLeast = senders.all { (local[it] ?: 0) >= (remote[it] ?: 0) }
  val remoteAtLeast = senders.all { (remote[it] ?: 0) >= (local[it] ?: 0) }
  return when {
    localAtLeast && remoteAtLeast -> VectorRelation.Equal
    localAtLeast -> VectorRelation.LocalDominates
    remoteAtLeast -> VectorRelation.RemoteDominates
    else -> VectorRelation.Concurrent
  }
}

fun Envelope.operationStamp(): OperationStamp =
  OperationStamp(lamport = lamport, peerKey = senderPeerKey, senderSequence = senderSequence)
