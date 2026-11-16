package pvpbots.path

import pvpbots.math.BlockPos
import pvpbots.math.Bounds
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

object SpawnPointFinder {
    private const val SAMPLES = 32

    /**
     * Up to 32 random samples on a ring 6-10 blocks (horizontal) from [near]; for each, the highest standable
     * feet position within +-3 Y of [near] that lies inside [b]. Fallback: highest standable block in the
     * column at the centre of [b]. Null if nothing is standable.
     */
    fun find(g: BlockGrid, near: BlockPos, b: Bounds, rng: Random): BlockPos? {
        repeat(SAMPLES) {
            val angle = rng.nextDouble(0.0, 2 * PI)
            val r = rng.nextDouble(6.0, 10.0)
            val x = near.x + (cos(angle) * r).roundToInt()
            val z = near.z + (sin(angle) * r).roundToInt()
            val d = hypot((x - near.x).toDouble(), (z - near.z).toDouble())
            if (d < 6.0 || d > 10.0) return@repeat
            for (y in near.y + 3 downTo near.y - 3) {
                val p = BlockPos(x, y, z)
                if (b.contains(p) && g.standable(x, y, z)) return p
            }
        }
        val cx = (b.min.x + b.max.x) / 2
        val cz = (b.min.z + b.max.z) / 2
        for (y in b.max.y downTo b.min.y) {
            if (g.standable(cx, y, cz)) return BlockPos(cx, y, cz)
        }
        return null
    }
}
