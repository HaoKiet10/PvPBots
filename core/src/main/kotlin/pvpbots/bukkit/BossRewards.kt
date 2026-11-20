package pvpbots.bukkit

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import pvpbots.boss.BossType
import pvpbots.boss.LootRoller
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.random.Random

/**
 * Personal rewards of one winner (spec 5.7): loot rolled from the type's table into the inventory (overflow dropped at
 * their feet), the type's XP, then the type's console commands with `%player%` and `%boss%` (the boss name).
 */
class BossRewards(private val logger: Logger, private val rng: Random = Random.Default) {
    /** Runs one console command; replaced by the self-test to record them. */
    var dispatch: (String) -> Unit = CONSOLE

    fun give(p: Player, type: BossType) {
        for (e in LootRoller.roll(type.loot, rng)) {
            val mat = Material.matchMaterial(e.item)
            if (mat == null || !mat.isItem || mat.isAir || e.amount <= 0) {
                logger.warning("Boss ${type.id}: loot item ${e.item} is not an item; skipped")
                continue
            }
            var left = e.amount
            while (left > 0) { // amounts above a stack size become several stacks
                val n = minOf(left, mat.maxStackSize)
                left -= n
                p.inventory.addItem(ItemStack(mat, n)).values.forEach { p.world.dropItemNaturally(p.location, it) }
            }
        }
        if (type.xp > 0) p.giveExp(type.xp)
        for (c in type.commands) {
            val cmd = c.replace("%player%", p.name).replace("%boss%", type.name).removePrefix("/")
            try {
                dispatch(cmd)
            } catch (e: Exception) {
                logger.log(Level.WARNING, "Boss ${type.id}: reward command '$cmd' failed", e)
            }
        }
    }

    companion object {
        val CONSOLE: (String) -> Unit = { Bukkit.dispatchCommand(Bukkit.getConsoleSender(), it) }
    }
}
