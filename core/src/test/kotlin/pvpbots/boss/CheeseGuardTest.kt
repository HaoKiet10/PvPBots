package pvpbots.boss

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CheeseGuardTest {
    @Test fun strafingWithinReachNeverTriggers() {
        val g = CheeseGuard()
        g.reset(0)
        for (t in 1L..400L) assertNull(g.check(t, if (t % 2 == 0L) 2.4 else 3.1, 0.0), "tick $t")
    }

    @Test fun outOfReachWithoutProgressTriggersAfterThreeSeconds() {
        val g = CheeseGuard()
        g.reset(0)
        // The first look after a reset sets the baseline (tick 1); 60 ticks later it is stuck.
        for (t in 1L..60L) assertNull(g.check(t, 8.0, 0.0), "tick $t")
        assertEquals(CheeseGuard.Reason.STUCK, g.check(61, 8.0, 0.0))
    }

    @Test fun closingInIsProgress() {
        val g = CheeseGuard()
        g.reset(0)
        var d = 15.0
        for (t in 1L..200L) {
            d = maxOf(4.0, d - 0.1)
            if (d > 4.0) assertNull(g.check(t, d, 0.0), "tick $t")
        }
    }

    @Test fun targetOnAStepIsNotAbove_targetOnAPillarIs() {
        val g = CheeseGuard()
        g.reset(0)
        assertNull(g.check(1, 6.0, 2.0))
        assertEquals(CheeseGuard.Reason.TARGET_ABOVE, g.check(2, 1.0, 6.0))
    }

    @Test fun leavingReachRestartsTheTimerFromThere() {
        val g = CheeseGuard()
        g.reset(0)
        for (t in 1L..100L) assertNull(g.check(t, 3.0, 0.0))
        for (t in 101L..159L) assertNull(g.check(t, 6.0, 0.0), "tick $t")
        assertEquals(CheeseGuard.Reason.STUCK, g.check(160, 6.0, 0.0))
    }
}
