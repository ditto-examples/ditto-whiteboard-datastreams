import CryptoKit
import Foundation

public enum WhiteboardProtocol {
  public static let protocolVersion: Int = 5
  public static let liveStreamName: String = "wb_live"
  public static let stateStreamName: String = "wb_state"

  public static let maxReliableFrameBytes: Int = 1024 * 1024
  public static let maxLiveFrameBytes: Int = 64 * 1024
  public static let maxSnapshotOperations: Int = 10_000
  public static let maxEraseOperations: Int = 128
  public static let maxOperationPoints: Int = 128
  public static let maxEraserPoints: Int = 32
  public static let maxLivePoints: Int = 64
  public static let maxPeerKeyLength: Int = 512
  public static let maxProtocolCounter: Int64 = maxOperationCounter
  public static let maxStateVectorPeers: Int = 64
  public static let maxSessionLamport: Int64 = maxProtocolCounter - Int64(maxSnapshotOperations) - 1
  static let maxOperationJsonBytes: Int = 512 * 1024
  static let maxProfileJsonBytes: Int = 4 * 1024
  static let maxStrokeWidth: Int = 256
  static let maxEraserRadius: Int = 512
  static let maxTextLength: Int = 200
  static let maxGestureIdLength: Int = 640
  static let snapshotHeaderBytes: Int64 = 8
  static let snapshotMagic: Int32 = 0x57425334 // "WBS4"
  static let stateDigestMagic: Int32 = 0x57424434 // "WBD4"

  private static func require(_ condition: Bool, _ message: String = "") throws {
    if !condition { throw WhiteboardCoreError.requirementFailed(message) }
  }

  public static func operationEnvelope(_ operation: BoardOperation) throws -> Data {
    try requireValidOperation(operation)
    var reliable = Ditto_Whiteboard_V4_ReliableOperation()
    reliable.operationJson = Data(OperationJSON.encode(operation).utf8)
    var envelope = Ditto_Whiteboard_V4_Envelope()
    envelope.protocolVersion = UInt32(protocolVersion)
    envelope.boardID = boardID
    envelope.senderPeerKey = operation.id.senderPeerKey
    envelope.senderSequence = UInt64(bitPattern: operation.id.senderSequence)
    envelope.lamport = UInt64(bitPattern: operation.stamp.lamport)
    envelope.operation = reliable
    let bytes: Data = try envelope.serializedBytes()
    try require(bytes.count <= maxReliableFrameBytes, "Reliable frame exceeds protocol limit")
    return bytes
  }

  public static func helloEnvelope(
    peerKey: String,
    sequence: Int64,
    lamport: Int64,
    profile: UserProfile,
    state: BoardState,
    stateDigest: Data? = nil
  ) throws -> Data {
    var hello = Ditto_Whiteboard_V4_Hello()
    hello.profileJson = Data(OperationJSON.encode(profile).utf8)
    hello.stateDigest = stateDigest ?? WhiteboardProtocol.stateDigest(state)
    var envelope = Ditto_Whiteboard_V4_Envelope()
    envelope.protocolVersion = UInt32(protocolVersion)
    envelope.boardID = boardID
    envelope.senderPeerKey = peerKey
    envelope.senderSequence = UInt64(bitPattern: sequence)
    envelope.lamport = UInt64(bitPattern: lamport)
    envelope.hello = hello
    let bytes: Data = try envelope.serializedBytes()
    return bytes
  }

