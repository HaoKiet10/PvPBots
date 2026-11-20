package pvpbots.bukkit

import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import org.bukkit.GameMode
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
 * Drives one boss bot (spec 5.4, first cut: the moveset comes later). Each tick it picks its aggro target among the
 * participants (most damage dealt to the boss in the last 10 s, else the nearest), walks to it and melee-attacks it
 * through the fair-hit path (look ray against the target's box at combat reach, full attack cooldown). Main thread only.
 */
class BossController(
    private val adapter: BotAdapter,
    val handle: BotHandle,
    val type: BossType,
    val arena: ArenaRecord,
) {
    private class Dealt(val by: UUID, val amount: Double, val tick: Long)

    private val dealt = ArrayDeque<Dealt>()
    private var look = Rotation(handle.bukkit.location.yaw, 0f)
    private var tick = 0L

    /** In a move's recovery (punish window: x1.5 damage taken). The moveset (a later task) sets it; never true yet. */
    val recovering: Boolean get() = false

    /** Roaring at a phase change (no damage taken). The moveset (a later task) sets it; never true yet. */
    val roaring: Boolean get() = false

    /** The player the boss is after right now (diagnostics / later moves). */
    var target: Player? = null
        private set

    /** [by] took [amount] off the boss's pool: counts towards aggro for 10 s. */
    fun recordDamage(by: UUID, amount: Double) {
        if (amount > 0.0) dealt.addLast(Dealt(by, amount, tick))
    }

    /** Stands still facing where it looks (intro roar, or nobody to fight). */
    fun idle() {
        tick++
        adapter.setInput(handle, BotInput(0f, 0f, false, false, false, look.yaw, look.pitch))
    }

    fun tick(participants: List<Player>) {
        tick++
        while (dealt.isNotEmpty() && tick - dealt.first().tick > AGGRO_TICKS) dealt.removeFirst()
        val bot = handle.bukkit
        if (bot.isDead) return
        val t = pickTarget(bot, participants)
        target = t
        if (t == null) {
            adapter.setInput(handle, BotInput(0f, 0f, false, false, false, look.yaw, look.pitch))
            return
        }
        val pos = vec(bot.location)
        val eye = vec(bot.eyeLocation)
        val box = aabb(t.boundingBox)
        val chest = (box.min + box.max) * 0.5
        look = Aim.turnToward(look, Aim.lookAt(eye, chest), TURN_DEG)
        val goal = vec(t.location)
        val dist = pos.horizontalDistance(goal)
        val walk = dist > STOP_DIST
        val move = if (walk) InputMapper.toward(look, pos, goal) else null
        val jump = walk && bot.isOnGround && blockedAhead(bot, pos, goal)
        adapter.setInput(handle, BotInput(move?.forward ?: 0f, move?.strafe ?: 0f, jump, walk && dist > SPRINT_DIST, false, look.yaw, look.pitch))
        if (bot.attackCooldown >= ATTACK_READY && FairHit.hits(eye, look, box, PerceptionBuilder.REACH)) {
            adapter.attack(handle, t) // attack before swing: swing resets the attack cooldown
            adapter.swing(handle)
        }
    }

    /** Most damage dealt to the boss in the last 10 s among [participants]; else the nearest one. */
    private fun pickTarget(bot: Player, participants: List<Player>): Player? {
        val alive = participants.filter { it.isValid && !it.isDead && it.world == bot.world }
        if (alive.isEmpty()) return null
        val byDamage = HashMap<UUID, Double>()
        for (d in dealt) byDamage.merge(d.by, d.amount, Double::plus)
        alive.filter { (byDamage[it.uniqueId] ?: 0.0) > 0.0 }.maxByOrNull { byDamage.getValue(it.uniqueId) }?.let { return it }
        val l = bot.location
        return alive.minByOrNull { it.location.distanceSquared(l) }
    }

    /** A solid block at feet height half a block towards [goal]: jump to climb it. */
    private fun blockedAhead(bot: Player, pos: Vec3, goal: Vec3): Boolean {
        val d = goal - pos
        val len = kotlin.math.hypot(d.x, d.z)
        if (len < 1e-6) return false
        val x = pos.x + d.x / len * 0.6
        val z = pos.z + d.z / len * 0.6
        return bot.world.getBlockAt(floor(x).toInt(), floor(pos.y + 0.01).toInt(), floor(z).toInt()).type.isSolid
    }

    private companion object {
        const val AGGRO_TICKS = 200L
        const val TURN_DEG = 40.0
        const val STOP_DIST = 2.2
        const val SPRINT_DIST = 4.0
        const val ATTACK_READY = 0.95f
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

    private const val BASE_SPEED = 0.1
}
