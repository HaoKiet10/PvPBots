package pvpbots.bukkit

/**
 * Optional per-phase timing of [BotController.tick], switched on only by the `load` self-test. When [enabled] is
 * false every hook is a single field read. Main thread only.
 */
object BotProfiler {
    /** Tick phases. SCAN (hostile scan) and REACHABLE (ally on-foot A*) are nested inside REFRESH. */
    enum class Phase { REFRESH, SCAN, REACHABLE, PERCEIVE, DECIDE, PATH, STEER, ACT }

    /** [BotManager.spawn] parts. */
    enum class SpawnPart { ADAPTER, KIT, DISTANCES }

    enum class Counter { ASTAR_REACHABLE, ASTAR_WAYPOINT, ENEMY_SCAN }

    @JvmField var enabled = false

    val nanos = LongArray(Phase.entries.size)
    val counts = LongArray(Counter.entries.size)
    val spawnNanos = LongArray(SpawnPart.entries.size)

    fun reset() {
        nanos.fill(0L)
        counts.fill(0L)
        spawnNanos.fill(0L)
    }

    inline fun <T> spawnPart(part: SpawnPart, block: () -> T): T {
        if (!enabled) return block()
        val t0 = System.nanoTime()
        try {
            return block()
        } finally {
            spawnNanos[part.ordinal] += System.nanoTime() - t0
        }
    }

    fun count(c: Counter) {
        if (enabled) counts[c.ordinal]++
    }

    inline fun <T> timed(phase: Phase, block: () -> T): T {
        if (!enabled) return block()
        val t0 = System.nanoTime()
        try {
            return block()
        } finally {
            nanos[phase.ordinal] += System.nanoTime() - t0
        }
    }
}
