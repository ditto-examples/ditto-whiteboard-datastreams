import Foundation

// JSON wire coding for operations and profiles. Byte-compatible with the Android
// kotlinx-serialization configuration (`Json { encodeDefaults = true; classDiscriminator =
// "kind" }`): the "kind" discriminator is emitted FIRST, remaining keys follow Kotlin
// declaration order, separators are compact, defaults are always encoded, and strings are
// escaped with kotlinx rules (`\"`, `\\`, `\b`, `\t`, `\n`, `\f`, `\r`, `\u00xx` lowercase for
// other control chars; `/` and non-ASCII pass through). Byte-identical output is load-bearing:
// both platforms SHA-256 these bytes for state digests and the equivocation tie-breaker.
// Decoding stays tolerant (any key order, unknown keys) via JSONDecoder.
enum OperationJSON {
  static let decoder = JSONDecoder()

  static func encode(_ operation: BoardOperation) -> String {
    do {
      return try CanonicalJSONWriter.encode(operation)
    } catch {
      preconditionFailure("BoardOperation JSON encoding failed: \(error)")
    }
  }

  static func encode(_ profile: UserProfile) -> String {
    do {
      return try CanonicalJSONWriter.encode(profile)
    } catch {
      preconditionFailure("UserProfile JSON encoding failed: \(error)")
    }
  }

  static func decodeOperation(_ string: String) throws -> BoardOperation {
    try decoder.decode(BoardOperation.self, from: Data(string.utf8))
  }

  static func decodeProfile(_ string: String) throws -> UserProfile {
    try decoder.decode(UserProfile.self, from: Data(string.utf8))
  }
}

// Ordered-object JSON encoder. JSONEncoder cannot reproduce kotlinx output because it either
// emits keys in (unspecified) insertion order or sorts them; this writer preserves the exact
// order the Codable `encode(to:)` implementations append keys.
enum CanonicalJSONWriter {
  static func encode(_ value: some Encodable) throws -> String {
    let encoder = CanonicalJSONEncoder()
    try value.encode(to: encoder)
    guard encoder.stack.count == 1, let root = encoder.stack.first else {
      throw EncodingError.invalidValue(
        value,
        EncodingError.Context(
          codingPath: [], debugDescription: "Top-level value did not produce a JSON value"
        )
      )
    }
    var output = String()
    write(root, into: &output)
    return output
  }

  private static func write(_ value: CanonicalJSONValue, into output: inout String) {
    switch value {
    case .object(let box):
      output.append("{")
      var first = true
      for entry in box.entries {
        if !first { output.append(",") }
        first = false
        writeEscaped(entry.key, into: &output)
        output.append(":")
        write(entry.value, into: &output)
      }
      output.append("}")
    case .array(let box):
      output.append("[")
      var first = true
      for element in box.elements {
        if !first { output.append(",") }
        first = false
        write(element, into: &output)
      }
      output.append("]")
    case .string(let string):
      writeEscaped(string, into: &output)
    case .integer(let integer):
      output.append(String(integer))
    case .boolean(let boolean):
      output.append(boolean ? "true" : "false")
    case .rawNumber(let text):
      output.append(text)
    }
  }

  private static let hexDigits: [Character] = Array("0123456789abcdef")

  private static func writeEscaped(_ string: String, into output: inout String) {
    output.append("\"")
    for scalar in string.unicodeScalars {
      switch scalar.value {
      case 0x22: output.append("\\\"")
      case 0x5C: output.append("\\\\")
      case 0x08: output.append("\\b")
      case 0x09: output.append("\\t")
      case 0x0A: output.append("\\n")
      case 0x0C: output.append("\\f")
      case 0x0D: output.append("\\r")
      case 0x00...0x1F:
        output.append("\\u00")
        output.append(hexDigits[Int((scalar.value >> 4) & 0xF)])
        output.append(hexDigits[Int(scalar.value & 0xF)])
      default:
        output.unicodeScalars.append(scalar)
      }
    }
    output.append("\"")
  }
}

