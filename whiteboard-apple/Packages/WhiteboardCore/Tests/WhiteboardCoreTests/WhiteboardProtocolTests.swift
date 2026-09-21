import Foundation
import Testing
@testable import WhiteboardCore

private func operation(sequence: Int64 = 7, lamport: Int64 = 9) -> BoardOperation.Commit {
  let id = OperationId(senderPeerKey: "peer", senderSequence: sequence)
  let stamp = OperationStamp(lamport: lamport, peerKey: "peer", senderSequence: sequence)
  return BoardOperation.Commit(
    id: id,
    stamp: stamp,
    boardObject: .freehand(
      BoardObject.Freehand(
        id: ObjectId(origin: id),
        stamp: stamp,
        colorArgb: whiteboardPalette[0],
        points: [LogicalPoint(x: 1, y: 2), LogicalPoint(x: 3, y: 4)]
      )
    )
  )
}

@Suite("WhiteboardProtocolTest")
struct WhiteboardProtocolTest {
  @Test func operationRoundTripsThroughProtobufEnvelope() throws {
    let op = operation()
    let decoded = WhiteboardProtocol.decodeEnvelope(
      try WhiteboardProtocol.operationEnvelope(.commit(op))
    )
    guard case .compatible(let envelope) = decoded else {
      Issue.record("Expected compatible envelope, got \(decoded)")
      return
    }
    #expect(try WhiteboardProtocol.decodeOperation(envelope) == .commit(op))
  }

  @Test func incompatibleVersionIsReported() throws {
    var envelope = Ditto_Whiteboard_V4_Envelope()
    envelope.protocolVersion = UInt32(WhiteboardProtocol.protocolVersion + 1)
    envelope.boardID = "demo"
    let bytes: Data = try envelope.serializedBytes()
    let decoded = WhiteboardProtocol.decodeEnvelope(bytes)
    guard case .incompatible(let version) = decoded else {
      Issue.record("Expected incompatible, got \(decoded)")
      return
    }
    #expect(version == WhiteboardProtocol.protocolVersion + 1)
  }

  @Test func liveCoordinatesUseLosslessDeltaEncoding() throws {
    let preview = LivePreview(
      peerKey: "peer",
      tool: .pen,
      colorArgb: whiteboardPalette[0],
      points: [LogicalPoint(x: 100, y: 200), LogicalPoint(x: 103, y: 198), LogicalPoint(x: 500, y: 700)],
      expiresAtMillis: 1,
      gestureId: "gesture"
    )
    let decoded = WhiteboardProtocol.decodeLive(
      try WhiteboardProtocol.encodeLive(preview, sequence: 1, senderSessionId: "session"),
      nowMillis: 50
    )
    guard case .compatible(let value) = decoded else {
      Issue.record("Expected compatible live preview, got \(decoded)")
      return
    }
    #expect(value.points == preview.points)
    #expect(value.expiresAtMillis == 2_050)
  }

