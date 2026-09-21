import Foundation
import Testing
@testable import WhiteboardCore

private let failingLamport: Int64 = 5_000

private func commit(_ peerKey: String, _ sequence: Int64) -> BoardOperation {
  let id = OperationId(senderPeerKey: peerKey, senderSequence: sequence)
  let stamp = OperationStamp(lamport: sequence, peerKey: peerKey, senderSequence: sequence)
  return .commit(
    BoardOperation.Commit(
      id: id,
      stamp: stamp,
      boardObject: .line(
        BoardObject.Line(
          id: ObjectId(origin: id),
          stamp: stamp,
          colorArgb: whiteboardPalette[1],
          start: LogicalPoint(x: 10, y: 10),
          end: LogicalPoint(x: 200, y: 200)
        )
      )
    )
  )
}

/// A failure while handling one remote event must not end the session's event consumer.
@Suite("BoardSessionEventResilienceTest")
struct BoardSessionEventResilienceTest {
  private func makeSession(
    transport: FakeTransport
  ) -> (BoardSession, LockedBox<Set<Int64>>) {
    // Fail the reservation only for the first remote operation's Lamport value, so the session's
    // own start-up profile update is unaffected and the failure lands exactly where intended.
    let failedFor = LockedBox<Set<Int64>>([])
    let session = BoardSession(
      transport: transport,
      messages: .test,
      reserveOperationClock: {
        OperationClockReservation(firstSenderSequence: 0, initialLamport: 0, lamportCeiling: 1)
      },
      reserveLamportAfter: { observed in
        let shouldFail = failedFor.withLock { failed -> Bool in
          guard observed == failingLamport, !failed.contains(observed) else { return false }
          failed.insert(observed)
          return true
        }
        if shouldFail {
          throw WhiteboardCoreError.requirementFailed(
            "durable clock reservation is unavailable"
          )
        }
        return observed + 1_000
      }
    )
    return (session, failedFor)
  }

  @Test func aRemoteOperationThatFailsClockReservationDoesNotWedgeTheCollector() async throws {
    let transport = FakeTransport(localPeerKey: "peer-local")
    let (session, _) = makeSession(transport: transport)
    try await session.start(displayName: "Local", colorArgb: whiteboardPalette[0])

    let failing = commit("peer-remote", failingLamport)
    let firstApplied = HandshakeSignal()
    transport.deliver(
      .reliableOperationReceived(
        operation: failing,
        prepared: HandshakeSignal(),
        committed: HandshakeSignal(completed: true),
        applied: firstApplied
      )
    )

    #expect(await eventually { await session.error != nil })
    #expect(try await firstApplied.wait() == false)
    let boardAfterFailure = await session.boardState
    #expect(boardAfterFailure.operations[failing.id] == nil)

    let recovered = commit("peer-remote", failingLamport + 1)
    let secondApplied = HandshakeSignal()
    transport.deliver(
      .reliableOperationReceived(
        operation: recovered,
        prepared: HandshakeSignal(),
        committed: HandshakeSignal(completed: true),
        applied: secondApplied
      )
    )

    #expect(try await secondApplied.wait() == true)
    if case .commit(let recoveredCommit) = recovered {
      let board = await session.boardState
      #expect(board.operations[recovered.id] != nil)
      #expect(board.objects[recoveredCommit.boardObject.id] != nil)
    } else {
      Issue.record("expected a commit operation")
    }
  }

  @Test func aSnapshotThatFailsClockReservationDoesNotWedgeTheCollector() async throws {
    let transport = FakeTransport(localPeerKey: "peer-local")
    let (session, _) = makeSession(transport: transport)
    try await session.start(displayName: "Local", colorArgb: whiteboardPalette[0])

    let failing = commit("peer-remote", failingLamport)
    transport.deliver(
      .snapshotMerged(
        operations: [failing],
        prepared: HandshakeSignal(),
        committed: HandshakeSignal(completed: true),
        applied: HandshakeSignal()
      )
    )
    #expect(await eventually { await session.error != nil })
    #expect(await session.boardState.operations[failing.id] == nil)

    let recovered = commit("peer-remote", failingLamport + 1)
    transport.deliver(
      .reliableOperationReceived(
        operation: recovered,
        prepared: HandshakeSignal(),
        committed: HandshakeSignal(completed: true),
        applied: HandshakeSignal()
      )
    )
    #expect(await eventually {
      await session.boardState.operations[recovered.id] != nil
    })
  }

  /// Exercises the consumer's own guard: a transport can fail a handshake signal, so `wait()`
  /// throws from inside the event handler, outside every inner catch — exactly the shape that used
  /// to end the collector permanently.
  @Test func anExceptionallyCompletedHandshakeDoesNotWedgeTheCollector() async throws {
    let transport = FakeTransport(localPeerKey: "peer-local")
    let session = BoardSession(transport: transport, messages: .test)
    try await session.start(displayName: "Local", colorArgb: whiteboardPalette[0])

    let poisoned = HandshakeSignal()
    poisoned.fail(WhiteboardCoreError.requirementFailed("transport failed mid-handshake"))
    transport.deliver(
      .reliableOperationReceived(
        operation: commit("peer-remote", failingLamport),
        prepared: HandshakeSignal(),
        committed: poisoned,
        applied: HandshakeSignal()
      )
    )
    #expect(await eventually { await session.error != nil })

    let recovered = commit("peer-remote", failingLamport + 1)
    transport.deliver(
      .reliableOperationReceived(
        operation: recovered,
        prepared: HandshakeSignal(),
        committed: HandshakeSignal(completed: true),
        applied: HandshakeSignal()
      )
    )
    #expect(await eventually {
      await session.boardState.operations[recovered.id] != nil
    })
  }
}
