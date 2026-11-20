package pvpbots.bukkit

import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import pvpbots.adapter.BotAdapter
import pvpbots.adapter.BotHandle
import pvpbots.adapter.BotInput
import pvpbots.arena.ArenaRecord
import pvpbots.boss.BossType
import pvpbots.bukkit.PerceptionBuilder.aabb
import pvpbots.bukkit.PerceptionBuilder.vec
import pvpbots.math.Aim
import pvpbots.math.FairHit
import pvpbots.math.InputMapper
import pvpbots.math.Rotation
import pvpbots.math.Vec3
import java.util.UUID
import kotlin.math.floor

/**
 * The body of one boss bot (spec 5.4): looking, walking, strafing, swinging and the fair-hit reach check, driven by
 * its [BossBrain] (aggro, moveset, phases, cheese guards). [fraction] is the pool's health fraction, [inArena] the
 * arena's disc membership, [onPhase] is told each new phase index, [tracer] gives the self-test trace. Main thread only.
 */
class BossController(
    private val adapter: BotAdapter,
    val handle: BotHandle,
    val type: BossType,
    val arena: ArenaRecord,
    fraction: () -> Double,
    inArena: (Location) -> Boolean,
    onPhase: (Int) -> Unit,
    tracer: () -> ((String) -> Unit)?,
) {
    private var look = Rotation(handle.bukkit.location.yaw, 0f)

    val brain = BossBrain(type, this, fraction, inArena, onPhase, tracer)

    val bot: Player get() = handle.bukkit

    /** In a move's recovery (punish window: x1.5 damage taken). */
    val recovering: Boolean get() = brain.recovering

    /** Roaring at a phase change (no damage taken). */
    val roaring: Boolean get() = brain.roaring

    /** The player the boss is after right now. */
    val target: Player? get() = brain.target

    fun recordDamage(by: UUID, amount: Double) = brain.recordDamage(by, amount)

    /** Stands still facing where it looks (intro). */
    fun idle() = brain.idle()

    fun tick(participants: List<Player>) = brain.tick(participants)

    fun onTeleportedBack() = brain.onTeleportedBack()

    fun close() = brain.close()

    // ---- body, for the brain ----

    /** Turns towards [t]'s chest (at most 40 degrees a tick). */
    internal fun face(t: Player) {
        val box = aabb(t.boundingBox)
        look = Aim.turnToward(look, Aim.lookAt(vec(bot.eyeLocation), (box.min + box.max) * 0.5), TURN_DEG)
    }

    /** No movement input; keeps the look (velocity from a leap or knockback carries on). */
    internal fun stand() {
        adapter.setInput(handle, BotInput(0f, 0f, false, false, false, look.yaw, look.pitch))
    }

    /** Walks to [t] (sprinting beyond 4 blocks if [sprint]), jumping onto blocks in the way; stops at 2.2 blocks. */
    internal fun chase(t: Player, sprint: Boolean) {
        face(t)
        val pos = vec(bot.location)
        val goal = vec(t.location)
        val dist = pos.horizontalDistance(goal)
        val walk = dist > STOP_DIST
        val move = if (walk) InputMapper.toward(look, pos, goal) else null
        val jump = walk && bot.isOnGround && blockedAhead(pos, goal)
        adapter.setInput(handle, BotInput(move?.forward ?: 0f, move?.strafe ?: 0f, jump, sprint && walk && dist > SPRINT_DIST, false, look.yaw, look.pitch))
    }

    /** Circles [t] sideways ([dir] +1 left, -1 right), facing it. */
    internal fun strafe(t: Player, dir: Float) {
        face(t)
        val pos = vec(bot.location)
        val goal = vec(t.location)
        val jump = bot.isOnGround && blockedAhead(pos, goal)
        adapter.setInput(handle, BotInput(0f, dir, jump, false, false, look.yaw, look.pitch))
    }

    internal fun swing() = adapter.swing(handle)

    /** The fair-hit path: the current look ray hits [t]'s box at combat reach. */
    internal fun inReach(t: Player): Boolean = FairHit.hits(vec(bot.eyeLocation), look, aabb(t.boundingBox), PerceptionBuilder.REACH)

    /** Movement speed attribute = vanilla base x [multiplier]. */
    internal fun setSpeed(multiplier: Double) {
        bot.getAttribute(Attribute.MOVEMENT_SPEED)?.let { it.baseValue = BossGear.BASE_SPEED * multiplier }
    }

    /** A solid block at feet height half a block towards [goal]: jump to climb it. */
    private fun blockedAhead(pos: Vec3, goal: Vec3): Boolean {
        val d = goal - pos
        val len = kotlin.math.hypot(d.x, d.z)
        if (len < 1e-6) return false
        val x = pos.x + d.x / len * 0.6
        val z = pos.z + d.z / len * 0.6
        return bot.world.getBlockAt(floor(x).toInt(), floor(pos.y + 0.01).toInt(), floor(z).toInt()).type.isSolid
    }

    private companion object {
        const val TURN_DEG = 40.0
        const val STOP_DIST = 2.2
        const val SPRINT_DIST = 4.0
    }
}

/** Puts a boss type's gear, enchantments and attributes on the boss bot. */
object BossGear {
    fun apply(p: Player, type: BossType, warn: (String) -> Unit) {
        if (p.gameMode != GameMode.SURVIVAL) p.gameMode = GameMode.SURVIVAL
        val inv = p.inventory
        inv.clear()
        inv.setArmorContents(arrayOfNulls(4))
        val enchants = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT)
        fun item(slot: String): ItemStack? {
            val name = type.gear[slot] ?: return null
            val mat = Material.matchMaterial(name)
            if (mat == null || !mat.isItem || mat.isAir) {
                warn("gear $slot: unknown item $name")
                return null
            }
            val stack = ItemStack(mat)
            for ((ench, level) in type.enchants[slot].orEmpty()) {
                val e = enchants.get(NamespacedKey.minecraft(ench.lowercase()))
                if (e == null) warn("gear $slot: unknown enchantment $ench") else stack.addUnsafeEnchantment(e, level)
            }
            return stack
        }
        item("head")?.let { inv.helmet = it }
        item("chest")?.let { inv.chestplate = it }
        item("legs")?.let { inv.leggings = it }
        item("feet")?.let { inv.boots = it }
        item("main-hand")?.let { inv.setItemInMainHand(it) }
        item("off-hand")?.let { inv.setItemInOffHand(it) }
        p.getAttribute(Attribute.KNOCKBACK_RESISTANCE)?.baseValue = type.knockbackResistance
        p.getAttribute(Attribute.MOVEMENT_SPEED)?.let { it.baseValue = BASE_SPEED * type.moveSpeed }
        p.health = p.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
        p.fireTicks = 0
    }

    const val BASE_SPEED = 0.1
}
