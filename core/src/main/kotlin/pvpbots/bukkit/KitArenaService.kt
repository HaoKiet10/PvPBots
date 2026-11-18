package pvpbots.bukkit

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.inventory.ItemStack
import pvpbots.arena.Action
import pvpbots.arena.KitRules
import pvpbots.arena.StashCodec
import pvpbots.session.AreaRef
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.logging.Level

/**
 * Kit arenas (§5.6): a player entering a built arena with a kit has their whole inventory saved to
 * `stash/<uuid>.dat` (written, fsynced and read back BEFORE the inventory changes), then fights in the arena kit; the
 * stash comes back when they leave, respawn, quit, or the arena is removed or its kit changed, and on join after a
 * crash. A stash file is only ever deleted after its items were put back; one that cannot be read is kept, the
 * inventory is left alone and no new stash is made over it. Main thread only; bots are ignored.
 */
class KitArenaService(private val plugin: PvpBotsPlugin, private val dir: File) {
    private class Kitted(val player: Player, val arena: String?, val kit: String)

    /** Last area the region callback reported per player. */
    private val areas = HashMap<UUID, AreaRef>()

    /** Players whose inventory is an arena kit right now (their own items are in the stash file). */
    private val kitted = HashMap<UUID, Kitted>()

    /** Died with a stash: nothing happens to their inventory until the tick after they respawn. */
    private val awaitingRespawn = HashSet<UUID>()

    /** Restored, but the file could neither be deleted nor moved aside: never restored again while the server runs. */
    private val spent = HashSet<UUID>()

    private val kits get() = plugin.botManager.config.kits

    fun stashFile(id: UUID) = File(dir, "$id.dat")

    fun hasStash(id: UUID): Boolean = id !in spent && stashFile(id).exists()

    /** Wearing an arena kit: drops are blocked and a death drops nothing. */
    fun inKitArena(p: Player): Boolean = p.uniqueId in kitted

    /** From the region callback (the same one the director gets). */
    fun onAreaChange(p: Player, from: AreaRef?, to: AreaRef?) {
        val id = p.uniqueId
        if (isBot(id)) return
        if (to == null) areas.remove(id) else areas[id] = to
        if (id in awaitingRespawn) return
        apply(p, from, to)
    }

    private fun apply(p: Player, from: AreaRef?, to: AreaRef?) {
        val id = p.uniqueId
        when (KitRules.action(to?.kit != null, from?.kit != null, p.gameMode.name, hasStash(id), p.isDead)) {
            Action.STASH_AND_GIVE -> stashAndGive(p, to!!)
            Action.RESTORE -> restore(p)
            Action.RESTORE_THEN_STASH_AND_GIVE -> {
                val k = kitted[id]
                // Same arena and kit re-reported (the region watcher was reset): already wearing it, keep the stash.
                if (k != null && k.arena == to!!.arena && k.kit == to.kit) return
                // A stash that cannot be restored blocks a new one: the player keeps what they have on.
                if (restore(p)) stashAndGive(p, to!!)
            }
            Action.NONE -> {}
        }
    }

    /** A trainer spawned for [owner] in a kit arena (a new round): a fresh kit, no new stash. */
    fun refill(owner: UUID) {
        val k = kitted[owner] ?: return
        val p = k.player
        if (!hasStash(owner)) {
            kitted.remove(owner) // never clear an inventory whose owner's items are not safe on disk
            return
        }
        if (p.isDead || owner in awaitingRespawn || k.kit !in kits.kits) return
        kits.applyToPlayer(p, k.kit)
    }

    /**
     * Puts the stashed items back (inventory cleared first) and deletes the file. True when there was nothing to restore
     * or it worked; false when the stash could not be read (file kept, inventory untouched, player told, SEVERE
     * logged) or the player is dead (the respawn restores it).
     */
    fun restore(p: Player): Boolean {
        val id = p.uniqueId
        val file = stashFile(id)
        if (!hasStash(id)) {
            kitted.remove(id)
            return true
        }
        if (p.isDead) return false
        val items: List<ItemStack?> = try {
            StashCodec.decode(file.readBytes()).map { b -> b?.let { ItemStack.deserializeBytes(it) } }
        } catch (e: VirtualMachineError) {
            throw e
        } catch (e: Throwable) {
            failed(p, file, e)
            return false
        }
        val inv = p.inventory
        try {
            p.closeInventory()
            val size = inv.contents.size
            val contents = arrayOfNulls<ItemStack>(size)
            for (i in 0 until minOf(size, items.size)) contents[i] = items[i]
            inv.contents = contents
            // Only if the inventory got smaller between versions: nothing is left out.
            val extra = items.drop(size).filterNotNull()
            if (extra.isNotEmpty()) inv.addItem(*extra.toTypedArray()).values.forEach { p.world.dropItemNaturally(p.location, it) }
        } catch (e: VirtualMachineError) {
            throw e
        } catch (e: Throwable) {
            failed(p, file, e)
            return false
        }
        kitted.remove(id)
        discard(id, file, p.name)
        p.sendMessage("PvpBots: your own items are back.")
        return true
    }

    /** The arena's kit changes or it is removed: everyone wearing its kit gets their items back first. */
    fun releaseArena(name: String) {
        for (k in kitted.values.filter { it.arena == name }) {
            if (!k.player.isDead) restore(k.player) // a dead player's stash is restored when they respawn
        }
    }

