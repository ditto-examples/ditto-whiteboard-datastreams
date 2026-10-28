import Foundation

public func isNearbyLifecycleActive(
  started: Bool,
  foreground: Bool,
  nearbyNetworkingRunning: Bool,
  localOnly: Bool
) -> Bool {
  started && foreground && nearbyNetworkingRunning && !localOnly
}

/// Thread-safe desired lifecycle state for independently dispatched permission and foreground work.
///
/// A granted permission is not ready for Ditto sync until `markPermissionsRefreshed` has completed
/// for the latest request. Consumers always reconcile a current snapshot after taking their
/// lifecycle lock.
public final class TransportLifecycleCoordinator: @unchecked Sendable {
  public struct Snapshot: Equatable, Sendable {
    public var permissionsGranted: Bool
    public var permissionsReadyForSync: Bool
    public var requestedForeground: Bool
    public var permissionGeneration: Int64

    public init(
      permissionsGranted: Bool,
      permissionsReadyForSync: Bool,
      requestedForeground: Bool,
      permissionGeneration: Int64
    ) {
      self.permissionsGranted = permissionsGranted
      self.permissionsReadyForSync = permissionsReadyForSync
      self.requestedForeground = requestedForeground
      self.permissionGeneration = permissionGeneration
    }

    public func shouldRunNearby(started: Bool, localOnly: Bool) -> Bool {
      started && !localOnly && permissionsGranted && permissionsReadyForSync && requestedForeground
    }
  }

  private let lock = NSLock()
  private var granted: Bool
  private var ready: Bool
  private var foreground: Bool
  private var generation: Int64 = 0

  public init(permissionsGranted: Bool, requestedForeground: Bool) {
    granted = permissionsGranted
    ready = permissionsGranted
    foreground = requestedForeground
  }

  @discardableResult
  public func requestPermissions(allGranted: Bool) -> Int64 {
    lock.withLock {
      granted = allGranted
      // A false→true request cannot enable sync until the SDK has refreshed its permission view.
      if !allGranted || !ready { ready = false }
      generation += 1
      return generation
    }
  }

  public func markPermissionsRefreshed(requestGeneration: Int64) -> Snapshot? {
    lock.withLock {
      guard requestGeneration == generation else { return nil }
      ready = granted
      return snapshotLocked()
    }
  }

  @discardableResult
  public func requestForeground(_ isForeground: Bool) -> Bool {
    lock.withLock {
      guard foreground != isForeground else { return false }
      foreground = isForeground
      return true
    }
  }

  public func snapshot() -> Snapshot {
    lock.withLock { snapshotLocked() }
  }

  public func isCurrent(requestGeneration: Int64) -> Bool {
    lock.withLock { requestGeneration == generation }
  }

  private func snapshotLocked() -> Snapshot {
    Snapshot(
      permissionsGranted: granted,
      permissionsReadyForSync: ready,
      requestedForeground: foreground,
      permissionGeneration: generation
    )
  }
}
