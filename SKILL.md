---
name: ditto-data-streams
description: Write, review, and troubleshoot Kotlin or Android code using Ditto Data Streams in the Ditto 5.1.0 SDK line. Use for NGN and transport setup, topic binding, peer connection, stream open/send/receive flows, PreviewDataStreams opt-in, candidate ownership with take(), DittoResource cleanup, reliability, backpressure, cancellation, peer discovery, and reconnect logic. Also use when correcting code written against older or mismatched Ditto Data Streams previews.
---

# Ditto Data Streams for Kotlin and Android

> Advanced integration guide. If you have not used Ditto Data Streams before,
> begin with [`docs/GETTING_STARTED.md`](docs/GETTING_STARTED.md), then return
> here for API ownership, backpressure, cancellation, and teardown patterns.

## Use the 5.1.0 API surface

Target the Kotlin API verified in the Ditto 5.1.0 source line. Treat the API as preview and opt in with `@OptIn(PreviewDataStreams::class)`.

Use [DittoDataStreams.md](DittoDataStreams.md) as the companion API reference for types, signatures, parameters, statuses, and overloads.

If the project uses another preview or release artifact, inspect that artifact's public declarations before coding. Never combine APIs from different previews.

## Follow this workflow

1. Inspect the project's exact Ditto dependency and existing Ditto lifecycle.
2. Set NGN startup parameters before constructing Ditto.
3. Modify the existing transport configuration and start sync.
4. Choose a valid topic and matching reliability on both peers.
5. Bind and retain an acceptor on the receiving peer.
6. Discover or obtain the remote peer key, then connect from a coroutine.
7. Open candidates within their callback lifetime or transfer ownership with `take()`.
8. Retain the owned payload bytes and return quickly from receive callbacks.
9. Retain and close streams explicitly; enforce `maxSendSize()`.
10. Preserve coroutine cancellation and use bounded reconnect backoff.
11. Compile against the actual dependency and test with at least two peers.

## Configure NGN before constructing Ditto

Set `DITTO_NETWORK_ENABLE_NGN=true` before `DittoFactory.create(...)`. Data Streams will not work correctly when NGN is disabled.

On Android, set startup-only parameters from `Application.onCreate()`:

```kotlin
import android.system.Os

Os.setenv("DITTO_NETWORK_ENABLE_NGN", "true", true)
```

Choose `DITTO_REPLICATION_OVER_NGN` deliberately:

- Use `true` when document and attachment replication must use NGN connections, including UDP-only configurations.
- Leave it `false` (the default) when Data Streams should use NGN while document and attachment replication remain on legacy channels.
- Use the same value on every peer that must replicate. Mixed NGN/legacy replication settings cannot replicate with each other.
- Set it before constructing Ditto; changing it at runtime has no effect.

## Configure transports and start sync

Use `Ditto.updateTransportConfig` to modify a copy of the current configuration. Do not replace it with a new `DittoTransportConfig()` unless intentionally disabling every omitted transport; newly constructed configs have all transports disabled.

For a legacy TCP mesh:

```kotlin
ditto.updateTransportConfig { config ->
    config.listen.tcp {
        enabled = true
        port = 0
    }
}
```

Port `0` selects an ephemeral port. Use a fixed port only when firewall policy requires it.

For an NGN UDP/Wi-Fi Aware deployment such as Android Audio:

```kotlin
ditto.updateTransportConfig { config ->
    config.peerToPeer.wifiAware.enabled = true
    config.listen.tcp.enabled = false
}
```

Select transports for the deployment; Data Streams does not inherently require a TCP listener. Configure transports first, then start Ditto's networking lifecycle:

```kotlin
ditto.sync.start()
```

Start sync before depending on presence for peer discovery.

## Bind and receive

Topics must match `^[a-zA-Z0-9_ ]{1,15}$`. Keep the returned acceptor reachable while the topic should accept connections.

Open a candidate inside the binding callback when no asynchronous handoff is needed:

```kotlin
@file:OptIn(com.ditto.kotlin.PreviewDataStreams::class)

import com.ditto.kotlin.Ditto
import com.ditto.kotlin.DittoAcceptor
import com.ditto.kotlin.DittoReliability
import com.ditto.kotlin.DittoStream
import com.ditto.kotlin.open
import java.util.concurrent.ConcurrentMap

fun bindReceiver(
    ditto: Ditto,
    streams: ConcurrentMap<String, DittoStream>,
    enqueue: (ByteArray) -> Unit,
): DittoAcceptor = ditto.dataStreams.bindTopic(
    topic = "sensor_data",
    reliability = DittoReliability.Reliable,
) { candidate ->
    val peer = candidate.peerKeyString()
    val stream = candidate.open { inbound ->
        // The wrapper is callback-scoped; payload() returns an owned ByteArray.
        val bytes = inbound.payload()
        enqueue(bytes)
    }
    streams.put(peer, stream)?.close()
}
```

Require `enqueue` to be non-blocking, such as `Channel.trySend` or `MutableSharedFlow.tryEmit`. The receive callback runs directly on a connection driver thread. Call `payload()` once, enqueue its owned `ByteArray`, and return. Do not retain `DittoInbound`, make another redundant copy, or perform blocking I/O, heavy decoding, database work, or UI work in the callback.

Closing the acceptor prevents new connections but does not close streams already opened from it. Close those streams separately.

## Transfer candidate ownership safely