private indirect enum CanonicalJSONValue {
  case object(CanonicalJSONObjectBox)
  case array(CanonicalJSONArrayBox)
  case string(String)
  case integer(Int64)
  case boolean(Bool)
  case rawNumber(String)
}

private final class CanonicalJSONObjectBox {
  var entries: [(key: String, value: CanonicalJSONValue)] = []
}

private final class CanonicalJSONArrayBox {
  var elements: [CanonicalJSONValue] = []
}

private final class CanonicalJSONEncoder: Encoder {
  var codingPath: [any CodingKey] = []
  var userInfo: [CodingUserInfoKey: Any] = [:]
  var stack: [CanonicalJSONValue] = []

  func container<Key: CodingKey>(keyedBy keyType: Key.Type) -> KeyedEncodingContainer<Key> {
    if case .object(let box) = stack.last {
      return KeyedEncodingContainer(CanonicalKeyedEncodingContainer<Key>(box: box))
    }
    let box = CanonicalJSONObjectBox()
    stack.append(.object(box))
    return KeyedEncodingContainer(CanonicalKeyedEncodingContainer<Key>(box: box))
  }

  func unkeyedContainer() -> any UnkeyedEncodingContainer {
    if case .array(let box) = stack.last {
      return CanonicalUnkeyedEncodingContainer(box: box)
    }
    let box = CanonicalJSONArrayBox()
    stack.append(.array(box))
    return CanonicalUnkeyedEncodingContainer(box: box)
  }

  func singleValueContainer() -> any SingleValueEncodingContainer {
    CanonicalSingleValueEncodingContainer(encoder: self)
  }

  static func encodeChild(_ value: some Encodable) throws -> CanonicalJSONValue {
    let child = CanonicalJSONEncoder()
    try value.encode(to: child)
    guard child.stack.count == 1, let encoded = child.stack.first else {
      throw EncodingError.invalidValue(
        value,
        EncodingError.Context(
          codingPath: [], debugDescription: "Nested value did not produce a JSON value"
        )
      )
    }
    return encoded
  }
}

private struct CanonicalKeyedEncodingContainer<Key: CodingKey>: KeyedEncodingContainerProtocol {
  let box: CanonicalJSONObjectBox
  var codingPath: [any CodingKey] { [] }

