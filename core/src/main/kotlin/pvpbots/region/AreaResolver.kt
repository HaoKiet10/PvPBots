package pvpbots.region

import pvpbots.profile.Difficulty
import pvpbots.session.AreaRef

object AreaResolver {
    private const val DEFAULT_COUNT = 5

    /**
     * Picks the one area among [applicable] regions: the highest-priority region that has a role
     * (ties: lexicographically smallest id). Difficulty defaults to MEDIUM, count to 5, clamped to 1..[maxBots].
     */
    fun resolve(applicable: List<RegionInfo>, maxBots: Int): AreaRef? {
        val r = applicable.filter { it.role != null }
            .sortedWith(compareByDescending<RegionInfo> { it.priority }.thenBy { it.id })
            .firstOrNull() ?: return null
        val count = (r.count ?: DEFAULT_COUNT).coerceIn(1, maxOf(1, maxBots))
        return AreaRef(r.world, r.id, r.role!!, r.difficulty ?: Difficulty.MEDIUM, count, r.bounds)
    }
}
