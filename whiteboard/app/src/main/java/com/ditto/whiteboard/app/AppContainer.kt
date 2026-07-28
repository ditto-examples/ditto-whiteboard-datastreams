package com.ditto.whiteboard.app

import android.content.Context
import com.ditto.whiteboard.BuildConfig
import com.ditto.whiteboard.data.BoardSession
import com.ditto.whiteboard.data.ProfileRepository
import com.ditto.whiteboard.transport.DittoWhiteboardTransport
import com.ditto.whiteboard.transport.InMemoryWhiteboardTransport
import com.ditto.whiteboard.transport.WhiteboardTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Manual dependency container — deliberately no Hilt, so a reader can follow construction top to
 * bottom. It is created once in [WhiteboardApplication] and lives for the whole process; [close]
 * exists so tests (or a future explicit teardown) can release the Ditto instance and cancel the
 * scope. Android does not guarantee an `Application` teardown callback, so it is not wired to one.
 */
class AppContainer(context: Context) : AutoCloseable {
  private val job = SupervisorJob()
  val scope = CoroutineScope(job + Dispatchers.Default)
  val profileRepository = ProfileRepository(context, scope)

  private val credentialsPresent = BuildConfig.DITTO_DATABASE_ID.isNotBlank() &&
    BuildConfig.DITTO_OFFLINE_LICENSE_TOKEN.isNotBlank()

  private val transport: WhiteboardTransport = if (credentialsPresent) {
    runCatching {
      DittoWhiteboardTransport(
        context = context,
        databaseId = BuildConfig.DITTO_DATABASE_ID,
        offlineLicenseToken = BuildConfig.DITTO_OFFLINE_LICENSE_TOKEN,
        parentScope = scope,
      )
    }.getOrElse { error ->
      InMemoryWhiteboardTransport(reason = "Ditto could not initialize: ${error.message ?: "unknown error"}")
    }
  } else {
    InMemoryWhiteboardTransport(
      reason = "Nearby collaboration is off until Whiteboard Ditto credentials are added.",
    )
  }
  val boardSession = BoardSession(transport, scope)
  val requiredPermissions: List<String> get() = boardSession.requiredPermissions

  fun resolvePermissions(allGranted: Boolean) = boardSession.resolvePermissions(allGranted)

  override fun close() {
    boardSession.close()
    job.cancel()
  }
}
