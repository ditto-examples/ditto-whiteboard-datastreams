import Foundation
import SpriteKit
import Anvil
import SwiftUI
import WhiteboardCore

/// Full-screen presence network viewer, ported from Edge Studio's
/// `PresenceViewerSK`: a SpriteKit mesh graph with Direct/full-mesh filtering,
/// focus mode, peer search, a detail card, a connection legend, and zoom/pan
/// controls. Presence data arrives via the `WhiteboardTransport.observePresenceGraph`
/// seam (unfiltered — the session's own topology filters to whiteboard peers, this
/// viewer shows the whole mesh).
struct PresenceViewerScreen: View {
  @State private var viewModel: ViewModel
  @State private var scene: PresenceNetworkScene?
  @Environment(\.dismiss) private var dismiss
  @Environment(\.dittoColors) private var colors

  init(viewModel: ViewModel) {
    _viewModel = State(initialValue: viewModel)
  }

  /// Production composition root: the app model provides the presence-graph
  /// observation and sync-status fetch from the live transport.
  init(appModel: AppModel) {
    self.init(viewModel: ViewModel(
      presenceRegistration: { handler in
        appModel.observePresenceGraph(handler)
      },
      syncStatusFetcher: {
        await appModel.peerSyncStatus()
      }
    ))
  }

  var body: some View {
    #if os(macOS)
    content
    #else
    NavigationStack {
      content
    }
    #endif
  }