  mutating func encodeNil(forKey key: Key) throws {}
  mutating func encode(_ value: Bool, forKey key: Key) throws {
    box.entries.append((key.stringValue, .boolean(value)))
  }
  mutating func encode(_ value: String, forKey key: Key) throws {
    box.entries.append((key.stringValue, .string(value)))
  }
  mutating func encode(_ value: Double, forKey key: Key) throws {
    box.entries.append((key.stringValue, .rawNumber(String(value))))
  }
  mutating func encode(_ value: Float, forKey key: Key) throws {
    box.entries.append((key.stringValue, .rawNumber(String(value))))
  }
  mutating func encode(_ value: Int, forKey key: Key) throws {
    box.entries.append((key.stringValue, .integer(Int64(value))))
  }
  mutating func encode(_ value: Int8, forKey key: Key) throws {
    box.entries.append((key.stringValue, .integer(Int64(value))))
  }
  mutating func encode(_ value: Int16, forKey key: Key) throws {
    box.entries.append((key.stringValue, .integer(Int64(value))))
  }
  mutating func encode(_ value: Int32, forKey key: Key) throws {
    box.entries.append((key.stringValue, .integer(Int64(value))))
  }
  mutating func encode(_ value: Int64, forKey key: Key) throws {
    box.entries.append((key.stringValue, .integer(value)))
  }
  mutating func encode(_ value: UInt, forKey key: Key) throws {
    box.entries.append((key.stringValue, .integer(Int64(value))))
  }
  mutating func encode(_ value: UInt8, forKey key: Key) throws {
    box.entries.append((key.stringValue, .integer(Int64(value))))
  }
  mutating func encode(_ value: UInt16, forKey key: Key) throws {
    box.entries.append((key.stringValue, .integer(Int64(value))))
  }
  mutating func encode(_ value: UInt32, forKey key: Key) throws {
    box.entries.append((key.stringValue, .integer(Int64(value))))
  }
  mutating func encode(_ value: UInt64, forKey key: Key) throws {
    box.entries.append((key.stringValue, .integer(Int64(bitPattern: value))))
  }
  mutating func encode<T: Encodable>(_ value: T, forKey key: Key) throws {
    box.entries.append((key.stringValue, try CanonicalJSONEncoder.encodeChild(value)))
  }
  mutating func nestedContainer<NestedKey: CodingKey>(
    keyedBy keyType: NestedKey.Type, forKey key: Key
  ) -> KeyedEncodingContainer<NestedKey> {
    let nested = CanonicalJSONObjectBox()
    box.entries.append((key.stringValue, .object(nested)))
    return KeyedEncodingContainer(CanonicalKeyedEncodingContainer<NestedKey>(box: nested))
  }
  mutating func nestedUnkeyedContainer(forKey key: Key) -> any UnkeyedEncodingContainer {
    let nested = CanonicalJSONArrayBox()
    box.entries.append((key.stringValue, .array(nested)))
    return CanonicalUnkeyedEncodingContainer(box: nested)
  }
  mutating func superEncoder() -> any Encoder {
    let child = CanonicalJSONEncoder()
    _ = child.container(keyedBy: Key.self)
    if let value = child.stack.last { box.entries.append(("super", value)) }
    return child
  }
  mutating func superEncoder(forKey key: Key) -> any Encoder {
    let child = CanonicalJSONEncoder()
    _ = child.container(keyedBy: Key.self)
    if let value = child.stack.last { box.entries.append((key.stringValue, value)) }
    return child
  }
}

private struct CanonicalUnkeyedEncodingContainer: UnkeyedEncodingContainer {
  let box: CanonicalJSONArrayBox
  var codingPath: [any CodingKey] { [] }
  var count: Int { box.elements.count }

  mutating func encodeNil() throws {}
  mutating func encode(_ value: Bool) throws { box.elements.append(.boolean(value)) }
  mutating func encode(_ value: String) throws { box.elements.append(.string(value)) }
  mutating func encode(_ value: Double) throws { box.elements.append(.rawNumber(String(value))) }
  mutating func encode(_ value: Float) throws { box.elements.append(.rawNumber(String(value))) }
  mutating func encode(_ value: Int) throws { box.elements.append(.integer(Int64(value))) }
  mutating func encode(_ value: Int8) throws { box.elements.append(.integer(Int64(value))) }
  mutating func encode(_ value: Int16) throws { box.elements.append(.integer(Int64(value))) }
  mutating func encode(_ value: Int32) throws { box.elements.append(.integer(Int64(value))) }
  mutating func encode(_ value: Int64) throws { box.elements.append(.integer(value)) }
  mutating func encode(_ value: UInt) throws { box.elements.append(.integer(Int64(value))) }
  mutating func encode(_ value: UInt8) throws { box.elements.append(.integer(Int64(value))) }
  mutating func encode(_ value: UInt16) throws { box.elements.append(.integer(Int64(value))) }
  mutating func encode(_ value: UInt32) throws { box.elements.append(.integer(Int64(value))) }
  mutating func encode(_ value: UInt64) throws {
    box.elements.append(.integer(Int64(bitPattern: value)))
  }
  mutating func encode<T: Encodable>(_ value: T) throws {
    box.elements.append(try CanonicalJSONEncoder.encodeChild(value))
  }
  mutating func nestedContainer<NestedKey: CodingKey>(
    keyedBy keyType: NestedKey.Type
  ) -> KeyedEncodingContainer<NestedKey> {
    let nested = CanonicalJSONObjectBox()
    box.elements.append(.object(nested))
    return KeyedEncodingContainer(CanonicalKeyedEncodingContainer<NestedKey>(box: nested))
  }
  mutating func nestedUnkeyedContainer() -> any UnkeyedEncodingContainer {
    let nested = CanonicalJSONArrayBox()
    box.elements.append(.array(nested))
    return CanonicalUnkeyedEncodingContainer(box: nested)
  }
  mutating func superEncoder() -> any Encoder {
    let child = CanonicalJSONEncoder()
    _ = child.container(keyedBy: AnyCodingKey.self)
    if let value = child.stack.last { box.elements.append(value) }
    return child
  }
}

