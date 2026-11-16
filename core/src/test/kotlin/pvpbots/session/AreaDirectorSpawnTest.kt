package pvpbots.session

import pvpbots.math.BlockPos
import pvpbots.math.Bounds
import pvpbots.profile.Difficulty
import pvpbots.slots.BotSlots
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Spawn rate limit, spawn-failure isolation, dead owners, busy resend and the failure streak. */
class AreaDirectorSpawnTest {
    private val P1 = UUID(0, 1)
    private val P2 = UUID(0, 2)
    private val P3 = UUID(0, 3)
    private val W = "world"
    private val B = Bounds(BlockPos(0, 0, 0), BlockPos(10, 10, 10))
    private val ARENA = AreaRef(W, "arena", Role.TRAINER, Difficulty.MEDIUM, 1, B)
    private val BOSS5 = AreaRef(W, "boss", Role.ALLY, Difficulty.MEDIUM, 5, B)
    private val BOSS2 = AreaRef(W, "boss2", Role.ALLY, Difficulty.MEDIUM, 2, B)
    private val T = Timings(3, 300, 5, 15, 10)
    private val BUSY = "All trainers busy, please wait…"

    private val port = FakeBotPort()
    private val logged = mutableListOf<Pair<String, Throwable?>>()
    private lateinit var slots: BotSlots
    private lateinit var d: AreaDirector

    private fun director(capacity: Int, spawnsPerTick: Int = 2) {
        slots = BotSlots(capacity)
        d = AreaDirector(slots, port, T, spawnsPerTick) { msg, e -> logged += msg to e }
    }

    // ---- spawn budget (R11) ----

    @Test fun eventsOutsideTick_doNotSpawn_butReserveTheSlot() {
        director(10)
        d.onAreaChange(P1, null, ARENA, 0)
        assertTrue(port.spawned.isEmpty())
        assertEquals(1, slots.used())
        assertEquals(1, d.pendingSpawns())
        d.tick(50)
        assertEquals(1, port.spawned.size)
        assertEquals(0, d.pendingSpawns())
    }

    @Test fun atMostSpawnsPerTick_inFifoOrder() {
        director(10, spawnsPerTick = 2)
        d.onAreaChange(P1, null, BOSS5, 0)   // 5 allies granted first
        d.onAreaChange(P2, null, ARENA, 0)   // then a trainer
        assertEquals(6, slots.used())
        d.tick(50); assertEquals(2, port.spawned.size)
        d.tick(100); assertEquals(4, port.spawned.size)
        d.tick(150); assertEquals(6, port.spawned.size)
        assertEquals(List(5) { Role.ALLY } + Role.TRAINER, port.spawned.map { it.role })
        d.tick(200); assertEquals(6, port.spawned.size)
    }

    @Test fun spawnsGrantedDuringTick_spawnSameTickWithinBudget() {
        director(10, spawnsPerTick = 1)
        d.onAreaChange(P1, null, ARENA, 0); d.tick(0)
        d.tick(3000)                                     // fighting
        d.onBotDeath(port.spawned[0].botId, 3500)
        d.tick(8500)                                     // rematch requested and spawned in this tick
        assertEquals(2, port.spawned.size)
    }

    @Test fun pendingSpawnOfEndedSession_isDroppedAndSlotReleased() {
        director(10, spawnsPerTick = 1)
        d.onAreaChange(P1, null, ARENA, 0)
        d.onAreaChange(P2, null, ARENA, 0)
        assertEquals(2, slots.used())
        d.tick(50)                                       // P1 spawns, P2 waits
        d.onAreaChange(P2, ARENA, null, 60)              // P2 leaves before its turn
        assertEquals(1, slots.used())
        assertEquals(0, d.pendingSpawns())
        d.tick(100); d.tick(150)
        assertEquals(1, port.spawned.size)
        assertEquals(P1, port.spawned[0].near)
    }

    @Test fun pendingSquadSpawns_stillSpawnDuringGrace() {
        director(10, spawnsPerTick = 1)
        d.onAreaChange(P1, null, BOSS5, 0)
        d.tick(0)                                        // 1 ally, 4 pending
        d.onAreaChange(P1, BOSS5, null, 0)               // grace starts; pending ones still spawn during grace
        for (i in 1..20) d.tick(i * 50L)
        assertEquals(5, port.spawned.size)
        d.tick(10_000)                                   // grace over: all removed
        assertTrue(port.alive.isEmpty())
        assertEquals(0, slots.used())
    }

    @Test fun squadEndingWithSpawnsStillPending_releasesThem() {
        director(10, spawnsPerTick = 1)
        d.onAreaChange(P1, null, BOSS5, 0)
        d.tick(0)
        d.onAreaChange(P1, BOSS5, null, 0)
        d.tick(10_000)                                   // grace ends before the other 4 got their turn
        assertEquals(1, port.spawned.size)
        assertTrue(port.alive.isEmpty())
        assertEquals(0, slots.used())
        assertEquals(0, d.pendingSpawns())
        d.tick(10_050)
        assertEquals(1, port.spawned.size)
    }

