package pvpbots.math

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

object Ballistics {
    private const val MAX_TICKS = 200
    private const val TOLERANCE = 0.5

    /**
     * Pitch (Rotation convention, negative = up) at which an arrow launched at [speed] from [from]
     * passes within 0.5 blocks vertically of [to] at its horizontal distance. Searches launch
     * elevations -45..+45 deg in 0.25 deg steps, flattest (lowest elevation) first. Null = unreachable.
     * Per tick: pos += vel; vel *= 0.99; vel.y -= 0.05.
     */
    fun pitchFor(from: Vec3, to: Vec3, speed: Double): Double? {
        val dist = from.horizontalDistance(to)
        val dy = to.y - from.y
        var i = 0
        while (i <= 360) {
            val elevation = -45.0 + i * 0.25
            val y = heightAt(elevation, dist, speed)
            if (y != null && abs(y - dy) <= TOLERANCE) return -elevation
            i++
        }
        return null
    }

    private fun heightAt(elevationDeg: Double, dist: Double, speed: Double): Double? {
        val e = Math.toRadians(elevationDeg)
        var h = 0.0
        var y = 0.0
        var vh = speed * cos(e)
        var vy = speed * sin(e)
        repeat(MAX_TICKS) {
            val nh = h + vh
            val ny = y + vy
            vh *= 0.99
            vy *= 0.99
            vy -= 0.05
            if (nh >= dist) {
                if (nh == h) return y
                return y + (ny - y) * ((dist - h) / (nh - h))
            }
            h = nh
            y = ny
        }
        return null
    }
}
