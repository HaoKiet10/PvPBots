package pvpbots.bukkit

import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.Arrow
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.util.Vector
import pvpbots.boss.LeapArc
import pvpbots.boss.Move
import pvpbots.boss.MoveType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** What a move needs from the boss that runs it (spec 5.4). Main thread only. */
interface MoveContext {
    val boss: Player

    /** Swing animation. */
    fun swing()

    /** The fair-hit check: the boss's current look ray hits [target]'s box at combat reach. */
    fun inReach(target: Player): Boolean

    /** The only players a move may hurt: the arena's living participants. */
    fun victims(): List<Player>

    /** The arena's disc membership (same rule as [BossDirector]). */
    fun inArena(l: Location): Boolean

    /** An arrow the boss shot: no pickup, removed after landing. */
    fun track(arrow: AbstractArrow)

    fun trace(line: () -> String)
}

/** A move running after its tell. [tick] returns true when it is over. */
interface MoveRun {
    /** The boss may walk towards its target meanwhile (combo swings); otherwise it stands (or flies). */
    val mobile: Boolean get() = false

    fun tick(): Boolean
}

/**
 * The boss moveset's effects (spec 5.4, 8): tells, melee combos through the fair-hit path, leaps, ground rings, arrows,
 * backsteps and roars. Every hit is a normal attack by the boss (`damage(amount, boss)`, so armour applies) on a
 * participant only; the damage listener's policy still has the last word.
 */
object BossMoves {
    /** One tick of [move]'s tell, [ticksLeft] of [total] left: a sound on the first tick, gathering particles every tick. */
    fun telegraph(ctx: MoveContext, move: Move, ticksLeft: Int, total: Int) {
        val boss = ctx.boss
        val l = boss.location
        val w = boss.world
        if (ticksLeft == total) {
            val (sound, pitch) = when (move.type) {
                MoveType.COMBO -> Sound.ENTITY_EVOKER_PREPARE_ATTACK to 1.4f
                MoveType.LEAP_SLAM -> Sound.ENTITY_RAVAGER_ATTACK to 0.6f
                MoveType.SHOCKWAVE -> Sound.ENTITY_WARDEN_SONIC_CHARGE to 1.0f
                MoveType.VOLLEY, MoveType.CHARGED_SHOT -> Sound.ITEM_CROSSBOW_LOADING_MIDDLE to 0.7f
                MoveType.BACKSTEP -> Sound.ENTITY_PHANTOM_FLAP to 1.2f
                MoveType.ROAR -> Sound.ENTITY_RAVAGER_STUNNED to 0.8f
            }
            w.playSound(l, sound, 1.6f, pitch)
            ctx.trace { "tell ${move.id} ${move.type} $total sound played" }
        }
        val particle = when (move.type) {
            MoveType.COMBO -> Particle.CRIT
            MoveType.LEAP_SLAM, MoveType.SHOCKWAVE -> Particle.FLAME
            MoveType.VOLLEY, MoveType.CHARGED_SHOT -> Particle.ENCHANTED_HIT
            MoveType.BACKSTEP -> Particle.CLOUD
            MoveType.ROAR -> Particle.LARGE_SMOKE
        }
        // A ring closing in on the boss as the tell runs out.
        val r = 0.4 + 1.4 * ticksLeft / total.coerceAtLeast(1)
        val spin = ticksLeft * 0.5
        for (i in 0 until 6) {
            val a = spin + i * PI / 3
            w.spawnParticle(particle, l.x + cos(a) * r, l.y + 1.1, l.z + sin(a) * r, 1, 0.0, 0.0, 0.0, 0.0)
        }
        ctx.trace { "tellfx ${move.id}" }
    }

    /** Starts [move] at the end of its tell against [target] (null only for a roar). Null when it cannot run. */
    fun execute(ctx: MoveContext, move: Move, target: Player?): MoveRun? = when (move.type) {
        MoveType.ROAR -> Roar(ctx, move)
        MoveType.SHOCKWAVE -> Shockwave(ctx, move)
        else -> if (target == null) null else when (move.type) {
            MoveType.COMBO -> Combo(ctx, move, target)
            MoveType.LEAP_SLAM -> Leap(ctx, move, target)
            MoveType.VOLLEY -> Volley(ctx, move, target, (move.params["arrows"] ?: 3.0).toInt().coerceAtLeast(1), (move.params["bursts"] ?: 1.0).toInt().coerceAtLeast(1), VOLLEY_SPEED)
            MoveType.CHARGED_SHOT -> Volley(ctx, move, target, 1, 1, CHARGED_SPEED)
            MoveType.BACKSTEP -> Backstep(ctx, move, target)
            else -> null
        }
    }

