package com.ditto.whiteboard.app

import android.app.Application
import android.system.Os

class WhiteboardApplication : Application() {
  lateinit var container: AppContainer
    private set

  override fun onCreate() {
    super.onCreate()
    Os.setenv("DITTO_NETWORK_ENABLE_NGN", "true", true)
    Os.setenv("DITTO_REPLICATION_OVER_NGN", "true", true)
    // Match the Data Streams reference app: avoid the significant I/O regression from
    // always-debug disk logging. This must be set before AppContainer creates Ditto.
    Os.setenv("DITTO_DISABLE_ALWAYS_DEBUG_ON_DISK_LOGS", "1", true)
    container = AppContainer(this)
  }
}
