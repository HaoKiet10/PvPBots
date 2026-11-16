package pvpbots.combat

import pvpbots.math.Ballistics
import pvpbots.math.Vec3
import pvpbots.profile.DifficultyProfile
import pvpbots.profile.ShieldMode
import pvpbots.profile.StrafeMode
import kotlin.random.Random

/** Decision logic shared by [TrainerBrain] and [AllyBrain]; the ally flag switches the differing rules. */
internal object CombatLogic {
    private const val MELEE_RANGE = 3.5
    private const val FOLLOW_RANGE = 4.0
    private const val HIGH_TARGET = 4.0
    private const val BOW_DRAW_TICKS = 20
    private const val ARROW_SPEED = 3.0
    private const val WTAP_TICKS = 2
    private const val CRIT_WAIT_LIMIT = 10
    private const val TOTEM_HEALTH = 0.25
    private const val BASIC_SHIELD_COOLDOWN = 0.5f

    fun decide(p: Perception, prof: DifficultyProfile, s: BrainState, rng: Random, ally: Boolean): Intent {
        val wTapActive = s.wTapTicksLeft > 0
        if (wTapActive) s.wTapTicksLeft--
        val raw = rules(p, prof, s, rng, ally)
        return if (wTapActive && raw.style != MoveStyle.RETREAT) raw.copy(style = MoveStyle.WTAP_RELEASE) else raw
    }

    private fun intent(
        style: MoveStyle, moveTarget: Vec3?, lookAt: Vec3?, action: Action = Action.NONE,
        select: Int = -1, bowPitch: Double? = null,
    ) = Intent(style, moveTarget, lookAt, false, action, select, bowPitch)

    private fun chest(t: TargetView): Vec3 = (t.box.min + t.box.max) * 0.5

    private fun rules(p: Perception, prof: DifficultyProfile, s: BrainState, rng: Random, ally: Boolean): Intent {
        val t = p.target
        val look = t?.let { chest(it) }

        // 1. Totem
        if (prof.totem && p.healthFraction <= TOTEM_HEALTH && !p.offhandIsTotem && p.hasTotemInHotbar) {
            val move = movement(p, prof, s, rng)
            return if (p.heldSlot != KitSlots.TOTEM) move.copy(selectSlot = KitSlots.TOTEM)
            else move.copy(action = Action.SWAP_TOTEM)
        }
        // 2. Eat (keep eating until the item use finishes)
        val eatingNow = p.usingItem && p.heldSlot == KitSlots.FOOD
        if (p.hasFood && ((prof.healAt > 0 && p.healthFraction <= prof.healAt) || eatingNow)) {
            return intent(
                MoveStyle.RETREAT, null, look, Action.EAT,
                select = if (p.heldSlot != KitSlots.FOOD) KitSlots.FOOD else -1,
            )
        }

        if (t == null) return noTarget(p, ally, s)

        // Ally: ranged when the target cannot be reached on foot
        if (ally && p.hasBow && (!p.targetReachable || t.pos.y - p.pos.y > HIGH_TARGET)) {
            return bow(p, s, t, look!!)
        }
        s.bowDrawTicks = 0

        val ready = p.lookHitsTarget && p.attackCooldown >= s.cooldownThreshold
        val shieldUp = prof.shield != ShieldMode.NONE && p.hasShield && p.usingItem &&
            p.heldSlot != KitSlots.FOOD && p.heldSlot != KitSlots.BOW

        // 3. Axe vs blocking target (trainer only)
        if (!ally && t.blocking && prof.shield == ShieldMode.FULL_AXE && p.hasAxe && p.lookHitsTarget) {
            if (p.heldSlot != KitSlots.AXE) return movement(p, prof, s, rng).copy(selectSlot = KitSlots.AXE)
            if (ready) return attack(p, prof, s, rng, shieldUp)
        }
        // 4. Back to the sword (switching resets the cooldown, so no action this tick)
        if (!t.blocking && p.heldSlot != KitSlots.SWORD) {
            return movement(p, prof, s, rng).copy(selectSlot = KitSlots.SWORD)
        }
        // 5. Attack, with optional crit jump
        if (ready) {
            if (shieldUp) return movement(p, prof, s, rng).copy(action = Action.LOWER_SHIELD)
            if (s.critRoll == null) s.critRoll = rng.nextDouble() < prof.crits.chance
            if (s.critRoll != true) return attack(p, prof, s, rng, false)
            if (p.onGround && !s.critJumped) {
                s.critJumped = true
                s.critWaitTicks = 0
                return movement(p, prof, s, rng).copy(jump = true)
            }
            if (!p.onGround && p.velocity.y < 0) return attack(p, prof, s, rng, false)
            if (++s.critWaitTicks > CRIT_WAIT_LIMIT) s.critJumped = false
            return movement(p, prof, s, rng)
        }
        // 6. Shield
        val dist = (t.pos - p.pos).length()
        val shieldRule = p.hasShield && dist <= MELEE_RANGE && p.attackCooldown < s.cooldownThreshold && when (prof.shield) {
            ShieldMode.FULL, ShieldMode.FULL_AXE -> true
            ShieldMode.BASIC -> p.attackCooldown < BASIC_SHIELD_COOLDOWN
            ShieldMode.NONE -> false
        }
        // 7. Movement
        val move = movement(p, prof, s, rng)
        if (shieldRule) return if (p.usingItem) move else move.copy(action = Action.RAISE_SHIELD)
        if (shieldUp) return move.copy(action = Action.LOWER_SHIELD)
        return move
    }

