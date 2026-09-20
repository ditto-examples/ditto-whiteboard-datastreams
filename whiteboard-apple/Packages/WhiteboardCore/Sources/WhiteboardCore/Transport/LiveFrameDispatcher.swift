import Foundation

/// Drains lossy live frames without letting one closed recipient permanently kill the shared
/// dispatcher. Cancellation still propagates so transport shutdown remains prompt.
public func dispatchLiveFrames<Frame, Recipient, Frames: AsyncSequence>(
  frames: Frames,
  encode: (Frame) throws -> Data,
  recipients: () -> some Collection<Recipient>,
  maxSendSize: (Recipient) throws -> Int64,
  send: (Recipient, Data) throws -> Void,
  onSent: (Recipient) -> Void,
  onFailure: (Recipient?, any Error) -> Void,
  throttle: () async -> Void
) async throws where Frames.Element == Frame {
  for try await frame in frames {
    do {
      let bytes = try encode(frame)
      for recipient in recipients() {
        do {
          let sizeLimit = try maxSendSize(recipient)
          if Int64(bytes.count) <= sizeLimit {
            try send(recipient, bytes)
            onSent(recipient)
          }
        } catch is CancellationError {
          throw CancellationError()
        } catch {
          onFailure(recipient, error)
        }
      }
    } catch is CancellationError {
      throw CancellationError()
    } catch {
      onFailure(nil, error)
    }
    await throttle()
  }
}
