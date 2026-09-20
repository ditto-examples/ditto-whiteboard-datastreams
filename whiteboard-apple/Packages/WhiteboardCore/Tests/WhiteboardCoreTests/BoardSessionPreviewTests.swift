import Foundation
import Testing
@testable import WhiteboardCore

@Suite("BoardSessionPreviewTest")
struct BoardSessionPreviewTest {
  private func points(_ coordinates: Int...) -> [LogicalPoint] {
    coordinates.map { LogicalPoint(x: $0, y: $0) }
  }

  private func remotePreview(
    _ sequence: Int64,
    session: String = "session-remote",
    gesture: String = "gesture-remote",
    points: [LogicalPoint]? = nil
  ) -> LivePreview {
    LivePreview(
      peerKey: "peer-remote",
      tool: .pen,
      colorArgb: whiteboardPalette[2],
      points: points ?? self.points(1, 2),
      expiresAtMillis: Int64(Date().timeIntervalSince1970 * 1_000) + 5_000,
      gestureId: gesture,
      frameSequence: sequence,
      senderSessionId: session
    )
  }

  private func remoteCommit(_ gesture: String) -> BoardOperation {
    let id = OperationId(senderPeerKey: "peer-remote", senderSequence: 1)
    let stamp = OperationStamp(lamport: 2, peerKey: "peer-remote", senderSequence: 1)
    return .commit(
      BoardOperation.Commit(
        id: id,
        stamp: stamp,
        boardObject: .line(
          BoardObject.Line(
            id: ObjectId(origin: id),
            stamp: stamp,
            colorArgb: whiteboardPalette[2],
            start: LogicalPoint(x: 1, y: 1),
            end: LogicalPoint(x: 2, y: 2)
          )
        ),
        gestureId: gesture
      )
    )
  }

  private func startedSession(_ transport: FakeTransport) async throws -> BoardSession {
    let session = BoardSession(transport: transport, messages: .test)
    try await session.start(displayName: "Local Artist", colorArgb: whiteboardPalette[1])
    return session
  }

  /// The local in-progress stroke is drawn by the canvas from its own active points; if the local
  /// peer also appeared in the previews map the canvas would draw it a second time and leave a TTL
  /// ghost when abandoned. preview() must never add the local peer, though it still broadcasts.
  @Test func localPreviewIsSentButNotAddedToPreviewsMap() async throws {
    let transport = FakeTransport(localPeerKey: "peer-local")
    let session = try await startedSession(transport)

    session.preview(
      gestureId: "gesture-local",
      tool: .pen,
      colorArgb: Int32(bitPattern: 0xFF0057B8),
      points: [LogicalPoint(x: 10, y: 10), LogicalPoint(x: 20, y: 20)]
    )

    #expect(transport.recordedLive.count == 1)
    #expect(transport.recordedLive.last?.peerKey == "peer-local")
    let previews = await session.previews
    #expect(previews["peer-local"] == nil)
  }

  @Test func remotePreviewIsAddedToPreviewsMap() async throws {
    let transport = FakeTransport(localPeerKey: "peer-local")
    let session = try await startedSession(transport)

    transport.deliver(.livePreviewReceived(remotePreview(1, points: points(30, 40))))

    #expect(await eventually { await session.previews["peer-remote"] != nil })
    let previews = await session.previews
    #expect(previews["peer-local"] == nil)
  }

