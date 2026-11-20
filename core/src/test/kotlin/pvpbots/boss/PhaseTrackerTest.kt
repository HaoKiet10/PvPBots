package pvpbots.boss

import kotlin.test.Test
import kotlin.test.assertEquals

class PhaseTrackerTest {
    private fun ph(at: Double, add: List<String> = emptyList(), remove: List<String> = emptyList()) =
        Phase(at, false, 1.0, 1.0, add, remove)

    private fun mv(id: String) = Move(id, MoveType.COMBO, 1, 0.0..10.0, 10, 5.0, 0, 0, emptyMap())

    @Test fun phaseAtFractions() {
        val phases = listOf(ph(0.5), ph(0.25))
        assertEquals(-1, PhaseTracker.phaseAt(phases, 0.6))
        assertEquals(0, PhaseTracker.phaseAt(phases, 0.5))
        assertEquals(0, PhaseTracker.phaseAt(phases, 0.3))
        assertEquals(1, PhaseTracker.phaseAt(phases, 0.2))
        assertEquals(-1, PhaseTracker.phaseAt(emptyList(), 0.1))
    }

    @Test fun activeMovesApplyAddAndRemove() {
        val moves = listOf(mv("a"), mv("b"), mv("late"))
        val phases = listOf(ph(0.5, add = listOf("late"), remove = listOf("a")))
        assertEquals(listOf("a", "b"), PhaseTracker.activeMoves(moves, phases, -1).map { it.id })
        assertEquals(listOf("b", "late"), PhaseTracker.activeMoves(moves, phases, 0).map { it.id })
    }
}
