package pvpbots.path

import pvpbots.math.v
import kotlin.test.Test
import kotlin.test.assertEquals

class StuckDetectorTest {
    @Test fun stuckRetryThenTeleport() {
        val s = StuckDetector(100, 200)
        assertEquals(Verdict.OK, s.update(0, v(0.0, 1.0, 0.0), v(10.0, 1.0, 0.0)))
        assertEquals(Verdict.RETRY, s.update(100, v(0.0, 1.0, 0.0), v(10.0, 1.0, 0.0)))
        assertEquals(Verdict.TELEPORT, s.update(200, v(0.0, 1.0, 0.0), v(10.0, 1.0, 0.0)))
    }

    @Test fun progressResets() {
        val s = StuckDetector(100, 200)
        s.update(0, v(0.0, 1.0, 0.0), v(10.0, 1.0, 0.0))
        assertEquals(Verdict.OK, s.update(100, v(2.0, 1.0, 0.0), v(10.0, 1.0, 0.0)))
    }

    @Test fun resetClearsCheckpoint() {
        val s = StuckDetector(100, 200)
        s.update(0, v(0.0, 1.0, 0.0), v(10.0, 1.0, 0.0))
        s.reset()
        assertEquals(Verdict.OK, s.update(500, v(0.0, 1.0, 0.0), v(10.0, 1.0, 0.0)))
    }
}
