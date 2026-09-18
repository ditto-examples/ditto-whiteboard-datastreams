package com.ditto.whiteboard.transport

import com.ditto.whiteboard.domain.BoardOperation
import com.ditto.whiteboard.domain.LivePreview
import com.ditto.whiteboard.domain.UserProfile
import java.util.UUID
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

class InMemoryWhiteboardTransport(
  override val localPeerKey: String = "local-${UUID.randomUUID().toString().take(8)}",
  private val reason: String,
) : WhiteboardTransport {
  private val mutableEvents = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 64)
  override val events = mutableEvents.asSharedFlow()
  private val mutableDiagnostics = MutableStateFlow(
    TransportDiagnostics(
      localPeerKey = localPeerKey,
      mode = TransportMode.LocalPreview,
      connectivityMessage = reason,
    ),
  )
  override val diagnostics = mutableDiagnostics.asStateFlow()
  private var foreground = true
  private val knownLog = BoundedOperationLog()

  override suspend fun start(profile: UserProfile) {
    mutableDiagnostics.value = mutableDiagnostics.value.copy(running = foreground)
  }

  override suspend fun sendReliable(operation: BoardOperation): Boolean =
    knownLog.accept(operation) !is KnownOperationResult.Rejected

  override fun sendLive(preview: LivePreview) = Unit

  override fun setForeground(isForeground: Boolean) {
    foreground = isForeground
    mutableDiagnostics.value = mutableDiagnostics.value.copy(running = isForeground)
  }

  override fun close() {
    mutableDiagnostics.value = mutableDiagnostics.value.copy(running = false)
  }
}
