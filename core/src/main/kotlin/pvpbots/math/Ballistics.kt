package pvpbots.math

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

object Ballistics {
    private const val MAX_TICKS = 200
    private const val TOLERANCE = 0.5
    private const val IMPROVEMENT = 0.1

    /**
     * Pitch (Rotation convention, negative = up) at which an arrow launched at [speed] from [from]
     * passes within 0.5 blocks vertically of [to] at its horizontal distance. Searches launch
     * elevations -80..+80 deg in 0.25 deg steps, visiting the flattest (lowest |elevation|) first, and
     * returns the one with the smallest vertical error; a steeper arc only replaces a flatter one if it
     * is more accurate by more than 0.1 blocks, so a direct shot is preferred over a lob. Null = unreachable.
     * Per tick: pos += vel; vel *= 0.99; vel.y -= 0.05.
     */
    fun pitchFor(from: Vec3, to: Vec3, speed: Double): Double? {
        val dist = from.horizontalDistance(to)
        val dy = to.y - from.y
        var best: Double? = null
        var bestErr = Double.MAX_VALUE
        for (step in 0..320) {
            for (sign in if (step == 0) intArrayOf(1) else intArrayOf(-1, 1)) {
                val elevation = sign * step * 0.25
                val y = heightAt(elevation, dist, speed) ?: continue
                val err = abs(y - dy)
                if (err <= TOLERANCE && err < bestErr - IMPROVEMENT) {
                    best = -elevation
                    bestErr = err
                }
            }
        }
        return best
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
