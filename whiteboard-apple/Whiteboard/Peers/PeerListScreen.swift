import Foundation
import SwiftUI
import WhiteboardCore

#if os(iOS)
import UIKit
#endif

/// A direct-peer diagnostic view. It intentionally uses the same admitted
/// nearby peers as Transport, but makes their connection and stream state
/// scannable without turning the Transport inspector into a long table.
struct PeerListScreen: View {
  let appModel: AppModel
  @Environment(\.dismiss) private var dismiss

  var body: some View {
    #if os(macOS)
    PeerListContent(diagnostics: appModel.diagnostics, profile: appModel.profile)
    #else
    NavigationStack {
      PeerListContent(diagnostics: appModel.diagnostics, profile: appModel.profile)
        .toolbar {
          ToolbarItem(placement: .confirmationAction) {
            Button("Done") { dismiss() }
              .accessibilityIdentifier("closePeerListButton")
          }
        }
    }
    #endif
  }
}

private struct PeerListContent: View {
  let diagnostics: TransportDiagnostics
  let profile: ProfileSettings?

  private let columns = [GridItem(.adaptive(minimum: 260, maximum: 520), spacing: 16)]

  private var peers: [PeerDiagnostics] {
    diagnostics.peers.values.sorted { $0.peerKey < $1.peerKey }
  }

  var body: some View {
    ScrollView {
      LazyVGrid(columns: columns, spacing: 16) {
        LocalPeerCard(peerKey: diagnostics.localPeerKey, displayName: profile?.displayName)

        ForEach(peers, id: \.peerKey) { peer in
          PeerCard(peer: peer)
        }
        if peers.isEmpty {
          ContentUnavailableView(
            "No nearby peers",
            systemImage: "person.2.slash",
            description: Text("This device will appear here when a nearby collaborator connects.")
          )
          .frame(maxWidth: .infinity, minHeight: 240)
        }
      }
      .padding(16)
    }
    .background(Color.primary.opacity(0.025))
    .navigationTitle("Peers")
    #if os(iOS)
      .navigationBarTitleDisplayMode(.inline)
    #endif
  }
}

private struct LocalPeerCard: View {
  let peerKey: String
  let displayName: String?

  var body: some View {
    VStack(alignment: .leading, spacing: 12) {
      HStack(alignment: .top, spacing: 10) {
        Image(systemName: "laptopcomputer.and.iphone")
          .font(.title2)
        VStack(alignment: .leading, spacing: 2) {
          Text("This device")
            .font(.headline)
          Text(displayName ?? localDeviceName)
            .font(.subheadline)
            .foregroundStyle(.secondary)
        }
        Spacer()
        Circle()
          .fill(.green)
          .frame(width: 10, height: 10)
          .accessibilityLabel("Connected")
      }

      Divider()
      PeerKeyRow(peerKey: peerKey)
      PeerDetailRow(label: "Platform", value: localPlatform)
      PeerDetailRow(label: "Whiteboard", value: "SwiftUI")
    }
    .padding(16)
    .frame(maxWidth: .infinity, alignment: .leading)
    .background(.quaternary, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
    .accessibilityElement(children: .contain)
    .accessibilityIdentifier("localPeerCard")
  }

  private var localDeviceName: String {
    #if os(iOS)
    UIDevice.current.name
    #elseif os(macOS)
    Host.current().localizedName ?? ProcessInfo.processInfo.hostName
    #else
    "This device"
    #endif
  }

  private var localPlatform: String {
    #if os(iOS)
    "\(UIDevice.current.systemName) \(UIDevice.current.systemVersion)"
    #elseif os(macOS)
    "macOS \(ProcessInfo.processInfo.operatingSystemVersionString)"
    #else
    "Apple platform"
    #endif
  }
}

private struct PeerCard: View {
  let peer: PeerDiagnostics

  private var appearance: PeerCardAppearance {
    PeerCardAppearance(transports: peer.transports)
  }

  private var title: String {
    peer.displayName ?? "Nearby peer \(peer.peerKey.suffix(8))"
  }

