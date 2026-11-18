package pvpbots.bukkit

import com.destroystokyo.paper.event.server.ServerTickEndEvent
import io.papermc.paper.event.packet.PlayerChunkLoadEvent
import org.bukkit.Bukkit
import org.bukkit.Difficulty as WorldDifficulty
import org.bukkit.GameMode
import org.bukkit.GameRules
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.WorldCreator
import org.bukkit.WorldType
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.entity.Zombie
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.scheduler.BukkitTask
import pvpbots.adapter.BotHandle
import pvpbots.adapter.BotSpec
import pvpbots.math.BlockPos
import pvpbots.math.Bounds
import pvpbots.profile.Difficulty
import pvpbots.session.AreaRef
import pvpbots.session.Role
import pvpbots.session.SessionKey
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * Scripted in-server checks (T1-T6 trainer, A1-A2 ally, A3-A6 + T7 arenas in [ArenaSelfTest], K1-K3 kit arenas in [KitSelfTest]; D1 is checked by the Gradle `selfTest` task after the
 * server exits). Runs in the flat world [WORLD] with dummy owners: fake players spawned through the adapter but NOT
 * registered as bots (so the director and the damage policy treat them as real players). Areas are hand-built
 * [AreaRef]s passed to `director.onAreaChange` directly. The script is a sequence that yields "wait N ticks".
 */
