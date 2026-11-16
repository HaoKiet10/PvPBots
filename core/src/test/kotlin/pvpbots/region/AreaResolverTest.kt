package pvpbots.region

import pvpbots.math.BlockPos
import pvpbots.math.Bounds
import pvpbots.profile.Difficulty
import pvpbots.session.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AreaResolverTest {
    private val b = Bounds(BlockPos(0, 0, 0), BlockPos(10, 10, 10))

    private fun region(id: String, prio: Int, role: Role?, diff: Difficulty? = null, count: Int? = null) =
        RegionInfo("world", id, prio, role, diff, count, b)

    private fun resolveCount(count: Int?) =
        AreaResolver.resolve(listOf(region("a", 0, Role.ALLY, count = count)), 100)!!.count

    @Test fun overlap_highestPriorityFlaggedRegionWins() {
        val r = AreaResolver.resolve(
            listOf(region("boss", 5, Role.ALLY), region("arena", 10, Role.TRAINER), region("plain", 20, null)), 100,
        )
        assertEquals("arena", r!!.regionId)
        assertEquals(Role.TRAINER, r.role)
    }

    @Test fun priorityTie_smallestIdWins() {
        val r = AreaResolver.resolve(listOf(region("b", 5, Role.ALLY), region("a", 5, Role.TRAINER)), 100)
        assertEquals("a", r!!.regionId)
    }

    @Test fun noFlaggedRegion_isNull() {
        assertNull(AreaResolver.resolve(listOf(region("plain", 1, null)), 100))
        assertNull(AreaResolver.resolve(emptyList(), 100))
    }

    @Test fun count_clampedToOneAndMaxBots() {
        assertEquals(1, resolveCount(0))
        assertEquals(100, resolveCount(99_999))
        assertEquals(7, resolveCount(7))
    }

    @Test fun missingCount_defaultsToFive() {
        assertEquals(5, resolveCount(null))
        assertEquals(3, AreaResolver.resolve(listOf(region("a", 0, Role.ALLY)), 3)!!.count)
    }

    @Test fun missingDifficulty_defaultsToMedium() {
        assertEquals(Difficulty.MEDIUM, AreaResolver.resolve(listOf(region("a", 0, Role.TRAINER)), 100)!!.difficulty)
        assertEquals(Difficulty.HARD, AreaResolver.resolve(listOf(region("a", 0, Role.TRAINER, Difficulty.HARD)), 100)!!.difficulty)
    }
}