    /** Every online player (and anyone wearing a kit) with a stash gets it back now: plugin enable and disable. */
    fun restoreAllOnline() {
        val players = LinkedHashMap<UUID, Player>()
        kitted.values.forEach { players[it.player.uniqueId] = it.player }
        Bukkit.getOnlinePlayers().forEach { if (!isBot(it.uniqueId)) players[it.uniqueId] = it }
        for (p in players.values) {
            if (!hasStash(p.uniqueId)) {
                kitted.remove(p.uniqueId)
                continue
            }
            if (p.isDead) {
                awaitingRespawn += p.uniqueId // the file stays; the respawn (or the next join) restores it
                continue
            }
            restore(p)
        }
    }

    /** PlayerJoinEvent (LOWEST): a stash left by a crash comes back before the player can do anything. */
    fun onJoin(p: Player) {
        val id = p.uniqueId
        if (isBot(id)) return
        kitted.remove(id)
        areas.remove(id)
        awaitingRespawn.remove(id)
        if (!hasStash(id)) return
        if (p.isDead) {
            awaitingRespawn += id
            return
        }
        restore(p)
    }

    /** PlayerQuitEvent (LOWEST, before the server saves the player). A dead player's stash stays on disk for later. */
    fun onQuit(p: Player) {
        val id = p.uniqueId
        if (isBot(id)) return
        if (hasStash(id) && !p.isDead) restore(p)
        kitted.remove(id)
        areas.remove(id)
        awaitingRespawn.remove(id)
    }

    /** PlayerDeathEvent: wearing a kit → no item drops, no XP orbs, XP kept. */
    fun onDeath(event: PlayerDeathEvent) {
        if (!inKitArena(event.player) || isBot(event.player.uniqueId)) return
        event.drops.clear()
        event.keepLevel = true
        event.droppedExp = 0
    }

    /** After a death went through: the respawn resets the inventory, so the stash waits until the player is back. */
    fun onDied(p: Player) {
        val id = p.uniqueId
        if (isBot(id)) return
        kitted.remove(id)
        if (hasStash(id)) awaitingRespawn += id
    }

    /** PlayerRespawnEvent: restore on the next tick, once the respawn has finished; respawning inside a kit arena re-kits. */
    fun onRespawn(p: Player) {
        val id = p.uniqueId
        if (isBot(id)) return
        Bukkit.getScheduler().runTask(plugin, Runnable {
            if (!p.isOnline || p.isDead) return@Runnable // quit or join handle it
            awaitingRespawn.remove(id)
            val area = areas[id]
            apply(p, area, area)
        })
    }

    /** Self-test: drop every in-memory note about [id] (the file is the caller's business). */
    internal fun forget(id: UUID) {
        kitted.remove(id)
        areas.remove(id)
        awaitingRespawn.remove(id)
        spent.remove(id)
    }

    private fun stashAndGive(p: Player, area: AreaRef): Boolean {
        val id = p.uniqueId
        val kitName = area.kit ?: return false
        if (kitName !in kits.kits) {
            plugin.logger.warning("Kit arena ${area.arena}: kit '$kitName' is not in config.yml kits; ${p.name} keeps their own items")
            return false
        }
        val file = stashFile(id)
        if (file.exists() || id in spent) return false // never overwrite a stash
        // Cursor and crafting-grid items go back into the inventory, so they are saved too.
        p.closeInventory()
        try {
            val bytes = StashCodec.encode(p.inventory.contents.map { it?.takeUnless { s -> s.type.isAir }?.serializeAsBytes() })
            writeNew(file, bytes)
        } catch (e: VirtualMachineError) {
            throw e
        } catch (e: Exception) {
            plugin.logger.log(Level.SEVERE, "Kit arena ${area.arena}: the inventory of ${p.name} ($id) could not be saved to ${file.absolutePath}; nothing was changed", e)
            p.sendMessage("PvpBots: your items could not be saved, so you keep them and fight in your own gear here.")
            return false
        }
        kits.applyToPlayer(p, kitName)
        kitted[id] = Kitted(p, area.arena, kitName)
        p.sendMessage("PvpBots: your items are saved; here you fight with the $kitName kit. You get them back when you leave.")
        return true
    }

    /** Writes `<file>.tmp`, fsyncs and reads it back, then moves it into place; refuses to replace an existing [file]. */
    private fun writeNew(file: File, bytes: ByteArray) {
        dir.mkdirs()
        val tmp = File(dir, file.name + ".tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            check(tmp.readBytes().contentEquals(bytes)) { "read-back of ${tmp.name} differs from what was written" }
            check(!file.exists()) { "${file.name} already exists" }
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), file.toPath())
            }
        } finally {
            tmp.delete()
        }
        check(file.isFile && file.length() == bytes.size.toLong()) { "${file.name} missing after the move" }
        // Make the rename itself durable where the OS allows it (not on Windows).
        runCatching { FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) } }
    }

    /** After a successful restore only. */
    private fun discard(id: UUID, file: File, name: String) {
        try {
            Files.delete(file.toPath())
            return
        } catch (e: Exception) {
            val aside = File(dir, "${file.name}.restored-${System.currentTimeMillis()}")
            try {
                Files.move(file.toPath(), aside.toPath())
                plugin.logger.warning("Restored the items of $name but could not delete ${file.absolutePath} (${e.message}); renamed it to ${aside.name}")
            } catch (e2: Exception) {
                spent += id
                plugin.logger.log(
                    Level.SEVERE,
                    "Restored the items of $name but could neither delete nor rename ${file.absolutePath}: delete it by hand before " +
                        "$name joins after the next restart, or they get that old inventory again",
                    e2,
                )
            }
        }
    }

    private fun failed(p: Player, file: File, e: Throwable) {
        plugin.logger.log(Level.SEVERE, "Inventory stash of ${p.name} (${p.uniqueId}) could not be restored; the file is kept: ${file.absolutePath}", e)
        p.sendMessage("Your items could not be restored automatically; an admin has been notified.")
    }

    private fun isBot(id: UUID) = plugin.botManager.isBot(id)
}