  public static func decodeEnvelope(
    _ bytes: Data,
    expectedPeerKey: String? = nil
  ) -> ProtocolDecodeResult<Ditto_Whiteboard_V4_Envelope> {
    do {
      if bytes.count > maxReliableFrameBytes {
        return .invalid("Reliable frame exceeds protocol limit")
      }
      let envelope = try Ditto_Whiteboard_V4_Envelope(serializedBytes: bytes)
      let hello: Ditto_Whiteboard_V4_Hello? =
        if case .hello(let hello) = envelope.payload { hello } else { nil }
      if Int(envelope.protocolVersion) != protocolVersion {
        return .incompatible(Int(envelope.protocolVersion))
      } else if envelope.boardID != boardID {
        return .invalid("Unexpected board id")
      } else if envelope.senderPeerKey.isBlankKotlin
        || envelope.senderPeerKey.utf16.count > maxPeerKeyLength
      {
        return .invalid("Invalid sender peer key")
      } else if expectedPeerKey != nil && envelope.senderPeerKey != expectedPeerKey! {
        return .invalid("Envelope sender does not match the connected peer")
      } else if !(1...UInt64(bitPattern: maxProtocolCounter)).contains(envelope.senderSequence) {
        return .invalid("Invalid sender sequence")
      } else if envelope.lamport > UInt64(bitPattern: maxProtocolCounter) {
        return .invalid("Invalid Lamport stamp")
      } else if hello != nil && envelope.lamport > UInt64(bitPattern: maxSessionLamport) {
        return .invalid("Hello Lamport stamp exceeds the session range")
      } else if let hello, hello.stateDigest.count != sha256ByteCount {
        return .invalid("Invalid state digest")
      } else {
        return .compatible(envelope)
      }
    } catch {
      return .invalid(String(describing: error))
    }
  }

  public static func decodeOperation(
    _ envelope: Ditto_Whiteboard_V4_Envelope,
    expectedPeerKey: String? = nil
  ) throws -> BoardOperation {
    try require(
      envelope.operation.operationJson.count <= maxOperationJsonBytes,
      "Reliable operation exceeds protocol limit"
    )
    let operation = try OperationJSON.decodeOperation(
      String(decoding: envelope.operation.operationJson, as: UTF8.self)
    )
    try requireValidOperation(operation, expectedPeerKey: expectedPeerKey)
    try require(
      envelope.senderPeerKey == operation.id.senderPeerKey,
      "Envelope and operation sender differ"
    )
    try require(
      envelope.senderSequence == UInt64(bitPattern: operation.id.senderSequence),
      "Envelope and operation sequence differ"
    )
    try require(
      envelope.lamport == UInt64(bitPattern: operation.stamp.lamport),
      "Envelope and operation Lamport stamp differ"
    )
    return operation
  }

  public static func decodeProfile(_ bytes: Data, expectedPeerKey: String? = nil) throws -> UserProfile {
    try require(bytes.count <= maxProfileJsonBytes, "Profile exceeds protocol limit")
    let profile = try OperationJSON.decodeProfile(String(decoding: bytes, as: UTF8.self))
    try require(!profile.peerKey.isBlankKotlin && profile.peerKey.utf16.count <= maxPeerKeyLength)
    try require((1...24).contains(profile.displayName.utf16.count))
    try require(!profile.displayName.containsISOControl)
    try require(isApprovedWhiteboardColor(profile.colorArgb), "Profile color is not approved")
    try require(
      expectedPeerKey == nil || profile.peerKey == expectedPeerKey!,
      "Profile owner does not match the connected peer"
    )
    return profile
  }

  public static func encodeLive(
    _ preview: LivePreview, sequence: Int64, senderSessionId: String
  ) throws -> Data {
    try require((1...maxProtocolCounter).contains(sequence))
    try require(preview.tool != .hand, "Hand is a local navigation tool")
    try require(!senderSessionId.isBlankKotlin && senderSessionId.utf16.count <= maxGestureIdLength)
    try require(!preview.gestureId.isBlankKotlin && preview.gestureId.utf16.count <= maxGestureIdLength)
    try require(isApprovedWhiteboardColor(preview.colorArgb), "Live color is not approved")
    try require((1...maxLivePoints).contains(preview.points.count))
    for point in preview.points {
      try require((0...boardWidth).contains(point.x) && (0...boardHeight).contains(point.y))
    }
    let deltas = deltaEncode(preview.points)
    var protoPreview = Ditto_Whiteboard_V4_LivePreview()
    protoPreview.tool = UInt32(preview.tool.rawValue)
    protoPreview.colorArgb = preview.colorArgb
    protoPreview.coordinateDeltas = deltas.map { Int32(truncatingIfNeeded: $0) }
    protoPreview.gestureID = preview.gestureId
    var envelope = Ditto_Whiteboard_V4_LiveEnvelope()
    envelope.protocolVersion = UInt32(protocolVersion)
    envelope.boardID = boardID
    envelope.senderPeerKey = preview.peerKey
    envelope.senderSequence = UInt64(bitPattern: sequence)
    envelope.senderSessionID = senderSessionId
    envelope.preview = protoPreview
    let bytes: Data = try envelope.serializedBytes()
    return bytes
  }

