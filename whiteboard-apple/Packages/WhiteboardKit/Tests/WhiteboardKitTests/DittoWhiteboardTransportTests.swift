import Foundation
import Testing
import WhiteboardCore
@testable import WhiteboardKit
import DittoSwift

// License token from DittoSwiftTests/Helpers.swift (expires 2029-09-04). If activation starts
// failing with `licenseTokenExpired`, refresh this constant from the ditto monorepo.
private let testLicense =
  "o2d1c2VyX2lkcmludGVybmFsQGRpdHRvLmNvbWZleHBpcnl0MjAyOS0wOS0wNFQxNjowMDowMFppc2lnbmF0dXJleFhadm1IcFRPL3l2VE9VeURxd25zOUQ5QlFtcG54WHBCazAyTVRvOW5QeFlQSnFwNmFoKzNJa29PbzBSd1RFVFl5emxiYS9yd1JlU0ZGZE1FV0xFMU5nZz09"

private func makeCredentials(databaseID: String) -> DittoCredentials {
  DittoCredentials(
    databaseID: databaseID,
    offlineLicenseToken: testLicense,
    persistenceDirectory: FileManager.default.temporaryDirectory
      .appendingPathComponent("ditto-whiteboardkit-tests-\(UUID().uuidString.lowercased())")
  )
}

/// Polls `condition` until it holds or the deadline elapses.
private func waitUntil(
  _ description: String,
  deadlineSeconds: TimeInterval = 30,
  condition: @Sendable () async -> Bool
) async throws {
  let deadline = ContinuousClock.now + .seconds(deadlineSeconds)
  while await !condition() {
    if ContinuousClock.now >= deadline {
      Issue.record("Timed out waiting for: \(description)")
      throw WhiteboardCoreError.requirementFailed("waitUntil timed out: \(description)")
    }
    try await Task.sleep(for: .milliseconds(100))
  }
}

private actor EventCollector {
  private(set) var operations: [OperationId] = []
  private(set) var livePreviews: [LivePreview] = []
  private(set) var mergedSnapshots: [[BoardOperation]] = []

  func collect(_ events: AsyncStream<TransportEvent>) async {
    for await event in events {
      switch event {
      case .reliableOperationReceived(let operation, let prepared, _, let applied):
        prepared?.complete(true)
        applied?.complete(true)
        operations.append(operation.id)
      case .livePreviewReceived(let preview):
        livePreviews.append(preview)
      case .snapshotMerged(let operations, let prepared, _, let applied):
        prepared?.complete(true)
        applied?.complete(true)
        mergedSnapshots.append(operations)
      case .incompatiblePeer:
        break
      }
    }
  }
}

private func makeOperation(senderPeerKey: String, senderSequence: Int64) -> BoardOperation {
  let id = OperationId(senderPeerKey: senderPeerKey, senderSequence: senderSequence)
  let stamp = OperationStamp(lamport: senderSequence, peerKey: senderPeerKey, senderSequence: senderSequence)
  return .clear(BoardOperation.Clear(id: id, stamp: stamp))
}

private func makeProfile(peerKey: String, displayName: String) throws -> UserProfile {
  try UserProfile(peerKey: peerKey, displayName: displayName, colorArgb: defaultWhiteboardColor)
}

@Test
func appleLANDiscoveryIsFullyEnabledByDefault() throws {
  // The Ditto Rust core clap-parses NO_COLOR when initializing logging and panics on the "1"
  // injected by SwiftPM for piped test output.
  unsetenv("NO_COLOR")
  let transport = try DittoWhiteboardTransport(credentials: makeCredentials(databaseID: UUID().uuidString))
  defer { transport.close() }

  let lan = transport.ditto.transportConfig.peerToPeer.lan
  #expect(lan.isEnabled)
  #expect(lan.isMDNSEnabled)
  #expect(lan.isMulticastEnabled)
}

private struct ConnectedPair {
  let server: DittoWhiteboardTransport
  let client: DittoWhiteboardTransport
  let serverKey: String
  let clientKey: String

