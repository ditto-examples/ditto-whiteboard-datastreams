package com.ditto.whiteboard.protocol

import com.ditto.whiteboard.domain.BOARD_ID
import com.ditto.whiteboard.domain.BOARD_HEIGHT
import com.ditto.whiteboard.domain.BOARD_WIDTH
import com.ditto.whiteboard.domain.BoardOperation
import com.ditto.whiteboard.domain.BoardObject
import com.ditto.whiteboard.domain.BoardState
import com.ditto.whiteboard.domain.BoardTextFont
import com.ditto.whiteboard.domain.DrawingTool
import com.ditto.whiteboard.domain.LivePreview
import com.ditto.whiteboard.domain.LogicalPoint
import com.ditto.whiteboard.domain.MAX_OPERATION_COUNTER
import com.ditto.whiteboard.domain.OperationStamp
import com.ditto.whiteboard.domain.UserProfile
import com.ditto.whiteboard.domain.isApprovedWhiteboardColor
import com.ditto.whiteboard.protocol.proto.Envelope
import com.ditto.whiteboard.protocol.proto.Hello
import com.ditto.whiteboard.protocol.proto.LiveEnvelope
import com.ditto.whiteboard.protocol.proto.ReliableOperation
import com.google.protobuf.ByteString
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Wire-format version. Peers on a different version are rejected rather than misread.
 *
 * 5 adds `SnapshotAck.busy`, letting a receiver say "my hydration slot is occupied" instead of
 * "your snapshot failed". A v4 peer reads a busy refusal as a plain rejection and falls back to its
 * error retry ladder, so the versions are deliberately not interoperable.
 */
const val PROTOCOL_VERSION: Int = 5

/** Data Streams topic for lossy, low-latency live drawing previews (bound as Unreliable). */
const val LIVE_STREAM_NAME: String = "wb_live"

/** Data Streams topic for durable, ordered board operations and snapshots (bound as Reliable). */
const val STATE_STREAM_NAME: String = "wb_state"

internal const val MAX_RELIABLE_FRAME_BYTES: Int = 1024 * 1024
internal const val MAX_LIVE_FRAME_BYTES: Int = 64 * 1024
internal const val MAX_SNAPSHOT_OPERATIONS: Int = 10_000
internal const val MAX_ERASE_OPERATIONS: Int = 128
internal const val MAX_OPERATION_POINTS: Int = 128
internal const val MAX_ERASER_POINTS: Int = 32
internal const val MAX_LIVE_POINTS: Int = 64
internal const val MAX_PEER_KEY_LENGTH: Int = 512
internal const val MAX_PROTOCOL_COUNTER: Long = MAX_OPERATION_COUNTER
internal const val MAX_STATE_VECTOR_PEERS: Int = 64
/** Terminal logical-time ceiling for one bounded ephemeral board session. */
internal const val MAX_SESSION_LAMPORT: Long =
  MAX_PROTOCOL_COUNTER - MAX_SNAPSHOT_OPERATIONS - 1L
private const val MAX_OPERATION_JSON_BYTES: Int = 512 * 1024
private const val MAX_PROFILE_JSON_BYTES: Int = 4 * 1024
private const val MAX_STROKE_WIDTH: Int = 256
private const val MAX_ERASER_RADIUS: Int = 512
private const val MAX_TEXT_LENGTH: Int = 200
private const val MAX_GESTURE_ID_LENGTH: Int = 640
private const val SNAPSHOT_HEADER_BYTES: Long = 8
private const val SNAPSHOT_MAGIC: Int = 0x57425334 // "WBS4"
private const val STATE_DIGEST_MAGIC: Int = 0x57424434 // "WBD4"

sealed interface ProtocolDecodeResult<out T> {
  data class Compatible<T>(val value: T) : ProtocolDecodeResult<T>
  data class Incompatible(val version: Int) : ProtocolDecodeResult<Nothing>
  data class Invalid(val reason: String) : ProtocolDecodeResult<Nothing>
}

/**
 * Encodes and decodes everything that crosses the two Data Streams topics.
 *
 * Reliable `wb_state` traffic is wrapped in protobuf [Envelope]s (operations, `Hello` handshakes
 * carrying state digests, and snapshot framing). Live `wb_live` previews use a compact,
 * delta-encoded [LiveEnvelope] so a 30 Hz stream of strokes stays small. Every decode is
 * version- and board-gated, returning a [ProtocolDecodeResult] instead of throwing.
 */
