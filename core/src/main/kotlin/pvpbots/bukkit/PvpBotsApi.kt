package pvpbots.bukkit

import org.bukkit.entity.Player

/**
 * Public API for other plugins. Bots are not in `Bukkit.getOnlinePlayers()` (and `isOnline()` is false for them),
 * so this registry lookup is the only reliable way to tell a bot from a real player. Main thread only.
 */
object PvpBotsApi {
    @Volatile
    internal var manager: BotManager? = null

    /** True if [player] is a PvpBots bot. False before the plugin is enabled. */
    @JvmStatic
    fun isBot(player: Player): Boolean = manager?.isBot(player.uniqueId) == true

    /** Every live bot, as Bukkit players (snapshot). Empty before the plugin is enabled. */
    @JvmStatic
    fun bots(): Collection<Player> = manager?.botPlayers().orEmpty()
}
