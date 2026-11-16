package pvpbots.combat

import pvpbots.profile.DifficultyProfile
import pvpbots.profile.StrafeMode
import kotlin.random.Random

/** Mutable per-bot memory used by the brains. */
class BrainState(profile: DifficultyProfile, rng: Random) {
    var cooldownThreshold: Double = sampleThreshold(profile, rng)
    var strafeLeft: Boolean = rng.nextBoolean()
    var strafeTicksLeft: Int = strafePeriod(profile, rng)
    var wTapTicksLeft: Int = 0

    /** null = not rolled for the current swing. */
    var critRoll: Boolean? = null
    var critJumped: Boolean = false
    var critWaitTicks: Int = 0
    var bowDrawTicks: Int = 0
    var bowWalkTicks: Int = 0

    companion object {
        fun sampleThreshold(p: DifficultyProfile, rng: Random): Double =
            if (p.cooldownMin >= p.cooldownMax) p.cooldownMin
            else p.cooldownMin + rng.nextDouble() * (p.cooldownMax - p.cooldownMin)

        fun strafePeriod(p: DifficultyProfile, rng: Random): Int = when (p.strafe) {
            StrafeMode.SIMPLE -> 40
            StrafeMode.FULL -> 20
            StrafeMode.RANDOMISED -> rng.nextInt(8, 31)
            StrafeMode.NONE -> Int.MAX_VALUE
        }
    }
}
