package pvpbots.bukkit

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.LivingEntity
import org.bukkit.inventory.EquipmentSlot
import pvpbots.adapter.BotHandle
import pvpbots.adapter.BotInput
import pvpbots.bukkit.BotProfiler.Counter
import pvpbots.bukkit.BotProfiler.Phase
import pvpbots.bukkit.PerceptionBuilder.aabb
import pvpbots.bukkit.PerceptionBuilder.vec
import pvpbots.combat.Action
import pvpbots.combat.AllyBrain
import pvpbots.combat.BrainState
import pvpbots.combat.Intent
import pvpbots.combat.MoveStyle
import pvpbots.combat.Perception
import pvpbots.combat.RefreshClock
import pvpbots.combat.TargetView
import pvpbots.combat.TrainerBrain
import pvpbots.math.Aim
import pvpbots.math.BlockPos
import pvpbots.math.FairHit
import pvpbots.math.InputMapper
import pvpbots.math.MoveInput
import pvpbots.math.Rotation
import pvpbots.math.Vec3
import pvpbots.path.Pathfinder
import pvpbots.path.ReachCheck
import pvpbots.path.SpawnPointFinder
import pvpbots.path.StuckDetector
import pvpbots.path.Verdict
import pvpbots.profile.DifficultyProfile
import pvpbots.session.AreaRef
import pvpbots.session.Role
import pvpbots.session.SessionKey
import kotlin.math.abs
import kotlin.math.floor
import kotlin.random.Random

/**
 * One bot's per-tick loop (spec §7.1): perceive (target fields every `reactionTicks`), decide (brain, or stand
 * during the countdown), aim (turn-speed limited, with aim error), move (A* waypoints -> [InputMapper]) and act
 * through the adapter. Perception building lives in [PerceptionBuilder].
 */
