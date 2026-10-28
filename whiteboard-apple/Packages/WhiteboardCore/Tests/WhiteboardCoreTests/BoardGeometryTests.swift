import Testing
@testable import WhiteboardCore

@Suite("BoardGeometryTest")
struct BoardGeometryTest {
  @Test func simplifyKeepsEndpointsAndRemovesCollinearNoise() {
    let points = (0...100).map { LogicalPoint(x: $0, y: $0) }
    let simplified = BoardGeometry.simplify(points)
    #expect(simplified == [points.first!, points.last!])
  }

  @Test func simplifyHandlesMaximumWireStrokeWithoutRecursion() {
    let points = (0..<4_096).map { index in
      LogicalPoint(x: index % boardWidth, y: index % 2 == 0 ? 0 : boardHeight)
    }
    let simplified = BoardGeometry.simplify(points)
    #expect(simplified.first == points.first)
    #expect(simplified.last == points.last)
  }

  @Test func eraserDeterministicallySplitsFreehand() {
    let id = OperationId(senderPeerKey: "a", senderSequence: 1)
    let stamp = OperationStamp(lamport: 1, peerKey: "a", senderSequence: 1)
    let stroke = BoardObject.freehand(
      BoardObject.Freehand(
        id: ObjectId(origin: id), stamp: stamp, colorArgb: Int32(bitPattern: 0xFF000000), width: 2,
        points: [
          LogicalPoint(x: 0, y: 50), LogicalPoint(x: 40, y: 50),
          LogicalPoint(x: 60, y: 50), LogicalPoint(x: 100, y: 50),
        ]
      )
    )
    let erase = BoardOperation.Erase(
      id: OperationId(senderPeerKey: "b", senderSequence: 1),
      stamp: OperationStamp(lamport: 2, peerKey: "b", senderSequence: 1),
      path: [LogicalPoint(x: 50, y: 30), LogicalPoint(x: 50, y: 70)],
      radius: 3
    )

    let first = BoardGeometry.erase([stroke], eraser: erase)
    let second = BoardGeometry.erase([stroke], eraser: erase)
    #expect(first == second)
    #expect(first.count == 2)
    #expect(first.allSatisfy { if case .freehand = $0 { true } else { false } })
    #expect(!first.map(\.id).contains(stroke.id))
  }

  @Test func eraserRemovesIntersectedShapeInFull() {
    let id = OperationId(senderPeerKey: "a", senderSequence: 1)
    let stamp = OperationStamp(lamport: 1, peerKey: "a", senderSequence: 1)
    let rectangle = BoardObject.rectangle(
      BoardObject.Rectangle(
        id: ObjectId(origin: id), stamp: stamp, colorArgb: Int32(bitPattern: 0xFF000000),
        start: LogicalPoint(x: 10, y: 10), end: LogicalPoint(x: 100, y: 100)
      )
    )
    let erase = BoardOperation.Erase(
      id: OperationId(senderPeerKey: "b", senderSequence: 1),
      stamp: OperationStamp(lamport: 2, peerKey: "b", senderSequence: 1),
      path: [LogicalPoint(x: 50, y: 0), LogicalPoint(x: 50, y: 30)],
      radius: 4
    )
    #expect(BoardGeometry.erase([rectangle], eraser: erase).isEmpty)
  }

  @Test func eraserRemovesSinglePointFreehandDot() {
    let id = OperationId(senderPeerKey: "a", senderSequence: 1)
    let stamp = OperationStamp(lamport: 1, peerKey: "a", senderSequence: 1)
    let dot = BoardObject.freehand(
      BoardObject.Freehand(
        id: ObjectId(origin: id), stamp: stamp, colorArgb: Int32(bitPattern: 0xFF000000), width: 4,
        points: [LogicalPoint(x: 50, y: 50)]
      )
    )
    let erase = BoardOperation.Erase(
      id: OperationId(senderPeerKey: "b", senderSequence: 1),
      stamp: OperationStamp(lamport: 2, peerKey: "b", senderSequence: 1),
      path: [LogicalPoint(x: 55, y: 50)],
      radius: 4
    )
    #expect(BoardGeometry.erase([dot], eraser: erase).isEmpty)
  }

