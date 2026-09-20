import Foundation
import Observation
import WhiteboardCore
import WhiteboardKit

@MainActor @Observable
final class AppModel {
  static let sessionStartFailedMessage =
    "Whiteboard could not start. Close and reopen the app to retry."
  static let profileSessionUpdateFailedMessage =
    "Profile was saved, but the session could not update it."
  static let profileSaveFailedMessage =
    "Profile could not be saved. Check the name and try again."

  let profileStore: ProfileStore
  private let sessionProvider: @Sendable () -> BoardSession
  /// Lazily yields the session's transport so the presence viewer can observe the
  /// raw (unfiltered) presence graph without going through the board session, which
  /// deliberately filters presence to admitted whiteboard peers.
  private let transportProvider: @Sendable () -> (any WhiteboardTransport)?

  private(set) var profile: ProfileSettings?
  private(set) var boardState = BoardState()
  private(set) var previews: [LivePreview] = []
  private(set) var diagnostics = TransportDiagnostics(editingReady: false)
  private(set) var sessionError: String?
  private(set) var startFailed = false
  private(set) var sessionAttached = false
  var actionError: String?
  /// The remembered drawing tool. Hand navigation never replaces this selection.
  private(set) var selectedTool: DrawingTool = .pen
  /// The mode currently driving canvas gestures; this may temporarily be Hand.
  private(set) var activeTool: DrawingTool = .pen
  private(set) var selectedColorArgb: Int32 = whiteboardPalette[0]

  private var sessionInstance: BoardSession?
  private var observationTasks: [Task<Void, Never>] = []
  private var colorSeeded = false
  private var lastStartInput: (name: String, colorArgb: Int32)?
  private var desiredForeground = true

  var bannerMessage: String? {
    actionError ?? sessionError ?? boardBannerConnectivityMessage
  }

  /// A timed-out initial reconciliation is non-blocking: the board has already
  /// enabled editing and the detail remains available in Transport. Keeping it
  /// out of the canvas banner prevents a stale nearby peer from covering the
  /// work surface with an error-looking message.
  private var boardBannerConnectivityMessage: String? {
    guard let message = diagnostics.connectivityMessage,
      !message.hasPrefix("Initial nearby sync timed out")
    else { return nil }
    return message
  }

  init(
    profileStore: ProfileStore,
    sessionProvider: @escaping @Sendable () -> BoardSession,
    transportProvider: @escaping @Sendable () -> (any WhiteboardTransport)? = { nil }
  ) {
    self.profileStore = profileStore
    self.sessionProvider = sessionProvider
    self.transportProvider = transportProvider
    self.profile = profileStore.profile
  }

  /// Observes the full presence mesh for the presence viewer. `.inactive` (never
  /// fires) when no transport exists yet.
  func observePresenceGraph(
    _ handler: @escaping @Sendable (PresenceGraphSnapshot) -> Void
  ) -> PresenceGraphObservationToken {
    transportProvider()?.observePresenceGraph(handler) ?? .inactive
  }

  /// Best-effort `system:data_sync_info` rows keyed by peer key (last-known on
  /// failure for the Ditto transport; empty when no transport exists yet).
  func peerSyncStatus() async -> [String: PeerSyncStatus] {
    await transportProvider()?.syncStatusByPeerKey() ?? [:]
  }

  func startSession(displayName: String, colorArgb: Int32) {
    let normalizedColor = normalizeWhiteboardColor(colorArgb)
    if !colorSeeded {
      selectedColorArgb = normalizedColor
      colorSeeded = true
    }
    lastStartInput = (displayName, normalizedColor)
    Task { [weak self] in
      guard let self else { return }
      let session = await self.session()
      do {
        try await session.start(displayName: displayName, colorArgb: normalizedColor)
        FileHandle.standardError.write("DittoWhiteboardApp: session started\n".data(using: .utf8)!)
        self.attach(to: session)
        self.actionError = nil
        self.startFailed = false
      } catch {
        FileHandle.standardError.write("DittoWhiteboardApp: session start failed: \(error.localizedDescription)\n".data(using: .utf8)!)
        self.actionError = Self.sessionStartFailedMessage
        self.startFailed = true
      }
    }
  }

  func retrySessionStart() {
    guard let input = lastStartInput else { return }
    startFailed = false
    startSession(displayName: input.name, colorArgb: input.colorArgb)
  }

  @discardableResult
  func saveProfile(displayName: String, colorArgb: Int32) async -> Bool {
    do {
      try profileStore.save(displayName: displayName, colorArgb: colorArgb)
    } catch {
      actionError = Self.profileSaveFailedMessage
      return false
    }
    actionError = nil
    let saved = profileStore.profile
    profile = saved
    if sessionAttached, let session = sessionInstance {
      do {
        try await session.start(
          displayName: saved?.displayName ?? displayName,
          colorArgb: colorArgb
        )
      } catch {
        actionError = Self.profileSessionUpdateFailedMessage
        return false
      }
    }
    return true
  }

