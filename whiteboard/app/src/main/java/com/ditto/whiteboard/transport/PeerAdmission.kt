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