  private var content: some View {
      ZStack(alignment: .bottomLeading) {
        // SpriteKit scene
        SpriteKitSceneView(scene: $scene, viewModel: viewModel)
          .frame(maxWidth: .infinity, maxHeight: .infinity)
        #if os(macOS)
          .focusable() // Allow view to receive keyboard and scroll events
        #endif

        // Connection-types legend (bottom-leading corner overlay).
        if viewModel.controlsVisible {
          connectionLegend
            .padding(.leading, 16)
            .padding(.bottom, 76)
            .transition(.opacity)
        }

        // Focus banner (top-center) — "Focused on <label>" pill with an exit
        // button. Focus mode is only reachable in the full-mesh (Direct OFF) view.
        if let focusedName = viewModel.focusedPeerName {
          VStack {
            HStack(spacing: 8) {
              Text("Focused on **\(focusedName)**")
                .font(.caption)
                .foregroundStyle(.primary)
              Button {
                viewModel.exitFocusMode()
              } label: {
                Image(systemName: "xmark.circle.fill")
                  .font(.system(size: 13))
                  .foregroundStyle(.secondary)
              }
              .buttonStyle(.plain)
              .accessibilityLabel("Exit focus")
              .accessibilityIdentifier("FocusModeExitButton")
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(.ultraThinMaterial)
            .clipShape(Capsule())
            Spacer()
          }
          .frame(maxWidth: .infinity)
          .padding(.top, 12)
          .transition(.move(edge: .top).combined(with: .opacity))
        }

        // Detail card — centred in the viewport, above the SpriteKit scene rather
        // than inside it, so it is never scaled by the camera and never reaches the
        // layout engine's peerFootprints.
        if let detail = viewModel.openPeerDetail {
          PeerDetailCardView(
            detail: detail,
            onFocusPeer: viewModel.canFocusOpenPeer ? { viewModel.focusOpenPeer() } : nil
          )
          // Tap-to-close is attached BEFORE the centring frame, so the gesture
          // covers the card's own bounds only. Attaching it after would make the
          // full-size frame swallow taps beside the card, which must still reach
          // the scene as canvas taps (dismiss, or exit focus when no card is open).
          .onTapGesture { viewModel.dismissDetail() }
          .frame(maxWidth: .infinity, maxHeight: .infinity)
          .transition(.opacity.combined(with: .scale(scale: 0.97)))
        }
      }
      .overlay(alignment: .topTrailing) {
        PresencePeerSearchField(viewModel: viewModel)
          .padding(12)
      }
      .overlay(alignment: .bottom) {
        PresenceViewerToolbarControls(viewModel: viewModel)
          .padding(.bottom, 12)
      }
      .background(colors.background)
      .tint(colors.fillBrandPrimary)
      .animation(.easeInOut(duration: 0.2), value: viewModel.focusedPeerName)
      .animation(.easeInOut(duration: 0.15), value: viewModel.detailPeerKey)
      #if os(macOS)
        // Escape with focus on the canvas (a mouse pick moves focus onto a
        // results row, and an open detail card never had an Escape route at all).
        // The search box carries its own `.onKeyPress(.escape)` for the
        // still-typing case; both land on the same unwind order.
        .onExitCommand { viewModel.handleEscape() }
      #endif
      .navigationTitle("Presence Graph")
      #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
      #endif
      #if !os(macOS)
      .toolbar {
        ToolbarItem(placement: .confirmationAction) {
          Button("Done") { dismiss() }
            .accessibilityIdentifier("closePresenceViewerButton")
        }
      }
      #endif
      .onAppear {
        createScene()
      }
      .task {
        // Start presence observation tied to the view's lifetime via
        // structured concurrency, rather than an untracked Task in the
        // ViewModel's init that can race view teardown.
        await viewModel.startProductionMode()
      }
      .onDisappear {
        // Stop the presence observer here rather than relying on ViewModel ARC
        // dealloc: the observation token holds the Ditto observer alive.
        viewModel.stopProductionMode()
        cleanupScene()
      }
  }

  // MARK: - Connection Legend

  /// Connection types legend showing dash patterns and colors
  private var connectionLegend: some View {
    VStack(alignment: .leading, spacing: 8) {
      Text("Connection Types")
        .font(.caption)
        .fontWeight(.semibold)

      LegendRow(color: PresenceConnectionType.bluetooth.cardColor, pattern: "● ● ●", label: "Bluetooth")
      LegendRow(color: PresenceConnectionType.accessPoint.cardColor, pattern: "████ ████", label: "LAN")
      LegendRow(color: PresenceConnectionType.p2pWiFi.cardColor, pattern: "██ ██ ██", label: "P2P WiFi")
      LegendRow(color: PresenceConnectionType.webSocket.cardColor, pattern: "███·███·", label: "WebSocket")
      LegendRow(color: PresenceConnectionType.multicast.cardColor, pattern: "· · · · · ·", label: "Multicast")
      LegendRow(color: PresenceViewerColors.cloudCardColor, pattern: "████ ○ ████", label: "Cloud")
    }
    .padding(12)
    .background(.ultraThinMaterial)
    .cornerRadius(8)
  }

  // MARK: - Scene Management

  /// Creates and configures the SpriteKit scene
  private func createScene() {
    let newScene = PresenceNetworkScene()

    // Configure scene size (larger for better quality)
    newScene.size = CGSize(width: 1000, height: 800)
    newScene.scaleMode = .aspectFill

    // Configure initial zoom level
    newScene.initialZoomLevel = viewModel.zoomLevel

    // Apply persisted VM-level display preferences to the fresh scene
    newScene.backgroundEffectsEnabled = viewModel.backgroundEffectsEnabled

    // Set up zoom change callback
    newScene.onZoomChanged = { [weak viewModel] newZoom in
      viewModel?.updateZoomLevel(newZoom)
    }

    // Focus-mode banner state. Both hoisted fields update together so the banner
    // and the post-rebuild focus restore never disagree.
    newScene.onFocusChanged = { [weak viewModel] key, name in
      viewModel?.focusedPeerKey = key
      viewModel?.focusedPeerName = name
      // Focus ending takes any open card with it — the card belongs to a focus
      // session, and a card anchored to one that has ended is stale.
      if key == nil {
        viewModel?.dismissDetail()
      }
    }

    // In focus mode a tap means "show me this peer".
    newScene.onPeerDetailRequested = { [weak viewModel] key in
      viewModel?.toggleDetail(for: key)
    }

    // Empty-canvas tap while a card is open dismisses the card and keeps focus.
    newScene.onDetailDismissRequested = { [weak viewModel] in
      viewModel?.dismissDetail()
    }

    // A rebuilt scene starts with no card; keep its flag in step with the VM.
    newScene.hasOpenDetailCard = viewModel.detailPeerKey != nil

    scene = newScene
    viewModel.scene = newScene

    // A rebuilt scene starts with no search dim; re-apply the live query now rather
    // than waiting on the next presence push.
    viewModel.reapplySearchMatchesToScene()

    // Note: Camera zoom will be applied after scene is presented (in didMove(to:))
  }

  /// Cleanup SpriteKit resources when view disappears
  private func cleanupScene() {
    scene?.removeAllChildren()
    scene?.removeAllActions()
    scene?.removeFromParent()
    scene = nil
  }
}

// MARK: - Legend Row Component

/// Single row in the connection legend showing color, pattern, and label
struct LegendRow: View {
  let color: Color
  let pattern: String
  let label: String

