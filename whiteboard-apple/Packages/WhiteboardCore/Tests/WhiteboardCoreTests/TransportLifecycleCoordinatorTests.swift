import Foundation
import Testing
@testable import WhiteboardCore

@Suite("TransportLifecycleCoordinatorTest")
struct TransportLifecycleCoordinatorTest {
  @Test func localPreviewConvergesWhenForegroundRequestRunsBeforePermissionRefresh() {
    let coordinator = TransportLifecycleCoordinator(
      permissionsGranted: true,
      requestedForeground: false
    )

    let deniedGeneration = coordinator.requestPermissions(allGranted: false)
    coordinator.requestForeground(true)
    let target = coordinator.markPermissionsRefreshed(requestGeneration: deniedGeneration)

    #expect(target?.requestedForeground == true)
    #expect(target?.permissionsReadyForSync == false)
    #expect(target?.shouldRunNearby(started: true, localOnly: true) == false)
  }

  @Test func restoredPermissionCannotRunSyncUntilLatestRefreshCompletes() {
    let coordinator = TransportLifecycleCoordinator(
      permissionsGranted: false,
      requestedForeground: false
    )

    let staleDeniedGeneration = coordinator.requestPermissions(allGranted: false)
    let grantedGeneration = coordinator.requestPermissions(allGranted: true)
    coordinator.requestForeground(true)

    #expect(!coordinator.snapshot().shouldRunNearby(started: true, localOnly: false))
    #expect(coordinator.markPermissionsRefreshed(requestGeneration: staleDeniedGeneration) == nil)
    #expect(
      coordinator.markPermissionsRefreshed(requestGeneration: grantedGeneration)?
        .shouldRunNearby(started: true, localOnly: false) == true
    )
  }

  @Test func foregroundLocalPreviewCannotAdmitAStreamAfterPermissionRevocation() {
    #expect(
      !isNearbyLifecycleActive(
        started: true,
        foreground: true,
        nearbyNetworkingRunning: false,
        localOnly: true
      )
    )
    #expect(
      isNearbyLifecycleActive(
        started: true,
        foreground: true,
        nearbyNetworkingRunning: true,
        localOnly: false
      )
    )
  }
}
