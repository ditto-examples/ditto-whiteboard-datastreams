import Foundation

/// Debug-only transport overrides applied at Ditto construction. Toggling any of these requires an
/// app restart to take effect; the Troubleshooting screen surfaces them for protocol testing.
struct DebugTransportSettings: Sendable {
  var bluetoothLEEnabled: Bool
  var lanEnabled: Bool
  var mdnsEnabled: Bool
  var multicastEnabled: Bool
  var awdlEnabled: Bool

  static let `default` = DebugTransportSettings(
    bluetoothLEEnabled: true,
    lanEnabled: true,
    mdnsEnabled: true,
    multicastEnabled: false,
    awdlEnabled: true
  )

  private enum Key {
    static let ble = "debugTransports.bluetoothLE"
    static let lan = "debugTransports.lan"
    static let mdns = "debugTransports.mdns"
    static let multicast = "debugTransports.multicast"
    static let awdl = "debugTransports.awdl"
  }

  static func load(from defaults: UserDefaults = .standard) -> DebugTransportSettings {
    func read(_ key: String, fallback: Bool) -> Bool {
      defaults.object(forKey: key) as? Bool ?? fallback
    }
    return DebugTransportSettings(
      bluetoothLEEnabled: read(Key.ble, fallback: true),
      lanEnabled: read(Key.lan, fallback: true),
      mdnsEnabled: read(Key.mdns, fallback: true),
      multicastEnabled: read(Key.multicast, fallback: false),
      awdlEnabled: read(Key.awdl, fallback: true)
    )
  }

  func save(to defaults: UserDefaults = .standard) {
    defaults.set(bluetoothLEEnabled, forKey: Key.ble)
    defaults.set(lanEnabled, forKey: Key.lan)
    defaults.set(mdnsEnabled, forKey: Key.mdns)
    defaults.set(multicastEnabled, forKey: Key.multicast)
    defaults.set(awdlEnabled, forKey: Key.awdl)
  }
}
