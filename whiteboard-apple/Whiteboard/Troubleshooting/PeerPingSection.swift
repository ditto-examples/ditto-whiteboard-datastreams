import SwiftUI
import WhiteboardCore

struct PeerPingSection: View {
  let diagnostics: TransportDiagnostics
  let appModel: AppModel
  @State private var selectedPeer = ""
  @State private var runID: UUID?
  @State private var samples: [Double] = []
  @State private var outcome: String?
  @State private var testedPeer: String?

  private var available: Bool { diagnostics.mode == .nearbyMesh && diagnostics.running }
  private var peers: [PeerDiagnostics] { diagnostics.peers.values.sorted { $0.peerKey < $1.peerKey } }

  var body: some View {
    Section {
      Text("Send five small requests and verify their echoes. Round-trip time includes app and network overhead.")
        .font(.footnote)
      if available {
        Picker("Peer", selection: $selectedPeer) {
          Text("Select a peer").tag("")
          ForEach(peers, id: \.peerKey) { peer in
            Text("\(peer.displayName ?? "Peer") · \(peer.peerKey.suffix(8))").tag(peer.peerKey)
          }
        }
        .disabled(runID != nil)
        if peers.isEmpty {
          Text("No nearby peers. Open another Whiteboard app with the same database and allow nearby access.")
            .font(.footnote)
        }
      } else {
        Text("Peer ping needs nearby sync. Configure credentials, allow nearby access, and keep both apps open.")
          .font(.footnote)
      }
      if runID != nil {
        ProgressView("Waiting for echo…")
        Button("Cancel ping") {
          runID = nil
          outcome = "Ping cancelled."
        }
      } else {
        Button("Run ping", systemImage: "waveform.path") {
          samples = []
          outcome = nil
          testedPeer = selectedPeer
          runID = UUID()
        }
        .disabled(!available || !diagnostics.peers.keys.contains(selectedPeer))
        .accessibilityIdentifier("runPeerPingButton")
      }
      if let testedPeer {
        Text("Results for peer \(testedPeer.suffix(8))")
          .font(.caption)
        ForEach(Array(samples.enumerated()), id: \.offset) { index, rtt in
          LabeledContent("Echo \(index + 1)", value: "\(rtt.formatted(.number.precision(.fractionLength(2)))) ms")
        }
        if !samples.isEmpty {
          Text("\(samples.count) of \(PeerPing.count) echoes verified")
            .font(.footnote)
        }
      }
      if let outcome { Text(outcome).font(.footnote).textSelection(.enabled) }
    } header: {
      Text("Peer ping")
    } footer: {
      Text("Tests a reliable Data Stream, not a specific radio or board synchronization. Older apps may not support ping.")
    }
    .task(id: runID) {
      guard let id = runID else { return }
      let peer = selectedPeer
      do {
        try await appModel.ping(peerKey: peer) { rtt in
          await MainActor.run {
            if runID == id { samples.append(rtt) }
          }
        }
        if runID == id { outcome = "Ping completed." }
      } catch {
        guard !Task.isCancelled, runID == id else { return }
        outcome = (error as? PeerPingError)?.errorDescription ?? "Ping failed. Check nearby connectivity and try again."
      }
      if runID == id { runID = nil }
    }
    .onDisappear { runID = nil }
  }
}
