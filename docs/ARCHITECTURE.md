# Architecture & Data Streams walkthrough

This page explains how the Whiteboard app is put together and, specifically, **how it uses the Ditto Data Streams API**. If Streams terminology is new, read [Getting started](GETTING_STARTED.md) first. The two main ideas here are (1) *why the app opens two separate streams* and (2) *how a peer that joins late—or missed an application frame—catches up*.

> API reference: [`DittoDataStreams.md`](../DittoDataStreams.md) · usage guide: [`SKILL.md`](../SKILL.md)

---

## 1. Layers & data flow

![Architecture and data flow](diagrams/architecture.png)

The app is a one-directional stack. **User actions flow down**; **state and inbound network events flow up** (blue arrows). The domain and protocol modules on the right are pure/stateless helpers with no Android or Ditto dependencies, so they can be unit-tested in isolation.

| Layer | Package | Responsibility |
|---|---|---|
| **UI** | `ui/` | Jetpack Compose + Navigation 3. Draws the board, the connected-people roster, and a live presence-graph troubleshooting view. Renders state and forwards pointer events — no business logic. |
| **Presentation** | `ui/WhiteboardViewModel` | `combine`s board state, live previews, the selected tool/color, and diagnostics into a single immutable `BoardUiState`, exposed as a lifecycle-aware `StateFlow`. |
| **Session / coordination** | `data/BoardSession` | The heart of the app. Assigns Lamport-ordered stamps to local operations, de-duplicates and folds operations through `BoardReducer`, keeps a short-lived live-preview cache, and collects inbound transport events. |
| **Transport (interface)** | `transport/WhiteboardTransport` | The seam between the app and the network. `DittoWhiteboardTransport` is the real implementation; `InMemoryWhiteboardTransport` is a graceful fallback that runs as a local-only preview when credentials or the SDK are unavailable. |
| **Ditto SDK** | `ditto.dataStreams` | Two bound topics — `wb_live` (Unreliable) and `wb_state` (Reliable) — plus the presence graph used to discover peer keys. NGN is enabled in `WhiteboardApplication.onCreate()` before Ditto is constructed. |
| **Mesh** | — | `SmallPeersOnly` nearby transports (BLE, LAN/mDNS, Wi-Fi Aware). Up to ten peers, no cloud, ephemeral — nothing is written to the Ditto store. |

**Domain** (`domain/`) holds the immutable operation model and `BoardReducer`, a CRDT-style fold that produces board state from the operation log in Lamport total order, honouring a "clear" watermark. **Protocol** (`protocol/`) turns operations into protobuf envelopes on the wire, gates incompatible protocol versions, computes a canonical state digest, and implements chunked, SHA-256-verified snapshot transfer.

---

## 2. Two Data Streams topics per peer pair

![Two Data Streams topics](diagrams/data-streams-topology.png)

Every peer **binds both topics as an acceptor** and retains those acceptors for the whole session. To avoid opening a stream in both directions, the app uses a simple tie-break: **the peer with the lower public key calls `connect()`**; the higher-key peer only accepts. On the receiving side the candidate is claimed with `take()` inside the bind callback and opened on a background dispatcher. Every `onReceive` callback retains the payload returned by the SDK and only `trySend`s it onto a channel — the SDK's driver thread is never blocked.

| Topic | Reliability | Carries | Delivery characteristics |
|---|---|---|---|
| **`wb_live`** | Unreliable | Live drawing previews (delta-encoded) | ≤ 30 msg/s, drop-oldest, `sendAndForget`, never chunked — optimised for latency, tolerant of loss |
| **`wb_state`** | Reliable | Committed operations, profiles, clears, snapshots | Ordered per peer, LZ4 compression requested, size-checked and chunked past `maxSendSize()`; operations are immutable, idempotent, replayed in Lamport order, and repaired by digest-aware snapshots |

**Why split them?** Real-time strokes need to be fast and can tolerate a dropped frame, so they go over an Unreliable stream. The operations that *define the board* need ordered reliable transport, but the application still handles queue saturation, reconnects, and process failure: a missing operation is repaired by the next digest exchange. Running separate topics prevents a preview burst from sharing an application queue with the board's source of truth.

---

## 3. Late-join snapshot hydration

![Snapshot hydration sequence](diagrams/snapshot-sequence.png)

When a peer joins a board that already has content, it can't replay history it never saw — so an existing peer sends it a **snapshot** over the Reliable stream:

