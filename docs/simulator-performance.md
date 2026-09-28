# Simulator response latency

The saved run identifies ClearSimulator as the slow simulator, rather than
traffic. At timestep 17, clear took 2492 ms, traffic 201 ms, misc 3 ms and
collapse 0 ms. Clear's recent timings tracked the number of synchronous kernel
round trips closely:

| Timestep | Clear processing | ID request/reply pairs |
| --- | ---: | ---: |
| 13 | 1513 ms | 17 |
| 14 | 2961 ms | 33 |
| 15 | 1511 ms | 17 |
| 16 | 2571 ms | 29 |
| 17 | 2492 ms | 28 |

**ID allocation and request behavior are unchanged**, including requests for zero
IDs. Clear's geometry, entity creation and command processing are unchanged.

## Transport change

The wire format has a four-byte length followed by the protobuf payload.
`EncodingTools.writeInt32` writes the length one byte at a time. Previously these
writes went directly to the socket, with Nagle's algorithm enabled. Small,
synchronous messages can then incur Nagle/delayed-ACK stalls.

`TCPConnection` now wraps the socket output in `BufferedOutputStream` and enables
`TCP_NODELAY`. The existing write thread still flushes after every complete
message. Message contents, ordering and framing are unchanged, but small headers
and payloads reach the socket together and replies need not wait for an earlier
packet's acknowledgement. This applies to kernel, simulator and agent processes
using this connection class.

Three regression tests verify exact wire bytes and coalesced writes for small
messages, decoding/order for small/large/small sequences, and real connection
worker threads exchanging 20 requests (including zero-ID requests) in order and
shutting down cleanly over loopback TCP. All 14 tests pass.

A standalone loopback benchmark uses the actual StreamConnection serialization
and deserialization methods, comparing the old raw-socket/default-Nagle path
with the new TCPConnection path. Each mode sends the same 30 request/reply pairs,
with two warmup pairs and a median of three runs:

| Mode | 30 round trips |
| --- | ---: |
| Original | 2652.19 ms |
| Buffered + TCP_NODELAY | 10.78 ms |

This measures transport/framing, not a whole simulator timestep, and excludes
component worker queues. The approximately 88 ms original round-trip latency
matches the pattern in the saved clear timings. Re-run the real simulation to
measure the full improvement; the transport ratio is not a whole-server speedup.

## Build and reproduce

```sh
./gradlew test launcherJar kernelJar rescuecore2Jar
java -cp 'build/classes/java/main:build/classes/java/test:lib/*' rescuecore2.connection.TCPRoundTripBenchmark
```

The benchmark opens loopback sockets only. Restart the server and its simulator
processes to load the rebuilt JARs.

## New kernel diagnostics

```text
Simulator response (ms): name=..., id=..., time=..., elapsed=...
Simulator update log (ms): ...
```

Response time is measured from command dispatch to receipt of the simulator's
first update for that timestep, including queueing, network transport and simulator
work. It is recorded on receipt rather than inferred from the kernel's sequential
`getUpdates` calls, so an earlier slow simulator cannot make a later simulator
appear artificially fast. Update-log time is reported separately, since the
existing `Simulator updates took` total also includes writing that record.
