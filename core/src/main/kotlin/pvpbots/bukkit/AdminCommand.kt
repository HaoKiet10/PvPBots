package pvpbots.bukkit

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter

/** `/pvpbots status | reload | clear | selftest <trainer|ally|load N> confirm` (admins only). */
class AdminCommand(private val plugin: PvpBotsPlugin) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val sub = args.firstOrNull()?.lowercase()
        if (sub in ADMIN_SUBCOMMANDS && !sender.hasPermission("pvpbots.admin")) {
            sender.sendMessage("You need pvpbots.admin for /pvpbots $sub.")
            return true
        }
        when (sub) {
            "status" -> status(sender)
            "reload" -> {
                plugin.reload()
                sender.sendMessage("PvpBots: config reloaded; all sessions ended and restarted.")
            }
            "clear" -> {
                val n = plugin.botManager.count()
                plugin.clear()
                sender.sendMessage("PvpBots: removed $n bot(s). Players still inside a bot region get their bots back within a few seconds.")
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
                "pending spawns ${d.pendingSpawns()}, sessions: ${d.trainerCount()} trainer, ${d.squadCount()} squad, " +
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
                    sender.sendMessage("Usage: /pvpbots selftest load <1-500> confirm")
                    return
                }
                SelfTest.Mode.Load(n)
            }
            else -> {
                sender.sendMessage("Usage: /pvpbots selftest <trainer|ally|load N> confirm")
                return
            }
        }
        val confirmAt = if (mode is SelfTest.Mode.Load) 2 else 1
        if (!rest.getOrNull(confirmAt).equals("confirm", ignoreCase = true)) {
            sender.sendMessage(
                "PvpBots: the self-test ENDS EVERY LIVE BOT SESSION on this server (all bots are removed; sessions restart " +
                    "afterwards for players still inside a bot region) and uses temporary worlds ${SelfTest.WORLD}*. " +
                    "Repeat with 'confirm' at the end to run it: /pvpbots selftest ${rest.take(confirmAt).joinToString(" ")} confirm",
            )
            return
        }
        sender.sendMessage(
            "PvpBots: self-test $mode started; all live bot sessions will be ended. It runs in temporary world(s) " +
                "${SelfTest.WORLD}* (unloaded and deleted afterwards); results follow and go to selftest-result.txt.",
        )
        plugin.runSelfTest(mode, shutdownAfter = false) { sender.sendMessage(it) }
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        val options = when (args.size) {
            1 -> listOf("status", "reload", "clear", "selftest")
            2 -> if (args[0].equals("selftest", true)) listOf("trainer", "ally", "load") else emptyList()
            3 -> if (args[0].equals("selftest", true) && !args[1].equals("load", true)) listOf("confirm") else emptyList()
            4 -> if (args[0].equals("selftest", true) && args[1].equals("load", true)) listOf("confirm") else emptyList()
            else -> emptyList()
        }
        return options.filter { it.startsWith(args.last(), ignoreCase = true) }
    }

    private companion object {
        val ADMIN_SUBCOMMANDS = setOf("status", "reload", "clear")
    }
}
