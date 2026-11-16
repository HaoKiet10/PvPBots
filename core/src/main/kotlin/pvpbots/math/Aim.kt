package pvpbots.math

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

object Aim {
    fun lookAt(eye: Vec3, target: Vec3): Rotation {
        val d = target - eye
        val yaw = Math.toDegrees(atan2(-d.x, d.z))
        val pitch = -Math.toDegrees(atan2(d.y, sqrt(d.x * d.x + d.z * d.z)))
        return Rotation(wrapYaw(yaw).toFloat(), pitch.toFloat())
    }

    fun turnToward(current: Rotation, desired: Rotation, maxDegPerTick: Double): Rotation {
        val dy = wrapYaw((desired.yaw - current.yaw).toDouble()).coerceIn(-maxDegPerTick, maxDegPerTick)
        val dp = (desired.pitch - current.pitch).toDouble().coerceIn(-maxDegPerTick, maxDegPerTick)
        return Rotation(
            wrapYaw(current.yaw + dy).toFloat(),
            (current.pitch + dp).coerceIn(-90.0, 90.0).toFloat(),
        )
    }

    fun withError(r: Rotation, errorDeg: Double, rng: Random): Rotation {
        if (errorDeg <= 0.0) return r
        val yaw = r.yaw + rng.nextDouble(-errorDeg, errorDeg)
        val pitch = r.pitch + rng.nextDouble(-errorDeg, errorDeg)
        return Rotation(wrapYaw(yaw).toFloat(), pitch.coerceIn(-90.0, 90.0).toFloat())
    }

    fun direction(r: Rotation): Vec3 {
        val y = Math.toRadians(r.yaw.toDouble())
        val p = Math.toRadians(r.pitch.toDouble())
        return Vec3(-sin(y) * cos(p), -sin(p), cos(y) * cos(p))
    }

    /** Wrap to (-180, 180]. */
    internal fun wrapYaw(deg: Double): Double {
        var w = ((deg % 360.0) + 360.0) % 360.0
        if (w > 180.0) w -= 360.0
        return w
    }
}
