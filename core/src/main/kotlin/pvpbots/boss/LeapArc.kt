package pvpbots.boss

import pvpbots.math.Vec3
import kotlin.math.pow

/**
 * Start velocity for a boss leap (spec 5.4 leap-slam). The boss is a fake player moved by server-side player physics
 * with no input: the first tick moves with ground friction (0.6 x 0.91) applied after it, then air drag 0.91 a tick;
 * vertically each tick moves, then speed = (speed - 0.08) x 0.98. Height after n ticks is linear in the start speed, so
 * it is solved exactly.
 */
object LeapArc {
    const val GRAVITY = 0.08
    const val VERTICAL_DRAG = 0.98
    const val AIR_DRAG = 0.91
    const val GROUND_FRICTION = 0.6 * 0.91

    /** Velocity that carries the boss by ([dx], [dy], [dz]) in [ticks] ticks (>= 2). */
    fun startVelocity(dx: Double, dy: Double, dz: Double, ticks: Int): Vec3 {
        require(ticks >= 2) { "ticks must be >= 2" }
        val reach = 1.0 + GROUND_FRICTION * (1.0 - AIR_DRAG.pow(ticks - 1)) / (1.0 - AIR_DRAG)
        val b = height(0.0, ticks)
        val a = height(1.0, ticks) - b
        return Vec3(dx / reach, (dy - b) / a, dz / reach)
    }

    /** Position after [ticks] ticks of the physics above, starting at 0 with velocity [v]. */
    fun simulate(v: Vec3, ticks: Int): Vec3 {
        var x = 0.0; var y = 0.0; var z = 0.0
        var vx = v.x; var vy = v.y; var vz = v.z
        repeat(ticks) { i ->
            x += vx; y += vy; z += vz
            val f = if (i == 0) GROUND_FRICTION else AIR_DRAG
            vx *= f; vz *= f
            vy = (vy - GRAVITY) * VERTICAL_DRAG
        }
        return Vec3(x, y, z)
    }

    private fun height(vy0: Double, ticks: Int): Double = simulate(Vec3(0.0, vy0, 0.0), ticks).y
}
