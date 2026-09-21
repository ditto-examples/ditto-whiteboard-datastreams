import Darwin
import Foundation
import Network
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
  @State private var peerCardHeight: CGFloat = 0
  @State private var networkCardHeight: CGFloat = 0
  @State private var localNetworkInterfaces: [LocalNetworkInterface] = []

  private var peers: [PeerDiagnostics] {
    diagnostics.peers.values.sorted { $0.peerKey < $1.peerKey }
  }

  var body: some View {
    ScrollView {
      VStack(spacing: 0) {
        LazyVGrid(columns: columns, spacing: 16) {
          LocalPeerCard(
            peerKey: diagnostics.localPeerKey,
            displayName: profile?.displayName,
            minimumHeight: peerCardHeight
          )

          ForEach(peers, id: \.peerKey) { peer in
            PeerCard(peer: peer, minimumHeight: peerCardHeight)
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
        .onPreferenceChange(PeerCardHeightPreferenceKey.self) { height in
          guard height > peerCardHeight else { return }
          peerCardHeight = height
        }

        if !localNetworkInterfaces.isEmpty {
          SectionDivider(title: "Local Network")
            .padding(.horizontal, 16)
            .padding(.vertical, 16)

          LazyVGrid(columns: columns, spacing: 16) {
            ForEach(localNetworkInterfaces) { interface in
              LocalNetworkInterfaceCard(interface: interface, minimumHeight: networkCardHeight)
            }
          }
          .padding(.horizontal, 16)
          .padding(.bottom, 16)
          .onPreferenceChange(NetworkCardHeightPreferenceKey.self) { height in
            guard height > networkCardHeight else { return }
            networkCardHeight = height
          }
        }
      }
    }
    .background(Color.primary.opacity(0.025))
    .navigationTitle("Peers")
    #if os(iOS)
      .navigationBarTitleDisplayMode(.inline)
    #endif
    .task {
      localNetworkInterfaces = await LocalNetworkDiagnostics.fetchInterfaces()
    }
  }
}

private struct LocalPeerCard: View {
  let peerKey: String
  let displayName: String?
  let minimumHeight: CGFloat

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
    .frame(maxWidth: .infinity, minHeight: minimumHeight, alignment: .topLeading)
    .background(.quaternary, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
    .background(PeerCardHeightReader())
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
  let minimumHeight: CGFloat

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
          Text(peer.transports.isEmpty ? "No direct transport" : peer.transports.sorted().joined(separator: " · "))
            .font(.subheadline)
            .foregroundStyle(appearance.foreground.opacity(0.8))
        }
        Spacer()
        Circle()
          .fill(peer.liveConnected || peer.stateConnected ? .green : .secondary)
          .frame(width: 10, height: 10)
          .accessibilityLabel(peer.liveConnected || peer.stateConnected ? "Connected" : "Offline")
      }

      Divider().overlay(appearance.foreground.opacity(0.3))
      PeerKeyRow(peerKey: peer.peerKey)
      ActiveConnectionsSection(transports: peer.transports, foreground: appearance.foreground)
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
          .textSelection(.enabled)
      }
    }
    .padding(16)
    .frame(maxWidth: .infinity, minHeight: minimumHeight, alignment: .topLeading)
    .foregroundStyle(appearance.foreground)
    .background(
      LinearGradient(
        colors: appearance.colors,
        startPoint: .topLeading,
        endPoint: .bottomTrailing
      ),
      in: RoundedRectangle(cornerRadius: 18, style: .continuous)
    )
    .background(PeerCardHeightReader())
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
        .textSelection(.enabled)
        .fixedSize(horizontal: false, vertical: true)
    }
  }
}

/// Mirrors Edge Studio's per-peer transport badges. A peer can have more than
/// one direct connection, so the diagnostic card lists each one rather than
/// reducing the header to a single dominant transport.
private struct ActiveConnectionsSection: View {
  let transports: Set<String>
  let foreground: Color