object WhiteboardProtocol {
  private val json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    classDiscriminator = "kind"
  }

  fun operationEnvelope(operation: BoardOperation): ByteArray {
    requireValidOperation(operation)
    return Envelope.newBuilder()
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
      .also { require(it.size <= MAX_RELIABLE_FRAME_BYTES) { "Reliable frame exceeds protocol limit" } }
  }

  fun helloEnvelope(
    peerKey: String,
    sequence: Long,
    lamport: Long,
    profile: UserProfile,
    state: BoardState,
    stateDigest: ByteArray = stateDigest(state),
  ): ByteArray = Envelope.newBuilder()
    .setProtocolVersion(PROTOCOL_VERSION)
    .setBoardId(BOARD_ID)
    .setSenderPeerKey(peerKey)
    .setSenderSequence(sequence)
    .setLamport(lamport)
    .setHello(
      Hello.newBuilder()
        .setProfileJson(ByteString.copyFromUtf8(json.encodeToString(profile)))
        .setStateDigest(ByteString.copyFrom(stateDigest))
    )
    .build()
    .toByteArray()

  fun decodeEnvelope(
    bytes: ByteArray,
    expectedPeerKey: String? = null,
  ): ProtocolDecodeResult<Envelope> = try {
    if (bytes.size > MAX_RELIABLE_FRAME_BYTES) {
      return ProtocolDecodeResult.Invalid("Reliable frame exceeds protocol limit")
    }
    val envelope = Envelope.parseFrom(bytes)
    when {
      envelope.protocolVersion != PROTOCOL_VERSION ->
        ProtocolDecodeResult.Incompatible(envelope.protocolVersion)
      envelope.boardId != BOARD_ID -> ProtocolDecodeResult.Invalid("Unexpected board id")
      envelope.senderPeerKey.isBlank() || envelope.senderPeerKey.length > MAX_PEER_KEY_LENGTH ->
        ProtocolDecodeResult.Invalid("Invalid sender peer key")
      expectedPeerKey != null && envelope.senderPeerKey != expectedPeerKey ->
        ProtocolDecodeResult.Invalid("Envelope sender does not match the connected peer")
      envelope.senderSequence !in 1..MAX_PROTOCOL_COUNTER ->
        ProtocolDecodeResult.Invalid("Invalid sender sequence")
      envelope.lamport !in 0..MAX_PROTOCOL_COUNTER ->
        ProtocolDecodeResult.Invalid("Invalid Lamport stamp")
      envelope.hasHello() && envelope.lamport > MAX_SESSION_LAMPORT ->
        ProtocolDecodeResult.Invalid("Hello Lamport stamp exceeds the session range")
      envelope.hasHello() && envelope.hello.stateDigest.size() != SHA_256_BYTE_COUNT ->
        ProtocolDecodeResult.Invalid("Invalid state digest")
      else -> ProtocolDecodeResult.Compatible(envelope)
    }
  } catch (exception: Exception) {
    ProtocolDecodeResult.Invalid(exception.message ?: "Malformed protobuf envelope")
  }

  fun decodeOperation(envelope: Envelope, expectedPeerKey: String? = null): BoardOperation {
    require(envelope.operation.operationJson.size() <= MAX_OPERATION_JSON_BYTES) {
      "Reliable operation exceeds protocol limit"
    }
    val operation = json.decodeFromString<BoardOperation>(envelope.operation.operationJson.toStringUtf8())
    requireValidOperation(operation, expectedPeerKey)
    require(envelope.senderPeerKey == operation.id.senderPeerKey) { "Envelope and operation sender differ" }
    require(envelope.senderSequence == operation.id.senderSequence) { "Envelope and operation sequence differ" }
    require(envelope.lamport == operation.stamp.lamport) { "Envelope and operation Lamport stamp differ" }
    return operation
  }

  fun decodeProfile(bytes: ByteArray, expectedPeerKey: String? = null): UserProfile {
    require(bytes.size <= MAX_PROFILE_JSON_BYTES) { "Profile exceeds protocol limit" }
    return json.decodeFromString<UserProfile>(bytes.decodeToString()).also { profile ->
      require(profile.peerKey.isNotBlank() && profile.peerKey.length <= MAX_PEER_KEY_LENGTH)
      require(profile.displayName.length in 1..24 && profile.displayName.none(Char::isISOControl))
      require(isApprovedWhiteboardColor(profile.colorArgb)) { "Profile color is not approved" }
      require(expectedPeerKey == null || profile.peerKey == expectedPeerKey) {
        "Profile owner does not match the connected peer"
      }
    }
  }

  fun encodeLive(preview: LivePreview, sequence: Long, senderSessionId: String): ByteArray {
    require(sequence in 1..MAX_PROTOCOL_COUNTER)
    require(preview.tool != DrawingTool.Hand) { "Hand is a local navigation tool" }
    require(senderSessionId.isNotBlank() && senderSessionId.length <= MAX_GESTURE_ID_LENGTH)
    require(preview.gestureId.isNotBlank() && preview.gestureId.length <= MAX_GESTURE_ID_LENGTH)
    require(isApprovedWhiteboardColor(preview.colorArgb)) { "Live color is not approved" }
    require(preview.points.size in 1..MAX_LIVE_POINTS)
    preview.points.forEach {
      require(it.x in 0..BOARD_WIDTH && it.y in 0..BOARD_HEIGHT)
    }
    val deltas = deltaEncode(preview.points)
    val protoPreview = com.ditto.whiteboard.protocol.proto.LivePreview.newBuilder()
      .setTool(preview.tool.ordinal)
      .setColorArgb(preview.colorArgb)
      .addAllCoordinateDeltas(deltas)
      .setGestureId(preview.gestureId)
      .build()
    return LiveEnvelope.newBuilder()
      .setProtocolVersion(PROTOCOL_VERSION)
      .setBoardId(BOARD_ID)
      .setSenderPeerKey(preview.peerKey)
      .setSenderSequence(sequence)
      .setSenderSessionId(senderSessionId)
      .setPreview(protoPreview)
      .build()
      .toByteArray()
  }

  fun decodeLive(
    bytes: ByteArray,
    nowMillis: Long,
    expectedPeerKey: String? = null,
  ): ProtocolDecodeResult<LivePreview> = try {
    if (bytes.size > MAX_LIVE_FRAME_BYTES) {
      return ProtocolDecodeResult.Invalid("Live frame exceeds protocol limit")
    }
    val envelope = LiveEnvelope.parseFrom(bytes)
    when {
      envelope.protocolVersion != PROTOCOL_VERSION -> ProtocolDecodeResult.Incompatible(envelope.protocolVersion)
      envelope.boardId != BOARD_ID -> ProtocolDecodeResult.Invalid("Unexpected board ${envelope.boardId}")
      envelope.senderPeerKey.isBlank() || envelope.senderPeerKey.length > MAX_PEER_KEY_LENGTH ->
        ProtocolDecodeResult.Invalid("Invalid sender peer key")
      envelope.senderSequence !in 1..MAX_PROTOCOL_COUNTER ->
        ProtocolDecodeResult.Invalid("Invalid live sequence")
      envelope.senderSessionId.isBlank() ||
        envelope.senderSessionId.length > MAX_GESTURE_ID_LENGTH ->
        ProtocolDecodeResult.Invalid("Invalid live sender session")
      expectedPeerKey != null && envelope.senderPeerKey != expectedPeerKey ->
        ProtocolDecodeResult.Invalid("Live sender does not match the connected peer")
      envelope.preview.gestureId.isBlank() ||
        envelope.preview.gestureId.length > MAX_GESTURE_ID_LENGTH ->
        ProtocolDecodeResult.Invalid("Invalid live gesture id")
      envelope.preview.tool !in DrawingTool.entries.indices -> ProtocolDecodeResult.Invalid("Unknown drawing tool")
      DrawingTool.entries[envelope.preview.tool] == DrawingTool.Hand ->
        ProtocolDecodeResult.Invalid("Hand is a local navigation tool")
      !isApprovedWhiteboardColor(envelope.preview.colorArgb) ->
        ProtocolDecodeResult.Invalid("Live color is not approved")
      envelope.preview.coordinateDeltasCount !in 2..(MAX_LIVE_POINTS * 2) ||
        envelope.preview.coordinateDeltasCount % 2 != 0 ->
        ProtocolDecodeResult.Invalid("Invalid live point count")
      else -> {
        val points = deltaDecode(envelope.preview.coordinateDeltasList)
        if (points.any { it.x !in 0..BOARD_WIDTH || it.y !in 0..BOARD_HEIGHT }) {
          ProtocolDecodeResult.Invalid("Live point is outside the board")
        } else ProtocolDecodeResult.Compatible(
          LivePreview(
          peerKey = envelope.senderPeerKey,
          tool = DrawingTool.entries[envelope.preview.tool],
          colorArgb = envelope.preview.colorArgb,
          points = points,
          expiresAtMillis = nowMillis + 2_000,
          gestureId = envelope.preview.gestureId,
          frameSequence = envelope.senderSequence,
          senderSessionId = envelope.senderSessionId,
          ),
        )
      }
    }
  } catch (exception: Exception) {
    ProtocolDecodeResult.Invalid(exception.message ?: "Malformed live envelope")
  }

  /**
   * Canonical operation-set digest used by Hello reconciliation.
   *
   * Snapshot byte integrity has its own SHA-256 in SnapshotBegin. Keeping the reconciliation
   * digest independent lets the transport update a 32-byte XOR accumulator per accepted operation
   * instead of repeatedly serializing a multi-megabyte snapshot on the receive hot path.
   */
  fun stateDigest(state: BoardState): ByteArray {
    return operationSetDigest(state.operations.values)
  }

  internal fun operationSetDigest(operations: Collection<BoardOperation>): ByteArray {
    val accumulator = ByteArray(SHA_256_BYTE_COUNT)
    operations.forEach { operation ->
      xorDigestInto(accumulator, operationStateDigest(operation))
    }
    return stateDigestFromAccumulator(operations.size, accumulator)
  }

  fun snapshotBytes(state: BoardState): ByteArray {
    require(state.operations.size <= MAX_SNAPSHOT_OPERATIONS) { "Snapshot contains too many operations" }
    require(state.operations.values.count { it is BoardOperation.Erase } <= MAX_ERASE_OPERATIONS) {
      "Snapshot contains too many erase operations"
    }
    val ordered = state.operations.values.sortedBy { it.stamp }
    val operationBytes = ordered.sumOf { operationJsonByteCount(it).toLong() }
    val byteCount = snapshotDocumentByteCount(operationBytes, ordered.size)
    require(byteCount <= MAX_TRANSFER_BYTES && byteCount <= Int.MAX_VALUE) {
      "Snapshot exceeds protocol byte limit"
    }
    return ByteBuffer.allocate(byteCount.toInt()).apply {
      putInt(SNAPSHOT_MAGIC)
      putInt(ordered.size)
      ordered.forEach { operation ->
        val bytes = json.encodeToString(operation).encodeToByteArray()
        putInt(bytes.size)
        put(bytes)
      }
    }.array()
  }

  /**
   * Parses a merge-only snapshot document.
   *
   * **Trust boundary — snapshot contents are not origin-authenticated.** Direct frames are bound to
   * their sender: [decodeEnvelope], [decodeOperation] and [decodeProfile] all reject a payload whose
   * author is not the connected peer. A snapshot cannot use that check, because relaying a third
   * party's operations is the entire point of late-join catch-up. Each operation is therefore
   * validated for *shape* ([requireValidOperation]) but not for *origin*, which means a peer can:
   *
   * - attribute strokes, an [BoardOperation.Clear], or a [BoardOperation.ProfileUpdate] to any
   *   other peer key;
   * - pre-seed another peer's future `senderSequence` values (they come from predictable persisted
   *   blocks) with content that sorts low under [com.ditto.whiteboard.domain.canonicalOperation],
   *   permanently censoring that peer's real operations at those sequences.
   *
   * Closing this requires per-operation signatures over a peer-key-bound identity and a protocol
   * version bump; the board is an ephemeral, same-room demo surface, so it is a documented
   * limitation rather than a mitigated one. Do not model this board as an authenticated log.
   */
  fun snapshotOperations(bytes: ByteArray): List<BoardOperation> {
    require(bytes.size.toLong() <= MAX_TRANSFER_BYTES) { "Snapshot exceeds protocol byte limit" }
    require(bytes.size >= SNAPSHOT_HEADER_BYTES) { "Snapshot header is truncated" }
    val buffer = ByteBuffer.wrap(bytes)
    require(buffer.int == SNAPSHOT_MAGIC) { "Snapshot format is not supported" }
    val count = buffer.int
    require(count in 0..MAX_SNAPSHOT_OPERATIONS) { "Snapshot contains too many operations" }
    val operations = ArrayList<BoardOperation>(count)
    val operationIds = HashSet<com.ditto.whiteboard.domain.OperationId>(count)
    val authors = HashSet<String>()
    var eraseCount = 0
    repeat(count) {
      require(buffer.remaining() >= Int.SIZE_BYTES) { "Snapshot operation header is truncated" }
      val byteCount = buffer.int
      require(byteCount in 1..MAX_OPERATION_JSON_BYTES && byteCount <= buffer.remaining()) {
        "Snapshot operation length is invalid"
      }
      val operationBytes = ByteArray(byteCount)
      buffer.get(operationBytes)
      val operation = json.decodeFromString<BoardOperation>(operationBytes.decodeToString())
      requireValidOperation(operation)
      require(operationIds.add(operation.id)) { "Snapshot contains duplicate operation ids" }
      if (operation is BoardOperation.Erase) {
        eraseCount += 1
        require(eraseCount <= MAX_ERASE_OPERATIONS) { "Snapshot contains too many erase operations" }
      }
      authors += operation.id.senderPeerKey
      require(authors.size <= MAX_STATE_VECTOR_PEERS) { "Snapshot contains too many authors" }
      operations += operation
    }
    require(!buffer.hasRemaining()) { "Snapshot contains trailing bytes" }
    return operations
  }

  /** Exact UTF-8 JSON contribution of one operation inside a snapshot document. */
  internal fun operationJsonByteCount(operation: BoardOperation): Int =
    json.encodeToString(operation).encodeToByteArray().size

  internal fun operationStateDigest(operation: BoardOperation): ByteArray =
    sha256(json.encodeToString(operation).encodeToByteArray())

  internal fun xorDigestInto(accumulator: ByteArray, digest: ByteArray) {
    require(accumulator.size == SHA_256_BYTE_COUNT && digest.size == SHA_256_BYTE_COUNT)
    accumulator.indices.forEach { index ->
      accumulator[index] = (accumulator[index].toInt() xor digest[index].toInt()).toByte()
    }
  }

  internal fun stateDigestFromAccumulator(operationCount: Int, accumulator: ByteArray): ByteArray {
    require(operationCount in 0..MAX_SNAPSHOT_OPERATIONS)
    require(accumulator.size == SHA_256_BYTE_COUNT)
    return sha256(
      ByteBuffer.allocate(Int.SIZE_BYTES * 2 + accumulator.size)
        .putInt(STATE_DIGEST_MAGIC)
        .putInt(operationCount)
        .put(accumulator)
        .array(),
    )
  }

  /** Exact framed snapshot size from the sum of its encoded operation JSON payloads. */
  internal fun snapshotDocumentByteCount(operationJsonBytes: Long, operationCount: Int): Long =
    SNAPSHOT_HEADER_BYTES + operationJsonBytes + operationCount.toLong() * Int.SIZE_BYTES

  internal fun validateOperation(operation: BoardOperation) {
    requireValidOperation(operation)
  }

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
    var x = 0L
    var y = 0L
    return values.chunked(2).map { delta ->
      x += delta[0]
      y += delta[1]
      require(
        x in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() &&
          y in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()
      ) {
        "Live coordinate overflow"
      }
      LogicalPoint(x.toInt(), y.toInt())
    }
  }
}