    @Test fun shutdown_dropsPendingSpawns() {
        director(10, spawnsPerTick = 1)
        d.onAreaChange(P1, null, BOSS5, 0)
        d.onAreaChange(P2, null, ARENA, 0)
        d.tick(0)
        d.shutdown()
        assertEquals(0, d.pendingSpawns())
        assertEquals(0, slots.used())
        assertTrue(port.alive.isEmpty())
        d.tick(50)
        assertEquals(1, port.spawned.size)
    }

    // ---- spawn failures are isolated (finding 1) ----

    private fun throwingSpawnIsIsolated(t: Throwable) {
        director(10)
        port.throwOnNextSpawn = t
        d.onAreaChange(P1, null, ARENA, 0)
        d.tick(0)
        assertTrue(port.spawned.isEmpty())
        assertEquals(0, slots.used(), "slot released")
        assertEquals(1, logged.count { it.second === t })
        // Another session's work in the same director still runs, and nothing stale comes later.
        d.onAreaChange(P2, null, BOSS2, 10)
        d.tick(10)
        assertEquals(listOf(Role.ALLY, Role.ALLY), port.spawned.map { it.role })
        assertEquals(2, slots.used())
        d.tick(4999)
        assertEquals(2, port.spawned.size)
        // The trainer session is not stuck: it re-requests after 5 s and gets a bot.
        d.tick(5000)
        assertEquals(3, port.spawned.size)
        assertEquals(Role.TRAINER, port.spawned.last().role)
        assertEquals(SessionKey.Trainer(P1), d.sessionOfBot(port.spawned.last().botId))
        assertEquals(3, slots.used())
        d.tick(8000)
        assertEquals(TrainerSession.State.FIGHTING, d.trainerFor(P1)?.state())
        assertTrue(port.removed.isEmpty())
        assertTrue(port.titles.isEmpty())
    }

    @Test fun spawnThrowingError_isIsolated() = throwingSpawnIsIsolated(NoSuchMethodError("FakeAdapter.spawn"))

    @Test fun spawnThrowingException_isIsolated() = throwingSpawnIsIsolated(IllegalStateException("boom"))

    @Test fun throwingSpawnInsideSquadBurst_otherSpawnsOfTheSameTickStillRun() {
        director(10, spawnsPerTick = 5)
        port.throwOnNextSpawn = LinkageError("first ally")
        d.onAreaChange(P1, null, BOSS5, 0)
        d.tick(0)
        assertEquals(4, port.spawned.size)
        assertEquals(4, slots.used())
        d.tick(5000)
        assertEquals(5, port.spawned.size)
        assertEquals(5, slots.used())
    }

    @Test fun throwingRemove_stillReleasesTheSlot() {
        director(10)
        val failing = object : BotPort by port {
            override fun remove(botId: UUID) = throw AbstractMethodError("remove")
        }
        slots = BotSlots(10)
        d = AreaDirector(slots, failing, T, 2) { msg, e -> logged += msg to e }
        d.onAreaChange(P1, null, ARENA, 0); d.tick(0)
        d.onAreaChange(P1, ARENA, null, 10)
        assertEquals(0, slots.used())
        assertNull(d.sessionOfBot(port.spawned[0].botId))
        assertEquals(1, logged.size)
    }

    // ---- busy action bar (M7) ----

    @Test fun busyBar_resentEveryTwoSecondsWhileQueued() {
        director(1)
        d.onAreaChange(P1, null, ARENA, 0); d.tick(0)
        d.onAreaChange(P2, null, ARENA, 0)
        assertEquals(listOf(BUSY), port.actionBars[P2])
        d.tick(1950); assertEquals(1, port.actionBars[P2]!!.size)
        d.tick(2000); assertEquals(2, port.actionBars[P2]!!.size)
        d.tick(3000); assertEquals(2, port.actionBars[P2]!!.size)
        d.tick(4000); assertEquals(3, port.actionBars[P2]!!.size)
        assertTrue(port.actionBars[P2]!!.all { it == BUSY })
        assertNull(port.actionBars[P1])
        d.onAreaChange(P1, ARENA, null, 4100); d.tick(4100)  // P2 gets the slot
        d.tick(6100); d.tick(8100)
        assertEquals(3, port.actionBars[P2]!!.size)
    }

    // ---- dead owners (M9) ----

    @Test fun rematchWaitsForOwnerToRespawn() {
        director(10)
        d.onAreaChange(P1, null, ARENA, 0); d.tick(0); d.tick(3000)
        d.onPlayerDeath(P1, 4000)
        d.tick(9000)                                    // rematch delay over, owner still dead
        d.tick(9050)
        assertEquals(1, port.spawned.size)
        assertEquals(1, slots.used(), "the held spawn keeps its slot")
        d.onPlayerRespawn(P1, 12_000)
        assertEquals(1, port.spawned.size, "respawn alone does not spawn outside tick")
        d.tick(12_050)
        assertEquals(2, port.spawned.size)
        assertEquals(TrainerSession.State.COUNTDOWN, d.trainerFor(P1)?.state())
    }

