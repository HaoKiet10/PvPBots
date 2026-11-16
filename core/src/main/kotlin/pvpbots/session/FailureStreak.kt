package pvpbots.session

/**
 * Counts consecutive bot failures (a spawn that failed, or a bot dropped because its tick threw) per session.
 * A failure more than [windowMs] after the previous one starts a new streak; [reset] is called on a normal outcome
 * (a bot died in combat, the owner died, a round ended). Pure; time is passed in.
 */
class FailureStreak(private val windowMs: Long) {
    private class Streak(var count: Int, var lastMs: Long)

    private val streaks = HashMap<SessionKey, Streak>()

    /** Records a failure of [key] at [nowMs] and returns the current streak length (1 for a fresh streak). */
    fun record(key: SessionKey, nowMs: Long): Int {
        val s = streaks[key]
        if (s == null || nowMs - s.lastMs > windowMs) {
            streaks[key] = Streak(1, nowMs)
            return 1
        }
        s.count++
        s.lastMs = nowMs
        return s.count
    }

    fun reset(key: SessionKey) {
        streaks.remove(key)
    }

    fun clear() = streaks.clear()
}
