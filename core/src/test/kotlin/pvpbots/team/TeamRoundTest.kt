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
        assertTrue(Effect.CancelQueuedSlots(KEY) in fx)
        assertTrue(fx.none { it is Effect.CancelSlotRequests })
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
        assertTrue(Effect.CancelQueuedSlots(KEY) in fx)
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

    @Test fun deadThenLeaves_keepsTeamResult() {
        val (r, bots) = fighting()
        r.playerDied(p2, 4000)
        r.playerLeft(p2, 4100)
        val fx = bots.getValue(Side.B).flatMap { r.botDied(it, 5000) }
        assertTrue(Effect.TeamResult(p2, Outcome.BOT_WON) in fx)
        assertTrue(Effect.Title(p2, Messages.TEAM_LOST) in fx)
        assertTrue(Effect.TeamResult(p1, Outcome.PLAYER_WON) in fx)
    }

    @Test fun overflowPlayers_waitAtStart() {
        val r = round(seats = 1)
        val ps = (1..3).map { id(it) }
        val fx = r.start(ps, ps.associateWith { 50.0 }, 0)
        assertTrue(Effect.Wait(ps[2]) in fx)
        assertEquals(COUNTDOWN, r.state())
    }

    // ---- fillers lost before the fight (final review I1/I2) ----

    /** seats 3, 2 players: 2 fillers per side; grants [n] slots and spawns the first [spawned] of them. */
    private fun waiting(r: TeamRound, n: Int, spawned: Int): List<Pair<Side, UUID>> {
        r.start(listOf(p1, p2), ratings, 0)
        val spawns = (1..n).flatMap { r.slotGranted(0) }.filterIsInstance<Effect.SpawnTeamBot>()
        return spawns.take(spawned).map { it.side to id(nextBot++) }.onEach { (side, bot) -> r.botSpawned(side, bot, 0) }
    }

    @Test fun fillerKilledWhileWaiting_isReplaced() {
        val r = round(3)
        val bots = waiting(r, 3, 3)
        val dead = bots[0].second
        assertEquals(listOf<Effect>(Effect.RequestSlots(KEY, 1)), r.botDied(dead, 1000))
        val fourth = r.slotGranted(1000).filterIsInstance<Effect.SpawnTeamBot>().single()
        r.botSpawned(fourth.side, id(nextBot++), 1000)
        assertEquals(WAITING_SLOTS, r.state())
        val replacement = r.slotGranted(1000).filterIsInstance<Effect.SpawnTeamBot>().single()
        val fx = r.botSpawned(replacement.side, id(nextBot++), 1000)
        assertTrue(Effect.PlacePlayer(p1, Side.A, 0) in fx)
        assertEquals(COUNTDOWN, r.state())
    }

    @Test fun fillerKilledWhileWaiting_neverStuck() {
        val r = round(3)
        val bots = waiting(r, 3, 3)
        r.botDied(bots[0].second, 1000)
        val fourth = r.slotGranted(1000).filterIsInstance<Effect.SpawnTeamBot>().single()
        r.botSpawned(fourth.side, id(nextBot++), 1000)
        r.tick(40_000)
        assertTrue(r.state() == COUNTDOWN || r.slotGranted(40_000).isNotEmpty())
    }

    @Test fun fillerKilledDuringCountdown_backToWaiting_neverStartsUneven() {
        val r = round(3)
        val bots = waiting(r, 4, 4)
        assertEquals(COUNTDOWN, r.state())
        val (side, dead) = bots[1]
        assertEquals(listOf<Effect>(Effect.RequestSlots(KEY, 1)), r.botDied(dead, 1000))
        assertEquals(WAITING_SLOTS, r.state())
        r.tick(3000)
        assertEquals(WAITING_SLOTS, r.state())
        val replacement = r.slotGranted(3000).filterIsInstance<Effect.SpawnTeamBot>().single()
        assertEquals(side, replacement.side)
        val fx = r.botSpawned(side, id(nextBot++), 3000)
        assertTrue(Effect.PlacePlayer(p2, Side.B, 0) in fx)
        assertEquals(COUNTDOWN, r.state())
        r.tick(6000)
        assertEquals(FIGHTING, r.state())
        assertEquals(3, r.foesOf(Side.A).size) // p2 + two B bots
    }

    @Test fun shrink_keepsGrantedPendingSpawns() {
        val r = round(3)
        val spawns = run {
            r.start(listOf(p1, p2), ratings, 0)
            (1..4).flatMap { r.slotGranted(0) }.filterIsInstance<Effect.SpawnTeamBot>()
        }
        spawns.take(3).forEach { r.botSpawned(it.side, id(nextBot++), 0) }
        val fx = r.tick(TeamRound.SHRINK_TIMEOUT_MS)
        assertTrue(fx.none { it is Effect.CancelSlotRequests })
        assertEquals(WAITING_SLOTS, r.state())
        r.botSpawned(spawns[3].side, id(nextBot++), 10_100)
        assertEquals(COUNTDOWN, r.state())
    }

    @Test fun failedFillerAfterShrink_shrinksAgain() {
        val r = round(3)
        r.start(listOf(p1, p2), ratings, 0)
        val spawns = (1..3).flatMap { r.slotGranted(0) }.filterIsInstance<Effect.SpawnTeamBot>()
        assertEquals(listOf(Side.A, Side.B, Side.A), spawns.map { it.side })
        r.tick(10_000) // shrinks to 2v2: one filler per side
        r.spawnFailed(Side.B, 10_500) // the only B filler fails; its re-request never gets a slot
        r.botSpawned(Side.A, id(nextBot++), 10_600)
        assertEquals(WAITING_SLOTS, r.state())
        assertTrue(r.tick(19_999).none { it is Effect.PlacePlayer })
        val fx = r.tick(20_600)
        assertEquals(COUNTDOWN, r.state()) // 1v1, players only
        assertTrue(Effect.PlacePlayer(p1, Side.A, 0) in fx && Effect.PlacePlayer(p2, Side.B, 0) in fx)
        assertTrue(fx.any { it is Effect.RemoveBot })
    }

    @Test fun surplusGrantAfterShrink_thenFillerDiesInCountdown_isReplaced() {
        val r = round(3)
        r.start(listOf(p1, p2), ratings, 0)
        val spawns = (1..3).flatMap { r.slotGranted(0) }.filterIsInstance<Effect.SpawnTeamBot>()
        assertEquals(listOf(Side.A, Side.B, Side.A), spawns.map { it.side })
        r.tick(10_000) // 2v2: the second A grant is surplus (its spawn is dropped by the director once the countdown runs)
        val a = id(nextBot++)
        r.botSpawned(Side.A, a, 10_100)
        r.botSpawned(Side.B, id(nextBot++), 10_100)
        assertEquals(COUNTDOWN, r.state())
        assertEquals(listOf<Effect>(Effect.RequestSlots(KEY, 1)), r.botDied(a, 11_000))
        val replacement = r.slotGranted(11_000).filterIsInstance<Effect.SpawnTeamBot>().single()
        assertEquals(Side.A, replacement.side)
        r.botSpawned(Side.A, id(nextBot++), 11_100)
        assertEquals(COUNTDOWN, r.state())
    }
}
