package pvpbots.math

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AimTest {
    @Test fun plusZ_isYaw0() = assertEquals(0f, Aim.lookAt(v(0.0, 0.0, 0.0), v(0.0, 0.0, 5.0)).yaw, 1e-3f)
    @Test fun minusX_isYaw90() = assertEquals(90f, Aim.lookAt(v(0.0, 0.0, 0.0), v(-5.0, 0.0, 0.0)).yaw, 1e-3f)
    @Test fun above_isNegativePitch() = assertTrue(Aim.lookAt(v(0.0, 0.0, 0.0), v(0.0, 5.0, 5.0)).pitch < 0)
    @Test fun turnLimited() =
        assertEquals(15f, Aim.turnToward(Rotation(0f, 0f), Rotation(90f, 0f), 15.0).yaw, 1e-3f)
    @Test fun turnShortestWay() =
        assertEquals(-175f, Aim.turnToward(Rotation(175f, 0f), Rotation(-170f, 0f), 10.0).yaw, 1e-3f)
    @Test fun errorBounded() {
        val rng = Random(1)
        repeat(1000) {
            val r = Aim.withError(Rotation(0f, 0f), 3.0, rng)
            assertTrue(Math.abs(r.yaw) <= 3f && Math.abs(r.pitch) <= 3f)
        }
    }
    @Test fun errorClampsPitch() {
        val rng = Random(2)
        repeat(200) { assertTrue(Math.abs(Aim.withError(Rotation(0f, 89f), 5.0, rng).pitch) <= 90f) }
    }
    @Test fun directionMatchesLookAt() {
        val d = Aim.direction(Aim.lookAt(v(0.0, 0.0, 0.0), v(-3.0, 2.0, 4.0))).normalize()
        val e = v(-3.0, 2.0, 4.0).normalize()
        assertEquals(e.x, d.x, 1e-4); assertEquals(e.y, d.y, 1e-4); assertEquals(e.z, d.z, 1e-4)
    }
}
