package pvpbots.bukkit

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter

/** `/pvpbots status | reload | clear | selftest <trainer|ally|load N>` (admins only). */
class AdminCommand(private val plugin: PvpBotsPlugin) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        when (args.firstOrNull()?.lowercase()) {
            "status" -> status(sender)
            "reload" -> {
                plugin.reload()
                sender.sendMessage("PvpBots: config reloaded; all sessions ended and restarted.")
            }
            "clear" -> {
                val n = plugin.botManager.count()
                plugin.clear()
                sender.sendMessage("PvpBots: removed $n bot(s).")
            }
            "selftest" -> selfTest(sender, args.drop(1))
            else -> return false
        }
        return true
    }

    private fun status(sender: CommandSender) {
        val slots = plugin.slots
        val d = plugin.director
        val m = plugin.botManager
        sender.sendMessage(
            "PvpBots: bots ${slots.used()}/${slots.capacity()} (live ${m.count()}), queued ${slots.queued()}, " +
                "sessions: ${d.trainerCount()} trainer, ${d.squadCount()} squad, " +
                "avg bot tick ${"%.3f".format(m.averageTickMs())} ms (last 100 ticks)",
        )
    }

    private fun selfTest(sender: CommandSender, rest: List<String>) {
        if (!sender.hasPermission("pvpbots.debug")) {
            sender.sendMessage("You need pvpbots.debug for the self-test.")
            return
        }
        val mode = when (rest.firstOrNull()?.lowercase()) {
            "trainer" -> SelfTest.Mode.Trainer
            "ally" -> SelfTest.Mode.Ally
            "load" -> {
                val n = rest.getOrNull(1)?.toIntOrNull()
                if (n == null || n !in 1..500) {
                    sender.sendMessage("Usage: /pvpbots selftest load <1-500>")
                    return
                }
                SelfTest.Mode.Load(n)
            }
            else -> {
                sender.sendMessage("Usage: /pvpbots selftest <trainer|ally|load N>")
                return
            }
        }
        sender.sendMessage("PvpBots: self-test $mode started in world ${SelfTest.WORLD}; results follow and go to selftest-result.txt.")
        plugin.runSelfTest(mode, shutdownAfter = false) { sender.sendMessage(it) }
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        val options = when (args.size) {
            1 -> listOf("status", "reload", "clear", "selftest")
            2 -> if (args[0].equals("selftest", true)) listOf("trainer", "ally", "load") else emptyList()
            else -> emptyList()
        }
        return options.filter { it.startsWith(args.last(), ignoreCase = true) }
    }
}
