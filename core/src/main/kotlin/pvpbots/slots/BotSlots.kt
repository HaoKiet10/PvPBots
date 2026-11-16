package pvpbots.slots

import pvpbots.session.SessionKey

/**
 * Server-wide cap on live bots with a FIFO queue. One queue entry per requested slot.
 * Not thread-safe; call from the server main thread.
 */
class BotSlots(capacity: Int) {
    private var capacity: Int = capacity.coerceAtLeast(0)
    private var used: Int = 0
    private val queue = ArrayDeque<SessionKey>()

    /** Enqueues [n] slots for [key], then grants from the queue head while capacity allows. Returns every key granted, one entry per slot. */
    fun request(key: SessionKey, n: Int): List<SessionKey> {
        repeat(n.coerceAtLeast(0)) { queue.addLast(key) }
        return drain()
    }

    /** Drops all queued (not yet granted) requests of [key]. */
    fun cancel(key: SessionKey) {
        queue.removeAll { it == key }
    }

    /** Frees [n] slots (never below 0 used) and returns keys newly granted from the queue head. */
    fun release(n: Int): List<SessionKey> {
        used = (used - n.coerceAtLeast(0)).coerceAtLeast(0)
        return drain()
    }

    fun used(): Int = used
    fun capacity(): Int = capacity
    fun queued(): Int = queue.size

    /** 1-based position of the key's first queued entry, 0 if none. */
    fun queuePosition(key: SessionKey): Int = queue.indexOf(key) + 1

    /** Never revokes used slots; grants nothing until used < capacity (next request/release). */
    fun setCapacity(capacity: Int) {
        this.capacity = capacity.coerceAtLeast(0)
    }

    fun clear() {
        used = 0
        queue.clear()
    }

    private fun drain(): List<SessionKey> {
        val granted = ArrayList<SessionKey>()
        while (used < capacity && queue.isNotEmpty()) {
            granted += queue.removeFirst()
            used++
        }
        return granted
    }
}