  var body: some View {
    HStack(spacing: 8) {
      Circle()
        .fill(color)
        .frame(width: 8, height: 8)

      Text(pattern)
        .font(.system(.caption, design: .monospaced))
        .foregroundStyle(color)

      Text(label)
        .font(.caption)
    }
  }
}

// MARK: - SpriteKit Scene View (Platform-Specific)

#if os(macOS)
  /// Custom SKView subclass that properly forwards scroll events to the scene
  final class ScrollableSKView: SKView {
    override var acceptsFirstResponder: Bool { true }

    override func scrollWheel(with event: NSEvent) {
      // Forward scroll events to the scene
      scene?.scrollWheel(with: event)
    }
  }

  /// NSViewRepresentable wrapper for SKView with scroll event handling
  struct SpriteKitSceneView: NSViewRepresentable {
    @Binding var scene: PresenceNetworkScene?
    let viewModel: PresenceViewerScreen.ViewModel

    func makeNSView(context: Context) -> ScrollableSKView {
      let skView = ScrollableSKView()
      skView.ignoresSiblingOrder = true
      skView.showsFPS = false
      skView.showsNodeCount = false
      skView.allowsTransparency = true

      if let scene {
        skView.presentScene(scene)
      }

      // Ensure view can become first responder and receive scroll events
      DispatchQueue.main.async {
        skView.window?.makeFirstResponder(skView)
      }

      return skView
    }

    func updateNSView(_ nsView: ScrollableSKView, context: Context) {
      if let scene, nsView.scene !== scene {
        nsView.presentScene(scene)
      }
    }
  }

#else
  // iOS / iPadOS

  /// UIViewRepresentable wrapper for SKView with pinch-to-zoom support
  struct SpriteKitSceneView: UIViewRepresentable {
    @Binding var scene: PresenceNetworkScene?
    let viewModel: PresenceViewerScreen.ViewModel

    func makeCoordinator() -> Coordinator {
      Coordinator(viewModel: viewModel)
    }

    func makeUIView(context: Context) -> SKView {
      let skView = SKView()
      skView.ignoresSiblingOrder = true
      skView.showsFPS = false
      skView.showsNodeCount = false
      skView.allowsTransparency = true

      if let scene {
        skView.presentScene(scene)
      }

      // Add pinch gesture for zoom
      let pinch = UIPinchGestureRecognizer(
        target: context.coordinator,
        action: #selector(Coordinator.handlePinch(_:))
      )
      skView.addGestureRecognizer(pinch)

      return skView
    }

    func updateUIView(_ uiView: SKView, context: Context) {
      if let scene, uiView.scene !== scene {
        uiView.presentScene(scene)
      }
      context.coordinator.scene = scene
    }

    // @MainActor: UIKit delivers gesture actions on the main thread, and the
    // handler touches main-actor-isolated UIKit/SpriteKit APIs.
    @MainActor
    final class Coordinator: NSObject {
      let viewModel: PresenceViewerScreen.ViewModel
      weak var scene: PresenceNetworkScene?

      init(viewModel: PresenceViewerScreen.ViewModel) {
        self.viewModel = viewModel
      }