  var body: some View {
    VStack(alignment: .leading, spacing: 8) {
      Label {
        Text(transports.isEmpty ? "No Active Connections" : "Active Connections (\(transports.count))")
      } icon: {
        Image(systemName: transports.isEmpty ? "link.badge.plus" : "link")
      }
      .font(.subheadline.weight(.semibold))
      .foregroundStyle(foreground.opacity(0.85))

      ForEach(transports.sorted(), id: \.self) { transport in
        TransportConnectionBadge(transport: transport, foreground: foreground)
      }
    }
  }
}

private struct TransportConnectionBadge: View {
  let transport: String
  let foreground: Color

  private var presentation: TransportPresentation {
    TransportPresentation(transport: transport)
  }

  var body: some View {
    HStack(spacing: 8) {
      Image(systemName: presentation.systemImage)
        .frame(width: 18)
      Text(presentation.name)
        .fixedSize(horizontal: false, vertical: true)
      Spacer(minLength: 0)
    }
    .font(.subheadline.weight(.medium))
    .foregroundStyle(foreground)
    .padding(8)
    .background(
      RoundedRectangle(cornerRadius: 8, style: .continuous)
        .fill(foreground.opacity(0.12))
        .overlay(
          RoundedRectangle(cornerRadius: 8, style: .continuous)
            .stroke(foreground.opacity(0.2))
        )
    )
    .accessibilityLabel("Active connection: \(presentation.name)")
  }
}

private struct TransportPresentation {
  let name: String
  let systemImage: String

  init(transport: String) {
    let normalized = transport.lowercased()
    if normalized.contains("cloud") {
      name = "Cloud"
      systemImage = "cloud"
    } else if normalized.contains("websocket") || normalized.contains("web socket") {
      name = "WebSocket"
      systemImage = "globe"
    } else if normalized.contains("lan") || normalized.contains("access") {
      name = "Wi-Fi AP"
      systemImage = "antenna.radiowaves.left.and.right"
    } else if normalized.contains("p2p") || normalized.contains("wifi") {
      name = "P2P Wi-Fi"
      systemImage = "wifi"
    } else if normalized.contains("multicast") {
      name = "Multicast"
      systemImage = "dot.radiowaves.left.and.right"
    } else if normalized.contains("bluetooth") {
      name = "Bluetooth"
      systemImage = "bonjour"
    } else {
      name = transport
      systemImage = "link"
    }
  }
}

private struct SectionDivider: View {
  let title: String

  var body: some View {
    HStack(spacing: 12) {
      Rectangle()
        .fill(.secondary.opacity(0.25))
        .frame(height: 1)
      Text(title)
        .font(.subheadline.weight(.semibold))
        .foregroundStyle(.secondary)
        .fixedSize()
      Rectangle()
        .fill(.secondary.opacity(0.25))
        .frame(height: 1)
    }
  }
}

private struct PeerCardHeightPreferenceKey: PreferenceKey {
  static var defaultValue: CGFloat { 0 }

  static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
    value = max(value, nextValue())
  }
}

private struct NetworkCardHeightPreferenceKey: PreferenceKey {
  static var defaultValue: CGFloat { 0 }

  static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
    value = max(value, nextValue())
  }
}

private struct PeerCardHeightReader: View {
  var body: some View {
    GeometryReader { proxy in
      Color.clear.preference(key: PeerCardHeightPreferenceKey.self, value: proxy.size.height)
    }
  }
}

private struct NetworkCardHeightReader: View {
  var body: some View {
    GeometryReader { proxy in
      Color.clear.preference(key: NetworkCardHeightPreferenceKey.self, value: proxy.size.height)
    }
  }
}

/// Local-network cards use the same separate grid as Edge Studio. The data is
/// intentionally limited to properties the app can read without requesting
/// location access; macOS and iOS/iPadOS expose the active interface and
/// addresses, while unavailable values simply do not produce a row.
private struct LocalNetworkInterfaceCard: View {
  let interface: LocalNetworkInterface
  let minimumHeight: CGFloat

