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

}
