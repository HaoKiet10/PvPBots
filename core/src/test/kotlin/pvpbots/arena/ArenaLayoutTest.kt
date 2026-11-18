package pvpbots.arena

import pvpbots.math.BlockPos
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArenaLayoutTest {
    private val origin = BlockPos(0, 64, 0)
    private fun gen(p: Preset, f: Facing = Facing.NORTH) = ArenaLayout.generate(p, origin, f)

    @Test fun duel_dimensions() {
        val l = gen(Preset.DUEL)
        val floor = l.blocks.filter { it.key.y == 64 }
        assertEquals(19 * 19, floor.size)
        assertTrue(floor.keys.all { abs(it.x) <= 9 && abs(it.z) <= 9 })
        assertEquals(BlockPos(-9, 64, -9), l.outer.min)
        assertEquals(BlockPos(9, 64 + 5 + 3, 9), l.outer.max)
        val walls = l.blocks.filter { it.value == Part.WALL }.keys
        for (y in 65..69) {
            val row = walls.filter { it.y == y }
            assertEquals(4 * 18 - 3, row.size, "y=$y")
            // entrance on the SOUTH side (+z): behind an admin facing NORTH
            for (x in -1..1) {
                assertEquals(Part.AIR, l.blocks[BlockPos(x, y, 9)])
                assertTrue(BlockPos(x, y, 9) !in walls)
            }
            assertEquals(19 - 3, row.count { it.z == 9 })
            assertEquals(19, row.count { it.z == -9 })
            assertEquals(19, row.count { it.x == 9 })
            assertEquals(19, row.count { it.x == -9 })
        }
        assertTrue(walls.all { it.y in 65..69 })
    }

    @Test fun pads_perPreset() {
        for ((p, n) in listOf(Preset.DUEL to 1, Preset.SQUAD to 3, Preset.TEAM to 5)) {
            val l = gen(p)
            val h = p.inside / 2
            assertEquals(n, l.padsA.size)
            assertEquals(n, l.padsB.size)
            val all = l.padsA + l.padsB
            assertEquals(all.size, all.toSet().size)
            assertTrue(all.all { it.y == 64 && abs(it.x) <= h && abs(it.z) <= h })
            // facing NORTH: the admin's side is +z
            assertTrue(l.padsA.all { it.z > 0 })
            assertTrue(l.padsB.all { it.z < 0 })
            l.padsA.forEach { assertEquals(Part.PAD_A, l.blocks[it]) }
            l.padsB.forEach { assertEquals(Part.PAD_B, l.blocks[it]) }
        }
    }

    @Test fun interior_clearedToAir() {
        for (p in Preset.entries) {
            val l = gen(p)
            val h = p.inside / 2
            for (x in -h..h) for (z in -h..h) for (y in 65..72) {
                val part = l.blocks[BlockPos(x, y, z)]
                assertTrue(part == Part.AIR || part == Part.COVER, "$p $x,$y,$z was $part")
            }
        }
    }

    @Test fun cover_onlyForSquadAndTeam() {
        fun columns(l: Layout) = l.blocks.filter { it.value == Part.COVER }.keys.map { it.x to it.z }.toSet()
        assertEquals(0, columns(gen(Preset.DUEL)).size)
        assertTrue(columns(gen(Preset.SQUAD)).size >= 4)
        assertTrue(columns(gen(Preset.TEAM)).size >= 8)
        for (p in listOf(Preset.SQUAD, Preset.TEAM)) {
            val l = gen(p)
            val h = p.inside / 2
            val nearby = (l.padsA + l.padsB).map { it.x to it.z } + (-1..1).map { it to h + 1 }
            for (c in columns(l)) for (n in nearby) {
                assertTrue(maxOf(abs(c.first - n.first), abs(c.second - n.second)) > 2, "$p cover $c near $n")
            }
            assertTrue(l.blocks.filter { it.value == Part.COVER }.keys.all { it.y in 65..67 })
        }
    }

    @Test fun lights_inFloorGrid() {
        for (p in Preset.entries) {
            val l = gen(p)
            val h = p.inside / 2
            val lights = l.blocks.filter { it.value == Part.LIGHT }.keys
            assertTrue(lights.isNotEmpty() && lights.all { it.y == 64 })
            for (x in -h..h) for (z in -h..h) {
                assertTrue(lights.any { maxOf(abs(it.x - x), abs(it.z - z)) <= 4 }, "$p $x,$z")
            }
        }
    }

    @Test fun facing_rotatesLayout() {
        for (p in Preset.entries) {
            var expected = gen(p, Facing.NORTH).blocks
            for (f in listOf(Facing.EAST, Facing.SOUTH, Facing.WEST)) {
                // 90 degrees clockwise seen from above: NORTH(-z) -> EAST(+x), (x,z) -> (-z,x)
                expected = expected.mapKeys { (k, _) -> BlockPos(-k.z, k.y, k.x) }
                assertEquals(expected, gen(p, f).blocks, "$p $f")
            }
        }
        // entrance is behind an admin facing EAST, i.e. on the WEST wall
        assertEquals(Part.AIR, gen(Preset.DUEL, Facing.EAST).blocks[BlockPos(-9, 65, 0)])
    }

    @Test fun region_isInsideWalls() {
        val l = gen(Preset.DUEL)
        assertEquals(BlockPos(-8, 65, -8), l.region.min)
        assertEquals(BlockPos(8, 72, 8), l.region.max)
    }

    @Test fun blocks_stayWithinOuterBounds() {
        val l = gen(Preset.TEAM, Facing.WEST)
        assertTrue(l.blocks.keys.all { l.outer.contains(it) })
    }
}
