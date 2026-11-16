package pvpbots.combat

import pvpbots.math.Vec3
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AllyBrainTest {
    private val rng = Random(7)

    @Test
    fun allyNoTarget_followsPlayer() {
        val i = AllyBrain.decide(percept(targetAt = null, follow = Vec3(10.0, 0.0, 0.0)), MEDIUM, state(MEDIUM), rng)
        assertEquals(MoveStyle.WALK, i.style)
        assertEquals(Vec3(10.0, 0.0, 0.0), i.moveTarget)
    }

    @Test
    fun allyNoTarget_closeToPlayer_stands() {
        val i = AllyBrain.decide(percept(targetAt = null, follow = Vec3(2.0, 0.0, 0.0)), MEDIUM, state(MEDIUM), rng)
        assertEquals(MoveStyle.STAND, i.style)
    }

    @Test
    fun allyFlyingTarget_drawsBow() {
        val i = AllyBrain.decide(percept(targetAt = Vec3(0.0, 8.0, 5.0), hasBow = true, held = KitSlots.BOW), MEDIUM, state(MEDIUM), rng)
        assertEquals(Action.DRAW_BOW, i.action)
    }

    @Test
    fun allyFlyingTarget_selectsBowFirst() {
        val i = AllyBrain.decide(percept(targetAt = Vec3(0.0, 8.0, 5.0), hasBow = true), MEDIUM, state(MEDIUM), rng)
        assertEquals(KitSlots.BOW, i.selectSlot)
    }

    @Test
    fun allyBow_releasesAfter20Ticks() {
        val s = state(MEDIUM)
        val p = percept(targetAt = Vec3(0.0, 0.0, 15.0), hasBow = true, held = KitSlots.BOW, targetReachable = false)
        repeat(20) { assertEquals(Action.DRAW_BOW, AllyBrain.decide(p, MEDIUM, s, rng).action) }
        val r = AllyBrain.decide(p, MEDIUM, s, rng)
        assertEquals(Action.RELEASE_BOW, r.action)
        assertNotNull(r.bowPitch)
        assertEquals(Action.DRAW_BOW, AllyBrain.decide(p, MEDIUM, s, rng).action)
    }

    @Test
    fun allyBow_unreachableShot_walksInstead() {
        val s = state(MEDIUM)
        val p = percept(targetAt = Vec3(0.0, 40.0, 3.0), hasBow = true, held = KitSlots.BOW)
        repeat(20) { AllyBrain.decide(p, MEDIUM, s, rng) }
        val r = AllyBrain.decide(p, MEDIUM, s, rng)
        assertEquals(MoveStyle.WALK, r.style)
        assertEquals(Action.NONE, r.action)
        assertNull(r.bowPitch)
    }

    @Test
    fun allyWithoutBow_meleesFlyingTarget() {
        val i = AllyBrain.decide(percept(targetAt = Vec3(0.0, 8.0, 5.0)), MEDIUM, state(MEDIUM), rng)
        assertNotEquals(KitSlots.BOW, i.selectSlot)
    }

    @Test
    fun allyIgnoresAxeRule() {
        val i = AllyBrain.decide(percept(targetBlocking = true, hasAxe = true, lookHits = true), PRO, state(PRO), rng)
        assertNotEquals(KitSlots.AXE, i.selectSlot)
    }

    @Test
    fun allyEatsAtLowHealth() {
        val i = AllyBrain.decide(percept(health = 0.2, hasFood = true), MEDIUM, state(MEDIUM), rng)
        assertEquals(Action.EAT, i.action)
    }
}
