package com.ditto.whiteboard.data

import com.ditto.whiteboard.domain.BoardObject
import com.ditto.whiteboard.domain.BoardOperation
import com.ditto.whiteboard.domain.LogicalPoint
import com.ditto.whiteboard.domain.ObjectId
import com.ditto.whiteboard.domain.OperationId
import com.ditto.whiteboard.domain.OperationStamp
import com.ditto.whiteboard.domain.UserProfile
import com.ditto.whiteboard.domain.WHITEBOARD_PALETTE
import com.ditto.whiteboard.transport.TransportDiagnostics
import com.ditto.whiteboard.transport.TransportEvent
import com.ditto.whiteboard.transport.WhiteboardTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A failure while handling one remote event must not end the session's event collector.
 *
 * `Flow.collect` completes for good when its lambda throws, so an unguarded handler would leave
 * local editing working while every later remote edit is silently ignored — with no repair path,
 * because the transport's reconciliation digest may already count the operation whose application
 * failed. The transport applies the same discipline to its own reliable-ingress worker.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BoardSessionEventResilienceTest {
  @Test
  fun aRemoteOperationThatFailsClockReservationDoesNotWedgeTheCollector() = runTest {
    val transport = RecordingTransport("peer-local")
    // Fail the reservation only for the first remote operation's Lamport value, so the session's
    // own start-up profile update is unaffected and the failure lands exactly where intended.
    val failedFor = mutableSetOf<Long>()
    val session = BoardSession(
      transport = transport,
      scope = backgroundScope,
      messages = TEST_BOARD_SESSION_MESSAGES,
      reserveOperationClock = { OperationClockReservation(0L, 0L, lamportCeiling = 1L) },
      reserveLamportAfter = { observed ->
        if (observed == FAILING_LAMPORT && failedFor.add(observed)) {
          error("durable clock reservation is unavailable")
        }
        observed + 1_000L
      },
    )
    session.start("Local", WHITEBOARD_PALETTE[0])
    runCurrent()

    val failing = commit("peer-remote", FAILING_LAMPORT)
    val firstApplied = CompletableDeferred<Boolean>()
    transport.deliver(
      TransportEvent.ReliableOperationReceived(
        operation = failing,
        prepared = CompletableDeferred(),
        committed = CompletableDeferred(true),
        applied = firstApplied,
      ),
    )
    runCurrent()

    assertNotNull("the failure surfaces to the user", session.error.value)
    assertFalse(
      "the operation whose reservation failed must not reach the board",
      failing.id in session.boardState.value.operations,
    )
    assertFalse(
      "the transport is told the operation was not applied, so it can reconcile",
      firstApplied.getCompleted(),
    )

    // The collector must still be alive and healthy for the next peer update.
    val recovered = commit("peer-remote", FAILING_LAMPORT + 1L)
    val secondApplied = CompletableDeferred<Boolean>()
    transport.deliver(
      TransportEvent.ReliableOperationReceived(
        operation = recovered,
        prepared = CompletableDeferred(),
        committed = CompletableDeferred(true),
        applied = secondApplied,
      ),
    )
    runCurrent()

    assertTrue(
      "a later remote operation is still applied after the earlier failure",
      recovered.id in session.boardState.value.operations,
    )
    assertTrue(recovered.boardObject.id in session.boardState.value.objects)
    assertTrue(secondApplied.getCompleted())
  }

  @Test
  fun aSnapshotThatFailsClockReservationDoesNotWedgeTheCollector() = runTest {
    val transport = RecordingTransport("peer-local")
    // Fail the reservation only for the first remote operation's Lamport value, so the session's
    // own start-up profile update is unaffected and the failure lands exactly where intended.
    val failedFor = mutableSetOf<Long>()
    val session = BoardSession(
      transport = transport,
      scope = backgroundScope,
      messages = TEST_BOARD_SESSION_MESSAGES,
      reserveOperationClock = { OperationClockReservation(0L, 0L, lamportCeiling = 1L) },
      reserveLamportAfter = { observed ->
        if (observed == FAILING_LAMPORT && failedFor.add(observed)) {
          error("durable clock reservation is unavailable")
        }
        observed + 1_000L
      },
    )
    session.start("Local", WHITEBOARD_PALETTE[0])
    runCurrent()

    val failing = commit("peer-remote", FAILING_LAMPORT)
    transport.deliver(
      TransportEvent.SnapshotMerged(
        operations = listOf(failing),
        prepared = CompletableDeferred(),
        committed = CompletableDeferred(true),
        applied = CompletableDeferred(),
      ),
    )
    runCurrent()
    assertFalse(failing.id in session.boardState.value.operations)

    val recovered = commit("peer-remote", FAILING_LAMPORT + 1L)
    transport.deliver(
      TransportEvent.ReliableOperationReceived(
        operation = recovered,
        prepared = CompletableDeferred(),
        committed = CompletableDeferred(true),
        applied = CompletableDeferred(),
      ),
    )
    runCurrent()

    assertTrue(
      "the collector survived the failed snapshot and still applies operations",
      recovered.id in session.boardState.value.operations,
    )
  }

  /**
   * Exercises the collector's own guard rather than a guard further in.
   *
   * The prepare/commit/apply handshake deferreds are part of the transport-facing event contract,
   * so a transport can complete one exceptionally. `await()` then throws from inside the collect
   * lambda, outside every inner `runCatching`, which is exactly the shape that used to end the
   * collector permanently.
   */
  @Test
  fun anExceptionallyCompletedHandshakeDoesNotWedgeTheCollector() = runTest {
    val transport = RecordingTransport("peer-local")
    val session = BoardSession(transport, backgroundScope, TEST_BOARD_SESSION_MESSAGES)
    session.start("Local", WHITEBOARD_PALETTE[0])
    runCurrent()

    val poisoned = CompletableDeferred<Boolean>().apply {
      completeExceptionally(IllegalStateException("transport failed mid-handshake"))
    }
    transport.deliver(
      TransportEvent.ReliableOperationReceived(
        operation = commit("peer-remote", FAILING_LAMPORT),
        prepared = CompletableDeferred(),
        committed = poisoned,
        applied = CompletableDeferred(),
      ),
    )
    runCurrent()

    assertNotNull("the failure surfaces to the user", session.error.value)

    val recovered = commit("peer-remote", FAILING_LAMPORT + 1L)
    transport.deliver(
      TransportEvent.ReliableOperationReceived(
        operation = recovered,
        prepared = CompletableDeferred(),
        committed = CompletableDeferred(true),
        applied = CompletableDeferred(),
      ),
    )
    runCurrent()

    assertTrue(
      "the collector survived an exception thrown inside the collect lambda",
      recovered.id in session.boardState.value.operations,
    )
  }

  private companion object {
    /**
     * Above the Lamport ceiling the session reserves for its own start-up profile update, so the
     * inbound operation genuinely takes the `reserveLamportAfter` path where the failure is injected.
     */
    const val FAILING_LAMPORT = 5_000L
  }

  private fun commit(peerKey: String, sequence: Long): BoardOperation.Commit {
    val id = OperationId(peerKey, sequence)
    val stamp = OperationStamp(sequence, peerKey, sequence)
    return BoardOperation.Commit(
      id = id,
      stamp = stamp,
      boardObject = BoardObject.Line(
        id = ObjectId(id),
        stamp = stamp,
        colorArgb = WHITEBOARD_PALETTE[1],
        start = LogicalPoint(10, 10),
        end = LogicalPoint(200, 200),
      ),
    )
  }
}

private class RecordingTransport(
  override val localPeerKey: String,
) : WhiteboardTransport {
  private val mutableEvents = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 64)
  override val events = mutableEvents.asSharedFlow()
  private val mutableDiagnostics = MutableStateFlow(TransportDiagnostics(localPeerKey = localPeerKey))
  override val diagnostics = mutableDiagnostics.asStateFlow()

  override suspend fun start(profile: UserProfile) {
    mutableDiagnostics.value = mutableDiagnostics.value.copy(running = true, editingReady = true)
  }

  override suspend fun sendReliable(operation: BoardOperation): Boolean = true
  override fun sendLive(preview: com.ditto.whiteboard.domain.LivePreview) = Unit
  fun deliver(event: TransportEvent) { check(mutableEvents.tryEmit(event)) }
  override fun close() = Unit
}