private struct AnyCodingKey: CodingKey {
  var stringValue: String
  var intValue: Int?
  init?(stringValue: String) { self.stringValue = stringValue }
  init?(intValue: Int) {
    self.stringValue = String(intValue)
    self.intValue = intValue
  }
}

private struct CanonicalSingleValueEncodingContainer: SingleValueEncodingContainer {
  let encoder: CanonicalJSONEncoder
  var codingPath: [any CodingKey] { [] }

  mutating func encodeNil() throws {}
  mutating func encode(_ value: Bool) throws { encoder.stack.append(.boolean(value)) }
  mutating func encode(_ value: String) throws { encoder.stack.append(.string(value)) }
  mutating func encode(_ value: Double) throws {
    encoder.stack.append(.rawNumber(String(value)))
  }
  mutating func encode(_ value: Float) throws {
    encoder.stack.append(.rawNumber(String(value)))
  }
  mutating func encode(_ value: Int) throws { encoder.stack.append(.integer(Int64(value))) }
  mutating func encode(_ value: Int8) throws { encoder.stack.append(.integer(Int64(value))) }
  mutating func encode(_ value: Int16) throws { encoder.stack.append(.integer(Int64(value))) }
  mutating func encode(_ value: Int32) throws { encoder.stack.append(.integer(Int64(value))) }
  mutating func encode(_ value: Int64) throws { encoder.stack.append(.integer(value)) }
  mutating func encode(_ value: UInt) throws { encoder.stack.append(.integer(Int64(value))) }
  mutating func encode(_ value: UInt8) throws { encoder.stack.append(.integer(Int64(value))) }
  mutating func encode(_ value: UInt16) throws { encoder.stack.append(.integer(Int64(value))) }
  mutating func encode(_ value: UInt32) throws { encoder.stack.append(.integer(Int64(value))) }
  mutating func encode(_ value: UInt64) throws {
    encoder.stack.append(.integer(Int64(bitPattern: value)))
  }
  mutating func encode<T: Encodable>(_ value: T) throws { try value.encode(to: encoder) }
}

func canonicalOperationJSONString(_ operation: BoardOperation) -> String {
  OperationJSON.encode(operation)
}

func canonicalJSONString(_ value: some Encodable) -> String {
  do {
    return try CanonicalJSONWriter.encode(value)
  } catch {
    preconditionFailure("Canonical JSON encoding failed: \(error)")
  }
}

private enum KindDiscriminator: String, Codable {
  case freehand, line, rectangle, ellipse, text
  case commit, erase, clear, profile
}

private struct KindProbe: Decodable {
  let kind: String
}

extension BoardObject.Freehand: Codable {
  private enum CodingKeys: String, CodingKey {
    case id, stamp, colorArgb, width, points
  }

  public func encode(to encoder: any Encoder) throws {
    var container = encoder.container(keyedBy: CodingKeys.self)
    try container.encode(id, forKey: .id)
    try container.encode(stamp, forKey: .stamp)
    try container.encode(colorArgb, forKey: .colorArgb)
    try container.encode(width, forKey: .width)
    try container.encode(points, forKey: .points)
  }

