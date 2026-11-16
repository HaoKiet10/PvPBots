package pvpbots.bukkit

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
 * Scripted in-server checks (T1-T6 trainer, A1-A2 ally; D1 is checked by the Gradle `selfTest` task after the
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
        world = setupWorld()
        yield(2)
        when (mode) {
            Mode.All -> { trainerChecks(); allyChecks() }
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
            zombiesAtStart == 4 && firstThree >= 0 && maxAllies == 3 && allNamed && cleared >= 0 && botDamage == 0.0,
            "3 allies after $firstThree ticks (max at once $maxAllies; names $names); zombies $zombiesAtStart -> ${zombiesAlive()} " +
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

    private suspend fun SequenceScope<Int>.loadCheck(n: Int) {
        plugin.newDirector(n)
        val owners = ArrayList<Pair<BotHandle, AreaRef>>()
        for (i in 0 until n) {
            val x = 1000 + (i % 10) * 40
            val z = (i / 10) * 40
            val owner = spawnDummy("Load", x, z)
            owners += owner to area("selftest_load_$i", x, z, Role.TRAINER, Difficulty.HARD, 1)
        }
        yield(5)
        owners.forEach { (o, a) -> director.onAreaChange(o.id, null, a, nowMs()) }
        yield(200)
        val live = manager.count()
        val ms = manager.averageTickMs()
        check("L1 load $n", live == n, "$live/$n trainer bots live after 200 ticks; average bot tick ${"%.3f".format(ms)} ms (all bots, last 100 ticks)")
        owners.forEach { (o, a) -> director.onAreaChange(o.id, a, null, nowMs()) }
        plugin.newDirector(null)
        owners.forEach { removeDummy(it.first) }
    }

    // ---- helpers ----

    private suspend fun SequenceScope<Int>.waitUntil(maxTicks: Int, cond: () -> Boolean): Int {
        var t = 0
        while (!cond()) {
            if (t >= maxTicks) return -1
            yield(1)
            t++
        }
        return t
    }

    private fun now(): Long = Bukkit.getCurrentTick().toLong()

    private fun nowMs(): Long = manager.nowMs()

    private fun trainerBot(key: SessionKey): BotRecord? = manager.records().firstOrNull { it.key == key }

    private fun check(name: String, ok: Boolean, measured: String) {
        if (ok) passed++ else failed++
        val line = "${if (ok) "PASS" else "FAIL"} $name: $measured"
        lines += line
        report(line)
    }

    private fun setupWorld(): World {
        val w = Bukkit.getWorld(WORLD) ?: WorldCreator(WORLD)
            .type(WorldType.FLAT)
            .generateStructures(false)
            .generatorSettings("""{"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:stone","height":3}],"biome":"minecraft:plains"}""")
            .createWorld() ?: error("could not create world $WORLD")
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

    private fun groundY(x: Int, z: Int): Int = world.getHighestBlockYAt(x, z) + 1

    private fun area(id: String, cx: Int, cz: Int, role: Role, d: Difficulty, count: Int, radius: Int = 14): AreaRef {
        val y = groundY(cx, cz)
        return AreaRef(world.name, id, role, d, count, Bounds(BlockPos(cx - radius, y - 4, cz - radius), BlockPos(cx + radius, y + 12, cz + radius)))
    }

    private fun spawnDummy(label: String, x: Int, z: Int): BotHandle {
        val loc = Location(world, x + 0.5, groundY(x, z).toDouble(), z + 0.5)
        val id = UUID.randomUUID()
        val h = manager.adapter.spawn(BotSpec("St${label}_${id.toString().take(4)}", id, null, null), loc)
        dummies += h
        fakeIds += id
        if (h.bukkit.gameMode != GameMode.SURVIVAL) h.bukkit.gameMode = GameMode.SURVIVAL
        return h
    }

    private fun removeDummy(h: BotHandle) {
        manager.adapter.remove(h)
        dummies.remove(h)
    }

    private fun spawnZombie(x: Int, z: Int) {
        val loc = Location(world, x + 0.5, groundY(x, z).toDouble(), z + 0.5)
        zombies += world.spawn(loc, Zombie::class.java, false) { zb ->
            zb.setAdult()
            zb.setShouldBurnInDay(false)
            zb.equipment.clear()
            zb.canPickupItems = false
            zb.isPersistent = true
            zb.removeWhenFarAway = false
            zb.getAttribute(Attribute.SPAWN_REINFORCEMENTS)?.baseValue = 0.0
        }
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
        HandlerList.unregisterAll(this)
        manager.spawnObserver = null
        zombies.forEach { runCatching { it.remove() } }
        zombies.clear()
        for (h in dummies.toList()) runCatching { manager.adapter.remove(h) }
        dummies.clear()
        if (restoreDirector) runCatching { plugin.newDirector(null) }

        lines += "DONE $passed/${passed + failed} checks passed"
        report(lines.last())
        val dir = plugin.dataFolder.apply { mkdirs() }
        File(dir, RESULT_FILE).writeText(lines.joinToString("\n", postfix = "\n"))
        File(dir, BOTS_FILE).writeText(fakeIds.joinToString("\n", postfix = "\n"))
        plugin.logger.info("Self-test finished: $passed/${passed + failed} passed; results in ${File(dir, RESULT_FILE)}")
        onFinished()
        if (shutdownAfter && mayShutdown) Bukkit.getScheduler().runTask(plugin, Runnable { Bukkit.shutdown() })
    }

    companion object {
        const val WORLD = "pvpbots_selftest"
        const val RESULT_FILE = "selftest-result.txt"
        const val BOTS_FILE = "selftest-bots.txt"
        private val TRAINER_NAME = Regex("^Trainer_[0-9a-f]{4}$")
        private val ALLY_NAME = Regex("^Ally_[0-9a-f]{4}$")
    }
}
