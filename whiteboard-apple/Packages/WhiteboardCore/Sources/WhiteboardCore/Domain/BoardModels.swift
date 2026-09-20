import Foundation

public let boardID: String = "demo"
public let boardWidth: Int = 3840
public let boardHeight: Int = 2160
public let defaultStrokeWidth: Int = 10
public let defaultEraserRadius: Int = 40
public let defaultTextSize: Int = 48
public let maxOperationCounter: Int64 = 1 << 60
public let maxRenderedBoardObjects: Int = 512

public struct LogicalPoint: Equatable, Hashable, Sendable, Codable {
  public var x: Int
  public var y: Int

  public init(x: Int, y: Int) {
    self.x = x
    self.y = y
  }

  public func clamped() -> LogicalPoint {
    LogicalPoint(x: min(max(x, 0), boardWidth), y: min(max(y, 0), boardHeight))
  }
}

public struct OperationId: Equatable, Hashable, Sendable, Codable {
  public var senderPeerKey: String
  public var senderSequence: Int64

  public init(senderPeerKey: String, senderSequence: Int64) {
    self.senderPeerKey = senderPeerKey
    self.senderSequence = senderSequence
  }
}

public struct ObjectId: Equatable, Hashable, Sendable, Codable {
  public var origin: OperationId
  public var fragmentDigest: String

  public init(origin: OperationId, fragmentDigest: String = "") {
    self.origin = origin
    self.fragmentDigest = fragmentDigest
  }

  public init(from decoder: any Decoder) throws {
    let container = try decoder.container(keyedBy: CodingKeys.self)
    origin = try container.decode(OperationId.self, forKey: .origin)
    fragmentDigest = try container.decodeIfPresent(String.self, forKey: .fragmentDigest) ?? ""
  }
}

public struct OperationStamp: Equatable, Hashable, Sendable, Codable, Comparable {
  public var lamport: Int64
  public var peerKey: String
  public var senderSequence: Int64

  public init(lamport: Int64, peerKey: String, senderSequence: Int64) {
    self.lamport = lamport
    self.peerKey = peerKey
    self.senderSequence = senderSequence
  }

  public static func < (lhs: OperationStamp, rhs: OperationStamp) -> Bool {
    if lhs.lamport != rhs.lamport { return lhs.lamport < rhs.lamport }
    if lhs.peerKey != rhs.peerKey { return lhs.peerKey < rhs.peerKey }
    return lhs.senderSequence < rhs.senderSequence
  }
}

public struct UserProfile: Equatable, Hashable, Sendable, Codable {
  public var peerKey: String
  public var displayName: String
  public var colorArgb: Int32

  public init(peerKey: String, displayName: String, colorArgb: Int32) throws {
    guard (1...24).contains(displayName.utf16.count) else {
      throw WhiteboardCoreError.requirementFailed("Display name must be 1–24 characters")
    }
    guard !displayName.containsISOControl else {
      throw WhiteboardCoreError.requirementFailed("Display name cannot contain control characters")
    }
    self.peerKey = peerKey
    self.displayName = displayName
    self.colorArgb = colorArgb
  }

  public init(from decoder: any Decoder) throws {
    let container = try decoder.container(keyedBy: CodingKeys.self)
    let peerKey = try container.decode(String.self, forKey: .peerKey)
    let displayName = try container.decode(String.self, forKey: .displayName)
    let colorArgb = try container.decode(Int32.self, forKey: .colorArgb)
    try self.init(peerKey: peerKey, displayName: displayName, colorArgb: colorArgb)
  }
}

public enum BoardObject: Equatable, Hashable, Sendable {
  case freehand(Freehand)
  case line(Line)
  case rectangle(Rectangle)
  case ellipse(Ellipse)
  case text(Text)

  public struct Freehand: Equatable, Hashable, Sendable {
    public var id: ObjectId
    public var stamp: OperationStamp
    public var colorArgb: Int32
    public var width: Int
    public var points: [LogicalPoint]

    public init(
      id: ObjectId, stamp: OperationStamp, colorArgb: Int32,
      width: Int = defaultStrokeWidth, points: [LogicalPoint]
    ) {
      self.id = id
      self.stamp = stamp
      self.colorArgb = colorArgb
      self.width = width
      self.points = points
    }
  }

  public struct Line: Equatable, Hashable, Sendable {
    public var id: ObjectId
    public var stamp: OperationStamp
    public var colorArgb: Int32
    public var width: Int
    public var start: LogicalPoint
    public var end: LogicalPoint

