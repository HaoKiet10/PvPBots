package pvpbots.bukkit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class FailureLogTest {
    @Test fun firstFull_thenSilent_thenOneSummaryPerMinute() {
        val log = FailureLog(60_000)
        assertEquals(FailureLog.Action.Full, log.record("A", 0))
        assertEquals(FailureLog.Action.Silent, log.record("A", 5_000))
        assertEquals(FailureLog.Action.Silent, log.record("A", 59_999))
        assertEquals(FailureLog.Action.Summary(3, 4), log.record("A", 60_000))
        assertEquals(FailureLog.Action.Silent, log.record("A", 61_000))
        assertEquals(FailureLog.Action.Summary(2, 6), log.record("A", 130_000))
    }

    @Test fun distinctSignaturesAreLoggedInFullOnce() {
        val log = FailureLog(60_000)
        assertEquals(FailureLog.Action.Full, log.record("A", 0))
        assertEquals(FailureLog.Action.Full, log.record("B", 1))
        assertEquals(FailureLog.Action.Silent, log.record("B", 2))
    }

    private fun boom(msg: String): Throwable = try {
        throw IllegalStateException(msg)
    } catch (e: IllegalStateException) {
        e
    }

    @Test fun signatureIgnoresTheMessage_butNotTheClassOrFrame() {
        val a = boom("bot 1")
        val b = boom("bot 2")
        assertEquals(FailureLog.signature(a), FailureLog.signature(b))
        assertNotEquals(FailureLog.signature(a), FailureLog.signature(NoSuchMethodError("x")))
        val log = FailureLog()
        assertEquals(FailureLog.Action.Full, log.record(a, 0))
        assertEquals(FailureLog.Action.Silent, log.record(b, 1))
    }
}
