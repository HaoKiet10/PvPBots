package pvpbots.math

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FairHitTest {
    @Test fun straightAt_2_5_hits() =
        assertTrue(FairHit.hits(v(0.0, 1.62, 0.0), Rotation(0f, 0f), box(0.0, 0.0, 2.5), 3.0))
    @Test fun at_3_6_misses() =
        assertFalse(FairHit.hits(v(0.0, 1.62, 0.0), Rotation(0f, 0f), box(0.0, 0.0, 3.6), 3.0))
    @Test fun lookingAway30_misses() =
        assertFalse(FairHit.hits(v(0.0, 1.62, 0.0), Rotation(30f, 0f), box(0.0, 0.0, 2.5), 3.0))
    @Test fun eyeInsideBox_hits() =
        assertTrue(FairHit.hits(v(0.0, 1.0, 0.0), Rotation(123f, 40f), box(0.0, 0.0, 0.0), 3.0))
}
