package pvpbots.skill

object RatingUpdate {
    /** The stored rating after a round: pulled toward where the fight ended, plus a bonus/penalty for the result. */
    fun after(old: Double, fightEnd: Double, outcome: Outcome, s: SkillSettings): Double {
        val pulled = old + s.learnRate * (fightEnd - old)
        val r = when (outcome) {
            Outcome.PLAYER_WON -> pulled + s.winBonus
            Outcome.BOT_WON -> pulled - s.lossPenalty
            Outcome.DRAW -> pulled
            Outcome.NO_RESULT -> return old
        }
        return r.coerceIn(0.0, 100.0)
    }
}
