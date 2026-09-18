package com.ditto.whiteboard.data

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileLoadStateTest {
  @Test
  fun legacyWhiteProfileColorLoadsAsAVisiblePaletteColor() {
    val profile = loadedProfileSettings("Ada", 0xFFFFFFFF.toInt())

    assertEquals(0xFF0057B8.toInt(), profile?.colorArgb)
  }

  @Test
  fun exceptionalProfileUpstreamBecomesAnExplicitLoadedErrorState() = runTest {
    val state = flow<ProfileLoadState> {
      throw IllegalStateException("storage failed")
    }.recoverProfileLoadFailures().single()

    assertTrue(state.loaded)
    assertEquals(ProfileLoadFailure.StorageUnavailable, state.failure)
    assertEquals(null, state.profile)
  }

  @Test
  fun fatalProfileUpstreamErrorIsNotConvertedToAStorageFailure() {
    assertThrows(AssertionError::class.java) {
      runTest {
        flow<ProfileLoadState> {
          throw AssertionError("fatal")
        }.recoverProfileLoadFailures().single()
      }
    }
  }
}
