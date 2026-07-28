package com.ditto.whiteboard.protocol

import com.ditto.whiteboard.domain.BoardObject
import com.ditto.whiteboard.domain.BoardOperation
import com.ditto.whiteboard.domain.BoardReducer
import com.ditto.whiteboard.domain.BoardState
import com.ditto.whiteboard.domain.DrawingTool
import com.ditto.whiteboard.domain.LivePreview
import com.ditto.whiteboard.domain.LogicalPoint
import com.ditto.whiteboard.domain.ObjectId
import com.ditto.whiteboard.domain.OperationId
import com.ditto.whiteboard.domain.OperationStamp
import com.ditto.whiteboard.protocol.proto.Envelope
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
      "peer", DrawingTool.Pen, 0xFF123456.toInt(),
      listOf(LogicalPoint(100, 200), LogicalPoint(103, 198), LogicalPoint(500, 700)), 1,
    )
    val decoded = WhiteboardProtocol.decodeLive(WhiteboardProtocol.encodeLive(preview, 1), nowMillis = 50)
    assertTrue(decoded is ProtocolDecodeResult.Compatible)
    assertEquals(preview.points, (decoded as ProtocolDecodeResult.Compatible).value.points)
    assertEquals(2_050, decoded.value.expiresAtMillis)
  }

  @Test
  fun snapshotRoundTripsOperationsAndDigest() {
    val state = BoardReducer.apply(BoardState(), operation())
    val bytes = WhiteboardProtocol.snapshotBytes(state)
    assertEquals(state.operations.values.toSet(), WhiteboardProtocol.snapshotOperations(bytes).toSet())
    assertArrayEquals(WhiteboardProtocol.sha256(bytes), WhiteboardProtocol.stateDigest(state))
  }

  @Test
  fun stateVectorComparisonFindsDominanceAndConcurrency() {
    assertEquals(VectorRelation.Equal, compareStateVectors(mapOf("a" to 1), mapOf("a" to 1)))
    assertEquals(VectorRelation.LocalDominates, compareStateVectors(mapOf("a" to 2), mapOf("a" to 1)))
    assertEquals(VectorRelation.RemoteDominates, compareStateVectors(mapOf("a" to 1), mapOf("a" to 2)))
    assertEquals(VectorRelation.Concurrent, compareStateVectors(mapOf("a" to 2), mapOf("b" to 2)))
  }

  private fun operation(): BoardOperation.Commit {
    val id = OperationId("peer", 7)
    val stamp = OperationStamp(9, "peer", 7)
    return BoardOperation.Commit(
      id, stamp,
      BoardObject.Freehand(ObjectId(id), stamp, 0xFF123456.toInt(), points = listOf(LogicalPoint(1, 2), LogicalPoint(3, 4))),
    )
  }
}
