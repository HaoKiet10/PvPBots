package pvpbots.combat

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RefreshClockTest {
    @Test fun dueAtOnceThenEveryPeriod() {
        val c = RefreshClock(3, Random(1))
        assertTrue(c.due(1000))
        c.mark(1000)
        c.mark(1000) // later marks are not backdated
        assertFalse(c.due(1001))
        assertFalse(c.due(1002))
        assertTrue(c.due(1003))
    }

    @Test fun firstMarkIsStaggeredWithinOnePeriod() {
        val nextDue = HashSet<Long>()
        repeat(200) { seed ->
            val c = RefreshClock(3, Random(seed))
            c.mark(1000)
            val next = (1001L..1003L).first { c.due(it) }
            nextDue += next
        }
        assertEquals(setOf(1001L, 1002L, 1003L), nextDue)
    }

    @Test fun setPeriod_appliesNextDue() {
        val c = RefreshClock(10, Random(1))
        c.mark(1000)
        c.mark(1000)
        c.setPeriod(3)
        assertFalse(c.due(1002))
        assertTrue(c.due(1003))
        c.setPeriod(20)
        assertFalse(c.due(1019))
        assertTrue(c.due(1020))
        c.setPeriod(0) // below 1 acts as 1
        assertTrue(c.due(1001))
    }

    @Test fun periodBelowOneActsAsOne() {
        val c = RefreshClock(0, Random(1))
        c.mark(5)
        assertTrue(c.due(6))
    }
}
