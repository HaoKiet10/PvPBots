package pvpbots.bukkit

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.attribute.Attribute
import org.bukkit.block.ShulkerBox
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerPickupArrowEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.PlayerInventory
import org.bukkit.inventory.meta.BlockStateMeta
import org.bukkit.inventory.meta.BookMeta
import org.bukkit.inventory.meta.Damageable
import pvpbots.arena.ArenaLayout
import pvpbots.arena.ArenaRecord
import pvpbots.arena.Facing
import pvpbots.arena.Preset
import pvpbots.arena.StashCodec
import pvpbots.combat.KitSlots
import pvpbots.math.BlockPos
import pvpbots.profile.Difficulty
import pvpbots.session.AreaRef
import pvpbots.session.Role
import pvpbots.session.SessionKey

/**
 * Kit-arena self-test checks (K1, K2, K3, K4), run by [SelfTest] in [SelfTest.Mode.All]. The test player is a dummy (a real
 * server-side `Player`, but not in the server's player list), so the region callback is called directly the way the
 * plugin's RegionWatcher hook calls it, and join is driven through [KitArenaService.onJoin] (the listener's body).
 */
internal class KitSelfTest(private val t: SelfTest, private val plugin: PvpBotsPlugin, private val world: World) : Listener {
    private val service get() = plugin.arenaService
    private val kits get() = plugin.kitArenas
    private var lastDeath: PlayerDeathEvent? = null

    /** Slots that matched only up to NBT key order (see [diff]). */
    private var reordered = 0

    /** Saves seen through [KitArenaService.savePlayer]: player, and whether their stash file still existed then. */
    private val saves = ArrayList<Pair<java.util.UUID, Boolean>>()
    private var failSaves = false

    suspend fun SequenceScope<Int>.run() {
        Bukkit.getPluginManager().registerEvents(this@KitSelfTest, plugin)
        val realSave = kits.savePlayer
        // Dummies must leave no player data behind (D1): saves are recorded instead (K2 runs one real save and cleans up).
        kits.savePlayer = { p ->
            saves += p.uniqueId to kits.stashFile(p.uniqueId).exists()
            if (failSaves) throw java.io.IOException("self-test: save refused")
        }
        try {
            kitArena()
            stashOnJoin(realSave)
            failedRestore()
            kitChangeMidSession()
            saveFails()
        } finally {
            kits.savePlayer = realSave
            HandlerList.unregisterAll(this@KitSelfTest)
        }
    }

