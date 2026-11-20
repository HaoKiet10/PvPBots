package pvpbots.arena

import pvpbots.math.BlockPos
import pvpbots.math.Bounds
import kotlin.math.abs

/** [blocks] holds every position the build changes. Pads are the floor blocks under the spawn points. */
data class Layout(
    val blocks: Map<BlockPos, Part>,
    val outer: Bounds,
    val region: Bounds,
    val padsA: List<BlockPos>,
    val padsB: List<BlockPos>,
)

object ArenaLayout {
    const val WALL_HEIGHT = 5
    const val CLEAR_ABOVE = 3
    private const val COVER_HEIGHT = 3
    private const val PAD_SPACING = 3
    private const val LIGHT_SPACING = 8

    // Local cover columns (x across, z away from the admin), mirrored so both teams get equal cover.
    private val SQUAD_COVER = mirror(6 to 3)
    private val TEAM_COVER = SQUAD_COVER + mirror(11 to 5)

    private fun mirror(c: Pair<Int, Int>) =
        listOf(c.first to c.second, -c.first to c.second, c.first to -c.second, -c.first to -c.second)

    /**
     * A column just outside the footprint in front of the entrance (two blocks beyond the outer ring, on the entrance's
     * centre line), at floor height [origin].y: where players are put before the arena is removed.
     */
    fun outsideEntrance(preset: Preset, origin: BlockPos, facing: Facing): BlockPos {
        val ring = preset.inside / 2 + 1
        return rotate(0, 0, -(ring + 2), origin, facing)
    }

    private fun rotate(lx: Int, dy: Int, lz: Int, origin: BlockPos, facing: Facing): BlockPos {
        var x = lx
        var z = -lz // NORTH: away from the admin is -z
        repeat(facing.ordinal) { val nx = -z; z = x; x = nx } // 90 degrees clockwise seen from above
        return BlockPos(origin.x + x, origin.y + dy, origin.z + z)
    }

    /** Boss arena: walls reach y+6, the interior is cleared to y+10. */
    const val BOSS_WALL_HEIGHT = 6
    const val BOSS_CLEAR_HEIGHT = 10

    fun generate(preset: Preset, origin: BlockPos, facing: Facing): Layout =
        if (preset.round) generateRound(preset, origin, facing) else generateSquare(preset, origin, facing)

    /**
     * A disc of radius `inside / 2` (floor, interior air) inside a one-block wall ring (cells at distance in (r, r+1]).
     * Every 4-neighbour of a disc cell is a disc or ring cell, so the ring is closed. The entrance is the three ring
     * cells behind the admin (local z < 0, |x| <= 1). [Layout.padsA] is one entry spot just inside it, [Layout.padsB]
     * the centre (the boss spawn).
     */
    private fun generateRound(preset: Preset, origin: BlockPos, facing: Facing): Layout {
        val r = preset.inside / 2
        val ring = r + 1
        val local = LinkedHashMap<Triple<Int, Int, Int>, Part>()
        fun d2(x: Int, z: Int) = x * x + z * z
        for (lx in -ring..ring) for (lz in -ring..ring) {
            val d = d2(lx, lz)
            if (d > ring * ring) continue
            local[Triple(lx, 0, lz)] = Part.FLOOR
            if (d <= r * r) {
                for (dy in 1..BOSS_CLEAR_HEIGHT) local[Triple(lx, dy, lz)] = Part.AIR
            } else {
                val entrance = lz < 0 && abs(lx) <= 1
                for (dy in 1..BOSS_WALL_HEIGHT) local[Triple(lx, dy, lz)] = if (entrance) Part.AIR else Part.WALL
            }
        }
        val entryZ = -(r - 2)
        local[Triple(0, 0, entryZ)] = Part.PAD_A
        local[Triple(0, 0, 0)] = Part.PAD_B
        val lightAxis = (-(r / LIGHT_SPACING)..r / LIGHT_SPACING).map { it * LIGHT_SPACING }
        for (lx in lightAxis) for (lz in lightAxis) {
            val k = Triple(lx, 0, lz)
            if (d2(lx, lz) <= r * r && local[k] == Part.FLOOR) local[k] = Part.LIGHT
        }
        val blocks = LinkedHashMap<BlockPos, Part>()
        for ((k, part) in local) blocks[rotate(k.first, k.second, k.third, origin, facing)] = part
        val top = origin.y + BOSS_CLEAR_HEIGHT
        return Layout(
            blocks = blocks,
            outer = Bounds(BlockPos(origin.x - ring, origin.y, origin.z - ring), BlockPos(origin.x + ring, top, origin.z + ring)),
            region = Bounds(BlockPos(origin.x - r, origin.y + 1, origin.z - r), BlockPos(origin.x + r, top, origin.z + r)),
            padsA = listOf(rotate(0, 0, entryZ, origin, facing)),
            padsB = listOf(origin),
        )
    }