  /// Mirrors DittoSwiftTests' makeNGNDitto/makeConnectedPair: one instance listens on TCP
  /// loopback, the other connects to it, both with NGN system parameters (baked into the
  /// transport itself).
  init() async throws {
    // The Ditto Rust core clap-parses NO_COLOR when initializing logging and panics on the "1"
    // SwiftPM injects for piped test output; drop it before the first Ditto instance opens.
    unsetenv("NO_COLOR")
    let databaseID = UUID().uuidString.lowercased()
    let port = UInt16.random(in: 10_000..<20_000)
    server = try DittoWhiteboardTransport(credentials: makeCredentials(databaseID: databaseID)) { config in
      config.listen.tcp.isEnabled = true
      config.listen.tcp.interfaceIP = "127.0.0.1"
      config.listen.tcp.port = port
    }
    client = try DittoWhiteboardTransport(credentials: makeCredentials(databaseID: databaseID)) { config in
      config.connect.tcpServers = ["127.0.0.1:\(port)"]
    }
    serverKey = server.localPeerKey
    clientKey = client.localPeerKey
  }

  func startServer() async throws {
    try await server.start(profile: makeProfile(peerKey: serverKey, displayName: "Server"))
  }

  func startClient() async throws {
    try await client.start(profile: makeProfile(peerKey: clientKey, displayName: "Client"))
  }

  func close() {
    client.close()
    server.close()
  }

  func awaitFullyConnected() async throws {
    try await waitUntil("presence exchange") {
      self.server.currentDiagnostics.peers[self.clientKey] != nil
        && self.client.currentDiagnostics.peers[self.serverKey] != nil
    }
    try await waitUntil("wb_state connected") {
      self.server.currentDiagnostics.peers[self.clientKey]?.stateConnected == true
        && self.client.currentDiagnostics.peers[self.serverKey]?.stateConnected == true
    }
    try await waitUntil("wb_live connected") {
      self.server.currentDiagnostics.peers[self.clientKey]?.liveConnected == true
        && self.client.currentDiagnostics.peers[self.serverKey]?.liveConnected == true
    }
  }
}

@Test(.timeLimit(.minutes(3)))
func reliableOperationDeliveryAndLivePreview() async throws {
  let pair = try await ConnectedPair()
  defer { pair.close() }
  try await pair.startServer()
  try await pair.startClient()
  try await pair.awaitFullyConnected()

  let collector = EventCollector()
  let collectTask = Task { await collector.collect(pair.client.events) }
  defer { collectTask.cancel() }

  let operation = makeOperation(senderPeerKey: pair.serverKey, senderSequence: 1)
  let accepted = await pair.server.sendReliable(operation)
  #expect(accepted)

  try await waitUntil("reliable operation A→B") {
    await collector.operations.contains(operation.id)
  }

  pair.server.sendLive(
    LivePreview(
      peerKey: pair.serverKey,
      tool: .pen,
      colorArgb: defaultWhiteboardColor,
      points: [LogicalPoint(x: 1, y: 1), LogicalPoint(x: 5, y: 7)],
      expiresAtMillis: 0,
      gestureId: "gesture-1"
    )
  )
  try await waitUntil("live preview A→B on wb_live") {
    await !collector.livePreviews.isEmpty
  }
  let preview = await collector.livePreviews.first
  #expect(preview?.points == [LogicalPoint(x: 1, y: 1), LogicalPoint(x: 5, y: 7)])
}

@Test(.timeLimit(.minutes(3)))
func snapshotHealsLateJoiner() async throws {
  let pair = try await ConnectedPair()
  defer { pair.close() }
  try await pair.startServer()

  // Commit operations while the joiner is absent; they only travel via snapshot.
  for sequence: Int64 in 1...3 {
    let accepted = await pair.server.sendReliable(
      makeOperation(senderPeerKey: pair.serverKey, senderSequence: sequence)
    )
    #expect(accepted)
  }

  let collector = EventCollector()
  let collectTask = Task { await collector.collect(pair.client.events) }
  defer { collectTask.cancel() }

  try await pair.startClient()
  try await pair.awaitFullyConnected()

  // Digest mismatch on the Hello exchange must drive a Begin/Chunk/End snapshot from the server,
  // merged on the late joiner through the prepare/commit/apply handshake.
  try await waitUntil("snapshot merged on late joiner") {
    await collector.mergedSnapshots.contains { snapshot in
      snapshot.count == 3
        && snapshot.allSatisfy { $0.id.senderPeerKey == pair.serverKey }
    }
  }

  // After the merge, digests converge: the joiner marks the peer synchronized.
  try await waitUntil("editing ready after hydration") {
    pair.client.currentDiagnostics.editingReady
  }
}