  public static func decodeLive(
    _ bytes: Data,
    nowMillis: Int64,
    expectedPeerKey: String? = nil
  ) -> ProtocolDecodeResult<LivePreview> {
    do {
      if bytes.count > maxLiveFrameBytes {
        return .invalid("Live frame exceeds protocol limit")
      }
      let envelope = try Ditto_Whiteboard_V4_LiveEnvelope(serializedBytes: bytes)
      let tool = DrawingTool(rawValue: Int(envelope.preview.tool))
      if Int(envelope.protocolVersion) != protocolVersion {
        return .incompatible(Int(envelope.protocolVersion))
      } else if envelope.boardID != boardID {
        return .invalid("Unexpected board \(envelope.boardID)")
      } else if envelope.senderPeerKey.isBlankKotlin
        || envelope.senderPeerKey.utf16.count > maxPeerKeyLength
      {
        return .invalid("Invalid sender peer key")
      } else if !(1...UInt64(bitPattern: maxProtocolCounter)).contains(envelope.senderSequence) {
        return .invalid("Invalid live sequence")
      } else if envelope.senderSessionID.isBlankKotlin
        || envelope.senderSessionID.utf16.count > maxGestureIdLength
      {
        return .invalid("Invalid live sender session")
      } else if expectedPeerKey != nil && envelope.senderPeerKey != expectedPeerKey! {
        return .invalid("Live sender does not match the connected peer")
      } else if envelope.preview.gestureID.isBlankKotlin
        || envelope.preview.gestureID.utf16.count > maxGestureIdLength
      {
        return .invalid("Invalid live gesture id")
      } else if tool == nil {
        return .invalid("Unknown drawing tool")
      } else if tool == .hand {
        return .invalid("Hand is a local navigation tool")
      } else if !isApprovedWhiteboardColor(envelope.preview.colorArgb) {
        return .invalid("Live color is not approved")
      } else if !(2...(maxLivePoints * 2)).contains(envelope.preview.coordinateDeltas.count)
        || envelope.preview.coordinateDeltas.count % 2 != 0
      {
        return .invalid("Invalid live point count")
      } else {
        let points = try deltaDecode(envelope.preview.coordinateDeltas)
        if points.contains(where: { !(0...boardWidth).contains($0.x) || !(0...boardHeight).contains($0.y) }) {
          return .invalid("Live point is outside the board")
        }
        guard let tool else { return .invalid("Unknown drawing tool") }
        return .compatible(
          LivePreview(
            peerKey: envelope.senderPeerKey,
            tool: tool,
            colorArgb: envelope.preview.colorArgb,
            points: points,
            expiresAtMillis: nowMillis + 2_000,
            gestureId: envelope.preview.gestureID,
            frameSequence: Int64(bitPattern: envelope.senderSequence),
            senderSessionId: envelope.senderSessionID
          )
        )
      }
    } catch {
      return .invalid(String(describing: error))
    }
  }

  public static func stateDigest(_ state: BoardState) -> Data {
    operationSetDigest(state.operations.values)
  }

  public static func operationSetDigest(_ operations: some Collection<BoardOperation>) -> Data {
    var accumulator = Data(repeating: 0, count: sha256ByteCount)
    for operation in operations {
      xorDigestInto(&accumulator, operationStateDigest(operation))
    }
    return stateDigestFromAccumulator(operationCount: operations.count, accumulator: accumulator)
  }

