package pvpbots.bukkit

import org.bukkit.Location
import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.Player
import pvpbots.boss.BossType
import pvpbots.boss.Move
import pvpbots.boss.MoveSelector
import pvpbots.boss.MoveType
import pvpbots.boss.Phase
import pvpbots.boss.PhaseTracker
import java.util.UUID
import kotlin.random.Random

/**
 * The boss's decisions (spec 5.4, 5.5): aggro, move choice, tell -> move -> recovery, phases with their roar, and the
 * cheese guards. [body] does the walking and looking; [BossMoves] the effects. Main thread only.
 *
 * - Aggro: re-picked every 2 s (or when the target is gone): most damage in the last 10 s, else the nearest
 *   participant; each pick has a 20% chance to go to a random participant instead. Only participants are targets.
 * - Chase: a usable move ([MoveSelector.pick] with distance and cooldowns) starts its tell
 *   ([Phase.scaledTelegraph], never under 10 ticks for a damaging move); else walk to the target, strafe when close.
 * - Recovery after a move: slowed to half speed, takes 1.5x damage ([recovering]).
 * - Phases: on crossing a threshold the bar colour changes, the moveset changes and (if set) the boss roars:
 *   invulnerable ([roaring]) and pushing players back.
 * - Guards: a target more than 4 blocks above, or no progress (closer by 0.5 blocks, or a move) for 3 s, starts a
 *   leap or ranged move at once, cooldown and range aside.
 */
