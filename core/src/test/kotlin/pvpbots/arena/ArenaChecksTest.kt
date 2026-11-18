package pvpbots.arena

import pvpbots.math.BlockPos
import pvpbots.math.Bounds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArenaChecksTest {
    private val layout = ArenaLayout.generate(Preset.DUEL, BlockPos(0, 64, 0), Facing.NORTH)
    private val bigBorder = Bounds(BlockPos(-1000, -64, -1000), BlockPos(1000, 320, 1000))

    private fun check(
        minY: Int = -64, maxY: Int = 319, border: Bounds = bigBorder,
        others: List<Bounds> = emptyList(), entities: List<BlockPos> = emptyList(),
    ) = ArenaChecks.check(layout, minY, maxY, border, others, entities)

    @Test fun check_okOnFlatGround() = assertEquals(emptyList(), check())

    @Test fun check_refusesOutsideHeight() {
        val r = check(maxY = 70)
        assertEquals(1, r.size)
        assertTrue("build height" in r[0])
        assertEquals(1, check(minY = 65).size)
    }

    @Test fun check_refusesOutsideBorder() {
        val r = check(border = Bounds(BlockPos(-5, -64, -5), BlockPos(5, 320, 5)))
        assertEquals(1, r.size)
        assertTrue("border" in r[0])
    }

    @Test fun check_refusesOverlap() {
        val other = Bounds(BlockPos(9, 60, 9), BlockPos(30, 80, 30))
        val r = check(others = listOf(other))
        assertEquals(1, r.size)
        assertTrue("overlap" in r[0])
        val apart = Bounds(BlockPos(10, 60, 0), BlockPos(30, 80, 5))
        assertEquals(emptyList(), check(others = listOf(apart)))
    }

    @Test fun check_refusesBlockEntities() {
        val r = check(entities = listOf(BlockPos(1, 65, 2), BlockPos(3, 66, 4)))
        assertEquals(1, r.size)
        assertTrue("(1, 65, 2)" in r[0] && "(3, 66, 4)" in r[0])
    }
}
