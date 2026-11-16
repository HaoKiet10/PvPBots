package pvpbots.math

import kotlin.test.Test
import kotlin.test.assertEquals

class InputMapperTest {
    @Test fun ahead_isForward() {
        val m = InputMapper.toward(Rotation(0f, 0f), v(0.0, 0.0, 0.0), v(0.0, 0.0, 5.0))
        assertEquals(1f, m.forward, 1e-3f); assertEquals(0f, m.strafe, 1e-3f)
    }
    @Test fun left_isPlusStrafe() {
        val m = InputMapper.toward(Rotation(0f, 0f), v(0.0, 0.0, 0.0), v(5.0, 0.0, 0.0))
        assertEquals(0f, m.forward, 1e-3f); assertEquals(1f, m.strafe, 1e-3f)
    }
    @Test fun atSamePoint_isZero() {
        val m = InputMapper.toward(Rotation(0f, 0f), v(1.0, 0.0, 1.0), v(1.0, 3.0, 1.0))
        assertEquals(0f, m.forward, 1e-3f); assertEquals(0f, m.strafe, 1e-3f)
    }
}
