package pvpbots.session

import pvpbots.math.BlockPos
import pvpbots.math.Bounds
import pvpbots.profile.Difficulty
import pvpbots.slots.BotSlots
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AreaDirectorTest {
    private val P1 = UUID(0, 1)
    private val P2 = UUID(0, 2)
    private val P3 = UUID(0, 3)
    private val P4 = UUID(0, 4)
    private val BOTX = UUID(0, 0xff)
    private val W = "world"
    private val B = Bounds(BlockPos(0, 0, 0), BlockPos(10, 10, 10))
    private val ARENA = AreaRef(W, "arena", Role.TRAINER, Difficulty.MEDIUM, 1, B)
    private val BOSS = AreaRef(W, "boss", Role.ALLY, Difficulty.MEDIUM, 2, B)
    private val T = Timings(3, 300, 5, 15, 10)
    private val BUSY = "All trainers busy, please wait…"

    private val port = FakeBotPort()
    private lateinit var slots: BotSlots
    private lateinit var d: AreaDirector

    private val logged = mutableListOf<Pair<String, Throwable?>>()

    private fun director(capacity: Int, spawnsPerTick: Int = 100): AreaDirector {
        slots = BotSlots(capacity)
        d = AreaDirector(slots, port, T, spawnsPerTick) { msg, e -> logged += msg to e }
        return d
    }

    /** Enters, then runs the director tick at the same time (spawns happen in tick, within the budget). */
    private fun enter(p: UUID, a: AreaRef, now: Long = 0) {
        d.onAreaChange(p, null, a, now)
        d.tick(now)
    }

    @Test fun enterArena_spawnsPersonalBot() {
        director(10)
        enter(P1, ARENA)
        assertEquals(1, port.spawned.size)
        assertEquals(P1, port.spawned[0].near)
        assertEquals(Role.TRAINER, port.spawned[0].role)
        assertEquals(1, slots.used())
        assertNotNull(d.trainerFor(P1))
        assertEquals(SessionKey.Trainer(P1), d.sessionOfBot(port.spawned[0].botId))
    }

    @Test fun botEnteringArena_startsNothing() {
        director(10)
        port.bots.add(BOTX)
        d.onAreaChange(BOTX, null, ARENA, 0)
        assertTrue(port.spawned.isEmpty())
        assertNull(d.trainerFor(BOTX))
    }

    @Test fun capQueuesSecondPlayer() {
        director(1)
        enter(P1, ARENA); enter(P2, ARENA)
        assertEquals(1, port.spawned.size)
        assertEquals(listOf(BUSY), port.actionBars[P2])
        assertNull(port.actionBars[P1])
    }

    @Test fun slotPassesToQueuedPlayerOnLeave() {
        director(1)
        enter(P1, ARENA); enter(P2, ARENA)
        d.tick(3000)
        d.onAreaChange(P1, ARENA, null, 3500)
        d.tick(3500)
        assertEquals(2, port.spawned.size)
        assertEquals(P2, port.lastSpawnNear)
        assertEquals(1, slots.used())
        assertEquals(0, slots.queued())
    }

    @Test fun movingArenaToBossArea_switchesRole() {
        director(10)
        enter(P1, ARENA)
        d.onAreaChange(P1, ARENA, BOSS, 0)
        d.tick(0)
        assertNull(d.trainerFor(P1))
        assertNotNull(d.squadAt(W, "boss"))
        assertEquals(1, port.removed.size)          // trainer bot gone
        assertEquals(1 + 2, port.spawned.size)      // trainer bot + 2 allies
        assertEquals(2, slots.used())
    }

    @Test fun shutdown_removesAllBotsAndClearsQueue() {
        director(2)
        enter(P1, ARENA); d.tick(3000)           // fighting
        enter(P2, ARENA, 3000)                   // second slot
        enter(P3, ARENA, 3000)                   // queued
        d.onAreaChange(P4, null, BOSS, 3000)     // squad queued behind
        assertTrue(slots.queued() > 0)
        d.shutdown()
        assertTrue(port.alive.isEmpty())
        assertEquals(0, slots.used())
        assertEquals(0, slots.queued())
        val before = port.spawned.size
        d.tick(60_000)
        enter(P1, ARENA, 60_000)
        assertEquals(before, port.spawned.size)
        assertNull(d.trainerFor(P1))
    }

    @Test fun shutdown_removesSquadBots() {
        director(10)
        enter(P1, BOSS)
        assertEquals(2, port.alive.size)
        d.shutdown()
        assertTrue(port.alive.isEmpty())
        assertEquals(0, slots.used())
        assertNull(d.squadAt(W, "boss"))
    }

    @Test fun spawnFailure_releasesSlot() {
        director(10)
        port.failNextSpawn = true
        enter(P1, ARENA)
        assertEquals(0, slots.used())
        assertTrue(port.spawned.isEmpty())
    }

    @Test fun spawnFailure_reRequestsAfterFiveSeconds() {
        director(10)
        port.failNextSpawn = true
        enter(P1, ARENA)
        d.tick(4999)
        assertTrue(port.spawned.isEmpty())
        d.tick(5000)
        assertEquals(1, port.spawned.size)
        assertEquals(1, slots.used())
        d.tick(10_000)
        assertEquals(1, port.spawned.size)
    }

    @Test fun spawnFailure_slotGoesToNextQueuedWhileWaiting() {
        director(1)
        port.failNextSpawn = true
        enter(P1, ARENA)          // P1's spawn fails -> slot released
        enter(P2, ARENA, 100)     // P2 gets it
        assertEquals(P2, port.lastSpawnNear)
        d.tick(5000)              // P1 re-requests, queues
        assertEquals(1, port.spawned.size)
        assertEquals(1, slots.queued())
    }

    @Test fun spawnFailure_noRetryIfOwnerLeft() {
        director(10)
        port.failNextSpawn = true
        enter(P1, ARENA)
        d.onAreaChange(P1, ARENA, null, 1000)
        d.tick(6000)
        assertTrue(port.spawned.isEmpty())
        assertEquals(0, slots.used())
        assertEquals(0, slots.queued())
    }

    @Test fun botSpawnedForGoneSession_isRemovedAndSlotReleased() {
        director(10)
        // the owner leaves while the port is spawning (re-entrant call)
        port.onSpawn = { d.onAreaChange(P1, ARENA, null, 1) }
        enter(P1, ARENA)
        assertTrue(port.alive.isEmpty())
        assertEquals(0, slots.used())
        assertNull(d.sessionOfBot(port.spawned[0].botId))
    }

    @Test fun squadAllyDeath_freesSlotAndRespawnsAfterFifteenSeconds() {
        director(10)
        enter(P1, BOSS)
        val ally = port.spawned[0].botId
        d.onBotDeath(ally, 1000)
        assertEquals(1, slots.used())
        assertTrue(ally in port.removed)
        assertNull(d.sessionOfBot(ally))
        d.tick(15_999)
        assertEquals(2, port.spawned.size)
        d.tick(16_000)
        assertEquals(3, port.spawned.size)
        assertEquals(2, slots.used())
    }

    @Test fun squadSharesQueueWithTrainers() {
        director(2)
        enter(P1, BOSS)           // 2 allies take both slots
        enter(P2, ARENA)
        assertEquals(listOf(BUSY), port.actionBars[P2])
        d.onBotDeath(port.spawned[0].botId, 1000)   // slot freed -> queued P2 trainer
        d.tick(1000)
        assertEquals(P2, port.lastSpawnNear)
    }

    @Test fun sameAreaChange_isNoOp() {
        director(10)
        enter(P1, ARENA)
        val t = d.trainerFor(P1)
        d.onAreaChange(P1, ARENA, ARENA.copy(), 10)
        assertEquals(1, port.spawned.size)
        assertTrue(port.removed.isEmpty())
        assertTrue(t === d.trainerFor(P1))
    }

    @Test fun sameRegionNewKit_keepsSession_nextSpawnUsesNewArea() {
        director(10)
        val kitA = ARENA.copy(arena = "a1", kit = "iron")
        val kitB = kitA.copy(kit = "diamond")
        enter(P1, kitA); d.tick(3000)
        val t = d.trainerFor(P1)
        d.onAreaChange(P1, kitA, kitB, 3500)
        assertTrue(t === d.trainerFor(P1))
        assertTrue(port.removed.isEmpty())
        assertTrue(port.roundEnds.isEmpty())
        d.onBotDeath(port.spawned[0].botId, 4000)
        d.tick(9000)    // rematch delay 5 s
        assertEquals(2, port.spawned.size)
        assertEquals("diamond", port.spawned[1].area.kit)
        // A later leave reports the new area as `from` and still ends the session.
        d.onAreaChange(P1, kitB, null, 9500)
        assertNull(d.trainerFor(P1))
    }

    @Test fun quit_endsTrainer() {
        director(10)
        enter(P1, ARENA)
        d.onQuit(P1, 100)
        assertNull(d.trainerFor(P1))
        assertEquals(1, port.removed.size)
        assertEquals(0, slots.used())
    }

    @Test fun quit_leavesSquadAndEndsAfterGrace() {
        director(10)
        enter(P1, BOSS)
        d.onQuit(P1, 100)
        d.tick(10_099)
        assertEquals(2, port.alive.size)
        d.tick(10_100)
        assertTrue(port.alive.isEmpty())
        assertEquals(0, slots.used())
        assertNull(d.squadAt(W, "boss"))
    }

    @Test fun secondPlayerJoinsExistingSquad() {
        director(10)
        enter(P1, BOSS); enter(P2, BOSS)
        assertEquals(2, port.spawned.size)
        d.onAreaChange(P1, BOSS, null, 10)
        d.tick(20_000)
        assertEquals(2, port.alive.size)   // P2 still inside
    }

    @Test fun playerDeath_endsRoundWithTitle() {
        director(10)
        enter(P1, ARENA); d.tick(3000)
        d.onPlayerDeath(P1, 4000)
        assertEquals(listOf("Trainer won"), port.titles[P1])
        assertEquals(0, slots.used())
        assertEquals(1, port.removed.size)
    }

    @Test fun roundEnded_forwardedToPort() {
        director(10)
        enter(P1, ARENA); d.tick(3000)
        d.onPlayerDeath(P1, 4000)
        assertEquals(listOf(P1 to pvpbots.skill.Outcome.BOT_WON), port.roundEnds)
    }

    @Test fun trainerBotDeath_endsRoundAndRematchSpawnsAgain() {
        director(10)
        enter(P1, ARENA); d.tick(3000)
        val bot = port.spawned[0].botId
        d.onBotDeath(bot, 4000)
        assertEquals(listOf("You won!"), port.titles[P1])
        assertEquals(0, slots.used())
        assertEquals(1, port.removed.count { it == bot })
        d.tick(9000)    // rematch delay 5 s
        assertEquals(2, port.spawned.size)
        assertEquals(1, slots.used())
    }

    @Test fun unknownBotDeath_isNoOp() {
        director(10)
        d.onBotDeath(BOTX, 0)
        assertTrue(port.removed.isEmpty())
    }

    @Test fun fighting_trueForAlliesAndFightingTrainers() {
        director(10)
        enter(P1, ARENA)
        val tb = port.spawned[0].botId
        assertFalse(d.fighting(tb))      // countdown
        d.tick(3000)
        assertTrue(d.fighting(tb))
        enter(P2, BOSS)
        assertTrue(d.fighting(port.spawned[1].botId))
        assertFalse(d.fighting(BOTX))
    }

    @Test fun unknownBotDeath_doesNotReleaseASlot() {
        director(1)
        enter(P1, ARENA)
        assertEquals(1, slots.used())
        d.onBotDeath(BOTX, 0)
        assertEquals(1, slots.used())
    }

    @Test fun manyQueuedPlayers_chainDoesNotOverflow() {
        director(1)
        val ps = (1..3000).map { UUID(0, it.toLong() + 1000) }
        ps.forEach { enter(it, ARENA) }
        assertEquals(1, port.spawned.size)
        ps.forEach { d.onAreaChange(it, ARENA, null, 1) }
        assertEquals(0, slots.used())
        assertEquals(0, slots.queued())
    }

    @Test fun sessionCounts_trackTrainersAndSquads() {
        director(10)
        enter(P1, ARENA)
        enter(P2, ARENA)
        enter(P3, BOSS)
        assertEquals(2, d.trainerCount())
        assertEquals(1, d.squadCount())
        d.onAreaChange(P1, ARENA, null, 1)
        assertEquals(1, d.trainerCount())
        d.shutdown()
        assertEquals(0, d.trainerCount())
        assertEquals(0, d.squadCount())
    }
}
