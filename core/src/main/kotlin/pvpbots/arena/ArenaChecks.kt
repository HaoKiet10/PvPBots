package pvpbots.arena

import pvpbots.math.BlockPos
import pvpbots.math.Bounds

object ArenaChecks {
    /** Empty list means the arena may be built; each entry is an admin-facing reason it may not. */
    fun check(
        layout: Layout,
        minY: Int,
        maxY: Int,
        border: Bounds,
        others: List<Bounds>,
        blockEntities: List<BlockPos>,
    ): List<String> {
        val outer = layout.outer
        val reasons = mutableListOf<String>()
        if (outer.min.y < minY || outer.max.y > maxY) {
            reasons += "Arena needs y ${outer.min.y}..${outer.max.y} but the build height is $minY..$maxY."
        }
        if (!border.contains(outer.min) || !border.contains(outer.max)) {
            reasons += "Arena would extend outside the world border."
        }
        if (others.any { intersects(outer, it) }) {
            reasons += "Arena would overlap another arena."
        }
        if (blockEntities.isNotEmpty()) {
            val list = blockEntities.joinToString(", ") { "(${it.x}, ${it.y}, ${it.z})" }
            reasons += "Arena area contains blocks with stored data (chests, signs, etc.): $list."
        }
        return reasons
    }

    private fun intersects(a: Bounds, b: Bounds) =
        a.min.x <= b.max.x && b.min.x <= a.max.x &&
            a.min.y <= b.max.y && b.min.y <= a.max.y &&
            a.min.z <= b.max.z && b.min.z <= a.max.z
}