      @objc func handlePinch(_ gesture: UIPinchGestureRecognizer) {
        guard gesture.state == .changed else { return }
        scene?.adjustZoom(by: gesture.scale)
        gesture.scale = 1.0 // Reset to get incremental deltas
      }
    }
  }
#endif

// MARK: - ViewModel

extension PresenceViewerScreen {
  /// ViewModel for the presence viewer: manages presence-graph observation and
  /// scene state. The presence source and sync-status fetcher are injected so
  /// tests can drive the VM with fakes instead of a live Ditto instance.
  @MainActor
  @Observable
  final class ViewModel {
    /// Registers a presence-graph observer and returns its cancellation token —
    /// the `WhiteboardTransport.observePresenceGraph` seam's shape.
    typealias PresenceRegistration =
      (@escaping @Sendable (PresenceGraphSnapshot) -> Void) -> PresenceGraphObservationToken

    // MARK: - Published State

    /// When true, only peers directly connected to this device are shown
    var showDirectConnectedOnly = true {
      didSet {
        updateSceneWithCurrentFilter()
      }
    }

    /// Current SpriteKit camera **scale**, which is the INVERSE of magnification:
    /// a larger scale shows more of the scene. Range 0.5 … 4.0, i.e. 200% … 25%.
    /// Use ``zoomPercent`` for anything shown to the user.
    var zoomLevel: CGFloat = 1.0

    /// Magnification as a whole percentage, for display.
    var zoomPercent: Int {
      guard zoomLevel > 0 else { return 100 }
      return Int((100 / zoomLevel).rounded())
    }

    /// Display name of the focused peer (focus mode, full-mesh view only).
    /// Drives the top banner; nil when no peer is focused.
    var focusedPeerName: String?

    /// Key of the focused peer, hoisted so an active focus session survives
    /// the scene being torn down and recreated. Always updated together with
    /// `focusedPeerName` via the scene's `onFocusChanged`.
    var focusedPeerKey: String?

    /// The peer whose detail card is open, or nil. Accordion semantics: opening one
    /// closes the other, because two centred overlays would occlude each other.
    var detailPeerKey: String?

    /// Sync rows for the open card, keyed by peer key. Refreshed alongside each
    /// presence push. Only directly connected peers ever appear:
    /// `system:data_sync_info` is a local table computed from where this device
    /// actually receives data, so it has no row for an indirect peer or for
    /// ourselves — which is exactly what the card's three-way sync section reports.
    private(set) var syncStatusByPeerKey: [String: PeerSyncStatus] = [:]

    /// Detail for the open card, or nil when no card is open (or its peer has left
    /// the mesh — a card anchored to a peer that is gone must not survive).
    var openPeerDetail: PresencePeerDetail? {
      guard let key = detailPeerKey, let localPeer = rawLocalPeer else { return nil }
      let isLocal = key == localPeer.peerKeyString
      guard let peer = isLocal ? localPeer : rawRemotePeers.first(where: { $0.peerKeyString == key }) else {
        return nil
      }
      let directKeys = PresenceEdgeAggregator.directVisiblePeerKeys(
        localPeer: localPeer,
        remotePeers: rawRemotePeers
      )
      return PresencePeerDetail(
        peer: peer,
        isLocal: isLocal,
        isDirectlyConnected: directKeys.contains(key),
        syncStatus: syncStatusByPeerKey[key]
      )
    }

    /// Whether the open card's peer can be focused from here — not already focused,
    /// and not the local device (which is never a valid focus target).
    var canFocusOpenPeer: Bool {
      guard let key = detailPeerKey, let localPeer = rawLocalPeer else { return false }
      return key != focusedPeerKey && key != localPeer.peerKeyString
    }

    // MARK: - Peer Search

    /// Query typed into the search box. Owned here rather than in the view so it
    /// survives the scene being torn down and recreated.
    var searchQuery = "" {
      didSet {
        guard searchQuery != oldValue else { return }
        pushSearchMatchesToScene()
      }
    }

    /// Whether the box holds a real query (whitespace alone does not count).
    var searchIsActive: Bool {
      PresencePeerSearch.isActive(query: searchQuery)
    }

    /// Rows for the results card. Empty while `searchIsActive` is true means
    /// "no peers match" — a distinct state from "not searching".
    var searchMatches: [PresencePeerSearchMatch] {
      PresencePeerSearch.matches(in: searchCandidates, query: searchQuery)
    }

    /// Rebuilt on each (throttled) presence push rather than per keystroke:
    /// at 100+ peers this is the only part of matching worth not repeating.
    private var searchCandidates: [PresencePeerSearchMatch] = []