class BossBrain(
    private val type: BossType,
    private val body: BossController,
    private val fraction: () -> Double,
    private val inArena: (Location) -> Boolean,
    private val onPhase: (Int) -> Unit,
    private val tracer: () -> ((String) -> Unit)?,
    private val rng: Random = Random.Default,
) {
    private enum class State { CHASE, TELL, EXEC, RECOVER }

    private class Dealt(val by: UUID, val amount: Double, val tick: Long)

    private val dealt = ArrayDeque<Dealt>()
    private var tick = 0L
    private var state = State.CHASE
    private var move: Move? = null
    private var left = 0
    private var total = 0
    private var run: MoveRun? = null
    private val readyAt = HashMap<String, Long>()
    private var phaseIndex = -1
    private var active: List<Move> = PhaseTracker.activeMoves(type.moves, type.phases, -1)
    private var pendingRoar = false
    private var forced: Move? = null
    private var progressTick = 0L
    private var bestDist = Double.MAX_VALUE
    private var lastPick = Long.MIN_VALUE / 2
    private var strafeDir = 1f
    private var speed = Double.NaN
    private var alive: List<Player> = emptyList()
    private val arrows = ArrayList<AbstractArrow>()

    /** The player the boss is after right now. */
    var target: Player? = null
        private set

    /** In a move's recovery: the punish window (x1.5 damage taken). */
    val recovering: Boolean get() = state == State.RECOVER

    /** Roaring: hits are ignored. */
    val roaring: Boolean get() = state == State.EXEC && move?.type == MoveType.ROAR

    /** Doing anything but chasing (tell, move, recovery). */
    val busy: Boolean get() = state != State.CHASE

    /** Index of the phase in force (-1 before the first). */
    val phase: Int get() = phaseIndex

    fun activeMoveIds(): List<String> = active.map { it.id }

    /** Self-test: [id]'s tell starts at the next decision, range and cooldown aside. False if the type has no such move. */
    fun force(id: String): Boolean {
        forced = type.moves.firstOrNull { it.id == id } ?: return false
        return true
    }

    /** [by] took [amount] off the boss's pool: counts towards aggro for 10 s. */
    fun recordDamage(by: UUID, amount: Double) {
        if (amount > 0.0) dealt.addLast(Dealt(by, amount, tick))
    }

    /** Intro: stands still. */
    fun idle() {
        tick++
        cleanArrows()
        body.stand()
    }

    /** The director put the boss back at the centre: a move in flight is dropped (no damage). */
    fun onTeleportedBack() {
        if (state == State.EXEC) finishMove()
        bestDist = Double.MAX_VALUE
    }

    /** The boss is going away: its arrows go too. */
    fun close() {
        arrows.forEach { it.remove() }
        arrows.clear()
    }

    fun tick(participants: List<Player>) {
        tick++
        while (dealt.isNotEmpty() && tick - dealt.first().tick > AGGRO_TICKS) dealt.removeFirst()
        cleanArrows()
        val boss = body.bot
        if (boss.isDead) return
        alive = participants.filter { it.isValid && !it.isDead && it.world == boss.world }
        updatePhase()
        val cur = target
        if (cur == null || cur !in alive || tick - lastPick >= PICK_TICKS) pickTarget(boss)
        applySpeed()
        val t = target
        when (state) {
            State.TELL -> tell(t)
            State.EXEC -> exec(t)
            State.RECOVER -> {
                if (pendingRoar) return startRoar(t)
                if (t != null) body.chase(t, sprint = false) else body.stand()
                if (--left <= 0) state = State.CHASE
            }
            State.CHASE -> chase(boss, t)
        }
    }

    private fun chase(boss: Player, t: Player?) {
        if (pendingRoar) return startRoar(t)
        if (t == null) return body.stand()
        forced?.let {
            forced = null
            return start(it, t)
        }
        val bl = boss.location
        val tl = t.location
        val dist = bl.distance(tl)
        if (dist < bestDist - PROGRESS) {
            bestDist = dist
            progressTick = tick
        }
        if (tl.y - bl.y > ABOVE || tick - progressTick >= STUCK_TICKS) {
            guardMove()?.let {
                tracer()?.invoke("guard ${it.id} (${if (tl.y - bl.y > ABOVE) "target above" else "no progress"})")
                return start(it, t)
            }
        }
        val cooldowns = HashMap<String, Int>(active.size)
        for (m in active) cooldowns[m.id] = ((readyAt[m.id] ?: 0L) - tick).toInt()
        val pick = MoveSelector.pick(active.filter { it.type != MoveType.ROAR }, dist, cooldowns, rng)
        if (pick != null) return start(pick, t)
        if (tick % STRAFE_FLIP == 0L) strafeDir = -strafeDir
        if (dist > CLOSE) body.chase(t, sprint = true) else body.strafe(t, strafeDir)
    }

    private fun start(m: Move, t: Player?) {
        move = m
        total = (phaseNow() ?: NEUTRAL).scaledTelegraph(m)
        left = total
        state = State.TELL
        progressTick = tick
        bestDist = Double.MAX_VALUE
        if (total <= 0) return startExec(t)
        t?.let { body.face(it) }
        body.stand()
        BossMoves.telegraph(context, m, left, total)
    }

    private fun tell(t: Player?) {
        val m = move ?: return finishMove()
        t?.let { body.face(it) }
        body.stand()
        if (--left <= 0) return startExec(t)
        BossMoves.telegraph(context, m, left, total)
    }

    private fun startExec(t: Player?) {
        val m = move ?: return finishMove()
        val r = BossMoves.execute(context, m, t)
        if (r == null) { // the target went away during the tell: nothing lands
            state = State.CHASE
            move = null
            return
        }
        run = r
        state = State.EXEC
        exec(t)
    }

    private fun exec(t: Player?) {
        val r = run ?: return finishMove()
        if (r.mobile && t != null) body.chase(t, sprint = false) else {
            if (t != null && move?.type != MoveType.LEAP_SLAM && move?.type != MoveType.BACKSTEP) body.face(t)
            body.stand()
        }
        if (r.tick()) finishMove()
    }

    private fun finishMove() {
        val m = move
        run = null
        move = null
        progressTick = tick
        if (m == null) {
            state = State.CHASE
            return
        }
        readyAt[m.id] = tick + m.cooldownTicks
        left = m.recoveryTicks
        state = if (left > 0) State.RECOVER else State.CHASE
    }

    private fun startRoar(t: Player?) {
        pendingRoar = false
        start(type.moves.firstOrNull { it.type == MoveType.ROAR } ?: DEFAULT_ROAR, t)
    }

    private fun updatePhase() {
        val idx = PhaseTracker.phaseAt(type.phases, fraction())
        if (idx <= phaseIndex) return
        val roar = (phaseIndex + 1..idx).any { type.phases[it].roar }
        phaseIndex = idx
        active = PhaseTracker.activeMoves(type.moves, type.phases, idx)
        if (roar) pendingRoar = true
        onPhase(idx)
    }

    private fun phaseNow(): Phase? = type.phases.getOrNull(phaseIndex)

    private fun applySpeed() {
        val s = type.moveSpeed * (phaseNow()?.moveSpeed ?: 1.0) * (if (state == State.RECOVER) RECOVERY_SLOW else 1.0)
        if (s != speed) {
            speed = s
            body.setSpeed(s)
        }
    }

    /** A leap or ranged move for the guards: one off cooldown (weighted) if any, else the one ready soonest. */
    private fun guardMove(): Move {
        val pool = active.filter { it.type in GUARD_TYPES }.ifEmpty { type.moves.filter { it.type in GUARD_TYPES } }
        if (pool.isEmpty()) return GUARD_LEAP
        val ready = pool.filter { (readyAt[it.id] ?: 0L) <= tick }
        if (ready.isEmpty()) return pool.minBy { readyAt[it.id] ?: 0L }
        var roll = rng.nextInt(ready.sumOf { maxOf(1, it.weight) })
        for (m in ready) {
            roll -= maxOf(1, m.weight)
            if (roll < 0) return m
        }
        return ready.last()
    }

    private fun pickTarget(boss: Player) {
        lastPick = tick
        val before = target
        target = when {
            alive.isEmpty() -> null
            rng.nextDouble() < RANDOM_AGGRO -> alive[rng.nextInt(alive.size)]
            else -> {
                val byDamage = HashMap<UUID, Double>()
                for (d in dealt) byDamage.merge(d.by, d.amount, Double::plus)
                alive.filter { (byDamage[it.uniqueId] ?: 0.0) > 0.0 }.maxByOrNull { byDamage.getValue(it.uniqueId) }
                    ?: boss.location.let { l -> alive.minByOrNull { it.location.distanceSquared(l) } }
            }
        }
        if (target != before) bestDist = Double.MAX_VALUE
    }

    private fun cleanArrows() {
        if (arrows.isEmpty()) return
        arrows.removeIf { a ->
            val gone = !a.isValid || a.isInBlock || a.isOnGround || a.ticksLived > ARROW_LIFE
            if (gone) a.remove()
            gone
        }
    }

    private val context = object : MoveContext {
        override val boss: Player get() = body.bot
        override fun swing() = body.swing()
        override fun inReach(target: Player) = body.inReach(target)
        override fun victims(): List<Player> = alive.filter { it.isValid && !it.isDead }
        override fun inArena(l: Location) = this@BossBrain.inArena(l)
        override fun track(arrow: AbstractArrow) {
            arrows += arrow
        }
        override fun trace(line: () -> String) {
            tracer()?.invoke(line())
        }
    }

    private companion object {
        const val AGGRO_TICKS = 200L
        const val PICK_TICKS = 40L
        const val RANDOM_AGGRO = 0.2
        const val STUCK_TICKS = 60L
        const val ABOVE = 4.0
        const val PROGRESS = 0.5
        const val CLOSE = 2.8
        const val STRAFE_FLIP = 40L
        const val RECOVERY_SLOW = 0.5
        const val ARROW_LIFE = 100
        val GUARD_TYPES = setOf(MoveType.LEAP_SLAM, MoveType.VOLLEY, MoveType.CHARGED_SHOT)
        val NEUTRAL = Phase(1.0, false, 1.0, 1.0, emptyList(), emptyList())
        val DEFAULT_ROAR = Move("roar", MoveType.ROAR, 0, 0.0..0.0, 0, 0.0, 0, 0, mapOf("invulnerable-ticks" to 40.0))

        /** For a boss type without any leap or ranged move: a harmless leap to the target. */
        val GUARD_LEAP = Move("guard-leap", MoveType.LEAP_SLAM, 0, 0.0..0.0, 10, 0.0, 0, 10, mapOf("radius" to 0.0))
    }
}
