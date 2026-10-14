package dev.julianzhu.blekit.frame

/**
 * Reassembles messages from fragments that may arrive out of order, duplicated,
 * corrupted, or not at all.
 *
 * Not thread-safe by design: feed it from a single coroutine/collector (the
 * notification flow), which is how BLE callbacks are serialised anyway.
 *
 * @param timeoutMillis a partial message with no new fragment for this long is evicted.
 * @param maxPending caps memory if a peer starts many messages and finishes none;
 *   the least recently updated message is evicted first.
 * @param clock injectable for tests; defaults to a monotonic clock.
 */
class FrameAssembler(
    private val timeoutMillis: Long = 5_000,
    private val maxPending: Int = 8,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private class Partial(val total: Int, var lastUpdate: Long) {
        val parts = arrayOfNulls<ByteArray>(total)
        var received = 0
    }

    // LinkedHashMap in access order gives us LRU eviction for free.
    private val pending = LinkedHashMap<Int, Partial>(16, 0.75f, true)

    val pendingCount: Int get() = pending.size

    fun accept(raw: ByteArray): AssemblyEvent {
        val evicted = evictExpired()
        return when (val decoded = Frame.decode(raw)) {
            is DecodeResult.Malformed -> AssemblyEvent.Rejected(decoded.reason, evicted)
            is DecodeResult.CrcMismatch -> AssemblyEvent.Rejected(
                "crc mismatch: expected %04X got %04X".format(decoded.expected, decoded.actual), evicted,
            )
            is DecodeResult.Ok -> accept(decoded.frame, evicted)
        }
    }

    private fun accept(frame: Frame, evicted: List<AssemblyEvent.Evicted>): AssemblyEvent {
        val now = clock()
        if (frame.total == 1) return AssemblyEvent.Complete(frame.seq, frame.payload, evicted)

        val existing = pending[frame.seq]
        if (existing != null && existing.total != frame.total) {
            // Same seq, different shape: the peer restarted numbering. Trust the newest.
            pending.remove(frame.seq)
        }
        val partial = pending.getOrPut(frame.seq) { Partial(frame.total, now) }
        val extraEvicted = if (pending.size > maxPending) evictOldest() else emptyList()
        val allEvicted = evicted + extraEvicted

        if (partial.parts[frame.index] != null) {
            return AssemblyEvent.Duplicate(frame.seq, frame.index, allEvicted)
        }
        partial.parts[frame.index] = frame.payload
        partial.received++
        partial.lastUpdate = now

        if (partial.received < partial.total) {
            return AssemblyEvent.Progress(frame.seq, partial.received, partial.total, allEvicted)
        }
        pending.remove(frame.seq)
        val size = partial.parts.sumOf { it!!.size }
        val out = ByteArray(size)
        var pos = 0
        for (p in partial.parts) {
            p!!.copyInto(out, pos)
            pos += p.size
        }
        return AssemblyEvent.Complete(frame.seq, out, allEvicted)
    }

    /** Drops partial messages that have timed out. Safe to call from a ticker. */
    fun evictExpired(): List<AssemblyEvent.Evicted> {
        val now = clock()
        val out = mutableListOf<AssemblyEvent.Evicted>()
        val it = pending.entries.iterator()
        while (it.hasNext()) {
            val (seq, p) = it.next()
            if (now - p.lastUpdate >= timeoutMillis) {
                out += p.toEvicted(seq)
                it.remove()
            }
        }
        return out
    }

    private fun evictOldest(): List<AssemblyEvent.Evicted> {
        val (seq, p) = pending.entries.first()
        pending.remove(seq)
        return listOf(p.toEvicted(seq))
    }

    private fun Partial.toEvicted(seq: Int) =
        AssemblyEvent.Evicted(seq, parts.indices.filter { parts[it] == null })

    fun clear() = pending.clear()
}

sealed interface AssemblyEvent {
    /** Messages dropped as a side effect of this call, with the fragment indices that never arrived. */
    val evicted: List<Evicted>

    class Complete(val seq: Int, val data: ByteArray, override val evicted: List<Evicted> = emptyList()) : AssemblyEvent
    data class Progress(val seq: Int, val received: Int, val total: Int, override val evicted: List<Evicted> = emptyList()) : AssemblyEvent
    data class Duplicate(val seq: Int, val index: Int, override val evicted: List<Evicted> = emptyList()) : AssemblyEvent
    data class Rejected(val reason: String, override val evicted: List<Evicted> = emptyList()) : AssemblyEvent

    data class Evicted(val seq: Int, val missing: List<Int>) : AssemblyEvent {
        override val evicted: List<Evicted> get() = emptyList()
    }
}