    /// Armed by a search pick made while Direct is ON — the peer is not in the
    /// scene yet, so the focus has to wait for the rebuilt full-mesh push.
    private var pendingFocusPeerKey: String?

    /// Focus a search hit exactly as clicking its pill in the full mesh would.
    ///
    /// With Direct ON the peer may not be in the scene at all (that is the
    /// whole point of searching for it), so this flips Direct off and defers
    /// the focus to the rebuilt graph.
    func focusSearchResult(_ key: String) {
      // The local peer is listed in the card but never focusable.
      guard key != rawLocalPeer?.peerKeyString else { return }
      if showDirectConnectedOnly {
        pendingFocusPeerKey = key
        showDirectConnectedOnly = false // didSet → updateSceneWithCurrentFilter()
        return
      }
      // A card belongs to the focus session that was open when it was
      // raised; changing (or leaving) focus takes it along. `focusPeer`
      // toggles focus OFF when the pick is the already-focused peer, so
      // this cleanup has to run on both branches.
      detailPeerKey = nil
      scene?.hasOpenDetailCard = false
      scene?.focusPeer(key)
    }

    /// Enter/Return in the box focuses the first focusable hit — the
    /// "find a peer without touching the mouse" path.
    func focusFirstSearchResult() {
      guard let first = searchMatches.first(where: { !$0.isLocal }) else { return }
      focusSearchResult(first.key)
    }

    /// Clear the query and restore full opacity. The focus view (if any) is
    /// deliberately left alone.
    func clearSearch() {
      searchQuery = ""
    }

    /// Escape unwinds the **innermost** context first: an open detail card,
    /// then the search query. The focus view survives both — backing out of a
    /// card is not backing out of the peer you are investigating.
    ///
    /// Returns whether anything was consumed, so a key handler can report
    /// `.ignored` and let Escape do its normal job when there is nothing to
    /// unwind.
    @discardableResult
    func handleEscape() -> Bool {
      if detailPeerKey != nil {
        dismissDetail()
        return true
      }
      if searchIsActive {
        clearSearch()
        return true
      }
      return false
    }

    /// Re-apply the live query to a freshly built scene.
    func reapplySearchMatchesToScene() {
      pushSearchMatchesToScene()
    }

    /// Push the current match set to the scene.
    ///
    /// `nil` when the box is empty; an **empty set** when the query has no
    /// hits, which dims the whole graph. Those two are different states and
    /// conflating them is the defect this method exists to avoid.
    private func pushSearchMatchesToScene() {
      scene?.setSearchMatches(
        searchIsActive ? Set(searchMatches.map(\.key)) : nil
      )
    }

    /// Whether the graph controls (legend, Direct toggle, zoom cluster) are
    /// visible. The eye button and reset control always remain.
    var controlsVisible = true

    /// Whether the floating-squares background renders + animates.
    var backgroundEffectsEnabled = true {
      didSet {
        scene?.backgroundEffectsEnabled = backgroundEffectsEnabled
      }
    }

    // MARK: - Scene Reference

    /// Reference to the SpriteKit scene for updates
    weak var scene: PresenceNetworkScene?

    // MARK: - Private State

    /// Registration handle for the injected presence source.
    private var presenceToken: PresenceGraphObservationToken?

    /// Pending throttled scene update. Presence pushes are throttled to one
    /// scene update per 250 ms fixed window so connect/disconnect flapping
    /// can't thrash layout animations. The Direct toggle path
    /// (`updateSceneWithCurrentFilter` from `didSet`) stays immediate.
    private var presencePushTask: Task<Void, Never>?

    /// Raw local peer from the presence graph
    private var rawLocalPeer: (any PeerProtocol)?

    /// All remote peers from the presence graph (unfiltered)
    private var rawRemotePeers: [any PeerProtocol] = []

    private let presenceRegistration: PresenceRegistration?
    private let syncStatusFetcher: @Sendable () async -> [String: PeerSyncStatus]

    // MARK: - Initialization

    init(
      presenceRegistration: PresenceRegistration? = nil,
      syncStatusFetcher: @escaping @Sendable () async -> [String: PeerSyncStatus] = { [:] }
    ) {
      self.presenceRegistration = presenceRegistration
      self.syncStatusFetcher = syncStatusFetcher
    }

