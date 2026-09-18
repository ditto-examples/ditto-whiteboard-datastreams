package com.ditto.whiteboard.transport

internal fun isNearbyLifecycleActive(
  started: Boolean,
  foreground: Boolean,
  nearbyNetworkingRunning: Boolean,
  localOnly: Boolean,
): Boolean = started && foreground && nearbyNetworkingRunning && !localOnly

/**
 * Thread-safe desired lifecycle state for independently dispatched permission and foreground work.
 *
 * A granted permission is not ready for Ditto sync until refreshPermissions has completed for the
 * latest request. Consumers always reconcile a current snapshot after taking their lifecycle lock.
 */
internal class TransportLifecycleCoordinator(
  permissionsGranted: Boolean,
  requestedForeground: Boolean,
) {
  data class Snapshot(
    val permissionsGranted: Boolean,
    val permissionsReadyForSync: Boolean,
    val requestedForeground: Boolean,
    val permissionGeneration: Long,
  ) {
    fun shouldRunNearby(started: Boolean, localOnly: Boolean): Boolean =
      started &&
        !localOnly &&
        permissionsGranted &&
        permissionsReadyForSync &&
        requestedForeground
  }

  private var granted = permissionsGranted
  private var ready = permissionsGranted
  private var foreground = requestedForeground
  private var generation = 0L

  @Synchronized
  fun requestPermissions(allGranted: Boolean): Long {
    granted = allGranted
    // A false→true request cannot enable sync until the SDK has refreshed its permission view.
    if (!allGranted || !ready) ready = false
    generation += 1
    return generation
  }

  @Synchronized
  fun markPermissionsRefreshed(requestGeneration: Long): Snapshot? {
    if (requestGeneration != generation) return null
    ready = granted
    return snapshotLocked()
  }

  @Synchronized
  fun requestForeground(isForeground: Boolean): Boolean {
    if (foreground == isForeground) return false
    foreground = isForeground
    return true
  }

  @Synchronized
  fun snapshot(): Snapshot = snapshotLocked()

  @Synchronized
  fun isCurrent(requestGeneration: Long): Boolean = requestGeneration == generation

  private fun snapshotLocked() = Snapshot(
    permissionsGranted = granted,
    permissionsReadyForSync = ready,
    requestedForeground = foreground,
    permissionGeneration = generation,
  )
}
