package pvpbots.bukkit

import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent

/**
 * Deaths and quits. A dying bot drops nothing, gives no XP and has no death message; the director is told on the
 * next tick (never from inside the vanilla death code) and the bot is removed for good. Real players' deaths and
 * quits go to the director.
 */
class BotLifecycleListener(private val plugin: PvpBotsPlugin) : Listener {
    private val manager get() = plugin.botManager

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onDeath(event: PlayerDeathEvent) {
        val id = event.player.uniqueId
        if (manager.isBot(id)) {
            event.drops.clear()
            event.droppedExp = 0
            event.setShouldDropExperience(false)
            event.deathMessage(null)
            event.keepInventory = false
            Bukkit.getScheduler().runTask(plugin, Runnable {
                plugin.director.onBotDeath(id, manager.nowMs())
                manager.remove(id) // no-op when the director already removed it
            })
        } else {
            plugin.director.onPlayerDeath(id, manager.nowMs())
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        val id = event.player.uniqueId
        if (manager.isBot(id)) {
            event.quitMessage(null)
            return
        }
        plugin.regionWatcher.forget(id)
        plugin.director.onQuit(id, manager.nowMs())
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onJoin(event: PlayerJoinEvent) {
        if (manager.isBot(event.player.uniqueId)) event.joinMessage(null)
    }
}
