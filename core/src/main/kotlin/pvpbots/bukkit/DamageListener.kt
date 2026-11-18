package pvpbots.bukkit

import org.bukkit.entity.Enemy
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import pvpbots.combat.DamagePolicy
import pvpbots.combat.Party
import pvpbots.session.SessionKey

/** Enforces [DamagePolicy]: trainers only fight their owner, allies never hurt players/allies and vice versa. */
class DamageListener(private val manager: BotManager) : Listener {

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onDamage(event: EntityDamageByEntityEvent) {
        val damager = event.damager
        val attacker = party((damager as? Projectile)?.shooter as? Entity ?: damager.takeUnless { it is Projectile })
        val victim = party(event.entity)
        if (!DamagePolicy.allow(attacker, victim)) event.isCancelled = true
    }

    /** Feeds the fight judge (adaptive skill); runs after every plugin that may cancel or change the damage. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDamageDone(event: EntityDamageEvent) = manager.countDamage(event)

    fun party(e: Entity?): Party {
        if (e == null) return Party.Other
        manager.record(e.uniqueId)?.let { r ->
            return when (val k = r.key) {
                is SessionKey.Trainer -> Party.Trainer(r.id, k.owner)
                is SessionKey.Squad -> Party.Ally(r.id)
            }
        }
        return when (e) {
            is Player -> Party.RealPlayer(e.uniqueId)
            is Enemy -> Party.Mob
            else -> Party.Other
        }
    }
}
