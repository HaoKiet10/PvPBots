package spike

import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionHand
import org.bukkit.Bukkit
import org.bukkit.Difficulty
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.WorldCreator
import org.bukkit.WorldType
import org.bukkit.craftbukkit.entity.CraftEntity
import org.bukkit.craftbukkit.entity.CraftPlayer
import org.bukkit.entity.Zombie
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.util.EnumSet
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** One check = a small per-tick state machine. step(t) returns true when finished. */
abstract class Check(val id: String, val origin: Location) {
    var result: String = "FAIL (did not finish)"
    var passed = false
    val bots = mutableListOf<FakeBot>()
    val mobs = mutableListOf<org.bukkit.entity.Entity>()
    open val keepAlive = false
    abstract fun step(t: Int): Boolean
    fun pass(msg: String) { passed = true; result = "PASS $msg" }
    fun fail(msg: String) { passed = false; result = "FAIL $msg" }
    fun verdict(ok: Boolean, msg: String) = if (ok) pass(msg) else fail(msg)
    fun bot(name: String, at: Location): FakeBot = FakeBot.spawn(at, name).also { bots += it }
    fun zombie(at: Location, ai: Boolean): Zombie =
        at.world.spawn(at, Zombie::class.java) { z ->
            z.setAdult(); z.setAI(ai); z.isSilent = true; z.removeWhenFarAway = false
        }.also { mobs += it }
    open fun cleanup() {
        if (!keepAlive) bots.forEach { if (!it.isRemoved) FakeBot.remove(it) }
        mobs.forEach { it.remove() }
    }
}

fun f(d: Double) = "%.3f".format(d)

class SpikePlugin : JavaPlugin(), Listener {
    private val log = mutableListOf<String>()
    private lateinit var world: World
    private var manualBot: FakeBot? = null
    private val manual = System.getProperty("spike.manual") == "true"

    // Every bot name the spike may create (for the Q6 file scan).
    private val botNames = listOf("Q1aBot", "Q1bWalk", "Q1bSprint", "Q1cBot", "Q1dBot", "Q2Bot", "Q3aBot",
        "Q3bBot", "Q3cBot", "Q3dBot", "Q4Bot", "SpikeBot")

    private fun out(line: String) { logger.info(line); log += line }

    override fun onEnable() {
        dataFolder.mkdirs()
        server.pluginManager.registerEvents(this, this)
        out("SPIKE server=${Bukkit.getVersion()} mc=${Bukkit.getMinecraftVersion()} java=${System.getProperty("java.version")}")
        q6FromPreviousBoot()
        // Wait until the server has fully started, then create the flat world.
        server.scheduler.runTask(this, Runnable { setupWorld() })
    }

    /** Q6: the previous boot ended with live bots in the world; did shutdown write any files for them? */
    private fun q6FromPreviousBoot() {
        val marker = File(dataFolder, "previous-boot-done")
        if (!marker.exists()) {
            out("Q6 PENDING first boot (no previous run to inspect; boot again to evaluate)")
            return
        }
        val root = server.worldContainer.canonicalFile
        val uuids = botNames.map { FakeBot.uuidFor(it).toString() }
        val hits = root.walkTopDown()
            .onEnter { it.name != "plugins" && it.name != "libraries" && it.name != "cache" && it.name != "versions" }
            .filter { it.isFile && uuids.any { u -> it.name.contains(u) } }
            .map { it.relativeTo(root).path }.toList()
        val usercache = File(root, "usercache.json").takeIf { it.exists() }?.readText() ?: ""
        val inUsercache = uuids.filter { usercache.contains(it) }
        val msg = "files-for-bot-uuids=${hits.size} $hits usercache-entries=${inUsercache.size}"
        out(if (hits.isEmpty()) "Q6 PASS $msg" else "Q6 FAIL $msg")
    }

    private fun setupWorld() {
        world = WorldCreator("spike_flat").type(WorldType.FLAT).generateStructures(false).createWorld()!!
        world.difficulty = Difficulty.NORMAL
        world.time = 18000
        world.isAutoSave = false
        out("world spike_flat created; waiting 100 ticks")
        server.scheduler.runTaskLater(this, Runnable { runChecks() }, 100L)
    }

    @EventHandler
    fun onSpawn(e: CreatureSpawnEvent) {
        if (e.spawnReason != CreatureSpawnEvent.SpawnReason.CUSTOM && e.spawnReason != CreatureSpawnEvent.SpawnReason.DEFAULT) e.isCancelled = true
    }

