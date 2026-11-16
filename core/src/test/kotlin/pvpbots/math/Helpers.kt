package pvpbots.math

import kotlin.math.cos
import kotlin.math.sin

fun v(x: Double, y: Double, z: Double) = Vec3(x, y, z)

/** Player box 0.6 x 1.8 with feet-centre at (x, y, z). */
fun box(x: Double, y: Double, z: Double) =
    Aabb(Vec3(x - 0.3, y, z - 0.3), Vec3(x + 0.3, y + 1.8, z + 0.3))

fun bounds(x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int) =
    Bounds(BlockPos(x1, y1, z1), BlockPos(x2, y2, z2))

/**
 * Height gained relative to the launch point when the arrow's horizontal travel reaches [dist].
 * [pitch] uses the Rotation convention (negative = up). Same per-tick rule as Ballistics.
 */
fun simulatedYAt(pitch: Double, dist: Double, speed: Double = 3.0): Double {
    val p = Math.toRadians(pitch)
    var h = 0.0
    var y = 0.0
    var vh = speed * cos(p)
    var vy = -speed * sin(p)
    repeat(200) {
        val nh = h + vh
        val ny = y + vy
        vh *= 0.99
        vy *= 0.99
        vy -= 0.05
        if (nh >= dist) {
            val f = (dist - h) / (nh - h)
            return y + (ny - y) * f
        }
        h = nh
        y = ny
    }
    return Double.NaN
}
