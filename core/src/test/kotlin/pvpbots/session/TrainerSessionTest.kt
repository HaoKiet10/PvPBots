package pvpbots.session

import pvpbots.math.BlockPos
import pvpbots.math.Bounds
import pvpbots.profile.Difficulty
import pvpbots.skill.Outcome
import pvpbots.session.TrainerSession.State.*
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrainerSessionTest {
    private val OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val BOT = UUID.fromString("00000000-0000-0000-0000-0000000000b0")
    private val KEY = SessionKey.Trainer(OWNER)
    private val area = AreaRef("world", "arena", Role.TRAINER, Difficulty.HARD, 1,
        Bounds(BlockPos(0, 0, 0), BlockPos(10, 10, 10)))

    private fun trainer() = TrainerSession(OWNER, area, Timings(3, 300, 5, 15, 10))

    /** Fighting from 3_000. */
    private fun fighting(): TrainerSession {
        val s = trainer(); s.start(0); s.slotGranted(0); s.botSpawned(BOT, 0); s.tick(3000); return s
    }

    @Test fun fullRound_ownerWins() {
        val s = trainer(); s.start(0); s.slotGranted(0); s.botSpawned(BOT, 0); s.tick(3000)
        assertEquals(FIGHTING, s.state())
        assertEquals(listOf(Effect.RemoveBot(BOT), Effect.Title(OWNER, Messages.YOU_WON), Effect.RoundEnded(OWNER, Outcome.PLAYER_WON)), s.botDied(10_000))
        assertEquals(REMATCH_WAIT, s.state())
        assertNull(s.botId())
    }

    @Test fun rematchAfterDelay() {
        val s = fighting(); s.botDied(10_000)
        assertTrue(s.tick(14_999).isEmpty())
        assertTrue(s.tick(15_000).contains(Effect.RequestSlots(KEY, 1)))
        assertEquals(QUEUED, s.state())
    }

    @Test fun timeLimitDraw() {
        val s = fighting()
        assertTrue(s.tick(302_999).isEmpty())
        assertEquals(listOf(Effect.RemoveBot(BOT), Effect.Title(OWNER, Messages.DRAW), Effect.RoundEnded(OWNER, Outcome.DRAW)), s.tick(303_000))
        assertEquals(REMATCH_WAIT, s.state())
    }

    @Test fun queuedShowsBusyMessage() {
        assertEquals(listOf(Effect.ActionBar(OWNER, Messages.BUSY)), trainer().queued())
    }

    @Test fun ownerLeftWhileQueued_cancelsSlotRequest() {
        val s = trainer(); s.start(0)
        assertEquals(listOf(Effect.CancelSlotRequests(KEY)), s.ownerLeft())
        assertEquals(ENDED, s.state())
    }

    @Test fun ownerDiedDuringCountdown_removesBot() {
        val s = trainer(); s.start(0); s.slotGranted(0); s.botSpawned(BOT, 0)
        assertEquals(listOf(Effect.RemoveBot(BOT), Effect.RoundEnded(OWNER, Outcome.NO_RESULT)), s.ownerDied(1000))
        assertEquals(REMATCH_WAIT, s.state())
    }

    @Test fun endedIgnoresLateEvents() {
        val s = trainer(); s.start(0); s.ownerLeft()
        assertTrue(s.slotGranted(5).isEmpty())
        assertEquals(listOf(Effect.RemoveBot(BOT)), s.botSpawned(BOT, 6))
        assertTrue(s.tick(10_000_000).isEmpty())
        assertEquals(ENDED, s.state())
        assertNull(s.botId())
    }

    @Test fun startRequestsOneSlot() {
        val s = trainer()
        assertEquals(listOf(Effect.RequestSlots(KEY, 1)), s.start(0))
        assertEquals(QUEUED, s.state())
        assertEquals(KEY, s.key)
    }

    @Test fun slotGrantedSpawnsTrainerNearOwner_stateStaysQueued() {
        val s = trainer(); s.start(0)
        assertEquals(listOf(Effect.SpawnBot(KEY, Role.TRAINER, Difficulty.HARD, OWNER)), s.slotGranted(0))
        assertEquals(QUEUED, s.state())
    }

    @Test fun botSpawnedStartsCountdown() {
        val s = trainer(); s.start(0); s.slotGranted(0)
        assertTrue(s.botSpawned(BOT, 100).isEmpty())
        assertEquals(COUNTDOWN, s.state())
        assertEquals(BOT, s.botId())
        assertFalse(s.fighting())
        assertTrue(s.tick(3099).isEmpty())
        assertEquals(COUNTDOWN, s.state())
        assertTrue(s.tick(3100).isEmpty())
        assertEquals(FIGHTING, s.state())
        assertTrue(s.fighting())
    }

    @Test fun slotGrantedInCountdownIgnored() {
        val s = trainer(); s.start(0); s.slotGranted(0); s.botSpawned(BOT, 0)
        assertTrue(s.slotGranted(1).isEmpty())
    }

    @Test fun ownerDiedWhileFighting_trainerWon() {
        val s = fighting()
        assertEquals(listOf(Effect.RemoveBot(BOT), Effect.Title(OWNER, Messages.TRAINER_WON), Effect.RoundEnded(OWNER, Outcome.BOT_WON)), s.ownerDied(5000))
        assertEquals(REMATCH_WAIT, s.state())
    }

    @Test fun botDiedDuringCountdown_removesBotNoTitle() {
        val s = trainer(); s.start(0); s.slotGranted(0); s.botSpawned(BOT, 0)
        assertEquals(listOf(Effect.RemoveBot(BOT), Effect.RoundEnded(OWNER, Outcome.NO_RESULT)), s.botDied(1000))
        assertEquals(REMATCH_WAIT, s.state())
    }

    @Test fun ownerLeftWithBot_cancelsThenRemoves() {
        val s = fighting()
        assertEquals(listOf(Effect.CancelSlotRequests(KEY), Effect.RemoveBot(BOT)), s.ownerLeft())
        assertEquals(ENDED, s.state())
        assertTrue(s.ownerLeft().isEmpty())
    }

    private val BOT2 = UUID.fromString("00000000-0000-0000-0000-0000000000b2")

    @Test fun duplicateSlotGranted_secondEmpty() {
        val s = trainer(); s.start(0)
        assertEquals(1, s.slotGranted(0).size)
        assertTrue(s.slotGranted(1).isEmpty())
    }

    @Test fun botSpawnedAfterOwnerLeft_removed() {
        val s = trainer(); s.start(0); s.slotGranted(0); s.ownerLeft()
        assertEquals(listOf(Effect.RemoveBot(BOT)), s.botSpawned(BOT, 5))
        assertEquals(ENDED, s.state())
        assertNull(s.botId())
    }

    @Test fun secondBotSpawnedInCountdown_removed_originalKept() {
        val s = trainer(); s.start(0); s.slotGranted(0); s.botSpawned(BOT, 0)
        assertEquals(listOf(Effect.RemoveBot(BOT2)), s.botSpawned(BOT2, 1))
        assertEquals(BOT, s.botId())
        assertEquals(COUNTDOWN, s.state())
    }

    @Test fun botSpawnedWithoutPendingSpawn_removed() {
        val s = trainer(); s.start(0)
        assertEquals(listOf(Effect.RemoveBot(BOT)), s.botSpawned(BOT, 0))
        assertEquals(QUEUED, s.state())
    }

    @Test fun startTwice_secondEmpty() {
        val s = trainer()
        assertEquals(1, s.start(0).size)
        assertTrue(s.start(1).isEmpty())
        assertEquals(QUEUED, s.state())
    }

    @Test fun rematchAllowsNewSpawn() {
        val s = fighting(); s.botDied(10_000); s.tick(15_000)
        assertEquals(1, s.slotGranted(15_000).size)
    }

    @Test fun messagesAreExact() {
        assertEquals("All trainers busy, please wait…", Messages.BUSY)
        assertEquals("You won!", Messages.YOU_WON)
        assertEquals("Trainer won", Messages.TRAINER_WON)
        assertEquals("Time's up — draw", Messages.DRAW)
    }

    @Test fun spawnFailed_staysQueuedAndAllowsNewGrant() {
        val s = trainer(); s.start(0); s.slotGranted(0)
        assertTrue(s.spawnFailed(10).isEmpty())
        assertEquals(QUEUED, s.state())
        assertEquals(1, s.slotGranted(5_000).size)
    }

    @Test fun botDied_emitsRoundEndedPlayerWon() {
        assertTrue(Effect.RoundEnded(OWNER, Outcome.PLAYER_WON) in fighting().botDied(10_000))
    }

    @Test fun ownerDiedWhileFighting_emitsBotWon() {
        assertTrue(Effect.RoundEnded(OWNER, Outcome.BOT_WON) in fighting().ownerDied(10_000))
    }

    @Test fun timeLimit_emitsDraw() {
        assertTrue(Effect.RoundEnded(OWNER, Outcome.DRAW) in fighting().tick(303_000))
    }

    @Test fun ownerDiedInCountdown_emitsNoResult() {
        val s = trainer(); s.start(0); s.slotGranted(0); s.botSpawned(BOT, 0)
        assertTrue(Effect.RoundEnded(OWNER, Outcome.NO_RESULT) in s.ownerDied(1000))
    }

    @Test fun botFailed_emitsNoResult() {
        assertTrue(Effect.RoundEnded(OWNER, Outcome.NO_RESULT) in fighting().botFailed(10_000))
    }

    @Test fun ownerLeft_emitsNoRoundEnded() {
        assertTrue(fighting().ownerLeft().none { it is Effect.RoundEnded })
    }

    @Test fun adaptiveArea_spawnsWithNullDifficulty() {
        val s = TrainerSession(OWNER, area.copy(difficulty = null), Timings(3, 300, 5, 15, 10))
        s.start(0)
        assertEquals(listOf(Effect.SpawnBot(KEY, Role.TRAINER, null, OWNER)), s.slotGranted(0))
    }
}
