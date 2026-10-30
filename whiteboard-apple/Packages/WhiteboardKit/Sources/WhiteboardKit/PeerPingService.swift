@_spi(PreviewDataStreams) import DittoSwift
import Foundation
import WhiteboardCore

/// Owns an independent diagnostic topic. Both peers may initiate; board stream ordering does
/// not apply. All queues, accepted sessions, message sizes and waits are bounded.
final class PeerPingService: @unchecked Sendable {
  private let ditto: Ditto
  private let lock = NSLock()
  private var acceptor: DittoAcceptor?
  private var sessions: [UUID: PingConnection] = [:]
  private var generation = 0
  private var running = false
  private var clientRunning = false

  init(ditto: Ditto) { self.ditto = ditto }

  func start() throws {
    // Called serially by the transport lifecycle coordinator.
    let epoch = lock.withLock { generation }
    guard !lock.withLock({ running }) else { return }
    let binding = try ditto.dataStreams.bindTopic(PeerPing.topic, reliability: .reliable) { [weak self] candidate in
      guard let self, let (id, connection) = try? reserve() else { return }
      do {
        let stream = try candidate.open { inbound in
          guard let data = try? inbound.payload else { connection.close(); return }
          connection.receive(data)
        }
        guard connection.attach(stream) else { release(id, connection); return }
        Task { [self] in
          let monitor = Task {
            _ = try? await stream.waitUntilClosed()
            connection.close()
          }
          defer { monitor.cancel(); release(id, connection) }
          do {
            // Idle clients cannot retain a responder forever.
            for _ in 0..<100 {
              guard let payload = try await withTimeout(milliseconds: 15_000, {
                var iterator = connection.messages.makeAsyncIterator()
                return await iterator.next()
              }) ?? nil else { break }
              guard payload.count <= (try stream.maxSendSize) else { break }
              try stream.sendAndForget(payload)
            }
          } catch { /* Closing or pausing the transport ends this diagnostic session. */ }
        }
      } catch { release(id, connection) }
    }
    let installed = lock.withLock {
      guard generation == epoch else { return false }
      acceptor = binding
      running = true
      return true
    }
    if !installed { binding.close(); throw PeerPingError.unavailable }
  }

  func stop() {
    let resources = lock.withLock {
      running = false
      generation += 1
      let resources = (acceptor, Array(sessions.values))
      acceptor = nil
      sessions.removeAll()
      return resources
    }
    resources.0?.close()
    resources.1.forEach { $0.close() }
  }

  func ping(peerKey: String, onSample: @escaping @Sendable (Double) async -> Void) async throws {
    try lock.withLock {
      guard running else { throw PeerPingError.unavailable }
      guard !clientRunning else { throw PeerPingError.busy }
      clientRunning = true
    }
    defer { lock.withLock { clientRunning = false } }
    let (id, connection) = try reserve()
    defer { release(id, connection) }
    let stream: DittoStream
    do {
      stream = try await ditto.dataStreams.connect(
        peerKey: peerKey, topic: PeerPing.topic, requestCompression: false,
        reliability: .reliable, timeoutMs: 5_000
      ) { candidate in
        try candidate.open { inbound in
          guard let data = try? inbound.payload else { connection.close(); return }
          connection.receive(data)
        }
      }
    } catch {
      try Task.checkCancellation()
      throw PeerPingError.connectionFailed
    }
    guard connection.attach(stream) else { throw PeerPingError.closed }
    let monitor = Task {
      _ = try? await stream.waitUntilClosed()
      connection.messagesContinuation.finish()
    }
    defer { monitor.cancel() }
    try await withTaskCancellationHandler {
      guard PeerPing.payloadSize <= (try stream.maxSendSize) else { throw PeerPingError.tooLarge }
      for sequence in 0..<PeerPing.count {
        try Task.checkCancellation()
        let rtt = try await PeerPing.measure(sequence: UInt64(sequence)) { request in
          try stream.sendAndForget(request)
          guard let response = try await withTimeout(milliseconds: 5_000, {
            var iterator = connection.messages.makeAsyncIterator()
            guard let data = await iterator.next() else {
              try Task.checkCancellation()
              throw PeerPingError.closed
            }
            return data
          }) else { throw PeerPingError.timedOut }
          return response
        }
        await onSample(rtt)
      }
    } onCancel: { connection.close() }
  }

  private func reserve() throws -> (UUID, PingConnection) {
    try lock.withLock {
      guard running else { throw PeerPingError.unavailable }
      guard sessions.count < 11 else { throw PeerPingError.busy }
      let id = UUID()
      let connection = PingConnection()
      sessions[id] = connection
      return (id, connection)
    }
  }

  private func release(_ id: UUID, _ connection: PingConnection) {
    connection.close()
    _ = lock.withLock { sessions.removeValue(forKey: id) }
  }
}

private final class PingConnection: @unchecked Sendable {
  let messages: AsyncStream<Data>
  let messagesContinuation: AsyncStream<Data>.Continuation
  private let lock = NSLock()
  private var stream: DittoStream?
  private var closed = false

  init() {
    (messages, messagesContinuation) = AsyncStream.makeStream(bufferingPolicy: .bufferingOldest(1))
  }

  func receive(_ data: Data) {
    guard (8...PeerPing.maximumPayloadSize).contains(data.count) else { close(); return }
    if case .dropped = messagesContinuation.yield(data) { close() }
  }

  func attach(_ value: DittoStream) -> Bool {
    let accepted = lock.withLock {
      guard !closed else { return false }
      stream = value
      return true
    }
    if !accepted { value.close() }
    return accepted
  }

  func close() {
    let value = lock.withLock {
      closed = true
      let value = stream
      stream = nil
      return value
    }
    messagesContinuation.finish()
    value?.close()
  }
}
