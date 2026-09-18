package com.ditto.whiteboard.ui

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.test.runTest
import com.ditto.whiteboard.util.runSuspendCatchingPreservingCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WhiteboardViewModelTest {
  @Test
  fun suspendFailureHelperPreservesCancellation() {
    assertThrows(CancellationException::class.java) {
      runTest {
        runSuspendCatchingPreservingCancellation<Unit> {
          throw CancellationException("ViewModel cleared")
        }
      }
    }
  }

  @Test
  fun suspendFailureHelperStillReportsOrdinaryFailures() = runTest {
    val result = runSuspendCatchingPreservingCancellation<Unit> {
      error("storage failed")
    }

    assertEquals("storage failed", result.exceptionOrNull()?.message)
  }

  @Test
  fun suspendFailureHelperDoesNotConvertFatalErrors() {
    assertThrows(AssertionError::class.java) {
      runTest {
        runSuspendCatchingPreservingCancellation<Unit> {
          throw AssertionError("fatal")
        }
      }
    }
  }
}