class SelfTest(
    private val plugin: PvpBotsPlugin,
    private val mode: Mode,
    private val shutdownAfter: Boolean,
    private val report: (String) -> Unit,
    private val onFinished: () -> Unit,
) : Listener {
    sealed interface Mode {
        data object All : Mode
        data object Trainer : Mode
        data object Ally : Mode
        data class Load(val bots: Int) : Mode

        companion object {
            /** `all`, `trainer`, `ally` or `load:N`; anything else means all. */
            fun parse(s: String): Mode = when {
                s.equals("trainer", true) -> Trainer
                s.equals("ally", true) -> Ally
                s.startsWith("load:", true) -> s.substringAfter(':').toIntOrNull()?.let { Load(it.coerceIn(1, 500)) } ?: All
                else -> All
            }
        }
    }

    private val manager get() = plugin.botManager
    private val director get() = plugin.director

    private val lines = ArrayList<String>()
    private var passed = 0
    private var failed = 0
    private val dummies = ArrayList<BotHandle>()
    private val zombies = ArrayList<Zombie>()
    private val fakeIds = LinkedHashSet<UUID>()
    private val damageTaken = HashMap<UUID, Double>()
    private val damageFromBots = HashMap<UUID, Double>()
    private val blockedFromBots = HashMap<UUID, Int>()
    private val firstDamageTick = HashMap<UUID, Long>()
    private var zombieHits = 0
    private var loadRecording = false
    private val msptSamples = ArrayList<Double>()
    private var chunkLoadsBots = 0
    private var chunkLoadsOthers = 0
    private var zombieHitsCancelled = 0
    private var zombieDamage = 0.0
    private var task: BukkitTask? = null
    private var script: Iterator<Int>? = null
    private var wait = 0
    private var finished = false
    private lateinit var world: World

    fun start() {
        Bukkit.getPluginManager().registerEvents(this, plugin)
        manager.spawnObserver = { fakeIds += it.id }
        lines += "PvpBots self-test mode=$mode minecraft=${Bukkit.getMinecraftVersion()} started=${Instant.now()}"
        script = iterator { run() }
        task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { step() }, 1L, 1L)
    }

    /** Stops early (plugin disabling): removes test entities and writes what was measured so far. */
    fun abort() {
        if (finished) return
        lines += "ERROR self-test aborted (plugin disabled) before it finished"
        failed++
        finish(restoreDirector = false, mayShutdown = false)
    }

    private fun step() {
        if (finished) return
        try {
            keepDummiesAlive()
            if (wait > 0) {
                wait--
                return
            }
            val s = script ?: return
            if (s.hasNext()) wait = (s.next() - 1).coerceAtLeast(0) else finish(restoreDirector = true)
        } catch (e: Throwable) {
            plugin.logger.log(java.util.logging.Level.SEVERE, "Self-test crashed", e)
            lines += "ERROR self-test crashed: $e"
            failed++
            finish(restoreDirector = true)
        }
    }

    // ---- the script ----

    private suspend fun SequenceScope<Int>.run() {
        if (mode == Mode.All) ArenaSelfTest.purgeStale(plugin)
        world = setupWorld()
        yield(2)
        when (mode) {
            Mode.All -> {
                trainerChecks()
                allyChecks()
                with(ArenaSelfTest(this@SelfTest, plugin, world)) { run() }
                with(KitSelfTest(this@SelfTest, plugin, world)) { run() }
            }
            Mode.Trainer -> trainerChecks()
            Mode.Ally -> allyChecks()
            is Mode.Load -> loadCheck(mode.bots)
        }
    }

    private suspend fun SequenceScope<Int>.trainerChecks() {
        val arena = area("selftest_arena", 0, 0, Role.TRAINER, Difficulty.EASY, 1)
        val owner = spawnDummy("Owner", 0, 0)
        val bystander = spawnDummy("Near", 1, 1)
        yield(5)
        val key = SessionKey.Trainer(owner.id)
        val t0 = now()
        director.onAreaChange(owner.id, null, arena, nowMs())

        // T1
        val spawnTicks = waitUntil(20) { trainerBot(key) != null }
        val bot = trainerBot(key)
        if (bot == null) {
            check("T1 trainer spawns", false, "no bot within 20 ticks (slots used ${plugin.slots.used()}, queued ${plugin.slots.queued()})")
            listOf("T2 countdown", "T3 fights", "T4 isolation", "T5 leave").forEach { check(it, false, "skipped: no trainer bot") }
        } else {
            val name = bot.player.name
            val inOnline = Bukkit.getOnlinePlayers().any { it.uniqueId == bot.id }
            val viaGetPlayer = Bukkit.getPlayer(bot.id) != null
            val apiBot = PvpBotsApi.isBot(bot.player) && PvpBotsApi.bots().any { it.uniqueId == bot.id }
            check(
                "T1 trainer spawns",
                TRAINER_NAME.matches(name) && !inOnline && !viaGetPlayer && apiBot,
                "$name after $spawnTicks ticks; in getOnlinePlayers=$inOnline, Bukkit.getPlayer!=null=$viaGetPlayer, " +
                    "PvpBotsApi.isBot=$apiBot; " +
                    "${"%.1f".format(bot.player.location.distance(owner.bukkit.location))} blocks from owner",
            )

            // T2: no damage before the fight starts (countdown 3 s = 60 ticks)
            waitUntil(200) { director.fighting(bot.id) }
            val fightTick = now()
            val early = firstDamageTick[owner.id]?.takeIf { it < fightTick }
            check(
                "T2 countdown",
                early == null && fightTick - t0 >= 60,
                "fight started ${fightTick - t0} ticks after entering; owner damage before that: " +
                    (early?.let { "first at +${it - t0} ticks" } ?: "none"),
            )

            // T3 + T4: 600 ticks of fighting next to a bystander
            waitUntil(600) { false }
            val first = firstDamageTick[owner.id]
            check(
                "T3 fights",
                first != null && first - fightTick <= 600,
                (first?.let { "owner first hurt ${it - fightTick} ticks into the fight; " } ?: "owner never hurt in 600 ticks; ") +
                    "owner damage total ${"%.1f".format(damageTaken[owner.id] ?: 0.0)}; " +
                    "bot view/sim distance ${bot.player.viewDistance}/${bot.player.simulationDistance}; " +
                    "owner valid=${owner.bukkit.isValid}; bot: ${trainerBot(key)?.controller?.debugSummary()}",
            )
            val bystanderBefore = damageTaken[bystander.id] ?: 0.0
            val blockedBefore = blockedFromBots[bystander.id] ?: 0
            // Probe: the trainer swings straight at the bystander; the damage policy must cancel it.
            if (manager.isBot(bot.id)) manager.adapter.attack(bot.handle, bystander.bukkit)
            yield(1)
            val bystanderAfter = damageTaken[bystander.id] ?: 0.0
            val blocked = blockedFromBots[bystander.id] ?: 0
            check(
                "T4 isolation",
                bystanderAfter == 0.0 && blocked > blockedBefore,
                "bystander damage over 600 ticks ${"%.1f".format(bystanderBefore)}; after a direct trainer attack ${"%.1f".format(bystanderAfter)}; " +
                    "bot hits on bystander cancelled: $blocked (sweeps during the fight: $blockedBefore)",
            )

            // T5
            director.onAreaChange(owner.id, arena, null, nowMs())
            val goneAtOnce = !manager.isBot(bot.id)
            yield(1)
            val entity = Bukkit.getEntity(bot.id)
            check(
                "T5 leave",
                goneAtOnce && entity == null && plugin.slots.used() == 0,
                "registry removed immediately=$goneAtOnce; entity next tick=${entity != null}; slots used ${plugin.slots.used()}, queued ${plugin.slots.queued()}",
            )
        }
        removeDummy(owner)
        removeDummy(bystander)

        // T6: cap/queue with max-bots=1
        plugin.newDirector(1)
        val a = area("selftest_cap_a", 100, 0, Role.TRAINER, Difficulty.EASY, 1)
        val b = area("selftest_cap_b", 160, 0, Role.TRAINER, Difficulty.EASY, 1)
        val ownerA = spawnDummy("CapA", 100, 0)
        val ownerB = spawnDummy("CapB", 160, 0)
        yield(5)
        val keyA = SessionKey.Trainer(ownerA.id)
        val keyB = SessionKey.Trainer(ownerB.id)
        director.onAreaChange(ownerA.id, null, a, nowMs())
        val gotA = waitUntil(20) { trainerBot(keyA) != null }
        director.onAreaChange(ownerB.id, null, b, nowMs())
        yield(40)
        val bWhileFull = trainerBot(keyB) != null
        val queued = plugin.slots.queued()
        director.onAreaChange(ownerA.id, a, null, nowMs())
        val gotB = waitUntil(20) { trainerBot(keyB) != null }
        val usedAfter = plugin.slots.used()
        check(
            "T6 cap/queue",
            gotA >= 0 && !bWhileFull && queued == 1 && gotB >= 0 && usedAfter == 1,
            "max-bots=1: first owner got a bot after $gotA ticks; second owner had a bot while full=$bWhileFull (queued $queued); " +
                "after the first left the second got one after $gotB ticks; slots used $usedAfter",
        )
        director.onAreaChange(ownerB.id, b, null, nowMs())
        plugin.newDirector(null)
        removeDummy(ownerA)
        removeDummy(ownerB)
    }

    private suspend fun SequenceScope<Int>.allyChecks() {
        val boss = area("selftest_boss", 300, 0, Role.ALLY, Difficulty.MEDIUM, 3, radius = 16)
        val squadKey = SessionKey.Squad(world.name, boss.regionId)
        val p1 = spawnDummy("Fan1", 300, 0)
        val p2 = spawnDummy("Fan2", 301, 2)
        yield(5)
        repeat(4) { i -> spawnZombie(310, -3 + 2 * i) }
        val zombiesAtStart = zombiesAlive()
        val t0 = now()
        director.onAreaChange(p1.id, null, boss, nowMs())
        director.onAreaChange(p2.id, null, boss, nowMs())

        var maxAllies = 0
        var firstThree = -1L
        val names = LinkedHashSet<String>()
        val cleared = waitUntil(1200) {
            val allies = manager.records().filter { it.key == squadKey }
            allies.forEach { names += it.player.name }
            maxAllies = maxOf(maxAllies, allies.size)
            if (firstThree < 0 && allies.size == 3) firstThree = now() - t0
            zombiesAlive() == 0
        }
        val botDamage = (damageFromBots[p1.id] ?: 0.0) + (damageFromBots[p2.id] ?: 0.0)
        val blocked = (blockedFromBots[p1.id] ?: 0) + (blockedFromBots[p2.id] ?: 0)
        val allNamed = names.isNotEmpty() && names.all { ALLY_NAME.matches(it) }
        check(
            "A1 squad",
            zombiesAtStart == 4 && firstThree in 0..SQUAD_SPAWN_TICKS && maxAllies == 3 && allNamed && cleared >= 0 && botDamage == 0.0,
            "3 allies after $firstThree ticks (limit $SQUAD_SPAWN_TICKS; max at once $maxAllies; names $names); zombies $zombiesAtStart -> ${zombiesAlive()} " +
                (if (cleared >= 0) "after $cleared ticks" else "(not cleared in 1200 ticks)") +
                "; damage dealt by bots to dummy players ${"%.1f".format(botDamage)} (cancelled bot hits: $blocked); " +
                "zombie damage to dummies ${"%.1f".format((damageTaken[p1.id] ?: 0.0) + (damageTaken[p2.id] ?: 0.0))}" +
                "; zombie damage events applied $zombieHits (${"%.1f".format(zombieDamage)} hp), cancelled $zombieHitsCancelled; zombies: " +
                zombies.joinToString(" ") { "[hp ${"%.1f".format(it.health)} lived ${it.ticksLived} ${it.location.chunk.loadLevel} valid ${it.isValid}]" } +
                (if (cleared < 0) "; bots: " + manager.records().filter { it.key == squadKey }.joinToString(" | ") { it.player.name + " " + it.controller.debugSummary() } else ""),
        )

        // A2: grace period (leave-grace-seconds, 10 s = 200 ticks)
        director.onAreaChange(p1.id, boss, null, nowMs())
        director.onAreaChange(p2.id, boss, null, nowMs())
        yield(100)
        val at100 = manager.records().count { it.key == squadKey }
        yield(120)
        val at220 = manager.records().count { it.key == squadKey }
        check(
            "A2 grace",
            at100 > 0 && at220 == 0 && plugin.slots.used() == 0,
            "allies at +100 ticks: $at100; at +220 ticks: $at220; slots used ${plugin.slots.used()}",
        )
        removeDummy(p1)
        removeDummy(p2)
        zombies.forEach { it.remove() }
        zombies.clear()
    }

    /** A hostile spot in a load-test ally area: the zombie there is replaced [RESPAWN_TICKS] after it dies. */
    private class MobSpot(val world: World, val x: Int, val z: Int, val pillar: Boolean) {
        var zombie: Zombie? = null
        var deadFor = 0
    }

    /**
     * `load:N`: N/2 trainers (HARD) fighting dummy owners in their own arenas, spread over 3 worlds, plus the other
     * N/2 as ally squads of 10 (HARD) in boss areas with zombies that keep coming back (one stands on a pillar the
     * allies cannot walk up, so they also use the bow). Measures the spawn burst, then [LOAD_TICKS] ticks of steady
     * state: summed [BotController.tick] time per tick (bot work) and server MSPT ([ServerTickEndEvent]).
     */
    private suspend fun SequenceScope<Int>.loadCheck(n: Int) {
        val worlds = listOf(world, setupWorld(WORLD + "_2"), setupWorld(WORLD + "_3"))
        plugin.newDirector(n)
        val trainers = n / 2
        val allyTotal = n - trainers
        val entries = ArrayList<Pair<BotHandle, AreaRef>>()
        for (i in 0 until trainers) {
            val w = worlds[i % worlds.size]
            val j = i / worlds.size
            val x = 1000 + (j % 6) * 40
            val z = (j / 6) * 40
            entries += spawnDummy("L", x, z, w) to area("selftest_load_t$i", x, z, Role.TRAINER, Difficulty.HARD, 1, w = w)
        }
        val spots = ArrayList<MobSpot>()
        var a = 0
        var allyLeft = allyTotal
        while (allyLeft > 0) {
            val w = worlds[a % worlds.size]
            val x = 2000 + (a / worlds.size) * 80
            val count = minOf(10, allyLeft)
            allyLeft -= count
            entries += spawnDummy("A", x, 0, w) to area("selftest_load_a$a", x, 0, Role.ALLY, Difficulty.HARD, count, radius = 16, w = w)
            spots += MobSpot(w, x + 10, -6, false)
            spots += MobSpot(w, x - 10, 6, false)
            spots += MobSpot(w, x + 6, 10, false)
            // 5-high stone pillar: the zombie on top is unreachable on foot and above HIGH_TARGET, so allies shoot it.
            val g = groundY(x + 9, 8, w) // the flat floor next to it, so re-running on a kept world is idempotent
            for (y in g until g + 5) w.getBlockAt(x + 8, y, 8).type = org.bukkit.Material.STONE
            spots += MobSpot(w, x + 8, 8, true)
            a++
        }
        spots.forEach { respawn(it) }
        yield(5)

        // Spawn burst: every session starts in this one tick; the director spawns at most bot.spawns-per-tick per tick.
        loadRecording = true
        BotProfiler.reset()
        BotProfiler.enabled = true
        val spawnNanos0 = manager.spawnNanos
        val wall0 = System.nanoTime()
        entries.forEach { (o, ar) -> director.onAreaChange(o.id, null, ar, nowMs()) }
        val burstMs = (System.nanoTime() - wall0) / 1e6
        val spawnedAtOnce = manager.count()
        val spikeBot = ArrayList<Double>()
        val spawnPerTick = ArrayList<Double>()
        var lastSpawnNanos = manager.spawnNanos
        var allSpawnedAfter = -1
        for (i in 1..SPIKE_TICKS) {
            yield(1)
            tendMobs(spots)
            spikeBot += manager.lastTickNanos / 1e6
            val sn = manager.spawnNanos
            if (sn != lastSpawnNanos) spawnPerTick += (sn - lastSpawnNanos) / 1e6
            lastSpawnNanos = sn
            if (allSpawnedAfter < 0 && manager.count() >= n) allSpawnedAfter = i
        }
        val spawnMs = (manager.spawnNanos - spawnNanos0) / 1e6
        BotProfiler.enabled = false
        val spawnParts = BotProfiler.SpawnPart.entries.joinToString(" ") { "${it.name.lowercase()}=${"%.1f".format(BotProfiler.spawnNanos[it.ordinal] / 1e6)}" }
        val spikeMspt = msptSamples.toList()
        val spikeChunksBots = chunkLoadsBots
        val spikeChunksDummies = chunkLoadsOthers

        // Steady state.
        val liveAtStart = manager.count()
        msptSamples.clear()
        chunkLoadsBots = 0
        chunkLoadsOthers = 0
        BotProfiler.reset()
        BotProfiler.enabled = true
        val botMs = ArrayList<Double>(LOAD_TICKS)
        var killed = 0
        repeat(LOAD_TICKS) {
            yield(1)
            botMs += manager.lastTickNanos / 1e6
            killed += tendMobs(spots)
        }
        BotProfiler.enabled = false
        loadRecording = false
        val mspt = msptSamples.toList()
        val liveAtEnd = manager.count()
        val trainersEnd = manager.records().count { it.role == Role.TRAINER }

        val avg = botMs.average()
        check(
            "L1 load $n",
            liveAtStart == n && avg <= LOAD_BUDGET_MS,
            "bots live $liveAtStart/$n at start, $liveAtEnd at end ($trainersEnd trainers); bot work per tick over $LOAD_TICKS ticks: " +
                "${stats(botMs)} (budget avg <= $LOAD_BUDGET_MS ms); server MSPT ${stats(mspt)} (${mspt.size} ticks)",
        )
        info(
            "L1 spawn burst: ${entries.size} sessions started in one tick (director calls ${"%.1f".format(burstMs)} ms, " +
                "$spawnedAtOnce bots spawned inside those calls); spawns-per-tick ${manager.config.settings.spawnsPerTick}: all $n bots live after " +
                "$allSpawnedAfter ticks; BotManager.spawn total ${"%.1f".format(spawnMs)} ms ($spawnParts) over ${spawnPerTick.size} ticks, " +
                "per tick ${stats(spawnPerTick)}; next $SPIKE_TICKS ticks: server MSPT ${stats(spikeMspt)}, " +
                "first 5 ticks ${spikeMspt.take(5).joinToString(" ") { "%.1f".format(it) }}; bot work ${stats(spikeBot)}; " +
                "chunk sends (PlayerChunkLoadEvent) to bots $spikeChunksBots, to dummies $spikeChunksDummies",
        )
        val perTick = { v: Long -> "%.3f".format(v / 1e6 / LOAD_TICKS) }
        info(
            "L1 phases (ms/tick, all bots; scan and reachable are inside refresh): " +
                BotProfiler.Phase.entries.joinToString(" ") { "${it.name.lowercase()}=${perTick(BotProfiler.nanos[it.ordinal])}" } +
                "; calls per tick: " +
                BotProfiler.Counter.entries.joinToString(" ") { "${it.name.lowercase()}=${"%.2f".format(BotProfiler.counts[it.ordinal].toDouble() / LOAD_TICKS)}" } +
                "; steady chunk sends to bots $chunkLoadsBots, to dummies $chunkLoadsOthers; zombies killed $killed",
        )
        entries.forEach { (o, ar) -> director.onAreaChange(o.id, ar, null, nowMs()) }
        plugin.newDirector(null)
        entries.forEach { removeDummy(it.first) }
    }

    /** Replaces load-test zombies [RESPAWN_TICKS] after they die. Returns how many were found dead this tick. */
    private fun tendMobs(spots: List<MobSpot>): Int {
        var died = 0
        for (s in spots) {
            val z = s.zombie
            if (z != null && z.isValid && !z.isDead) continue
            if (s.deadFor == 0) died++
            if (++s.deadFor >= RESPAWN_TICKS) {
                z?.let { zombies.remove(it) }
                respawn(s)
            }
        }
        return died
    }

    private fun respawn(s: MobSpot) {
        val z = spawnZombie(s.x, s.z, s.world)
        if (s.pillar) z.setAI(false)
        s.zombie = z
        s.deadFor = 0
    }

    private fun stats(v: List<Double>): String {
        if (v.isEmpty()) return "no samples"
        val sorted = v.sorted()
        val p95 = sorted[((sorted.size - 1) * 0.95).toInt()]
        return "avg ${"%.3f".format(v.average())} / p95 ${"%.3f".format(p95)} / max ${"%.3f".format(sorted.last())} ms"
    }

    private fun info(text: String) {
        val line = "INFO $text"
        lines += line
        report(line)
    }

    // ---- helpers ----

    private fun now(): Long = Bukkit.getCurrentTick().toLong()

    internal fun nowMs(): Long = manager.nowMs()

    internal fun trainerBot(key: SessionKey): BotRecord? = manager.records().firstOrNull { it.key == key }

    internal fun check(name: String, ok: Boolean, measured: String) {
        if (ok) passed++ else failed++
        val line = "${if (ok) "PASS" else "FAIL"} $name: $measured"
        lines += line
        report(line)
    }

    private fun setupWorld(name: String = WORLD): World {
        val w = Bukkit.getWorld(name) ?: WorldCreator(name)
            .type(WorldType.FLAT)
            .generateStructures(false)
            .generatorSettings("""{"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:stone","height":3}],"biome":"minecraft:plains"}""")
            .createWorld() ?: error("could not create world $name")
        w.setGameRule(GameRules.SPAWN_MOBS, false)
        w.setGameRule(GameRules.ADVANCE_TIME, false)
        w.setGameRule(GameRules.ADVANCE_WEATHER, false)
        w.time = 6000
        w.setStorm(false)
        w.difficulty = WorldDifficulty.NORMAL
        w.setGameRule(GameRules.PVP, true)
        w.isAutoSave = false
        w.entities.filter { it !is Player }.forEach { it.remove() }
        return w
    }

    private fun groundY(x: Int, z: Int, w: World = world): Int = w.getHighestBlockYAt(x, z) + 1

    private fun area(id: String, cx: Int, cz: Int, role: Role, d: Difficulty, count: Int, radius: Int = 14, w: World = world): AreaRef {
        val y = groundY(cx, cz, w)
        return AreaRef(w.name, id, role, d, count, Bounds(BlockPos(cx - radius, y - 4, cz - radius), BlockPos(cx + radius, y + 12, cz + radius)))
    }

    /** [id]: reuse a removed dummy's UUID (stands in for a respawn, which dummies cannot do). */
    internal fun spawnDummy(label: String, x: Int, z: Int, w: World = world, id: UUID = UUID.randomUUID()): BotHandle {
        val loc = Location(w, x + 0.5, groundY(x, z, w).toDouble(), z + 0.5)
        val h = manager.adapter.spawn(BotSpec("St${label}_${id.toString().take(4)}", id, null, null), loc)
        dummies += h
        fakeIds += id
        if (h.bukkit.gameMode != GameMode.SURVIVAL) h.bukkit.gameMode = GameMode.SURVIVAL
        return h
    }

    internal fun removeDummy(h: BotHandle) {
        manager.adapter.remove(h)
        dummies.remove(h)
    }

    private fun spawnZombie(x: Int, z: Int, w: World = world): Zombie {
        val loc = Location(w, x + 0.5, groundY(x, z, w).toDouble(), z + 0.5)
        val zombie = w.spawn(loc, Zombie::class.java, false) { zb ->
            zb.setAdult()
            zb.setShouldBurnInDay(false)
            zb.equipment.clear()
            zb.canPickupItems = false
            zb.isPersistent = true
            zb.removeWhenFarAway = false
            zb.getAttribute(Attribute.SPAWN_REINFORCEMENTS)?.baseValue = 0.0
        }
        zombies += zombie
        return zombie
    }

    private fun zombiesAlive(): Int = zombies.count { it.isValid && !it.isDead }

    /** Dummies must survive the whole test: heal them before a hit could kill them. */
    private fun keepDummiesAlive() {
        for (h in dummies) {
            val p = h.bukkit
            if (!p.isDead && p.health < 10.0) p.health = p.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
        }
    }

    private fun botDamager(e: Entity): Boolean {
        val source = (e as? Projectile)?.shooter as? Entity ?: e
        return manager.isBot(source.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onTickEnd(event: ServerTickEndEvent) {
        if (loadRecording) msptSamples += event.tickDuration
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onChunkSend(event: PlayerChunkLoadEvent) {
        if (!loadRecording) return
        if (manager.isBot(event.player.uniqueId)) chunkLoadsBots++ else chunkLoadsOthers++
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onDamage(event: EntityDamageEvent) {
        val id = event.entity.uniqueId
        if (zombies.any { it.uniqueId == id }) {
            if (event.isCancelled) zombieHitsCancelled++ else { zombieHits++; zombieDamage += event.finalDamage }
            return
        }
        if (dummies.none { it.id == id }) return
        val byBot = (event as? EntityDamageByEntityEvent)?.let { botDamager(it.damager) } == true
        if (event.isCancelled) {
            if (byBot) blockedFromBots.merge(id, 1, Int::plus)
            return
        }
        if (event.finalDamage <= 0.0) return
        damageTaken.merge(id, event.finalDamage, Double::plus)
        firstDamageTick.putIfAbsent(id, now())
        if (byBot) damageFromBots.merge(id, event.finalDamage, Double::plus)
    }

    private fun finish(restoreDirector: Boolean, mayShutdown: Boolean = true) {
        if (finished) return
        finished = true
        task?.cancel()
        BotProfiler.enabled = false
        loadRecording = false
        HandlerList.unregisterAll(this)
        manager.spawnObserver = null
        zombies.forEach { runCatching { it.remove() } }
        zombies.clear()
        for (h in dummies.toList()) {
            // A check that stopped half-way may leave a dummy wearing an arena kit: give its test items back, drop the file.
            runCatching { plugin.kitArenas.restore(h.bukkit) }
            runCatching { plugin.kitArenas.forget(h.id); plugin.kitArenas.stashFile(h.id).delete() }
            runCatching { manager.adapter.remove(h) }
        }
        dummies.clear()
        if (restoreDirector) runCatching { plugin.newDirector(null) }
        if (mayShutdown) unloadWorlds()

        lines += "DONE $passed/${passed + failed} checks passed"
        report(lines.last())
        val dir = plugin.dataFolder.apply { mkdirs() }
        File(dir, RESULT_FILE).writeText(lines.joinToString("\n", postfix = "\n"))
        File(dir, BOTS_FILE).writeText(fakeIds.joinToString("\n", postfix = "\n"))
        plugin.logger.info("Self-test finished: $passed/${passed + failed} passed; results in ${File(dir, RESULT_FILE)}")
        onFinished()
        if (shutdownAfter && mayShutdown) Bukkit.getScheduler().runTask(plugin, Runnable { Bukkit.shutdown() })
    }

    /** Unloads (without saving) and deletes every `pvpbots_selftest*` world, so a live server is left as it was. */
    private fun unloadWorlds() {
        val results = Bukkit.getWorlds().filter { it.name.startsWith(WORLD) }.map { w ->
            val name = w.name
            val folder = w.worldFolder
            val unloaded = runCatching { Bukkit.unloadWorld(w, false) }.getOrDefault(false)
            val deleted = unloaded && runCatching { folder.deleteRecursively() }.getOrDefault(false)
            "$name unloaded=$unloaded deleted=$deleted"
        }
        if (results.isNotEmpty()) info("test worlds: ${results.joinToString("; ")}")
    }

    companion object {
        const val WORLD = "pvpbots_selftest"
        const val RESULT_FILE = "selftest-result.txt"
        const val BOTS_FILE = "selftest-bots.txt"
        private const val LOAD_TICKS = 1200
        private const val SPIKE_TICKS = 100
        /** A squad of 3 is spawned 2 per tick (bot.spawns-per-tick default); allow a few ticks of slack. */
        private const val SQUAD_SPAWN_TICKS = 10
        private const val RESPAWN_TICKS = 20
        private const val LOAD_BUDGET_MS = 10.0
        private val TRAINER_NAME = Regex("^Trainer_[0-9a-f]{4}$")
        private val ALLY_NAME = Regex("^Ally_[0-9a-f]{4}$")
    }
}

/** Yields one tick at a time until [cond] holds; returns the ticks waited, or -1 after [maxTicks]. */
internal suspend fun SequenceScope<Int>.waitUntil(maxTicks: Int, cond: () -> Boolean): Int {
    var t = 0
    while (!cond()) {
        if (t >= maxTicks) return -1
        yield(1)
        t++
    }
    return t
}
