package pvpbots.arena

/**
 * Multiset difference of item stacks (pure, so it is unit-tested without a server): what is left of [minusOnce]'s
 * `disk` stacks after taking away ONE copy of the `stash` stacks. Items are matched with `similar` (an ItemStack's
 * `isSimilar`: everything but the amount) and counted by amount, so a stack only partly covered leaves its remainder.
 */
object StackDiff {
    /** A stack of the disk list and how many of it are left over (always > 0). */
    data class Left<T>(val stack: T, val amount: Int)

    /**
     * Each stash stack is a budget of its amount; disk stacks use up the budgets of similar stash stacks in order.
     * A stash item missing from the disk list costs nothing (never a negative amount); non-positive amounts are ignored.
     */
    fun <T> minusOnce(disk: List<T>, stash: List<T>, similar: (T, T) -> Boolean, amount: (T) -> Int): List<Left<T>> {
        val budget = IntArray(stash.size) { amount(stash[it]).coerceAtLeast(0) }
        val out = ArrayList<Left<T>>()
        for (d in disk) {
            var need = amount(d)
            if (need <= 0) continue
            for (j in stash.indices) {
                if (need == 0) break
                if (budget[j] == 0 || !similar(d, stash[j])) continue
                val take = minOf(need, budget[j])
                need -= take
                budget[j] -= take
            }
            if (need > 0) out += Left(d, need)
        }
        return out
    }
}
