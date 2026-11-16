package pvpbots.combat

import pvpbots.math.Aabb
import pvpbots.math.Rotation
import pvpbots.math.Vec3
import java.util.UUID

/** Hotbar layout shared by all bots; the offhand holds the shield (or a totem after a swap). */
object KitSlots {
    const val SWORD = 0
    const val AXE = 1
    const val FOOD = 2
    const val BOW = 3
    const val ARROWS = 4
    const val TOTEM = 8
}

data class TargetView(
    val id: UUID,
    val pos: Vec3,
    val velocity: Vec3,
    val box: Aabb,
    val healthFraction: Double,
    val blocking: Boolean,
    val eating: Boolean,
)

/** One-tick snapshot of the world as seen by a bot; built by the platform layer. */
data class Perception(
    val pos: Vec3,
    val eye: Vec3,
    val velocity: Vec3,
    val look: Rotation,
    val onGround: Boolean,
    val healthFraction: Double,
    val attackCooldown: Float,
    val heldSlot: Int,
    val usingItem: Boolean,
    val hasAxe: Boolean,
    val hasShield: Boolean,
    val hasFood: Boolean,
    val hasBow: Boolean,
    val offhandIsTotem: Boolean,
    val hasTotemInHotbar: Boolean,
    val target: TargetView?,
    val lookHitsTarget: Boolean,
    val targetReachable: Boolean,
    val followPos: Vec3?,
)

enum class MoveStyle { STAND, WALK, APPROACH_SPRINT, STRAFE_LEFT, STRAFE_RIGHT, RETREAT, WTAP_RELEASE }

enum class Action { NONE, ATTACK, RAISE_SHIELD, LOWER_SHIELD, EAT, SWAP_TOTEM, DRAW_BOW, RELEASE_BOW }

/** [selectSlot] -1 keeps the current slot; [bowPitch] is non-null only with [Action.RELEASE_BOW]. */
data class Intent(
    val style: MoveStyle,
    val moveTarget: Vec3?,
    val lookAt: Vec3?,
    val jump: Boolean,
    val action: Action,
    val selectSlot: Int,
    val bowPitch: Double?,
)

data class MobView(val id: UUID, val pos: Vec3, val maxHealth: Double, val targetId: UUID?)