  public init(from decoder: any Decoder) throws {
    let container = try decoder.container(keyedBy: CodingKeys.self)
    self.init(
      id: try container.decode(ObjectId.self, forKey: .id),
      stamp: try container.decode(OperationStamp.self, forKey: .stamp),
      colorArgb: try container.decode(Int32.self, forKey: .colorArgb),
      width: try container.decodeIfPresent(Int.self, forKey: .width) ?? defaultStrokeWidth,
      points: try container.decode([LogicalPoint].self, forKey: .points)
    )
  }
}

extension BoardObject.Line: Codable {
  private enum CodingKeys: String, CodingKey {
    case id, stamp, colorArgb, width, start, end
  }

  public func encode(to encoder: any Encoder) throws {
    var container = encoder.container(keyedBy: CodingKeys.self)
    try container.encode(id, forKey: .id)
    try container.encode(stamp, forKey: .stamp)
    try container.encode(colorArgb, forKey: .colorArgb)
    try container.encode(width, forKey: .width)
    try container.encode(start, forKey: .start)
    try container.encode(end, forKey: .end)
  }

  public init(from decoder: any Decoder) throws {
    let container = try decoder.container(keyedBy: CodingKeys.self)
    self.init(
      id: try container.decode(ObjectId.self, forKey: .id),
      stamp: try container.decode(OperationStamp.self, forKey: .stamp),
      colorArgb: try container.decode(Int32.self, forKey: .colorArgb),
      width: try container.decodeIfPresent(Int.self, forKey: .width) ?? defaultStrokeWidth,
      start: try container.decode(LogicalPoint.self, forKey: .start),
      end: try container.decode(LogicalPoint.self, forKey: .end)
    )
  }
}

extension BoardObject.Rectangle: Codable {
  private enum CodingKeys: String, CodingKey {
    case id, stamp, colorArgb, width, start, end
  }

  public func encode(to encoder: any Encoder) throws {
    var container = encoder.container(keyedBy: CodingKeys.self)
    try container.encode(id, forKey: .id)
    try container.encode(stamp, forKey: .stamp)
    try container.encode(colorArgb, forKey: .colorArgb)
    try container.encode(width, forKey: .width)
    try container.encode(start, forKey: .start)
    try container.encode(end, forKey: .end)
  }

  public init(from decoder: any Decoder) throws {
    let container = try decoder.container(keyedBy: CodingKeys.self)
    self.init(
      id: try container.decode(ObjectId.self, forKey: .id),
      stamp: try container.decode(OperationStamp.self, forKey: .stamp),
      colorArgb: try container.decode(Int32.self, forKey: .colorArgb),
      width: try container.decodeIfPresent(Int.self, forKey: .width) ?? defaultStrokeWidth,
      start: try container.decode(LogicalPoint.self, forKey: .start),
      end: try container.decode(LogicalPoint.self, forKey: .end)
    )
  }
}

extension BoardObject.Ellipse: Codable {
  private enum CodingKeys: String, CodingKey {
    case id, stamp, colorArgb, width, start, end
  }

  public func encode(to encoder: any Encoder) throws {
    var container = encoder.container(keyedBy: CodingKeys.self)
    try container.encode(id, forKey: .id)
    try container.encode(stamp, forKey: .stamp)
    try container.encode(colorArgb, forKey: .colorArgb)
    try container.encode(width, forKey: .width)
    try container.encode(start, forKey: .start)
    try container.encode(end, forKey: .end)
  }

  public init(from decoder: any Decoder) throws {
    let container = try decoder.container(keyedBy: CodingKeys.self)
    self.init(
      id: try container.decode(ObjectId.self, forKey: .id),
      stamp: try container.decode(OperationStamp.self, forKey: .stamp),
      colorArgb: try container.decode(Int32.self, forKey: .colorArgb),
      width: try container.decodeIfPresent(Int.self, forKey: .width) ?? defaultStrokeWidth,
      start: try container.decode(LogicalPoint.self, forKey: .start),
      end: try container.decode(LogicalPoint.self, forKey: .end)
    )
  }
}

extension BoardObject.Text: Codable {
  private enum CodingKeys: String, CodingKey {
    case id, stamp, colorArgb, anchor, text, size
  }