Candidates supplied to `bindTopic` and `connect` are borrowed and close automatically when their callback returns. Open them in the callback or call `take()` before moving work to another coroutine. Close the candidate returned by `take()`.

```kotlin
ditto.dataStreams.bindTopic("sensor_data") { borrowedCandidate ->
    val ownedCandidate = borrowedCandidate.take()
    applicationScope.launch(Dispatchers.IO) {
        ownedCandidate.use { candidate ->
            candidate.open().use { stream ->
                val status = stream.send(initialPayload)
                handleStatus(status)
            }
        }
    }
}
```

Calling `take()` invalidates the original wrapper and transfers its native resource to the returned wrapper. An opened stream is parented to the Data Streams endpoint and remains valid after its candidate closes, but the stream must still be retained and closed.

## Connect and open a sender

Use the continuation-based, suspending `connect` API. It returns whatever the continuation returns and closes the candidate afterward.

```kotlin
@file:OptIn(com.ditto.kotlin.PreviewDataStreams::class)

import com.ditto.kotlin.Ditto
import com.ditto.kotlin.DittoReliability
import com.ditto.kotlin.DittoStream
import com.ditto.kotlin.open

suspend fun openSender(ditto: Ditto, peerKey: String): DittoStream =
    ditto.dataStreams.connect(
        peer = peerKey,
        topic = "sensor_data",
        reliability = DittoReliability.Reliable,
        timeoutMs = 10_000,
    ) { candidate ->
        candidate.open()
    }
```

Presence visibility is only a discovery hint; it does not prove that the remote topic is already bound. Treat initial connection failures as normal during startup and topology churn.

## Send data and apply backpressure

Reject payloads larger than `maxSendSize()`:

```kotlin
require(payload.size.toLong() <= stream.maxSendSize())
```

Use one of the verified send modes:

```kotlin
val status: DittoSendStatus = stream.send(payload)

stream.sendAndForget(payload)

val terminalStatus = stream.send(payload) { operation ->
    var current = operation.currentStatus()
    while (current == DittoSendStatus.Pending) {
        current = operation.awaitStatusChange()
    }
    current
}
```

Treat `DittoSendStatus.Unknown` as an indeterminate terminal result for waiting purposes; do not repeatedly await it. Treat `Sent` as transport send completion, not proof that application code on the receiver processed the payload.

The `DittoSendOperation` is callback-scoped and closes when the block returns. Its `cancel()` is best-effort and cannot guarantee that the remote peer did not receive the message. `sendAndForget` releases the operation handle without cancelling the send.

## Match reliability and exchange arguments

- Default to `DittoReliability.Reliable` for ordered delivery.
- Use `Unreliable` for latency-sensitive data that tolerates loss or reordering, such as real-time audio frames.
- Match the connector's reliability to the topic binding or the connection is rejected.
- Set `requestCompression = true` only when LZ4 benefits the payload enough to justify its CPU and latency cost.
- Pass initial metadata with `connect(arguments = ...)`; read it from the binding-side candidate with `arguments()`.
- A `MaySendArguments.Allowed` candidate may pass response arguments through `open(arguments = ...)`.
- A `MaySendArguments.Disallowed` candidate cannot pass arguments during `open`; its side already had the `connect(arguments = ...)` opportunity.

## Close resources deliberately

All Data Streams handles implement `DittoResource`/`AutoCloseable`. Close them explicitly or with Kotlin `use`.

- Retain each acceptor for the full binding lifetime.
- Retain each stream in one lifecycle owner.
- Use `closeSync()` to initiate a local close, drain queued sends, and await full closure.
- Use `waitUntilClosed()` only to observe closure; it does not initiate a local close and may wait indefinitely.
- Treat inbound values and send operations as callback-scoped.
- On shutdown, cancel connection/retry/monitor jobs, close streams, close acceptors, then close Ditto.
- Use thread-safe collections or synchronization because binding callbacks can arrive concurrently.

Closing the parent Ditto instance closes its endpoint and child resources. Do not use handles afterward.

## Preserve cancellation and reconnect safely

The public `connect` contract declares `CancellationException`, but the inspected 5.1.0 internal implementation can wrap an in-flight cancellation as `DittoException.DataStreamsException`. Catch a direct cancellation first, then confirm the coroutine is still active before retrying a Data Streams error:

```kotlin
import com.ditto.kotlin.DittoException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

try {
    val stream = openSender(ditto, peerKey)
    retainStream(stream)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: DittoException.DataStreamsException) {
    currentCoroutineContext().ensureActive()
    scheduleBoundedRetry(error)
}
```

Use capped exponential backoff with jitter. Cancel retry work with its lifecycle owner, and avoid creating multiple simultaneous connects for the same peer and topic.

## Review generated code

Reject code that:

- Calls `connect` without its continuation.
- Calls `sendSync` or treats `send(payload)` as fire-and-forget.
- Moves a candidate out of a callback without `take()`.
- Fails to close a candidate returned by `take()`.
- Retains `DittoInbound` or `DittoSendOperation` outside its callback.
- Blocks or performs substantial work in `onReceive`.
- Drops an acceptor or stream reference while it is still needed.
- Uses `waitUntilClosed()` as though it initiates closure.
- Retries after `DataStreamsException` without checking coroutine activity.
- Replaces the current transport configuration unintentionally.
- Configures mixed `DITTO_REPLICATION_OVER_NGN` values among peers that must replicate.
- Uses mismatched reliability or an invalid topic.
- Sends without respecting `maxSendSize()`.