  private var colors: [Color] {
    switch interface.kind {
    case .wifi:
      [Color(red: 0.05, green: 0.52, blue: 0.25), Color(red: 0.02, green: 0.32, blue: 0.14)]
    case .ethernet:
      [Color(red: 0.05, green: 0.45, blue: 0.50), Color(red: 0.02, green: 0.28, blue: 0.32)]
    case .other:
      [Color(red: 0.35, green: 0.35, blue: 0.40), Color(red: 0.20, green: 0.20, blue: 0.25)]
    }
  }

  var body: some View {
    VStack(alignment: .leading, spacing: 12) {
      HStack(alignment: .top) {
        Label(interface.kind.displayName, systemImage: interface.kind.systemImage)
          .font(.headline.weight(.bold))
        Text("(\(interface.interfaceName))")
          .font(.caption)
          .foregroundStyle(.white.opacity(0.7))
        Spacer(minLength: 0)
        Label(interface.isActive ? "Connected" : "Not Connected", systemImage: "circle.fill")
          .font(.caption.weight(.medium))
          .foregroundStyle(interface.isActive ? Color.green : Color.secondary)
      }

      Divider().overlay(Color.white.opacity(0.25))

      if interface.isActive {
        if let hardwareAddress = interface.hardwareAddress {
          LocalNetworkValueRow(label: "Hardware", value: hardwareAddress)
        }
        if let ipv4Address = interface.ipv4Address {
          LocalNetworkValueRow(label: "IPv4", value: ipv4Address)
        }
        if let ipv6Address = interface.ipv6Address {
          LocalNetworkValueRow(label: "IPv6", value: ipv6Address)
        }
      } else {
        Text("Not Connected")
          .font(.subheadline)
          .foregroundStyle(.white.opacity(0.65))
      }

      Spacer(minLength: 0)
    }
    .padding(16)
    .frame(maxWidth: .infinity, minHeight: minimumHeight, alignment: .topLeading)
    .foregroundStyle(.white)
    .background(
      LinearGradient(colors: colors, startPoint: .topLeading, endPoint: .bottomTrailing),
      in: RoundedRectangle(cornerRadius: 18, style: .continuous)
    )
    .background(NetworkCardHeightReader())
    .accessibilityElement(children: .contain)
    .accessibilityIdentifier("localNetworkCard.\(interface.interfaceName)")
  }
}

private struct LocalNetworkValueRow: View {
  let label: String
  let value: String

  var body: some View {
    HStack(alignment: .top, spacing: 12) {
      Text(label)
        .font(.caption.weight(.semibold))
        .foregroundStyle(.white.opacity(0.7))
      Spacer(minLength: 8)
      Text(value)
        .font(.caption.monospaced())
        .foregroundStyle(.white)
        .multilineTextAlignment(.trailing)
        .textSelection(.enabled)
        .fixedSize(horizontal: false, vertical: true)
    }
  }
}

private struct LocalNetworkInterface: Identifiable, Sendable {
  enum Kind: Sendable {
    case wifi
    case ethernet
    case other

    var displayName: String {
      switch self {
      case .wifi: "Wi-Fi"
      case .ethernet: "Ethernet"
      case .other: "Network"
      }
    }

    var systemImage: String {
      switch self {
      case .wifi: "wifi"
      case .ethernet: "cable.connector"
      case .other: "network"
      }
    }
  }

  let interfaceName: String
  let kind: Kind
  let isActive: Bool
  let hardwareAddress: String?
  let ipv4Address: String?
  let ipv6Address: String?

  var id: String { interfaceName }
}

private enum LocalNetworkDiagnostics {
  private struct InterfaceDescriptor: Sendable {
    let name: String
    let kind: LocalNetworkInterface.Kind
  }

  private struct InterfaceAddresses {
    var hardwareAddress: String?
    var ipv4Address: String?
    var ipv6Address: String?
    var isUp = false
  }

