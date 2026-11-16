package pvpbots.path

import pvpbots.math.Vec3
import java.util.UUID

/**
 * Throttles the ally "can I reach the target on foot" A* (an unreachable target makes A* explore the whole area, which
 * is the most expensive thing a bot does). A new check is [due] when the target changed, the bot or the target moved
 * more than [moveTolerance] blocks since the last check, or [recheckTicks] ticks passed. Pure; time is passed in.
 */
class ReachCheck(private val recheckTicks: Int = 20, private val moveTolerance: Double = 2.0) {
    private var lastTick = NEVER
    private var lastTarget: UUID? = null
    private var lastFrom: Vec3? = null
    private var lastGoal: Vec3? = null

    /** Result of the last check (true before the first one). */
    var reachable = true
        private set

    fun due(tick: Long, target: UUID, from: Vec3, goal: Vec3): Boolean {
        val f = lastFrom ?: return true
        val g = lastGoal ?: return true
        return target != lastTarget || tick - lastTick >= recheckTicks ||
            (from - f).length() > moveTolerance || (goal - g).length() > moveTolerance
    }

    fun record(tick: Long, target: UUID, from: Vec3, goal: Vec3, result: Boolean) {
        lastTick = tick
        lastTarget = target
        lastFrom = from
        lastGoal = goal
        reachable = result
    }

    private companion object {
        const val NEVER = -1_000_000_000L
    }
}