  public static func snapshotBytes(_ state: BoardState) throws -> Data {
    try require(
      state.operations.count <= maxSnapshotOperations,
      "Snapshot contains too many operations"
    )
    try require(
      state.operations.values.filter({ if case .erase = $0 { true } else { false } }).count
        <= maxEraseOperations,
      "Snapshot contains too many erase operations"
    )
    let ordered = state.operations.values.sorted { first, second in
      if first.stamp != second.stamp { return first.stamp < second.stamp }
      if first.id.senderPeerKey != second.id.senderPeerKey {
        return first.id.senderPeerKey < second.id.senderPeerKey
      }
      return first.id.senderSequence < second.id.senderSequence
    }
    let operationBytes = ordered.reduce(Int64(0)) { $0 + Int64(operationJsonByteCount($1)) }
    let byteCount = snapshotDocumentByteCount(
      operationJsonBytes: operationBytes, operationCount: ordered.count
    )
    try require(byteCount <= maxTransferBytes && byteCount <= Int64(Int32.max), "Snapshot exceeds protocol byte limit")
    var document = Data()
    document.reserveCapacity(Int(byteCount))
    document.appendBigEndian(snapshotMagic)
    document.appendBigEndian(Int32(ordered.count))
    for operation in ordered {
      let bytes = Data(OperationJSON.encode(operation).utf8)
      document.appendBigEndian(Int32(bytes.count))
      document.append(bytes)
    }
    return document
  }

  public static func snapshotOperations(_ bytes: Data) throws -> [BoardOperation] {
    try require(Int64(bytes.count) <= maxTransferBytes, "Snapshot exceeds protocol byte limit")
    try require(bytes.count >= Int(snapshotHeaderBytes), "Snapshot header is truncated")
    var reader = BigEndianReader(bytes)
    try require(reader.readInt32() == snapshotMagic, "Snapshot format is not supported")
    let count = reader.readInt32() ?? -1
    try require((0...Int32(maxSnapshotOperations)).contains(count), "Snapshot contains too many operations")
    var operations: [BoardOperation] = []
    operations.reserveCapacity(Int(count))
    var operationIds = Set<OperationId>()
    var authors = Set<String>()
    var eraseCount = 0
    for _ in 0..<count {
      try require(reader.remaining >= 4, "Snapshot operation header is truncated")
      let byteCount = reader.readInt32() ?? -1
      try require(
        byteCount >= 1 && byteCount <= Int32(maxOperationJsonBytes)
          && Int64(byteCount) <= reader.remaining,
        "Snapshot operation length is invalid"
      )
      let operationBytes = reader.readBytes(Int(byteCount))
      let operation = try OperationJSON.decodeOperation(String(decoding: operationBytes, as: UTF8.self))
      try requireValidOperation(operation)
      try require(operationIds.insert(operation.id).inserted, "Snapshot contains duplicate operation ids")
      if case .erase = operation {
        eraseCount += 1
        try require(eraseCount <= maxEraseOperations, "Snapshot contains too many erase operations")
      }
      authors.insert(operation.id.senderPeerKey)
      try require(authors.count <= maxStateVectorPeers, "Snapshot contains too many authors")
      operations.append(operation)
    }
    try require(reader.remaining == 0, "Snapshot contains trailing bytes")
    return operations
  }

  public static func operationJsonByteCount(_ operation: BoardOperation) -> Int {
    OperationJSON.encode(operation).utf8.count
  }

  public static func operationStateDigest(_ operation: BoardOperation) -> Data {
    sha256(Data(OperationJSON.encode(operation).utf8))
  }

  public static func xorDigestInto(_ accumulator: inout Data, _ digest: Data) {
    precondition(accumulator.count == sha256ByteCount && digest.count == sha256ByteCount)
    for index in 0..<sha256ByteCount {
      accumulator[index] = accumulator[index] ^ digest[index]
    }
  }

  public static func stateDigestFromAccumulator(operationCount: Int, accumulator: Data) -> Data {
    precondition((0...maxSnapshotOperations).contains(operationCount))
    precondition(accumulator.count == sha256ByteCount)
    var bytes = Data()
    bytes.appendBigEndian(stateDigestMagic)
    bytes.appendBigEndian(Int32(operationCount))
    bytes.append(accumulator)
    return sha256(bytes)
  }