  var body: some View {
    VStack(alignment: .leading, spacing: 12) {
      HStack(alignment: .top, spacing: 10) {
        Image(systemName: appearance.systemImage)
          .font(.title2)
        VStack(alignment: .leading, spacing: 2) {
          Text(title)
            .font(.headline)
            .lineLimit(2)
          Text(peer.transports.isEmpty ? "No direct transport" : peer.transports.sorted().joined(separator: " · "))
            .font(.subheadline)
            .lineLimit(2)
        }
        Spacer()
        Circle()
          .fill(peer.liveConnected || peer.stateConnected ? .green : .secondary)
          .frame(width: 10, height: 10)
          .accessibilityLabel(peer.liveConnected || peer.stateConnected ? "Connected" : "Offline")
      }

      Divider().overlay(appearance.foreground.opacity(0.3))
      PeerKeyRow(peerKey: peer.peerKey)
      PeerDetailRow(label: "wb_live", value: peer.liveConnected ? "Connected" : "Offline")
      PeerDetailRow(label: "wb_state", value: peer.stateConnected ? "Connected" : "Offline")
      PeerDetailRow(
        label: "Snapshot",
        value: "\(snapshotStatusLabel(peer.snapshotStatus)) · \(Int((Double(peer.snapshotProgress) * 100).rounded()))%"
      )
      PeerDetailRow(
        label: "Traffic",
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
          .lineLimit(3)
      }
    }
    .padding(16)
    .frame(maxWidth: .infinity, alignment: .leading)
    .foregroundStyle(appearance.foreground)
    .background(
      LinearGradient(
        colors: appearance.colors,
        startPoint: .topLeading,
        endPoint: .bottomTrailing
      ),
      in: RoundedRectangle(cornerRadius: 18, style: .continuous)
    )
    .accessibilityElement(children: .contain)
    .accessibilityIdentifier("peerCard.\(peer.peerKey)")
  }
}

private struct PeerKeyRow: View {
  let peerKey: String

  var body: some View {
    PeerDetailRow(label: "Peer key", value: peerKey, monospaced: true)
  }
}

private struct PeerDetailRow: View {
  let label: String
  let value: String
  var monospaced = false

  var body: some View {
    VStack(alignment: .leading, spacing: 2) {
      Text(label)
        .font(.caption.weight(.semibold))
        .opacity(0.8)
      Text(value)
        .font(monospaced ? .caption.monospaced() : .subheadline)
        .lineLimit(monospaced ? 2 : 3)
        .textSelection(.enabled)
    }
  }
}

private struct PeerCardAppearance {
  let colors: [Color]
  let foreground: Color
  let systemImage: String

  init(transports: Set<String>) {
    let names = transports.map { $0.lowercased() }
    if names.contains(where: { $0.contains("cloud") }) {
      colors = [Color(red: 0.35, green: 0.20, blue: 0.66), Color(red: 0.20, green: 0.12, blue: 0.46)]
      foreground = .white
      systemImage = "cloud"
    } else if names.contains(where: { $0.contains("websocket") || $0.contains("web socket") }) {
      colors = [Color(red: 0.85, green: 0.48, blue: 0), Color(red: 0.60, green: 0.24, blue: 0)]
      foreground = .white
      systemImage = "globe"
    } else if names.contains(where: { $0.contains("lan") || $0.contains("access") }) {
      colors = [Color(red: 0.05, green: 0.52, blue: 0.25), Color(red: 0.02, green: 0.32, blue: 0.14)]
      foreground = .white
      systemImage = "network"
    } else if names.contains(where: { $0.contains("p2p") || $0.contains("wifi") }) {
      colors = [Color(red: 0.78, green: 0.10, blue: 0.22), Color(red: 0.50, green: 0.04, blue: 0.12)]
      foreground = .white
      systemImage = "wifi"
    } else if names.contains(where: { $0.contains("multicast") }) {
      colors = [Color(red: 1, green: 0.84, blue: 0.04), Color(red: 0.72, green: 0.48, blue: 0)]
      foreground = .black
      systemImage = "dot.radiowaves.left.and.right"
    } else if names.contains(where: { $0.contains("bluetooth") }) {
      colors = [Color(red: 0, green: 0.40, blue: 0.85), Color(red: 0, green: 0.20, blue: 0.60)]
      foreground = .white
      systemImage = "bonjour"
    } else {
      colors = [Color.gray.opacity(0.5), Color.gray.opacity(0.7)]
      foreground = .white
      systemImage = "person.2"
    }
  }
}

private func snapshotStatusLabel(_ status: SnapshotStatus) -> String {
  switch status {
  case .idle: return "Idle"
  case .queued: return "Queued"
  case .receiving: return "Receiving"
  case .merged: return "Merged"
  case .rejected: return "Rejected"
  case .acknowledged: return "Acknowledged"
  case .sending: return "Sending"
  }
}