  public func encode(to encoder: any Encoder) throws {
    var container = encoder.container(keyedBy: CodingKeys.self)
    try container.encode(id, forKey: .id)
    try container.encode(stamp, forKey: .stamp)
    try container.encode(colorArgb, forKey: .colorArgb)
    try container.encode(anchor, forKey: .anchor)
    try container.encode(text, forKey: .text)
    try container.encode(size, forKey: .size)
  }

  public init(from decoder: any Decoder) throws {
    let container = try decoder.container(keyedBy: CodingKeys.self)
    self.init(
      id: try container.decode(ObjectId.self, forKey: .id),
      stamp: try container.decode(OperationStamp.self, forKey: .stamp),
      colorArgb: try container.decode(Int32.self, forKey: .colorArgb),
      anchor: try container.decode(LogicalPoint.self, forKey: .anchor),
      text: try container.decode(String.self, forKey: .text),
      size: try container.decodeIfPresent(Int.self, forKey: .size) ?? defaultTextSize
    )
  }
}

extension BoardObject: Codable {
  private enum DiscriminatorKey: String, CodingKey {
    case kind
  }

  public func encode(to encoder: any Encoder) throws {
    var container = encoder.container(keyedBy: DiscriminatorKey.self)
    switch self {
    case .freehand(let object):
      try container.encode(KindDiscriminator.freehand, forKey: .kind)
      try object.encode(to: encoder)
    case .line(let object):
      try container.encode(KindDiscriminator.line, forKey: .kind)
      try object.encode(to: encoder)
    case .rectangle(let object):
      try container.encode(KindDiscriminator.rectangle, forKey: .kind)
      try object.encode(to: encoder)
    case .ellipse(let object):
      try container.encode(KindDiscriminator.ellipse, forKey: .kind)
      try object.encode(to: encoder)
    case .text(let object):
      try container.encode(KindDiscriminator.text, forKey: .kind)
      try object.encode(to: encoder)
    }
  }

  public init(from decoder: any Decoder) throws {
    let probe = try KindProbe(from: decoder)
    switch probe.kind {
    case KindDiscriminator.freehand.rawValue:
      self = .freehand(try Freehand(from: decoder))
    case KindDiscriminator.line.rawValue:
      self = .line(try Line(from: decoder))
    case KindDiscriminator.rectangle.rawValue:
      self = .rectangle(try Rectangle(from: decoder))
    case KindDiscriminator.ellipse.rawValue:
      self = .ellipse(try Ellipse(from: decoder))
    case KindDiscriminator.text.rawValue:
      self = .text(try Text(from: decoder))
    default:
      throw DecodingError.dataCorrupted(
        DecodingError.Context(
          codingPath: decoder.codingPath,
          debugDescription: "Unknown board object kind \"\(probe.kind)\""
        )
      )
    }
  }
}

extension BoardOperation.Commit: Codable {
  private enum CodingKeys: String, CodingKey {
    case id, stamp, boardObject, gestureId
  }

  public func encode(to encoder: any Encoder) throws {
    var container = encoder.container(keyedBy: CodingKeys.self)
    try container.encode(id, forKey: .id)
    try container.encode(stamp, forKey: .stamp)
    try container.encode(boardObject, forKey: .boardObject)
    try container.encode(gestureId, forKey: .gestureId)
  }

  public init(from decoder: any Decoder) throws {
    let container = try decoder.container(keyedBy: CodingKeys.self)
    let id = try container.decode(OperationId.self, forKey: .id)
    self.init(
      id: id,
      stamp: try container.decode(OperationStamp.self, forKey: .stamp),
      boardObject: try container.decode(BoardObject.self, forKey: .boardObject),
      gestureId: try container.decodeIfPresent(String.self, forKey: .gestureId)
    )
  }
}

extension BoardOperation.Erase: Codable {
  private enum CodingKeys: String, CodingKey {
    case id, stamp, path, radius, gestureId
  }

