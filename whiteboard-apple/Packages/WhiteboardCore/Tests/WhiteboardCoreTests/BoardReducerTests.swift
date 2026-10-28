import Testing
@testable import WhiteboardCore

private func commit(_ peer: String, _ sequence: Int64, _ lamport: Int64, _ x: Int) -> BoardOperation.Commit {
  let id = OperationId(senderPeerKey: peer, senderSequence: sequence)
  let stamp = OperationStamp(lamport: lamport, peerKey: peer, senderSequence: sequence)
  return BoardOperation.Commit(
    id: id,
    stamp: stamp,
    boardObject: .line(
      BoardObject.Line(
        id: ObjectId(origin: id),
        stamp: stamp,
        colorArgb: Int32(bitPattern: 0xFF000000),
        start: LogicalPoint(x: x, y: 0),
        end: LogicalPoint(x: x, y: 100)
      )
    )
  )
}

private func erase(_ peer: String, _ sequence: Int64, _ lamport: Int64) -> BoardOperation.Erase {
  BoardOperation.Erase(
    id: OperationId(senderPeerKey: peer, senderSequence: sequence),
    stamp: OperationStamp(lamport: lamport, peerKey: peer, senderSequence: sequence),
    path: [LogicalPoint(x: 10, y: 50), LogicalPoint(x: 10, y: 60)],
    radius: 2
  )
}

@Suite("BoardReducerTest")
struct BoardReducerTest {
  @Test func concurrentDeliveryOrdersConverge() {
    let operations: [BoardOperation] = [
      .commit(commit("a", 1, 1, 10)),
      .commit(commit("b", 1, 1, 20)),
      .erase(erase("c", 1, 2)),
    ]
    let forward = operations.reduce(BoardState()) { BoardReducer.apply($0, operation: $1) }
    let reverse = operations.reversed().reduce(BoardState()) { BoardReducer.apply($0, operation: $1) }
    #expect(forward == reverse)
  }

  @Test func duplicateOperationIsIdempotent() {
    let operation = BoardOperation.commit(commit("a", 1, 1, 10))
    let once = BoardReducer.apply(BoardState(), operation: operation)
    let twice = BoardReducer.apply(once, operation: operation)
    #expect(once == twice)
    #expect(twice.operations.count == 1)
  }

  @Test func clearSuppressesOlderDrawingButAllowsNewerDrawing() {
    let old = commit("a", 1, 1, 10)
    let clear = BoardOperation.Clear(
      id: OperationId(senderPeerKey: "b", senderSequence: 1),
      stamp: OperationStamp(lamport: 4, peerKey: "b", senderSequence: 1)
    )
    let newer = commit("a", 2, 5, 200)
    let state = BoardReducer.merge(
      BoardState(),
      operations: [.commit(newer), .clear(clear), .commit(old)]
    )
    #expect(state.objects[old.boardObject.id] == nil)
    #expect(state.objects[newer.boardObject.id] != nil)
    #expect(state.clearWatermark == clear.stamp)
  }

  @Test func operationStampUsesStableTotalOrder() {
    let stamps = [
      OperationStamp(lamport: 2, peerKey: "b", senderSequence: 1),
      OperationStamp(lamport: 2, peerKey: "a", senderSequence: 2),
      OperationStamp(lamport: 1, peerKey: "z", senderSequence: 5),
    ].sorted()
    #expect(stamps.map(\.peerKey) == ["z", "a", "b"])
  }

  @Test func equivocatedOperationIdConvergesRegardlessOfDeliveryOrder() {
    let first = BoardOperation.commit(commit("a", 1, 1, 10))
    let second = BoardOperation.commit(commit("a", 1, 1, 200))

    let firstThenSecond = [first, second].reduce(BoardState()) { BoardReducer.apply($0, operation: $1) }
    let secondThenFirst = [second, first].reduce(BoardState()) { BoardReducer.apply($0, operation: $1) }

    #expect(firstThenSecond == secondThenFirst)
    #expect(canonicalOperation(first, second) == firstThenSecond.operations[first.id])
  }
}

@Suite("BoardReducerIncrementalTest")
struct BoardReducerIncrementalTest {
  private func commitOp(_ peer: String, _ seq: Int64, _ lamport: Int64) -> BoardOperation {
    let id = OperationId(senderPeerKey: peer, senderSequence: seq)
    let stamp = OperationStamp(lamport: lamport, peerKey: peer, senderSequence: seq)
    let base = Int(seq) * 10
    let object = BoardObject.Freehand(
      id: ObjectId(origin: id),
      stamp: stamp,
      colorArgb: Int32(bitPattern: 0xFF112233),
      points: [LogicalPoint(x: base, y: base), LogicalPoint(x: base + 50, y: base + 50)]
    )
    return .commit(BoardOperation.Commit(id: id, stamp: stamp, boardObject: .freehand(object)))
  }

