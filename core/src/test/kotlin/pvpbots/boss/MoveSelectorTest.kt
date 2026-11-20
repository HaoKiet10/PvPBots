package pvpbots.boss

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MoveSelectorTest {
    private fun mv(id: String, weight: Int = 1, range: ClosedFloatingPointRange<Double> = 0.0..10.0) =
        Move(id, MoveType.COMBO, weight, range, 10, 5.0, 0, 0, emptyMap())

    @Test fun respectsRange() {
        val moves = listOf(mv("near", range = 0.0..3.0), mv("far", range = 8.0..20.0))
        repeat(50) { assertEquals("near", MoveSelector.pick(moves, 2.0, emptyMap(), Random(it))!!.id) }
        repeat(50) { assertEquals("far", MoveSelector.pick(moves, 12.0, emptyMap(), Random(it))!!.id) }
        assertNull(MoveSelector.pick(moves, 5.0, emptyMap(), Random(1)))
    }

    @Test fun skipsMovesOnCooldown() {
        val moves = listOf(mv("a"), mv("b"))
        repeat(50) { assertEquals("b", MoveSelector.pick(moves, 2.0, mapOf("a" to 20), Random(it))!!.id) }
        assertNull(MoveSelector.pick(moves, 2.0, mapOf("a" to 1, "b" to 5), Random(1)))
        assertTrue(MoveSelector.pick(moves, 2.0, mapOf("a" to 0), Random(1)) != null)
    }

    @Test fun weightZeroNeverPicked() {
        val moves = listOf(mv("zero", weight = 0), mv("one", weight = 1))
        repeat(100) { assertEquals("one", MoveSelector.pick(moves, 2.0, emptyMap(), Random(it))!!.id) }
        assertNull(MoveSelector.pick(listOf(mv("zero", weight = 0)), 2.0, emptyMap(), Random(1)))
    }

    @Test fun deterministicWithSeed() {
        val moves = listOf(mv("a", 3), mv("b", 1), mv("c", 2))
        val r1 = Random(99)
        val r2 = Random(99)
        assertEquals(
            (0 until 20).map { MoveSelector.pick(moves, 2.0, emptyMap(), r1)!!.id },
            (0 until 20).map { MoveSelector.pick(moves, 2.0, emptyMap(), r2)!!.id },
        )
    }

    @Test fun weightsBiasThePick() {
        val moves = listOf(mv("heavy", 9), mv("light", 1))
        val r = Random(5)
        val heavy = (0 until 1000).count { MoveSelector.pick(moves, 2.0, emptyMap(), r)!!.id == "heavy" }
        assertTrue(heavy in 800..980, "heavy=$heavy")
    }
}
