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

/**
 * Enforces [DamagePolicy]: trainers only fight their owner, team rounds only between enemies, allies never hurt
 * players/allies and vice versa, bosses only fight the players inside their arena. A boss bot never takes damage
 * itself: every hit on it is cancelled and a real player's final damage goes to the boss's pool instead.
 */
class DamageListener(private val manager: BotManager, private val bosses: () -> BossDirector) : Listener {

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onDamage(event: EntityDamageByEntityEvent) {
        val damager = event.damager
        val attacker = party((damager as? Projectile)?.shooter as? Entity ?: damager.takeUnless { it is Projectile })
        val victim = party(event.entity)
        if (!DamagePolicy.allow(attacker, victim, manager.director::teamSideOf, bosses()::arenaOf)) event.isCancelled = true
    }

    /**
     * Last word on damage to a boss bot: always cancelled (so it is immune to fall, fire, lava, drowning, suffocation
     * and any other non-entity damage). A hit that [onDamage] let through (a real player inside the boss's arena,
     * directly or with a projectile, judged by the shooter) lowers the pool by its final damage.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onBossDamage(event: EntityDamageEvent) {
        val victim = event.entity
        if (manager.record(victim.uniqueId)?.key !is SessionKey.Boss) return
        val counted = !event.isCancelled
        val amount = event.finalDamage
        event.isCancelled = true
        if (!counted) return
        val damager = (event as? EntityDamageByEntityEvent)?.damager ?: return
        val source = (damager as? Projectile)?.shooter as? Entity ?: damager.takeUnless { it is Projectile }
        val p = source as? Player ?: return
        if (manager.isBot(p.uniqueId)) return
        bosses().onBossHit(victim.uniqueId, p, amount)
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
                is SessionKey.Boss -> Party.Boss(r.id, k.arena)
                is SessionKey.Team -> r.side?.let { Party.TeamBot(r.id, k.regionId, it) } ?: Party.Trainer(r.id, NOBODY) // a team bot always has a side; never hurts anyone if not
            }
        }
        return when (e) {
            is Player -> Party.RealPlayer(e.uniqueId)
            is Enemy -> Party.Mob
            else -> Party.Other
        }
    }

    private companion object {
        val NOBODY = java.util.UUID(0, 0)
    }
}
