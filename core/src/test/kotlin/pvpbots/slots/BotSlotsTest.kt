package pvpbots.slots

import pvpbots.session.SessionKey
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BotSlotsTest {
    private val A = SessionKey.Trainer(UUID.fromString("00000000-0000-0000-0000-00000000000a"))
    private val B = SessionKey.Trainer(UUID.fromString("00000000-0000-0000-0000-00000000000b"))
    private val C = SessionKey.Trainer(UUID.fromString("00000000-0000-0000-0000-00000000000c"))
    private val SQ = SessionKey.Squad("world", "boss")

    @Test fun grantsUpToCapacity() {
        val s = BotSlots(2)
        assertEquals(2, s.request(A, 3).size)
        assertEquals(1, s.queued())
    }

    @Test fun releaseGrantsFifo() {
        val s = BotSlots(1)
        s.request(A, 1); s.request(B, 1); s.request(C, 1)
        assertEquals(listOf(B), s.release(1))
        assertEquals(1, s.queuePosition(C))
    }

    @Test fun cancelRemovesQueuedOnly() {
        val s = BotSlots(1)
        s.request(A, 1); s.request(B, 2); s.cancel(B)
        assertEquals(0, s.queued())
        assertEquals(1, s.used())
    }

    @Test fun squadPartialFill() {
        val s = BotSlots(3)
        s.request(A, 1)
        assertEquals(2, s.request(SQ, 5).size)
        assertEquals(3, s.queued())
    }

    @Test fun clearEmptiesQueueAndReleasesAll() {
        val s = BotSlots(1)
        s.request(A, 1); s.request(B, 1); s.clear()
        assertEquals(0, s.used())
        assertEquals(0, s.queued())
    }

    @Test fun shrinkingCapacityNeverGrantsOver() {
        val s = BotSlots(3)
        s.request(A, 3); s.setCapacity(1)
        assertTrue(s.release(1).isEmpty())
        assertEquals(2, s.used())
    }
}
