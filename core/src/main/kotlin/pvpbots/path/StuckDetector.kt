package pvpbots.path

import pvpbots.math.Vec3

enum class Verdict { OK, RETRY, TELEPORT }

/** Progress = distance to goal shrank by >= 1.0 since the last checkpoint. */
class StuckDetector(private val retryTicks: Int, private val teleportTicks: Int) {
    private var checkTick = 0L
    private var checkDist = 0.0
    private var started = false

    fun update(tick: Long, pos: Vec3, goal: Vec3): Verdict {
        val dist = (goal - pos).length()
        if (!started) {
            started = true; checkTick = tick; checkDist = dist
            return Verdict.OK
        }
        if (checkDist - dist >= 1.0) {
            checkTick = tick; checkDist = dist
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
}
