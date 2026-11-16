package pvpbots.bukkit

import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import pvpbots.adapter.AdapterLoader
import pvpbots.adapter.BotAdapter
import pvpbots.session.AreaDirector
import pvpbots.session.AreaRef
import pvpbots.slots.BotSlots
import java.io.File
import java.util.UUID
import java.util.logging.Level

/** Plugin entry point: loads the adapter and config, owns the director/slots lifecycle and the main tick. */
class PvpBotsPlugin : JavaPlugin() {
    lateinit var regionWatcher: RegionWatcher
        private set
    lateinit var botManager: BotManager
        private set
    lateinit var director: AreaDirector
        private set
    lateinit var slots: BotSlots
        private set

    private var flagsRegistered = false
    private var running = false
    private var tickTask: BukkitTask? = null
    private var selfTest: SelfTest? = null

    override fun onLoad() {
        regionWatcher = RegionWatcher(this, object : RegionWatcher.Events {
            override fun onAreaChange(player: UUID, from: AreaRef?, to: AreaRef?) =
                director.onAreaChange(player, from, to, botManager.nowMs())

            override fun maxBots(): Int = botManager.config.settings.maxBots
        })
        flagsRegistered = regionWatcher.registerFlags()
    }

    override fun onEnable() {
        if (!flagsRegistered) {
            server.pluginManager.disablePlugin(this)
            return
        }
        val version = Bukkit.getMinecraftVersion()
        val className = AdapterLoader.classNameFor(version)
        if (className == null) {
            logger.severe("Unsupported Minecraft version $version. Supported: ${AdapterLoader.SUPPORTED.joinToString(", ")}")
            server.pluginManager.disablePlugin(this)
            return
        }
        val adapter = try {
            Class.forName(className, true, classLoader).getDeclaredConstructor().newInstance() as BotAdapter
        } catch (e: Throwable) {
            logger.log(Level.SEVERE, "Adapter $className for Minecraft $version could not be loaded; disabling", e)
            server.pluginManager.disablePlugin(this)
            return
        }

        saveDefaultConfig()
        botManager = BotManager(adapter, logger, loadBotConfig())
        newDirector(null)
        PvpBotsApi.manager = botManager

        server.pluginManager.registerEvents(DamageListener(botManager), this)
        server.pluginManager.registerEvents(BotLifecycleListener(this), this)
        regionWatcher.start()
        getCommand("pvpbots")?.let { cmd ->
            val admin = AdminCommand(this)
            cmd.setExecutor(admin)
            cmd.tabCompleter = admin
        }
        tickTask = server.scheduler.runTaskTimer(this, Runnable { tick() }, 1L, 1L)
        running = true

        val mode = System.getProperty("pvpbots.selftest")?.trim().orEmpty()
        if (mode.isNotEmpty()) {
            logger.info("pvpbots.selftest=$mode: the self-test runs once the server has started, then the server stops")
            server.scheduler.runTask(this, Runnable { runSelfTest(SelfTest.Mode.parse(mode), shutdownAfter = true) { logger.info(it) } })
        }
    }

    override fun onDisable() {
        selfTest?.abort()
        selfTest = null
        if (!running) return
        running = false
        tickTask?.cancel()
        director.shutdown()
        botManager.removeAll()
        regionWatcher.stop()
        PvpBotsApi.manager = null
    }

    private fun tick() {
        val tick = Bukkit.getCurrentTick().toLong()
        director.tick(tick * 50L)
        botManager.tickBots(tick)
    }

    /** Reads config.yml and logs every warning. */
    private fun loadBotConfig(): BotConfig {
        val (config, warnings) = BotConfig.load(File(dataFolder, "config.yml")) { getTextResource("config.yml") }
        warnings.forEach { logger.warning("config: $it") }
        return config
    }

    /**
     * R9: ends every session (all bots removed) and builds a NEW [BotSlots] + [AreaDirector]. Players already
     * standing in an area are picked up again by the region watcher's next refresh. Never call this from inside a
     * BotPort callback. [maxBotsOverride] is for the self-test.
     */
    fun newDirector(maxBotsOverride: Int?) {
        if (this::director.isInitialized) director.shutdown()
        if (this::botManager.isInitialized) botManager.removeAll()
        val settings = botManager.config.settings
        slots = BotSlots(maxBotsOverride ?: settings.maxBots)
        director = AreaDirector(slots, botManager, settings.timings)
        botManager.director = director
        if (this::regionWatcher.isInitialized) regionWatcher.reset()
    }

    /** /pvpbots reload: ends all sessions, re-reads config.yml, starts over. */
    fun reload() {
        director.shutdown()
        botManager.removeAll()
        botManager.config = loadBotConfig()
        newDirector(null)
    }

    /** /pvpbots clear: removes every bot now (sessions restart for players still inside an area). */
    fun clear() = newDirector(null)

    fun selfTestRunning(): Boolean = selfTest != null

    fun runSelfTest(mode: SelfTest.Mode, shutdownAfter: Boolean, report: (String) -> Unit) {
        if (selfTest != null) {
            report("A self-test is already running.")
            return
        }
        val test = SelfTest(this, mode, shutdownAfter, report) { selfTest = null }
        selfTest = test
        test.start()
    }
}
