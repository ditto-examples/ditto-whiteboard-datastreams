import Foundation
import Testing
import WhiteboardCore

@testable import Whiteboard

struct ProfileStoreTests {
  private func makeStore(now: Int64 = 1_700_000_000_000) -> (ProfileStore, UserDefaults) {
    let suiteName = "ProfileStoreTests.\(UUID().uuidString)"
    let defaults = UserDefaults(suiteName: suiteName)!
    return (ProfileStore(defaults: defaults, nowMillis: { now }), defaults)
  }

  @Test func `save validates display name length`() throws {
    let (store, _) = makeStore()

    #expect(throws: ProfileStoreError.invalidDisplayName) {
      try store.save(displayName: "   ", colorArgb: whiteboardPalette[0])
    }
    #expect(throws: ProfileStoreError.invalidDisplayName) {
      try store.save(displayName: String(repeating: "a", count: 25), colorArgb: whiteboardPalette[0])
    }
    #expect(throws: Never.self) {
      try store.save(displayName: String(repeating: "a", count: 24), colorArgb: whiteboardPalette[0])
    }
  }

  @Test func `save rejects control characters and unapproved colors`() {
    let (store, _) = makeStore()

    #expect(throws: ProfileStoreError.invalidDisplayName) {
      try store.save(displayName: "A\u{01}da", colorArgb: whiteboardPalette[0])
    }
    #expect(throws: ProfileStoreError.invalidColor) {
      try store.save(displayName: "Ada", colorArgb: Int32(bitPattern: 0xFF12_3456))
    }
  }

  @Test func `save trims and round trips the profile`() throws {
    let (store, _) = makeStore()

    try store.save(displayName: "  Ada  ", colorArgb: whiteboardPalette[4])

    #expect(store.profile == ProfileSettings(displayName: "Ada", colorArgb: whiteboardPalette[4]))
  }

  @Test func `loaded profile normalizes unapproved stored color`() throws {
    let (store, defaults) = makeStore()
    try store.save(displayName: "Ada", colorArgb: whiteboardPalette[1])
    defaults.set(NSNumber(value: Int32(bitPattern: 0xFF00_FF00)), forKey: "default_color")

    #expect(store.profile?.colorArgb == defaultWhiteboardColor)
  }

  @Test func `operation clock reserves sequence blocks per process lifetime`() throws {
    let (store, _) = makeStore()

    let first = try store.reserveOperationClock()
    let second = try store.reserveOperationClock()

    #expect(first.firstSenderSequence == ProfileStore.sequenceBlockSize)
    #expect(second.firstSenderSequence == ProfileStore.sequenceBlockSize * 2)
    #expect(first.initialLamport == 1_700_000_000_000)
    #expect(first.lamportCeiling == 1_700_000_000_000 + ProfileStore.lamportBlockSize)
  }

  @Test func `lamport block keeps durable high water mark over wall time`() throws {
    let (store, _) = makeStore(now: 100)

    _ = try store.reserveOperationClock()
    let second = try store.reserveOperationClock()

    #expect(second.initialLamport == 100 + ProfileStore.lamportBlockSize)
    #expect(second.lamportCeiling == 100 + ProfileStore.lamportBlockSize * 2)
  }

  @Test func `reserve lamport after is monotonic`() throws {
    let (store, _) = makeStore(now: 1_000)

    let initial = try store.reserveOperationClock()
    let unchanged = try store.reserveLamportAfter(observedLamport: 500)
    #expect(unchanged == initial.lamportCeiling)

    let raised = try store.reserveLamportAfter(observedLamport: initial.lamportCeiling + 40_000)
    #expect(raised == initial.lamportCeiling + 40_000 + ProfileStore.lamportBlockSize)

    let stillMonotonic = try store.reserveLamportAfter(observedLamport: initial.lamportCeiling)
    #expect(stillMonotonic == raised)
  }
}