    /**
     * A move hit on [p]: a normal attack by the boss (armour applies), every hit counted (no hurt window between combo
     * hits). True when it landed (not cancelled by the damage rules or another plugin).
     */
    private fun hurt(ctx: MoveContext, move: Move, p: Player): Boolean {
        if (move.damage <= 0.0 || p.isDead || !p.isValid) return false
        p.noDamageTicks = 0
        p.lastDamageCause = null
        p.damage(move.damage, ctx.boss)
        ctx.trace { "hit ${move.id} ${p.name}" }
        // Bukkit records the cause only for a damage event that was not cancelled.
        return (p.lastDamageCause as? EntityDamageByEntityEvent)?.damager == ctx.boss
    }

    private class Combo(val ctx: MoveContext, val move: Move, val target: Player) : MoveRun {
        val hits = (move.params["hits"] ?: 3.0).toInt().coerceAtLeast(1)
        val interval = (move.params["interval-ticks"] ?: 8.0).toInt().coerceAtLeast(1)
        var t = 0
        var done = 0
        override val mobile get() = true

        override fun tick(): Boolean {
            if (t % interval == 0) {
                ctx.swing()
                ctx.boss.world.playSound(ctx.boss.location, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 0.8f)
                if (target in ctx.victims() && ctx.inReach(target)) hurt(ctx, move, target)
                done++
            }
            t++
            return done >= hits
        }
    }

    /** A velocity arc onto the target (inside the arena); on landing an area hit with outward knockback. */
    private class Leap(val ctx: MoveContext, val move: Move, target: Player) : MoveRun {
        val flight: Int
        var t = 0

        init {
            val from = ctx.boss.location
            val to = target.location
            var dx = to.x - from.x
            var dz = to.z - from.z
            val dy = to.y - from.y
            val d = hypot(dx, dz)
            // Land a block short of a target on the same level (not inside it); on a ledge land on it.
            if (dy <= 1.0 && d > 1.5) {
                dx *= (d - 1.0) / d
                dz *= (d - 1.0) / d
            }
            var k = 1.0
            while (k > 0.0 && !ctx.inArena(from.clone().add(dx * k, 0.0, dz * k))) k -= 0.1
            dx *= k.coerceAtLeast(0.0)
            dz *= k.coerceAtLeast(0.0)
            flight = (10 + hypot(dx, dz) * 0.7 + maxOf(dy, 0.0) * 1.2).roundToInt().coerceIn(12, 30)
            val v = LeapArc.startVelocity(dx, dy, dz, flight)
            ctx.boss.velocity = Vector(v.x, v.y, v.z)
            ctx.boss.world.playSound(from, Sound.ENTITY_GOAT_LONG_JUMP, 1.6f, 0.6f)
        }

        override fun tick(): Boolean {
            t++
            val boss = ctx.boss
            if (t < 3 || (!boss.isOnGround && t < flight + 30)) return false
            val l = boss.location
            val radius = move.params["radius"] ?: 4.0
            boss.world.spawnParticle(Particle.EXPLOSION, l.x, l.y + 0.2, l.z, 3, radius / 3, 0.1, radius / 3, 0.0)
            boss.world.playSound(l, Sound.ENTITY_GENERIC_EXPLODE, 1.4f, 0.7f)
            for (p in ctx.victims()) {
                val pl = p.location
                val h = hypot(pl.x - l.x, pl.z - l.z)
                if (h > radius || kotlin.math.abs(pl.y - l.y) > 2.5) continue
                if (hurt(ctx, move, p)) p.velocity = outward(l, pl, 0.9, 0.4)
            }
            return true
        }
    }

    /** A ring along the ground growing by `speed` blocks a tick up to `radius`; hits players on the ground it passes. */
    private class Shockwave(val ctx: MoveContext, val move: Move) : MoveRun {
        val origin: Location = ctx.boss.location
        val radius = move.params["radius"] ?: 10.0
        val speed = (move.params["speed"] ?: 0.6).coerceAtLeast(0.05)
        var r = 0.5
        val hit = HashSet<java.util.UUID>()

        override fun tick(): Boolean {
            val prev = r - speed
            val w = origin.world ?: return true
            val points = (2 * PI * r / 0.8).toInt().coerceIn(8, 64)
            for (i in 0 until points) {
                val a = 2 * PI * i / points
                w.spawnParticle(Particle.FLAME, origin.x + cos(a) * r, origin.y + 0.15, origin.z + sin(a) * r, 1, 0.0, 0.0, 0.0, 0.0)
            }
            if (r == 0.5) w.playSound(origin, Sound.ENTITY_WARDEN_SONIC_BOOM, 1.4f, 0.8f)
            for (p in ctx.victims()) {
                if (p.uniqueId in hit) continue
                val pl = p.location
                val h = hypot(pl.x - origin.x, pl.z - origin.z)
                if (h < prev - 0.6 || h > r + 0.3 || kotlin.math.abs(pl.y - origin.y) > 1.5 || !p.isOnGround) continue
                hit += p.uniqueId
                hurt(ctx, move, p)
            }
            r += speed
            return r > radius
        }
    }

