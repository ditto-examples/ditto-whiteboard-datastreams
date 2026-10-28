import Foundation
import Testing
import WhiteboardCore

@testable import Whiteboard

@MainActor
private func waitFor(
  _ timeout: Duration = .seconds(3),
  _ condition: @MainActor () -> Bool
) async -> Bool {
  let clock = ContinuousClock()
  let deadline = clock.now + timeout
  while clock.now < deadline {
    if condition() { return true }
    try? await Task.sleep(for: .milliseconds(10))
  }
  return condition()
}

@MainActor
private func makeModel(
  reason: String = "preview",
  reservationFails: Bool = false
) -> AppModel {
  let suiteName = "AppModelTests.\(UUID().uuidString)"
  let store = ProfileStore(defaults: UserDefaults(suiteName: suiteName)!)
  return AppModel(profileStore: store) {
    BoardSession(
      transport: InMemoryWhiteboardTransport(reason: reason),
      reserveOperationClock: {
        if reservationFails { throw WhiteboardCoreError.requirementFailed("disk full") }
        return try store.reserveOperationClock()
      },
      reserveLamportAfter: { try store.reserveLamportAfter(observedLamport: $0) }
    )
  }
}

@Test
func legacyLANDiscoveryDefaultMigratesOnceAndPreservesLaterUserChoice() {
  let suiteName = "DebugTransportSettingsTests.\(UUID().uuidString)"
  let defaults = UserDefaults(suiteName: suiteName)!
  defer { defaults.removePersistentDomain(forName: suiteName) }

  // This is the value stored by the old default, not an explicit user choice.
  defaults.set(false, forKey: "debugTransports.multicast")
  #expect(DebugTransportSettings.load(from: defaults).multicastEnabled)

  var userDisabled = DebugTransportSettings.default
  userDisabled.multicastEnabled = false
  userDisabled.save(to: defaults)
  #expect(!DebugTransportSettings.load(from: defaults).multicastEnabled)
}

@MainActor
struct AppModelTests {
  @Test func `profile starts empty and save publishes it`() async {
    let model = makeModel()

    #expect(model.profile == nil)

    let saved = await model.saveProfile(displayName: "Ada", colorArgb: whiteboardPalette[3])

    #expect(saved)
    #expect(model.profile == ProfileSettings(displayName: "Ada", colorArgb: whiteboardPalette[3]))
  }

  @Test func `invalid profile save surfaces an action error`() async {
    let model = makeModel()

    let saved = await model.saveProfile(displayName: "   ", colorArgb: whiteboardPalette[0])

    #expect(!saved)
    #expect(model.actionError == AppModel.profileSaveFailedMessage)
    #expect(model.profile == nil)
  }

  @Test func `session start seeds color and local commit reaches board state`() async {
    let model = makeModel()

    model.startSession(displayName: "Ada", colorArgb: whiteboardPalette[2])
    #expect(await waitFor { model.sessionAttached })
    #expect(model.selectedColorArgb == whiteboardPalette[2])

    model.selectTool(.pen)
    let points = [LogicalPoint(x: 100, y: 100), LogicalPoint(x: 400, y: 500)]
    model.commit(gestureId: "gesture-1", points: points)

    #expect(
      await waitFor {
        model.boardState.objects.values.contains { object in
          if case .freehand(let freehand) = object {
            return freehand.points == points && freehand.colorArgb == whiteboardPalette[2]
          }
          return false
        }
      })
  }

  @Test func `tool and color selection state`() {
    let model = makeModel()

    model.selectTool(.eraser)
    #expect(model.selectedTool == .eraser)

    model.selectTool(.hand)
    #expect(model.activeTool == .hand)
    #expect(model.selectedTool == .eraser)

    model.selectColor(whiteboardPalette[5])
    #expect(model.selectedColorArgb == whiteboardPalette[5])

    model.selectColor(Int32(bitPattern: 0xFF00_FF00))
    #expect(model.selectedColorArgb == whiteboardPalette[5])
  }

  @Test func `banner prefers action error then session error then connectivity message`() async {
    let model = makeModel(reason: "local preview reason")

    #expect(model.bannerMessage == nil)
    model.startSession(displayName: "Ada", colorArgb: whiteboardPalette[0])
    #expect(await waitFor { model.bannerMessage == "local preview reason" })

    model.actionError = "action problem"
    #expect(model.bannerMessage == "action problem")

    model.actionError = nil
    #expect(model.bannerMessage == "local preview reason")
  }

  @Test func `non-blocking initial sync timeout stays in Transport diagnostics`() async {
    let model = makeModel(
      reason: "Initial nearby sync timed out. Editing is available, but an unreachable peer may be stale."
    )

    model.startSession(displayName: "Ada", colorArgb: whiteboardPalette[0])

    #expect(await waitFor { model.sessionAttached })
    #expect(model.diagnostics.connectivityMessage?.hasPrefix("Initial nearby sync timed out") == true)
    #expect(model.bannerMessage == nil)
  }

  @Test func `failed session start latches and retry clears it`() async {
    let model = makeModel(reservationFails: true)

    model.startSession(displayName: "Ada", colorArgb: whiteboardPalette[0])
    #expect(await waitFor { model.startFailed })
    #expect(model.actionError == AppModel.sessionStartFailedMessage)

    model.retrySessionStart()
    #expect(!model.startFailed)
    #expect(await waitFor { model.startFailed })
  }

  @Test func `clear removes committed objects`() async {
    let model = makeModel()

    model.startSession(displayName: "Ada", colorArgb: whiteboardPalette[0])
    #expect(await waitFor { model.sessionAttached })

    model.commit(gestureId: "gesture-1", points: [LogicalPoint(x: 10, y: 10), LogicalPoint(x: 50, y: 50)])
    #expect(await waitFor { !model.boardState.objects.isEmpty })

    model.clear()
    #expect(await waitFor { model.boardState.objects.isEmpty })
  }
}