  @Test func snapshotRoundTripsOperationsAndDigest() throws {
    let op = operation()
    let state = BoardReducer.apply(BoardState(), operation: .commit(op))
    let bytes = try WhiteboardProtocol.snapshotBytes(state)
    let decodedOperations = try WhiteboardProtocol.snapshotOperations(bytes)
    #expect(Set(decodedOperations) == Set(state.operations.values))
    let rebuilt = BoardReducer.merge(BoardState(), operations: decodedOperations)
    #expect(WhiteboardProtocol.stateDigest(state) == WhiteboardProtocol.stateDigest(rebuilt))
    var digestAccumulator = Data(repeating: 0, count: sha256ByteCount)
    WhiteboardProtocol.xorDigestInto(
      &digestAccumulator,
      WhiteboardProtocol.operationStateDigest(.commit(op))
    )
    #expect(
      WhiteboardProtocol.stateDigest(state)
        == WhiteboardProtocol.stateDigestFromAccumulator(
          operationCount: 1, accumulator: digestAccumulator
        )
    )
    #expect(
      Int64(bytes.count)
        == WhiteboardProtocol.snapshotDocumentByteCount(
          operationJsonBytes: Int64(WhiteboardProtocol.operationJsonByteCount(.commit(op))),
          operationCount: 1
        )
    )
  }

  @Test func snapshotRejectsDuplicateOperationIds() throws {
    let snapshot = try WhiteboardProtocol.snapshotBytes(
      BoardReducer.apply(BoardState(), operation: .commit(operation()))
    )
    let framedOperation = snapshot.subdata(in: 8..<snapshot.count)
    var duplicateSnapshot = Data()
    duplicateSnapshot.append(snapshot.subdata(in: 0..<4))
    duplicateSnapshot.appendBigEndian(Int32(2))
    duplicateSnapshot.append(framedOperation)
    duplicateSnapshot.append(framedOperation)

    #expect(throws: WhiteboardCoreError.requirementFailed("Snapshot contains duplicate operation ids")) {
      try WhiteboardProtocol.snapshotOperations(duplicateSnapshot)
    }
  }

  @Test func snapshotFramingRejectsHostileCountsLengthsAndTrailingBytes() throws {
    let snapshot = try WhiteboardProtocol.snapshotBytes(
      BoardReducer.apply(BoardState(), operation: .commit(operation()))
    )
    var excessiveCount = Data()
    excessiveCount.append(snapshot.subdata(in: 0..<4))
    excessiveCount.appendBigEndian(Int32(WhiteboardProtocol.maxSnapshotOperations + 1))
    var invalidLength = Data()
    invalidLength.append(snapshot.subdata(in: 0..<4))
    invalidLength.appendBigEndian(Int32(1))
    invalidLength.appendBigEndian(Int32(1))
    var trailingByte = snapshot
    trailingByte.append(0x42)

    #expect(throws: WhiteboardCoreError.requirementFailed("Snapshot contains too many operations")) {
      try WhiteboardProtocol.snapshotOperations(excessiveCount)
    }
    #expect(throws: WhiteboardCoreError.requirementFailed("Snapshot operation length is invalid")) {
      try WhiteboardProtocol.snapshotOperations(invalidLength)
    }
    #expect(throws: WhiteboardCoreError.requirementFailed("Snapshot contains trailing bytes")) {
      try WhiteboardProtocol.snapshotOperations(trailingByte)
    }
  }

  @Test func digestMismatchRepairsDroppedMiddleOperation() {
    let first = BoardOperation.commit(operation(sequence: 1, lamport: 1))
    let third = BoardOperation.commit(operation(sequence: 3, lamport: 3))
    let stateWithGap = [first, third].reduce(BoardState()) { BoardReducer.apply($0, operation: $1) }

    #expect(stateWithGap.highWaterMarks == ["peer": 3])
    #expect(
      shouldOfferSnapshot(local: stateWithGap, remoteDigest: Data(repeating: 0, count: sha256ByteCount))
    )
    #expect(
      !shouldOfferSnapshot(
        local: stateWithGap,
        remoteDigest: WhiteboardProtocol.stateDigest(stateWithGap)
      )
    )
  }

  @Test func reciprocalSnapshotRepairsAReceivedSubsetWithoutDependingOnAnotherHello() {
    let received = Data([1, 2, 3])
    let merged = Data([1, 2, 4])

    #expect(shouldSendReciprocalSnapshot(receivedMaterialDigest: received, mergedLocalDigest: merged))
    #expect(
      !shouldSendReciprocalSnapshot(receivedMaterialDigest: received, mergedLocalDigest: received)
    )
  }

  @Test func connectedPeerMustMatchWireSender() throws {
    let encoded = try WhiteboardProtocol.operationEnvelope(.commit(operation()))
    let decoded = WhiteboardProtocol.decodeEnvelope(encoded, expectedPeerKey: "another-peer")
    guard case .invalid = decoded else {
      Issue.record("Expected invalid, got \(decoded)")
      return
    }
  }

  @Test func inconsistentObjectIdentityIsRejected() {
    let valid = operation()
    guard case .freehand(let freehand) = valid.boardObject else {
      Issue.record("Expected freehand")
      return
    }
    var forgedObject = freehand
    forgedObject.id = ObjectId(origin: OperationId(senderPeerKey: "someone-else", senderSequence: 7))
    let forged = BoardOperation.Commit(
      id: valid.id, stamp: valid.stamp, boardObject: .freehand(forgedObject),
      gestureId: valid.gestureId
    )
    #expect(throws: WhiteboardCoreError.self) {
      try requireValidOperation(.commit(forged), expectedPeerKey: "peer")
    }
  }

  @Test func protocolRepresentsTerminalLamportButRejectsOutOfRangeValue() throws {
    try requireValidOperation(
      .commit(operation(lamport: WhiteboardProtocol.maxProtocolCounter)),
      expectedPeerKey: "peer"
    )
    #expect(throws: WhiteboardCoreError.self) {
      try requireValidOperation(
        .commit(operation(lamport: WhiteboardProtocol.maxProtocolCounter + 1)),
        expectedPeerKey: "peer"
      )
    }
  }

  @Test func invisibleLiveColorIsRejectedBeforeItCanConsumeBoardCapacity() throws {
    var preview = Ditto_Whiteboard_V4_LivePreview()
    preview.tool = UInt32(DrawingTool.pen.rawValue)
    preview.colorArgb = 0x001D1B20
    preview.coordinateDeltas = [1, 2, 2, 2]
    preview.gestureID = "gesture"
    var envelope = Ditto_Whiteboard_V4_LiveEnvelope()
    envelope.protocolVersion = UInt32(WhiteboardProtocol.protocolVersion)
    envelope.boardID = "shared-whiteboard"
    envelope.senderPeerKey = "peer"
    envelope.senderSequence = 1
    envelope.senderSessionID = "session"
    envelope.preview = preview
    let bytes: Data = try envelope.serializedBytes()

    guard case .invalid = WhiteboardProtocol.decodeLive(bytes, nowMillis: 0) else {
      Issue.record("Expected invalid")
      return
    }
  }

  @Test func committedTextRejectsControlCharacters() {
    let valid = operation()
    let text = BoardObject.Text(
      id: valid.boardObject.id,
      stamp: valid.stamp,
      colorArgb: whiteboardPalette[0],
      anchor: LogicalPoint(x: 1, y: 2),
      text: "first\nsecond"
    )
    let forged = BoardOperation.Commit(
      id: valid.id, stamp: valid.stamp, boardObject: .text(text), gestureId: valid.gestureId
    )
    #expect(throws: WhiteboardCoreError.self) {
      try requireValidOperation(.commit(forged))
    }
  }

  @Test func invisibleAreaShapeIsRejected() {
    let valid = operation()
    let rectangle = BoardObject.Rectangle(
      id: valid.boardObject.id,
      stamp: valid.stamp,
      colorArgb: whiteboardPalette[0],
      start: LogicalPoint(x: 20, y: 20),
      end: LogicalPoint(x: 20, y: 80)
    )
    let forged = BoardOperation.Commit(
      id: valid.id, stamp: valid.stamp, boardObject: .rectangle(rectangle), gestureId: valid.gestureId
    )
    #expect(throws: WhiteboardCoreError.self) {
      try requireValidOperation(.commit(forged))
    }
  }
}