  public static func snapshotDocumentByteCount(operationJsonBytes: Int64, operationCount: Int) -> Int64 {
    snapshotHeaderBytes + operationJsonBytes + Int64(operationCount) * 4
  }

  public static func validateOperation(_ operation: BoardOperation) throws {
    try requireValidOperation(operation)
  }

  public static func sha256(_ bytes: Data) -> Data {
    Data(SHA256.hash(data: bytes))
  }

  static func deltaEncode(_ points: [LogicalPoint]) -> [Int] {
    var previousX = 0
    var previousY = 0
    var deltas: [Int] = []
    deltas.reserveCapacity(points.count * 2)
    for point in points {
      deltas.append(point.x - previousX)
      deltas.append(point.y - previousY)
      previousX = point.x
      previousY = point.y
    }
    return deltas
  }

  static func deltaDecode(_ values: [Int32]) throws -> [LogicalPoint] {
    if values.count % 2 != 0 { return [] }
    var x: Int64 = 0
    var y: Int64 = 0
    var points: [LogicalPoint] = []
    points.reserveCapacity(values.count / 2)
    for index in stride(from: 0, to: values.count, by: 2) {
      x += Int64(values[index])
      y += Int64(values[index + 1])
      try require(
        (Int64(Int32.min)...Int64(Int32.max)).contains(x)
          && (Int64(Int32.min)...Int64(Int32.max)).contains(y),
        "Live coordinate overflow"
      )
      points.append(LogicalPoint(x: Int(x), y: Int(y)))
    }
    return points
  }
}

public enum ProtocolDecodeResult<Value> {
  case compatible(Value)
  case incompatible(Int)
  case invalid(String)
}

extension ProtocolDecodeResult: Equatable where Value: Equatable {}
extension ProtocolDecodeResult: Sendable where Value: Sendable {}

public func shouldOfferSnapshot(
  local: BoardState,
  remoteDigest: Data,
  localDigest: Data? = nil
) -> Bool {
  if local.operations.isEmpty { return false }
  return (localDigest ?? WhiteboardProtocol.stateDigest(local)) != remoteDigest
}

public func shouldSendReciprocalSnapshot(
  receivedMaterialDigest: Data,
  mergedLocalDigest: Data
) -> Bool {
  receivedMaterialDigest != mergedLocalDigest
}

