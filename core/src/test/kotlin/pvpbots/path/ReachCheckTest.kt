package pvpbots.path

import pvpbots.math.v
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReachCheckTest {
    private val a = UUID(0, 1)
    private val b = UUID(0, 2)
    private val from = v(0.0, 1.0, 0.0)
    private val goal = v(10.0, 6.0, 0.0)

    @Test fun firstCheckIsDueAndDefaultIsReachable() {
        val c = ReachCheck()
        assertTrue(c.reachable)
        assertTrue(c.due(0, a, from, goal))
    }

    @Test fun sameTargetStillWithinIntervalIsNotDue() {
        val c = ReachCheck(recheckTicks = 20)
        c.record(100, a, from, goal, false)
        assertFalse(c.reachable)
        assertFalse(c.due(101, a, from, goal))
        assertFalse(c.due(119, a, v(1.5, 1.0, 0.0), v(10.0, 6.0, 1.5)))
        assertTrue(c.due(120, a, from, goal))
    }

    @Test fun newTargetOrMovementMakesItDue() {
        val c = ReachCheck(recheckTicks = 20, moveTolerance = 2.0)
        c.record(100, a, from, goal, true)
        assertTrue(c.due(101, b, from, goal))
        assertTrue(c.due(101, a, v(2.5, 1.0, 0.0), goal))
        assertTrue(c.due(101, a, from, v(10.0, 6.0, 2.5)))
    }
}
