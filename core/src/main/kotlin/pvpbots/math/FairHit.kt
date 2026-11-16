package pvpbots.math

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object FairHit {
    /** Slab ray/AABB test along [look] from [eye], within [reach]. Eye inside the box counts as a hit. */
    fun hits(eye: Vec3, look: Rotation, box: Aabb, reach: Double): Boolean {
        val d = Aim.direction(look)
        var tMin = 0.0
        var tMax = reach
        val o = doubleArrayOf(eye.x, eye.y, eye.z)
        val dir = doubleArrayOf(d.x, d.y, d.z)
        val lo = doubleArrayOf(box.min.x, box.min.y, box.min.z)
        val hi = doubleArrayOf(box.max.x, box.max.y, box.max.z)
        for (i in 0..2) {
            if (abs(dir[i]) < 1e-12) {
                if (o[i] < lo[i] || o[i] > hi[i]) return false
            } else {
                val t1 = (lo[i] - o[i]) / dir[i]
                val t2 = (hi[i] - o[i]) / dir[i]
                tMin = max(tMin, min(t1, t2))
                tMax = min(tMax, max(t1, t2))
                if (tMin > tMax) return false
            }
        }
        return true
    }
}
