# ble-kit-sample

Small Kotlin building blocks for talking to BLE wearables: checksums, a framing
layer for notifications, and a connection manager that keeps a link alive. It is
a cut-down, from-scratch version of patterns I have used on wearable and OTA
projects, written to be read in one sitting.

## Modules

- **crc**: table-driven CRC-16/CCITT-FALSE (frame integrity) and CRC-32
  (whole-image check after OTA). Both support incremental updates so large
  images can be verified while streaming.
- **frame**: a 7-byte header + payload + CRC16 format, a fragmenter that
  sizes frames from the negotiated MTU, and `FrameAssembler`, which rebuilds
  messages from fragments that arrive out of order, duplicated, corrupted or
  not at all.
- **connection**: `BleConnectionManager`, a coroutine-driven state machine
  (`Disconnected`, `Connecting`, `Connected`, `Reconnecting`, `Failed`) exposed
  as a `StateFlow`, with exponential backoff, jitter, connect timeouts and an
  attempt cap. It talks to the radio only through `BleTransport`.
- **android-adapter/GattTransport.kt**: a reference `BleTransport` built on
  `BluetoothGatt`. It is not in the Gradle build because that would require the
  Android SDK; copy it into an Android library module that depends on
  `:connection`.

## Wire format

```
0  2  magic   B1 E5
2  2  seq     message id, big-endian
4  1  total   fragment count (1..255)
5  1  index   0-based fragment index
6  1  len     payload length
7  n  payload
.  2  crc16   CCITT-FALSE over header + payload, big-endian
```

## Design notes

**Decoding never throws.** `Frame.decode` returns a sealed result. Radio data is
untrusted input, and an exception thrown from inside a notification collector
tends to take the whole flow down with it.

**The assembler is single-threaded on purpose.** BLE notifications already
arrive serialised, so it is meant to be fed from one collector. That keeps it
lock-free and easy to reason about. Partials are kept in an access-ordered
`LinkedHashMap`, which gives both timeout eviction and an LRU cap on memory.
Evictions are returned with the list of missing fragment indices, so the caller
can log them or ask the peer to resend exactly those fragments.

**A corrupt fragment does not poison its message.** It is rejected on its own and
the slot stays open for a retransmit.

**The connection state machine is a single coroutine.** Rather than a state
enum mutated from callbacks, the states are just positions in one `while` loop.
Cancellation goes through the same path as everything else: `disconnect()`
cancels the job, and a `finally` block closes the session under
`NonCancellable` and publishes `Disconnected`. A connect timeout is re-thrown as
`ConnectTimeoutException`, so it counts as a failed attempt rather than being
mistaken for cancellation.

**Backoff resets after a successful link.** A drop from a working connection
retries quickly; a run of failed connects backs off up to `maxDelayMillis` and
gives up after `maxAttempts`. Jitter keeps several devices coming back into
range from retrying in lockstep.

**The clock and randomness are injected.** The assembler takes a clock
function and the manager takes a `Random`, so tests are deterministic and the
connection tests run on virtual time with `kotlinx-coroutines-test`. The whole
suite finishes in about a second, even though it covers tens of seconds of
simulated backoff.

## Running the tests

Requires JDK 17.

```
./gradlew test
```

## License

MIT, see [LICENSE](LICENSE).
