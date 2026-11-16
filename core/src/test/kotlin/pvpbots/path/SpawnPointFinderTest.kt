package pvpbots.path

import pvpbots.math.bounds
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpawnPointFinderTest {
    @Test fun spawnRing() {
        val s = assertNotNull(SpawnPointFinder.find(flat(40), p(20, 1, 20), all, Random(1)))
        val dx = (s.x - 20).toDouble(); val dy = (s.y - 1).toDouble(); val dz = (s.z - 20).toDouble()
        val d = sqrt(dx * dx + dy * dy + dz * dz)
        assertTrue(d >= 6 && d <= 10, "distance $d")
    }

    @Test fun spawnFallbackCentre() {
        assertEquals(p(2, 1, 2), SpawnPointFinder.find(flat(5), p(2, 1, 2), bounds(0, 0, 0, 4, 5, 4), Random(1)))
    }

    @Test fun noStandableAnywhereIsNull() {
        assertNull(SpawnPointFinder.find(FakeGrid(), p(0, 1, 0), bounds(-5, 0, -5, 5, 5, 5), Random(1)))
    }
}
