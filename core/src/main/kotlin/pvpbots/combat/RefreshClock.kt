package pvpbots.combat

import kotlin.random.Random

/**
 * When a bot re-reads its target (every [periodTicks] ticks). The first [mark] backdates itself by a random 0 until
 * [periodTicks] ticks, so bots spawned in the same tick do not all refresh in the same ticks (staggered load).
 * Pure; time is passed in.
 */
class RefreshClock(periodTicks: Int, private val rng: Random) {
    private var period = periodTicks.coerceAtLeast(1)
    private var last = NEVER
    private var marked = false

    /** Changes the period; the next [due] check already uses it (the last refresh time is kept). */
    fun setPeriod(ticks: Int) {
        period = ticks.coerceAtLeast(1)
    }

    fun due(tick: Long): Boolean = tick - last >= period

    fun mark(tick: Long) {
        last = if (marked) tick else tick - rng.nextInt(period)
        marked = true
    }

    private companion object {
        /** "Long ago"; not Long.MIN_VALUE, since `tick - MIN_VALUE` overflows. */
        const val NEVER = -1_000_000_000L
    }
}
