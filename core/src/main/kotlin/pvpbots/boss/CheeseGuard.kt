package pvpbots.boss

/**
 * The boss's anti-cheese trigger (spec 5.4): a leap or ranged move is forced, cooldowns aside, when the target is more
 * than [ABOVE] blocks above the boss, or when the target has not been reachable on foot for [STUCK_TICKS]: out of
 * melee reach and no horizontal progress (getting [PROGRESS] blocks closer than the best so far) and no move started.
 * Standing or strafing within reach is never "stuck", so normal melee play keeps every move's cooldown.
 */
class CheeseGuard {
    enum class Reason { TARGET_ABOVE, STUCK }

    private var progressTick = 0L
    private var best = Double.MAX_VALUE

    /** A move started, the target changed, or the boss was put back: the stuck timer starts again at [tick]. */
    fun reset(tick: Long) {
        progressTick = tick
        best = Double.MAX_VALUE
    }

    /** Called each chase tick with the horizontal distance and height difference to the target; non-null forces a guard move. */
    fun check(tick: Long, horizontal: Double, above: Double): Reason? {
        if (above > ABOVE) return Reason.TARGET_ABOVE
        if (horizontal <= REACH || horizontal < best - PROGRESS) {
            best = minOf(best, horizontal)
            progressTick = tick
            if (horizontal <= REACH) best = horizontal
        }
        return if (tick - progressTick >= STUCK_TICKS) Reason.STUCK else null
    }

    companion object {
        const val ABOVE = 4.0
        const val STUCK_TICKS = 60L
        const val PROGRESS = 0.5

        /** Within this horizontal distance the target counts as reached (melee range plus strafing slack). */
        const val REACH = 3.5
    }
}
