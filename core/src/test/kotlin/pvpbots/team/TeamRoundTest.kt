package pvpbots.team

import pvpbots.math.BlockPos
import pvpbots.math.Bounds
import pvpbots.session.AreaRef
import pvpbots.session.Effect
import pvpbots.session.Messages
import pvpbots.session.Role
import pvpbots.session.SessionKey
import pvpbots.session.Timings
import pvpbots.skill.Outcome
import pvpbots.team.TeamRound.State.*
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TeamRoundTest {
    private fun id(n: Int) = UUID.fromString("00000000-0000-0000-0000-0000000000%02x".format(n))
    private val p1 = id(1)
    private val p2 = id(2)
    private val KEY = SessionKey.Team("world", "arena")
    private val area = AreaRef("world", "arena", Role.TEAM, null, 5,
        Bounds(BlockPos(0, 0, 0), BlockPos(10, 10, 10)), seats = 5)
    private val ratings = mapOf(p1 to 80.0, p2 to 40.0)

    private fun round(seats: Int = 5) = TeamRound(KEY, area.copy(seats = seats), Timings(3, 300, 5, 15, 10))

    private var nextBot = 100

    /** 2 players (A=p1, B=p2), seats 5, all 8 fillers spawned; countdown from 0. Returns bots by side. */
    private fun counting(r: TeamRound): Map<Side, List<UUID>> {
        r.start(listOf(p1, p2), ratings, 0)
        val spawns = (1..8).flatMap { r.slotGranted(0) }.filterIsInstance<Effect.SpawnTeamBot>()
        return spawns.map { it.side to id(nextBot++) }
            .onEach { (side, bot) -> r.botSpawned(side, bot, 0) }
            .groupBy({ it.first }, { it.second })
    }

    private fun fighting(): Pair<TeamRound, Map<Side, List<UUID>>> {
        val r = round()
        val bots = counting(r)
        r.tick(3000)
        return r to bots
    }

    @Test fun start_requestsSlotsForFillerBots_thenCountdown() {
        val r = round()
        assertEquals(listOf<Effect>(Effect.RequestSlots(KEY, 8)), r.start(listOf(p1, p2), ratings, 0))
        assertEquals(WAITING_SLOTS, r.state())
        val spawns = (1..8).flatMap { r.slotGranted(0) }
        assertEquals(8, spawns.size)
        val bots = spawns.filterIsInstance<Effect.SpawnTeamBot>()
        assertEquals(4, bots.count { it.side == Side.A })
        assertEquals(4, bots.count { it.side == Side.B })
        assertTrue(bots.filter { it.side == Side.A }.all { it.rating == 80.0 })
        assertTrue(bots.filter { it.side == Side.B }.all { it.rating == 40.0 })
        assertEquals(4, bots.filter { it.side == Side.A }.map { it.pad }.toSet().size)
        assertTrue(r.slotGranted(0).isEmpty())
        var placed = emptyList<Effect>()
        bots.forEachIndexed { i, b ->
            val fx = r.botSpawned(b.side, id(100 + i), 0)
            if (i < 7) assertTrue(fx.isEmpty()) else placed = fx
        }
        assertEquals(setOf<Effect>(Effect.PlacePlayer(p1, Side.A, 0), Effect.PlacePlayer(p2, Side.B, 0)), placed.toSet())
        assertEquals(COUNTDOWN, r.state())
        r.tick(3000)
        assertEquals(FIGHTING, r.state())
    }

    @Test fun slotsShort10s_shrinksToFit() {
        val r = round()
        r.start(listOf(p1, p2), ratings, 0)
        val spawns = (1..4).flatMap { r.slotGranted(0) }.filterIsInstance<Effect.SpawnTeamBot>()
        assertEquals(2, spawns.count { it.side == Side.A })
        assertTrue(r.tick(9_999).isEmpty())
        val fx = r.tick(TeamRound.SHRINK_TIMEOUT_MS)
        assertTrue(Effect.CancelSlotRequests(KEY) in fx)
        assertEquals(WAITING_SLOTS, r.state())
        spawns.forEachIndexed { i, b -> r.botSpawned(b.side, id(100 + i), 10_000) }
        assertEquals(COUNTDOWN, r.state())
        assertTrue(r.slotGranted(10_000).isEmpty())
    }

    @Test fun slotsShort_noFit_keepsWaiting() {
        val r = round()
        r.start(listOf(p1, p2, id(3)), ratings + (id(3) to 60.0), 0)
        assertTrue(r.tick(20_000).isEmpty())
        assertEquals(WAITING_SLOTS, r.state())
    }

    @Test fun evenSplitWithNoSlots_playersOnly() {
        val r = round()
        r.start(listOf(p1, p2), ratings, 0)
        val fx = r.tick(10_000)
        assertEquals(COUNTDOWN, r.state())
        assertTrue(Effect.CancelSlotRequests(KEY) in fx)
        assertTrue(Effect.PlacePlayer(p1, Side.A, 0) in fx)
    }

    @Test fun lastPlayerOfSideLeaves_otherSideWins() {
        val (r, bots) = fighting()
        bots.getValue(Side.B).forEach { r.botDied(it, 5000) }
        val fx = r.playerLeft(p2, 6000)
        assertTrue(fx.none { it is Effect.TeamResult && it.player == p2 })
        assertTrue(Effect.TeamResult(p1, Outcome.PLAYER_WON) in fx)
        assertTrue(Effect.Title(p1, Messages.TEAM_WON) in fx)
        assertTrue(fx.none { it is Effect.Title && it.player == p2 })
        bots.getValue(Side.A).forEach { assertTrue(Effect.RemoveBot(it) in fx) }
        assertEquals(REMATCH_WAIT, r.state())
    }

    @Test fun eliminationOfAllEnemies_wins() {
        val (r, bots) = fighting()
        r.playerDied(p2, 4000)
        bots.getValue(Side.B).dropLast(1).forEach { assertTrue(r.botDied(it, 5000).isEmpty()) }
        val fx = r.botDied(bots.getValue(Side.B).last(), 6000)
        assertEquals(listOf<Effect>(Effect.Title(p1, Messages.TEAM_WON), Effect.Title(p2, Messages.TEAM_LOST)),
            fx.filterIsInstance<Effect.Title>())
        assertTrue(Effect.TeamResult(p2, Outcome.BOT_WON) in fx)
        assertTrue(Effect.TeamResult(p1, Outcome.PLAYER_WON) in fx)
    }

    @Test fun timeLimit_draw() {
        val (r, _) = fighting()
        assertTrue(r.tick(302_999).isEmpty())
        val fx = r.tick(303_000)
        assertTrue(Effect.TeamResult(p1, Outcome.DRAW) in fx && Effect.TeamResult(p2, Outcome.DRAW) in fx)
        assertTrue(Effect.Title(p1, Messages.DRAW) in fx)
        assertEquals(REMATCH_WAIT, r.state())
    }

    @Test fun allPlayersLeave_roundEndsNoResult() {
        val (r, bots) = fighting()
        r.playerLeft(p1, 5000)
        val fx = r.playerLeft(p2, 6000)
        assertTrue(fx.none { it is Effect.TeamResult })
        (bots.getValue(Side.A) + bots.getValue(Side.B)).forEach { assertTrue(Effect.RemoveBot(it) in fx) }
        assertEquals(ENDED, r.state())
    }

    @Test fun midRoundEntrant_waits_thenSeatedNextRound() {
        val (r, bots) = fighting()
        val p3 = id(3)
        assertEquals(listOf<Effect>(Effect.Wait(p3)), r.playerEntered(p3, 10.0, 4000))
        bots.getValue(Side.B).forEach { r.botDied(it, 5000) }
        r.playerLeft(p2, 5000)
        assertEquals(REMATCH_WAIT, r.state())
        val fx = r.tick(10_000)
        assertEquals(setOf(p1, p3), r.seated().toSet())
        assertTrue(fx.any { it is Effect.RequestSlots })
        assertEquals(WAITING_SLOTS, r.state())
    }

    @Test fun deadPlayer_outForRound() {
        val (r, _) = fighting()
        r.playerDied(p2, 4000)
        assertEquals(listOf<Effect>(Effect.Wait(p2)), r.playerEntered(p2, 40.0, 5000))
    }

    @Test fun entrantDuringRematchWait_getsWait() {
        val (r, bots) = fighting()
        bots.getValue(Side.B).forEach { r.botDied(it, 5000) }
        r.playerDied(p2, 5000)
        assertEquals(REMATCH_WAIT, r.state())
        assertEquals(listOf<Effect>(Effect.Wait(id(3))), r.playerEntered(id(3), 10.0, 6000))
    }

    @Test fun allPlayersDieDuringCountdown_noResult() {
        val r = round()
        val bots = counting(r)
        r.playerDied(p1, 1000)
        val fx = r.playerDied(p2, 1500)
        assertTrue(fx.none { it is Effect.TeamResult })
        (bots.getValue(Side.A) + bots.getValue(Side.B)).forEach { assertTrue(Effect.RemoveBot(it) in fx) }
        assertEquals(ENDED, r.state())
    }

    @Test fun overflowPlayers_waitAtStart() {
        val r = round(seats = 1)
        val ps = (1..3).map { id(it) }
        val fx = r.start(ps, ps.associateWith { 50.0 }, 0)
        assertTrue(Effect.Wait(ps[2]) in fx)
        assertEquals(COUNTDOWN, r.state())
    }
}
