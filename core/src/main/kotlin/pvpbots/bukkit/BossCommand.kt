package pvpbots.bukkit

import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import pvpbots.arena.Preset

/**
 * `/pvpbots boss create <name> <boss-type> [confirm] | remove <name> [confirm] | list`. The caller has already checked
 * `pvpbots.admin`. Building and removing reuse [ArenaService] (snapshot first, staged build, crash resume, evacuation);
 * changing blocks always needs a trailing `confirm`.
 */
class BossCommand(private val plugin: PvpBotsPlugin) {
    private val service get() = plugin.arenaService

    fun run(sender: CommandSender, args: List<String>) {
        when (args.firstOrNull()?.lowercase()) {
            "create" -> create(sender, args.drop(1))
            "remove" -> remove(sender, args.drop(1))
            "list" -> service.list(bossOnly = true).forEach { sender.sendMessage(it) }
            else -> sender.sendMessage(USAGE)
        }
    }

    private fun create(sender: CommandSender, rest: List<String>) {
        if (sender !is Player) {
            sender.sendMessage("Only a player can create a boss arena: it is built where you stand, entrance behind you.")
            return
        }
        val confirm = rest.lastOrNull().equals("confirm", ignoreCase = true)
        val args = if (confirm) rest.dropLast(1) else rest
        if (args.size != 2) {
            sender.sendMessage("Usage: /pvpbots boss create <name> <boss-type> [confirm]")
            return
        }
        val name = args[0].lowercase()
        val type = service.bossKey(args[1])!!
        if (!confirm) {
            val lines = service.preview(sender, name, Preset.BOSS, null, type)
            lines.forEach { sender.sendMessage(it) }
            if (lines.none { it.startsWith("Cannot build") }) {
                sender.sendMessage("Repeat with 'confirm' at the end to build it here: /pvpbots boss create $name ${args[1]} confirm")
            }
            return
        }
        service.create(sender, name, Preset.BOSS, null, type).forEach { sender.sendMessage(it) }
    }

    private fun remove(sender: CommandSender, rest: List<String>) {
        val name = rest.firstOrNull()?.lowercase()
        if (name == null || rest.size > 2) {
            sender.sendMessage("Usage: /pvpbots boss remove <name> [confirm]")
            return
        }
        val r = service.registry.get(name)
        if (r == null || r.preset != Preset.BOSS) {
            sender.sendMessage("No boss arena named '$name'.")
            return
        }
        if (!rest.getOrNull(1).equals("confirm", ignoreCase = true)) {
            sender.sendMessage(
                "Removing boss arena '$name' ends its fight (a live boss is removed), deletes region ${r.regionId} and puts back the " +
                    "blocks that were there before it was built. Repeat with 'confirm' at the end: /pvpbots boss remove $name confirm",
            )
            return
        }
        service.remove(name).forEach { sender.sendMessage(it) }
    }

    /** [args] are the arguments after `boss`. */
    fun complete(args: List<String>): List<String> {
        val sub = args.firstOrNull()?.lowercase()
        return when (args.size) {
            1 -> listOf("create", "remove", "list")
            2 -> if (sub == "remove") service.registry.all().filter { it.preset == Preset.BOSS }.map { it.name } else emptyList()
            3 -> when (sub) {
                "create" -> plugin.botManager.config.bosses.keys.toList()
                "remove" -> listOf("confirm")
                else -> emptyList()
            }
            4 -> if (sub == "create") listOf("confirm") else emptyList()
            else -> emptyList()
        }
    }

    companion object {
        const val USAGE = "Usage: /pvpbots boss <create <name> <boss-type> [confirm] | remove <name> [confirm] | list>"
    }
}
