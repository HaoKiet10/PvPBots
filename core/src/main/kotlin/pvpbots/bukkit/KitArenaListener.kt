package pvpbots.bukkit

import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerPickupArrowEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent

/**
 * Kit-arena inventory hooks (§5.6). Join and quit run at LOWEST: a stash is restored before anything else sees the
 * player and before the server saves them on quit. Bots are ignored by [KitArenaService] itself.
 */
class KitArenaListener(private val plugin: PvpBotsPlugin) : Listener {
    private val kits get() = plugin.kitArenas

    @EventHandler(priority = EventPriority.LOWEST)
    fun onJoin(event: PlayerJoinEvent) = kits.onJoin(event.player)

    @EventHandler(priority = EventPriority.LOWEST)
    fun onQuit(event: PlayerQuitEvent) = kits.onQuit(event.player)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onRespawn(event: PlayerRespawnEvent) = kits.onRespawn(event.player)

    /** No smuggling kit items out: dropping is blocked while wearing an arena kit in its arena. */
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onDrop(event: PlayerDropItemEvent) = kits.onDrop(event)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onDropDone(event: PlayerDropItemEvent) = kits.onDropDone(event)

    /** No picking up while wearing an arena kit: the next refill or restore would replace those items. */
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onPickup(event: EntityPickupItemEvent) = kits.onPickup(event)

    /** Arrows are picked up through their own event. */
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onPickupArrow(event: PlayerPickupArrowEvent) = kits.onPickupArrow(event)

    /** A death while wearing an arena kit drops nothing; the player keeps their XP (the stash comes back on respawn). */
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onDeath(event: PlayerDeathEvent) = kits.onDeath(event)

    /** The death went through: the stash waits for the respawn (which resets the inventory). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDied(event: PlayerDeathEvent) = kits.onDied(event.player)
}
