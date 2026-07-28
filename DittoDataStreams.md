# Module `DittoDataStreams` — Ditto Kotlin SDK

**Package:** `com.ditto.kotlin`  
**API target:** Ditto Kotlin SDK 5.1.0  
**Status:** Preview

The Ditto Data Streams API provides bidirectional, low-latency communication between Ditto peers. This API allows applications to establish topic-based connections for streaming data beyond the traditional document-based synchronization.

> [!WARNING]
> **Preview API**
>
> This API is in preview and may receive breaking changes without notice. Use `@OptIn(PreviewDataStreams::class)` to acknowledge this.

## Contents

- [Annotations](#annotations)
  - [`PreviewDataStreams`](#previewdatastreams)
- [Classes](#classes)
  - [`DittoDataStreamsEndpoint`](#dittodatastreamsendpoint)
  - [`DittoAcceptor`](#dittoacceptor)
  - [`DittoStream`](#dittostream)
  - [`DittoStreamCandidate`](#dittostreamcandidate)
  - [`DittoInbound`](#dittoinbound)
  - [`DittoSendOperation`](#dittosendoperation)
- [Sealed classes](#sealed-classes)
  - [`MaySendArguments`](#maysendarguments)
- [Enums](#enums)
  - [`DittoReliability`](#dittoreliability)
  - [`DittoStreamStatus`](#dittostreamstatus)
  - [`DittoSendStatus`](#dittosendstatus)
- [Extension functions](#extension-functions)
  - [`open` (`MaySendArguments.Allowed`)](#open-allowed)
  - [`open` (`MaySendArguments.Disallowed`)](#open-disallowed)

## Annotations

<a id="previewdatastreams"></a>

### `PreviewDataStreams`

```kotlin
@RequiresOptIn
annotation class PreviewDataStreams
```

Marks APIs that are in preview and may be changed in the future without notice.

Users must explicitly opt-in using `@OptIn(PreviewDataStreams::class)` to use Data Streams APIs.

## Classes

<a id="dittodatastreamsendpoint"></a>

### `DittoDataStreamsEndpoint`

```kotlin
class DittoDataStreamsEndpoint : DittoResource
```

The core of the Ditto Data Streams API, allowing topics to be bound and connections to be made.

This class provides the main entry points for creating data stream connections between peers.

#### `bindTopic`

```kotlin
fun bindTopic(
    topic: String,
    reliability: DittoReliability = DittoReliability.Reliable,
    onNewCandidate: (DittoStreamCandidate<MaySendArguments.Allowed>) -> Unit
): DittoAcceptor
```

Binds a topic, allowing for connections to be made to it. This makes the peer act as a server for this topic.

**Parameters**

- `topic` — The topic to bind. Must be a valid topic matching the regex: `^[a-zA-Z0-9_ ]{1,15}$`
- `reliability` — The expected reliability for streams on this topic (default: `Reliable`)
- `onNewCandidate` — Callback invoked when a new candidate connects. Each call is made from a separate thread.

The candidate is valid only for the duration of the callback. Open it before the callback returns, or call `take()` to transfer ownership and close the returned candidate when finished.

**Throws**

- `DittoException` if the topic cannot be bound.

#### `connect`

```kotlin
suspend fun <T> connect(
    peer: String,
    topic: String,
    requestCompression: Boolean = false,
    reliability: DittoReliability = DittoReliability.Reliable,
    timeoutMs: Long = 0,
    arguments: ByteArray? = null,
    continuation: suspend (DittoStreamCandidate<MaySendArguments.Disallowed>) -> T
): T
```

Connects to a peer on a topic, invokes `continuation` with a stream candidate after the remote peer accepts, and returns the continuation's result. The candidate closes automatically when the continuation returns unless ownership is transferred with `take()`.

**Parameters**

- `peer` — The public key of the peer to connect to, in `peerKeyString` format.
- `topic` — The topic to connect to.
- `requestCompression` — Whether to request LZ4 compression for a reliable stream (default: `false`). The SDK ignores this setting for unreliable streams.
- `reliability` — The expected reliability for the stream (default: `Reliable`).
- `timeoutMs` — Connection timeout in milliseconds. `0` uses Ditto's default of 10s.
- `arguments` — Optional arguments sent to the acceptor. Available to the remote peer via `DittoStreamCandidate.arguments()`.
- `continuation` — Suspending block that receives the accepted candidate and returns the value returned by `connect`.

**Throws**

- `DittoException` if the connection could not be made.
- `CancellationException` if the coroutine is cancelled.

> [!NOTE]
> The inspected 5.1.0 implementation can wrap an in-flight cancellation as `DittoException.DataStreamsException`. Before retrying that exception, check `currentCoroutineContext().ensureActive()` so a cancelled coroutine does not reconnect.

<a id="dittoacceptor"></a>

### `DittoAcceptor`

```kotlin
class DittoAcceptor : DittoResource
```

An acceptor returned from binding a topic. Represents an active server binding for a specific topic.

#### `topic`

```kotlin
fun topic(): String
```

Returns the topic of the acceptor.

#### `reliability`

```kotlin
fun reliability(): DittoReliability
```

Returns the expected reliability of the acceptor. Any attempt at connecting with the wrong reliability will be rejected automatically.

<a id="dittostream"></a>

### `DittoStream`

```kotlin
class DittoStream : DittoResource
```

A stream for bidirectional communication between peers.

#### `send` with operation monitoring

```kotlin
suspend fun <T> send(
    payload: ByteArray,
    withSendOperation: suspend (DittoSendOperation) -> T
): T
```

Sends a message on the stream with access to the send operation for monitoring progress.

**Parameters**

- `payload` — The message to send.
- `withSendOperation` — Block that receives a `DittoSendOperation` to monitor send progress.

The send operation is valid only for the duration of `withSendOperation` and closes automatically when the block returns.

#### `send`

```kotlin
suspend fun send(payload: ByteArray): DittoSendStatus
```

Sends a message on the stream, suspending until the message's lifecycle ends.

#### `sendAndForget`

```kotlin
fun sendAndForget(payload: ByteArray): Unit
```

Sends a message on the stream, ignoring its lifecycle. Fire-and-forget variant.

#### `peerKeyString`

```kotlin
fun peerKeyString(): String
```

Returns the public key of the peer that this stream is connected to.

#### `topic`

```kotlin
fun topic(): String
```

Returns the topic of the stream.

#### `maxSendSize`

```kotlin
fun maxSendSize(): Long
```

Returns the maximum size for a single send operation.

#### `waitUntilClosed`

```kotlin
suspend fun waitUntilClosed(): DittoStreamStatus
```

Suspends until the stream is closed, returning a status indicating why the stream was closed.

> [!NOTE]
> This does not initiate local closure. It resumes when the stream closes locally or remotely and returns the corresponding status. It can wait indefinitely if neither side closes the stream. Use `closeSync()` to initiate local closure, drain the send queue, and wait for the stream to close.

#### `closeSync`

```kotlin
suspend fun closeSync(): DittoStreamStatus
```

Prevents further send operations and closes the stream once the send queue is fully drained. Suspends until the stream is effectively closed.

<a id="dittostreamcandidate"></a>

### `DittoStreamCandidate`

```kotlin
class DittoStreamCandidate<Args : MaySendArguments> : DittoResource
```

A candidate for opening a stream. Use the `open()` extension functions to convert this into a `DittoStream`.

The type parameter `Args` determines whether arguments can be sent when opening the stream.

Candidates received by `bindTopic` and `connect` are callback-scoped. Open the candidate before its callback returns, or use `take()` to transfer ownership.

#### `peerKeyString`

```kotlin
fun peerKeyString(): String
```

Returns the public key of the peer that this candidate is connected to.

#### `topic`

```kotlin
fun topic(): String
```

Returns the topic of the stream.

#### `arguments`

```kotlin
fun arguments(): ByteArray?
```

Returns the arguments set by the remote peer, if any. Returns `null` if the peer didn't set any arguments.

#### `take`

```kotlin
fun take(): DittoStreamCandidate<Args>
```

Transfers ownership of the candidate so it can be used after the callback that produced it returns. This invalidates the original wrapper. The caller must close the returned candidate to avoid leaking its native resource.

An opened `DittoStream` remains valid after its candidate closes, but the stream must still be retained and closed separately.

<a id="dittoinbound"></a>

### `DittoInbound`

```kotlin
class DittoInbound : DittoResource
```

Inbound data from a stream, representing a received message. Treat it as callback-scoped; retain the copied `ByteArray` returned by `payload()`, not the `DittoInbound` wrapper.

#### `payload`

```kotlin
fun payload(): ByteArray
```

Returns the payload of the message.

<a id="dittosendoperation"></a>

### `DittoSendOperation`

```kotlin
class DittoSendOperation : DittoResource
```

Represents an ongoing send operation, allowing monitoring and cancellation of the send.

#### `currentStatus`

```kotlin
fun currentStatus(): DittoSendStatus
```

Returns the current status of the send operation.

#### `cancel`

```kotlin
fun cancel(): Unit
```

Attempts to cancel the send operation. This is best-effort and cannot guarantee the message won't reach the remote peer.

> [!NOTE]
> Cancelling is meant as a way to save bandwidth when sending is no longer necessary, not as a way to prevent the consequences of the message being received.

#### `awaitStatusChange`

```kotlin
suspend fun awaitStatusChange(): DittoSendStatus
```

Suspends until the status of the send operation changes, returning the new status. Can be used to implement backpressure.

## Sealed classes

<a id="maysendarguments"></a>

### `MaySendArguments`

```kotlin
sealed class MaySendArguments
```

A type hint to show whether or not a `DittoStreamCandidate` can send arguments when opened.

#### `Allowed`

```kotlin
class Allowed : MaySendArguments()
```

The `DittoStreamCandidate` may send arguments to the remote when `open()` is called.

This applies to candidates received in `bindTopic()`'s callback, where we act as the server.

#### `Disallowed`

```kotlin
class Disallowed : MaySendArguments()
```

The `DittoStreamCandidate` may not send arguments to the remote.

This applies to candidates returned from `connect()`, as arguments were already sent in that call.

## Enums

<a id="dittoreliability"></a>

### `DittoReliability`

```kotlin
enum class DittoReliability
```

Reliability level for streams.

**Variants**

- `Unreliable` — Messages may be lost or arrive out of order (lower latency).
- `Reliable` — Messages are guaranteed to arrive in order (higher latency).

<a id="dittostreamstatus"></a>

### `DittoStreamStatus`

```kotlin
enum class DittoStreamStatus
```

Status indicating the current state of a stream.

**Variants**

- `Open` — The stream is open and active.
- `ClosedByRemote` — The stream was closed by the remote peer.
- `ClosedByLocal` — The stream was closed locally.

<a id="dittosendstatus"></a>

### `DittoSendStatus`

```kotlin
enum class DittoSendStatus
```

Status indicating the lifecycle state of a send operation.

**Variants**

- `Unknown` — Status is indeterminate. Do not repeatedly wait for it to change.
- `Pending` — Message is queued for sending.
- `Sent` — The payload was successfully submitted to the local QUIC send machinery. For either reliability mode, this does not prove remote receipt or that receiver application code processed the payload. Reliable delivery is handled by the stream protocol, but `Sent` itself is not a peer acknowledgment.
- `Failed` — Send operation failed.
- `Cancelled` — Send operation was cancelled.

## Extension functions

<a id="open-allowed"></a>

### `open` for `MaySendArguments.Allowed`

#### Write-only overload

```kotlin
fun DittoStreamCandidate<MaySendArguments.Allowed>.open(
    arguments: ByteArray? = null
): DittoStream
```

Opens the stream as write-only, ignoring all messages that may be received on the returned `DittoStream`.

**Parameters**

- `arguments` — Optional arguments to send to the acceptor when opening the stream.

**Throws**

- `DittoException` if the stream cannot be opened.

#### Receiving overload

```kotlin
fun DittoStreamCandidate<MaySendArguments.Allowed>.open(
    arguments: ByteArray? = null,
    onReceive: (DittoInbound) -> Unit
): DittoStream
```

Opens the stream, calling the provided callback whenever a message is received.

**Parameters**

- `arguments` — Optional arguments to send to the acceptor when opening the stream.
- `onReceive` — Callback invoked when messages are received.

> [!WARNING]
> **Performance warning**
>
> The `onReceive` callback is called directly by the connection's driver thread to minimize latency. You must return quickly to avoid blocking the connection, which could lead to delayed messages or disconnections due to timeouts.

**Throws**

- `DittoException` if the stream cannot be opened.

<a id="open-disallowed"></a>

### `open` for `MaySendArguments.Disallowed`

#### Write-only overload

```kotlin
fun DittoStreamCandidate<MaySendArguments.Disallowed>.open(): DittoStream
```

Opens the stream as write-only, ignoring all messages that may be received on the returned `DittoStream`.

**Throws**

- `DittoException` if the stream cannot be opened.

#### Receiving overload

```kotlin
fun DittoStreamCandidate<MaySendArguments.Disallowed>.open(
    onReceive: (DittoInbound) -> Unit
): DittoStream
```

Opens the stream, calling the provided callback whenever a message is received.

**Parameters**

- `onReceive` — Callback invoked when messages are received.

> [!WARNING]
> **Performance warning**
>
> The `onReceive` callback is called directly by the connection's driver thread to minimize latency. You must return quickly to avoid blocking the connection.

**Throws**

- `DittoException` if the stream cannot be opened.
