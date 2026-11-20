package pvpbots.boss

object PhaseTracker {
    /** Index of the last phase whose threshold [fraction] has reached (phases ordered by descending [Phase.at]); -1 before the first. */
    fun phaseAt(phases: List<Phase>, fraction: Double): Int = phases.indexOfLast { fraction <= it.at }

    /**
     * The moveset in force at [phaseIndex]: moves named by any phase's add-moves are inactive until that phase,
     * then each phase up to [phaseIndex] adds and removes in order.
     */
    fun activeMoves(moves: List<Move>, phases: List<Phase>, phaseIndex: Int): List<Move> {
        val late = phases.flatMap { it.addMoves }.toSet()
        val active = moves.filter { it.id !in late }.map { it.id }.toMutableSet()
        for (i in 0..minOf(phaseIndex, phases.lastIndex)) {
            active += phases[i].addMoves
            active -= phases[i].removeMoves.toSet()
        }
        return moves.filter { it.id in active }
    }
}
