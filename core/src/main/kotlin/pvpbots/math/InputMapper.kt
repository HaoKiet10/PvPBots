package pvpbots.math

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Unit-length movement input in the look frame; +strafe = left (Minecraft convention). */
data class MoveInput(val forward: Float, val strafe: Float)

object InputMapper {
    fun toward(look: Rotation, from: Vec3, waypoint: Vec3): MoveInput {
        val dx = waypoint.x - from.x
        val dz = waypoint.z - from.z
        val len = sqrt(dx * dx + dz * dz)
        if (len < 1e-6) return MoveInput(0f, 0f)
        val yaw = Math.toRadians(look.yaw.toDouble())
        val forward = (-sin(yaw) * dx + cos(yaw) * dz) / len
        val strafe = (cos(yaw) * dx + sin(yaw) * dz) / len
        return MoveInput(forward.toFloat(), strafe.toFloat())
    }
}