  @Test func slidingPreviewSegmentsMergeTheirLargestOverlap() async throws {
    let transport = FakeTransport(localPeerKey: "peer-local")
    let session = try await startedSession(transport)

    transport.deliver(.livePreviewReceived(remotePreview(1, points: points(1, 2, 3))))
    transport.deliver(.livePreviewReceived(remotePreview(2, points: points(2, 3, 4))))

    #expect(await eventually {
      await session.previews["peer-remote"]?.points == self.points(1, 2, 3, 4)
    })
  }

  @Test func duplicateAndReorderedPreviewFramesCannotRewindTheStroke() async throws {
    let transport = FakeTransport(localPeerKey: "peer-local")
    let session = try await startedSession(transport)

    transport.deliver(.livePreviewReceived(remotePreview(2, points: points(2, 3))))
    transport.deliver(.livePreviewReceived(remotePreview(1, points: points(1, 2))))
    transport.deliver(.livePreviewReceived(remotePreview(2, points: points(9))))

    #expect(await eventually { await session.previews["peer-remote"] != nil })
    let previews = await session.previews
    #expect(previews["peer-remote"]?.points == points(2, 3))
  }

  @Test func newGestureReplacesRatherThanJoinsThePriorPreview() async throws {
    let transport = FakeTransport(localPeerKey: "peer-local")
    let session = try await startedSession(transport)

    transport.deliver(.livePreviewReceived(remotePreview(1, points: points(1, 2))))
    transport.deliver(
      .livePreviewReceived(remotePreview(2, gesture: "gesture-new", points: points(8, 9)))
    )

    #expect(await eventually {
      await session.previews["peer-remote"]?.gestureId == "gesture-new"
    })
    let previews = await session.previews
    #expect(previews["peer-remote"]?.points == points(8, 9))
  }

  @Test func reliableCommitRetiresItsPreviewAndLateLiveFrameCannotResurrectIt() async throws {
    let transport = FakeTransport(localPeerKey: "peer-local")
    let session = try await startedSession(transport)
    let operation = remoteCommit("gesture-remote")

    transport.deliver(.livePreviewReceived(remotePreview(1)))
    transport.deliver(
      .reliableOperationReceived(
        operation: operation,
        prepared: nil,
        committed: nil,
        applied: nil
      )
    )
    transport.deliver(.livePreviewReceived(remotePreview(2)))

    #expect(await eventually {
      if await session.boardState.operations[operation.id] == nil { return false }
      return await session.previews["peer-remote"] == nil
    })
    let board = await session.boardState
    #expect(board.operations[operation.id] == operation)
  }

  @Test func restartedSenderSessionAdvancesButFramesFromRetiredSessionStayIgnored() async throws {
    let transport = FakeTransport(localPeerKey: "peer-local")
    let session = try await startedSession(transport)

    transport.deliver(.livePreviewReceived(remotePreview(9, session: "old")))
    transport.deliver(
      .livePreviewReceived(remotePreview(1, session: "new", gesture: "new", points: points(7)))
    )
    transport.deliver(
      .livePreviewReceived(
        remotePreview(10, session: "old", gesture: "old-late", points: points(99))
      )
    )

    #expect(await eventually {
      await session.previews["peer-remote"]?.senderSessionId == "new"
    })
    let previews = await session.previews
    #expect(previews["peer-remote"]?.points == points(7))
  }

  @Test func operationIsNotAppliedUntilTransportCommitsItsAuthoritativeLog() async throws {
    let transport = FakeTransport(localPeerKey: "peer-local")
    let session = try await startedSession(transport)
    let operation = remoteCommit("gesture")
    let prepared = HandshakeSignal()
    let committed = HandshakeSignal()
    let applied = HandshakeSignal()

    transport.deliver(
      .reliableOperationReceived(
        operation: operation,
        prepared: prepared,
        committed: committed,
        applied: applied
      )
    )

    #expect(try await prepared.wait() == true)
    #expect(await session.boardState.operations[operation.id] == nil)

    committed.complete(false)
    #expect(try await applied.wait() == false)
    #expect(await session.boardState.operations[operation.id] == nil)
  }

  @Test func degenerateAreaShapesDoNotConsumeOperationCapacity() async throws {
    let transport = FakeTransport(localPeerKey: "peer-local")
    let session = try await startedSession(transport)
    // Wait for the start-up profile update so the count below is stable.
    #expect(await eventually { await session.boardState.profiles["peer-local"] != nil })
    let initialCount = await session.boardState.operations.count

    await session.commit(
      tool: .rectangle,
      colorArgb: whiteboardPalette[1],
      points: [LogicalPoint(x: 10, y: 10), LogicalPoint(x: 10, y: 50)]
    )
    await session.commit(
      tool: .ellipse,
      colorArgb: whiteboardPalette[1],
      points: [LogicalPoint(x: 20, y: 20), LogicalPoint(x: 40, y: 20)]
    )

    let finalCount = await session.boardState.operations.count
    #expect(finalCount == initialCount)
  }
}
