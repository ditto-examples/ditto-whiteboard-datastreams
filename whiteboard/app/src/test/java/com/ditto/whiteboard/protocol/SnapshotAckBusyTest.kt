package com.ditto.whiteboard.protocol

import com.ditto.whiteboard.domain.BOARD_ID
import com.ditto.whiteboard.protocol.proto.Envelope
import com.ditto.whiteboard.protocol.proto.SnapshotAck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `SnapshotAck.busy` is the only thing that lets a snapshot sender tell "your transfer failed" from
 * "my single hydration slot is occupied, hold on".
 *
 * Without it the sender spends its three-strike error budget in about seven seconds and tears the
 * stream down while the receiver is patiently waiting for the slot — the reconnect churn a
 * full-mesh late join produces. These tests pin the wire contract that distinction rests on.
 */
class SnapshotAckBusyTest {
  @Test
  fun busyRefusalSurvivesAnEncodeDecodeRoundTripAndStaysDistinctFromAPlainRejection() {
    val busy = roundTrip(accepted = false, error = "Another snapshot is already being received", busy = true)
    val failed = roundTrip(accepted = false, error = "Snapshot digest mismatch", busy = false)

    assertFalse(busy.accepted)
    assertTrue("a busy refusal must be identifiable on the wire", busy.busy)

    assertFalse(failed.accepted)
    assertFalse("a genuine failure must not look like backpressure", failed.busy)
  }

  @Test
  fun anAcceptedAckIsNeverBusy() {
    val accepted = roundTrip(accepted = true, error = "", busy = false)

    assertTrue(accepted.accepted)
    assertFalse(accepted.busy)
  }

  /**
   * `busy` defaults to false, so a peer that never sets it — including one built against the
   * pre-`busy` message shape — is read as a genuine rejection rather than as backpressure. That is
   * the safe direction: it degrades to the old retry-and-reconnect behaviour instead of making a
   * sender wait forever on a peer that will never re-offer.
   */
  @Test
  fun anAckWithoutTheBusyFieldReadsAsAGenuineRejection() {
    val legacyShaped = SnapshotAck.newBuilder()
      .setTransferId("transfer-1")
      .setAccepted(false)
      .setError("Snapshot was superseded by a newer transfer")
      .build()

    val decoded = SnapshotAck.parseFrom(legacyShaped.toByteArray())

    assertFalse(decoded.busy)
  }

  @Test
  fun busySurvivesTheFullEnvelopeThatActuallyCrossesTheWire() {
    val envelope = Envelope.newBuilder()
      .setProtocolVersion(PROTOCOL_VERSION)
      .setBoardId(BOARD_ID)
      .setSenderPeerKey("peer-a")
      .setSenderSequence(1L)
      .setSnapshotAck(
        SnapshotAck.newBuilder()
          .setTransferId("transfer-1")
          .setAccepted(false)
          .setError("Another snapshot is already being received")
          .setBusy(true),
      )
      .build()

    val decoded = WhiteboardProtocol.decodeEnvelope(envelope.toByteArray(), expectedPeerKey = "peer-a")

    assertTrue(decoded is ProtocolDecodeResult.Compatible)
    val value = (decoded as ProtocolDecodeResult.Compatible).value
    assertEquals(Envelope.PayloadCase.SNAPSHOT_ACK, value.payloadCase)
    assertTrue(value.snapshotAck.busy)
  }

  private fun roundTrip(accepted: Boolean, error: String, busy: Boolean): SnapshotAck =
    SnapshotAck.parseFrom(
      SnapshotAck.newBuilder()
        .setTransferId("transfer-1")
        .setAccepted(accepted)
        .setError(error)
        .setBusy(busy)
        .build()
        .toByteArray(),
    )
}
