package pvpbots.combat

import pvpbots.combat.Party.Ally
import pvpbots.combat.Party.Boss
import pvpbots.combat.Party.RealPlayer
import pvpbots.combat.Party.TeamBot
import pvpbots.combat.Party.Trainer
import pvpbots.team.Side
import pvpbots.team.TeamSeat
import java.util.UUID

sealed interface Party {
    data class RealPlayer(val id: UUID) : Party
    data class Trainer(val botId: UUID, val owner: UUID) : Party
    data class Ally(val botId: UUID) : Party

    /** A filler bot of a team round: [arena] names the round (its region id), [side] the team it fights for. */
    data class TeamBot(val botId: UUID, val arena: String, val side: Side) : Party
    /** The boss bot of the boss arena named [arena]. */
    data class Boss(val botId: UUID, val arena: String) : Party
    data object Mob : Party
    data object Other : Party
}

object DamagePolicy {
    /**
     * [sideOf] gives the seat (arena, side, fighting) of any player or bot seated in a running team round; [bossArenaOf]
     * the boss arena a real player stands in (inside its region and its round wall), or null.
     *
     * Team rounds: seated enemies (players or filler bots) hurt each other only while their round is FIGHTING; before
     * the fight (waiting for fillers, countdown) and during the rematch wait nobody seated is hurt by anyone seated.
     * Seated and unseated never hurt each other. A filler bot the round no longer lists is never fighting.
     *
     * Bosses: a boss and a real player inside its arena hurt each other (the boss's own health is a plugin pool, see
     * the damage listener); bosses never hurt bots and bots never hurt bosses; two real players never hurt each other
     * while either stands in a boss arena.
     */
    fun allow(
        attacker: Party,
        victim: Party,
        sideOf: (UUID) -> TeamSeat?,
        bossArenaOf: (UUID) -> String?,
    ): Boolean {
        if (attacker is Boss || victim is Boss) {
            val boss = (attacker as? Boss) ?: (victim as Boss)
            val other = if (attacker is Boss) victim else attacker
            return other is RealPlayer && bossArenaOf(other.id) == boss.arena
        }
        if (attacker is RealPlayer && victim is RealPlayer && (bossArenaOf(attacker.id) != null || bossArenaOf(victim.id) != null)) return false
        // Team rounds: only enemies of the same round hurt each other; nobody else is touched by, or touches, a seated one.
        val a = seat(attacker, sideOf)
        val v = seat(victim, sideOf)
        if (attacker is TeamBot || victim is TeamBot || (a != null && v != null) || (attacker is RealPlayer && victim is RealPlayer && (a != null || v != null))) {
            return a != null && v != null && a.arena == v.arena && a.side != v.side && a.fighting && v.fighting
        }
        if (attacker is Trainer || victim is Trainer) {
            return (attacker is Trainer && victim is RealPlayer && victim.id == attacker.owner) ||
                (victim is Trainer && attacker is RealPlayer && attacker.id == victim.owner)
        }
        if (attacker is Ally && (victim is RealPlayer || victim is Ally)) return false
        if (attacker is RealPlayer && victim is Ally) return false
        return true
    }

    private fun seat(p: Party, sideOf: (UUID) -> TeamSeat?): TeamSeat? = when (p) {
        is TeamBot -> TeamSeat(p.arena, p.side, sideOf(p.botId)?.fighting ?: false)
        is RealPlayer -> sideOf(p.id)
        else -> null
    }
}
