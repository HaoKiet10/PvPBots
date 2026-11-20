package pvpbots.boss

/** The boss's plugin-side health pool, scaled by player count (spec 5.3, 8). Players leaving never lowers it. */
class BossHealth(private val base: Double, private val perExtra: Double) {
    var max: Double = base
        private set
    var current: Double = base
        private set

    val fraction: Double get() = if (max <= 0.0) 0.0 else (current / max).coerceIn(0.0, 1.0)

    private fun maxFor(players: Int) = base * (1.0 + perExtra * (maxOf(players, 1) - 1))

    /** Fresh pool for [players] participants (computed at INTRO). */
    fun start(players: Int) {
        max = maxFor(players)
        current = max
    }

    /** More players than before raise max and keep the fraction; fewer never lowers anything. */
    fun playersNow(n: Int) {
        val newMax = maxFor(n)
        if (newMax <= max) return
        val f = fraction
        max = newMax
        current = max * f
    }

    /** Applies a hit of [raw] damage; returns the amount actually taken (x1.5 recovering, 0 roaring). */
    fun hit(raw: Double, recovering: Boolean, roaring: Boolean): Double {
        if (roaring || raw <= 0.0) return 0.0
        val taken = minOf(raw * if (recovering) 1.5 else 1.0, current)
        current -= taken
        return taken
    }
}