    /**
     * K5 (C1): the restored inventory is saved while the stash still exists, and a save that fails keeps the stash (no
     * new stash over it, not restored twice); the retry on quit saves and deletes it.
     */
    private suspend fun SequenceScope<Int>.saveFails() {
        val h = t.spawnDummy("Save", 1400, 0)
        val p = h.bukkit
        val id = p.uniqueId
        yield(2)
        p.inventory.clear()
        val gem = ItemStack(Material.DIAMOND, 3)
        val size = p.inventory.contents.size
        val stash = arrayOfNulls<ItemStack>(size).also { it[4] = gem }
        val file = kits.stashFile(id)
        ArenaRegistry.writeDurably(file, StashCodec.encode(stash.map { it?.serializeAsBytes() }))
        saves.clear()
        failSaves = true
        kits.onJoin(p)
        val restored = p.inventory.getItem(4)?.isSimilar(gem) == true
        val savedWithStash = saves.singleOrNull() == (id to true)
        val kept = file.exists()
        val notAgain = !kits.hasStash(id)
        kits.refill(id) // a kept file must not count as a stash: nothing is refilled or restored over it
        val once = p.inventory.all(Material.DIAMOND).values.sumOf { it.amount } == 3
        failSaves = false
        saves.clear()
        kits.onQuit(p)
        val retried = saves.firstOrNull() == (id to true)
        val deleted = !file.exists()
        t.check(
            "K5 save before the stash goes",
            restored && savedWithStash && kept && notAgain && once && retried && deleted,
            "failing save: items back $restored, save attempted while the stash existed $savedWithStash, stash kept $kept, " +
                "not counted as a stash any more $notAgain, diamonds exactly once $once; " +
                "quit: save retried with the stash present $retried, stash deleted $deleted",
        )
        kits.forget(id)
        if (file.exists()) file.delete()
        runCatching { t.removeDummy(h) }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onDeath(event: PlayerDeathEvent) {
        lastDeath = event
    }

    /** K1: enter a kit arena (stash + kit), trainer in the kit, refill, no drops, kit change and leave restore exactly. */
    private suspend fun SequenceScope<Int>.kitArena() {
        val name = "selftest_k1"
        val origin = BlockPos(1000, world.getHighestBlockYAt(1000, 0), 0)
        val msgs = service.createAt(world, origin, Facing.NORTH, name, Preset.DUEL, "IRON", null)
        val ready = waitUntil(200) { registry().get(name)?.status == ArenaRecord.Status.READY }
        val rec = registry().get(name)
        if (ready < 0 || rec == null || rec.kit != "iron") {
            t.check("K1 kit arena stash/restore", false, "arena never became READY with kit iron (record ${rec?.status}, kit ${rec?.kit}; create: ${msgs.joinToString(" | ")})")
            cleanupArena(name)
            return
        }
        val layout = ArenaLayout.generate(rec.preset, rec.origin, rec.facing)
        val area = AreaRef(world.name, rec.regionId, Role.TRAINER, Difficulty.EASY, 1, layout.region, arena = rec.name, kit = rec.kit)
        val h = t.spawnDummy("Kit", origin.x + 2, origin.z + 2)
        val p = h.bukkit
        val id = p.uniqueId
        yield(5)
        giveTestItems(p)
        p.health = 12.0
        p.foodLevel = 8
        val original = slots(p)
        val originalCount = original.count { it != null }

        // Enter (what RegionWatcher's callback does for a real player).
        kits.onAreaChange(p, null, area)
        plugin.director.onAreaChange(id, null, area, t.nowMs())
        val stashFile = kits.stashFile(id)
        val stashed = stashFile.exists()
        val stashMatches = stashed && sameSlots(runCatching { StashCodec.decode(stashFile.readBytes()) }.getOrNull(), original)
        val kitAtEnter = kitMismatch(p.inventory, "iron", allowTotem = false)
        val max = p.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
        val healthAtEnter = p.health
        val foodAtEnter = p.foodLevel
        val healed = healthAtEnter == max && foodAtEnter == 20
        val stashBytes = if (stashed) stashFile.readBytes() else null
        // Use up some food: the trainer's spawn (a new round) must hand out a fresh kit.
        p.inventory.getItem(KitSlots.FOOD)?.let { it.amount = 1 }

        val key = SessionKey.Trainer(id)
        val spawned = waitUntil(60) { t.trainerBot(key) != null }
        val bot = t.trainerBot(key)
        // Both sides fight in the same kit: no bonus totem for the bot in a kit arena; only the player's items are tagged.
        val botKit = if (bot == null) "no trainer" else kitMismatch(bot.player.inventory, "iron", allowTotem = false)
        val botUntagged = bot != null && bot.player.inventory.contents.none { KitApplier.isKitItem(it) }
        val playerTagged = p.inventory.contents.filter { it != null && !it.type.isAir }.let { it.isNotEmpty() && it.all { s -> KitApplier.isKitItem(s) } }
        val refilled = p.inventory.getItem(KitSlots.FOOD)?.amount == 4
        val kitAfterSpawn = kitMismatch(p.inventory, "iron", allowTotem = false)
        val notRestashed = stashBytes != null && stashFile.readBytes().contentEquals(stashBytes)

        // Drop attempt inside.
        val dropped = world.dropItem(p.location, ItemStack(Material.IRON_SWORD))
        val dropEvent = PlayerDropItemEvent(p, dropped)
        Bukkit.getPluginManager().callEvent(dropEvent)
        val dropCancelled = dropEvent.isCancelled
        // Pick-up attempt inside (refill and restore replace the whole inventory, so nothing may come in).
        val pickupEvent = EntityPickupItemEvent(p, dropped, 0)
        Bukkit.getPluginManager().callEvent(pickupEvent)
        dropped.remove()
        val pickupCancelled = pickupEvent.isCancelled
        val arrowEntity = world.spawnArrow(p.location.clone().add(0.0, 1.0, 0.0), org.bukkit.util.Vector(0, 0, 0), 0f, 0f)
        val arrowItem = world.dropItem(p.location, ItemStack(Material.ARROW))
        val arrowEvent = PlayerPickupArrowEvent(p, arrowItem, arrowEntity)
        Bukkit.getPluginManager().callEvent(arrowEvent)
        arrowItem.remove()
        arrowEntity.remove()
        val arrowCancelled = arrowEvent.isCancelled

        // Leave.
        kits.onAreaChange(p, area, null)
        val afterLeave = slots(p)
        plugin.director.onAreaChange(id, area, null, t.nowMs())
        val leaveDiff = diff(original, afterLeave)
        val stashGoneAfterLeave = !stashFile.exists()

        // Kit change while inside (the arena release hook) restores too.
        kits.onAreaChange(p, null, area)
        val stashedAgain = stashFile.exists()
        val setKit = service.setKit(name, null)
        val releaseDiff = diff(original, slots(p))
        val stashGoneAfterRelease = !stashFile.exists()
        kits.onAreaChange(p, area, null)

        // Death inside drops nothing and keeps the stash (the dummy cannot respawn: see the report).
        kits.onAreaChange(p, null, area)
        val stashedForDeath = stashFile.exists()
        p.totalExperience = 0
        p.level = 7
        lastDeath = null
        val killed = runCatching { p.health = 0.0 }.exceptionOrNull()
        yield(1)
        val death = lastDeath
        val deathOk = death != null && death.drops.isEmpty() && death.keepLevel && death.droppedExp == 0
        val stashAfterDeath = stashFile.exists() && sameSlots(runCatching { StashCodec.decode(stashFile.readBytes()) }.getOrNull(), original)

        // Respawn: a dummy cannot respawn, so the dead one is replaced by a live dummy with the same UUID outside the
        // arena, and the real respawn hook runs (restore on the next tick, judged by the current area).
        runCatching { t.removeDummy(h) }
        yield(1)
        val h2 = runCatching { t.spawnDummy("Kit", origin.x + 40, origin.z, id = id) }.getOrNull()
        var respawnDiff: String? = "no live dummy with the same UUID"
        var stashGoneAfterRespawn = false
        if (h2 != null) {
            kits.onRespawn(h2.bukkit)
            yield(2)
            respawnDiff = diff(original, slots(h2.bukkit))
            stashGoneAfterRespawn = !stashFile.exists()
        }

        t.check(
            "K1 kit arena stash/restore",
            originalCount >= 9 && stashed && stashMatches && kitAtEnter == null && healed && spawned >= 0 && botKit == null &&
                botUntagged && playerTagged && arrowCancelled &&
                refilled && kitAfterSpawn == null && notRestashed && dropCancelled && leaveDiff == null && stashGoneAfterLeave &&
                stashedAgain && releaseDiff == null && stashGoneAfterRelease && stashedForDeath && deathOk && stashAfterDeath &&
                pickupCancelled && respawnDiff == null && stashGoneAfterRespawn,
            "test items in $originalCount slots; enter: stash file $stashed (decodes to the original slots: $stashMatches), " +
                "kit ${kitAtEnter ?: "iron exactly"}, health ${"%.1f/%.1f".format(healthAtEnter, max)} food $foodAtEnter (was 12/8), full: $healed; " +
                "trainer after $spawned ticks, its kit ${botKit ?: "iron, no totem"}, bot items untagged: $botUntagged, player kit items tagged: $playerTagged; " +
                "arrow pick-up cancelled: $arrowCancelled; refilled on spawn (apples 1 -> 4): $refilled, " +
                "kit after spawn ${kitAfterSpawn ?: "iron exactly"}, stash unchanged: $notRestashed; drop cancelled: $dropCancelled; pick-up cancelled: $pickupCancelled; " +
                "leave: ${leaveDiff ?: "every slot identical"}, stash deleted: $stashGoneAfterLeave; " +
                "kit change (${setKit.lastOrNull()}): stashed $stashedAgain, ${releaseDiff ?: "every slot identical"}, stash deleted: $stashGoneAfterRelease; " +
                "death${killed?.let { " (setHealth(0) threw $it)" } ?: ""}: stashed $stashedForDeath, event ${death?.let { "drops ${it.drops.size}, keepLevel ${it.keepLevel}, droppedExp ${it.droppedExp}" } ?: "not seen"}, " +
                "stash intact after death: $stashAfterDeath; respawn (live dummy, same UUID): ${respawnDiff ?: "every slot identical"}, " +
                "stash deleted: $stashGoneAfterRespawn; slot comparisons equal only up to NBT key order: $reordered",
        )
        kits.forget(id)
        stashFile.delete()
        h2?.let { runCatching { t.removeDummy(it) } }
        cleanupArena(name)
    }

    /** K2: a stash file present when the player joins is restored exactly and deleted. */
    private suspend fun SequenceScope<Int>.stashOnJoin(realSave: (Player) -> Unit) {
        val h = t.spawnDummy("Join", 1100, 0)
        val p = h.bukkit
        yield(2)
        val size = p.inventory.contents.size
        p.inventory.clear()
        p.inventory.setItem(3, ItemStack(Material.DIRT, 5)) // whatever was saved with the player before the crash
        val sword = ItemStack(Material.GOLDEN_SWORD)
        sword.editMeta { it.displayName(Component.text("Crash survivor")) }
        val stash = arrayOfNulls<ItemStack>(size)
        stash[0] = sword
        stash[7] = ItemStack(Material.EMERALD, 10)
        if (size > 39) stash[39] = ItemStack(Material.TURTLE_HELMET)
        if (size > 40) stash[40] = ItemStack(Material.SHIELD)
        val stashSlots = stash.map { it?.serializeAsBytes() }
        val file = kits.stashFile(p.uniqueId)
        ArenaRegistry.writeDurably(file, StashCodec.encode(stashSlots))
        val written = file.exists()

        val before = System.currentTimeMillis() - 2000
        // One real save (Player.saveData) to prove it works on this server; its files are deleted below (D1).
        var stashAtSave: Boolean? = null
        kits.savePlayer = { pl -> stashAtSave = kits.stashFile(pl.uniqueId).exists(); realSave(pl) }
        try {
            kits.onJoin(p)
        } finally {
            kits.savePlayer = { pl -> saves += pl.uniqueId to kits.stashFile(pl.uniqueId).exists(); if (failSaves) throw java.io.IOException("self-test: save refused") }
        }
        val d = diff(stashSlots, slots(p))
        val deleted = !file.exists()
        yield(5) // the save may finish off the main thread
        val saved = playerDataFile(p.uniqueId)
        val savedOk = saved != null && saved.lastModified() >= before && stashAtSave == true
        t.check(
            "K2 stash on join",
            written && d == null && deleted && savedOk,
            "stash file written: $written ($size slots); after join: ${d ?: "inventory equals the stash, every slot identical"}; stash deleted: $deleted; " +
                "player data saved while the stash still existed ($stashAtSave): ${saved?.let { "${it.parentFile.name}/${it.name}, written ${System.currentTimeMillis() - it.lastModified()} ms ago" } ?: "no file"}",
        )
        playerFiles(p.uniqueId).forEach { it.delete() }

        // Saved while kitted (a tagged kit item in it): untagged items that came in from outside are handed back.
        p.inventory.clear()
        val kitSword = ItemStack(Material.IRON_SWORD).also { KitApplier.tag(it) }
        val mailed = ItemStack(Material.EMERALD, 4)
        mailed.editMeta { it.displayName(Component.text("Mailed before the crash")) }
        p.inventory.setItem(0, kitSword)
        p.inventory.setItem(8, mailed.clone())
        ArenaRegistry.writeDurably(file, StashCodec.encode(stashSlots))
        kits.onJoin(p)
        val after = slots(p)
        val d2 = diff(stashSlots.mapIndexed { i, b -> b ?: after.getOrNull(i) }, after)
        val mailedBack = p.inventory.containsAtLeast(mailed, 4)
        val noKit = p.inventory.contents.none { KitApplier.isKitItem(it) }
        t.check(
            "K2b stash on join, saved while kitted",
            d2 == null && mailedBack && noKit && !file.exists(),
            "stash ${d2 ?: "every slot identical"}; untagged item handed back: $mailedBack; no kit item left: $noKit; stash deleted: ${!file.exists()}",
        )
        if (file.exists()) file.delete()
        runCatching { t.removeDummy(h) }
    }

    /**
     * K3: a restore that fails leaves the player their items under vanilla rules: no new stash over the broken one, no
     * refill, pick-ups and drops allowed, a death drops everything; the next successful restore (after the respawn)
     * gives the stash back AND hands back what the player gathered meanwhile.
     */
    private suspend fun SequenceScope<Int>.failedRestore() {
        val name = "selftest_k3"
        val origin = BlockPos(1200, world.getHighestBlockYAt(1200, 0), 0)
        service.createAt(world, origin, Facing.NORTH, name, Preset.DUEL, "iron", null)
        val ready = waitUntil(200) { registry().get(name)?.status == ArenaRecord.Status.READY }
        val rec = registry().get(name)
        if (ready < 0 || rec == null) {
            t.check("K3 failed restore keeps own items", false, "arena never became READY (record ${rec?.status})")
            cleanupArena(name)
            return
        }
        val layout = ArenaLayout.generate(rec.preset, rec.origin, rec.facing)
        val area = AreaRef(world.name, rec.regionId, Role.TRAINER, Difficulty.EASY, 1, layout.region, arena = rec.name, kit = rec.kit)
        val h = t.spawnDummy("Fail", origin.x + 2, origin.z + 2)
        val p = h.bukkit
        val id = p.uniqueId
        yield(2)
        giveTestItems(p)
        val original = slots(p)
        val file = kits.stashFile(id)

        kits.onAreaChange(p, null, area)
        val good = if (file.exists()) file.readBytes() else null
        val junk = "not a stash".toByteArray()
        ArenaRegistry.writeDurably(file, junk)

        // Leave: the restore fails; file and inventory (the kit) stay as they are.
        kits.onAreaChange(p, area, null)
        val fileKept = file.exists() && file.readBytes().contentEquals(junk)
        val marker = kits.failedMarker(id)
        val markerWritten = marker.isFile && marker.readText().trim() == "iron"
        val kitKept = kitMismatch(p.inventory, "iron", allowTotem = false)

        // Outside: picking up and dropping are vanilla.
        val picked = ItemStack(Material.EMERALD, 3)
        picked.editMeta { it.displayName(Component.text("Picked up")) }
        val ent = world.dropItem(p.location, picked.clone())
        val pickup = EntityPickupItemEvent(p, ent, 0)
        Bukkit.getPluginManager().callEvent(pickup)
        val drop = PlayerDropItemEvent(p, ent)
        Bukkit.getPluginManager().callEvent(drop)
        ent.remove()
        p.inventory.addItem(picked.clone())

        // Back into the same kit arena: no new stash over the broken one, and a new round does not wipe the inventory.
        kits.onAreaChange(p, null, area)
        val noRestash = file.readBytes().contentEquals(junk)
        kits.refill(id)
        val keptAfterRefill = p.inventory.containsAtLeast(picked, 3)
        val dropInside = PlayerDropItemEvent(p, world.dropItem(p.location, ItemStack(Material.DIRT)))
        Bukkit.getPluginManager().callEvent(dropInside)
        dropInside.itemDrop.remove()

        // Die (inside the arena, even): vanilla drops, the gathered emeralds are dropped, not destroyed.
        lastDeath = null
        runCatching { p.health = 0.0 }
        yield(1)
        val death = lastDeath
        val emeraldsDropped = death != null && death.drops.any { it.isSimilar(picked) && it.amount == 3 }
        world.getNearbyEntities(p.location, 4.0, 4.0, 4.0).filterIsInstance<org.bukkit.entity.Item>().forEach { it.remove() }

        // Respawn (live dummy, same UUID), gather something else, the admin fixes the stash: the respawn hook restores.
        runCatching { t.removeDummy(h) }
        yield(1)
        val h2 = runCatching { t.spawnDummy("Fail", origin.x + 40, origin.z, id = id) }.getOrNull()
        var ownDiff: String? = "no live dummy with the same UUID"
        var gatheredKept = false
        var deleted = false
        if (h2 != null && good != null) {
            val p2 = h2.bukkit
            val gathered = ItemStack(Material.DIAMOND, 2)
            gathered.editMeta { it.displayName(Component.text("Picked up 2")) }
            p2.inventory.addItem(gathered.clone())
            // A restart forgets everything held in memory; only the marker file tells the failure.
            kits.forgetFailuresInMemory(id)
            ArenaRegistry.writeDurably(file, good)
            kits.onRespawn(p2)
            yield(2)
            val after = slots(p2)
            // Every stashed slot back in place; the gathered diamonds handed back on top (in a free slot).
            ownDiff = diff(original.mapIndexed { i, b -> b ?: after.getOrNull(i) }, after)
            gatheredKept = p2.inventory.containsAtLeast(gathered, 2)
            deleted = !file.exists()
        }
        val markerGone = !marker.exists()
        t.check(
            "K3 failed restore keeps own items",
            good != null && fileKept && kitKept == null && !pickup.isCancelled && !drop.isCancelled && noRestash &&
                keptAfterRefill && !dropInside.isCancelled && emeraldsDropped && ownDiff == null && gatheredKept && deleted &&
                markerWritten && markerGone,
            "stash made: ${good != null}; broken stash on leave: file kept $fileKept, inventory ${kitKept ?: "still the kit"}, " +
                "failure marker written (kit iron): $markerWritten; " +
                "outside: pick-up cancelled ${pickup.isCancelled}, drop cancelled ${drop.isCancelled}; re-enter: stash untouched $noRestash, " +
                "emeralds kept after a refill: $keptAfterRefill, drop inside cancelled ${dropInside.isCancelled}; " +
                "death: ${death?.let { "drops ${it.drops.size}, gathered emeralds among them: $emeraldsDropped" } ?: "not seen"}; " +
                "in-memory failure state dropped (restart), stash fixed + respawn: ${ownDiff ?: "every stashed slot identical"}, gathered diamonds kept: $gatheredKept, stash deleted: $deleted, marker deleted: $markerGone",
        )
        kits.forget(id)
        if (file.exists()) file.delete()
        h2?.let { runCatching { t.removeDummy(it) } }
        cleanupArena(name)
    }

    /** Files naming [id] under the server's world folders (1.21.11 world/playerdata, 26.x world/players/data). */
    private fun playerFiles(id: java.util.UUID): List<java.io.File> {
        val root = Bukkit.getWorldContainer()
        val skip = setOf("plugins", "libraries", "cache", "versions", "logs", "config")
        return root.walkTopDown().maxDepth(6)
            .onEnter { d -> d == root || d.parentFile != root || d.name !in skip }
            .filter { it.isFile && it.name.contains(id.toString()) }.toList()
    }

    /** The player-data file [Player.saveData] writes for [id]. */
    private fun playerDataFile(id: java.util.UUID): java.io.File? = playerFiles(id).firstOrNull { it.name == "$id.dat" }

    /**
     * K4: `/pvpbots arena kit` mid-session (I2) with own items put into the kitted inventory from outside (I1): the
     * session goes on, the next round's trainer wears the new kit (no totem) and the player is refilled with it; items
     * that came in from outside survive the refill and are handed back on leave, on top of the stash.
     */
    private suspend fun SequenceScope<Int>.kitChangeMidSession() {
        val name = "selftest_k4"
        val origin = BlockPos(1300, world.getHighestBlockYAt(1300, 0), 0)
        service.createAt(world, origin, Facing.NORTH, name, Preset.DUEL, "iron", null)
        val ready = waitUntil(200) { registry().get(name)?.status == ArenaRecord.Status.READY }
        val rec = registry().get(name)
        if (ready < 0 || rec == null) {
            t.check("K4 kit change mid-session", false, "arena never became READY (record ${rec?.status})")
            cleanupArena(name)
            return
        }
        val layout = ArenaLayout.generate(rec.preset, rec.origin, rec.facing)
        val area = AreaRef(world.name, rec.regionId, Role.TRAINER, Difficulty.EASY, 1, layout.region, arena = rec.name, kit = rec.kit)
        val h = t.spawnDummy("Chg", origin.x + 2, origin.z + 2)
        val p = h.bukkit
        val id = p.uniqueId
        yield(2)
        giveTestItems(p)
        val original = slots(p)
        val file = kits.stashFile(id)
        val key = SessionKey.Trainer(id)
        kits.onAreaChange(p, null, area)
        plugin.director.onAreaChange(id, null, area, t.nowMs())
        val spawned = waitUntil(60) { t.trainerBot(key) != null }
        val bot1 = t.trainerBot(key)
        val session = plugin.director.trainerFor(id)

        // The admin switches the kit; RegionWatcher reports the same region with the new kit.
        val setKit = service.setKit(name, "diamond")
        val area2 = area.copy(kit = "diamond")
        kits.onAreaChange(p, area, area2)
        plugin.director.onAreaChange(id, area, area2, t.nowMs())
        val sameSession = session != null && plugin.director.trainerFor(id) === session
        val kitNow = kitMismatch(p.inventory, "diamond", allowTotem = false)
        val restashed = file.exists()

        // Own items put in from outside (an ender chest, a mail GUI) while kitted, one held on the cursor.
        val mail = ItemStack(Material.EMERALD, 7)
        mail.editMeta { it.displayName(Component.text("Mailed in")) }
        val held = ItemStack(Material.LAPIS_LAZULI, 9)
        held.editMeta { it.displayName(Component.text("On the cursor")) }
        p.inventory.addItem(mail.clone())
        p.setItemOnCursor(held.clone())
        p.inventory.getItem(KitSlots.FOOD)?.let { it.amount = 1 }

        // Next round: the bot dies, the rematch spawns a new trainer (refill).
        bot1?.player?.health = 0.0
        val next = waitUntil(400) { t.trainerBot(key)?.let { it.id != bot1?.id } == true }
        val bot2 = t.trainerBot(key)?.takeIf { it.id != bot1?.id }
        val bot2Kit = if (bot2 == null) "no second trainer" else kitMismatch(bot2.player.inventory, "diamond", allowTotem = false)
        val refilled = p.inventory.getItem(KitSlots.FOOD)?.amount == 6
        val mailKept = p.inventory.containsAtLeast(mail, 7)
        val heldKept = p.inventory.containsAtLeast(held, 9) || p.itemOnCursor.isSimilar(held)

        // Leave: the stash comes back and both own items are handed back on top.
        kits.onAreaChange(p, area2, null)
        plugin.director.onAreaChange(id, area2, null, t.nowMs())
        val after = slots(p)
        val leaveDiff = diff(original.mapIndexed { i, b -> b ?: after.getOrNull(i) }, after)
        val mailBack = p.inventory.containsAtLeast(mail, 7)
        val heldBack = p.inventory.containsAtLeast(held, 9)
        val noKitLeft = p.inventory.contents.none { KitApplier.isKitItem(it) }
        val stashGone = !file.exists()
        t.check(
            "K4 kit change mid-session",
            spawned >= 0 && bot1 != null && sameSession && kitNow == null && restashed && next >= 0 && bot2Kit == null &&
                refilled && mailKept && heldKept && leaveDiff == null && mailBack && heldBack && noKitLeft && stashGone,
            "first trainer after $spawned ticks; kit change (${setKit.lastOrNull()}): same session $sameSession, player kit ${kitNow ?: "diamond exactly"}, " +
                "stashed again $restashed; next round's trainer after $next ticks, its kit ${bot2Kit ?: "diamond, no totem"}; " +
                "player refilled with diamond (apples 1 -> 6): $refilled; own items put in while kitted kept through the refill: " +
                "mailed $mailKept, cursor $heldKept; leave: ${leaveDiff ?: "every stashed slot identical"}, mailed back $mailBack, " +
                "cursor item back $heldBack, no kit item left $noKitLeft, stash deleted $stashGone",
        )
        kits.forget(id)
        if (file.exists()) file.delete()
        runCatching { t.removeDummy(h) }
        cleanupArena(name)
    }

    private fun giveTestItems(p: Player) {
        val inv = p.inventory
        inv.clear()
        val sword = ItemStack(Material.DIAMOND_SWORD)
        sword.addUnsafeEnchantment(Enchantment.SHARPNESS, 5)
        sword.editMeta { m ->
            m.displayName(Component.text("Old Faithful"))
            (m as Damageable).damage = 321
        }
        val shulker = ItemStack(Material.SHULKER_BOX)
        shulker.editMeta { m ->
            val bsm = m as BlockStateMeta
            val box = bsm.blockState as ShulkerBox
            box.inventory.setItem(0, ItemStack(Material.DIAMOND, 12))
            box.inventory.setItem(13, ItemStack(Material.ENCHANTED_GOLDEN_APPLE, 2))
            bsm.blockState = box
        }
        val book = ItemStack(Material.WRITTEN_BOOK)
        book.editMeta { m ->
            val b = m as BookMeta
            b.title = "Notes"
            b.author = "Tester"
            b.addPages(Component.text("page one"), Component.text("page two"))
        }
        inv.setItem(0, sword)
        inv.setItem(5, shulker)
        inv.setItem(12, book)
        inv.setItem(20, ItemStack(Material.COBBLESTONE, 37))
        val helmet = ItemStack(Material.DIAMOND_HELMET)
        helmet.addUnsafeEnchantment(Enchantment.PROTECTION, 4)
        inv.helmet = helmet
        inv.chestplate = ItemStack(Material.CHAINMAIL_CHESTPLATE)
        inv.leggings = ItemStack(Material.GOLDEN_LEGGINGS)
        inv.boots = ItemStack(Material.LEATHER_BOOTS)
        inv.setItemInOffHand(ItemStack(Material.TORCH, 32))
    }

    /** Null when [inv] holds exactly the kit (armor, hotbar per KitSlots, shield off-hand; a bot may also hold a totem). */
    private fun kitMismatch(inv: PlayerInventory, kitName: String, allowTotem: Boolean): String? {
        val kit = plugin.botManager.config.kits.kits[kitName] ?: return "no kit $kitName in config"
        val expected = HashMap<Int, Pair<Material, Int>>()
        kit.sword?.let { expected[KitSlots.SWORD] = it to 1 }
        kit.axe?.let { expected[KitSlots.AXE] = it to 1 }
        kit.food?.let { expected[KitSlots.FOOD] = it to kit.foodAmount }
        kit.bow?.let { expected[KitSlots.BOW] = it to 1 }
        if (kit.arrows > 0) expected[KitSlots.ARROWS] = Material.ARROW to kit.arrows
        val armor = listOf(inv.helmet, inv.chestplate, inv.leggings, inv.boots).map { it?.type?.takeUnless { t -> t.isAir } }
        if (armor != kit.armor) return "armor $armor, expected ${kit.armor}"
        val off = inv.itemInOffHand.type.takeUnless { it.isAir }
        if (off != (if (kit.shield) Material.SHIELD else null)) return "off-hand $off"
        for (slot in 0 until 36) {
            val item = inv.getItem(slot)?.takeUnless { it.type.isAir }
            val want = expected[slot]
            if (allowTotem && slot == KitSlots.TOTEM && item?.type == Material.TOTEM_OF_UNDYING) continue
            if (want == null && item != null) return "unexpected ${item.type} x${item.amount} in slot $slot"
            if (want != null && (item?.type != want.first || item.amount != want.second)) return "slot $slot ${item?.type} x${item?.amount}, expected ${want.first} x${want.second}"
        }
        return null
    }

    private fun slots(p: Player): List<ByteArray?> = p.inventory.contents.map { it?.takeUnless { s -> s.type.isAir }?.serializeAsBytes() }

    private fun sameSlots(a: List<ByteArray?>?, b: List<ByteArray?>): Boolean = a != null && diff(b, a) == null

    /**
     * Null when every slot holds the same item as expected, else the first difference. "Same" is `serializeAsBytes()`
     * byte-identical, or (counted in [reordered]) identical NBT apart from the order of compound keys plus
     * `ItemStack.equals`: Paper writes an item's components in hash-map order, which can differ between an item built
     * through the API and the same item read back from bytes (seen on 1.21.11 with name + enchantments + damage).
     */
    private fun diff(expected: List<ByteArray?>, actual: List<ByteArray?>): String? {
        if (expected.size != actual.size) return "slot count ${actual.size}, expected ${expected.size}"
        val bad = expected.indices.filter { i ->
            val e = expected[i]
            val a = actual[i]
            when {
                e == null || a == null -> e !== a
                e.contentEquals(a) -> false
                NbtCanon.of(e) == NbtCanon.of(a) && ItemStack.deserializeBytes(e) == ItemStack.deserializeBytes(a) -> { reordered++; false }
                else -> true
            }
        }
        if (bad.isEmpty()) return null
        val i = bad.first()
        fun show(b: ByteArray?) = b?.let { runCatching { NbtCanon.of(it) }.getOrElse { e -> "unreadable $e" } }
        return "${bad.size} slot(s) differ: $bad (slot $i is ${show(actual[i])}, expected ${show(expected[i])})"
    }

    private fun registry() = service.registry

    private suspend fun SequenceScope<Int>.cleanupArena(name: String) {
        if (registry().get(name) == null) return
        service.remove(name)
        if (waitUntil(200) { registry().get(name) == null } < 0) service.forget(name)
    }
}

/** Canonical text of a gzipped NBT blob (`ItemStack.serializeAsBytes`): compound keys sorted, so key order does not matter. */
internal object NbtCanon {
    fun of(gz: ByteArray): String {
        java.io.DataInputStream(java.util.zip.GZIPInputStream(gz.inputStream())).use { d ->
            val type = d.readUnsignedByte()
            d.readUTF() // root name
            return tag(type, d)
        }
    }

    private fun tag(type: Int, d: java.io.DataInputStream): String = when (type) {
        1 -> "${d.readByte()}b"
        2 -> "${d.readShort()}s"
        3 -> "${d.readInt()}"
        4 -> "${d.readLong()}L"
        5 -> "${d.readFloat()}f"
        6 -> "${d.readDouble()}d"
        7 -> "[B;" + (0 until d.readInt()).joinToString(",") { d.readByte().toString() } + "]"
        8 -> "\"" + d.readUTF() + "\""
        9 -> {
            val t = d.readUnsignedByte()
            "[" + (0 until d.readInt()).joinToString(",") { tag(t, d) } + "]"
        }
        10 -> {
            val entries = ArrayList<String>()
            while (true) {
                val t = d.readUnsignedByte()
                if (t == 0) break
                val name = d.readUTF()
                entries += name + ":" + tag(t, d)
            }
            "{" + entries.sorted().joinToString(",") + "}"
        }
        11 -> "[I;" + (0 until d.readInt()).joinToString(",") { d.readInt().toString() } + "]"
        12 -> "[L;" + (0 until d.readInt()).joinToString(",") { d.readLong().toString() } + "]"
        else -> throw IllegalArgumentException("unknown NBT tag type $type")
    }
}
