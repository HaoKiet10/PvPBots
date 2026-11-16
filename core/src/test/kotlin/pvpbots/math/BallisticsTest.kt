package pvpbots.math

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BallisticsTest {
    @Test fun level20_reachable() {
        val p = Ballistics.pitchFor(v(0.0, 1.5, 0.0), v(0.0, 1.5, 20.0), 3.0)
        assertNotNull(p)
        assertTrue(Math.abs(simulatedYAt(p, 20.0) - 0.0) < 0.5)
    }
    @Test fun tooFar_empty() = assertNull(Ballistics.pitchFor(v(0.0, 0.0, 0.0), v(0.0, 0.0, 500.0), 3.0))
}
