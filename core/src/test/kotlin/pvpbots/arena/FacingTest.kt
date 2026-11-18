package pvpbots.arena

import kotlin.test.Test
import kotlin.test.assertEquals

class FacingTest {
    @Test fun fromYaw_cardinals() {
        assertEquals(Facing.SOUTH, Facing.fromYaw(0f))
        assertEquals(Facing.WEST, Facing.fromYaw(90f))
        assertEquals(Facing.NORTH, Facing.fromYaw(180f))
        assertEquals(Facing.NORTH, Facing.fromYaw(-180f))
        assertEquals(Facing.EAST, Facing.fromYaw(-90f))
        assertEquals(Facing.EAST, Facing.fromYaw(270f))
    }

    @Test fun fromYaw_nearest() {
        assertEquals(Facing.SOUTH, Facing.fromYaw(44f))
        assertEquals(Facing.WEST, Facing.fromYaw(46f))
        assertEquals(Facing.NORTH, Facing.fromYaw(-150f))
        assertEquals(Facing.SOUTH, Facing.fromYaw(-30f))
        assertEquals(Facing.SOUTH, Facing.fromYaw(359f))
        assertEquals(Facing.EAST, Facing.fromYaw(-450f + 360f))
    }
}