@Suite("SnapshotAckBusyTest")
struct SnapshotAckBusyTest {
  private func roundTrip(accepted: Bool, error: String, busy: Bool) throws -> Ditto_Whiteboard_V4_SnapshotAck {
    var ack = Ditto_Whiteboard_V4_SnapshotAck()
    ack.transferID = "transfer-1"
    ack.accepted = accepted
    ack.error = error
    ack.busy = busy
    return try Ditto_Whiteboard_V4_SnapshotAck(serializedBytes: ack.serializedBytes() as Data)
  }

  @Test func busyRefusalSurvivesAnEncodeDecodeRoundTripAndStaysDistinctFromAPlainRejection() throws {
    let busy = try roundTrip(accepted: false, error: "Another snapshot is already being received", busy: true)
    let failed = try roundTrip(accepted: false, error: "Snapshot digest mismatch", busy: false)

    #expect(!busy.accepted)
    #expect(busy.busy)
    #expect(!failed.accepted)
    #expect(!failed.busy)
  }

  @Test func anAcceptedAckIsNeverBusy() throws {
    let accepted = try roundTrip(accepted: true, error: "", busy: false)
    #expect(accepted.accepted)
    #expect(!accepted.busy)
  }

  @Test func anAckWithoutTheBusyFieldReadsAsAGenuineRejection() throws {
    var legacyShaped = Ditto_Whiteboard_V4_SnapshotAck()
    legacyShaped.transferID = "transfer-1"
    legacyShaped.accepted = false
    legacyShaped.error = "Snapshot was superseded by a newer transfer"
    let bytes: Data = try legacyShaped.serializedBytes()

    let decoded = try Ditto_Whiteboard_V4_SnapshotAck(serializedBytes: bytes)
    #expect(!decoded.busy)
  }

  @Test func busySurvivesTheFullEnvelopeThatActuallyCrossesTheWire() throws {
    var ack = Ditto_Whiteboard_V4_SnapshotAck()
    ack.transferID = "transfer-1"
    ack.accepted = false
    ack.error = "Another snapshot is already being received"
    ack.busy = true
    var envelope = Ditto_Whiteboard_V4_Envelope()
    envelope.protocolVersion = UInt32(WhiteboardProtocol.protocolVersion)
    envelope.boardID = boardID
    envelope.senderPeerKey = "peer-a"
    envelope.senderSequence = 1
    envelope.snapshotAck = ack
    let bytes: Data = try envelope.serializedBytes()

    let decoded = WhiteboardProtocol.decodeEnvelope(bytes, expectedPeerKey: "peer-a")
    guard case .compatible(let value) = decoded else {
      Issue.record("Expected compatible, got \(decoded)")
      return
    }
    guard case .snapshotAck(let decodedAck) = value.payload else {
      Issue.record("Expected snapshotAck payload")
      return
    }
    #expect(decodedAck.busy)
  }
}
