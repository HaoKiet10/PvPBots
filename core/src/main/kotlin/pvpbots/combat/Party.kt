package pvpbots.combat

import pvpbots.combat.Party.Ally
import pvpbots.combat.Party.RealPlayer
import pvpbots.combat.Party.Trainer
import java.util.UUID

sealed interface Party {
    data class RealPlayer(val id: UUID) : Party
    data class Trainer(val botId: UUID, val owner: UUID) : Party
    data class Ally(val botId: UUID) : Party
    data object Mob : Party
    data object Other : Party
}

object DamagePolicy {
    fun allow(attacker: Party, victim: Party): Boolean {
        if (attacker is Trainer || victim is Trainer) {
            return (attacker is Trainer && victim is RealPlayer && victim.id == attacker.owner) ||
                (victim is Trainer && attacker is RealPlayer && attacker.id == victim.owner)
        }
        if (attacker is Ally && (victim is RealPlayer || victim is Ally)) return false
        if (attacker is RealPlayer && victim is Ally) return false
        return true
    }
}
