package pvpbots.session

import pvpbots.math.BlockPos
import pvpbots.math.Bounds
import pvpbots.profile.Difficulty
import pvpbots.session.AllySquad.State.*
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AllySquadTest {
    private val P1 = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val P2 = UUID.fromString("00000000-0000-0000-0000-000000000002")
    private val BOT = UUID.fromString("00000000-0000-0000-0000-0000000000b0")
    private val SQ = SessionKey.Squad("world", "boss")

    private fun squad(count: Int) = AllySquad(
        AreaRef("world", "boss", Role.ALLY, Difficulty.MEDIUM, count, Bounds(BlockPos(0, 0, 0), BlockPos(10, 10, 10))),
        Timings(3, 300, 5, 15, 10),
    )

    private fun squadWithBots(): AllySquad {
        val q = squad(10); q.playerEntered(P1, 0); q.slotGranted(0); q.botSpawned(BOT, 0); return q
    }

    @Test fun squadRequestsCount() {
        val q = squad(10)
        assertEquals(listOf(Effect.RequestSlots(SQ, 10)), q.playerEntered(P1, 0))
        assertEquals(SQ, q.key)
        assertEquals(ACTIVE, q.state())
    }

    @Test fun secondPlayerRequestsNothing() {
        val q = squad(10); q.playerEntered(P1, 0)
        assertTrue(q.playerEntered(P2, 1).isEmpty())
    }

    @Test fun allyRespawns() {
        val q = squadWithBots(); q.allyDied(BOT, 0)
        assertTrue(q.bots().isEmpty())
        assertTrue(q.tick(14_999).isEmpty())
        assertTrue(q.tick(15_000).contains(Effect.RequestSlots(SQ, 1)))
        assertTrue(q.tick(15_001).isEmpty())
    }

    @Test fun graceThenEnd() {
        val q = squadWithBots(); q.playerLeft(P1, 0)
        assertEquals(GRACE, q.state())
        assertTrue(q.tick(9_999).isEmpty())
        assertEquals(listOf(Effect.CancelSlotRequests(SQ), Effect.RemoveBot(BOT)), q.tick(10_000))
        assertEquals(ENDED, q.state())
    }

    @Test fun reenterDuringGrace() {
        val q = squadWithBots(); q.playerLeft(P1, 0)
        assertTrue(q.playerEntered(P2, 5_000).isEmpty())
        assertEquals(ACTIVE, q.state())
        assertTrue(q.tick(20_000).isEmpty())
    }

    @Test fun slotGrantedSpawnsAllyNearAPlayer() {
        val q = squad(2); q.playerEntered(P1, 0)
        assertEquals(listOf(Effect.SpawnBot(SQ, Role.ALLY, Difficulty.MEDIUM, P1)), q.slotGranted(0))
    }

    @Test fun botSpawnedAddsToBots() {
        assertEquals(setOf(BOT), squadWithBots().bots())
    }

    @Test fun graceOnlyWhenLastPlayerLeaves() {
        val q = squad(2); q.playerEntered(P1, 0); q.playerEntered(P2, 0)
        q.playerLeft(P1, 1)
        assertEquals(ACTIVE, q.state())
        q.playerLeft(P2, 2)
        assertEquals(GRACE, q.state())
    }

    @Test fun respawnDueDuringGraceKeptUntilActiveAgain() {
        val q = squadWithBots(); q.allyDied(BOT, 0); q.playerLeft(P1, 1_000)
        assertTrue(q.tick(5_000).isEmpty())
        q.playerEntered(P2, 8_000)
        assertTrue(q.tick(15_000).contains(Effect.RequestSlots(SQ, 1)))
    }

    @Test fun respawnDueDuringGraceNotEmittedWhileGrace() {
        val q = squad(2); q.playerEntered(P1, 0); q.slotGranted(0); q.botSpawned(BOT, 0)
        q.allyDied(BOT, 0); q.playerLeft(P1, 5_000)
        // respawn due at 15_000, grace (10s) expires at 15_000 -> ends, no respawn request
        assertEquals(listOf(Effect.CancelSlotRequests(SQ)), q.tick(15_000))
        assertEquals(ENDED, q.state())
    }

    @Test fun endedIgnoresLateEvents() {
        val q = squadWithBots(); q.playerLeft(P1, 0); q.tick(10_000)
        assertTrue(q.slotGranted(11_000).isEmpty())
        assertTrue(q.playerEntered(P2, 12_000).isEmpty())
        assertTrue(q.tick(99_000).isEmpty())
        assertEquals(ENDED, q.state())
    }
}
