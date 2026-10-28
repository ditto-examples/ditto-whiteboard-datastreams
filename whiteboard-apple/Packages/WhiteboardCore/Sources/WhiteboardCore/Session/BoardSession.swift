import Foundation

/// User-facing copy shown by `BoardSession`. Defaults are the production English strings; tests
/// and embedders can substitute their own.
public struct BoardSessionMessages: Equatable, Sendable {
  public var syncFinishing: String
  public var sessionStarting: String
  public var textCannotBeBlank: String
  public var operationClockExhausted: String
  public var logicalTimeLimit: String
  public var editOutsideSafetyLimits: String
  public var terminalResourceLimit: String
  public var peerLogicalTimeLimit: String
  public var visibleObjectLimit: String
  public var clockReservationFailed: String
  public var remoteUpdateFailed: String

  public init(
    syncFinishing: String = "Finishing nearby board sync. Editing will unlock automatically.",
    sessionStarting: String = "The board is still starting. Try that edit again in a moment.",
    textCannotBeBlank: String = "Text cannot be blank.",
    operationClockExhausted: String =
      "The operation clock is exhausted; close this board before editing again.",
    logicalTimeLimit: String =
      "This ephemeral board reached its logical-time limit. All collaborators must close and reopen Whiteboard to start a new board.",
    editOutsideSafetyLimits: String = "That edit is outside the board’s safety limits.",
    terminalResourceLimit: String =
      "This ephemeral board reached a terminal resource limit. Every collaborator must close and reopen Whiteboard to start a new board.",
    peerLogicalTimeLimit: String =
      "A peer sent an operation beyond this board’s logical-time limit.",
    visibleObjectLimit: String =
      "This board reached its visible-object safety limit. Clear the board to continue, or have every collaborator close and reopen Whiteboard.",
    clockReservationFailed: String =
      "Could not reserve logical clock state; the incoming edit was not applied.",
    remoteUpdateFailed: String =
      "An update from a nearby peer could not be applied. Collaboration continues; the board repairs itself on the next sync."
  ) {
    self.syncFinishing = syncFinishing
    self.sessionStarting = sessionStarting
    self.textCannotBeBlank = textCannotBeBlank
    self.operationClockExhausted = operationClockExhausted
    self.logicalTimeLimit = logicalTimeLimit
    self.editOutsideSafetyLimits = editOutsideSafetyLimits
    self.terminalResourceLimit = terminalResourceLimit
    self.peerLogicalTimeLimit = peerLogicalTimeLimit
    self.visibleObjectLimit = visibleObjectLimit
    self.clockReservationFailed = clockReservationFailed
    self.remoteUpdateFailed = remoteUpdateFailed
  }
}

public struct OperationClockReservation: Equatable, Sendable {
  public var firstSenderSequence: Int64
  public var initialLamport: Int64
  public var lamportCeiling: Int64

  public init(firstSenderSequence: Int64, initialLamport: Int64, lamportCeiling: Int64) {
    self.firstSenderSequence = firstSenderSequence
    self.initialLamport = initialLamport
    self.lamportCeiling = lamportCeiling
  }
}

