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

    @Test fun chasingARecedingGoalWhileMoving_isNeverStuck() {
        // 20 ticks/s, retry 5 s, teleport 10 s. Bot and goal both run +x at 0.28 blocks/tick: the distance stays 3.
        val s = StuckDetector(100, 200)
        for (t in 0L..1200L) {
            val x = t * 0.28
            assertEquals(Verdict.OK, s.update(t, v(x, 1.0, 0.0), v(x + 3.0, 1.0, 0.0)), "tick $t")
        }
    }

    @Test fun standingStill_retriesAtFiveSecondsAndTeleportsAtTen() {
        val s = StuckDetector(100, 200)
        val pos = v(0.0, 1.0, 0.0)
        val verdicts = (0L..200L).map { t -> s.update(t, pos, v(10.0 + t * 0.28, 1.0, 0.0)) } // goal recedes, bot does not move
        assertEquals(Verdict.OK, verdicts[99])
        assertEquals(Verdict.RETRY, verdicts[100])
        assertEquals(Verdict.RETRY, verdicts[199])
        assertEquals(Verdict.TELEPORT, verdicts[200])
    }

    @Test fun smallJitterIsNotProgress() {
        val s = StuckDetector(100, 200)
        for (t in 0L until 200L) s.update(t, v(if (t % 2 == 0L) 0.0 else 0.6, 1.0, 0.0), v(10.0, 1.0, 0.0))
        assertEquals(Verdict.TELEPORT, s.update(200, v(0.3, 1.0, 0.0), v(10.0, 1.0, 0.0)))
    }
}
