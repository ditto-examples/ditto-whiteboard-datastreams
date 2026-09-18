package com.ditto.whiteboard.transport

/**
 * Serializes peer-scoped resource creation with presence removal.
 *
 * Without this gate, a sender can pass a visibility check, presence can tear down the peer, and
 * the sender can then recreate an outbox that no future presence update knows to remove.
 */
internal class PeerVisibilityGate {
  private val lock = Any()
  private var visiblePeers: Set<String> = emptySet()
  private var enabled: Boolean = false

  fun update(peers: Set<String>, onUpdated: (removedPeers: Set<String>) -> Unit) {
    synchronized(lock) {
      val removed = visiblePeers - peers
      visiblePeers = peers
      onUpdated(removed)
    }
  }

  fun <T> ifVisible(peer: String, block: () -> T): T? = synchronized(lock) {
    if (enabled && peer in visiblePeers) block() else null
  }

  fun enable() {
    synchronized(lock) { enabled = true }
  }

  fun disable(onDisabled: () -> Unit) {
    synchronized(lock) {
      enabled = false
      onDisabled()
    }
  }
}
