package com.ditto.whiteboard.transport

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportLifecycleCoordinatorTest {
  @Test
  fun localPreviewConvergesWhenForegroundRequestRunsBeforePermissionRefresh() {
    val coordinator = TransportLifecycleCoordinator(
      permissionsGranted = true,
      requestedForeground = false,
    )

    val deniedGeneration = coordinator.requestPermissions(false)
    coordinator.requestForeground(true)
    val target = coordinator.markPermissionsRefreshed(deniedGeneration)!!

    assertTrue(target.requestedForeground)
    assertFalse(target.permissionsReadyForSync)
    assertFalse(target.shouldRunNearby(started = true, localOnly = true))
  }

  @Test
  fun restoredPermissionCannotRunSyncUntilLatestRefreshCompletes() {
    val coordinator = TransportLifecycleCoordinator(
      permissionsGranted = false,
      requestedForeground = false,
    )

    val staleDeniedGeneration = coordinator.requestPermissions(false)
    val grantedGeneration = coordinator.requestPermissions(true)
    coordinator.requestForeground(true)

    assertFalse(
      coordinator.snapshot().shouldRunNearby(started = true, localOnly = false),
    )
    assertNull(coordinator.markPermissionsRefreshed(staleDeniedGeneration))
    assertTrue(
      coordinator.markPermissionsRefreshed(grantedGeneration)!!
        .shouldRunNearby(started = true, localOnly = false),
    )
  }

  @Test
  fun foregroundLocalPreviewCannotAdmitAStreamAfterPermissionRevocation() {
    assertFalse(
      isNearbyLifecycleActive(
        started = true,
        foreground = true,
        nearbyNetworkingRunning = false,
        localOnly = true,
      ),
    )
    assertTrue(
      isNearbyLifecycleActive(
        started = true,
        foreground = true,
        nearbyNetworkingRunning = true,
        localOnly = false,
      ),
    )
  }
}
