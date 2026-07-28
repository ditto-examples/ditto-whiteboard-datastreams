package com.ditto.whiteboard.transport

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Owns the transport's [TransportDiagnostics] snapshot and guarantees every mutation is an atomic
 * compare-and-set via [MutableStateFlow.update]. The transport touches diagnostics from many
 * coroutines at once (reliable/live/rate/presence workers on [kotlinx.coroutines.Dispatchers.Default]
 * plus connect/outbox/bind callbacks on the IO pool); a plain `value = value.copy(...)`
 * read-modify-write would let overlapping writers clobber each other and silently drop peer entries,
 * rates, snapshot progress or error text. Funnelling every write through here keeps the same
 * lost-update discipline `BoardSession` already uses for its board/preview state.
 *
 * Extracted so the diagnostics bookkeeping can be unit-tested in pure JVM without standing up a real
 * Ditto endpoint.
 */
internal class DiagnosticsState(initial: TransportDiagnostics) {
  private val state = MutableStateFlow(initial)
  val flow: StateFlow<TransportDiagnostics> = state.asStateFlow()
  val value: TransportDiagnostics get() = state.value

  /** Atomically mutate the whole diagnostics snapshot. */
  fun update(transform: (TransportDiagnostics) -> TransportDiagnostics) = state.update(transform)

  /** Atomically create-or-update a single peer entry. */
  fun updatePeer(peer: String, update: (PeerDiagnostics) -> PeerDiagnostics) {
    state.update { current ->
      val peerValue = update(current.peers[peer] ?: PeerDiagnostics(peerKey = peer))
      current.copy(peers = current.peers + (peer to peerValue))
    }
  }

  /**
   * Drop every peer entry whose key is not in [visiblePeers]. Called when presence changes so a peer
   * that left the mesh cannot linger in the connected-people count or presence graph.
   */
  fun retainPeers(visiblePeers: Set<String>) {
    state.update { current ->
      if (current.peers.keys.all { it in visiblePeers }) current
      else current.copy(peers = current.peers.filterKeys { it in visiblePeers })
    }
  }
}
