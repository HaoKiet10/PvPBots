package pvpbots.skill

import kotlin.math.abs
import kotlin.math.sign

/** One sampling window of a fight: health fractions (0..1) and damage traded since the last window. */
data class Window(val playerHealthFrac: Double, val botHealthFrac: Double, val dealtToBot: Double, val takenByPlayer: Double)

object FightJudge {
    /**
     * The bot's next rating during a fight: steps up when the player is clearly ahead, down when clearly behind,
     * never more than [SkillSettings.maxShiftPerFight] away from the fight's [start] rating.
     */
    fun next(current: Double, start: Double, w: Window, s: SkillSettings): Double {
        val lead = w.playerHealthFrac - w.botHealthFrac - s.targetLead
        val dir = if (abs(lead) > s.deadZone) {
            sign(lead)
        } else {
            val total = w.dealtToBot + w.takenByPlayer
            if (total > 0) {
                val share = w.dealtToBot / total
                when {
                    share > 0.8 -> 1.0
                    share < 0.2 -> -1.0
                    else -> 0.0
                }
            } else 0.0
        }
        return (current + dir * s.step)
            .coerceIn(start - s.maxShiftPerFight, start + s.maxShiftPerFight)
            .coerceIn(0.0, 100.0)
    }
}
