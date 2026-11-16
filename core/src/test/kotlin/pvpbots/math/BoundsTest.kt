package pvpbots.math

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoundsTest {
    @Test fun clampPullsInside() {
        val b = bounds(0, 0, 0, 10, 10, 10)
        assertTrue(b.contains(b.clamp(v(15.0, 5.0, 5.0))))
    }
    @Test fun containsBlockInclusive() {
        val b = bounds(0, 0, 0, 10, 10, 10)
        assertTrue(b.contains(BlockPos(10, 10, 10))); assertFalse(b.contains(BlockPos(11, 0, 0)))
    }
    @Test fun containsVecUsesBlockVolume() {
        val b = bounds(0, 0, 0, 10, 10, 10)
        assertTrue(b.contains(v(10.9, 0.0, 0.0))); assertFalse(b.contains(v(11.0, 0.0, 0.0)))
    }
}
