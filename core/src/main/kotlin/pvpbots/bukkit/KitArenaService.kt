package pvpbots.bukkit

import org.bukkit.Bukkit
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerPickupArrowEvent
import org.bukkit.inventory.ItemStack
import pvpbots.arena.Action
import pvpbots.arena.KitRules
import pvpbots.arena.StackDiff
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
 * crash. A stash file is only ever deleted after its items were put back AND the player's data was saved
 * ([Player.saveData]); one that cannot be read is kept, the inventory is left alone and no new stash is made over it.
 * Every kit item a player gets carries [KitApplier.KIT_TAG]; anything else that ends up in a kitted inventory (an
 * `/enderchest`, a trade or mail GUI, another plugin's `addItem`) is the player's own and is handed back on refill and
 * restore, never cleared. Main thread only; bots are ignored.
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

    /**
     * Restored, but [Player.saveData] failed, so the file is kept (a crash must not lose the items): not restored again
     * while the player stays online; the save and delete are retried when the player quits and when the plugin disables
     * or enables. Also written durably as [unsavedMarker], so a plugin reload keeps it; either one counts (see
     * [isUnsaved]). A join clears both: the inventory is then loaded from disk, which may predate the restore, so the
     * join restores again (see [restore]).
     */
    private val unsaved = HashSet<UUID>()

    /** An [unsavedMarker] a join could not delete: ignored for this player until a new failed save writes it again. */
    private val unsavedMarkerIgnored = HashSet<UUID>()

    /**
     * A restore of this player's stash failed: they still have whatever they had (kit plus anything gathered since), which
     * is now theirs. No kit rules apply to them (vanilla drops, no refill), and the next successful restore hands those
     * items back on top of the stash. Also written durably as [failedMarker] (holding the kit name), so the state
     * survives a restart, crash or reload; either one counts (see [restoreFailedState]).
     */
    private val restoreFailed = HashSet<UUID>()

    /** Writes the player's data to disk ([Player.saveData]); the self-test swaps it to observe or fail saves. */
    internal var savePlayer: (Player) -> Unit = { it.saveData() }

    /** Set while [stashAndGive] closes the player's inventory: items vanilla drops then are captured into the stash. */
    private var capturing: UUID? = null
    private val captured = ArrayList<Item>()

    private val kits get() = plugin.botManager.config.kits

    fun stashFile(id: UUID) = File(dir, "$id.dat")

    fun hasStash(id: UUID): Boolean = id !in spent && !isUnsaved(id) && stashFile(id).exists()

    /**
     * `stash/<uuid>.unsaved`: this player's stash was restored into their live inventory but their data could not be
     * saved, so the `.dat` is kept for a crash; until the next successful save it is not restored again.
     */
    fun unsavedMarker(id: UUID) = File(dir, "$id.unsaved")

    private fun isUnsaved(id: UUID): Boolean = id in unsaved || (id !in unsavedMarkerIgnored && unsavedMarker(id).exists())

    /** `stash/<uuid>.failed`: a restore of this player's stash failed; its text is the kit they were wearing (may be empty). */
    fun failedMarker(id: UUID) = File(dir, "$id.failed")

    private fun restoreFailedState(id: UUID): Boolean = id in restoreFailed || failedMarker(id).exists()

    /**
     * Wearing this arena's kit inside that arena (stash safe on disk, no failed restore): drops and pick-ups are blocked,
     * a death drops no kit item (own items that came in from outside drop as usual), and a new round refills the kit.
     * Anyone else follows vanilla rules, so their own items are never destroyed.
     */
    fun inKitArena(p: Player): Boolean = wearingKitHere(p.uniqueId)

    private fun wearingKitHere(id: UUID): Boolean {
        val k = kitted[id] ?: return false
        val area = areas[id] ?: return false
        return area.kit != null && area.arena == k.arena && !restoreFailedState(id) && hasStash(id)
    }

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
                if (k != null && !restoreFailedState(id) && k.arena == to!!.arena && k.kit == to.kit) return
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
        // Only inside the arena whose kit they wear, and never over items that became theirs after a failed restore.
        if (!wearingKitHere(owner) || p.isDead || owner in awaitingRespawn || k.kit !in kits.kits) return
        // Items that came in from outside (ender chest, trade GUI, another plugin) are the player's: kept, not cleared.
        val own = takeOwn(p)
        kits.applyToPlayer(p, k.kit)
        giveBack(p, own)
    }

    /**
     * Puts the stashed items back (inventory cleared first), saves the player's data, then deletes the file. True when
     * there was nothing to restore or it worked; false when the stash could not be read (file kept, inventory untouched,
     * player told, SEVERE logged) or the player is dead (the respawn restores it). Whatever the player holds now that
     * is not a tagged kit item (cursor included) is theirs and is handed back on top of the stash, leftovers dropped at
     * their feet. If the save fails the file is kept (see [unsaved]).
     *
     * [onJoin]: the inventory was loaded from disk (after a crash). With a kit item in it, it was saved while kitted and
     * its untagged items are handed back. With no kit item in it, it was saved either before the stash was made (then it
     * holds the stashed items again) or after an earlier restore whose stash was kept (a failed save or delete, then a
     * later autosave), or the kit items had left it: its untagged items minus ONE copy of the stash contents
     * ([StackDiff.minusOnce], `isSimilar`, by amount) are handed back. So the intended case (saved before the stash)
     * hands back nothing, and items gained or put in since are kept; the worst case is a duplicate (a stashed item
     * the player used up or gave away after an earlier restore comes back), never a loss.
     */
    fun restore(p: Player, onJoin: Boolean = false): Boolean {
        val id = p.uniqueId
        val file = stashFile(id)
        if (!hasStash(id)) {
            kitted.remove(id)
            if (id !in spent) clearFailed(id, p.name) // a marker without a stash (crash after the restore) means nothing
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
            // The cursor first (closing would put it in the inventory or drop it), then close: the crafting grid comes back.
            val cursor = p.itemOnCursor.takeUnless { it.type.isAir }?.clone()
            p.setItemOnCursor(null)
            p.closeInventory()
            val size = inv.contents.size
            // Not a kit item = the player's own (gathered after a failed restore, or put in from an ender chest, a GUI,
            // another plugin): handed back on top of the stash. Kit items (tagged) go.
            val savedBeforeStash = onJoin && !restoreFailedState(id) && (inv.contents.toList() + cursor).none { KitApplier.isKitItem(it) }
            val own = ownItems(inv.contents.toList() + cursor)
            val held = if (!savedBeforeStash) {
                own
            } else {
                // Possibly the stash again: hand back only what is not covered by one copy of it (never destroy).
                val stashed = items.filter { it != null && !it.type.isAir }.map { it!! }
                StackDiff.minusOnce(own, stashed, { a, b -> a.isSimilar(b) }, { it.amount })
                    .map { l -> l.stack.clone().also { it.amount = l.amount } }
            }
            val contents = arrayOfNulls<ItemStack>(size)
            for (i in 0 until minOf(size, items.size)) contents[i] = items[i]
            inv.contents = contents
            // Items beyond the slots (captured drops, or a smaller inventory in a later version) and the held own items:
            // nothing is left out.
            giveBack(p, items.drop(size).filterNotNull() + held)
        } catch (e: VirtualMachineError) {
            throw e
        } catch (e: Throwable) {
            failed(p, file, e)
            return false
        }
        kitted.remove(id)
        // The restored inventory must reach disk before the stash goes (Paper saves players only at autosave or quit);
        // when the save fails the file stays (see [unsaved]) and the items are still back in the inventory.
        saveAndDiscard(p, file)
        clearFailed(id, p.name) // after the stash is gone: a crash in between only leaves a harmless marker
        p.sendMessage("PvpBots: your own items are back.")
        return true
    }

    /**
     * Saves [p]'s data, then deletes [file] and the [unsavedMarker]. False (file kept, [unsaved] and the marker written,
     * SEVERE logged) when the save throws: a crash then still finds the stash, a reload does not restore it twice.
     */
    private fun saveAndDiscard(p: Player, file: File): Boolean {
        val id = p.uniqueId
        try {
            savePlayer(p)
        } catch (e: VirtualMachineError) {
            throw e
        } catch (e: Throwable) {
            unsaved += id
            unsavedMarkerIgnored -= id
            val marker = unsavedMarker(id)
            if (!marker.exists()) {
                try {
                    ArenaRegistry.writeDurably(marker, ByteArray(0))
                } catch (e2: Exception) {
                    plugin.logger.log(Level.SEVERE, "Could not write ${marker.absolutePath} for ${p.name}; their unsaved restore is remembered until restart or reload only", e2)
                }
            }
            plugin.logger.log(
                Level.SEVERE,
                "Restored the items of ${p.name} ($id) but their player data could not be saved; the stash ${file.absolutePath} is kept " +
                    "so a crash cannot lose them. Saving is retried when they quit. If it is still there after a clean quit, check " +
                    "their inventory before deleting it by hand (otherwise their next join hands the stash out again).",
                e,
            )
            return false
        }
        unsaved -= id
        discard(id, file, p.name)
        // Kept while the stash could not be deleted (spent): a reload must not hand it out again.
        if (!file.exists()) clearUnsavedMarker(id, p.name)
        return true
    }

    /** A restore whose save failed: try the save and the delete again (quit, disable, enable after a reload). */
    private fun retryUnsaved(p: Player) {
        val id = p.uniqueId
        if (!isUnsaved(id)) return
        val file = stashFile(id)
        if (!file.exists()) {
            unsaved -= id
            clearUnsavedMarker(id, p.name)
            return
        }
        if (saveAndDiscard(p, file)) plugin.logger.info("Saved ${p.name} on a retry; their restored stash is deleted")
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
            retryUnsaved(p)
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
        // A stash kept because a save failed: the inventory was just loaded from disk, which may predate that restore
        // (a crash before any later save), so it is restored again (the join rule hands back what was gained since).
        unsaved.remove(id)
        val m = unsavedMarker(id)
        if (m.exists() && !m.delete()) {
            unsavedMarkerIgnored += id
            plugin.logger.severe("Could not delete ${m.absolutePath} for ${p.name}; ignored for them until the server restarts (delete it by hand)")
        }
        if (!hasStash(id)) return
        if (p.isDead) {
            awaitingRespawn += id
            return
        }
        restore(p, onJoin = true)
    }

    /** PlayerQuitEvent (LOWEST, before the server saves the player). A dead player's stash stays on disk for later. */
    fun onQuit(p: Player) {
        val id = p.uniqueId
        if (isBot(id)) return
        retryUnsaved(p)
        if (hasStash(id) && !p.isDead) restore(p)
        kitted.remove(id)
        areas.remove(id)
        awaitingRespawn.remove(id)
    }

    /**
     * PlayerDeathEvent: wearing a kit in its arena: no kit item drops, no XP orbs, XP kept. The player's own items
     * (untagged: from an ender chest, a GUI, another plugin) drop as vanilla would, never destroyed.
     */
    fun onDeath(event: PlayerDeathEvent) {
        if (isBot(event.player.uniqueId) || !inKitArena(event.player)) return
        event.drops.removeIf { KitApplier.isKitItem(it) }
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

    /** PlayerRespawnEvent: [afterRespawn] on the next tick, once the respawn has finished. */
    fun onRespawn(p: Player) {
        if (isBot(p.uniqueId)) return
        Bukkit.getScheduler().runTask(plugin, Runnable { afterRespawn(p) })
    }

    /**
     * The tick after a respawn: the stash comes back, judged by where the player is now (the region watcher's current
     * area, not the arena they died in), so respawning outside restores and respawning inside a kit arena re-kits.
     */
    fun afterRespawn(p: Player) {
        val id = p.uniqueId
        if (isBot(id) || !p.isValid || p.isDead) return // quit or join handle it
        awaitingRespawn.remove(id)
        val area = plugin.regionWatcher.areaOf(id)
        if (area == null) areas.remove(id) else areas[id] = area
        apply(p, area, area)
    }

    /** PlayerDropItemEvent (HIGHEST): blocked while wearing a kit; drops from the inventory close in [stashAndGive] pass. */
    fun onDrop(event: PlayerDropItemEvent) {
        if (capturing == event.player.uniqueId) return
        if (inKitArena(event.player)) event.isCancelled = true
    }

    /** PlayerDropItemEvent (MONITOR): a drop that went through while [stashAndGive] closes the inventory joins the stash. */
    fun onDropDone(event: PlayerDropItemEvent) {
        if (capturing == event.player.uniqueId && !event.isCancelled) captured += event.itemDrop
    }

    /** EntityPickupItemEvent: no picking up while wearing a kit (loot stays on the ground for its owner). */
    fun onPickup(event: EntityPickupItemEvent) {
        val p = event.entity as? Player ?: return
        if (inKitArena(p)) event.isCancelled = true
    }

    /** PlayerPickupArrowEvent (arrows are not an EntityPickupItemEvent): blocked the same way. */
    fun onPickupArrow(event: PlayerPickupArrowEvent) {
        if (inKitArena(event.player)) event.isCancelled = true
    }

    /** Self-test: drop every in-memory note about [id] (the file is the caller's business). */
    internal fun forget(id: UUID) {
        kitted.remove(id)
        areas.remove(id)
        awaitingRespawn.remove(id)
        spent.remove(id)
        unsaved.remove(id)
        unsavedMarkerIgnored.remove(id)
        restoreFailed.remove(id)
        failedMarker(id).delete()
        unsavedMarker(id).delete()
    }

    /** Self-test: drop every in-memory note about [id] (what a reload or restart does); files and markers stay. */
    internal fun forgetInMemory(id: UUID) {
        kitted.remove(id)
        areas.remove(id)
        awaitingRespawn.remove(id)
        spent.remove(id)
        unsaved.remove(id)
        unsavedMarkerIgnored.remove(id)
        restoreFailed.remove(id)
    }

    /** Self-test: forget failed restores held in memory only (what a restart does); the marker file stays. */
    internal fun forgetFailuresInMemory(id: UUID) {
        restoreFailed.remove(id)
    }

    private fun stashAndGive(p: Player, area: AreaRef): Boolean {
        val id = p.uniqueId
        val kitName = area.kit ?: return false
        if (kitName !in kits.kits) {
            plugin.logger.warning("Kit arena ${area.arena}: kit '$kitName' is not in config.yml kits; ${p.name} keeps their own items")
            return false
        }
        val file = stashFile(id)
        if (file.exists() || id in spent || isUnsaved(id)) return false // never overwrite a stash
        // A failure marker without a stash is stale (the inventory is entirely the player's and is stashed now).
        clearFailed(id, p.name)
        // Cursor and crafting-grid items go back into the inventory; what vanilla drops because it is full is captured
        // and saved after the slots (restore hands those back with addItem).
        captured.clear()
        capturing = id
        try {
            p.closeInventory()
        } finally {
            capturing = null
        }
        val drops = captured.filter { it.isValid }
        captured.clear()
        try {
            val slots = p.inventory.contents.map { it?.takeUnless { s -> s.type.isAir }?.serializeAsBytes() } +
                drops.map { it.itemStack.serializeAsBytes() }
            writeNew(file, StashCodec.encode(slots))
        } catch (e: VirtualMachineError) {
            throw e
        } catch (e: Exception) {
            plugin.logger.log(Level.SEVERE, "Kit arena ${area.arena}: the inventory of ${p.name} ($id) could not be saved to ${file.absolutePath}; nothing was changed", e)
            p.sendMessage("PvpBots: your items could not be saved, so you keep them and fight in your own gear here.")
            return false
        }
        drops.forEach { it.remove() } // now in the stash
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

    /** Copies of the non-empty stacks in [stacks] that are not tagged kit items: the player's own. */
    private fun ownItems(stacks: List<ItemStack?>): List<ItemStack> =
        stacks.filter { it != null && !it.type.isAir && !KitApplier.isKitItem(it) }.map { it!!.clone() }

    /** The player's own items in inventory (armor, off-hand) and on the cursor, which is cleared; the inventory is not. */
    private fun takeOwn(p: Player): List<ItemStack> {
        val cursor = p.itemOnCursor.takeUnless { it.type.isAir }?.clone()
        p.setItemOnCursor(null)
        return ownItems(p.inventory.contents.toList() + cursor)
    }

    /** [items] into [p]'s inventory; what does not fit is dropped at their feet. */
    private fun giveBack(p: Player, items: List<ItemStack>) {
        if (items.isEmpty()) return
        p.inventory.addItem(*items.toTypedArray()).values.forEach { p.world.dropItemNaturally(p.location, it) }
    }

    /** Clears the failed state; a marker that cannot be deleted is only logged (worst case: kit items handed back as own). */
    private fun clearFailed(id: UUID, name: String) {
        restoreFailed.remove(id)
        val m = failedMarker(id)
        if (m.exists() && !m.delete()) {
            plugin.logger.warning("Could not delete ${m.absolutePath} for $name; delete it by hand (until then their kit-arena drops follow vanilla rules)")
        }
    }

    private fun clearUnsavedMarker(id: UUID, name: String) {
        unsavedMarkerIgnored -= id
        val m = unsavedMarker(id)
        if (m.exists() && !m.delete()) plugin.logger.warning("Could not delete ${m.absolutePath} for $name; delete it by hand (its stash is already gone)")
    }

    private fun failed(p: Player, file: File, e: Throwable) {
        restoreFailed += p.uniqueId
        val marker = failedMarker(p.uniqueId)
        if (!marker.exists()) {
            try {
                ArenaRegistry.writeDurably(marker, (kitted[p.uniqueId]?.kit ?: "").toByteArray(Charsets.UTF_8))
            } catch (e2: Exception) {
                plugin.logger.log(Level.SEVERE, "Could not write ${marker.absolutePath} for ${p.name}; their failed restore is remembered until restart only", e2)
            }
        }
        plugin.logger.log(
            Level.SEVERE,
            "Inventory stash of ${p.name} (${p.uniqueId}) could not be restored; the file is kept: ${file.absolutePath}. " +
                "The player is still wearing the arena kit (theirs now, vanilla rules); their own items are only in that file.",
            e,
        )
        p.sendMessage("Your items could not be restored automatically; an admin has been notified.")
    }

    private fun isBot(id: UUID) = plugin.botManager.isBot(id)
}
