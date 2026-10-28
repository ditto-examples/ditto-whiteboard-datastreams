package com.ditto.whiteboard.transport

internal const val MAX_CONNECTED_PEERS = 10

internal fun admittedPeerKeys(
  localPeerKey: String,
  discoveredPeerKeys: Collection<String>,
): Set<String> =
  (discoveredPeerKeys + localPeerKey)
    .sorted()
    .take(MAX_CONNECTED_PEERS)
    .toSet()
    .takeIf { localPeerKey in it }
    .orEmpty()

internal fun admittedPresenceConnections(
  localPeerKey: String,
  presentPeerKeys: Set<String>,
  connections: Iterable<PresenceConnection>,
): Set<PresenceConnection> {
  val admittedGraphKeys = presentPeerKeys + localPeerKey
  return connections
    .filter { it.peer1 in admittedGraphKeys && it.peer2 in admittedGraphKeys }
    .toSet()
}

/**
 * Returns only the Presence Graph transport edges directly connecting [localPeerKey] and
 * [remotePeerKey]. A remote peer's `connections` collection describes its entire view of the
 * mesh, so using it unfiltered can incorrectly attribute a connection between two other peers
 * (for example, an Apple-to-Apple P2P Wi-Fi edge) to this device.
 */
internal fun directPresenceTransports(
  localPeerKey: String,
  remotePeerKey: String,
  connections: Iterable<PresenceConnection>,
): Set<String> =
  connections
    .filter { connection ->
      (connection.peer1 == localPeerKey && connection.peer2 == remotePeerKey) ||
        (connection.peer1 == remotePeerKey && connection.peer2 == localPeerKey)
    }
    .mapTo(linkedSetOf()) { it.transport }
