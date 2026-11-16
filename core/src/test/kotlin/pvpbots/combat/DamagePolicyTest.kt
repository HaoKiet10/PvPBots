package pvpbots.combat

import pvpbots.combat.Party.Ally
import pvpbots.combat.Party.Mob
import pvpbots.combat.Party.Other
import pvpbots.combat.Party.RealPlayer
import pvpbots.combat.Party.Trainer
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DamagePolicyTest {
    private val bot = UUID(3, 1)
    private val owner = UUID(3, 2)
    private val other = UUID(3, 3)

    @Test
    fun trainerOnlyFightsOwner() {
        assertTrue(DamagePolicy.allow(Trainer(bot, owner), RealPlayer(owner)))
        assertTrue(DamagePolicy.allow(RealPlayer(owner), Trainer(bot, owner)))
        assertFalse(DamagePolicy.allow(Trainer(bot, owner), RealPlayer(other)))
        assertFalse(DamagePolicy.allow(RealPlayer(other), Trainer(bot, owner)))
        assertFalse(DamagePolicy.allow(Mob, Trainer(bot, owner)))
        assertFalse(DamagePolicy.allow(Trainer(bot, owner), Mob))
        assertFalse(DamagePolicy.allow(Ally(UUID(3, 4)), Trainer(bot, owner)))
        assertFalse(DamagePolicy.allow(Trainer(bot, owner), Ally(UUID(3, 4))))
        assertFalse(DamagePolicy.allow(Trainer(bot, owner), Trainer(UUID(3, 5), other)))
        assertFalse(DamagePolicy.allow(Other, Trainer(bot, owner)))
    }

    @Test
    fun alliesNeverHurtPlayers() {
        assertFalse(DamagePolicy.allow(Ally(bot), RealPlayer(other)))
        assertFalse(DamagePolicy.allow(RealPlayer(other), Ally(bot)))
        assertFalse(DamagePolicy.allow(Ally(bot), Ally(UUID(3, 4))))
        assertTrue(DamagePolicy.allow(Mob, Ally(bot)))
        assertTrue(DamagePolicy.allow(Ally(bot), Mob))
    }

    @Test
    fun everythingElseAllowed() {
        assertTrue(DamagePolicy.allow(RealPlayer(owner), RealPlayer(other)))
        assertTrue(DamagePolicy.allow(Mob, RealPlayer(other)))
        assertTrue(DamagePolicy.allow(RealPlayer(other), Mob))
        assertTrue(DamagePolicy.allow(Other, RealPlayer(other)))
    }
}
