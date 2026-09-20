import Foundation
import Testing
@testable import WhiteboardCore

private struct SplitMix64: RandomNumberGenerator {
  var state: UInt64
  mutating func next() -> UInt64 {
    state &+= 0x9E3779B97F4A7C15
    var z = state
    z = (z ^ (z &>> 30)) &* 0xBF58476D1CE4E5B9
    z = (z ^ (z &>> 27)) &* 0x94D049BB133111EB
    return z ^ (z &>> 31)
  }
}

private struct FakePeer {
  let peerKey: String
  var clock: OperationClock
  var state = BoardState()

  init(peerKey: String) {
    self.peerKey = peerKey
    self.clock = OperationClock(peerKey: peerKey)
  }

  mutating func start(_ displayName: String, _ colorArgb: Int32) throws -> BoardOperation {
    let (id, stamp) = try clock.next()
    let operation = BoardOperation.profileUpdate(
      BoardOperation.ProfileUpdate(
        id: id,
        stamp: stamp,
        profile: try UserProfile(peerKey: peerKey, displayName: displayName, colorArgb: colorArgb)
      )
    )
    state = BoardReducer.apply(state, operation: operation)
    return operation
  }

  mutating func commit(
    tool: DrawingTool, colorArgb: Int32, points: [LogicalPoint]
  ) throws -> BoardOperation {
    let (id, stamp) = try clock.next()
    let objectId = ObjectId(origin: id)
    let object: BoardObject
    switch tool {
    case .pen:
      object = .freehand(
        BoardObject.Freehand(id: objectId, stamp: stamp, colorArgb: colorArgb, points: points)
      )
    case .line:
      object = .line(
        BoardObject.Line(
          id: objectId, stamp: stamp, colorArgb: colorArgb,
          start: points[0], end: points[points.count - 1]
        )
      )
    case .rectangle:
      object = .rectangle(
        BoardObject.Rectangle(
          id: objectId, stamp: stamp, colorArgb: colorArgb,
          start: points[0], end: points[points.count - 1]
        )
      )
    case .ellipse:
      object = .ellipse(
        BoardObject.Ellipse(
          id: objectId, stamp: stamp, colorArgb: colorArgb,
          start: points[0], end: points[points.count - 1]
        )
      )
    case .text:
      object = .text(
        BoardObject.Text(
          id: objectId, stamp: stamp, colorArgb: colorArgb, anchor: points[0], text: "text"
        )
      )
      case .eraser:
        Issue.record("commit(tool:) does not support the eraser")
        throw WhiteboardCoreError.requirementFailed("eraser is not committable")
      case .hand:
        Issue.record("commit(tool:) does not support the local Hand navigation tool")
        throw WhiteboardCoreError.requirementFailed("hand is not committable")
      }
    let operation = BoardOperation.commit(
      BoardOperation.Commit(id: id, stamp: stamp, boardObject: object)
    )
    state = BoardReducer.apply(state, operation: operation)
    return operation
  }

  mutating func clear() throws -> BoardOperation {
    let (id, stamp) = try clock.next()
    let operation = BoardOperation.clear(BoardOperation.Clear(id: id, stamp: stamp))
    state = BoardReducer.apply(state, operation: operation)
    return operation
  }
}

