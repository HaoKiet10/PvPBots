package pvpbots.boss

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LootRollerTest {
    private val gold = LootEntry("GOLDEN_APPLE", 2, 1.0)

    @Test fun guaranteedAlwaysPresent() {
        val t = LootTable(listOf(gold), 0, emptyList())
        repeat(20) { assertEquals(listOf(gold), LootRoller.roll(t, Random(it))) }
    }

    @Test fun chanceOneAlwaysChanceZeroNever() {
        val always = LootEntry("DIAMOND", 3, 1.0)
        val never = LootEntry("DIRT", 1, 0.0)
        repeat(30) {
            assertEquals(listOf(always, always), LootRoller.roll(LootTable(emptyList(), 2, listOf(always)), Random(it)))
            assertEquals(emptyList(), LootRoller.roll(LootTable(emptyList(), 5, listOf(never)), Random(it)))
        }
    }

    @Test fun rollsDrawFromTable() {
        val a = LootEntry("A", 1, 1.0)
        val b = LootEntry("B", 1, 1.0)
        val out = LootRoller.roll(LootTable(emptyList(), 50, listOf(a, b)), Random(3))
        assertEquals(50, out.size)
        assertTrue(out.any { it == a } && out.any { it == b })
    }

    @Test fun emptyTableYieldsOnlyGuaranteed() {
        assertEquals(listOf(gold), LootRoller.roll(LootTable(listOf(gold), 3, emptyList()), Random(1)))
    }
}
