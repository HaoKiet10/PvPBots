package pvpbots.bukkit

import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import pvpbots.adapter.BotAdapter
import pvpbots.adapter.BotHandle
import pvpbots.adapter.BotSpec
import pvpbots.math.Aim
import pvpbots.math.BlockPos
import pvpbots.math.Vec3
import pvpbots.path.SpawnPointFinder
import pvpbots.profile.Difficulty
import pvpbots.profile.DifficultyProfile
import pvpbots.session.AreaDirector
import pvpbots.session.AreaRef
import pvpbots.session.BotPort
import pvpbots.session.Role
import pvpbots.session.SessionKey
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.random.Random

data class BotRecord(
    val handle: BotHandle,
    val key: SessionKey,
    val role: Role,
    val profile: DifficultyProfile,
    val area: AreaRef,
    val controller: BotController,
) {
    val id: UUID get() = handle.id
    val player: Player get() = handle.bukkit

    /** Set when removing the entity failed: the bot stays registered (still a bot), is not ticked, and removal is retried. */
    var removeFailedAtTick = -1L
}

/**
 * The server side of [BotPort]: owns the registry of live bots (the only source of truth for "is this a bot"),
 * spawns them through the adapter and drives every bot's [BotController] each tick. Main thread only.
 */
class BotManager(
    val adapter: BotAdapter,
    private val logger: Logger,
    var config: BotConfig,
) : BotPort {
    /** Set by the plugin; replaced on reload/clear (a new director is built, never reused). */
    lateinit var director: AreaDirector

    /** Current time in director milliseconds (server tick * 50). */
    var nowMs: () -> Long = { Bukkit.getCurrentTick() * 50L }

    /** Test hook: told about every bot right after it is registered. */
    var spawnObserver: ((BotRecord) -> Unit)? = null

    private val bots = LinkedHashMap<UUID, BotRecord>()
    private val rng = Random.Default
    private val tickNanos = LongArray(100)
    private var tickSamples = 0

    /** Wall time of the most recent [tickBots] call (all bots), in nanoseconds. */
    var lastTickNanos = 0L
        private set

    /** Total wall time spent in [spawn] since start (self-test measurements read differences of it). */
    var spawnNanos = 0L
        private set
    private var distanceWarned = false
    private val failureLog = FailureLog()

    override fun isBot(id: UUID): Boolean = id in bots

    fun record(id: UUID): BotRecord? = bots[id]

    fun records(): List<BotRecord> = bots.values.toList()

    fun botPlayers(): List<Player> = bots.values.map { it.player }

    fun count(): Int = bots.size

    /** Average wall time of one whole bot tick (all bots), over the last 100 server ticks. */
    fun averageTickMs(): Double {
        val n = tickSamples.coerceAtMost(tickNanos.size)
        if (n == 0) return 0.0
        var sum = 0L
        for (i in 0 until n) sum += tickNanos[i]
        return sum / n / 1_000_000.0
    }

    override fun spawn(key: SessionKey, role: Role, difficulty: Difficulty, area: AreaRef, nearPlayer: UUID): UUID? {
        val start = System.nanoTime()
        try {
            return spawnTimed(key, role, difficulty, area, nearPlayer)
        } finally {
            spawnNanos += System.nanoTime() - start
        }
    }

    private fun spawnTimed(key: SessionKey, role: Role, difficulty: Difficulty, area: AreaRef, nearPlayer: UUID): UUID? {
        try {
            val world = Bukkit.getWorld(area.world) ?: return null.also { logger.warning("Bot spawn: world ${area.world} is not loaded") }
            val near = Bukkit.getEntity(nearPlayer)
            if (near == null || near.world != world) return null
            val grid = BukkitBlockGrid(world)
            val nl = near.location
            val point = SpawnPointFinder.find(grid, BlockPos(nl.blockX, nl.blockY, nl.blockZ), area.bounds, rng)
            if (point == null) {
                logger.warning("Bot spawn: no standable block in region ${area.regionId} near ${near.name}")
                return null
            }
            val eye = Vec3(point.x + 0.5, point.y + EYE_HEIGHT, point.z + 0.5)
            val ne = (near as? org.bukkit.entity.LivingEntity)?.eyeLocation ?: nl
            val facing = Aim.lookAt(eye, Vec3(ne.x, ne.y, ne.z))
            val at = Location(world, point.x + 0.5, point.y.toDouble(), point.z + 0.5, facing.yaw, facing.pitch)
            val profile = config.profiles.getValue(difficulty)
            val skin = config.skins.pick(rng)
            val id = UUID.randomUUID()
            val handle = BotProfiler.spawnPart(BotProfiler.SpawnPart.ADAPTER) { adapter.spawn(BotSpec(nameFor(role, id), id, skin?.value, skin?.signature), at) }
            try {
                BotProfiler.spawnPart(BotProfiler.SpawnPart.KIT) { config.kits.apply(handle.bukkit, profile.kit, profile) }
                BotProfiler.spawnPart(BotProfiler.SpawnPart.DISTANCES) { applyDistances(handle.bukkit) }
                val controller = BotController(this, handle, key, role, profile, area, facing)
                val record = BotRecord(handle, key, role, profile, area, controller)
                bots[handle.id] = record
                spawnObserver?.invoke(record)
                return handle.id
            } catch (e: VirtualMachineError) {
                throw e
            } catch (e: Throwable) {
                // The entity exists but the bot is unusable: never leave it in the world outside the registry.
                bots.remove(handle.id)
                TickIsolation.guard({ adapter.remove(handle) }) { r -> e.addSuppressed(r) }
                throw e
            }
        } catch (e: VirtualMachineError) {
            throw e
        } catch (e: Throwable) {
            logFailure(e, "Bot spawn failed for session $key in ${area.world}/${area.regionId}")
            return null
        }
    }

    /**
     * Removes the entity first, then drops the registry entry. If the adapter fails, the entry stays (the entity is
     * still treated as a bot, not ticked) and [tickBots] retries the removal every second.
     */
    override fun remove(botId: UUID) {
        val r = bots[botId] ?: return
        var ok = true
        TickIsolation.guard({ adapter.remove(r.handle) }) { e ->
            ok = false
            logFailure(e, "Removing bot ${r.handle} failed; retrying every second")
        }
        if (ok) bots.remove(botId) else r.removeFailedAtTick = Bukkit.getCurrentTick().toLong()
    }

    override fun isDead(player: UUID): Boolean = Bukkit.getPlayer(player)?.isDead == true

    override fun title(player: UUID, text: String) {
        Bukkit.getPlayer(player)?.showTitle(Title.title(Component.text(text), Component.empty()))
    }

    override fun actionBar(player: UUID, text: String) {
        Bukkit.getPlayer(player)?.sendActionBar(Component.text(text))
    }

    /**
     * Ticks every bot's controller. A bot whose tick throws anything (exceptions and LinkageErrors from the adapter
     * alike) is logged once and dropped as if it died (no win title); the other bots still tick.
     */
    fun tickBots(tick: Long) {
        val start = System.nanoTime()
        TickIsolation.forEach(bots.values.toList(), { r ->
            when {
                bots[r.id] !== r -> {} // removed by an earlier bot's tick
                r.removeFailedAtTick >= 0 -> if (tick - r.removeFailedAtTick >= 20) remove(r.id)
                else -> r.controller.tick(tick)
            }
        }) { r, e ->
            logFailure(e, "Bot ${r.handle} (session ${r.key}) failed and was removed", Level.SEVERE)
            failed(r.id)
        }
        lastTickNanos = System.nanoTime() - start
        tickNanos[tickSamples % tickNanos.size] = lastTickNanos
        tickSamples++
    }

    /** Ends a bot as if it died, but without a win title (the director counts the failure). The bot is always removed. */
    private fun failed(botId: UUID) {
        TickIsolation.endFailed(
            notify = { director.onBotFailed(botId, nowMs()) },
            remove = { remove(botId) },
        ) { e -> logFailure(e, "Ending the session of failed bot $botId threw", Level.SEVERE) }
    }

    /**
     * Logs [e] with its stack trace the first time its signature (class + top plugin frame) is seen; repeats are
     * counted and summarised in one WARNING line at most once per minute.
     */
    private fun logFailure(e: Throwable, context: String, level: Level = Level.WARNING) {
        when (val a = failureLog.record(e, System.currentTimeMillis())) {
            FailureLog.Action.Full -> logger.log(level, context, e)
            is FailureLog.Action.Summary -> logger.warning(
                "$context: ${FailureLog.signature(e)}: ${a.sinceLast} more time(s) since the last report " +
                    "(${a.total} total; stack trace logged the first time)",
            )
            FailureLog.Action.Silent -> {}
        }
    }

    /** Removes every bot immediately (used after director.shutdown(), which should already have done it). */
    fun removeAll() {
        for (id in bots.keys.toList()) remove(id)
    }

    private fun applyDistances(p: Player) {
        val d = config.settings.botViewDistance
        val view = runCatching { p.viewDistance = d }
        val sim = runCatching { p.simulationDistance = d }
        // Nobody reads a bot's chunk packets (its connection drops them): send distance 0 stops Paper from building
        // and "sending" chunks to it. Chunks around the bot still load and tick (view/simulation distance above).
        val send = runCatching { p.sendViewDistance = 0 }
        // Bots must not block night skipping or count for mob spawning around them.
        runCatching { p.isSleepingIgnored = true }
        runCatching { p.affectsSpawning = false }
        if ((view.isFailure || sim.isFailure || send.isFailure) && !distanceWarned) {
            distanceWarned = true
            logger.warning(
                "bot.view-distance $d could not be applied: view ${view.exceptionOrNull()?.message ?: "ok"}, " +
                    "simulation ${sim.exceptionOrNull()?.message ?: "ok"}, send distance 0 ${send.exceptionOrNull()?.message ?: "ok"}",
            )
        }
    }

    private fun nameFor(role: Role, id: UUID): String {
        val prefix = if (role == Role.TRAINER) "Trainer_" else "Ally_"
        var name = prefix + id.toString().substring(0, 4)
        // Names must never match a real online player or another bot.
        while (Bukkit.getPlayerExact(name) != null || bots.values.any { it.player.name == name }) {
            name = prefix + "%04x".format(rng.nextInt(0x10000))
        }
        return name
    }

    private companion object {
        const val EYE_HEIGHT = 1.62
    }
}
