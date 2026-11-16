package pvpbots.adapter.v26_2

import io.papermc.paper.event.player.PlayerTrackEntityEvent
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket
import org.bukkit.Bukkit
import org.bukkit.craftbukkit.entity.CraftPlayer
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.plugin.java.JavaPlugin
import java.util.EnumSet

/**
 * Makes bots renderable (with their skin) on clients without listing them in the tab list.
 *
 * A player entity is only spawned on a client that already has a player-info entry for its UUID. Variant-A bots
 * are not in the PlayerList, so vanilla never sends one. Paper fires [PlayerTrackEntityEvent] right before the
 * tracker sends the spawn packets to a viewer; at that moment we send that viewer a player-info update with
 * ADD_PLAYER (profile incl. `textures`) and UPDATE_GAME_MODE but WITHOUT UPDATE_LISTED, so the client keeps the
 * entry unlisted. This covers bots spawning near players, players joining later and players walking into range.
 * When a bot is removed, every online player gets a player-info remove.
 */
internal object TabVisibility : Listener {
    private var registered = false

    fun ensureRegistered() {
        if (registered) return
        val plugin = try {
            JavaPlugin.getProvidingPlugin(TabVisibility::class.java)
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException("pvp-bots adapter must be loaded by a plugin class loader", e)
        }
        Bukkit.getPluginManager().registerEvents(this, plugin)
        registered = true
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTrack(event: PlayerTrackEntityEvent) {
        val bot = (event.entity as? CraftPlayer)?.handle as? FakeBot ?: return
        val viewer = (event.player as CraftPlayer).handle
        if (viewer is FakeBot) return // its connection drops everything anyway
        viewer.connection.send(
            ClientboundPlayerInfoUpdatePacket(
                EnumSet.of(
                    ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
                    ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE,
                ),
                listOf(bot),
            ),
        )
    }

    /** Call after the bot entity has been removed (so its despawn packet goes out first). */
    fun sendRemove(bot: FakeBot) {
        val packet = ClientboundPlayerInfoRemovePacket(listOf(bot.uuid))
        for (p in Bukkit.getOnlinePlayers()) {
            (p as CraftPlayer).handle.connection.send(packet)
        }
    }
}
