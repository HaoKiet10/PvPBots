package pvpbots.arena

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class StackDiffTest {
    /** Stands in for an ItemStack: [kind] is everything but the amount ([isSimilar]). */
    private data class S(val kind: String, val n: Int)

    private fun minus(disk: List<S>, stash: List<S>): List<Pair<String, Int>> =
        StackDiff.minusOnce(disk, stash, { a, b -> a.kind == b.kind }, { it.n }).map { it.stack.kind to it.amount }

    @Test fun diskEqualsStash_nothingLeft() =
        assertEquals(emptyList(), minus(listOf(S("sword", 1), S("dirt", 64), S("dirt", 10)), listOf(S("dirt", 10), S("sword", 1), S("dirt", 64))))

    @Test fun diskIsStashPlusExtra_extraLeft() =
        assertEquals(listOf("emerald" to 5), minus(listOf(S("sword", 1), S("emerald", 5)), listOf(S("sword", 1))))

    @Test fun largerStackOnDisk_differenceLeft() =
        assertEquals(listOf("dirt" to 7), minus(listOf(S("dirt", 17)), listOf(S("dirt", 10))))

    @Test fun stashSpreadOverSeveralDiskStacks_partialRemainder() =
        assertEquals(listOf("dirt" to 4), minus(listOf(S("dirt", 6), S("dirt", 8)), listOf(S("dirt", 10))))

    @Test fun oneStashStackCoversSeveralDiskStacks() =
        assertEquals(emptyList(), minus(listOf(S("dirt", 3), S("dirt", 3)), listOf(S("dirt", 6))))

    @Test fun diskMissingStashItem_nothingNegative() =
        assertEquals(emptyList(), minus(listOf(S("dirt", 2)), listOf(S("dirt", 10), S("sword", 1))))

    @Test fun diskMissingStashItem_othersStillLeft() =
        assertEquals(listOf("emerald" to 1), minus(listOf(S("emerald", 1)), listOf(S("sword", 1))))

    @Test fun differentKindsNeverCancel() =
        assertEquals(listOf("named dirt" to 10), minus(listOf(S("named dirt", 10)), listOf(S("dirt", 10))))

    @Test fun emptyStash_everythingLeft() =
        assertEquals(listOf("a" to 1, "b" to 2), minus(listOf(S("a", 1), S("b", 2)), emptyList()))

    @Test fun nonPositiveAmountsIgnored() =
        assertEquals(emptyList(), minus(listOf(S("a", 0)), listOf(S("a", -3))))
}
