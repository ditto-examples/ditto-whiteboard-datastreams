package com.ditto.whiteboard.transport

import com.ditto.whiteboard.domain.BoardOperation
import com.ditto.whiteboard.domain.LogicalPoint
import com.ditto.whiteboard.domain.OperationId
import com.ditto.whiteboard.domain.OperationStamp
import com.ditto.whiteboard.domain.UserProfile
import com.ditto.whiteboard.protocol.MAX_ERASE_OPERATIONS
import com.ditto.whiteboard.protocol.MAX_SNAPSHOT_OPERATIONS
import com.ditto.whiteboard.protocol.WhiteboardProtocol
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedOperationLogTest {
  @Test
  fun localPreviewRejectsTheOperationImmediatelyAfterItsTerminalLimit() = runTest {
    val transport = InMemoryWhiteboardTransport(localPeerKey = "local", reason = "Test")

    repeat(MAX_SNAPSHOT_OPERATIONS) { index ->
      assertTrue(transport.sendReliable(clear(index + 1L)))
    }

    assertFalse(transport.sendReliable(clear(MAX_SNAPSHOT_OPERATIONS + 1L)))
  }

  @Test
  fun localPreviewUsesTheSameEraseBudgetAsNearbyMesh() = runTest {
    val transport = InMemoryWhiteboardTransport(localPeerKey = "local", reason = "Test")

    repeat(MAX_ERASE_OPERATIONS) { index ->
      assertTrue(transport.sendReliable(erase(index + 1L)))
    }

    assertFalse(transport.sendReliable(erase(MAX_ERASE_OPERATIONS + 1L)))
  }

  @Test
  fun incrementalDigestMatchesCanonicalStateDigest() {
    val log = BoundedOperationLog()
    val operations = listOf(clear(1), erase(2), clear(3))

    operations.forEach { assertFalse(log.accept(it) is KnownOperationResult.Rejected) }

    val material = log.material()
    assertArrayEquals(WhiteboardProtocol.stateDigest(material.state), material.digest)
  }

  @Test
  fun profileIndexTracksLatestStampAndEquivocationReplacement() {
    val log = BoundedOperationLog()
    val ada = profile(sequence = 1, lamport = 1, displayName = "Ada")
    val grace = profile(sequence = 2, lamport = 2, displayName = "Grace")

    assertEquals(KnownOperationResult.Added, log.accept(ada))
    assertEquals(KnownOperationResult.Added, log.accept(grace))
    assertEquals("Grace", log.profile("local")?.displayName)

    val replacement = BoardOperation.Clear(ada.id, ada.stamp)
    assertEquals(KnownOperationResult.Replaced, log.accept(replacement))
    // Grace remains because it is a newer, independent profile operation.
    assertEquals("Grace", log.profile("local")?.displayName)

    val replacementForLatest = BoardOperation.Clear(grace.id, grace.stamp)
    assertEquals(KnownOperationResult.Replaced, log.accept(replacementForLatest))
    assertNull(log.profile("local"))
  }

  private fun clear(sequence: Long): BoardOperation.Clear {
    val id = OperationId("local", sequence)
    return BoardOperation.Clear(id, OperationStamp(sequence, "local", sequence))
  }

  private fun erase(sequence: Long): BoardOperation.Erase {
    val id = OperationId("local", sequence)
    return BoardOperation.Erase(
      id,
      OperationStamp(sequence, "local", sequence),
      path = listOf(LogicalPoint(10, 10)),
      radius = 2,
    )
  }

  private fun profile(
    sequence: Long,
    lamport: Long,
    displayName: String,
  ): BoardOperation.ProfileUpdate {
    val id = OperationId("local", sequence)
    return BoardOperation.ProfileUpdate(
      id = id,
      stamp = OperationStamp(lamport, "local", sequence),
      profile = UserProfile("local", displayName, 0xFF0057B8.toInt()),
    )
  }
}