  static func fetchInterfaces() async -> [LocalNetworkInterface] {
    let descriptors = await activeInterfaceDescriptors()
    let addresses = interfaceAddresses()
    return descriptors.compactMap { descriptor in
      guard let address = addresses[descriptor.name] else { return nil }
      return LocalNetworkInterface(
        interfaceName: descriptor.name,
        kind: descriptor.kind,
        isActive: address.isUp,
        hardwareAddress: address.hardwareAddress,
        ipv4Address: address.ipv4Address,
        ipv6Address: address.ipv6Address
      )
    }
  }

  private static func activeInterfaceDescriptors() async -> [InterfaceDescriptor] {
    await withCheckedContinuation { continuation in
      let monitor = NWPathMonitor()
      monitor.pathUpdateHandler = { path in
        let descriptors = path.availableInterfaces.compactMap { interface -> InterfaceDescriptor? in
          switch interface.type {
          case .wifi:
            InterfaceDescriptor(name: interface.name, kind: .wifi)
          case .wiredEthernet:
            InterfaceDescriptor(name: interface.name, kind: .ethernet)
          default:
            nil
          }
        }
        monitor.cancel()
        continuation.resume(returning: Array(Dictionary(descriptors.map { ($0.name, $0) }, uniquingKeysWith: { first, _ in first }).values))
      }
      monitor.start(queue: DispatchQueue(label: "com.ditto.whiteboard.local-network"))
    }
  }

  private static func interfaceAddresses() -> [String: InterfaceAddresses] {
    var results: [String: InterfaceAddresses] = [:]
    var pointer: UnsafeMutablePointer<ifaddrs>?
    guard getifaddrs(&pointer) == 0, let head = pointer else { return results }
    defer { freeifaddrs(head) }

    var current: UnsafeMutablePointer<ifaddrs>? = head
    while let node = current {
      current = node.pointee.ifa_next
      guard let address = node.pointee.ifa_addr else { continue }
      let name = String(cString: node.pointee.ifa_name)
      let flags = Int32(node.pointee.ifa_flags)
      if (flags & IFF_UP) != 0, (flags & IFF_RUNNING) != 0 {
        results[name, default: InterfaceAddresses()].isUp = true
      }

      switch Int32(address.pointee.sa_family) {
      case AF_LINK:
        let hardwareAddress = address.withMemoryRebound(to: sockaddr_dl.self, capacity: 1) { link -> String? in
          let nameLength = Int(link.pointee.sdl_nlen)
          let addressLength = Int(link.pointee.sdl_alen)
          guard addressLength == 6 else { return nil }
          let offset = 8 + nameLength
          guard offset + addressLength <= Int(link.pointee.sdl_len) else { return nil }
          let raw = UnsafeRawPointer(link)
          return (0 ..< addressLength)
            .map { String(format: "%02x", raw.load(fromByteOffset: offset + $0, as: UInt8.self)) }
            .joined(separator: ":")
        }
        if let hardwareAddress {
          results[name, default: InterfaceAddresses()].hardwareAddress = hardwareAddress
        }

      case AF_INET:
        results[name, default: InterfaceAddresses()].ipv4Address = numericAddress(
          address,
          length: socklen_t(MemoryLayout<sockaddr_in>.size)
        )

      case AF_INET6:
        let ipv6Address = numericAddress(
          address,
          length: socklen_t(MemoryLayout<sockaddr_in6>.size)
        )
        let existing = results[name]?.ipv6Address
        if existing == nil || ipv6Address?.lowercased().hasPrefix("fe80") == true {
          results[name, default: InterfaceAddresses()].ipv6Address = ipv6Address
        }

      default:
        break
      }
    }
    return results
  }

  private static func numericAddress(_ address: UnsafeMutablePointer<sockaddr>, length: socklen_t) -> String? {
    var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
    let result = getnameinfo(address, length, &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST)
    guard result == 0 else { return nil }
    return host.withUnsafeBufferPointer { buffer in
      guard let baseAddress = buffer.baseAddress else { return nil }
      return String(cString: baseAddress)
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