@Suite("TenPeerConvergenceTest")
struct TenPeerConvergenceTest {
  @Test func tenPeersConvergeAfterReorderingAndDuplicateReliableDelivery() throws {
    var peers = (0..<10).map {
      FakePeer(peerKey: String(format: "peer-%02d", $0))
    }
    var pending: [(String, BoardOperation)] = []

    for index in peers.indices {
      let operation = try peers[index].start(
        "Artist \(index)", whiteboardPalette[index % whiteboardPalette.count]
      )
      pending.append((peers[index].peerKey, operation))
    }

    for index in 0..<120 {
      let peerIndex = index % peers.count
      let x = (index * 37) % 1_900
      let operation = try peers[peerIndex].commit(
        tool: index % 3 == 0 ? .pen : .line,
        colorArgb: whiteboardPalette[index % whiteboardPalette.count],
        points: [
          LogicalPoint(x: x, y: 20),
          LogicalPoint(x: min(x + 100, boardWidth), y: 500),
        ]
      )
      pending.append((peers[peerIndex].peerKey, operation))
    }
    let clearOperation = try peers[3].clear()
    pending.append((peers[3].peerKey, clearOperation))
    for index in 0..<20 {
      let peerIndex = (index + 4) % peers.count
      let operation = try peers[peerIndex].commit(
        tool: .rectangle,
        colorArgb: Int32(bitPattern: 0xFF007A3D),
        points: [
          LogicalPoint(x: index * 20, y: 100),
          LogicalPoint(x: index * 20 + 80, y: 220),
        ]
      )
      pending.append((peers[peerIndex].peerKey, operation))
    }

    #expect(pending.count == 151)

    var rng = SplitMix64(state: 42)
    let deliveries = pending.flatMap { entry in
      (0..<3).map { _ in entry }
    }.shuffled(using: &rng)
    for (sender, operation) in deliveries {
      for index in peers.indices where peers[index].peerKey != sender {
        peers[index].state = BoardReducer.apply(peers[index].state, operation: operation)
      }
    }

    let states = peers.map(\.state)
    for state in states.dropFirst() {
      #expect(state == states[0])
    }
    #expect(states[0].operations.count == 151)
  }

  @Test func lateJoinerDrawsAboveSnapshotClearWatermark() throws {
    var lateJoiner = FakePeer(peerKey: "peer-late")
    _ = try lateJoiner.start("Late Joiner", whiteboardPalette[1])

    let remote = "peer-old"
    let clearStamp = OperationStamp(lamport: 100, peerKey: remote, senderSequence: 2)
    let preClearStamp = OperationStamp(lamport: 50, peerKey: remote, senderSequence: 1)
    let postClearStamp = OperationStamp(lamport: 120, peerKey: remote, senderSequence: 3)
    let history: [BoardOperation] = [
      .commit(
        BoardOperation.Commit(
          id: OperationId(senderPeerKey: remote, senderSequence: 1),
          stamp: preClearStamp,
          boardObject: .line(
            BoardObject.Line(
              id: ObjectId(origin: OperationId(senderPeerKey: remote, senderSequence: 1)),
              stamp: preClearStamp,
              colorArgb: whiteboardPalette[2],
              start: LogicalPoint(x: 0, y: 0),
              end: LogicalPoint(x: 10, y: 10)
            )
          )
        )
      ),
      .clear(
        BoardOperation.Clear(
          id: OperationId(senderPeerKey: remote, senderSequence: 2),
          stamp: clearStamp
        )
      ),
      .commit(
        BoardOperation.Commit(
          id: OperationId(senderPeerKey: remote, senderSequence: 3),
          stamp: postClearStamp,
          boardObject: .line(
            BoardObject.Line(
              id: ObjectId(origin: OperationId(senderPeerKey: remote, senderSequence: 3)),
              stamp: postClearStamp,
              colorArgb: whiteboardPalette[1],
              start: LogicalPoint(x: 5, y: 5),
              end: LogicalPoint(x: 20, y: 20)
            )
          )
        )
      ),
    ]
    lateJoiner.state = BoardReducer.merge(lateJoiner.state, operations: history)
    for operation in history {
      lateJoiner.clock.observe(operation.stamp)
    }

    let objectsAfterSnapshot = lateJoiner.state.objects.count
    #expect(objectsAfterSnapshot == 1)

    _ = try lateJoiner.commit(
      tool: .pen,
      colorArgb: whiteboardPalette[3],
      points: [LogicalPoint(x: 100, y: 100), LogicalPoint(x: 200, y: 200)]
    )

    let state = lateJoiner.state
    #expect(state.objects.count == objectsAfterSnapshot + 1)
    let localStrokes = state.objects.values.filter { $0.stamp.peerKey == "peer-late" }
    let localStroke = try #require(localStrokes.first)
    #expect(localStrokes.count == 1)
    #expect(localStroke.stamp > clearStamp)
    #expect(state.clearWatermark == clearStamp)
  }
}