    // MARK: - Production Mode (Live Presence)

    /// Start observing the presence graph
    func startProductionMode() async {
      // The enclosing `.task {}` is cancelled when the view disappears.
      // Check before and after registering so a rapid appear→disappear can't
      // leave an observer that `stopProductionMode()` already ran past.
      guard !Task.isCancelled, presenceToken == nil, let presenceRegistration else { return }
      let token = presenceRegistration { [weak self] snapshot in
        // Presence callbacks may fire on a background thread — hop to main before
        // touching @MainActor state or any SpriteKit node tree APIs.
        Task { @MainActor [weak self] in
          self?.applyPresenceSnapshot(snapshot)
        }
      }
      guard !Task.isCancelled else {
        token.cancel()
        return
      }
      presenceToken = token
    }

    /// Stop observing the presence graph
    func stopProductionMode() {
      presenceToken?.cancel()
      presenceToken = nil
      presencePushTask?.cancel()
      presencePushTask = nil
    }

    /// Record a pushed graph and arm the fixed-window throttle: the first push in
    /// a window arms a 250 ms flush; later pushes only replace the stored graph;
    /// the flush applies the latest at window end. A cancel-and-re-sleep debounce
    /// would starve the graph under sustained <250 ms churn. Internal (not private)
    /// so tests can drive the VM with synthetic snapshots.
    func applyPresenceSnapshot(_ snapshot: PresenceGraphSnapshot) {
      rawLocalPeer = snapshot.localPeer
      rawRemotePeers = snapshot.remotePeers
      if presencePushTask == nil {
        presencePushTask = Task { @MainActor [weak self] in
          try? await Task.sleep(for: .milliseconds(250))
          guard let self else { return }
          presencePushTask = nil
          guard !Task.isCancelled else { return }
          updateSceneWithCurrentFilter()
          // Sync rows ride the same 250 ms window as the graph push, so an open
          // card's commit id stays current without adding a second cadence.
          await refreshSyncStatus()
        }
      }
    }

    // MARK: - Filtering

    /// Push the current filtered graph state to the scene
    func updateSceneWithCurrentFilter() {
      // Rebuilt before the guard below, so a query typed before the scene
      // exists still has candidates.
      //
      // The set is the FULL MESH, never the Direct-mode projection: a
      // multi-hop peer has to be findable while Direct is on, because picking
      // it is exactly how the user jumps the graph over to it.
      //
      // But it is the full mesh *as the scene will actually render it* —
      // `meshVisiblePeerKeys`, the same filter `peersToShow` uses below — not
      // the raw peer list. `PresenceEdgeAggregator` drops edgeless "orphan"
      // peers, and they never become nodes. Listing them made rows that flip
      // Direct off and then silently fail to focus, because the peer is not in
      // the scene at all.
      searchCandidates = if let localPeer = rawLocalPeer {
        PresencePeerSearch.candidates(
          localPeer: localPeer,
          remotePeers: {
            let visible = PresenceEdgeAggregator.meshVisiblePeerKeys(
              localPeer: localPeer,
              remotePeers: rawRemotePeers
            )
            return rawRemotePeers.filter { visible.contains($0.peerKeyString) }
          }()
        )
      } else {
        []
      }

      guard let localPeer = rawLocalPeer, let scene else { return }

      // Both modes derive the visible set from the aggregated edges, which
      // include the local peer's own advertised connections — never from
      // each remote peer's connection list alone, which would hide a peer
      // whose edge only the local side advertises (the multicast
      // asymmetry). Expanded mode additionally drops orphan peers (those
      // in no edge at all) so the sync stop→start window doesn't render
      // floating pills. The local peer is always shown.
      let visibleKeys: Set<String> = if showDirectConnectedOnly {
        PresenceEdgeAggregator.directVisiblePeerKeys(
          localPeer: localPeer,
          remotePeers: rawRemotePeers
        )
      } else {
        PresenceEdgeAggregator.meshVisiblePeerKeys(
          localPeer: localPeer,
          remotePeers: rawRemotePeers
        )
      }
      let peersToShow = rawRemotePeers.filter { visibleKeys.contains($0.peerKeyString) }

      // Sync the filter flag to the scene so it can suppress remote-to-remote edges
      scene.showDirectConnectedOnly = showDirectConnectedOnly
      scene.updatePresenceGraph(localPeer: localPeer, remotePeers: peersToShow)

      // Focus survives a scene rebuild: re-enter the hoisted focus once the fresh
      // scene has graph state — or clear the hoist (via onFocusChanged) when the
      // peer is gone / Direct mode is on.
      if let focusedKey = focusedPeerKey, scene.focusedPeerKey == nil {
        scene.restoreFocusAfterRebuild(for: focusedKey)
      }

      // A search pick that had to flip Direct off lands here, once the
      // rebuilt full-mesh push guarantees the peer is in the scene. Cleared
      // unconditionally: a peer that left the mesh in the meantime must not
      // leave the request armed for the next unrelated push.
      if let pending = pendingFocusPeerKey {
        pendingFocusPeerKey = nil
        detailPeerKey = nil
        scene.hasOpenDetailCard = false
        scene.focusPeer(pending)
      }

      // The scene may have been rebuilt while this view model was not — re-apply
      // the live query to the fresh scene, or its dimming would silently
      // disappear on the round trip.
      pushSearchMatchesToScene()
    }

