package pvpbots.combat

import pvpbots.combat.Party.Ally
import pvpbots.combat.Party.RealPlayer
import pvpbots.combat.Party.TeamBot
import pvpbots.combat.Party.Trainer
import pvpbots.team.Side
import java.util.UUID

sealed interface Party {
    data class RealPlayer(val id: UUID) : Party
    data class Trainer(val botId: UUID, val owner: UUID) : Party
    data class Ally(val botId: UUID) : Party

    /** A filler bot of a team round: [arena] names the round (its region id), [side] the team it fights for. */
    data class TeamBot(val botId: UUID, val arena: String, val side: Side) : Party
    data object Mob : Party
    data object Other : Party
}

object DamagePolicy {
    /**
     * [sideOf] gives (arena, side) for any player or bot seated in a running team round; [bossArenaOf] the boss arena
     * an entity fights in (bosses arrive in a later release; callers pass `{ null }` until then).
     */
    @Suppress("UNUSED_PARAMETER")
    fun allow(
        attacker: Party,
        victim: Party,
        sideOf: (UUID) -> Pair<String, Side>?,
        bossArenaOf: (UUID) -> String?,
    ): Boolean {
        // Team rounds: only enemies of the same round hurt each other; nobody else is touched by, or touches, a seated one.
        val a = seat(attacker, sideOf)
        val v = seat(victim, sideOf)
        if (attacker is TeamBot || victim is TeamBot || (a != null && v != null) || (attacker is RealPlayer && victim is RealPlayer && (a != null || v != null))) {
            return a != null && v != null && a.first == v.first && a.second != v.second
        }
        if (attacker is Trainer || victim is Trainer) {
            return (attacker is Trainer && victim is RealPlayer && victim.id == attacker.owner) ||
                (victim is Trainer && attacker is RealPlayer && attacker.id == victim.owner)
        }
        if (attacker is Ally && (victim is RealPlayer || victim is Ally)) return false
        if (attacker is RealPlayer && victim is Ally) return false
        return true
    }

    private fun seat(p: Party, sideOf: (UUID) -> Pair<String, Side>?): Pair<String, Side>? = when (p) {
        is TeamBot -> p.arena to p.side
        is RealPlayer -> sideOf(p.id)
        else -> null
    }
}
