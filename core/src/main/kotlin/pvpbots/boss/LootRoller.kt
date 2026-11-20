package pvpbots.boss

import kotlin.random.Random

object LootRoller {
    /** Guaranteed entries, then [LootTable.rolls] draws: each picks a table entry uniformly and keeps it with its chance. */
    fun roll(t: LootTable, rng: Random): List<LootEntry> {
        val out = ArrayList(t.guaranteed)
        if (t.table.isEmpty()) return out
        repeat(t.rolls) {
            val e = t.table[rng.nextInt(t.table.size)]
            if (rng.nextDouble() < e.chance) out += e
        }
        return out
    }
}
