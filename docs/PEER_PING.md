# Peer ping

## Use the tool

1. Run updated Whiteboard apps on two peers with the same database ID and valid
   offline licenses. Allow nearby access and keep both apps in the foreground.
2. Open the board sidebar. Choose **Transport** on Apple or **Troubleshooting**
   on Android, then find **Peer ping**.
3. Select a discovered peer and choose **Run ping**. The remote app responds
   automatically; its troubleshooting screen does not need to be open.
4. Read the five echo RTTs and the verified-echo count. **Cancel ping** stops a run.

Ping is unavailable in local-preview mode. If no peers are listed, investigate
credentials, permissions, discovery and network access before testing streams.
An older Whiteboard app can appear in presence without supporting this tool.

## What a result means

Each successful sample confirms a full application-level round trip: the client
sent bytes, the remote app echoed them, and the client verified every byte. The
local monotonic clock measures elapsed time including application scheduling,
queueing, transport and validation overhead. The peers do not need synchronized
clocks. This is a diagnostic sample, not a radio benchmark.

The independent ping topic does not read or change the board or use Ditto Store.
A successful ping does not prove that `wb_state` reconciliation has completed,
that unreliable previews are arriving, or that a particular radio is working.
Use the presence graph and board diagnostics alongside it.

| Outcome | Meaning and next step |
| --- | --- |
| Could not connect | Discovery alone does not guarantee a listener. Keep the other app open, confirm it supports ping, and check reachability. Connection setup has a five-second handshake limit. |
| No echo within 5 seconds | The response deadline expired. The peer may be paused or disconnected; it may also have processed the request before the reply was delayed. |
| Stream closed | The stream ended or local networking paused before a verified response arrived. |
| Response did not match | A received message differed from the outstanding request. That request is not counted as successful. |
| Ping cancelled | The user stopped the run. Previously verified samples remain visible. |

The first failure ends the run. There are no automatic retries or reconnects;
select **Run ping** again after investigating. Earlier successful samples are
retained with the tested peer key so they cannot be mistaken for a newly selected
peer's results.

## Shared protocol

Swift and Kotlin use the same protocol as the Rust `datastream_ping` example:

- Topic: `ping`; reliable, uncompressed, bidirectional Data Stream.
- Each request: a big-endian unsigned 64-bit sequence number followed by zero
  padding. The app uses sequences 0–4 and a total payload size of 64 bytes.
- One outstanding request at a time. The responder returns the whole message
  unchanged on the same stream. The client compares every byte before reporting RTT.
- Send completion is not an acknowledgement. The matching echo is the acknowledgement.
- Each run opens a fresh stream and closes it on completion, failure or cancellation.

Both apps bind a responder while nearby networking is active. Either peer may
initiate; the board's lower-key connection-ownership rule does not apply to ping.
A Swift app can ping Android and vice versa. The UI lists discovered Whiteboard
peers. A Rust client using the same database/license and an app's peer key can
also exercise its responder using the shared topic and wire format.

## Bounds and lifecycle

The app permits one outgoing run at a time. A service retains at most 11 total
sessions, a one-message receive buffer per session, and accepts only 8–1,024-byte
requests. Oversized messages or receive overflow close the session. Responders
serve at most 100 messages per stream and expire idle sessions after 15 seconds.
These are demonstration-tool bounds; a Rust test should stay within them.

The responder, outgoing streams and receive buffers belong to the transport's
nearby lifecycle. Pausing networking or closing the transport unbinds the topic
and closes its sessions. Late connection completions cannot reattach to a closed
session. Closing the troubleshooting UI cancels its outgoing run. Resuming
nearby networking reinstalls the responder without altering the board protocol.

## Validation

Pure Swift and Kotlin tests share a fixed byte-order fixture and cover valid,
corrupted and truncated echoes, transport failure and cancellation. Compose tests cover peer selection, results, timeout, cancellation and local-preview
availability. SDK-backed Apple tests exercise real ping streams alongside board operations. See
[Testing](TESTING.md) for commands and the required physical-device matrix.

Host tests do not certify Swift-to-Android interoperability over real radios.
Before distributing a build, run the matrix in both directions on physical
Apple and Android devices and record the results.
