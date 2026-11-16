package pvpbots.combat

import pvpbots.math.Vec3
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrainerBrainTest {
    private val rng = Random(7)

    @Test
    fun lowHealth_eats() {
        val i = TrainerBrain.decide(percept(health = 0.3, hasFood = true), MEDIUM, state(MEDIUM), rng)
        assertEquals(Action.EAT, i.action)
        assertEquals(KitSlots.FOOD, i.selectSlot)
        assertEquals(MoveStyle.RETREAT, i.style)
    }

    @Test
    fun easyNeverEats() {
        assertNotEquals(Action.EAT, TrainerBrain.decide(percept(health = 0.1, hasFood = true), EASY, state(EASY), rng).action)
    }

    @Test
    fun continuesEatingWhileUsingItem() {
        val i = TrainerBrain.decide(percept(health = 0.9, hasFood = true, held = KitSlots.FOOD, usingItem = true), MEDIUM, state(MEDIUM), rng)
        assertEquals(Action.EAT, i.action)
    }

    @Test
    fun blockingTarget_switchesToAxeFirst() {
        val i = TrainerBrain.decide(percept(targetBlocking = true, hasAxe = true, lookHits = true, held = KitSlots.SWORD), PRO, state(PRO), rng)
        assertEquals(KitSlots.AXE, i.selectSlot)
        assertEquals(Action.NONE, i.action)
    }

    @Test
    fun axeHeld_attacksShield() {
        val i = TrainerBrain.decide(percept(targetBlocking = true, hasAxe = true, lookHits = true, held = KitSlots.AXE, cooldown = 1f), PRO, state(PRO), rng)
        assertEquals(Action.ATTACK, i.action)
    }

    @Test
    fun hardDoesNotAxeBreak() {
        val i = TrainerBrain.decide(percept(targetBlocking = true, hasAxe = true, lookHits = true), HARD, state(HARD), rng)
        assertNotEquals(KitSlots.AXE, i.selectSlot)
    }

    @Test
    fun nonBlockingTarget_returnsToSword() {
        val i = TrainerBrain.decide(percept(held = KitSlots.AXE, lookHits = true), PRO, state(PRO), rng)
        assertEquals(KitSlots.SWORD, i.selectSlot)
        assertEquals(Action.NONE, i.action)
    }

    @Test
    fun cooldownNotReady_noAttack() {
        assertNotEquals(Action.ATTACK, TrainerBrain.decide(percept(lookHits = true, cooldown = 0.5f), PRO, state(PRO), rng).action)
    }

    @Test
    fun notLookingAtTarget_noAttack() {
        assertNotEquals(Action.ATTACK, TrainerBrain.decide(percept(lookHits = false, cooldown = 1f), MEDIUM, state(MEDIUM), rng).action)
    }

    @Test
    fun proCrit_jumpsThenHitsFalling() {
        val s = state(PRO)
        val a = TrainerBrain.decide(percept(lookHits = true, cooldown = 1f, onGround = true), PRO, s, rng)
        assertTrue(a.jump)
        assertEquals(Action.NONE, a.action)
        val b = TrainerBrain.decide(percept(lookHits = true, cooldown = 1f, onGround = false, velY = -0.1), PRO, s, rng)
        assertEquals(Action.ATTACK, b.action)
    }

    @Test
    fun proCrit_waitsWhileRising() {
        val s = state(PRO)
        TrainerBrain.decide(percept(lookHits = true, cooldown = 1f, onGround = true), PRO, s, rng)
        val b = TrainerBrain.decide(percept(lookHits = true, cooldown = 1f, onGround = false, velY = 0.3), PRO, s, rng)
        assertEquals(Action.NONE, b.action)
    }

    @Test
    fun neverCrit_attacksOnGround() {
        val prof = MEDIUM.copy(crits = pvpbots.profile.CritMode.NEVER)
        val i = TrainerBrain.decide(percept(lookHits = true, cooldown = 1f), prof, state(prof), rng)
        assertEquals(Action.ATTACK, i.action)
        assertEquals(false, i.jump)
    }

    @Test
    fun wTapAfterHit() {
        val prof = HARD.copy(crits = pvpbots.profile.CritMode.NEVER)
        val s = state(prof)
        val hit = TrainerBrain.decide(percept(lookHits = true, cooldown = 1f), prof, s, Random(99))
        assertEquals(Action.ATTACK, hit.action)
        assertEquals(MoveStyle.WTAP_RELEASE, TrainerBrain.decide(percept(cooldown = 0.1f), prof, s, rng).style)
        assertEquals(MoveStyle.WTAP_RELEASE, TrainerBrain.decide(percept(cooldown = 0.2f), prof, s, rng).style)
        assertNotEquals(MoveStyle.WTAP_RELEASE, TrainerBrain.decide(percept(cooldown = 0.3f), prof, s, rng).style)
    }

    @Test
    fun farAway_sprints() {
        assertEquals(MoveStyle.APPROACH_SPRINT, TrainerBrain.decide(percept(targetAt = Vec3(0.0, 0.0, 8.0)), MEDIUM, state(MEDIUM), rng).style)
    }

    @Test
    fun easyNearTarget_walks() {
        assertEquals(MoveStyle.WALK, TrainerBrain.decide(percept(), EASY, state(EASY), rng).style)
    }

    @Test
    fun simpleStrafe_flipsEvery40() {
        val s = state(MEDIUM)
        val first = TrainerBrain.decide(percept(cooldown = 0f), MEDIUM, s, rng).style
        val styles = (1 until 40).map { TrainerBrain.decide(percept(cooldown = 0f), MEDIUM, s, rng).style }
        assertTrue(styles.all { it == first })
        val flipped = TrainerBrain.decide(percept(cooldown = 0f), MEDIUM, s, rng).style
        assertNotEquals(first, flipped)
    }

    @Test
    fun fullStrafe_flipsEvery20() {
        val s = state(HARD)
        val first = TrainerBrain.decide(percept(cooldown = 0f), HARD, s, rng).style
        repeat(19) { assertEquals(first, TrainerBrain.decide(percept(cooldown = 0f), HARD, s, rng).style) }
        assertNotEquals(first, TrainerBrain.decide(percept(cooldown = 0f), HARD, s, rng).style)
    }

    @Test
    fun totemSwapAtLowHealth() {
        assertEquals(KitSlots.TOTEM, TrainerBrain.decide(percept(health = 0.2, hasTotemInHotbar = true), PRO, state(PRO), rng).selectSlot)
    }

    @Test
    fun totemHeld_swapsToOffhand() {
        val i = TrainerBrain.decide(percept(health = 0.2, hasTotemInHotbar = true, held = KitSlots.TOTEM), PRO, state(PRO), rng)
        assertEquals(Action.SWAP_TOTEM, i.action)
    }

    @Test
    fun totemIgnoredWhenAlreadyInOffhand() {
        val i = TrainerBrain.decide(percept(health = 0.2, hasTotemInHotbar = true, offhandIsTotem = true), PRO, state(PRO), rng)
        assertNotEquals(KitSlots.TOTEM, i.selectSlot)
    }

    @Test
    fun fullShield_raisesWhileOnCooldown() {
        val i = TrainerBrain.decide(percept(hasShield = true, cooldown = 0.2f), HARD, state(HARD), rng)
        assertEquals(Action.RAISE_SHIELD, i.action)
    }

    @Test
    fun basicShield_onlyWhenCooldownVeryLow() {
        assertEquals(Action.RAISE_SHIELD, TrainerBrain.decide(percept(hasShield = true, cooldown = 0.2f), MEDIUM, state(MEDIUM), rng).action)
        assertNotEquals(Action.RAISE_SHIELD, TrainerBrain.decide(percept(hasShield = true, cooldown = 0.7f), MEDIUM, state(MEDIUM), rng).action)
    }

    @Test
    fun shieldLoweredWhenRuleNoLongerApplies() {
        val i = TrainerBrain.decide(percept(hasShield = true, usingItem = true, cooldown = 0.2f, targetAt = Vec3(0.0, 0.0, 8.0)), HARD, state(HARD), rng)
        assertEquals(Action.LOWER_SHIELD, i.action)
    }

    @Test
    fun trainerNeverBow() {
        assertNotEquals(KitSlots.BOW, TrainerBrain.decide(percept(hasBow = true, targetReachable = false), PRO, state(PRO), rng).selectSlot)
    }

    @Test
    fun noTarget_stands() {
        val i = TrainerBrain.decide(percept(targetAt = null), PRO, state(PRO), rng)
        assertEquals(MoveStyle.STAND, i.style)
        assertEquals(Action.NONE, i.action)
        assertNull(i.lookAt)
    }

    @Test
    fun looksAtTargetChest() {
        val i = TrainerBrain.decide(percept(), MEDIUM, state(MEDIUM), rng)
        assertEquals(Vec3(0.0, 0.9, 2.5), i.lookAt)
    }
}
