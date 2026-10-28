import Foundation
import WhiteboardCore

struct ProfileSettings: Equatable, Hashable, Sendable {
  var displayName: String
  var colorArgb: Int32
}

enum ProfileStoreError: Error, Equatable {
  case invalidDisplayName
  case invalidColor
  case sequenceSpaceExhausted
  case lamportSpaceExhausted
}

final class ProfileStore: @unchecked Sendable {
  static let sequenceBlockSize: Int64 = 1_000_000
  static let lamportBlockSize: Int64 = Int64(WhiteboardProtocol.maxSnapshotOperations) + 1

  private enum Keys {
    static let displayName = "display_name"
    static let defaultColor = "default_color"
    static let nextOperationSequence = "next_operation_sequence"
    static let nextLamport = "next_lamport"
  }

  private let defaults: UserDefaults
  private let nowMillis: @Sendable () -> Int64
  private let lock = NSLock()

  init(
    defaults: UserDefaults = .standard,
    nowMillis: @escaping @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1_000) }
  ) {
    self.defaults = defaults
    self.nowMillis = nowMillis
  }

  var profile: ProfileSettings? {
    lock.withLock {
      guard let name = defaults.string(forKey: Keys.displayName),
        let color = (defaults.object(forKey: Keys.defaultColor) as? NSNumber)?.int32Value
      else { return nil }
      return ProfileSettings(displayName: name, colorArgb: normalizeWhiteboardColor(color))
    }
  }

  func save(displayName: String, colorArgb: Int32) throws {
    let trimmed = displayName.trimmingCharacters(in: .whitespacesAndNewlines)
    guard (1...24).contains(trimmed.utf16.count), !trimmed.containsISOControlCharacters else {
      throw ProfileStoreError.invalidDisplayName
    }
    guard isApprovedWhiteboardColor(colorArgb) else { throw ProfileStoreError.invalidColor }
    lock.withLock {
      defaults.set(trimmed, forKey: Keys.displayName)
      defaults.set(NSNumber(value: colorArgb), forKey: Keys.defaultColor)
    }
  }

  func reserveOperationClock() throws -> OperationClockReservation {
    try lock.withLock {
      let storedSequence = (defaults.object(forKey: Keys.nextOperationSequence) as? NSNumber)?
        .int64Value
      let firstSequence = storedSequence ?? Self.sequenceBlockSize
      guard firstSequence <= WhiteboardProtocol.maxProtocolCounter - Self.sequenceBlockSize else {
        throw ProfileStoreError.sequenceSpaceExhausted
      }
      defaults.set(
        NSNumber(value: firstSequence + Self.sequenceBlockSize),
        forKey: Keys.nextOperationSequence
      )
      let storedLamport = (defaults.object(forKey: Keys.nextLamport) as? NSNumber)?.int64Value ?? 0
      let initialLamport = max(storedLamport, nowMillis())
      guard initialLamport <= WhiteboardProtocol.maxSessionLamport else {
        throw ProfileStoreError.lamportSpaceExhausted
      }
      let ceiling = min(initialLamport + Self.lamportBlockSize, WhiteboardProtocol.maxSessionLamport)
      defaults.set(NSNumber(value: ceiling), forKey: Keys.nextLamport)
      return OperationClockReservation(
        firstSenderSequence: firstSequence,
        initialLamport: initialLamport,
        lamportCeiling: ceiling
      )
    }
  }

  func reserveLamportAfter(observedLamport: Int64) throws -> Int64 {
    try lock.withLock {
      let alreadyReserved = (defaults.object(forKey: Keys.nextLamport) as? NSNumber)?.int64Value ?? 0
      if observedLamport < alreadyReserved { return alreadyReserved }
      guard observedLamport <= WhiteboardProtocol.maxSessionLamport else {
        throw ProfileStoreError.lamportSpaceExhausted
      }
      let ceiling = min(observedLamport + Self.lamportBlockSize, WhiteboardProtocol.maxSessionLamport)
      defaults.set(NSNumber(value: ceiling), forKey: Keys.nextLamport)
      return ceiling
    }
  }

  func reset() {
    lock.withLock {
      defaults.removeObject(forKey: Keys.displayName)
      defaults.removeObject(forKey: Keys.defaultColor)
    }
  }
}

extension String {
  var containsISOControlCharacters: Bool {
    unicodeScalars.contains { $0.value <= 0x1F || (0x7F...0x9F).contains($0.value) }
  }

  func removingISOControlCharacters() -> String {
    let filtered = unicodeScalars.filter { $0.value > 0x1F && !(0x7F...0x9F).contains($0.value) }
    return String(String.UnicodeScalarView(filtered))
  }
}