  @Test func eraserSplitsACollinearTwoPointStrokeAtCapsuleBoundaries() throws {
    let id = OperationId(senderPeerKey: "a", senderSequence: 1)
    let stamp = OperationStamp(lamport: 1, peerKey: "a", senderSequence: 1)
    let stroke = BoardObject.freehand(
      BoardObject.Freehand(
        id: ObjectId(origin: id), stamp: stamp, colorArgb: Int32(bitPattern: 0xFF000000), width: 2,
        points: [LogicalPoint(x: 0, y: 50), LogicalPoint(x: 100, y: 50)]
      )
    )
    let erase = BoardOperation.Erase(
      id: OperationId(senderPeerKey: "b", senderSequence: 1),
      stamp: OperationStamp(lamport: 2, peerKey: "b", senderSequence: 1),
      path: [LogicalPoint(x: 50, y: 40), LogicalPoint(x: 50, y: 60)],
      radius: 5
    )

    let fragments = BoardGeometry.erase([stroke], eraser: erase).compactMap { object -> BoardObject.Freehand? in
      if case .freehand(let stroke) = object { return stroke }
      return nil
    }

    #expect(fragments.count == 2)
    let firstFragment = try #require(fragments.first)
    let lastFragment = try #require(fragments.last)
    #expect(firstFragment.points.first?.x == 0)
    #expect((43...45).contains(try #require(firstFragment.points.last).x))
    #expect((55...57).contains(try #require(lastFragment.points.first).x))
    #expect(lastFragment.points.last?.x == 100)
  }

  @Test func fragmentIdentityStaysFixedSizeAcrossDeepRepeatedErasure() {
    let root = ObjectId(origin: OperationId(senderPeerKey: "origin", senderSequence: 1))
    var current = root
    for depth in 0..<1_000 {
      let next = BoardGeometry.childObjectId(
        current,
        eraserOperationId: OperationId(senderPeerKey: "eraser", senderSequence: Int64(depth + 1)),
        index: 1_000 + depth
      )
      #expect(next.fragmentDigest.count == 64)
      #expect(current.fragmentDigest != next.fragmentDigest)
      current = next
    }
    #expect(root.origin == current.origin)
  }

  @Test func fragmentAmplificationNeverEvictsUnrelatedObjectsAtTheCap() {
    let sourceId = OperationId(senderPeerKey: "source", senderSequence: 1)
    let sourceStamp = OperationStamp(lamport: 1, peerKey: "source", senderSequence: 1)
    let source = BoardObject.freehand(
      BoardObject.Freehand(
        id: ObjectId(origin: sourceId), stamp: sourceStamp,
        colorArgb: Int32(bitPattern: 0xFF000000), width: 2,
        points: [
          LogicalPoint(x: 0, y: 100), LogicalPoint(x: 100, y: 100), LogicalPoint(x: 200, y: 100),
        ]
      )
    )
    let unrelated: [BoardObject] = (1..<maxRenderedBoardObjects).map { sequence in
      let id = OperationId(senderPeerKey: "other", senderSequence: Int64(sequence))
      let stamp = OperationStamp(lamport: Int64(sequence + 1), peerKey: "other", senderSequence: Int64(sequence))
      return .line(
        BoardObject.Line(
          id: ObjectId(origin: id), stamp: stamp, colorArgb: Int32(bitPattern: 0xFF000000),
          start: LogicalPoint(x: 1_000 + sequence, y: 0),
          end: LogicalPoint(x: 1_000 + sequence, y: 10)
        )
      )
    }
    let erase = BoardOperation.Erase(
      id: OperationId(senderPeerKey: "eraser", senderSequence: 1),
      stamp: OperationStamp(
        lamport: Int64(maxRenderedBoardObjects + 2), peerKey: "eraser", senderSequence: 1
      ),
      path: [LogicalPoint(x: 100, y: 80), LogicalPoint(x: 100, y: 120)],
      radius: 3
    )

    let survivors = BoardGeometry.erase([source] + unrelated, eraser: erase)

    #expect(survivors.count == maxRenderedBoardObjects)
    let survivorIds = Set(survivors.map(\.id))
    #expect(unrelated.allSatisfy { survivorIds.contains($0.id) })
  }
}