    // MARK: - Zoom Control

    /// Zoom in (decrease scale value)
    func zoomIn() {
      let newZoom = max(0.5, zoomLevel - 0.1)
      updateZoomLevel(newZoom)
    }

    /// Zoom out (increase scale value)
    func zoomOut() {
      // 4.0 camera scale = 0.25 minimum magnification — the deep zoom-out a
      // large full-mesh layout needs.
      let newZoom = min(4.0, zoomLevel + 0.1)
      updateZoomLevel(newZoom)
    }

    /// Update the camera scale and apply it to the scene camera.
    /// - Parameter level: New camera **scale** (0.5 … 4.0 = 200% … 25% magnification).
    func updateZoomLevel(_ level: CGFloat) {
      zoomLevel = level
      scene?.camera?.setScale(level)
    }

    /// Reset the camera to origin at 100% zoom and snap dragged peers back to their
    /// layout-computed positions. Backs the reset button in the overlay.
    func recenterView() {
      scene?.resetCameraAndRelayout()
      // Local zoom mirror — the scene also fires onZoomChanged(1.0), but updating
      // here makes the % readout flip instantly even if the SK animation lags.
      zoomLevel = 1.0
    }

    /// Exit focus mode (the banner's ✕ button). Any open card goes with it — it is
    /// anchored to a focus session that no longer exists.
    func exitFocusMode() {
      detailPeerKey = nil
      scene?.hasOpenDetailCard = false
      scene?.exitFocusMode()
    }

    /// Toggle the detail card for `key` (accordion: same peer closes, another swaps).
    func toggleDetail(for key: String) {
      detailPeerKey = (detailPeerKey == key) ? nil : key
      scene?.hasOpenDetailCard = detailPeerKey != nil
    }

    /// Dismiss the open card without leaving focus.
    func dismissDetail() {
      detailPeerKey = nil
      scene?.hasOpenDetailCard = false
    }

    /// Focus the peer whose card is open (the card's labelled action).
    func focusOpenPeer() {
      guard let key = detailPeerKey else { return }
      dismissDetail()
      // `focusPeer`, NOT `restoreFocusAfterRebuild`. The latter's first guard is
      // `focusedPeerKey == nil`, and this card can only be open while a focus is
      // active — so that guard could never pass and this labelled action closed
      // the card without ever focusing anything. `focusPeer` is the path that can
      // replace an active focus.
      scene?.focusPeer(key)
    }

    /// Refresh the sync rows. Degrades to the fetcher's own fallback (last-known
    /// for the Ditto transport, empty otherwise) rather than failing the card:
    /// the presence-graph half is still worth showing when sync is stopped.
    func refreshSyncStatus() async {
      syncStatusByPeerKey = await syncStatusFetcher()
    }
  }
}

// MARK: - Floating Toolbar Controls

/// Bottom-center capsule with the Direct toggle, reset, ± zoom, background-effects
/// toggle, and the controls-visibility eye — ported from Edge Studio's
/// `PresenceViewerToolbarControls` (there it rode a `DetailBottomBar`; here a
/// simple `.regularMaterial` capsule does the same job).
struct PresenceViewerToolbarControls: View {
  @Bindable var viewModel: PresenceViewerScreen.ViewModel