class BotController(
    private val manager: BotManager,
    private val handle: BotHandle,
    private val key: SessionKey,
    private val role: Role,
    profile: DifficultyProfile,
    private val area: AreaRef,
    initialLook: Rotation,
) {
    /** The settings the bot fights with now; only the number settings change during a fight ([retune]). */
    var profile: DifficultyProfile = profile
        private set
    private val rng = Random.Default
    private val brain = BrainState(profile, rng)
    private val stuck = StuckDetector(manager.config.settings.stuckRetrySeconds * 20, manager.config.settings.stuckTeleportSeconds * 20)
    private val adapter get() = manager.adapter

    private var look = initialLook
    private val refreshClock = RefreshClock(profile.reactionTicks, rng)
    private val reachCheck = ReachCheck()
    private var target: LivingEntity? = null
    private var targetView: TargetView? = null
    private var followPos: Vec3? = null
    private var aimOffset = Rotation(0f, 0f)

    private var path: List<BlockPos> = emptyList()
    private var pathIndex = 0
    private var pathGoal: Vec3? = null
    private var pathTick = NEVER
    private var retried = false
    private var pendingRelease = false

    // Diagnostics (self-test / debugging): counts since spawn.
    private var statTicks = 0
    private var statNoTarget = 0
    private var statAttackIntents = 0
    private var statHits = 0
    private var statMinDist = Double.MAX_VALUE
    private var statLastIntent: Intent? = null

    /** One-line diagnostic summary of this bot's behaviour so far. */
    fun debugSummary(): String =
        "ticks=$statTicks noTarget=$statNoTarget attackIntents=$statAttackIntents fairHits=$statHits " +
            "minDist=${if (statMinDist == Double.MAX_VALUE) "-" else "%.2f".format(statMinDist)} " +
            "cooldown=${"%.2f".format(handle.bukkit.attackCooldown)} look=$look last=$statLastIntent"

    /**
     * Swaps in a re-blended profile mid-fight. The brain state is kept (the cooldown threshold is re-sampled on the
     * next swing as before), and the refresh clock uses the new reaction time from its next check on.
     */
    fun retune(newProfile: DifficultyProfile) {
        profile = newProfile
        refreshClock.setPeriod(newProfile.reactionTicks)
    }

    fun tick(tick: Long) {
        val bot = handle.bukkit
        // Left its area's world (e.g. teleported by another plugin): it can never get back, so it ends like a failure.
        check(bot.world.name == area.world) { "bot is in world ${bot.world.name}, its area is in ${area.world}" }
        if (bot.isDead) { // the death listener removes it next tick
            adapter.setInput(handle, BotInput(0f, 0f, false, false, false, look.yaw, look.pitch))
            return
        }
        if (pendingRelease) { // the release pitch was applied by the bot's own tick: shoot now
            pendingRelease = false
            adapter.stopUsing(handle)
        }
        val liveTarget = target?.takeIf { it.isValid && !it.isDead }
        if (refreshClock.due(tick) || (target != null && liveTarget == null)) {
            BotProfiler.timed(Phase.REFRESH) { refreshTarget(bot, tick) }
            refreshClock.mark(tick)
        }
        val perception = BotProfiler.timed(Phase.PERCEIVE) { PerceptionBuilder.perceive(bot, look, targetView, reachCheck.reachable, followPos) }
        statTicks++
        val tv = targetView
        if (tv == null) statNoTarget++ else statMinDist = minOf(statMinDist, (tv.pos - perception.pos).length())
        var intent = BotProfiler.timed(Phase.DECIDE) {
            if (manager.director.fighting(handle.id)) {
                if (role == Role.TRAINER) TrainerBrain.decide(perception, profile, brain, rng)
                else AllyBrain.decide(perception, profile, brain, rng)
            } else {
                Intent(MoveStyle.STAND, null, targetView?.let(::chest), false, Action.NONE, -1, null)
            }
        }
        if (role == Role.ALLY && !area.bounds.contains(perception.pos)) {
            val back = area.bounds.clamp(perception.pos)
            intent = intent.copy(style = MoveStyle.WALK, moveTarget = back, lookAt = intent.lookAt ?: back.copy(y = perception.eye.y))
        }

        // Move target -> next waypoint; jump up steps; stuck handling.
        var jump = intent.jump
        val waypoint = BotProfiler.timed(Phase.PATH) {
            intent.moveTarget?.takeIf { intent.style == MoveStyle.WALK || intent.style == MoveStyle.APPROACH_SPRINT }
                ?.let { goal -> nextWaypoint(perception.pos, goal, tick) }
        }
        val steerStart = if (BotProfiler.enabled) System.nanoTime() else 0L
        if (waypoint != null && waypoint.y > perception.pos.y + 0.5 && perception.onGround) jump = true
        jump = jump or handleStuck(intent, perception, tick)

        // Aim.
        val desired = when {
            intent.lookAt != null -> offset(Aim.lookAt(perception.eye, intent.lookAt))
            waypoint != null -> Aim.lookAt(perception.eye, waypoint.copy(y = perception.eye.y))
            else -> look
        }
        look = Aim.turnToward(look, desired, profile.turnSpeedDeg)
        if (intent.action == Action.RELEASE_BOW && intent.bowPitch != null) look = Rotation(desired.yaw, intent.bowPitch.toFloat())

        val move = moveInput(intent, perception, waypoint)
        val sprint = intent.style == MoveStyle.APPROACH_SPRINT
        adapter.setInput(handle, BotInput(move.forward, move.strafe, jump, sprint, false, look.yaw, look.pitch))
        if (BotProfiler.enabled) BotProfiler.nanos[Phase.STEER.ordinal] += System.nanoTime() - steerStart

        statLastIntent = intent
        BotProfiler.timed(Phase.ACT) { act(intent, perception) }
    }

    private fun refreshTarget(bot: org.bukkit.entity.Player, tick: Long) {
        val isBot = manager::isBot
        target = when (val k = key) {
            is SessionKey.Trainer -> PerceptionBuilder.livingTarget(bot, k.owner)
            is SessionKey.Squad -> BotProfiler.timed(Phase.SCAN) { PerceptionBuilder.allyTarget(bot, area.bounds, isBot) }
        }
        targetView = target?.let(PerceptionBuilder::targetView)
        followPos = if (role == Role.ALLY) PerceptionBuilder.followPos(bot, area.bounds, isBot) else null
        aimOffset = Aim.withError(Rotation(0f, 0f), profile.aimErrorDeg, rng)
        val tv = targetView
        if (role == Role.ALLY && tv != null) {
            val pos = vec(bot.location)
            if (reachCheck.due(tick, tv.id, pos, tv.pos)) {
                BotProfiler.timed(Phase.REACHABLE) { reachCheck.record(tick, tv.id, pos, tv.pos, reachableOnFoot(pos, tv.pos, tick)) }
            }
        }
    }

    /**
     * Allies switch to the bow when no walkable path ends within 2 blocks of the target. A path found here becomes the
     * bot's current path toward the target, so [nextWaypoint] does not search the same route again this tick.
     */
    private fun reachableOnFoot(pos: Vec3, goal: Vec3, tick: Long): Boolean {
        if (pos.horizontalDistance(goal) <= 2.0 && abs(goal.y - pos.y) < 1.5) return true
        BotProfiler.count(Counter.ASTAR_REACHABLE)
        val found = Pathfinder.find(BukkitBlockGrid(handle.bukkit.world), feet(pos), feet(goal), area.bounds)
        if (found.isEmpty()) return false
        path = found
        pathIndex = 0
        pathGoal = goal
        pathTick = tick
        return true
    }

    private fun chest(t: TargetView): Vec3 = (t.box.min + t.box.max) * 0.5

    private fun offset(r: Rotation): Rotation = Rotation(
        Aim.wrapYaw((r.yaw + aimOffset.yaw).toDouble()).toFloat(),
        (r.pitch + aimOffset.pitch).coerceIn(-90f, 90f),
    )

    private fun feet(p: Vec3) = BlockPos(floor(p.x).toInt(), floor(p.y + 1e-3).toInt(), floor(p.z).toInt())

    private fun centre(b: BlockPos) = Vec3(b.x + 0.5, b.y.toDouble(), b.z + 0.5)

    /** A* path toward [goal], recomputed every 10 ticks or when the goal moved more than 2 blocks. */
    private fun nextWaypoint(pos: Vec3, goal: Vec3, tick: Long): Vec3 {
        val old = pathGoal
        if (old == null || (goal - old).length() > 2.0 || tick - pathTick >= REPATH_TICKS) {
            val world = handle.bukkit.world
            BotProfiler.count(Counter.ASTAR_WAYPOINT)
            path = Pathfinder.find(BukkitBlockGrid(world), feet(pos), feet(goal), area.bounds)
            pathIndex = 0
            pathGoal = goal
            pathTick = tick
        }
        while (pathIndex < path.size) {
            val c = centre(path[pathIndex])
            if (c.horizontalDistance(pos) < 0.35 && abs(c.y - pos.y) < 1.0) pathIndex++ else break
        }
        return if (pathIndex < path.size) centre(path[pathIndex]) else goal
    }

    /** Returns true to request a jump. RETRY: jump + new path (once); TELEPORT: safe point near the goal. */
    private fun handleStuck(intent: Intent, p: Perception, tick: Long): Boolean {
        val goal = intent.moveTarget
        val travelling = goal != null && (intent.style == MoveStyle.WALK || intent.style == MoveStyle.APPROACH_SPRINT) &&
            (goal - p.pos).length() > STUCK_MIN_DIST
        if (!travelling) {
            stuck.reset(); retried = false
            return false
        }
        when (stuck.update(tick, p.pos, goal)) {
            Verdict.OK -> retried = false
            Verdict.RETRY -> if (!retried) {
                retried = true
                pathTick = NEVER
                return true
            }
            Verdict.TELEPORT -> {
                teleportNear(goal)
                stuck.reset(); retried = false
                pathTick = NEVER
            }
        }
        return false
    }

    private fun teleportNear(goal: Vec3) {
        val bot = handle.bukkit
        val grid = BukkitBlockGrid(bot.world)
        val g = feet(goal)
        var spot: BlockPos? = null
        search@ for (r in 0..3) for (dx in -r..r) for (dz in -r..r) {
            if (maxOf(abs(dx), abs(dz)) != r) continue
            for (dy in 2 downTo -3) {
                val c = BlockPos(g.x + dx, g.y + dy, g.z + dz)
                if (area.bounds.contains(c) && grid.standable(c)) { spot = c; break@search }
            }
        }
        val dest = spot ?: SpawnPointFinder.find(grid, g, area.bounds, rng) ?: return
        bot.teleport(Location(bot.world, dest.x + 0.5, dest.y.toDouble(), dest.z + 0.5, look.yaw, look.pitch))
        path = emptyList()
    }

    private fun moveInput(intent: Intent, p: Perception, waypoint: Vec3?): MoveInput {
        val t = intent.moveTarget ?: intent.lookAt
        return when (intent.style) {
            MoveStyle.STAND, MoveStyle.WTAP_RELEASE -> MoveInput(0f, 0f)
            MoveStyle.WALK, MoveStyle.APPROACH_SPRINT -> waypoint?.let { InputMapper.toward(look, p.pos, it) } ?: MoveInput(0f, 0f)
            MoveStyle.RETREAT -> t?.let { InputMapper.toward(look, p.pos, p.pos + (p.pos - it)) } ?: MoveInput(0f, 0f)
            MoveStyle.STRAFE_LEFT, MoveStyle.STRAFE_RIGHT -> {
                if (t == null) return MoveInput(0f, 0f)
                val toward = InputMapper.toward(look, p.pos, t) // unit vector to the target, look frame (+strafe = left)
                val side = if (intent.style == MoveStyle.STRAFE_LEFT) 1f else -1f
                val d = p.pos.horizontalDistance(t)
                val radial = when { d > 3.0 -> 0.6f; d < 2.0 -> -0.6f; else -> 0f }
                // Tangent = toward rotated 90 degrees to the left: (f, s) -> (-s, f).
                val f = radial * toward.forward + side * -toward.strafe
                val s = radial * toward.strafe + side * toward.forward
                MoveInput(f.coerceIn(-1f, 1f), s.coerceIn(-1f, 1f))
            }
        }
    }

    private fun act(intent: Intent, p: Perception) {
        val bot = handle.bukkit
        if (intent.selectSlot in 0..8 && intent.selectSlot != bot.inventory.heldItemSlot) adapter.selectSlot(handle, intent.selectSlot)
        // The brain moved on from a main-hand use (half-drawn bow, eating): cancel it.
        if (PerceptionBuilder.usingMainHand(bot) && intent.action != Action.DRAW_BOW && intent.action != Action.RELEASE_BOW &&
            intent.action != Action.EAT
        ) {
            adapter.stopUsing(handle)
        }
        // Nothing to defend against: lower a raised shield.
        if (p.target == null && bot.isHandRaised && bot.handRaised == EquipmentSlot.OFF_HAND) adapter.stopUsing(handle)

        val heldType = bot.inventory.itemInMainHand.type
        when (intent.action) {
            Action.NONE -> {}
            Action.ATTACK -> {
                statAttackIntents++
                val t = target?.takeIf { it.isValid && !it.isDead }
                if (t != null && FairHit.hits(p.eye, look, aabb(t.boundingBox), PerceptionBuilder.REACH)) {
                    statHits++
                    adapter.attack(handle, t) // attack before swing: swing resets the attack cooldown
                }
                adapter.swing(handle)
            }
            Action.RAISE_SHIELD ->
                if (!bot.isHandRaised && bot.inventory.itemInOffHand.type == Material.SHIELD) adapter.startUsing(handle, EquipmentSlot.OFF_HAND)
            Action.LOWER_SHIELD -> if (bot.isHandRaised) adapter.stopUsing(handle)
            Action.EAT -> startMainHand(bot, heldType.isEdible)
            Action.DRAW_BOW -> startMainHand(bot, heldType == Material.BOW)
            Action.RELEASE_BOW -> if (PerceptionBuilder.usingMainHand(bot)) pendingRelease = true
            Action.SWAP_TOTEM -> adapter.swapHands(handle)
        }
    }

    private fun startMainHand(bot: org.bukkit.entity.Player, rightItem: Boolean) {
        if (!rightItem) return
        if (bot.isHandRaised && bot.handRaised == EquipmentSlot.OFF_HAND) adapter.stopUsing(handle) // shield down first
        else if (!bot.isHandRaised) adapter.startUsing(handle, EquipmentSlot.HAND)
    }

    private companion object {
        const val REPATH_TICKS = 10

        /** "Long ago" for tick timestamps; not Long.MIN_VALUE, since `tick - MIN_VALUE` overflows. */
        const val NEVER = -1_000_000_000L
        const val STUCK_MIN_DIST = 3.5
    }
}
