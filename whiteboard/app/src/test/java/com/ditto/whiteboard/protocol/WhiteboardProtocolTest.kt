package com.ditto.whiteboard.protocol

import com.ditto.whiteboard.domain.BoardObject
import com.ditto.whiteboard.domain.BoardOperation
import com.ditto.whiteboard.domain.BoardReducer
import com.ditto.whiteboard.domain.BoardState
import com.ditto.whiteboard.domain.BoardTextFont
import com.ditto.whiteboard.domain.DrawingTool
import com.ditto.whiteboard.domain.LivePreview
import com.ditto.whiteboard.domain.LogicalPoint
import com.ditto.whiteboard.domain.ObjectId
import com.ditto.whiteboard.domain.OperationId
import com.ditto.whiteboard.domain.OperationStamp
import com.ditto.whiteboard.domain.WHITEBOARD_PALETTE
import com.ditto.whiteboard.protocol.proto.Envelope
import com.ditto.whiteboard.protocol.proto.LiveEnvelope
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WhiteboardProtocolTest {
  @Test
  fun operationRoundTripsThroughProtobufEnvelope() {
    val operation = operation()
    val decoded = WhiteboardProtocol.decodeEnvelope(WhiteboardProtocol.operationEnvelope(operation))
    assertTrue(decoded is ProtocolDecodeResult.Compatible)
    val envelope = (decoded as ProtocolDecodeResult.Compatible).value
    assertEquals(operation, WhiteboardProtocol.decodeOperation(envelope))
  }

  @Test
  fun textFontAndSizeRoundTripThroughTheReliableEnvelope() {
    val valid = operation()
    val styled = valid.copy(
      boardObject = BoardObject.Text(
        id = valid.boardObject.id,
        stamp = valid.stamp,
        colorArgb = WHITEBOARD_PALETTE.first(),
        anchor = LogicalPoint(1, 2),
        text = "Styled",
        size = 80,
        font = BoardTextFont.Rounded,
      ),
    )

    val decoded = WhiteboardProtocol.decodeEnvelope(WhiteboardProtocol.operationEnvelope(styled))
    assertTrue(decoded is ProtocolDecodeResult.Compatible)
    assertEquals(styled, WhiteboardProtocol.decodeOperation((decoded as ProtocolDecodeResult.Compatible).value))
  }

  @Test
  fun incompatibleVersionIsReported() {
    val bytes = Envelope.newBuilder()
      .setProtocolVersion(PROTOCOL_VERSION + 1)
      .setBoardId("demo")
      .build()
      .toByteArray()
    assertEquals(ProtocolDecodeResult.Incompatible(PROTOCOL_VERSION + 1), WhiteboardProtocol.decodeEnvelope(bytes))
  }

  @Test
  fun liveCoordinatesUseLosslessDeltaEncoding() {
    val preview = LivePreview(
      "peer", DrawingTool.Pen, WHITEBOARD_PALETTE.first(),
      listOf(LogicalPoint(100, 200), LogicalPoint(103, 198), LogicalPoint(500, 700)), 1,
      gestureId = "gesture",
    )
    val decoded = WhiteboardProtocol.decodeLive(
      WhiteboardProtocol.encodeLive(preview, 1, "session"),
      nowMillis = 50,
    )
    assertTrue(decoded is ProtocolDecodeResult.Compatible)
    assertEquals(preview.points, (decoded as ProtocolDecodeResult.Compatible).value.points)
    assertEquals(2_050, decoded.value.expiresAtMillis)
  }

  @Test
  fun snapshotRoundTripsOperationsAndDigest() {
    val state = BoardReducer.apply(BoardState(), operation())
    val bytes = WhiteboardProtocol.snapshotBytes(state)
    val decodedOperations = WhiteboardProtocol.snapshotOperations(bytes)
    assertEquals(state.operations.values.toSet(), decodedOperations.toSet())
    val rebuilt = BoardReducer.merge(BoardState(), decodedOperations)
    assertArrayEquals(WhiteboardProtocol.stateDigest(state), WhiteboardProtocol.stateDigest(rebuilt))
    val digestAccumulator = ByteArray(SHA_256_BYTE_COUNT)
    WhiteboardProtocol.xorDigestInto(
      digestAccumulator,
      WhiteboardProtocol.operationStateDigest(operation()),
    )
    assertArrayEquals(
      WhiteboardProtocol.stateDigest(state),
      WhiteboardProtocol.stateDigestFromAccumulator(1, digestAccumulator),
    )
    assertEquals(
      bytes.size.toLong(),
      WhiteboardProtocol.snapshotDocumentByteCount(
        WhiteboardProtocol.operationJsonByteCount(operation()).toLong(),
        operationCount = 1,
      ),
    )
  }

  @Test
  fun snapshotRejectsDuplicateOperationIds() {
    val snapshot = WhiteboardProtocol.snapshotBytes(BoardReducer.apply(BoardState(), operation()))
    val framedOperation = snapshot.copyOfRange(Long.SIZE_BYTES, snapshot.size)
    val duplicateSnapshot = ByteBuffer.allocate(Long.SIZE_BYTES + framedOperation.size * 2)
      .put(snapshot, 0, Int.SIZE_BYTES)
      .putInt(2)
      .put(framedOperation)
      .put(framedOperation)
      .array()

    val error = assertThrows(IllegalArgumentException::class.java) {
      WhiteboardProtocol.snapshotOperations(duplicateSnapshot)
    }
    assertEquals("Snapshot contains duplicate operation ids", error.message)
  }

  @Test
  fun snapshotFramingRejectsHostileCountsLengthsAndTrailingBytes() {
    val snapshot = WhiteboardProtocol.snapshotBytes(BoardReducer.apply(BoardState(), operation()))
    val excessiveCount = ByteBuffer.allocate(Long.SIZE_BYTES)
      .put(snapshot, 0, Int.SIZE_BYTES)
      .putInt(MAX_SNAPSHOT_OPERATIONS + 1)
      .array()
    val invalidLength = ByteBuffer.allocate(Long.SIZE_BYTES + Int.SIZE_BYTES)
      .put(snapshot, 0, Int.SIZE_BYTES)
      .putInt(1)
      .putInt(1)
      .array()
    val trailingByte = snapshot + 0x42

    assertEquals(
      "Snapshot contains too many operations",
      assertThrows(IllegalArgumentException::class.java) {
        WhiteboardProtocol.snapshotOperations(excessiveCount)
      }.message,
    )
    assertEquals(
      "Snapshot operation length is invalid",
      assertThrows(IllegalArgumentException::class.java) {
        WhiteboardProtocol.snapshotOperations(invalidLength)
      }.message,
    )
    assertEquals(
      "Snapshot contains trailing bytes",
      assertThrows(IllegalArgumentException::class.java) {
        WhiteboardProtocol.snapshotOperations(trailingByte)
      }.message,
    )
  }

  @Test
  fun digestMismatchRepairsDroppedMiddleOperation() {
    val first = operation(sequence = 1, lamport = 1)
    val third = operation(sequence = 3, lamport = 3)
    val stateWithGap = listOf(first, third).fold(BoardState()) { state, operation ->
      BoardReducer.apply(state, operation)
    }

    assertEquals(mapOf("peer" to 3L), stateWithGap.highWaterMarks)
    assertTrue(
      shouldOfferSnapshot(
        local = stateWithGap,
        remoteDigest = ByteArray(SHA_256_BYTE_COUNT),
      ),
    )
    assertFalse(
      shouldOfferSnapshot(
        local = stateWithGap,
        remoteDigest = WhiteboardProtocol.stateDigest(stateWithGap),
      ),
    )
  }

  @Test
  fun reciprocalSnapshotRepairsAReceivedSubsetWithoutDependingOnAnotherHello() {
    val received = byteArrayOf(1, 2, 3)
    val merged = byteArrayOf(1, 2, 4)

    assertTrue(shouldSendReciprocalSnapshot(received, merged))
    assertFalse(shouldSendReciprocalSnapshot(received, received.copyOf()))
  }

  @Test
  fun connectedPeerMustMatchWireSender() {
    val encoded = WhiteboardProtocol.operationEnvelope(operation())
    val decoded = WhiteboardProtocol.decodeEnvelope(encoded, expectedPeerKey = "another-peer")
    assertTrue(decoded is ProtocolDecodeResult.Invalid)
  }

  @Test
  fun inconsistentObjectIdentityIsRejected() {
    val valid = operation()
    val freehand = valid.boardObject as BoardObject.Freehand
    val forged = valid.copy(
      boardObject = freehand.copy(
        id = ObjectId(OperationId("someone-else", 7)),
      ),
    )
    assertThrows(IllegalArgumentException::class.java) {
      requireValidOperation(forged, expectedPeerKey = "peer")
    }
  }

  @Test
  fun protocolRepresentsTerminalLamportButRejectsOutOfRangeValue() {
    requireValidOperation(operation(lamport = MAX_PROTOCOL_COUNTER), expectedPeerKey = "peer")
    assertThrows(IllegalArgumentException::class.java) {
      requireValidOperation(
        operation(lamport = MAX_PROTOCOL_COUNTER + 1),
        expectedPeerKey = "peer",
      )
    }
  }

  @Test
  fun invisibleLiveColorIsRejectedBeforeItCanConsumeBoardCapacity() {
    val bytes = LiveEnvelope.newBuilder()
      .setProtocolVersion(PROTOCOL_VERSION)
      .setBoardId("shared-whiteboard")
      .setSenderPeerKey("peer")
      .setSenderSequence(1)
      .setSenderSessionId("session")
      .setPreview(
        com.ditto.whiteboard.protocol.proto.LivePreview.newBuilder()
          .setTool(DrawingTool.Pen.ordinal)
          .setColorArgb(0x001D1B20)
          .addAllCoordinateDeltas(listOf(1, 2, 2, 2))
          .setGestureId("gesture"),
      )
      .build()
      .toByteArray()

    assertTrue(WhiteboardProtocol.decodeLive(bytes, nowMillis = 0) is ProtocolDecodeResult.Invalid)
  }

  @Test
  fun committedTextRejectsControlCharacters() {
    val valid = operation()
    val text = BoardObject.Text(
      id = valid.boardObject.id,
      stamp = valid.stamp,
      colorArgb = WHITEBOARD_PALETTE.first(),
      anchor = LogicalPoint(1, 2),
      text = "first\nsecond",
    )

    assertThrows(IllegalArgumentException::class.java) {
      requireValidOperation(valid.copy(boardObject = text))
    }
  }

  @Test
  fun invisibleAreaShapeIsRejected() {
    val valid = operation()
    val rectangle = BoardObject.Rectangle(
      id = valid.boardObject.id,
      stamp = valid.stamp,
      colorArgb = WHITEBOARD_PALETTE.first(),
      start = LogicalPoint(20, 20),
      end = LogicalPoint(20, 80),
    )

    assertThrows(IllegalArgumentException::class.java) {
      requireValidOperation(valid.copy(boardObject = rectangle))
    }
  }

  private fun operation(sequence: Long = 7, lamport: Long = 9): BoardOperation.Commit {
    val id = OperationId("peer", sequence)
    val stamp = OperationStamp(lamport, "peer", sequence)
    return BoardOperation.Commit(
      id, stamp,
      BoardObject.Freehand(ObjectId(id), stamp, WHITEBOARD_PALETTE.first(), points = listOf(LogicalPoint(1, 2), LogicalPoint(3, 4))),
    )
  }
}