  func selectTool(_ tool: DrawingTool) {
    activeTool = tool
    if tool != .hand {
      selectedTool = tool
    }
  }

  func selectColor(_ colorArgb: Int32) {
    guard isApprovedWhiteboardColor(colorArgb) else { return }
    selectedColorArgb = colorArgb
  }

  func preview(gestureId: String, points: [LogicalPoint]) {
    sessionInstance?.preview(
      gestureId: gestureId,
      tool: activeTool,
      colorArgb: selectedColorArgb,
      points: points
    )
  }

  func commit(
    gestureId: String,
    points: [LogicalPoint],
    text: String = "",
    textFont: BoardTextFont = defaultTextFont,
    textSize: Int = defaultTextSize
  ) {
    guard let session = sessionInstance else { return }
    let tool = activeTool
    let color = selectedColorArgb
    Task {
      await session.commit(
        tool: tool,
        colorArgb: color,
        points: points,
        text: text,
        textFont: textFont,
        textSize: textSize,
        gestureId: gestureId
      )
    }
  }

  func clear() {
    guard let session = sessionInstance else { return }
    Task { await session.clear() }
  }

  func setForeground(_ isForeground: Bool) {
    desiredForeground = isForeground
    sessionInstance?.setForeground(isForeground)
  }

  private func session() async -> BoardSession {
    if let sessionInstance { return sessionInstance }
    let provider = sessionProvider
    let created = await Task.detached { provider() }.value
    if let existing = sessionInstance {
      await created.close()
      return existing
    }
    sessionInstance = created
    created.setForeground(desiredForeground)
    return created
  }

  private func attach(to session: BoardSession) {
    guard !sessionAttached else { return }
    sessionAttached = true
    observationTasks = [
      Task { [weak self] in
        for await state in session.boardStates { self?.boardState = state }
      },
      Task { [weak self] in
        for await previewMap in session.previewsStream {
          self?.previews = previewMap.values.sorted { $0.peerKey < $1.peerKey }
        }
      },
      Task { [weak self] in
        for await error in session.errors { self?.sessionError = error }
      },
      Task { [weak self] in
        for await current in session.diagnostics { self?.diagnostics = current }
      },
    ]
  }
}

final class LiveTransportFactory: @unchecked Sendable {
  private let lock = NSLock()
  private var cached: (any WhiteboardTransport)?

  func transport() -> any WhiteboardTransport {
    lock.withLock {
      if let cached { return cached }
      let created = Self.make()
      cached = created
      return created
    }
  }

  private static func make() -> any WhiteboardTransport {
    let databaseID = DittoEmbeddedCredentials.databaseID
      .trimmingCharacters(in: .whitespacesAndNewlines)
    let licenseToken = DittoEmbeddedCredentials.offlineLicenseToken
      .trimmingCharacters(in: .whitespacesAndNewlines)
    guard !databaseID.isEmpty, !licenseToken.isEmpty else {
      return InMemoryWhiteboardTransport(
        reason: "Nearby collaboration is off until Whiteboard Ditto credentials are added."
      )
    }
    do {
      let directory = try FileManager.default
        .url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
        .appendingPathComponent("DittoWhiteboard", isDirectory: true)
      try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
      let debugTransports = DebugTransportSettings.load()
      return try DittoWhiteboardTransport(
        credentials: DittoCredentials(
          databaseID: databaseID,
          offlineLicenseToken: licenseToken,
          persistenceDirectory: directory
        ),
        transportConfigCustomizer: { config in
          config.peerToPeer.bluetoothLE.isEnabled = debugTransports.bluetoothLEEnabled
          config.peerToPeer.lan.isEnabled = debugTransports.lanEnabled
          config.peerToPeer.lan.isMDNSEnabled = debugTransports.mdnsEnabled
          config.peerToPeer.lan.isMulticastEnabled = debugTransports.multicastEnabled
          config.peerToPeer.awdl.isEnabled = debugTransports.awdlEnabled
        }
      )
    } catch {
      return InMemoryWhiteboardTransport(
        reason: "Nearby collaboration could not start; using local preview."
      )
    }
  }
}

extension AppModel {
  static func live() -> AppModel {
    let defaults = UserDefaults.standard
    if ProcessInfo.processInfo.arguments.contains("-WhiteboardResetProfile") {
      ProfileStore(defaults: defaults).reset()
    }
    let profileStore = ProfileStore(defaults: defaults)
    let factory = LiveTransportFactory()
    return AppModel(
      profileStore: profileStore,
      sessionProvider: {
        BoardSession(
          transport: factory.transport(),
          reserveOperationClock: { try profileStore.reserveOperationClock() },
          reserveLamportAfter: { observed in
            try profileStore.reserveLamportAfter(observedLamport: observed)
          }
        )
      },
      transportProvider: { factory.transport() }
    )
  }
}