    private fun attack(p: Perception, prof: DifficultyProfile, s: BrainState, rng: Random, shieldUp: Boolean): Intent {
        val base = movement(p, prof, s, rng)
        if (shieldUp) return base.copy(action = Action.LOWER_SHIELD)
        s.cooldownThreshold = BrainState.sampleThreshold(prof, rng)
        s.critRoll = null
        s.critJumped = false
        s.critWaitTicks = 0
        if (prof.wTap) s.wTapTicksLeft = WTAP_TICKS
        return base.copy(action = Action.ATTACK)
    }

    private fun movement(p: Perception, prof: DifficultyProfile, s: BrainState, rng: Random): Intent {
        val t = p.target ?: return intent(MoveStyle.STAND, null, null)
        val look = chest(t)
        val dist = (t.pos - p.pos).length()
        if (dist > MELEE_RANGE) return intent(MoveStyle.APPROACH_SPRINT, t.pos, look)
        if (prof.strafe == StrafeMode.NONE) return intent(MoveStyle.WALK, t.pos, look)
        if (s.strafeTicksLeft <= 0) {
            s.strafeLeft = !s.strafeLeft
            s.strafeTicksLeft = BrainState.strafePeriod(prof, rng)
        }
        s.strafeTicksLeft--
        return intent(if (s.strafeLeft) MoveStyle.STRAFE_LEFT else MoveStyle.STRAFE_RIGHT, t.pos, look)
    }

    private fun noTarget(p: Perception, ally: Boolean, s: BrainState): Intent {
        s.bowDrawTicks = 0
        val f = p.followPos
        if (!ally || f == null) return intent(MoveStyle.STAND, null, null)
        return if ((f - p.pos).length() > FOLLOW_RANGE) intent(MoveStyle.WALK, f, f)
        else intent(MoveStyle.STAND, null, f)
    }

    private fun bow(p: Perception, s: BrainState, t: TargetView, look: Vec3): Intent {
        if (p.heldSlot != KitSlots.BOW) {
            s.bowDrawTicks = 0
            return intent(MoveStyle.STAND, null, look, select = KitSlots.BOW)
        }
        if (s.bowDrawTicks < BOW_DRAW_TICKS) {
            s.bowDrawTicks++
            return intent(MoveStyle.STAND, null, look, Action.DRAW_BOW)
        }
        s.bowDrawTicks = 0
        val pitch = Ballistics.pitchFor(p.eye, look, ARROW_SPEED)
            ?: return intent(MoveStyle.WALK, t.pos, look)
        return intent(MoveStyle.STAND, null, look, Action.RELEASE_BOW, bowPitch = pitch)
    }
}
