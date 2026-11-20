package pvpbots.combat

import pvpbots.combat.Party.Ally
import pvpbots.combat.Party.Boss
import pvpbots.combat.Party.Mob
import pvpbots.combat.Party.Other
import pvpbots.combat.Party.RealPlayer
import pvpbots.combat.Party.TeamBot
import pvpbots.combat.Party.Trainer
import pvpbots.team.Side
import pvpbots.team.TeamSeat
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DamagePolicyTest {
    private val bot = UUID(3, 1)
    private val owner = UUID(3, 2)
    private val other = UUID(3, 3)

    private val none: (UUID) -> TeamSeat? = { null }

    private fun allow(a: Party, v: Party, sideOf: (UUID) -> TeamSeat? = none): Boolean =
        DamagePolicy.allow(a, v, sideOf) { null }

    @Test
    fun trainerOnlyFightsOwner() {
        assertTrue(allow(Trainer(bot, owner), RealPlayer(owner)))
        assertTrue(allow(RealPlayer(owner), Trainer(bot, owner)))
        assertFalse(allow(Trainer(bot, owner), RealPlayer(other)))
        assertFalse(allow(RealPlayer(other), Trainer(bot, owner)))
        assertFalse(allow(Mob, Trainer(bot, owner)))
        assertFalse(allow(Trainer(bot, owner), Mob))
        assertFalse(allow(Ally(UUID(3, 4)), Trainer(bot, owner)))
        assertFalse(allow(Trainer(bot, owner), Ally(UUID(3, 4))))
        assertFalse(allow(Trainer(bot, owner), Trainer(UUID(3, 5), other)))
        assertFalse(allow(Other, Trainer(bot, owner)))
    }

    @Test
    fun alliesNeverHurtPlayers() {
        assertFalse(allow(Ally(bot), RealPlayer(other)))
        assertFalse(allow(RealPlayer(other), Ally(bot)))
        assertFalse(allow(Ally(bot), Ally(UUID(3, 4))))
        assertTrue(allow(Mob, Ally(bot)))
        assertTrue(allow(Ally(bot), Mob))
    }

    @Test
    fun everythingElseAllowed() {
        assertTrue(allow(RealPlayer(owner), RealPlayer(other)))
        assertTrue(allow(Mob, RealPlayer(other)))
        assertTrue(allow(RealPlayer(other), Mob))
        assertTrue(allow(Other, RealPlayer(other)))
    }

    private val b1 = UUID(3, 10)
    private val b2 = UUID(3, 11)
    private val b3 = UUID(3, 12)
    private val p1 = UUID(3, 20)
    private val p2 = UUID(3, 21)
    private val p3 = UUID(3, 22)
    private val outsider = UUID(3, 23)
    private fun seats(fighting: Boolean) = mapOf(
        p1 to TeamSeat("arena", Side.A, fighting), p2 to TeamSeat("arena", Side.A, fighting), p3 to TeamSeat("arena", Side.B, fighting),
        b1 to TeamSeat("arena", Side.A, fighting), b2 to TeamSeat("arena", Side.B, fighting),
    )
    private val sides = seats(true)
    private val sideOf: (UUID) -> TeamSeat? = { sides[it] }
    private val notFighting = seats(false)
    private val sideOfNotFighting: (UUID) -> TeamSeat? = { notFighting[it] }

    @Test
    fun teamBots_noFriendlyFire() {
        val a1 = TeamBot(b1, "arena", Side.A)
        val a2 = TeamBot(UUID(3, 13), "arena", Side.A)
        val bb = TeamBot(b2, "arena", Side.B)
        assertFalse(allow(a1, a2, sideOf))
        assertTrue(allow(a1, bb, sideOf))
        assertTrue(allow(bb, a1, sideOf))
        assertFalse(allow(a1, RealPlayer(p1), sideOf)) // teammate player
        assertFalse(allow(RealPlayer(p1), a1, sideOf))
        assertTrue(allow(a1, RealPlayer(p3), sideOf)) // enemy player
        assertTrue(allow(RealPlayer(p3), a1, sideOf))
        assertFalse(allow(RealPlayer(p1), RealPlayer(p2), sideOf)) // player <-> teammate player
        assertTrue(allow(RealPlayer(p1), RealPlayer(p3), sideOf)) // player <-> enemy player in a team round
    }

    @Test
    fun teamBot_neverHitsOutsiders() {
        val a1 = TeamBot(b1, "arena", Side.A)
        val other = TeamBot(b3, "elsewhere", Side.B)
        assertFalse(allow(a1, RealPlayer(outsider), sideOf))
        assertFalse(allow(RealPlayer(outsider), a1, sideOf))
        assertFalse(allow(a1, other, sideOf)) // another arena
        assertFalse(allow(a1, Mob, sideOf))
        assertFalse(allow(Mob, a1, sideOf))
        assertFalse(allow(a1, Trainer(bot, owner), sideOf))
        assertFalse(allow(Ally(bot), a1, sideOf))
        assertFalse(allow(a1, Other, sideOf))
        // a seated player is protected from, and cannot hit, outsiders
        assertFalse(allow(RealPlayer(p3), RealPlayer(outsider), sideOf))
        assertFalse(allow(RealPlayer(outsider), RealPlayer(p3), sideOf))
        // outside team rounds nothing changes
        assertTrue(allow(RealPlayer(outsider), RealPlayer(UUID(3, 24)), sideOf))
    }

    @Test
    fun seatedEnemies_onlyHurtEachOtherWhileFighting() {
        val a1 = TeamBot(b1, "arena", Side.A)
        val bb = TeamBot(b2, "arena", Side.B)
        assertFalse(allow(RealPlayer(p1), RealPlayer(p3), sideOfNotFighting)) // enemy players before the fight
        assertFalse(allow(RealPlayer(p3), RealPlayer(p1), sideOfNotFighting))
        assertFalse(allow(a1, bb, sideOfNotFighting)) // enemy bots
        assertFalse(allow(bb, a1, sideOfNotFighting))
        assertFalse(allow(a1, RealPlayer(p3), sideOfNotFighting)) // bot <-> enemy player
        assertFalse(allow(RealPlayer(p3), a1, sideOfNotFighting))
        assertFalse(allow(RealPlayer(p1), RealPlayer(p2), sideOfNotFighting)) // teammates: still never
        // seated <-> unseated stays blocked
        assertFalse(allow(RealPlayer(p3), RealPlayer(outsider), sideOfNotFighting))
        assertFalse(allow(RealPlayer(outsider), RealPlayer(p3), sideOfNotFighting))
        assertFalse(allow(a1, Mob, sideOfNotFighting))
        // a team bot the round no longer knows (removed surplus filler) is never in the fight
        assertFalse(allow(TeamBot(UUID(3, 14), "arena", Side.B), RealPlayer(p1), sideOf))
    }

    private val inArena: (UUID) -> String? = { if (it == owner) "pit" else if (it == other) "cave" else null }

    private fun allowBoss(a: Party, v: Party): Boolean = DamagePolicy.allow(a, v, none, inArena)

    @Test
    fun boss_onlyFightsPlayersInsideItsArena() {
        val boss = Boss(bot, "pit")
        assertTrue(allowBoss(boss, RealPlayer(owner)))
        assertTrue(allowBoss(RealPlayer(owner), boss))
        assertFalse(allowBoss(boss, RealPlayer(other))) // another boss arena
        assertFalse(allowBoss(RealPlayer(other), boss))
        assertFalse(allowBoss(RealPlayer(UUID(3, 40)), boss)) // outside every boss arena
        assertFalse(allowBoss(boss, Trainer(UUID(3, 41), owner)))
        assertFalse(allowBoss(Trainer(UUID(3, 41), owner), boss))
        assertFalse(allowBoss(Ally(UUID(3, 42)), boss))
        assertFalse(allowBoss(boss, Ally(UUID(3, 42))))
        assertFalse(allowBoss(TeamBot(UUID(3, 43), "t", Side.A), boss))
        assertFalse(allowBoss(boss, Boss(UUID(3, 44), "pit")))
        assertFalse(allowBoss(Mob, boss))
    }

    @Test
    fun playersNeverHurtEachOtherInBossArenas() {
        val free = UUID(3, 45)
        assertFalse(allowBoss(RealPlayer(owner), RealPlayer(free)))
        assertFalse(allowBoss(RealPlayer(free), RealPlayer(owner)))
        assertFalse(allowBoss(RealPlayer(owner), RealPlayer(other)))
        assertTrue(allowBoss(RealPlayer(free), RealPlayer(UUID(3, 46))))
        assertTrue(allowBoss(RealPlayer(owner), Mob)) // mobs in the arena are fair game
    }
}
