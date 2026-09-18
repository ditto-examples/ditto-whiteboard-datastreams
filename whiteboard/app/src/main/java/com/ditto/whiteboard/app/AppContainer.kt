package com.ditto.whiteboard.app

import android.content.Context
import android.util.Log
import com.ditto.whiteboard.BuildConfig
import com.ditto.whiteboard.R
import com.ditto.whiteboard.data.BoardSession
import com.ditto.whiteboard.data.BoardSessionMessages
import com.ditto.whiteboard.data.ProfileRepository
import com.ditto.whiteboard.transport.DittoWhiteboardTransport
import com.ditto.whiteboard.transport.InMemoryWhiteboardTransport
import com.ditto.whiteboard.transport.WhiteboardTransport
import com.ditto.whiteboard.util.runCatchingException
import com.ditto.kotlin.transports.DittoSyncPermissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.SupervisorJob

/**
 * Manual dependency container — deliberately no Hilt, so a reader can follow construction top to
 * bottom. It is created once in [WhiteboardApplication] and lives for the whole process; [close]
 * exists so tests (or a future explicit teardown) can release the Ditto instance and cancel the
 * scope. Android does not guarantee an `Application` teardown callback, so it is not wired to one.
 */
class AppContainer(context: Context) : AutoCloseable {
  private val appContext = context.applicationContext
  private val job = SupervisorJob()
  private val errorHandler = CoroutineExceptionHandler { _, error ->
    // Avoid logging peer payloads or credentials; the exception type is enough for a last-resort
    // process-scope diagnostic. Expected transport and UI failures are handled at their owners.
    Log.e("DittoWhiteboard", "Unhandled application task (${error::class.java.simpleName})")
  }
  val scope = CoroutineScope(job + Dispatchers.Default + errorHandler)
  val profileRepository = ProfileRepository(appContext, scope)

  private val credentialsPresent = BuildConfig.DITTO_DATABASE_ID.isNotBlank() &&
    BuildConfig.DITTO_OFFLINE_LICENSE_TOKEN.isNotBlank()

  @Volatile private var permissionDecision: Boolean? = null
  @Volatile private var foreground = true
  private val transportDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    when {
      !credentialsPresent -> InMemoryWhiteboardTransport(
        reason = appContext.getString(R.string.nearby_credentials_missing),
      )
      permissionDecision == false -> InMemoryWhiteboardTransport(
        reason = appContext.getString(R.string.nearby_off_for_session),
      )
      else -> runCatchingException {
        DittoWhiteboardTransport(
          context = appContext,
          databaseId = BuildConfig.DITTO_DATABASE_ID,
          offlineLicenseToken = BuildConfig.DITTO_OFFLINE_LICENSE_TOKEN,
          parentScope = scope,
        ).also { transport ->
          permissionDecision?.let(transport::resolvePermissions)
        }
      }.getOrElse { error ->
        Log.e(
          "DittoWhiteboard",
          "Ditto initialization failed (${error::class.java.simpleName})",
        )
        InMemoryWhiteboardTransport(
          reason = appContext.getString(R.string.nearby_start_failed),
        )
      }
    }
  }
  private val boardSessionDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    val transport = transportDelegate.value.also { it.setForeground(foreground) }
    BoardSession(
      transport = transport,
      scope = scope,
      messages = BoardSessionMessages(
        syncFinishing = appContext.getString(R.string.board_sync_finishing),
        sessionStarting = appContext.getString(R.string.board_session_starting),
        textCannotBeBlank = appContext.getString(R.string.board_text_cannot_be_blank),
        operationClockExhausted = appContext.getString(R.string.board_operation_clock_exhausted),
        logicalTimeLimit = appContext.getString(R.string.board_logical_time_limit),
        editOutsideSafetyLimits = appContext.getString(R.string.board_edit_outside_safety_limits),
        terminalResourceLimit = appContext.getString(R.string.board_terminal_resource_limit),
        peerLogicalTimeLimit = appContext.getString(R.string.board_peer_logical_time_limit),
        visibleObjectLimit = appContext.getString(
          R.string.board_visible_object_limit,
          com.ditto.whiteboard.domain.MAX_RENDERED_BOARD_OBJECTS,
        ),
        clockReservationFailed = appContext.getString(R.string.board_clock_reservation_failed),
      ),
      reserveOperationClock = profileRepository::reserveOperationClock,
      reserveLamportAfter = profileRepository::reserveLamportAfter,
    )
  }
  val boardSession: BoardSession get() = boardSessionDelegate.value
  val requiredPermissions: List<String> by lazy {
    if (credentialsPresent) DittoSyncPermissions(appContext).requiredPermissions() else emptyList()
  }

  fun resolvePermissions(allGranted: Boolean) {
    permissionDecision = allGranted
    if (boardSessionDelegate.isInitialized()) boardSession.resolvePermissions(allGranted)
  }

  fun setForeground(isForeground: Boolean) {
    foreground = isForeground
    if (boardSessionDelegate.isInitialized()) boardSession.setForeground(isForeground)
  }

  override fun close() {
    if (boardSessionDelegate.isInitialized()) boardSession.close()
    job.cancel()
  }
}