    private fun generateSquare(preset: Preset, origin: BlockPos, facing: Facing): Layout {
        val h = preset.inside / 2
        val ring = h + 1
        // Local frame: x across, z away from the admin. A pads at negative z (near side), B at positive z.
        val local = LinkedHashMap<Triple<Int, Int, Int>, Part>() // (lx, dy, lz)

        for (lx in -ring..ring) for (lz in -ring..ring) local[Triple(lx, 0, lz)] = Part.FLOOR

        for (lx in -h..h) for (lz in -h..h) for (dy in 1..WALL_HEIGHT + CLEAR_ABOVE) local[Triple(lx, dy, lz)] = Part.AIR

        for (lx in -ring..ring) for (lz in -ring..ring) {
            if (maxOf(abs(lx), abs(lz)) != ring) continue
            val entrance = lz == -ring && lx in -1..1 // behind the admin
            for (dy in 1..WALL_HEIGHT) local[Triple(lx, dy, lz)] = if (entrance) Part.AIR else Part.WALL
        }

        val cover = when (preset) {
            Preset.DUEL -> emptyList()
            Preset.SQUAD -> SQUAD_COVER
            Preset.TEAM -> TEAM_COVER
            Preset.BOSS -> emptyList()
        }
        for ((cx, cz) in cover) for (dy in 1..COVER_HEIGHT) local[Triple(cx, dy, cz)] = Part.COVER

        val n = preset.padsPerSide
        val padXs = (0 until n).map { (it - (n - 1) / 2) * PAD_SPACING }
        val padZ = h - 2
        for (x in padXs) {
            local[Triple(x, 0, -padZ)] = Part.PAD_A
            local[Triple(x, 0, padZ)] = Part.PAD_B
        }

        // Lights every 8 blocks on both axes, so every floor block is within 4 (Chebyshev) of one.
        val lightAxis = (-(h / LIGHT_SPACING)..h / LIGHT_SPACING).map { it * LIGHT_SPACING }
        for (lx in lightAxis) for (lz in lightAxis) local[Triple(lx, 0, lz)] = Part.LIGHT

        fun pos(lx: Int, dy: Int, lz: Int): BlockPos = rotate(lx, dy, lz, origin, facing)

        val blocks = LinkedHashMap<BlockPos, Part>()
        for ((k, part) in local) blocks[pos(k.first, k.second, k.third)] = part

        val top = origin.y + WALL_HEIGHT + CLEAR_ABOVE
        return Layout(
            blocks = blocks,
            outer = Bounds(BlockPos(origin.x - ring, origin.y, origin.z - ring), BlockPos(origin.x + ring, top, origin.z + ring)),
            region = Bounds(BlockPos(origin.x - h, origin.y + 1, origin.z - h), BlockPos(origin.x + h, top, origin.z + h)),
            padsA = padXs.map { pos(it, 0, -padZ) },
            padsB = padXs.map { pos(it, 0, padZ) },
        )
    }
}
