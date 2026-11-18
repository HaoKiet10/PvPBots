package pvpbots.skill

import pvpbots.profile.Difficulty
import pvpbots.profile.DifficultyProfile
import kotlin.math.roundToInt

/** Maps a skill rating (0..100) onto a [DifficultyProfile] by blending the four difficulty anchors. */
object SkillBlender {
    val ANCHORS: Map<Difficulty, Double> = mapOf(
        Difficulty.EASY to 0.0, Difficulty.MEDIUM to 33.0, Difficulty.HARD to 67.0, Difficulty.PRO to 100.0,
    )

    /** Discrete settings and kit switch to the upper anchor once the rating is within this of it. */
    private const val DISCRETE_WITHIN = 5.0

    fun startProfile(rating: Double, anchors: Map<Difficulty, DifficultyProfile>, kitOverride: String?): DifficultyProfile {
        val r = rating.coerceIn(0.0, 100.0)
        val order = Difficulty.entries
        val hiIdx = order.indexOfFirst { r <= ANCHORS.getValue(it) }.coerceAtLeast(1)
        val loD = order[hiIdx - 1]
        val hiD = order[hiIdx]
        val lo = anchors.getValue(loD)
        val hi = anchors.getValue(hiD)
        val loR = ANCHORS.getValue(loD)
        val hiR = ANCHORS.getValue(hiD)
        val t = ((r - loR) / (hiR - loR)).coerceIn(0.0, 1.0)
        fun lerp(a: Double, b: Double) = a + (b - a) * t
        val d = if (hiR - r <= DISCRETE_WITHIN) hi else lo
        return DifficultyProfile(
            reactionTicks = lerp(lo.reactionTicks.toDouble(), hi.reactionTicks.toDouble()).roundToInt(),
            aimErrorDeg = lerp(lo.aimErrorDeg, hi.aimErrorDeg),
            turnSpeedDeg = lerp(lo.turnSpeedDeg, hi.turnSpeedDeg),
            cooldownMin = lerp(lo.cooldownMin, hi.cooldownMin),
            cooldownMax = lerp(lo.cooldownMax, hi.cooldownMax),
            crits = d.crits,
            wTap = d.wTap,
            strafe = d.strafe,
            shield = d.shield,
            healAt = lerp(lo.healAt, hi.healAt),
            totem = d.totem,
            kit = kitOverride ?: d.kit,
        )
    }

    /** New number settings for [rating]; everything else (discrete settings, kit) stays as in [base]. */
    fun retune(base: DifficultyProfile, rating: Double, anchors: Map<Difficulty, DifficultyProfile>): DifficultyProfile {
        val n = startProfile(rating, anchors, null)
        return base.copy(
            reactionTicks = n.reactionTicks,
            aimErrorDeg = n.aimErrorDeg,
            turnSpeedDeg = n.turnSpeedDeg,
            cooldownMin = n.cooldownMin,
            cooldownMax = n.cooldownMax,
            healAt = n.healAt,
        )
    }

    /** A stored rating that is missing or NaN becomes the start rating; anything else is clamped to 0..100. */
    fun sanitize(stored: Double?, s: SkillSettings): Double =
        if (stored == null || stored.isNaN()) s.startRating else stored.coerceIn(0.0, 100.0)
}