    /** [arrows] arrows fanned over `spread` degrees, [bursts] times 8 ticks apart; each arrow deals about the move's damage. */
    private class Volley(val ctx: MoveContext, val move: Move, val target: Player, val arrows: Int, val bursts: Int, val speed: Double) : MoveRun {
        val spread = move.params["spread"] ?: 6.0
        var t = 0
        var fired = 0

        override fun tick(): Boolean {
            if (t % BURST_GAP == 0 && fired < bursts) {
                shoot()
                fired++
            }
            t++
            return fired >= bursts && t > BURST_GAP / 2
        }

        private fun shoot() {
            val boss = ctx.boss
            if (target !in ctx.victims()) return
            val eye = boss.eyeLocation
            val aim = target.location.clone().add(0.0, target.height * 0.6, 0.0)
            val dist = aim.distance(eye)
            val ticks = dist / speed
            aim.y += 0.5 * ARROW_GRAVITY * ticks * ticks // arrows drop; aim above
            val base = aim.toVector().subtract(eye.toVector()).normalize().multiply(speed)
            boss.world.playSound(eye, if (arrows == 1) Sound.ITEM_CROSSBOW_SHOOT else Sound.ENTITY_ARROW_SHOOT, 1.4f, 0.9f)
            for (i in 0 until arrows) {
                val deg = (i - (arrows - 1) / 2.0) * spread
                val v: Vector = base.clone().rotateAroundY(Math.toRadians(deg))
                val arrow = boss.launchProjectile(Arrow::class.java, v)
                arrow.damage = move.damage / speed // vanilla arrow damage = speed x base
                arrow.isCritical = false
                arrow.pickupStatus = AbstractArrow.PickupStatus.DISALLOWED
                ctx.track(arrow)
            }
            ctx.trace { "shoot ${move.id} $arrows" }
        }
    }

    /** A quick hop away from the target, kept inside the arena. No damage. */
    private class Backstep(val ctx: MoveContext, move: Move, target: Player) : MoveRun {
        var t = 0

        init {
            val from = ctx.boss.location
            val tl = target.location
            var ax = from.x - tl.x
            var az = from.z - tl.z
            val len = hypot(ax, az)
            if (len < 1e-3) {
                val yaw = Math.toRadians(from.yaw.toDouble())
                ax = sin(yaw)
                az = -cos(yaw)
            } else {
                ax /= len
                az /= len
            }
            var d = move.params["distance"] ?: 6.0
            while (d > 1.0 && !ctx.inArena(from.clone().add(ax * d, 0.0, az * d))) d -= 0.5
            val v = (d / BACKSTEP_TRAVEL).coerceAtMost(1.6)
            ctx.boss.velocity = Vector(ax * v, 0.38, az * v)
            ctx.boss.world.spawnParticle(Particle.CLOUD, from.x, from.y + 0.1, from.z, 8, 0.3, 0.05, 0.3, 0.02)
        }

        override fun tick(): Boolean {
            t++
            return t >= 4 && (ctx.boss.isOnGround || t > 20)
        }
    }

    /** Invulnerable for `invulnerable-ticks` (the brain reports it as roaring); pushes players near it back. */
    private class Roar(val ctx: MoveContext, move: Move) : MoveRun {
        val ticks = (move.params["invulnerable-ticks"] ?: 40.0).toInt().coerceAtLeast(1)
        var t = 0

        init {
            val l = ctx.boss.location
            val w = ctx.boss.world
            w.playSound(l, Sound.ENTITY_RAVAGER_ROAR, 2f, 0.6f)
            w.spawnParticle(Particle.LARGE_SMOKE, l.x, l.y + 1.0, l.z, 50, 0.8, 0.8, 0.8, 0.05)
            for (p in ctx.victims()) {
                val pl = p.location
                if (pl.distanceSquared(l) <= ROAR_PUSH * ROAR_PUSH) p.velocity = outward(l, pl, 1.2, 0.45)
            }
            ctx.trace { "roar $ticks" }
        }

        override fun tick(): Boolean {
            t++
            if (t % 5 == 0) {
                val l = ctx.boss.location
                ctx.boss.world.spawnParticle(Particle.LARGE_SMOKE, l.x, l.y + 1.0, l.z, 6, 0.5, 0.6, 0.5, 0.02)
            }
            return t >= ticks
        }
    }

    private fun outward(from: Location, to: Location, h: Double, up: Double): Vector {
        var x = to.x - from.x
        var z = to.z - from.z
        val len = hypot(x, z)
        if (len < 1e-3) {
            x = 1.0
            z = 0.0
        } else {
            x /= len
            z /= len
        }
        return Vector(x * h, up, z * h)
    }

    private const val BURST_GAP = 8
    private const val VOLLEY_SPEED = 2.5
    private const val CHARGED_SPEED = 3.2
    private const val ARROW_GRAVITY = 0.05
    private const val ROAR_PUSH = 8.0

    /** Blocks travelled per unit of start speed by a hop of about 9 ticks (one ground-friction tick, then air). */
    private const val BACKSTEP_TRAVEL = 6.0
}
