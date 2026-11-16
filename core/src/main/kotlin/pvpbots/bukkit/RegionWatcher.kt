package pvpbots.bukkit

import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.util.Location as WeLocation
import com.sk89q.worldguard.LocalPlayer
import com.sk89q.worldguard.WorldGuard
import com.sk89q.worldguard.protection.ApplicableRegionSet
import com.sk89q.worldguard.protection.flags.EnumFlag
import com.sk89q.worldguard.protection.flags.IntegerFlag
import com.sk89q.worldguard.protection.flags.registry.FlagConflictException
import com.sk89q.worldguard.protection.regions.GlobalProtectedRegion
import com.sk89q.worldguard.protection.regions.ProtectedRegion
import com.sk89q.worldguard.session.MoveType
import com.sk89q.worldguard.session.Session
import com.sk89q.worldguard.session.handler.Handler
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import pvpbots.math.BlockPos
import pvpbots.math.Bounds
import pvpbots.profile.Difficulty
import pvpbots.region.AreaResolver
import pvpbots.region.RegionInfo
import pvpbots.session.AreaRef
import pvpbots.session.Role
import java.util.UUID

/**
 * Turns WorldGuard regions into area changes for the director. Boundary crossings come from a WorldGuard session
 * [Handler] (walking, teleports, pearls, world changes); every 20 ticks every online player is re-resolved too, so
 * flag or region edits mid-fight are picked up. WorldGuard only creates sessions for real online players, so bots
 * never reach this (the director also ignores bots).
 */
class RegionWatcher(private val plugin: JavaPlugin, private val events: Events) {
    /** Where area changes go (the plugin forwards them to the current director). */
    interface Events {
        fun onAreaChange(player: UUID, from: AreaRef?, to: AreaRef?)
        fun maxBots(): Int
    }

    private val current = HashMap<UUID, AreaRef>()
    private var factory: Factory? = null
    private var task: BukkitTask? = null

    /** Registers the three flags; must run in onLoad. False (after logging) on a flag conflict. */
    fun registerFlags(): Boolean {
        val registry = WorldGuard.getInstance().flagRegistry
        return try {
            registry.register(ROLE)
            registry.register(DIFFICULTY)
            registry.register(COUNT)
            true
        } catch (e: FlagConflictException) {
            plugin.logger.severe("WorldGuard flag conflict (${e.message}): another plugin already registered pvpbot-role, pvpbot-difficulty or pvpbot-count. PvpBots will be disabled.")
            false
        } catch (e: IllegalStateException) {
            plugin.logger.severe("WorldGuard flags could not be registered (${e.message}); PvpBots must be loaded before WorldGuard enables. PvpBots will be disabled.")
            false
        }
    }

    fun start() {
        val f = Factory(this)
        factory = f
        WorldGuard.getInstance().platform.sessionManager.registerHandler(f, null)
        task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { refreshAll() }, REFRESH_TICKS, REFRESH_TICKS)
    }

    fun stop() {
        factory?.let { WorldGuard.getInstance().platform.sessionManager.unregisterHandler(it) }
        factory = null
        task?.cancel()
        task = null
        current.clear()
    }

    /** Forget every remembered area (after a new director was built): players re-enter on the next refresh. */
    fun reset() = current.clear()

    fun forget(player: UUID) {
        current.remove(player)
    }

    fun areaOf(player: UUID): AreaRef? = current[player]

    /** Re-resolves every online player's area from their current position. */
    fun refreshAll() {
        val query = WorldGuard.getInstance().platform.regionContainer.createQuery()
        for (p in Bukkit.getOnlinePlayers()) {
            val set = query.getApplicableRegions(BukkitAdapter.adapt(p.location))
            update(p.uniqueId, p.world.name, set)
        }
    }

    private fun update(player: UUID, world: String, regions: Iterable<ProtectedRegion>) {
        val area = resolve(world, regions)
        val old = current[player]
        if (old == area) return
        if (area == null) current.remove(player) else current[player] = area
        events.onAreaChange(player, old, area)
    }

    private fun resolve(world: String, regions: Iterable<ProtectedRegion>): AreaRef? {
        val infos = regions.filter { it !is GlobalProtectedRegion }.map { r ->
            val min = r.minimumPoint
            val max = r.maximumPoint
            RegionInfo(
                world = world,
                id = r.id,
                priority = r.priority,
                role = r.getFlag(ROLE),
                difficulty = r.getFlag(DIFFICULTY),
                count = r.getFlag(COUNT),
                bounds = Bounds(BlockPos(min.x(), min.y(), min.z()), BlockPos(max.x(), max.y(), max.z())),
            )
        }
        return AreaResolver.resolve(infos, events.maxBots())
    }

    private fun worldName(l: WeLocation): String? = (l.extent as? com.sk89q.worldedit.world.World)?.name

    private class AreaHandler(session: Session, private val watcher: RegionWatcher) : Handler(session) {
        override fun initialize(player: LocalPlayer, current: WeLocation, set: ApplicableRegionSet) {
            report(player, current, set)
        }

        override fun onCrossBoundary(
            player: LocalPlayer, from: WeLocation, to: WeLocation, toSet: ApplicableRegionSet,
            entered: Set<ProtectedRegion>, exited: Set<ProtectedRegion>, moveType: MoveType,
        ): Boolean {
            report(player, to, toSet)
            return true
        }

        private fun report(player: LocalPlayer, at: WeLocation, set: ApplicableRegionSet) {
            val world = watcher.worldName(at) ?: return
            if (Bukkit.getPlayer(player.uniqueId) == null) return // only real online players
            watcher.update(player.uniqueId, world, set)
        }
    }

    private class Factory(private val watcher: RegionWatcher) : Handler.Factory<AreaHandler>() {
        override fun create(session: Session): AreaHandler = AreaHandler(session, watcher)
    }

    companion object {
        private const val REFRESH_TICKS = 20L

        val ROLE = EnumFlag("pvpbot-role", Role::class.java)
        val DIFFICULTY = EnumFlag("pvpbot-difficulty", Difficulty::class.java)
        val COUNT = IntegerFlag("pvpbot-count")
    }
}
