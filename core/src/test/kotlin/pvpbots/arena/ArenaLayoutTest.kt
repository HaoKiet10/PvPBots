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
        for (p in Preset.TEAM_PRESETS) {
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
        for (p in Preset.TEAM_PRESETS) {
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

    @Test fun outsideEntrance_isOutsideTheFootprintInFrontOfTheGap() {
        for (p in Preset.TEAM_PRESETS) for (f in Facing.entries) {
            val l = gen(p, f)
            val o = ArenaLayout.outsideEntrance(p, origin, f)
            assertTrue(o.x !in l.outer.min.x..l.outer.max.x || o.z !in l.outer.min.z..l.outer.max.z, "$p $f: $o inside ${l.outer}")
            // Two blocks beyond the entrance gap (wall-height AIR at the ring), on its centre line.
            val gap = l.blocks.filter { it.value == Part.AIR && it.key.y == 65 }.keys
                .filter { maxOf(abs(it.x - origin.x), abs(it.z - origin.z)) == p.inside / 2 + 1 }
            assertEquals(3, gap.size, "$p $f")
            val mid = gap.first { g -> gap.count { maxOf(abs(it.x - g.x), abs(it.z - g.z)) == 1 } == 2 }
            assertEquals(2, abs(o.x - mid.x) + abs(o.z - mid.z), "$p $f: $o vs gap centre $mid")
        }
        assertEquals(BlockPos(0, 64, 11), ArenaLayout.outsideEntrance(Preset.DUEL, origin, Facing.NORTH))
    }

    // ---- boss arena ----

    private fun bossLocal(l: Layout) = l.blocks.mapKeys { (k, _) -> Triple(k.x - origin.x, k.y - origin.y, k.z - origin.z) }

    @Test fun boss_isRoundRadius20() {
        val l = gen(Preset.BOSS) // facing NORTH: the admin's side is +z
        val local = bossLocal(l)
        val floor = local.filter { it.key.second == 0 }
        // Floor is the disc of radius 21 (wall ring included) and nothing beyond; the radius-20 disc is fully floored.
        assertTrue(floor.keys.all { (x, _, z) -> x * x + z * z <= 21 * 21 })
        for (x in -21..21) for (z in -21..21) {
            val d = x * x + z * z
            if (d <= 21 * 21) assertTrue(Triple(x, 0, z) in floor, "floor missing at $x,$z")
        }
        assertTrue(local.keys.all { (x, _, z) -> x * x + z * z <= 21 * 21 })
        for ((k, part) in local) {
            val (x, y, z) = k
            val d = x * x + z * z
            if (y == 0) continue
            if (d <= 400) {
                assertEquals(Part.AIR, part, "interior $k")
                assertTrue(y in 1..10)
            } else {
                assertTrue(y in 1..6, "wall height $k")
                val entrance = z > 0 && abs(x) <= 1 // NORTH: the admin side is +z
                assertEquals(if (entrance) Part.AIR else Part.WALL, part, "ring $k")
            }
        }
        for (x in -20..20) for (z in -20..20) if (x * x + z * z <= 400) for (y in 1..10) assertEquals(Part.AIR, local[Triple(x, y, z)])
        // Walls reach y+6 exactly; the interior is cleared to y+10.
        assertEquals(6, local.filter { it.value == Part.WALL }.keys.maxOf { it.second })
        assertEquals(10, local.keys.maxOf { it.second })
        assertEquals(10, l.outer.max.y - origin.y)
        assertTrue(l.blocks.keys.all { l.outer.contains(it) })
    }

    @Test fun boss_ringIsClosedExceptTheEntrance() {
        val local = bossLocal(gen(Preset.BOSS))
        // Every 4-neighbour of an interior cell is interior or ring: the wall cannot leak.
        for (x in -20..20) for (z in -20..20) if (x * x + z * z <= 400) {
            for ((dx, dz) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                assertTrue(Triple(x + dx, 1, z + dz) in local, "leak next to $x,$z")
            }
        }
        val gap = local.filter { it.key.second == 1 && it.value == Part.AIR && it.key.first.let { _ -> true } }
            .keys.filter { (x, _, z) -> x * x + z * z > 400 }
        assertEquals(3, gap.size)
        assertTrue(gap.all { (x, _, z) -> abs(x) <= 1 && z > 0 })
        for (y in 1..6) assertEquals(3, local.count { (k, v) -> k.second == y && v == Part.AIR && k.first * k.first + k.third * k.third > 400 })
    }

    @Test fun boss_noCover_padsAndLights() {
        val l = gen(Preset.BOSS)
        assertTrue(l.blocks.values.none { it == Part.COVER })
        assertEquals(listOf(BlockPos(0, 64, 18)), l.padsA) // one entry spot at the entrance, admin side (+z facing NORTH)
        assertEquals(listOf(origin), l.padsB) // the centre
        assertEquals(Part.PAD_A, l.blocks[l.padsA.single()])
        assertEquals(Part.PAD_B, l.blocks[origin])
        val lights = l.blocks.filter { it.value == Part.LIGHT }.keys
        assertTrue(lights.isNotEmpty() && lights.all { it.y == 64 && it.x * it.x + it.z * it.z <= 400 })
    }

    @Test fun boss_entranceFollowsFacing() {
        for (f in Facing.entries) {
            val l = gen(Preset.BOSS, f)
            val o = ArenaLayout.outsideEntrance(Preset.BOSS, origin, f)
            val pad = l.padsA.single()
            // The outside spot, the entry pad and the centre are on one line; the spot is outside the footprint.
            assertTrue(o.x !in l.outer.min.x..l.outer.max.x || o.z !in l.outer.min.z..l.outer.max.z, "$f: $o")
            assertTrue((pad.x == 0 && o.x == 0) || (pad.z == 0 && o.z == 0), "$f: $pad $o")
            assertEquals(Part.AIR, l.blocks[BlockPos(pad.x, 65, pad.z)])
        }
    }

    @Test fun boss_record_roundTrips() {
        val l = gen(Preset.BOSS)
        val r = ArenaRecord(
            name = "b", world = "w", origin = origin, facing = Facing.NORTH, preset = Preset.BOSS, regionId = "pvpbots_b",
            status = ArenaRecord.Status.READY, kit = null, padsA = l.padsA, padsB = l.padsB, bossType = "ashblade",
        )
        assertEquals("ashblade", r.copy(status = ArenaRecord.Status.REMOVING).bossType)
        assertEquals(r, r.copy())
        assertEquals(null, r.copy(bossType = null).bossType)
        assertTrue(Preset.BOSS !in Preset.TEAM_PRESETS)
    }
}