  public func encode(to encoder: any Encoder) throws {
    var container = encoder.container(keyedBy: CodingKeys.self)
    try container.encode(id, forKey: .id)
    try container.encode(stamp, forKey: .stamp)
    try container.encode(path, forKey: .path)
    try container.encode(radius, forKey: .radius)
    try container.encode(gestureId, forKey: .gestureId)
  }

  public init(from decoder: any Decoder) throws {
    let container = try decoder.container(keyedBy: CodingKeys.self)
    let id = try container.decode(OperationId.self, forKey: .id)
    self.init(
      id: id,
      stamp: try container.decode(OperationStamp.self, forKey: .stamp),
      path: try container.decode([LogicalPoint].self, forKey: .path),
      radius: try container.decodeIfPresent(Int.self, forKey: .radius) ?? defaultEraserRadius,
      gestureId: try container.decodeIfPresent(String.self, forKey: .gestureId)
    )
  }
}

extension BoardOperation.Clear: Codable {
  private enum CodingKeys: String, CodingKey {
    case id, stamp
  }

  public func encode(to encoder: any Encoder) throws {
    var container = encoder.container(keyedBy: CodingKeys.self)
    try container.encode(id, forKey: .id)
    try container.encode(stamp, forKey: .stamp)
  }

  public init(from decoder: any Decoder) throws {
    let container = try decoder.container(keyedBy: CodingKeys.self)
    self.init(
      id: try container.decode(OperationId.self, forKey: .id),
      stamp: try container.decode(OperationStamp.self, forKey: .stamp)
    )
  }
}
extension BoardOperation.ProfileUpdate: Codable {
  private enum CodingKeys: String, CodingKey {
    case id, stamp, profile
  }

  public func encode(to encoder: any Encoder) throws {
    var container = encoder.container(keyedBy: CodingKeys.self)
    try container.encode(id, forKey: .id)
    try container.encode(stamp, forKey: .stamp)
    try container.encode(profile, forKey: .profile)
  }

  public init(from decoder: any Decoder) throws {
    let container = try decoder.container(keyedBy: CodingKeys.self)
    self.init(
      id: try container.decode(OperationId.self, forKey: .id),
      stamp: try container.decode(OperationStamp.self, forKey: .stamp),
      profile: try container.decode(UserProfile.self, forKey: .profile)
    )
  }
}

extension BoardOperation: Codable {
  private enum DiscriminatorKey: String, CodingKey {
    case kind
  }

  public func encode(to encoder: any Encoder) throws {
    var container = encoder.container(keyedBy: DiscriminatorKey.self)
    switch self {
    case .commit(let operation):
      try container.encode(KindDiscriminator.commit, forKey: .kind)
      try operation.encode(to: encoder)
    case .erase(let operation):
      try container.encode(KindDiscriminator.erase, forKey: .kind)
      try operation.encode(to: encoder)
    case .clear(let operation):
      try container.encode(KindDiscriminator.clear, forKey: .kind)
      try operation.encode(to: encoder)
    case .profileUpdate(let operation):
      try container.encode(KindDiscriminator.profile, forKey: .kind)
      try operation.encode(to: encoder)
    }
  }

  public init(from decoder: any Decoder) throws {
    let probe = try KindProbe(from: decoder)
    switch probe.kind {
    case KindDiscriminator.commit.rawValue:
      self = .commit(try Commit(from: decoder))
    case KindDiscriminator.erase.rawValue:
      self = .erase(try Erase(from: decoder))
    case KindDiscriminator.clear.rawValue:
      self = .clear(try Clear(from: decoder))
    case KindDiscriminator.profile.rawValue:
      self = .profileUpdate(try ProfileUpdate(from: decoder))
    default:
      throw DecodingError.dataCorrupted(
        DecodingError.Context(
          codingPath: decoder.codingPath,
          debugDescription: "Unknown board operation kind \"\(probe.kind)\""
        )
      )
    }
  }
}