1. As soon as `wb_state` opens, each side sends a **`Hello`** carrying its profile and canonical state digest.
2. If the digests differ and the sender has state, it offers a merge-only snapshot. Both sides may offer; operation ids and deterministic conflict resolution make duplicate exchange safe.
3. **`SnapshotBegin`** announces the transfer id, chunk count, byte count, and SHA-256 digest; the joiner starts hydrating and arms a 30-second inactivity timeout plus a five-minute hard lifetime.
4. **`SnapshotChunk × N`** stream the compressed board state; the joiner reassembles and reports progress.
5. While hydrating, the joiner **buffers** any new reliable operations from that peer and **suppresses** its live previews, so nothing is applied out of order.
6. **`SnapshotEnd`** closes the transfer. The joiner verifies the SHA-256 digest and byte count, then does a **merge-only** apply of the snapshot together with the buffered operations.
7. The joiner replies with **`SnapshotAck(accepted = true)`**.
8. If the received snapshot was only a subset of the joiner's merged history,
   the joiner sends the merged union back. This reciprocal repair means a
   one-way or lost initial `Hello` cannot leave the smaller peer repeatedly
   offering the same subset until its readiness deadline expires.

If no valid chunk arrives for 30 seconds, the five-minute lifetime expires, the
digest does not match, or either peer sends a negative acknowledgment, the app
preserves independently received operations and retries with bounded backoff
(1s, 2s, 4s, then reconnect). Rapid digest-changing Hellos are coalesced rather
than discarded. Because operations are idempotent, re-running the exchange is
safe.

Only one snapshot hydrates globally, so contention for that slot is treated as
**backpressure on both sides**, not as a failure:

- The receiver answers the offer with `SnapshotAck(accepted = false, busy = true)`
  and then waits for the slot, re-checking every 250 ms. When it frees, it sends a
  fresh `Hello`, which often finds the digests already equal.
- The sender reads `busy` and stands down: it marks the peer `Queued` and does
  **not** spend its error budget or tear down the stream. A single long-delay
  re-offer is armed purely in case that `Hello` is lost.

The `busy` flag is why `PROTOCOL_VERSION` is 5. Without it the sender cannot tell
backpressure from failure, so it exhausts its three-strike ladder in about seven
seconds and reconnects — while the receiver is still patiently waiting — which is
exactly the churn a full-mesh late join produces.

The waiter's patience is bounded **per holder, not in total**. With ten peers the
slot is legitimately handed between up to nine transfers in sequence, so any fixed
total deadline would expire on a healthy mesh; the budget therefore restarts
whenever the slot changes hands (peer key plus transfer id, so a retransmit counts
as progress). The wait ends only if one holder overstays a full transfer lifetime
plus finalization — a bound the holder's own hard lifetime cap and the separate
finalization deadline (parsing, session handshake, and merge after the last chunk)
already guarantee — and that peer then reconnects with a **fresh** error budget,
because waiting was never a failure.

Long-running snapshot control work — rejecting a superseded transfer, replaying
the operations buffered behind it, enqueuing an acknowledgment — runs on bounded
per-peer lanes, never on the shared reliable-ingress worker. That worker decodes
for *every* peer, so blocking it there would back up inbound traffic mesh-wide
and cascade into stream teardowns for peers that were perfectly healthy.

Peer-controlled frames are bounded before allocation. Direct operations must
match their connected stream peer, but **a snapshot authenticates only its
relay, not the author of each historical operation it carries** — relaying a
third party's operations is what late-join catch-up *is*, so origin cannot be
checked there. A peer can therefore attribute strokes, a clear, or a profile
change to any other peer key, and can pre-seed another peer's predictable future
sequence numbers to censor that peer's real operations under canonical conflict
resolution. Devices sharing credentials are fully trusted; this board is not an
authenticated log, and closing the gap would require per-operation signatures
and a protocol version bump.

A transfer is limited to 16 MiB and 4,096 chunks, only one snapshot is
hydrated globally, hydration buffering is capped at 64 operations/2 MiB, author
count is capped at 64, and a process-lifetime board stops at 10,000 operations
instead of growing without bound. The current limits are 10,000 total
operations, 128 erase operations, and 512 simultaneously visible objects; a
stroke carries at most 128 points and an eraser at most 32. Terminal history
limits require every participating process to exit before a fresh ephemeral
board can begin.

Nearby collaboration is foreground-only. Actual backgrounding through
`MainActivity.onStop` stops Ditto sync, closes active streams, and cancels
transfer/reconnect work while retaining the in-memory board. `onStart` restarts
sync; the normal digest exchange repairs anything missed in the background.
Configuration recreation (rotation, fold, or locale change) retains the
process-level transport so it does not churn radios or interrupt reconciliation.

---

## Regenerating the diagrams

The diagrams are authored as SVG in [`diagrams/`](diagrams/) and rendered to PNG (GitHub renders PNG reliably in Markdown):

```bash
cd docs/diagrams
for f in architecture data-streams-topology snapshot-sequence; do
  rsvg-convert -z 2 "$f.svg" -o "$f.png"
done
```