    public init(
      id: ObjectId, stamp: OperationStamp, colorArgb: Int32,
      width: Int = defaultStrokeWidth, start: LogicalPoint, end: LogicalPoint
    ) {
      self.id = id
      self.stamp = stamp
      self.colorArgb = colorArgb
      self.width = width
      self.start = start
      self.end = end
    }
  }

  public struct Rectangle: Equatable, Hashable, Sendable {
    public var id: ObjectId
    public var stamp: OperationStamp
    public var colorArgb: Int32
    public var width: Int
    public var start: LogicalPoint
    public var end: LogicalPoint

    public init(
      id: ObjectId, stamp: OperationStamp, colorArgb: Int32,
      width: Int = defaultStrokeWidth, start: LogicalPoint, end: LogicalPoint
    ) {
      self.id = id
      self.stamp = stamp
      self.colorArgb = colorArgb
      self.width = width
      self.start = start
      self.end = end
    }
  }

  public struct Ellipse: Equatable, Hashable, Sendable {
    public var id: ObjectId
    public var stamp: OperationStamp
    public var colorArgb: Int32
    public var width: Int
    public var start: LogicalPoint
    public var end: LogicalPoint

    public init(
      id: ObjectId, stamp: OperationStamp, colorArgb: Int32,
      width: Int = defaultStrokeWidth, start: LogicalPoint, end: LogicalPoint
    ) {
      self.id = id
      self.stamp = stamp
      self.colorArgb = colorArgb
      self.width = width
      self.start = start
      self.end = end
    }
  }

  public struct Text: Equatable, Hashable, Sendable {
    public var id: ObjectId
    public var stamp: OperationStamp
    public var colorArgb: Int32
    public var anchor: LogicalPoint
    public var text: String
    public var size: Int

    public init(
      id: ObjectId, stamp: OperationStamp, colorArgb: Int32,
      anchor: LogicalPoint, text: String, size: Int = defaultTextSize
    ) {
      self.id = id
      self.stamp = stamp
      self.colorArgb = colorArgb
      self.anchor = anchor
      self.text = text
      self.size = size
    }
  }

  public var id: ObjectId {
    switch self {
    case .freehand(let object): return object.id
    case .line(let object): return object.id
    case .rectangle(let object): return object.id
    case .ellipse(let object): return object.id
    case .text(let object): return object.id
    }
  }

  public var stamp: OperationStamp {
    switch self {
    case .freehand(let object): return object.stamp
    case .line(let object): return object.stamp
    case .rectangle(let object): return object.stamp
    case .ellipse(let object): return object.stamp
    case .text(let object): return object.stamp
    }
  }

  public var colorArgb: Int32 {
    switch self {
    case .freehand(let object): return object.colorArgb
    case .line(let object): return object.colorArgb
    case .rectangle(let object): return object.colorArgb
    case .ellipse(let object): return object.colorArgb
    case .text(let object): return object.colorArgb
    }
  }
}

public enum BoardOperation: Equatable, Hashable, Sendable {
  case commit(Commit)
  case erase(Erase)
  case clear(Clear)
  case profileUpdate(ProfileUpdate)

  public struct Commit: Equatable, Hashable, Sendable {
    public var id: OperationId
    public var stamp: OperationStamp
    public var boardObject: BoardObject
    public var gestureId: String

    public init(
      id: OperationId, stamp: OperationStamp, boardObject: BoardObject,
      gestureId: String? = nil
    ) {
      self.id = id
      self.stamp = stamp
      self.boardObject = boardObject
      self.gestureId = gestureId ?? "\(id.senderPeerKey):\(id.senderSequence)"
    }
  }

  public struct Erase: Equatable, Hashable, Sendable {
    public var id: OperationId
    public var stamp: OperationStamp
    public var path: [LogicalPoint]
    public var radius: Int
    public var gestureId: String

    public init(
      id: OperationId, stamp: OperationStamp, path: [LogicalPoint],
      radius: Int = defaultEraserRadius, gestureId: String? = nil
    ) {
      self.id = id
      self.stamp = stamp
      self.path = path
      self.radius = radius
      self.gestureId = gestureId ?? "\(id.senderPeerKey):\(id.senderSequence)"
    }
  }

  public struct Clear: Equatable, Hashable, Sendable {
    public var id: OperationId
    public var stamp: OperationStamp

