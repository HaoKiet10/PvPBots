package pvpbots.boss

import kotlin.random.Random

object MoveSelector {
    /**
     * Weighted pick among moves usable now: weight > 0, [distance] within range, no cooldown left
     * ([cooldowns] maps move id to ticks remaining). Null when nothing is usable.
     */
    fun pick(moves: List<Move>, distance: Double, cooldowns: Map<String, Int>, rng: Random): Move? {
        val usable = moves.filter { it.weight > 0 && distance in it.range && (cooldowns[it.id] ?: 0) <= 0 }
        if (usable.isEmpty()) return null
        var roll = rng.nextInt(usable.sumOf { it.weight })
        for (m in usable) {
            if (roll < m.weight) return m
            roll -= m.weight
        }
        return usable.last()
    }
}
