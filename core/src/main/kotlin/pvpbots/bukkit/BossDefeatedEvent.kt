package pvpbots.bukkit

import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

/**
 * Fired once when a boss dies, before any reward is given (not cancellable). [arena] is the boss arena's name,
 * [bossType] the boss type id (a key of `bosses:`), [winners] the online winners (spec 5.7: inside at the kill or died
 * inside during the fight, and dealt at least 1 damage).
 */
class BossDefeatedEvent(val arena: String, val bossType: String, val winners: List<Player>) : Event() {
    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLERS
    }
}