    @EventHandler
    fun onJoin(e: PlayerJoinEvent) {
        val bot = manualBot ?: return
        // Send player info WITHOUT UPDATE_LISTED -> client knows the profile (skin) but does not list it in tab.
        val pkt = ClientboundPlayerInfoUpdatePacket(
            EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER, ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE),
            listOf(bot),
        )
        (e.player as CraftPlayer).handle.connection.send(pkt)
        e.player.sendMessage("[Spike] bot 'SpikeBot' stands next to world spawn; it should NOT be in your tab list.")
    }

    private fun ground(x: Int, z: Int): Double = world.getHighestBlockYAt(x, z) + 1.0

    private fun loc(ox: Int, dx: Double, dy: Double, dz: Double, yaw: Float = -90f): Location {
        val g = ground(ox, 0)
        return Location(world, ox + 0.5 + dx, g + dy, 0.5 + dz, yaw, 0f)
    }

    private fun runChecks() {
        val checks = mutableListOf<Check>()
        var area = 0
        fun o() = (area++ + 1) * 64

        // Q1a gravity
        checks += object : Check("Q1a gravity", loc(o(), 0.0, 0.0, 0.0)) {
            lateinit var b: FakeBot; var g = 0.0
            override fun step(t: Int): Boolean {
                if (t == 0) { g = origin.y; b = bot("Q1aBot", origin.clone().add(0.0, 3.0, 0.0)); return false }
                if (t < 40) return false
                verdict(abs(b.y - g) < 1e-6 && b.onGround(), "groundY=${f(g)} feetY=${f(b.y)} onGround=${b.onGround()} startY=${f(g + 3)} botTickCalls=${b.tickCalls}")
                return true
            }
        }
        // Q1b walk (+ sprint, informational)
        checks += object : Check("Q1b walk", loc(o(), 0.0, 0.0, 0.0)) {
            lateinit var w: FakeBot; lateinit var s: FakeBot
            var sw = origin; var ss = origin
            override fun step(t: Int): Boolean {
                if (t == 0) { w = bot("Q1bWalk", origin); s = bot("Q1bSprint", origin.clone().add(0.0, 0.0, 4.0)); return false }
                if (t == 5) { sw = w.bukkitEntity.location; ss = s.bukkitEntity.location; w.input.forward = 1f; s.input.forward = 1f; s.input.sprint = true }
                if (t < 45) return false
                val yaw = Math.toRadians(-90.0)
                val dx = -sin(yaw); val dz = cos(yaw)
                val dw = (w.x - sw.x) * dx + (w.z - sw.z) * dz
                val ds = (s.x - ss.x) * dx + (s.z - ss.z) * dz
                verdict(dw >= 4.0, "walk40t=${f(dw)} blocks along look; sprint40t=${f(ds)} (info); lateralDrift=${f(abs(w.z - sw.z))}")
                return true
            }
        }
        // Q1c collision
        checks += object : Check("Q1c collision", loc(o(), 0.0, 0.0, 0.0)) {
            lateinit var b: FakeBot; var wallX = 0
            override fun step(t: Int): Boolean {
                if (t == 0) {
                    wallX = origin.blockX + 4
                    for (z in -3..3) for (y in 0..1) world.getBlockAt(wallX, origin.blockY + y, origin.blockZ + z).type = Material.STONE
                    b = bot("Q1cBot", origin); b.input.forward = 1f; return false
                }
                if (t < 40) return false
                val gap = wallX - (b.x + 0.3)
                verdict(gap >= -1e-6 && gap <= 0.5 && b.x < wallX, "wallFaceX=$wallX botMaxX=${f(b.x + 0.3)} gap=${f(gap)} horizontalCollision=${b.horizontalCollision}")
                return true
            }
        }
        // Q1d step up with jump
        checks += object : Check("Q1d step/jump", loc(o(), 0.0, 0.0, 0.0)) {
            lateinit var b: FakeBot; var g = 0.0; var noJumpY = 0.0
            override fun step(t: Int): Boolean {
                if (t == 0) {
                    g = origin.y
                    for (x in 3..14) for (z in -2..6) world.getBlockAt(origin.blockX + x, origin.blockY, origin.blockZ + z).type = Material.STONE
                    b = bot("Q1dBot", origin); b.input.forward = 1f; b.input.jump = true
                    return false
                }
                if (t == 30) { b.input.forward = 0f; b.input.jump = false }
                if (t < 45) return false
                val onTop = abs(b.y - (g + 1)) < 1e-6 && b.onGround()
                verdict(onTop, "groundY=${f(g)} stepTopY=${f(g + 1)} finalY=${f(b.y)} finalX=${f(b.x - origin.x)} onGround=${b.onGround()}")
                return true
            }
        }
        // Q2 knockback
        checks += object : Check("Q2 knockback", loc(o(), 0.0, 0.0, 0.0)) {
            lateinit var b: FakeBot; lateinit var z: Zombie
            var p0 = origin; var v1 = 0.0; var h0 = 0.0
            override fun step(t: Int): Boolean {
                if (t == 0) { b = bot("Q2Bot", origin); z = zombie(origin.clone().add(-1.2, 0.0, 0.0).apply { yaw = -90f }, false); return false }
                if (t == 5) { p0 = b.bukkitEntity.location; h0 = b.health.toDouble(); z.attack(b.bukkitEntity); return false }
                if (t == 6) { v1 = b.deltaMovement.horizontalDistance(); return false }
                if (t < 15) return false
                val shift = sqrt((b.x - p0.x) * (b.x - p0.x) + (b.z - p0.z) * (b.z - p0.z))
                verdict(v1 > 0 && shift > 0.1, "hVelNextTick=${f(v1)} posShift10t=${f(shift)} dx=${f(b.x - p0.x)} health ${f(h0)}->${f(b.health.toDouble())}")
                return true
            }
        }
        // Q3a attack
        checks += object : Check("Q3a attack", loc(o(), 0.0, 0.0, 0.0)) {
            lateinit var b: FakeBot; lateinit var z: Zombie; var h0 = 0.0; var scale = 0f
            override fun step(t: Int): Boolean {
                if (t == 0) {
                    b = bot("Q3aBot", origin); b.bukkitEntity.inventory.setItemInMainHand(ItemStack(Material.IRON_SWORD))
                    z = zombie(origin.clone().add(2.0, 0.0, 0.0), false); return false
                }
                if (t == 30) {
                    h0 = z.health; scale = b.getAttackStrengthScale(0.5f)
                    b.attack((z as CraftEntity).handle); return false
                }
                if (t < 31) return false
                verdict(z.health < h0, "cooldownScale=${f(scale.toDouble())} zombieHealth ${f(h0)}->${f(z.health)}")
                return true
            }
        }
        // Q3b eat golden apple
        checks += object : Check("Q3b eat", loc(o(), 0.0, 0.0, 0.0)) {
            lateinit var b: FakeBot; var using = false; var res = ""
            override fun step(t: Int): Boolean {
                if (t == 0) {
                    b = bot("Q3bBot", origin)
                    val p = b.bukkitEntity
                    p.foodLevel = 10; p.saturation = 0f
                    p.inventory.setItemInMainHand(ItemStack(Material.GOLDEN_APPLE))
                    return false
                }
                if (t == 5) {
                    res = b.gameMode.useItem(b, b.level(), b.getItemInHand(InteractionHand.MAIN_HAND), InteractionHand.MAIN_HAND).toString()
                    using = b.isUsingItem; return false
                }
                if (t < 45) return false
                val p = b.bukkitEntity
                verdict(p.foodLevel > 10 && p.absorptionAmount > 0,
                    "useItemResult=$res isUsingItemAfterUse=$using food 10->${p.foodLevel} absorption 0->${f(p.absorptionAmount)} applesLeft=${p.inventory.itemInMainHand.amount}")
                return true
            }
        }
        // Q3c shield
        checks += object : Check("Q3c shield", loc(o(), 0.0, 0.0, 0.0)) {
            lateinit var b: FakeBot; var res = ""
            override fun step(t: Int): Boolean {
                if (t == 0) { b = bot("Q3cBot", origin); b.bukkitEntity.inventory.setItemInOffHand(ItemStack(Material.SHIELD)); return false }
                if (t == 5) { res = b.gameMode.useItem(b, b.level(), b.getItemInHand(InteractionHand.OFF_HAND), InteractionHand.OFF_HAND).toString(); return false }
                if (t < 15) return false
                verdict(b.isBlocking, "useItemResult=$res isUsingItem=${b.isUsingItem} usedHand=${b.usedItemHand} ticksUsing=${b.ticksUsingItem} isBlocking=${b.isBlocking} bukkitIsBlocking=${b.bukkitEntity.isBlocking}")
                return true
            }
        }
        // Q3d totem
        checks += object : Check("Q3d totem", loc(o(), 0.0, 0.0, 0.0)) {
            lateinit var b: FakeBot
            override fun step(t: Int): Boolean {
                if (t == 0) { b = bot("Q3dBot", origin); b.bukkitEntity.inventory.setItemInOffHand(ItemStack(Material.TOTEM_OF_UNDYING)); return false }
                if (t == 5) { b.bukkitEntity.damage(1000.0); return false }
                if (t < 7) return false
                val p = b.bukkitEntity
                verdict(b.isAlive && !b.isRemoved && p.health > 0, "alive=${b.isAlive} health=${f(p.health)} offhand=${p.inventory.itemInOffHand.type} effects=${p.activePotionEffects.map { it.type.key.key }}")
                return true
            }
        }
        // Q4 mob targeting + Q5 online list (bot kept alive into shutdown for Q6)
        checks += object : Check("Q4 mob targeting", loc(o(), 0.0, 0.0, 0.0)) {
            lateinit var b: FakeBot; lateinit var z: Zombie; var targetedAt = -1
            override val keepAlive = true
            override fun step(t: Int): Boolean {
                if (t == 0) { b = bot("Q4Bot", origin); z = zombie(origin.clone().add(6.0, 0.0, 0.0), true); return false }
                if (targetedAt < 0 && z.target?.uniqueId == b.uuid) targetedAt = t
                if (targetedAt < 0 && t < 100) return false
                verdict(targetedAt in 0..100, "zombieDistance=6 targetedAfterTicks=$targetedAt target=${z.target?.name}")
                return true
            }
        }

        val it = checks.iterator()
        var cur: Check? = null
        var t = 0
        server.scheduler.runTaskTimer(this, { task ->
            try {
                if (cur == null) {
                    if (!it.hasNext()) { task.cancel(); finish(checks); return@runTaskTimer }
                    cur = it.next(); t = 0
                    val c = cur!!
                    for (cx in -1..1) for (cz in -1..1) world.getChunkAt((c.origin.blockX shr 4) + cx, cz).addPluginChunkTicket(this)
                }
                val c = cur!!
                val done = try { c.step(t++) } catch (e: Throwable) { c.fail("exception: $e"); logger.log(java.util.logging.Level.SEVERE, "check ${c.id}", e); true }
                if (done) {
                    out("${c.id.substringBefore(' ')} ${c.result}   [${c.id}]")
                    if (c.id.startsWith("Q4")) q5(c.bots.first())
                    c.cleanup(); cur = null
                }
            } catch (e: Throwable) {
                logger.log(java.util.logging.Level.SEVERE, "runner", e); task.cancel(); finish(checks)
            }
        }, 1L, 1L)
    }

    private fun q5(b: FakeBot) {
        val inOnline = Bukkit.getOnlinePlayers().any { it.uniqueId == b.uuid }
        val inPlayerList = MinecraftServer.getServer().playerList.players.contains(b)
        val inWorldPlayers = world.players.any { it.uniqueId == b.uuid }
        val inLevelPlayers = (b.level() as ServerLevel).players().contains(b)
        val getPlayer = Bukkit.getPlayer(b.uuid) != null
        val q4 = log.lastOrNull { it.startsWith("Q4") } ?: "Q4 ?"
        val variantA = !inOnline && !inPlayerList && inWorldPlayers
        out("Q5 ${if (variantA) "PASS variant=A" else "FAIL variant A not achieved"} inOnlinePlayers=$inOnline onlineCount=${Bukkit.getOnlinePlayers().size} inPlayerList=$inPlayerList inWorld.getPlayers=$inWorldPlayers inLevel.players=$inLevelPlayers Bukkit.getPlayer!=null=$getPlayer bukkit.isOnline=${b.bukkitEntity.isOnline} isConnected=${b.bukkitEntity.isConnected} (Q4 under this variant: ${q4.substringAfter("Q4 ").substringBefore(' ')})")
    }

    private fun finish(checks: List<Check>) {
        val fails = log.count { Regex("^Q\\d\\w* FAIL").containsMatchIn(it) }
        out("SUMMARY checks=${checks.size} fails(incl Q5/Q6)=$fails")
        out("live bots at shutdown (for Q6): Q4Bot${if (manual) " + SpikeBot" else ""}")
        File(dataFolder, "result.txt").writeText(log.joinToString("\n") + "\n")
        File(dataFolder, "previous-boot-done").writeText("1")
        if (manual) {
            val spawn = Bukkit.getWorlds()[0].spawnLocation.clone().add(2.0, 0.0, 0.0)
            manualBot = FakeBot.spawn(spawn, "SpikeBot")
            out("MANUAL mode: SpikeBot spawned at ${spawn.blockX},${spawn.blockY},${spawn.blockZ} in ${spawn.world.name}; server stays up")
            File(dataFolder, "result.txt").writeText(log.joinToString("\n") + "\n")
            return
        }
        Bukkit.shutdown()
    }
}