    @Test fun deadOwnerReportedByPort_isHeldToo() {
        director(10)
        port.deadPlayers += P1
        d.onAreaChange(P1, null, ARENA, 0)
        d.tick(0); d.tick(50)
        assertTrue(port.spawned.isEmpty())
        port.deadPlayers -= P1
        d.onPlayerRespawn(P1, 100)
        d.tick(100)
        assertEquals(1, port.spawned.size)
    }

    @Test fun deadOwnerLeaving_releasesHeldSlot() {
        director(10)
        d.onAreaChange(P1, null, ARENA, 0); d.tick(0); d.tick(3000)
        d.onPlayerDeath(P1, 4000)
        d.tick(9000)
        assertEquals(1, slots.used())
        d.onQuit(P1, 10_000)
        assertEquals(0, slots.used())
        assertEquals(0, d.pendingSpawns())
        d.onPlayerRespawn(P1, 11_000); d.tick(11_000)
        assertEquals(1, port.spawned.size)
    }

    @Test fun deadOwnerDoesNotBlockOthers() {
        director(10, spawnsPerTick = 1)
        port.deadPlayers += P1
        d.onAreaChange(P1, null, ARENA, 0)
        d.onAreaChange(P2, null, ARENA, 0)
        d.tick(0)
        assertEquals(listOf(P2), port.spawned.map { it.near })
    }

    // ---- failure streak (finding 4) ----

    @Test fun trainerFailingThreeTimes_endsSessionUntilReEntry() {
        director(10)
        d.onAreaChange(P1, null, ARENA, 0); d.tick(0)
        var now = 0L
        repeat(2) {
            now += 3000; d.tick(now)
            d.onBotFailed(port.spawned.last().botId, now)
            assertTrue(port.titles.isEmpty(), "no title for a failed bot")
            now += 5000; d.tick(now)                    // rematch
        }
        assertEquals(3, port.spawned.size)
        d.onBotFailed(port.spawned.last().botId, now + 100)
        assertEquals(0, slots.used())
        assertTrue(port.alive.isEmpty())
        assertEquals(1, logged.count { it.first.contains("ended after 3 consecutive bot failures") })
        for (i in 1..10) d.tick(now + i * 5000L)
        assertEquals(3, port.spawned.size)
        assertNull(d.trainerFor(P1))
        d.onAreaChange(P1, ARENA, null, now + 60_000)   // leave and re-enter: starts over
        d.onAreaChange(P1, null, ARENA, now + 61_000); d.tick(now + 61_000)
        assertEquals(4, port.spawned.size)
    }

    @Test fun normalDeathResetsTheStreak() {
        director(10)
        d.onAreaChange(P1, null, ARENA, 0); d.tick(0)
        var now = 0L
        repeat(6) { i ->
            now += 3000; d.tick(now)
            val bot = port.spawned.last().botId
            if (i % 2 == 0) d.onBotFailed(bot, now) else d.onBotDeath(bot, now)
            now += 5000; d.tick(now)
        }
        assertEquals(7, port.spawned.size)
        assertTrue(logged.none { it.first.contains("consecutive") })
    }

    @Test fun squadFailingThreeTimes_stopsRespawningThatSlot() {
        director(10)
        d.onAreaChange(P1, null, BOSS2, 0); d.tick(0)
        var now = 0L
        repeat(3) {
            val bot = port.alive.first()
            d.onBotFailed(bot, now)
            now += 15_000; d.tick(now)
        }
        // failures 1 and 2 were respawned; the 3rd was not
        assertEquals(4, port.spawned.size)
        assertEquals(1, port.alive.size)
        assertEquals(1, slots.used())
        assertEquals(1, logged.count { it.first.contains("stopped respawning") })
        for (i in 1..4) d.tick(now + i * 15_000L)
        assertEquals(4, port.spawned.size)
    }

    @Test fun spawnFailingThreeTimes_endsTrainerSession() {
        director(10)
        port.failAllSpawns = true
        d.onAreaChange(P1, null, ARENA, 0); d.tick(0)
        for (i in 1..10) d.tick(i * 5000L)
        assertEquals(3, port.spawnCalls)
        assertEquals(0, slots.used())
        assertEquals(0, slots.queued())
        assertNull(d.trainerFor(P1))
        assertEquals(1, logged.count { it.first.contains("ended after 3 consecutive bot failures") })
    }

    @Test fun squadSpawnFailingThreeTimes_stopsRetrying() {
        director(10)
        port.failAllSpawns = true
        d.onAreaChange(P3, null, BOSS2, 0); d.tick(0)
        for (i in 1..10) d.tick(i * 5000L)
        assertEquals(0, slots.used())
        assertTrue(port.spawnCalls in 3..4, "spawn calls ${port.spawnCalls}")
        val before = port.spawnCalls
        for (i in 11..20) d.tick(i * 5000L)
        assertEquals(before, port.spawnCalls)
    }
}
