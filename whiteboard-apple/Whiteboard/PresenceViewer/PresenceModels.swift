import Foundation
import SwiftUI
import WhiteboardCore

/// Local model for the presence viewer: the pieces of Edge Studio's `SyncStatus.swift`
/// the port actually uses (peer OS naming, per-transport colors, the cloud color), kept
/// free of DittoSwift so the viewer and its tests never touch the SPI-gated module.

enum PeerOS: Equatable {
  case iOS
  case android
  case macOS
  case linux
  case windows
  case unknown(name: String?)

  /// Maps the SDK's `DittoPeerOS` description onto the app's OS naming. There is no
  /// exhaustive public case list to switch over, so the string match is what there is
  /// to work with (same approach as Edge Studio's `PeerOS(dittoPeerOS:)`).
  init?(osName: String?) {
    guard let osName else { return nil }
    if osName.contains("iOS") || osName.contains("ios") {
      self = .iOS
    } else if osName.contains("Android") || osName.contains("android") {
      self = .android
    } else if osName.contains("macOS") || osName.contains("macos") {
      self = .macOS
    } else if osName.contains("Linux") || osName.contains("linux") {
      self = .linux
    } else if osName.contains("Windows") || osName.contains("windows") {
      self = .windows
    } else {
      self = .unknown(name: osName)
    }
  }

  var displayName: String {
    switch self {
    case .iOS: return "iOS"
    case .android: return "Android"
    case .macOS: return "macOS"
    case .linux: return "Linux"
    case .windows: return "Windows"
    case .unknown(let name): return name ?? "Unknown OS"
    }
  }
}

extension PresenceConnectionType {
  /// Legend/card colors — match `ConnectionLine`'s stroke colors exactly so the legend
  /// never drifts from the graph.
  var cardColor: Color {
    switch self {
    case .bluetooth: return Color(red: 0.0, green: 0.40, blue: 0.85)
    case .accessPoint: return Color(red: 0.05, green: 0.52, blue: 0.25)
    case .p2pWiFi: return Color(red: 0.78, green: 0.10, blue: 0.22)
    case .webSocket: return Color(red: 0.85, green: 0.48, blue: 0.00)
    // Multicast gold matches the VS Code extension card gradient (#FFD60A).
    case .multicast: return Color(red: 1.0, green: 0.84, blue: 0.04)
    case .unknown: return Color(red: 0.35, green: 0.35, blue: 0.40)
    }
  }
}

enum PresenceViewerColors {
  static let cloudCardColor = Color(red: 0.45, green: 0.15, blue: 0.72)
}

func presenceLog(_ message: String) {
  #if DEBUG
    FileHandle.standardError.write("WhiteboardPresence: \(message)\n".data(using: .utf8) ?? Data())
  #endif
}