  private func clearOp(_ peer: String, _ seq: Int64, _ lamport: Int64) -> BoardOperation {
    .clear(
      BoardOperation.Clear(
        id: OperationId(senderPeerKey: peer, senderSequence: seq),
        stamp: OperationStamp(lamport: lamport, peerKey: peer, senderSequence: seq)
      )
    )
  }

  private func eraseOp(_ peer: String, _ seq: Int64, _ lamport: Int64, _ path: [LogicalPoint]) -> BoardOperation {
    .erase(
      BoardOperation.Erase(
        id: OperationId(senderPeerKey: peer, senderSequence: seq),
        stamp: OperationStamp(lamport: lamport, peerKey: peer, senderSequence: seq),
        path: path
      )
    )
  }

  private func profileOp(_ peer: String, _ seq: Int64, _ lamport: Int64, _ name: String, _ color: Int32) -> BoardOperation {
    .profileUpdate(
      BoardOperation.ProfileUpdate(
        id: OperationId(senderPeerKey: peer, senderSequence: seq),
        stamp: OperationStamp(lamport: lamport, peerKey: peer, senderSequence: seq),
        profile: try! UserProfile(peerKey: peer, displayName: name, colorArgb: color)
      )
    )
  }

  private func applyAll(_ ops: [BoardOperation]) -> BoardState {
    ops.reduce(BoardState()) { BoardReducer.apply($0, operation: $1) }
  }

  private func rebuilt(_ ops: [BoardOperation]) -> BoardState {
    BoardReducer.rebuild(Dictionary(ops.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first }))
  }

  @Test func inOrderApplyMatchesRebuild() {
    let ops = [commitOp("a", 1, 1), commitOp("a", 2, 2), commitOp("b", 1, 3), commitOp("a", 3, 4)]
    #expect(rebuilt(ops) == applyAll(ops))
  }

  @Test func outOfOrderApplyMatchesRebuild() {
    let a1 = commitOp("a", 1, 1)
    let a2 = commitOp("a", 2, 5)
    let b1 = commitOp("b", 1, 3)
    let state = [a2, b1, a1].reduce(BoardState()) { BoardReducer.apply($0, operation: $1) }
    #expect(rebuilt([a1, a2, b1]) == state)
  }

  @Test func clearWatermarkMatchesRebuild() {
    let ops = [commitOp("a", 1, 1), clearOp("a", 2, 2), commitOp("a", 3, 3)]
    let state = applyAll(ops)
    #expect(rebuilt(ops) == state)
    #expect(state.objects.count == 1)
  }

  @Test func duplicateApplyIsANoOp() {
    let a1 = commitOp("a", 1, 1)
    let once = BoardReducer.apply(BoardState(), operation: a1)
    let twice = BoardReducer.apply(once, operation: a1)
    #expect(once == twice)
  }

  @Test func eraseMatchesRebuild() {
    let stroke = commitOp("a", 1, 1)
    let erase = eraseOp("a", 2, 2, [LogicalPoint(x: 0, y: 35), LogicalPoint(x: 100, y: 35)])
    let ops = [stroke, erase]
    #expect(rebuilt(ops) == applyAll(ops))
  }

  @Test func profileUpdateSupersessionMatchesRebuild() {
    let ops = [
      profileOp("a", 1, 1, "Ana", Int32(bitPattern: 0xFF111111)),
      profileOp("a", 2, 2, "Ana Lee", Int32(bitPattern: 0xFF222222)),
    ]
    let state = applyAll(ops)
    #expect(rebuilt(ops) == state)
    #expect(state.profiles["a"]?.displayName == "Ana Lee")
  }

  @Test func outOfOrderCommitBeforeClearIsSuppressed() {
    let early = commitOp("a", 1, 1)
    let clear = clearOp("a", 3, 3)
    let late = commitOp("a", 2, 2)
    let ordered = [early, clear, late]
    let state = ordered.reduce(BoardState()) { BoardReducer.apply($0, operation: $1) }
    #expect(rebuilt(ordered) == state)
    #expect(state.objects.isEmpty)
  }

  @Test func manyReverseOrderedConcurrentCommitsMatchRebuild() {
    let operations = (Int64(1)...Int64(400)).map { sequence in
      commitOp(sequence % 2 == 0 ? "a" : "b", sequence, sequence)
    }
    #expect(rebuilt(operations) == applyAll(operations.reversed()))
  }

  @Test func outOfOrderCommitReplaysOnlyLaterErasers() {
    let earlyErase = eraseOp("eraser", 1, 2, [LogicalPoint(x: 0, y: 900), LogicalPoint(x: 1_000, y: 900)])
    let concurrentStroke = commitOp("artist", 1, 3)
    let lateErase = eraseOp("eraser", 2, 5, [LogicalPoint(x: 0, y: 35), LogicalPoint(x: 100, y: 35)])
    let newest = profileOp("profile", 1, 6, "Riley", Int32(bitPattern: 0xFF334455))
    let arrivalOrder = [earlyErase, lateErase, newest, concurrentStroke]
    #expect(rebuilt(arrivalOrder) == applyAll(arrivalOrder))
  }
}
