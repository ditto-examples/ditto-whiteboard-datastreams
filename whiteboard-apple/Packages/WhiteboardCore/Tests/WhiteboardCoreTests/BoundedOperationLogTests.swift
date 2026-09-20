import Foundation
import Testing
@testable import WhiteboardCore

private func clearOp(_ sequence: Int64) -> BoardOperation {
  let id = OperationId(senderPeerKey: "local", senderSequence: sequence)
  return .clear(
    BoardOperation.Clear(
      id: id,
      stamp: OperationStamp(lamport: sequence, peerKey: "local", senderSequence: sequence)
    )
  )
}

private func eraseOp(_ sequence: Int64) -> BoardOperation {
  let id = OperationId(senderPeerKey: "local", senderSequence: sequence)
  return .erase(
    BoardOperation.Erase(
      id: id,
      stamp: OperationStamp(lamport: sequence, peerKey: "local", senderSequence: sequence),
      path: [LogicalPoint(x: 10, y: 10)],
      radius: 2
    )
  )
}

private func profileOp(_ sequence: Int64, _ lamport: Int64, _ displayName: String) throws -> BoardOperation {
  let id = OperationId(senderPeerKey: "local", senderSequence: sequence)
  return .profileUpdate(
    BoardOperation.ProfileUpdate(
      id: id,
      stamp: OperationStamp(lamport: lamport, peerKey: "local", senderSequence: sequence),
      profile: try UserProfile(
        peerKey: "local", displayName: displayName, colorArgb: Int32(bitPattern: 0xFF0057B8)
      )
    )
  )
}

@Suite("BoundedOperationLogTest")
struct BoundedOperationLogTest {
  @Test func localPreviewRejectsTheOperationImmediatelyAfterItsTerminalLimit() {
    var log = BoundedOperationLog()
    for index in 0..<WhiteboardProtocol.maxSnapshotOperations {
      let result = log.accept(clearOp(Int64(index + 1)))
      if case .rejected(let reason) = result {
        Issue.record("Operation \(index + 1) unexpectedly rejected: \(reason)")
      }
    }

    let result = log.accept(clearOp(Int64(WhiteboardProtocol.maxSnapshotOperations + 1)))
    #expect(result == .rejected("Session operation limit reached"))
  }

  @Test func localPreviewUsesTheSameEraseBudgetAsNearbyMesh() {
    var log = BoundedOperationLog()
    for index in 0..<WhiteboardProtocol.maxEraseOperations {
      let result = log.accept(eraseOp(Int64(index + 1)))
      if case .rejected(let reason) = result {
        Issue.record("Erase \(index + 1) unexpectedly rejected: \(reason)")
      }
    }

    let result = log.accept(eraseOp(Int64(WhiteboardProtocol.maxEraseOperations + 1)))
    #expect(result == .rejected("Session erase-operation limit reached"))
  }

  @Test func incrementalDigestMatchesCanonicalStateDigest() {
    var log = BoundedOperationLog()
    let operations = [clearOp(1), eraseOp(2), clearOp(3)]

    for operation in operations {
      let result = log.accept(operation)
      if case .rejected(let reason) = result {
        Issue.record("Unexpected rejection: \(reason)")
      }
    }

    let material = log.material()
    #expect(WhiteboardProtocol.stateDigest(material.state) == material.digest)
  }

  @Test func profileIndexTracksLatestStampAndEquivocationReplacement() throws {
    var log = BoundedOperationLog()
    let ada = try profileOp(1, 1, "Ada")
    let grace = try profileOp(2, 2, "Grace")

    #expect(log.accept(ada) == .added)
    #expect(log.accept(grace) == .added)
    #expect(log.profile("local")?.displayName == "Grace")

    let replacement = BoardOperation.clear(
      BoardOperation.Clear(id: ada.id, stamp: ada.stamp)
    )
    #expect(log.accept(replacement) == .replaced)
    #expect(log.profile("local")?.displayName == "Grace")

    let replacementForLatest = BoardOperation.clear(
      BoardOperation.Clear(id: grace.id, stamp: grace.stamp)
    )
    #expect(log.accept(replacementForLatest) == .replaced)
    #expect(log.profile("local") == nil)
  }
}
