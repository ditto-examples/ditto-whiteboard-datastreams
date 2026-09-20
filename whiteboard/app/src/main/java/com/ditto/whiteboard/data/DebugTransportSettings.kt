package com.ditto.whiteboard.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Debug-only transport overrides applied at Ditto construction. Toggling any of these requires an
 * app restart to take effect; the Troubleshooting screen surfaces them for protocol testing.
 */
class DebugTransportSettings(context: Context) {
  private val prefs: SharedPreferences =
    context.applicationContext.getSharedPreferences("whiteboard_debug_transports", Context.MODE_PRIVATE)

  var bluetoothLeEnabled: Boolean
    get() = prefs.getBoolean(KEY_BLE, true)
    set(value) {
      prefs.edit { putBoolean(KEY_BLE, value) }
    }

  var wifiAwareEnabled: Boolean
    get() = prefs.getBoolean(KEY_WIFI_AWARE, true)
    set(value) {
      prefs.edit { putBoolean(KEY_WIFI_AWARE, value) }
    }

  var lanEnabled: Boolean
    get() = prefs.getBoolean(KEY_LAN, true)
    set(value) {
      prefs.edit { putBoolean(KEY_LAN, value) }
    }

  var mdnsEnabled: Boolean
    get() = prefs.getBoolean(KEY_MDNS, true)
    set(value) {
      prefs.edit { putBoolean(KEY_MDNS, value) }
    }

  var multicastEnabled: Boolean
    get() = prefs.getBoolean(KEY_MULTICAST, false)
    set(value) {
      prefs.edit { putBoolean(KEY_MULTICAST, value) }
    }

  companion object {
    private const val KEY_BLE = "bluetooth_le_enabled"
    private const val KEY_WIFI_AWARE = "wifi_aware_enabled"
    private const val KEY_LAN = "lan_enabled"
    private const val KEY_MDNS = "mdns_enabled"
    private const val KEY_MULTICAST = "multicast_enabled"
  }
}
