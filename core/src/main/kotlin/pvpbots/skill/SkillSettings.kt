package pvpbots.skill

import pvpbots.config.ConfigReader

/** Tunables of the hidden per-player skill rating (0..100). */
data class SkillSettings(
    val startRating: Double = 50.0,
    val adjustEverySeconds: Int = 5,
    val step: Double = 3.0,
    val deadZone: Double = 0.15,
    val targetLead: Double = 0.05,
    val maxShiftPerFight: Double = 15.0,
    val learnRate: Double = 0.3,
    val winBonus: Double = 4.0,
    val lossPenalty: Double = 2.0,
) {
    companion object {
        internal fun load(r: ConfigReader): SkillSettings {
            val d = SkillSettings()
            fun ranged(name: String, def: Double, lo: Double, hi: Double): Double {
                val v = r.double(name) ?: return def
                if (v < lo || v > hi) {
                    r.warn(name, "must be between $lo and $hi, using default $def")
                    return def
                }
                return v
            }
            return SkillSettings(
                startRating = ranged("start-rating", d.startRating, 0.0, 100.0),
                adjustEverySeconds = r.int("adjust-every-seconds", d.adjustEverySeconds, min = 1),
                step = ranged("step", d.step, 0.0, 100.0),
                deadZone = ranged("dead-zone", d.deadZone, 0.0, 1.0),
                targetLead = ranged("target-lead", d.targetLead, 0.0, 1.0),
                maxShiftPerFight = ranged("max-shift-per-fight", d.maxShiftPerFight, 0.0, 100.0),
                learnRate = ranged("learn-rate", d.learnRate, 0.0, 1.0),
                winBonus = ranged("win-bonus", d.winBonus, 0.0, 100.0),
                lossPenalty = ranged("loss-penalty", d.lossPenalty, 0.0, 100.0),
            )
        }
    }
}
