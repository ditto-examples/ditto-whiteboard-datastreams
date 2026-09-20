import Anvil
import SwiftUI
import WhiteboardCore

struct TroubleshootingScreen: View {
  let diagnostics: TransportDiagnostics
  /// When provided, a "Presence graph" button opens the presence viewer.
  var appModel: AppModel?
  /// Embedded mode drops the NavigationStack/title/toolbar (the host inspector
  /// supplies its own chrome) and shows the presence-graph link inline.
  var embedded: Bool = false
  @Environment(\.dismiss) private var dismiss
  @Environment(\.dittoColors) private var colors
  @State private var debugTransports = DebugTransportSettings.load()
  #if os(macOS)
  @Environment(\.openWindow) private var openWindow
  #endif
  @State private var showPresenceViewer = false

  private var peers: [PeerDiagnostics] {
    diagnostics.peers.values.sorted { $0.peerKey < $1.peerKey }
  }

  var body: some View {
    if embedded {
      content
    } else {
      NavigationStack {
        content
          .navigationTitle("Troubleshooting")
          .toolbar {
            ToolbarItem(placement: .confirmationAction) {
              Button("Done") { dismiss() }
                .accessibilityIdentifier("closeTroubleshootingButton")
            }
          }
      }
    }
  }

  private var content: some View {
    List {
        Section("Session") {
          LabeledContent(
            "Mode",
            value: diagnostics.mode == .localPreview ? "Local preview" : "Ditto nearby mesh"
          )
          LabeledContent(
            "State",
            value: diagnostics.running ? "Session running" : "Session stopped"
          )
          if let message = diagnostics.connectivityMessage {
            Text(message)
              .font(.footnote)
              .foregroundStyle(colors.foregroundWarning)
          }
        }
        Section {
          Toggle("Bluetooth LE", isOn: debugBinding(\.bluetoothLEEnabled))
            .toggleStyle(.ditto)
          Toggle("LAN", isOn: debugBinding(\.lanEnabled))
            .toggleStyle(.ditto)
          Toggle("mDNS", isOn: debugBinding(\.mdnsEnabled))
            .toggleStyle(.ditto)
            .disabled(!debugTransports.lanEnabled)
          Toggle("LAN multicast", isOn: debugBinding(\.multicastEnabled))
            .toggleStyle(.ditto)
            .disabled(!debugTransports.lanEnabled)
          Toggle("AWDL", isOn: debugBinding(\.awdlEnabled))
            .toggleStyle(.ditto)
        } header: {
          Text("Debug transports")
        } footer: {
          Text("Applied at startup — restart the app after changing.")
        }
        if peers.isEmpty {
          Section("Peers") {
            Text("No nearby peers.")
              .foregroundStyle(.secondary)
          }
        } else {
          ForEach(peers, id: \.peerKey) { peer in
            Section(peer.displayName ?? "Nearby peer \(peer.peerKey.suffix(8))") {
              LabeledContent("Peer key", value: peer.peerKey)
                .font(.footnote)
              LabeledContent(
                "Transports",
                value: peer.transports.isEmpty
                  ? "No direct transport" : peer.transports.sorted().joined(separator: ", ")
              )
              LabeledContent("wb_live", value: peer.liveConnected ? "Connected" : "Offline")
              LabeledContent("wb_state", value: peer.stateConnected ? "Connected" : "Offline")
              LabeledContent(
                "Snapshot",
                value:
                  "\(snapshotStatusLabel(peer.snapshotStatus)) · \(Int((Double(peer.snapshotProgress) * 100).rounded()))%"
              )
              LabeledContent(
                "Traffic",
                value: String(
                  format: "TX %.1f/s · RX %.1f/s",
                  peer.transmitMessagesPerSecond,
                  peer.receiveMessagesPerSecond
                )
              )
              if let lastError = peer.lastError {
                Text(lastError)
                  .font(.footnote)
                  .foregroundStyle(.red)
              }
            }
          }
        }
        if !diagnostics.incompatiblePeers.isEmpty {
          Section("Incompatible protocol versions") {
            ForEach(
              diagnostics.incompatiblePeers.sorted(by: { $0.key < $1.key }),
              id: \.key
            ) { peerKey, version in
              Text("\(peerKey.suffix(8)) · protocol \(version)")
                .foregroundStyle(.red)
            }
          }
        }
        if embedded, appModel != nil {
          Section {
            Button {
              #if os(macOS)
              openWindow(id: "presence-graph")
              #else
              showPresenceViewer = true
              #endif
            } label: {
              Label("Open presence graph", systemImage: "dot.radiowaves.left.and.right")
            }
            .accessibilityIdentifier("troubleshootingPresenceGraphButton")
          }
        }
      }
      #if !os(macOS)
      .fullScreenCover(isPresented: $showPresenceViewer) {
        if let appModel {
          PresenceViewerScreen(appModel: appModel)
        }
      }
      #endif
  }

  private func debugBinding(_ keyPath: WritableKeyPath<DebugTransportSettings, Bool>) -> Binding<Bool> {
    Binding(
      get: { debugTransports[keyPath: keyPath] },
      set: { newValue in
        debugTransports[keyPath: keyPath] = newValue
        debugTransports.save()
      }
    )
  }

  private func snapshotStatusLabel(_ status: SnapshotStatus) -> String {
    switch status {
    case .idle: return "Idle"
    case .queued: return "Queued — the peer is busy receiving another snapshot"
    case .receiving: return "Receiving"
    case .merged: return "Merged"
    case .rejected: return "Rejected"
    case .acknowledged: return "Acknowledged"
    case .sending: return "Sending"
    }
  }
}
