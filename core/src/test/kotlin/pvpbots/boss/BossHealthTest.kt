package pvpbots.boss

import kotlin.test.Test
import kotlin.test.assertEquals

class BossHealthTest {
    @Test fun startScalesByPlayers() {
        val h = BossHealth(400.0, 0.6)
        h.start(1)
        assertEquals(400.0, h.max, 1e-9)
        h.start(3)
        assertEquals(880.0, h.max, 1e-9)
        assertEquals(880.0, h.current, 1e-9)
        assertEquals(1.0, h.fraction, 1e-9)
    }

    @Test fun joiningRaisesMaxKeepingFraction() {
        val h = BossHealth(400.0, 0.6)
        h.start(3)
        h.hit(440.0, recovering = false, roaring = false)
        assertEquals(0.5, h.fraction, 1e-9)
        h.playersNow(4)
        assertEquals(1120.0, h.max, 1e-9)
        assertEquals(560.0, h.current, 1e-9)
    }

    @Test fun leavingNeverLowers() {
        val h = BossHealth(400.0, 0.6)
        h.start(3)
        h.playersNow(2)
        assertEquals(880.0, h.max, 1e-9)
        assertEquals(880.0, h.current, 1e-9)
    }

    @Test fun recoveryAmplifiesRoarBlocks() {
        val h = BossHealth(400.0, 0.6)
        h.start(1)
        assertEquals(15.0, h.hit(10.0, recovering = true, roaring = false), 1e-9)
        assertEquals(385.0, h.current, 1e-9)
        assertEquals(0.0, h.hit(10.0, recovering = false, roaring = true), 1e-9)
        assertEquals(385.0, h.current, 1e-9)
    }

    @Test fun neverBelowZero() {
        val h = BossHealth(10.0, 0.6)
        h.start(1)
        assertEquals(10.0, h.hit(50.0, recovering = false, roaring = false), 1e-9)
        assertEquals(0.0, h.current, 1e-9)
        assertEquals(0.0, h.fraction, 1e-9)
    }
}
