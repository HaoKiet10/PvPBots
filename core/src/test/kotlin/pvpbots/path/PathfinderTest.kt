package pvpbots.path

import pvpbots.math.bounds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PathfinderTest {
    @Test fun straightLine() {
        assertEquals(p(5, 1, 0), Pathfinder.find(flat(10), p(0, 1, 0), p(5, 1, 0), all, 2000).last())
    }

    @Test fun aroundWall() {
        val path = Pathfinder.find(wallAtX3(), p(0, 1, 0), p(6, 1, 0), all, 2000)
        assertFalse(path.isEmpty())
        assertEquals(p(6, 1, 0), path.last())
        assertTrue(path.none { it.x == 3 && it.z == 0 })
    }

    @Test fun stepUpOne() {
        assertFalse(Pathfinder.find(stepAtX3(), p(0, 1, 0), p(5, 2, 0), all, 2000).isEmpty())
    }

    @Test fun twoHighWallBlocks() {
        assertTrue(Pathfinder.find(enclosed(), p(0, 1, 0), p(9, 1, 9), all, 2000).isEmpty())
    }

    @Test fun staysInBounds() {
        val b = bounds(0, 0, -3, 10, 5, 3)
        val path = Pathfinder.find(wallAtX3(), p(0, 1, 0), p(6, 1, 0), b, 2000)
        assertFalse(path.isEmpty())
        assertTrue(path.all { b.contains(it) })
        // bounds that leave no gap around the wall -> no path
        val tight = bounds(0, 0, -1, 10, 5, 1)
        assertTrue(Pathfinder.find(wallAtX3(), p(0, 1, 0), p(6, 1, 0), tight, 2000).isEmpty())
    }

    @Test fun maxNodesRespected() {
        assertTrue(Pathfinder.find(flat(500), p(0, 1, 0), p(400, 1, 0), all, 50).isEmpty())
    }

    @Test fun dropsUpToThree() {
        val g = FakeGrid().floor(0..2, 0..2, y = 3).floor(3..6, 0..2, y = 0)
        val path = Pathfinder.find(g, p(0, 4, 0), p(5, 1, 0), all, 2000)
        assertEquals(p(5, 1, 0), path.last())
        // a drop of 4 is too much
        val g4 = FakeGrid().floor(0..2, 0..2, y = 4).floor(3..6, 0..2, y = 0)
        assertTrue(Pathfinder.find(g4, p(0, 5, 0), p(5, 1, 0), all, 2000).isEmpty())
    }

    @Test fun noCornerCutting() {
        // Pillars at (1,1..2,0) and (0,1..2,1) block the diagonal (0,0)->(1,1) squeeze.
        val g = FakeGrid().floor(0..1, 0..1)
        g.column(1, 0, 1..2).column(0, 1, 1..2)
        assertTrue(Pathfinder.find(g, p(0, 1, 0), p(1, 1, 1), all, 2000).isEmpty())
    }

    @Test fun unreachableGoalNearbyReturnsClosest() {
        // Goal is on a floating cell behind the wall within 2 blocks of reachable ground.
        val g = flat(10)
        val path = Pathfinder.find(g, p(0, 1, 0), p(5, 1, 9), bounds(0, 0, 0, 9, 5, 8), 2000)
        assertFalse(path.isEmpty())
        assertTrue(path.last().z == 8)
    }
}
