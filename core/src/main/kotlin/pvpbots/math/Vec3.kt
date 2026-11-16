package pvpbots.math

import kotlin.math.sqrt

data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Double) = Vec3(x * k, y * k, z * k)
    fun length(): Double = sqrt(x * x + y * y + z * z)
    fun horizontalDistance(other: Vec3): Double {
        val dx = x - other.x
        val dz = z - other.z
        return sqrt(dx * dx + dz * dz)
    }
    /** Unit vector; the zero vector stays zero. */
    fun normalize(): Vec3 {
        val l = length()
        return if (l < 1e-12) this else Vec3(x / l, y / l, z / l)
    }
}