public func requireValidOperation(
  _ operation: BoardOperation,
  expectedPeerKey: String? = nil
) throws {
  func require(_ condition: Bool, _ message: String = "") throws {
    if !condition { throw WhiteboardCoreError.requirementFailed(message) }
  }
  func requireInBounds(_ point: LogicalPoint) throws {
    try require(
      (0...boardWidth).contains(point.x) && (0...boardHeight).contains(point.y),
      "Point is outside the board"
    )
  }

  let id = operation.id
  let stamp = operation.stamp
  try require(
    !id.senderPeerKey.isBlankKotlin && id.senderPeerKey.utf16.count <= WhiteboardProtocol.maxPeerKeyLength,
    "Invalid operation sender"
  )
  try require((1...WhiteboardProtocol.maxProtocolCounter).contains(id.senderSequence), "Invalid operation sequence")
  try require((1...WhiteboardProtocol.maxProtocolCounter).contains(stamp.lamport), "Invalid Lamport stamp")
  try require(
    stamp.peerKey == id.senderPeerKey && stamp.senderSequence == id.senderSequence,
    "Operation id and stamp differ"
  )
  try require(
    expectedPeerKey == nil || id.senderPeerKey == expectedPeerKey!,
    "Operation sender does not match the connected peer"
  )

  func requireConsistent(_ object: BoardObject) throws {
    try require(
      object.id.origin == operation.id && object.id.fragmentDigest.isEmpty,
      "Object id does not match its commit"
    )
    try require(object.stamp == operation.stamp, "Object stamp does not match its commit")
    try require(isApprovedWhiteboardColor(object.colorArgb), "Object color is not approved")
    switch object {
    case .freehand(let freehand):
      try require((1...WhiteboardProtocol.maxStrokeWidth).contains(freehand.width))
      try require((1...WhiteboardProtocol.maxOperationPoints).contains(freehand.points.count))
      for point in freehand.points { try requireInBounds(point) }
    case .line(let line):
      try require((1...WhiteboardProtocol.maxStrokeWidth).contains(line.width))
      try requireInBounds(line.start)
      try requireInBounds(line.end)
    case .rectangle(let rectangle):
      try require((1...WhiteboardProtocol.maxStrokeWidth).contains(rectangle.width))
      try requireInBounds(rectangle.start)
      try requireInBounds(rectangle.end)
      try require(
        rectangle.start.x != rectangle.end.x && rectangle.start.y != rectangle.end.y,
        "Rectangle must have visible area"
      )
    case .ellipse(let ellipse):
      try require((1...WhiteboardProtocol.maxStrokeWidth).contains(ellipse.width))
      try requireInBounds(ellipse.start)
      try requireInBounds(ellipse.end)
      try require(
        ellipse.start.x != ellipse.end.x && ellipse.start.y != ellipse.end.y,
        "Ellipse must have visible area"
      )
    case .text(let text):
      try require((1...WhiteboardProtocol.maxStrokeWidth).contains(text.size))
      try require(BoardTextFont.allCases.contains(text.font), "Unsupported text font")
      try require((1...WhiteboardProtocol.maxTextLength).contains(text.text.utf16.count))
      try require(!text.text.containsISOControl, "Text contains control characters")
      try requireInBounds(text.anchor)
    }
  }

  switch operation {
  case .commit(let commit):
    try require(
      !commit.gestureId.isBlankKotlin && commit.gestureId.utf16.count <= WhiteboardProtocol.maxGestureIdLength,
      "Invalid gesture id"
    )
    try requireConsistent(commit.boardObject)
  case .erase(let erase):
    try require((1...WhiteboardProtocol.maxEraserRadius).contains(erase.radius))
    try require((1...WhiteboardProtocol.maxEraserPoints).contains(erase.path.count))
    try require(
      !erase.gestureId.isBlankKotlin && erase.gestureId.utf16.count <= WhiteboardProtocol.maxGestureIdLength,
      "Invalid gesture id"
    )
    for point in erase.path { try requireInBounds(point) }
  case .clear:
    break
  case .profileUpdate(let update):
    try require(
      update.profile.peerKey == id.senderPeerKey,
      "Profile owner does not match its operation"
    )
    try require((1...24).contains(update.profile.displayName.utf16.count))
    try require(!update.profile.displayName.containsISOControl)
    try require(
      isApprovedWhiteboardColor(update.profile.colorArgb),
      "Profile color is not approved"
    )
  }
}

extension Ditto_Whiteboard_V4_Envelope {
  public func operationStamp() -> OperationStamp {
    OperationStamp(
      lamport: Int64(bitPattern: lamport),
      peerKey: senderPeerKey,
      senderSequence: Int64(bitPattern: senderSequence)
    )
  }
}

struct BigEndianReader {
  private let bytes: Data
  private(set) var offset: Int = 0

  init(_ bytes: Data) {
    self.bytes = bytes
  }

  var remaining: Int64 { Int64(bytes.count - offset) }

  mutating func readInt32() -> Int32? {
    guard bytes.count - offset >= 4 else { return nil }
    let value = Int32(bitPattern:
      UInt32(bytes[offset]) << 24
        | UInt32(bytes[offset + 1]) << 16
        | UInt32(bytes[offset + 2]) << 8
        | UInt32(bytes[offset + 3])
    )
    offset += 4
    return value
  }

  mutating func readBytes(_ count: Int) -> Data {
    let end = Swift.min(offset + count, bytes.count)
    let slice = bytes[offset..<end]
    offset = end
    return Data(slice)
  }
}

extension Data {
  mutating func appendBigEndian(_ value: Int32) {
    var bigEndian = UInt32(bitPattern: value).bigEndian
    append(contentsOf: Swift.withUnsafeBytes(of: &bigEndian) { Array($0) })
  }
}