/**
 * Returns whether this peer should offer its merge-only snapshot after a Hello.
 *
 * High-water marks cannot describe holes, even when one vector dominates another. Any digest
 * mismatch therefore triggers snapshots from every nonempty side; merge-only snapshots produce the
 * union and canonical conflict resolution chooses the same value for an equivocated id.
 */
fun shouldOfferSnapshot(
  local: BoardState,
  remoteDigest: ByteArray,
  localDigest: ByteArray = WhiteboardProtocol.stateDigest(local),
): Boolean {
  if (local.operations.isEmpty()) return false
  return !localDigest.contentEquals(remoteDigest)
}

internal fun shouldSendReciprocalSnapshot(
  receivedMaterialDigest: ByteArray,
  mergedLocalDigest: ByteArray,
): Boolean = !receivedMaterialDigest.contentEquals(mergedLocalDigest)

internal fun requireValidOperation(
  operation: BoardOperation,
  expectedPeerKey: String? = null,
) {
  val id = operation.id
  val stamp = operation.stamp
  require(id.senderPeerKey.isNotBlank() && id.senderPeerKey.length <= MAX_PEER_KEY_LENGTH) {
    "Invalid operation sender"
  }
  require(id.senderSequence in 1..MAX_PROTOCOL_COUNTER) { "Invalid operation sequence" }
  require(stamp.lamport in 1..MAX_PROTOCOL_COUNTER) { "Invalid Lamport stamp" }
  require(stamp.peerKey == id.senderPeerKey && stamp.senderSequence == id.senderSequence) {
    "Operation id and stamp differ"
  }
  require(expectedPeerKey == null || id.senderPeerKey == expectedPeerKey) {
    "Operation sender does not match the connected peer"
  }

  fun LogicalPoint.requireInBounds() {
    require(x in 0..BOARD_WIDTH && y in 0..BOARD_HEIGHT) { "Point is outside the board" }
  }

  fun BoardObject.requireConsistent() {
    require(this.id.origin == operation.id && this.id.fragmentDigest.isEmpty()) {
      "Object id does not match its commit"
    }
    require(this.stamp == operation.stamp) { "Object stamp does not match its commit" }
    require(isApprovedWhiteboardColor(colorArgb)) { "Object color is not approved" }
    when (this) {
      is BoardObject.Freehand -> {
        require(width in 1..MAX_STROKE_WIDTH)
        require(points.size in 1..MAX_OPERATION_POINTS)
        points.forEach(LogicalPoint::requireInBounds)
      }
      is BoardObject.Line -> {
        require(width in 1..MAX_STROKE_WIDTH)
        start.requireInBounds()
        end.requireInBounds()
      }
      is BoardObject.Rectangle -> {
        require(width in 1..MAX_STROKE_WIDTH)
        start.requireInBounds()
        end.requireInBounds()
        require(start.x != end.x && start.y != end.y) { "Rectangle must have visible area" }
      }
      is BoardObject.Ellipse -> {
        require(width in 1..MAX_STROKE_WIDTH)
        start.requireInBounds()
        end.requireInBounds()
        require(start.x != end.x && start.y != end.y) { "Ellipse must have visible area" }
      }
      is BoardObject.Text -> {
        require(size in 1..MAX_STROKE_WIDTH)
        require(font in BoardTextFont.entries) { "Unsupported text font" }
        require(text.length in 1..MAX_TEXT_LENGTH)
        require(text.none(Char::isISOControl)) { "Text contains control characters" }
        anchor.requireInBounds()
      }
    }
  }

  when (operation) {
    is BoardOperation.Commit -> {
      require(operation.gestureId.isNotBlank() && operation.gestureId.length <= MAX_GESTURE_ID_LENGTH) {
        "Invalid gesture id"
      }
      operation.boardObject.requireConsistent()
    }
    is BoardOperation.Erase -> {
      require(operation.radius in 1..MAX_ERASER_RADIUS)
      require(operation.path.size in 1..MAX_ERASER_POINTS)
      require(operation.gestureId.isNotBlank() && operation.gestureId.length <= MAX_GESTURE_ID_LENGTH) {
        "Invalid gesture id"
      }
      operation.path.forEach(LogicalPoint::requireInBounds)
    }
    is BoardOperation.Clear -> Unit
    is BoardOperation.ProfileUpdate -> {
      require(operation.profile.peerKey == id.senderPeerKey) { "Profile owner does not match its operation" }
      require(operation.profile.displayName.length in 1..24)
      require(operation.profile.displayName.none(Char::isISOControl))
      require(isApprovedWhiteboardColor(operation.profile.colorArgb)) { "Profile color is not approved" }
    }
  }
}

fun Envelope.operationStamp(): OperationStamp =
  OperationStamp(lamport = lamport, peerKey = senderPeerKey, senderSequence = senderSequence)
