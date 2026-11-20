package pvpbots.bukkit

import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import pvpbots.arena.Preset

/**
 * `/pvpbots arena create <name> <duel|squad|team> [kit] [confirm] | remove <name> [confirm] | list | kit <name> <kit|none>`.
 * The caller has already checked `pvpbots.admin`. Changing blocks always needs a trailing `confirm`.
 */
class ArenaCommand(private val plugin: PvpBotsPlugin) {
    private val service get() = plugin.arenaService

    fun run(sender: CommandSender, args: List<String>) {
        when (args.firstOrNull()?.lowercase()) {
            "create" -> create(sender, args.drop(1))
            "remove" -> remove(sender, args.drop(1))
            "list" -> service.list().forEach { sender.sendMessage(it) }
            "kit" -> kit(sender, args.drop(1))
            else -> sender.sendMessage(USAGE)
        }
    }

    private fun create(sender: CommandSender, rest: List<String>) {
        if (sender !is Player) {
            sender.sendMessage("Only a player can create an arena: it is built where you stand, entrance behind you.")
            return
        }
        val confirm = rest.lastOrNull().equals("confirm", ignoreCase = true)
        val args = if (confirm) rest.dropLast(1) else rest
        val preset = args.getOrNull(1)?.let { p -> Preset.TEAM_PRESETS.firstOrNull { it.name.equals(p, ignoreCase = true) } }
        if (args.size !in 2..3 || preset == null) {
            sender.sendMessage("Usage: /pvpbots arena create <name> <duel|squad|team> [kit] [confirm]")
            return
        }
        val name = args[0].lowercase()
        val kit = args.getOrNull(2)?.lowercase()?.takeUnless { it == "none" }
        if (!confirm) {
            val lines = service.preview(sender, name, preset, kit)
            lines.forEach { sender.sendMessage(it) }
            if (lines.none { it.startsWith("Cannot build") }) {
                sender.sendMessage("Repeat with 'confirm' at the end to build it here: /pvpbots arena create ${args.joinToString(" ")} confirm")
            }
            return
        }
        service.create(sender, name, preset, kit).forEach { sender.sendMessage(it) }
    }

    private fun remove(sender: CommandSender, rest: List<String>) {
        val name = rest.firstOrNull()?.lowercase()
        if (name == null || rest.size > 2) {
            sender.sendMessage("Usage: /pvpbots arena remove <name> [confirm]")
            return
        }
        if (!rest.getOrNull(1).equals("confirm", ignoreCase = true)) {
            val r = service.registry.get(name)
            if (r == null) {
                sender.sendMessage("No arena named '$name'.")
                return
            }
            sender.sendMessage(
                "Removing arena '$name' ends every fight in it, deletes region ${r.regionId} and puts back the blocks that were " +
                    "there before it was built. Repeat with 'confirm' at the end: /pvpbots arena remove $name confirm",
            )
            return
        }
        service.remove(name).forEach { sender.sendMessage(it) }
    }

    private fun kit(sender: CommandSender, rest: List<String>) {
        if (rest.size != 2) {
            sender.sendMessage("Usage: /pvpbots arena kit <name> <kit|none>")
            return
        }
        val kit = rest[1].lowercase().takeUnless { it == "none" }
        service.setKit(rest[0].lowercase(), kit).forEach { sender.sendMessage(it) }
    }

    /** [args] are the arguments after `arena`. */
    fun complete(args: List<String>): List<String> {
        val names = { service.registry.all().map { it.name } }
        val kits = { plugin.botManager.config.kits.kits.keys.toList() }
        val sub = args.firstOrNull()?.lowercase()
        return when (args.size) {
            1 -> listOf("create", "remove", "list", "kit")
            2 -> if (sub == "remove" || sub == "kit") names() else emptyList()
            3 -> when (sub) {
                "create" -> Preset.TEAM_PRESETS.map { it.name.lowercase() }
                "remove" -> listOf("confirm")
                "kit" -> kits() + "none"
                else -> emptyList()
            }
            4 -> if (sub == "create") kits() + "confirm" else emptyList()
            5 -> if (sub == "create" && !args[3].equals("confirm", true)) listOf("confirm") else emptyList()
            else -> emptyList()
        }
    }

    companion object {
        const val USAGE = "Usage: /pvpbots arena <create <name> <duel|squad|team> [kit] [confirm] | remove <name> [confirm] | list | kit <name> <kit|none>>"
    }
}
