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
    container = AppContainer(this)
  }
}