/// Coordinates the local board with the `WhiteboardTransport`: assigns Lamport-ordered stamps to
/// local operations, folds every operation (local and remote) through `BoardReducer` into the
/// shared `boardState`, de-duplicates by operation id, and maintains a short-lived cache of remote
/// drawing previews.
///
/// All mutable state is actor-isolated, so concurrent writes — local edits from the UI and inbound
/// transport events on background executors — compose atomically instead of racing.
public actor BoardSession {
  /// Cap on the number of points retained for a single live preview, to bound memory for a fast peer.
  public static let maxPreviewPoints = 128
  public static let livePreviewSegmentPoints = 32

  private let transport: any WhiteboardTransport
  private let messages: BoardSessionMessages
  private let nowMillis: @Sendable () -> Int64
  private let previewTTLMillis: Int64
  private let previewSweepIntervalMillis: UInt64
  private let reserveOperationClock: @Sendable () async throws -> OperationClockReservation
  private let reserveLamportAfter: @Sendable (Int64) async throws -> Int64

  public nonisolated var localPeerKey: String { transport.localPeerKey }
  public nonisolated var requiredPermissions: [String] { transport.requiredPermissions }
  public nonisolated var diagnostics: AsyncStream<TransportDiagnostics> { transport.diagnostics }
  public nonisolated var currentDiagnostics: TransportDiagnostics { transport.currentDiagnostics }

  private var clock: OperationClock?
  private var lamportCeiling: Int64 = maxOperationCounter
  private var startedProfile: UserProfile?
  private var transportTask: Task<Void, Never>?
  private var profileTask: Task<Void, Never>?
  private var sweepTask: Task<Void, Never>?
  private var diagnosticsTask: Task<Void, Never>?
  private var startLocked = false
  private var startWaiters: [CheckedContinuation<Void, Never>] = []

  private var lastLiveSequence: [String: Int64] = [:]
  private var currentLiveSession: [String: String] = [:]
  private var retiredLiveSessions: [String: [String]] = [:]
  private var completedGestures: [String: [String]] = [:]

  public private(set) var boardState = BoardState() {
    didSet { if oldValue != boardState { boardStateContinuation.yield(boardState) } }
  }
  public private(set) var previews: [String: LivePreview] = [:] {
    didSet { if oldValue != previews { previewsContinuation.yield(previews) } }
  }
  public private(set) var error: String? {
    didSet { if oldValue != error { errorContinuation.yield(error) } }
  }

  public nonisolated let boardStates: AsyncStream<BoardState>
  public nonisolated let previewsStream: AsyncStream<[String: LivePreview]>
  public nonisolated let errors: AsyncStream<String?>
  private let boardStateContinuation: AsyncStream<BoardState>.Continuation
  private let previewsContinuation: AsyncStream<[String: LivePreview]>.Continuation
  private let errorContinuation: AsyncStream<String?>.Continuation

  public init(
    transport: any WhiteboardTransport,
    messages: BoardSessionMessages = BoardSessionMessages(),
    nowMillis: @escaping @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1_000) },
    previewTTLMillis: Int64 = 2_000,
    previewSweepIntervalMillis: UInt64 = 500,
    reserveOperationClock: @escaping @Sendable () async throws -> OperationClockReservation = {
      OperationClockReservation(
        firstSenderSequence: 0,
        initialLamport: 0,
        lamportCeiling: maxOperationCounter
      )
    },
    reserveLamportAfter: @escaping @Sendable (Int64) async throws -> Int64 = { _ in
      maxOperationCounter
    }
  ) {
    self.transport = transport
    self.messages = messages
    self.nowMillis = nowMillis
    self.previewTTLMillis = previewTTLMillis
    self.previewSweepIntervalMillis = previewSweepIntervalMillis
    self.reserveOperationClock = reserveOperationClock
    self.reserveLamportAfter = reserveLamportAfter
    let (boardStates, boardStateContinuation) = AsyncStream<BoardState>.makeStream()
    let (previewsStream, previewsContinuation) = AsyncStream<[String: LivePreview]>.makeStream()
    let (errors, errorContinuation) = AsyncStream<String?>.makeStream()
    self.boardStates = boardStates
    self.previewsStream = previewsStream
    self.errors = errors
    self.boardStateContinuation = boardStateContinuation
    self.previewsContinuation = previewsContinuation
    self.errorContinuation = errorContinuation
  }

  public func start(displayName: String, colorArgb: Int32) async throws {
    await acquireStartLock()
    defer { releaseStartLock() }
    guard isApprovedWhiteboardColor(colorArgb) else {
      throw WhiteboardCoreError.requirementFailed("Drawing color must be in the approved palette")
    }
    let profile = try UserProfile(
      peerKey: localPeerKey,
      displayName: displayName,
      colorArgb: colorArgb
    )
    if startedProfile == profile { return }
    if sweepTask == nil {
      sweepTask = Task { [self] in await sweepExpiredPreviews() }
      diagnosticsTask = Task { [self] in await observeDiagnostics() }
    }
    if clock == nil {
      let reservation = try await reserveOperationClock()
      lamportCeiling = reservation.lamportCeiling
      clock = OperationClock(
        peerKey: localPeerKey,
        initialSenderSequence: reservation.firstSenderSequence,
        initialLamport: reservation.initialLamport
      )
    }
    if transportTask == nil {
      // Subscribe before `transport.start` so events emitted during start are not missed
      // (Kotlin mirrors this with CoroutineStart.UNDISPATCHED).
      let events = transport.events
      transportTask = Task { [self] in
        for await event in events {
          do {
            try await handleTransportEvent(event)
          } catch is CancellationError {
            break
          } catch {
            // Guard every event. Without this, one unexpected failure while applying a remote
            // operation ends this consumer for good: local editing keeps working while every
            // further remote edit is silently ignored, with no repair path because the
            // transport's digest already counts the operation. Cancellation still ends the loop.
            self.error = messages.remoteUpdateFailed
          }
        }
      }
    }
    await transport.start(profile: profile)
    startedProfile = profile
    scheduleProfileUpdate(profile)
  }

  /// The local in-progress stroke is rendered by the canvas from its own active points, so it must
  /// not also live in the previews map (double-draw, TTL ghost on abandoned strokes). Inbound
  /// loopback previews are already filtered by peerKey != localPeerKey.
  public nonisolated func preview(
    gestureId: String,
    tool: DrawingTool,
    colorArgb: Int32,
    points: [LogicalPoint]
  ) {
    if points.isEmpty { return }
    if tool == .hand { return }
    guard isApprovedWhiteboardColor(colorArgb) else { return }
    guard transport.currentDiagnostics.editingReady else { return }
    let preview = LivePreview(
      peerKey: localPeerKey,
      tool: tool,
      colorArgb: colorArgb,
      points: points.suffix(Self.livePreviewSegmentPoints).map { $0.clamped() },
      expiresAtMillis: nowMillis() + previewTTLMillis,
      gestureId: gestureId
    )
    transport.sendLive(preview)
  }

  public func commit(
    tool: DrawingTool,
    colorArgb: Int32,
    points: [LogicalPoint],
    text: String = "",
    textFont: BoardTextFont = defaultTextFont,
    textSize: Int = defaultTextSize,
    gestureId: String = UUID().uuidString
  ) {
    if points.isEmpty { return }
    if tool == .hand { return }
    guard ensureEditingReady() else { return }
    guard isApprovedWhiteboardColor(colorArgb) else { return }
    if tool == .text && text.isBlankKotlin {
      error = messages.textCannotBeBlank
      return
    }
    let maximumPoints =
      tool == .eraser ? WhiteboardProtocol.maxEraserPoints : WhiteboardProtocol.maxOperationPoints
    let boundedPoints = BoardGeometry.evenlySample(points, maximumPoints: maximumPoints)
    if tool == .eraser {
      submit { id, stamp in
        .erase(
          BoardOperation.Erase(
            id: id,
            stamp: stamp,
            path: BoardGeometry.simplify(boundedPoints.map { $0.clamped() }),
            gestureId: gestureId
          )
        )
      }
      previews[localPeerKey] = nil
      return
    }
    let clampedPoints = boundedPoints.map { $0.clamped() }
    if (tool == .rectangle || tool == .ellipse)
      && (clampedPoints.first!.x == clampedPoints.last!.x
        || clampedPoints.first!.y == clampedPoints.last!.y)
    {
      return
    }
    submit { id, stamp in
      let objectId = ObjectId(origin: id)
      let boardObject: BoardObject
      switch tool {
      case .pen:
        boardObject = .freehand(
          BoardObject.Freehand(
            id: objectId,
            stamp: stamp,
            colorArgb: colorArgb,
            points: BoardGeometry.simplify(clampedPoints)
          )
        )
      case .line:
        boardObject = .line(
          BoardObject.Line(
            id: objectId,
            stamp: stamp,
            colorArgb: colorArgb,
            start: clampedPoints.first!,
            end: clampedPoints.last!
          )
        )
      case .rectangle:
        boardObject = .rectangle(
          BoardObject.Rectangle(
            id: objectId,
            stamp: stamp,
            colorArgb: colorArgb,
            start: clampedPoints.first!,
            end: clampedPoints.last!
          )
        )
      case .ellipse:
        boardObject = .ellipse(
          BoardObject.Ellipse(
            id: objectId,
            stamp: stamp,
            colorArgb: colorArgb,
            start: clampedPoints.first!,
            end: clampedPoints.last!
          )
        )
      case .text:
        boardObject = .text(
          BoardObject.Text(
            id: objectId,
            stamp: stamp,
            colorArgb: colorArgb,
            anchor: clampedPoints.first!,
            text: String(text.prefix(200)),
            size: textSize,
            font: textFont
          )
        )
      case .eraser:
        preconditionFailure("Handled above")
      case .hand:
        preconditionFailure("Hand is a local navigation tool")
      }
      return .commit(
        BoardOperation.Commit(id: id, stamp: stamp, boardObject: boardObject, gestureId: gestureId)
      )
    }
    previews[localPeerKey] = nil
  }

  public func clear() {
    guard ensureEditingReady() else { return }
    submit { id, stamp in .clear(BoardOperation.Clear(id: id, stamp: stamp)) }
  }

  public nonisolated func resolvePermissions(allGranted: Bool) {
    transport.resolvePermissions(allGranted: allGranted)
  }

  public nonisolated func setForeground(_ isForeground: Bool) {
    transport.setForeground(isForeground)
  }

  public func close() {
    profileTask?.cancel()
    transportTask?.cancel()
    diagnosticsTask?.cancel()
    sweepTask?.cancel()
    transport.close()
    boardStateContinuation.finish()
    previewsContinuation.finish()
    errorContinuation.finish()
  }

  private func ensureEditingReady() -> Bool {
    if transport.currentDiagnostics.editingReady { return true }
    error = messages.syncFinishing
    return false
  }

  private func scheduleProfileUpdate(_ profile: UserProfile) {
    profileTask?.cancel()
    profileTask = Task { [self] in
      for await current in transport.diagnostics where current.editingReady {
        if startedProfile == profile {
          submit { id, stamp in
            .profileUpdate(BoardOperation.ProfileUpdate(id: id, stamp: stamp, profile: profile))
          }
        }
        return
      }
    }
  }

  private func submit(_ create: @escaping @Sendable (OperationId, OperationStamp) -> BoardOperation) {
    guard var activeClock = clock else {
      error = messages.sessionStarting
      return
    }
    let next: (OperationId, OperationStamp)
    do {
      next = try activeClock.next()
    } catch {
      self.error = messages.operationClockExhausted
      return
    }
    clock = activeClock
    let (id, stamp) = next
    // Creation can include freehand simplification and reducer geometry. Keep it off the caller's
    // (normally main) thread; the preallocated stamp preserves user action order even when
    // background work completes out of order, and the reducer handles that deterministically.
    Task { [self] in
      let operation = create(id, stamp)
      if operation.stamp.lamport > WhiteboardProtocol.maxSessionLamport {
        error = messages.logicalTimeLimit
        return
      }
      do {
        try WhiteboardProtocol.validateOperation(operation)
      } catch {
        self.error = messages.editOutsideSafetyLimits
        return
      }
      guard let prepared = try? await prepareOperation(operation), prepared else { return }
      if await transport.sendReliable(operation) {
        applyPreparedOperation(operation)
      } else {
        error = messages.terminalResourceLimit
      }
    }
  }

  private func handleTransportEvent(_ event: TransportEvent) async throws {
    switch event {
    case .reliableOperationReceived(let operation, let prepared, let committed, let applied):
      var appliedFlag = false
      defer {
        prepared?.complete(false)
        applied?.complete(appliedFlag)
      }
      let preparedOK = try await prepareOperation(operation)
      prepared?.complete(preparedOK)
      guard preparedOK else { return }
      if let committed, try await committed.wait() == false { return }
      let changed = applyPreparedOperation(operation)
      appliedFlag = changed || boardState.operations[operation.id] != nil
    case .livePreviewReceived(let preview):
      if preview.peerKey != localPeerKey { receivePreview(preview) }
    case .snapshotMerged(let operations, let prepared, let committed, let applied):
      var appliedFlag = false
      defer {
        prepared?.complete(false)
        applied?.complete(appliedFlag)
      }
      if let maximumStamp = operations.map(\.stamp).max() {
        guard try await prepareClockFor(maximumStamp) else { return }
      }
      prepared?.complete(true)
      if let committed, try await committed.wait() == false { return }
      // Advance the clock before exposing transport readiness. The transport awaits the event
      // acknowledgement, preventing local edits from racing below snapshot history.
      boardState = BoardReducer.merge(boardState, operations: operations)
      for operation in operations {
        switch operation {
        case .commit(let commit): completeGesture(commit.id.senderPeerKey, commit.gestureId)
        case .erase(let erase): completeGesture(erase.id.senderPeerKey, erase.gestureId)
        default: break
        }
      }
      appliedFlag = true
    case .incompatiblePeer:
      break
    }
  }

  private func prepareOperation(_ operation: BoardOperation) async throws(CancellationError) -> Bool {
    if operation.stamp.lamport > WhiteboardProtocol.maxSessionLamport {
      error = messages.peerLogicalTimeLimit
      return false
    }
    return try await prepareClockFor(operation.stamp)
  }

  @discardableResult
  private func applyPreparedOperation(_ operation: BoardOperation) -> Bool {
    let nextState = BoardReducer.apply(boardState, operation: operation)
    let applied = nextState != boardState
    boardState = nextState
    guard applied else { return false }
    if case .commit(let commit) = operation,
      boardState.objects[commit.boardObject.id] == nil,
      boardState.objects.count >= maxRenderedBoardObjects
    {
      error = messages.visibleObjectLimit
    }
    switch operation {
    case .commit(let commit):
      completeGesture(commit.id.senderPeerKey, commit.gestureId)
    case .erase(let erase):
      completeGesture(erase.id.senderPeerKey, erase.gestureId)
    default:
      previews[operation.id.senderPeerKey] = nil
    }
    return true
  }

  private func prepareClockFor(_ stamp: OperationStamp) async throws(CancellationError) -> Bool {
    if stamp.lamport >= lamportCeiling {
      let reserved = try await runAsyncCatchingPreservingCancellation {
        try await reserveLamportAfter(stamp.lamport)
      }
      switch reserved {
      case .success(let ceiling):
        lamportCeiling = ceiling
      case .failure:
        error = messages.clockReservationFailed
        return false
      }
    }
    clock?.observe(stamp)
    return true
  }

  private func receivePreview(_ preview: LivePreview) {
    let currentSession = currentLiveSession[preview.peerKey]
    if preview.senderSessionId != currentSession {
      if retiredLiveSessions[preview.peerKey]?.contains(preview.senderSessionId) == true { return }
      if let oldSession = currentSession {
        var retired = retiredLiveSessions[preview.peerKey] ?? []
        retired.append(oldSession)
        while retired.count > 8 { retired.removeFirst() }
        retiredLiveSessions[preview.peerKey] = retired
      }
      currentLiveSession[preview.peerKey] = preview.senderSessionId
      lastLiveSequence[preview.peerKey] = 0
    }
    let previousSequence = lastLiveSequence[preview.peerKey] ?? 0
    if preview.frameSequence <= previousSequence { return }
    lastLiveSequence[preview.peerKey] = preview.frameSequence
    if completedGestures[preview.peerKey]?.contains(preview.gestureId) == true { return }

    let now = nowMillis()
    let prior = previews[preview.peerKey]
    var merged = preview
    if let prior, prior.gestureId == preview.gestureId, prior.expiresAtMillis > now {
      merged.points = Array(
        mergePreviewPoints(prior.points, preview.points).suffix(Self.maxPreviewPoints)
      )
    }
    previews[preview.peerKey] = merged
  }

  private func completeGesture(_ peerKey: String, _ gestureId: String) {
    var recent = completedGestures[peerKey] ?? []
    if !recent.contains(gestureId) { recent.append(gestureId) }
    while recent.count > 64 { recent.removeFirst() }
    completedGestures[peerKey] = recent
    if previews[peerKey]?.gestureId == gestureId {
      previews[peerKey] = nil
    }
  }

  private func mergePreviewPoints(
    _ previous: [LogicalPoint],
    _ incoming: [LogicalPoint]
  ) -> [LogicalPoint] {
    let maximumOverlap = min(previous.count, incoming.count)
    var overlap = 0
    if maximumOverlap > 0 {
      for count in stride(from: maximumOverlap, through: 1, by: -1) {
        if previous.suffix(count).elementsEqual(incoming.prefix(count)) {
          overlap = count
          break
        }
      }
    }
    return previous + incoming.dropFirst(overlap)
  }

  private func sweepExpiredPreviews() async {
    while !Task.isCancelled {
      try? await Task.sleep(nanoseconds: previewSweepIntervalMillis * 1_000_000)
      if Task.isCancelled { break }
      let now = nowMillis()
      previews = previews.filter { $0.value.expiresAtMillis > now }
      let retainedPeers = Set(transport.currentDiagnostics.peers.keys).union(previews.keys)
      lastLiveSequence = lastLiveSequence.filter { retainedPeers.contains($0.key) }
      currentLiveSession = currentLiveSession.filter { retainedPeers.contains($0.key) }
      retiredLiveSessions = retiredLiveSessions.filter { retainedPeers.contains($0.key) }
      completedGestures = completedGestures.filter { retainedPeers.contains($0.key) }
    }
  }

  private func observeDiagnostics() async {
    for await current in transport.diagnostics {
      if current.editingReady, let currentError = error,
        currentError == messages.syncFinishing || currentError == messages.sessionStarting
      {
        error = nil
      }
    }
  }

  private func acquireStartLock() async {
    if !startLocked {
      startLocked = true
      return
    }
    await withCheckedContinuation { startWaiters.append($0) }
  }

  private func releaseStartLock() {
    if !startWaiters.isEmpty {
      startWaiters.removeFirst().resume()
    } else {
      startLocked = false
    }
  }
}