    public init(id: OperationId, stamp: OperationStamp) {
      self.id = id
      self.stamp = stamp
    }
  }

  public struct ProfileUpdate: Equatable, Hashable, Sendable {
    public var id: OperationId
    public var stamp: OperationStamp
    public var profile: UserProfile

    public init(id: OperationId, stamp: OperationStamp, profile: UserProfile) {
      self.id = id
      self.stamp = stamp
      self.profile = profile
    }
  }

  public var id: OperationId {
    switch self {
    case .commit(let operation): return operation.id
    case .erase(let operation): return operation.id
    case .clear(let operation): return operation.id
    case .profileUpdate(let operation): return operation.id
    }
  }

  public var stamp: OperationStamp {
    switch self {
    case .commit(let operation): return operation.stamp
    case .erase(let operation): return operation.stamp
    case .clear(let operation): return operation.stamp
    case .profileUpdate(let operation): return operation.stamp
    }
  }
}

public struct BoardState: Equatable, Sendable {
  public var objects: [ObjectId: BoardObject]
  public var erasures: [BoardOperation.Erase]
  public var clearWatermark: OperationStamp?
  public var profiles: [String: UserProfile]
  public var highWaterMarks: [String: Int64]
  public var operations: [OperationId: BoardOperation]
  public var latestStamp: OperationStamp?
  public var renderCapacityReachedSinceClear: Bool

  public init(
    objects: [ObjectId: BoardObject] = [:],
    erasures: [BoardOperation.Erase] = [],
    clearWatermark: OperationStamp? = nil,
    profiles: [String: UserProfile] = [:],
    highWaterMarks: [String: Int64] = [:],
    operations: [OperationId: BoardOperation] = [:],
    latestStamp: OperationStamp? = nil,
    renderCapacityReachedSinceClear: Bool = false
  ) {
    self.objects = objects
    self.erasures = erasures
    self.clearWatermark = clearWatermark
    self.profiles = profiles
    self.highWaterMarks = highWaterMarks
    self.operations = operations
    self.latestStamp = latestStamp
    self.renderCapacityReachedSinceClear = renderCapacityReachedSinceClear
  }
}

public enum DrawingTool: Int, Sendable, CaseIterable, Codable {
  case pen = 0
  case line = 1
  case rectangle = 2
  case ellipse = 3
  case text = 4
  case eraser = 5
}

public struct LivePreview: Equatable, Sendable {
  public var peerKey: String
  public var tool: DrawingTool
  public var colorArgb: Int32
  public var points: [LogicalPoint]
  public var expiresAtMillis: Int64
  public var gestureId: String
  public var frameSequence: Int64
  public var senderSessionId: String

  public init(
    peerKey: String, tool: DrawingTool, colorArgb: Int32, points: [LogicalPoint],
    expiresAtMillis: Int64, gestureId: String = "", frameSequence: Int64 = 0,
    senderSessionId: String = ""
  ) {
    self.peerKey = peerKey
    self.tool = tool
    self.colorArgb = colorArgb
    self.points = points
    self.expiresAtMillis = expiresAtMillis
    self.gestureId = gestureId
    self.frameSequence = frameSequence
    self.senderSessionId = senderSessionId
  }
}

public enum WhiteboardCoreError: Error, Equatable {
  case requirementFailed(String)
}

extension String {
  var containsISOControl: Bool {
    unicodeScalars.contains { $0.value <= 0x1F || (0x7F...0x9F).contains($0.value) }
  }

  var isBlankKotlin: Bool {
    allSatisfy(\.isWhitespace)
  }
}

// The Kotlin app resolves equivocation by comparing kotlinx-serialization JSON, which emits the
// "kind" discriminator first and remaining keys in declaration order. Swift uses
// CanonicalJSONWriter, which emits byte-identical output: key order MATCHES Android (kind first,
// then declaration order), so both platforms compare and hash the exact same canonical bytes.
public func canonicalOperation(_ first: BoardOperation, _ second: BoardOperation) -> BoardOperation {
  precondition(first.id == second.id)
  if first == second { return first }
  let firstKey = Array(canonicalOperationJSONString(first).utf16)
  let secondKey = Array(canonicalOperationJSONString(second).utf16)
  return firstKey.lexicographicallyPrecedes(secondKey, by: <) || firstKey == secondKey
    ? first
    : second
}
