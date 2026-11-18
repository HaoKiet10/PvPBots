package pvpbots.arena

import kotlin.math.floor

/** Direction the admin faces. NORTH = -z, EAST = +x, SOUTH = +z, WEST = -x. */
enum class Facing {
    NORTH, EAST, SOUTH, WEST;

    companion object {
        /** Nearest direction to a Minecraft yaw (0 = south, 90 = west, 180/-180 = north, -90 = east). */
        fun fromYaw(yaw: Float): Facing {
            val quarter = Math.floorMod(floor(yaw / 90.0 + 0.5).toInt(), 4)
            return arrayOf(SOUTH, WEST, NORTH, EAST)[quarter]
        }
    }
}
