package pvpbots.path

import pvpbots.math.Vec3

enum class Verdict { OK, RETRY, TELEPORT }

/**
 * Progress since the last checkpoint = the distance to the goal shrank by >= 1.0, OR the bot itself moved >= 1.0
 * (so a bot chasing a goal that recedes as fast as it runs, e.g. a fleeing owner, is never "stuck").
 */
class StuckDetector(private val retryTicks: Int, private val teleportTicks: Int) {
    private var checkTick = 0L
    private var checkDist = 0.0
    private var checkPos = Vec3(0.0, 0.0, 0.0)
    private var started = false

    fun update(tick: Long, pos: Vec3, goal: Vec3): Verdict {
        val dist = (goal - pos).length()
        if (!started || checkDist - dist >= PROGRESS || (pos - checkPos).length() >= PROGRESS) {
            started = true; checkTick = tick; checkDist = dist; checkPos = pos
            return Verdict.OK
        }
        val elapsed = tick - checkTick
        return when {
            elapsed >= teleportTicks -> Verdict.TELEPORT
            elapsed >= retryTicks -> Verdict.RETRY
            else -> Verdict.OK
        }
    }

    fun reset() {
        started = false
    }

    private companion object {
        const val PROGRESS = 1.0
    }
}
