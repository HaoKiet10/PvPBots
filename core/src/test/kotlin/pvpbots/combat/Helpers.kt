package pvpbots.combat

import pvpbots.math.Aabb
import pvpbots.math.Rotation
import pvpbots.math.Vec3
import pvpbots.profile.Difficulty
import pvpbots.profile.DifficultyProfile
import pvpbots.profile.ProfileLoader
import java.util.UUID
import kotlin.random.Random

val EASY: DifficultyProfile = ProfileLoader.defaults()[Difficulty.EASY]!!
val MEDIUM: DifficultyProfile = ProfileLoader.defaults()[Difficulty.MEDIUM]!!
val HARD: DifficultyProfile = ProfileLoader.defaults()[Difficulty.HARD]!!
val PRO: DifficultyProfile = ProfileLoader.defaults()[Difficulty.PRO]!!

fun state(p: DifficultyProfile, seed: Int = 1) = BrainState(p, Random(seed))

fun targetView(pos: Vec3, blocking: Boolean = false, eating: Boolean = false) = TargetView(
    id = UUID(0, 1),
    pos = pos,
    velocity = Vec3(0.0, 0.0, 0.0),
    box = Aabb(Vec3(pos.x - 0.3, pos.y, pos.z - 0.3), Vec3(pos.x + 0.3, pos.y + 1.8, pos.z + 0.3)),
    healthFraction = 1.0,
    blocking = blocking,
    eating = eating,
)

/** Bot stands at the origin; by default a non-blocking target is 2.5 blocks ahead. */
fun percept(
    health: Double = 1.0,
    cooldown: Float = 1.0f,
    onGround: Boolean = true,
    velY: Double = 0.0,
    held: Int = KitSlots.SWORD,
    usingItem: Boolean = false,
    hasAxe: Boolean = false,
    hasShield: Boolean = false,
    hasFood: Boolean = false,
    hasBow: Boolean = false,
    offhandIsTotem: Boolean = false,
    hasTotemInHotbar: Boolean = false,
    targetAt: Vec3? = Vec3(0.0, 0.0, 2.5),
    targetBlocking: Boolean = false,
    lookHits: Boolean = false,
    targetReachable: Boolean = true,
    follow: Vec3? = null,
) = Perception(
    pos = Vec3(0.0, 0.0, 0.0),
    eye = Vec3(0.0, 1.62, 0.0),
    velocity = Vec3(0.0, velY, 0.0),
    look = Rotation(0f, 0f),
    onGround = onGround,
    healthFraction = health,
    attackCooldown = cooldown,
    heldSlot = held,
    usingItem = usingItem,
    hasAxe = hasAxe,
    hasShield = hasShield,
    hasFood = hasFood,
    hasBow = hasBow,
    offhandIsTotem = offhandIsTotem,
    hasTotemInHotbar = hasTotemInHotbar,
    target = targetAt?.let { targetView(it, targetBlocking) },
    lookHitsTarget = lookHits,
    targetReachable = targetReachable,
    followPos = follow,
)
