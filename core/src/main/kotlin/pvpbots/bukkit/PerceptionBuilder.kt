package pvpbots.bukkit

import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Enemy
import org.bukkit.entity.Entity
import org.bukkit.entity.HumanEntity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.util.BoundingBox
import pvpbots.combat.KitSlots
import pvpbots.combat.MobView
import pvpbots.combat.Perception
import pvpbots.combat.TargetSelector
import pvpbots.combat.TargetView
import pvpbots.math.Aabb
import pvpbots.math.Bounds
import pvpbots.math.FairHit
import pvpbots.math.Rotation
import pvpbots.math.Vec3
import java.util.UUID

/** Reads the Bukkit world into the pure combat types ([Perception], [TargetView], [MobView]). */
object PerceptionBuilder {
    const val REACH = 3.0

    fun vec(l: Location) = Vec3(l.x, l.y, l.z)

    fun vec(v: org.bukkit.util.Vector) = Vec3(v.x, v.y, v.z)

    fun aabb(b: BoundingBox) = Aabb(Vec3(b.minX, b.minY, b.minZ), Vec3(b.maxX, b.maxY, b.maxZ))

    fun healthFraction(e: LivingEntity): Double {
        val max = e.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
        return if (max <= 0.0) 0.0 else (e.health / max).coerceIn(0.0, 1.0)
    }

    fun maxHealth(e: LivingEntity): Double = e.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0

    /** A fresh snapshot of [e] as a target. */
    fun targetView(e: LivingEntity): TargetView {
        val raised = e.isHandRaised
        return TargetView(
            id = e.uniqueId,
            pos = vec(e.location),
            velocity = vec(e.velocity),
            box = aabb(e.boundingBox),
            healthFraction = healthFraction(e),
            blocking = (e as? HumanEntity)?.isBlocking == true,
            eating = raised && e.activeItem.type.isEdible,
        )
    }

    /**
     * The bot's own state (every tick) combined with the possibly stale [target] snapshot. [lookHitsTarget] is
     * computed from the bot's current eye and [look] against the snapshot's box.
     */
    @Suppress("DEPRECATION") // Player.isOnGround: bots run server-side physics, so the flag is the server's own
    fun perceive(
        bot: Player,
        look: Rotation,
        target: TargetView?,
        targetReachable: Boolean,
        followPos: Vec3?,
    ): Perception {
        val inv = bot.inventory
        val eye = vec(bot.eyeLocation)
        val held = inv.heldItemSlot
        fun type(slot: Int): Material = inv.getItem(slot)?.type ?: Material.AIR
        val offhand = inv.itemInOffHand.type
        return Perception(
            pos = vec(bot.location),
            eye = eye,
            velocity = vec(bot.velocity),
            look = look,
            onGround = bot.isOnGround,
            healthFraction = healthFraction(bot),
            attackCooldown = bot.attackCooldown,
            heldSlot = held,
            usingItem = bot.isHandRaised,
            hasAxe = type(KitSlots.AXE).name.endsWith("_AXE"),
            hasShield = offhand == Material.SHIELD,
            hasFood = type(KitSlots.FOOD).isEdible,
            hasBow = type(KitSlots.BOW) == Material.BOW && inv.contains(Material.ARROW),
            offhandIsTotem = offhand == Material.TOTEM_OF_UNDYING,
            hasTotemInHotbar = type(KitSlots.TOTEM) == Material.TOTEM_OF_UNDYING,
            target = target,
            lookHitsTarget = target != null && FairHit.hits(eye, look, target.box, REACH),
            targetReachable = targetReachable,
            followPos = followPos,
        )
    }

    /** True if the bot is currently using an item in its main hand (bow draw, eating). */
    fun usingMainHand(bot: Player): Boolean = bot.isHandRaised && bot.handRaised == EquipmentSlot.HAND

    /** Real (non-bot) players that can be seen as allies' charges: same world, alive, not spectating. */
    fun realPlayers(bot: Player, isBot: (UUID) -> Boolean): List<Player> =
        bot.world.players.filter { !isBot(it.uniqueId) && !it.isDead && it.gameMode != GameMode.SPECTATOR }

    /** Ally target: hostile ([Enemy]) living entities inside [bounds], chosen by [TargetSelector]. */
    fun allyTarget(bot: Player, bounds: Bounds, isBot: (UUID) -> Boolean): LivingEntity? {
        val box = BoundingBox(
            bounds.min.x.toDouble(), bounds.min.y.toDouble(), bounds.min.z.toDouble(),
            bounds.max.x + 1.0, bounds.max.y + 1.0, bounds.max.z + 1.0,
        )
        val hostiles = bot.world.getNearbyEntities(box) { it is Enemy && !it.isDead && bounds.contains(vec(it.location)) }
            .map { it as LivingEntity }
        if (hostiles.isEmpty()) return null
        val views = hostiles.map { MobView(it.uniqueId, vec(it.location), maxHealth(it), (it as? Mob)?.target?.uniqueId) }
        val players = realPlayers(bot, isBot).associate { it.uniqueId to vec(it.location) }
        val id = TargetSelector.pickAllyTarget(vec(bot.location), views, players) ?: return null
        return hostiles.firstOrNull { it.uniqueId == id }
    }

    /** Position of the nearest real player inside [bounds] (allies follow it when nothing is hostile). */
    fun followPos(bot: Player, bounds: Bounds, isBot: (UUID) -> Boolean): Vec3? {
        val me = vec(bot.location)
        return realPlayers(bot, isBot).map { vec(it.location) }.filter { bounds.contains(it) }.minByOrNull { (it - me).length() }
    }

    /** Resolves an entity by UUID if it is a usable target in [bot]'s world. */
    fun livingTarget(bot: Player, id: UUID): LivingEntity? {
        val e: Entity = org.bukkit.Bukkit.getEntity(id) ?: return null
        if (e !is LivingEntity || e.isDead || e.world != bot.world) return null
        if (e is Player && e.gameMode == GameMode.SPECTATOR) return null
        return e
    }
}
