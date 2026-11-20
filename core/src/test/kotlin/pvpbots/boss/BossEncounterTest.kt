package pvpbots.boss

import pvpbots.boss.BossEncounter.Effect
import pvpbots.boss.BossEncounter.State
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BossEncounterTest {
    private val a = UUID.randomUUID()
    private val b = UUID.randomUUID()

    /** Everyone enters, intro runs at t=0..3000, fight starts. */
    private fun fight(e: BossEncounter, vararg ids: UUID) {
        ids.forEach { e.playerEntered(it) }
        e.tick(0)
        e.tick(3000)
        assertEquals(State.FIGHT, e.state)
    }

    @Test fun firstPlayer_intro3s_spawn() {
        val e = BossEncounter(600)
        val fx = e.playerEntered(a)
        assertTrue(Effect.SpawnBoss in fx && Effect.ShowBar in fx)
        assertEquals(State.INTRO, e.state)
        e.tick(1000)
        e.tick(3999)
        assertEquals(State.INTRO, e.state)
        e.tick(4000)
        assertEquals(State.FIGHT, e.state)
    }

    @Test fun secondPlayerDoesNotRespawn() {
        val e = BossEncounter(600)
        e.playerEntered(a)
        assertFalse(Effect.SpawnBoss in e.playerEntered(b))
    }

    @Test fun cooldown_blocksSpawn_showsCountdown() {
        val e = BossEncounter(600)
        fight(e, a)
        e.bossDamaged(a, 10.0)
        e.tick(10_000)
        e.bossDead()
        e.tick(10_000)
        e.tick(16_000)
        assertEquals(State.COOLDOWN, e.state)
        assertTrue(e.tick(17_000).any { it is Effect.CooldownBar })
        e.playerLeft(a)
        val fx2 = e.playerEntered(a)
        assertFalse(Effect.SpawnBoss in fx2)
        assertEquals(State.COOLDOWN, e.state)
        val bar = (fx2 + e.tick(18_000)).filterIsInstance<Effect.CooldownBar>()
        assertTrue(bar.isNotEmpty() && bar.all { it.secondsLeft in 1..600 })
        // elapses: players still inside, so a new intro starts
        val fx3 = e.tick(10_000 + 600_000 + 1)
        assertTrue(Effect.SpawnBoss in fx3)
        assertEquals(State.INTRO, e.state)
    }

    @Test fun cooldownEndsToIdleWhenEmpty() {
        val e = BossEncounter(10)
        fight(e, a)
        e.bossDamaged(a, 5.0)
        e.tick(5000)
        e.bossDead()
        e.tick(5000)
        e.playerLeft(a)
        e.tick(5000 + 10_001)
        assertEquals(State.IDLE, e.state)
    }

    @Test fun allParticipantsGone_resetsAfter10s() {
        val e = BossEncounter(600)
        fight(e, a)
        e.playerDied(a)
        e.tick(4000)
        assertEquals(State.FIGHT, e.state)
        val fx = e.tick(14_000)
        assertEquals(State.IDLE, e.state)
        assertTrue(Effect.RemoveBoss in fx && Effect.HideBar in fx)
        assertFalse(fx.any { it is Effect.Reward })
        assertTrue(Effect.SpawnBoss in e.playerEntered(b))
    }

    @Test fun participantReturnsWithin10s_noReset() {
        val e = BossEncounter(600)
        fight(e, a)
        e.playerLeft(a)
        e.tick(5000)
        e.tick(12_000)
        e.playerEntered(a)
        e.tick(13_000)
        e.tick(30_000)
        assertEquals(State.FIGHT, e.state)
    }

    @Test fun victory_rewardsWinnersOnly() {
        val e = BossEncounter(600)
        val c = UUID.randomUUID()
        val d = UUID.randomUUID()
        fight(e, a, b, c, d)
        e.bossDamaged(a, 30.0)
        e.bossDamaged(b, 1.0)
        e.bossDamaged(c, 40.0)
        e.playerDied(c) // died inside during the fight: still a winner
        e.playerLeft(b) // dealt damage but walked out before the kill: not a winner
        // d never hit the boss
        val fx = e.bossDead()
        val reward = fx.filterIsInstance<Effect.Reward>().single()
        assertEquals(setOf(a, c), reward.winners)
        assertTrue(fx.any { it is Effect.Title && it.text == BossEncounter.VICTORY_TITLE })
        assertTrue(Effect.HideBar in fx)
        assertEquals(State.VICTORY, e.state)
    }

    @Test fun afkAtDoor_noReward() {
        val e = BossEncounter(600)
        fight(e, a, b)
        e.bossDamaged(a, 100.0)
        val reward = e.bossDead().filterIsInstance<Effect.Reward>().single()
        assertEquals(setOf(a), reward.winners)
    }

    @Test fun belowOneDamageIsNotEnough() {
        val e = BossEncounter(600)
        fight(e, a)
        e.bossDamaged(a, 0.9)
        assertTrue(e.bossDead().filterIsInstance<Effect.Reward>().single().winners.isEmpty())
    }

    @Test fun shutdown_midFight_noRewards_idle() {
        val e = BossEncounter(600)
        fight(e, a)
        e.bossDamaged(a, 100.0)
        val fx = e.shutdown()
        assertEquals(State.IDLE, e.state)
        assertTrue(Effect.RemoveBoss in fx && Effect.HideBar in fx)
        assertFalse(fx.any { it is Effect.Reward })
        assertEquals(emptySet(), e.participants)
    }

    @Test fun damageOutsideFightIsIgnored() {
        val e = BossEncounter(600)
        e.playerEntered(a)
        e.bossDamaged(a, 50.0) // during the intro
        e.tick(0)
        e.tick(3000)
        assertTrue(e.bossDead().filterIsInstance<Effect.Reward>().single().winners.isEmpty())
    }
}
