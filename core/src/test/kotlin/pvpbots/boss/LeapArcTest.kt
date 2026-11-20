package pvpbots.boss

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LeapArcTest {
    @Test fun landsOnTheGoalAfterTheFlightTime() {
        for ((d, ticks) in listOf(Triple(8.0, 0.0, 6.0) to 16, Triple(-3.0, 6.0, 2.0) to 22, Triple(12.0, -1.0, -9.0) to 28)) {
            val v = LeapArc.startVelocity(d.first, d.second, d.third, ticks)
            val p = LeapArc.simulate(v, ticks)
            assertEquals(d.first, p.x, 1e-9)
            assertEquals(d.second, p.y, 1e-9)
            assertEquals(d.third, p.z, 1e-9)
        }
    }

    @Test fun goesUpThenComesDownOntoTheGoal() {
        val ticks = 18
        val v = LeapArc.startVelocity(10.0, 0.0, 0.0, ticks)
        assertTrue(v.y > 0.0)
        val before = LeapArc.simulate(v, ticks - 1).y
        assertTrue(before > 0.0, "still above the landing height one tick before landing: $before")
    }
}