  var body: some View {
    HStack(spacing: 12) {
      if viewModel.controlsVisible {
        // Direct toggle.
        Toggle("Direct", isOn: $viewModel.showDirectConnectedOnly)
          .toggleStyle(.switch)
          .font(.caption)
          .fixedSize()
          .help("Show only peers directly connected to this device")
          .accessibilityIdentifier("PresenceDirectToggle")

        Divider()
          .frame(height: 18)
      }

      // Reset (recenter + 100% zoom) — always visible.
      Button(action: { viewModel.recenterView() }, label: {
        Image(systemName: "scope")
          .font(.system(size: 14))
          .frame(minWidth: 32, minHeight: 32)
          .contentShape(Rectangle())
      })
      .buttonStyle(.plain)
      .accessibilityLabel("Reset view")
      .accessibilityIdentifier("PresenceResetViewButton")
      .help("Reset view — recenter and zoom to 100%")

      if viewModel.controlsVisible {
        // Zoom out.
        Button(action: { viewModel.zoomOut() }, label: {
          Image(systemName: "minus")
            .font(.system(size: 14))
            .frame(minWidth: 32, minHeight: 32)
            .contentShape(Rectangle())
        })
        .buttonStyle(.plain)
        .disabled(viewModel.zoomLevel >= 4.0)
        .accessibilityLabel("Zoom out")
        .help("Zoom out (or use scroll wheel)")

        // Zoom level readout. `zoomLevel` is the SKCameraNode SCALE, and
        // magnification is its inverse — a larger scale shows MORE of the scene.
        // User-facing range is 25%–200% (camera scale 0.5–4.0).
        Text("\(viewModel.zoomPercent)%")
          .font(.system(size: 12, design: .monospaced))
          .frame(width: 40, alignment: .center)
          .accessibilityLabel("Zoom level \(viewModel.zoomPercent) percent")

        // Zoom in.
        Button(action: { viewModel.zoomIn() }, label: {
          Image(systemName: "plus")
            .font(.system(size: 14))
            .frame(minWidth: 32, minHeight: 32)
            .contentShape(Rectangle())
        })
        .buttonStyle(.plain)
        .disabled(viewModel.zoomLevel <= 0.5)
        .accessibilityLabel("Zoom in")
        .help("Zoom in (or use scroll wheel)")

        Divider()
          .frame(height: 18)

        // Background effects toggle.
        Button(action: { viewModel.backgroundEffectsEnabled.toggle() }, label: {
          Image(systemName: viewModel.backgroundEffectsEnabled ? "sparkles" : "sparkle")
            .font(.system(size: 14))
            .frame(minWidth: 32, minHeight: 32)
            .contentShape(Rectangle())
        })
        .buttonStyle(.plain)
        .accessibilityLabel("Toggle background effects")
        .help(viewModel.backgroundEffectsEnabled ? "Hide background effects" : "Show background effects")
      }

      // Controls-visibility (eye) toggle — always visible: hides/shows the
      // legend + Direct toggle + zoom cluster.
      Button(action: { viewModel.controlsVisible.toggle() }, label: {
        Image(systemName: viewModel.controlsVisible ? "eye" : "eye.slash")
          .font(.system(size: 14))
          .frame(minWidth: 32, minHeight: 32)
          .contentShape(Rectangle())
      })
      .buttonStyle(.plain)
      .accessibilityLabel("Toggle controls visibility")
      .help(viewModel.controlsVisible ? "Hide graph controls" : "Show graph controls")
    }
    .padding(.horizontal, 14)
    .padding(.vertical, 6)
    .background(.regularMaterial, in: Capsule())
  }
}

// MARK: - Preview

#Preview {
  PresenceViewerScreen(viewModel: PresenceViewerScreen.ViewModel())
    .frame(width: 1000, height: 800)
}
