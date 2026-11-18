package pvpbots.bukkit

import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import pvpbots.math.BlockPos
import pvpbots.math.Bounds
import pvpbots.profile.Difficulty
import pvpbots.session.AreaRef
import pvpbots.session.Role
import pvpbots.session.SessionKey
import pvpbots.skill.Outcome
import pvpbots.skill.RatingUpdate
import pvpbots.skill.SkillBlender
import kotlin.math.abs

/**
 * Adaptive-skill self-test checks (S1), run by [SelfTest] in [SelfTest.Mode.All]. The owners are dummies (real
 * server-side `Player`s outside the player list), so the region callback is called directly the way RegionWatcher calls it.
 */
internal class SkillSelfTest(private val t: SelfTest, private val plugin: PvpBotsPlugin, private val world: World) {
    private val manager get() = plugin.botManager
    private val pdcKey = NamespacedKey("pvpbots", "skill")

    suspend fun SequenceScope<Int>.run() {
        adaptive()
        pinned()
        leaveAndStorage()
    }

    private fun area(id: String, cx: Int, difficulty: Difficulty?): AreaRef {
        val y = world.getHighestBlockYAt(cx, 0) + 1
        return AreaRef(world.name, id, Role.TRAINER, difficulty, 1, Bounds(BlockPos(cx - 14, y - 4, -14), BlockPos(cx + 14, y + 12, 14)))
    }

    private fun stored(p: Player): Double? = p.persistentDataContainer.get(pdcKey, PersistentDataType.DOUBLE)

    /** S1 adaptive rating: the player is clearly ahead at the first adjustment, then the bot dies; the stored rating follows the formula. */
    private suspend fun SequenceScope<Int>.adaptive() {
        val s = manager.config.settings.skill
        val anchors = manager.config.profiles
        val arena = area("selftest_s1_adaptive", 1500, null)
        val owner = t.spawnDummy("Skill", 1500, 0)
        yield(5)
        val key = SessionKey.Trainer(owner.id)
        plugin.director.onAreaChange(owner.id, null, arena, t.nowMs())
        val spawned = waitUntil(60) { t.trainerBot(key) != null }
        val bot = t.trainerBot(key)
        val tracker = manager.tracker(owner.id)
        val expectedStart = SkillBlender.startProfile(s.startRating, anchors, null)
        val startOk = bot?.controller?.profile == expectedStart
        val fighting = bot != null && waitUntil(200) { plugin.director.fighting(bot.id) } >= 0
        if (bot == null || tracker == null || !fighting) {
            t.check("S1 adaptive rating", false, "no fight (spawned $spawned, bot ${bot != null}, tracker ${tracker != null}, fighting $fighting)")
        } else {
            bot.player.health = 2.0 // bot at 10%, player full: lead far beyond the dead zone
            val adjusted = waitUntil(s.adjustEverySeconds * 20 + 40) { tracker.current != s.startRating }
            val current = tracker.current
            val retuned = bot.controller.profile == SkillBlender.retune(expectedStart, current, anchors) && bot.controller.profile != expectedStart
            val expected = RatingUpdate.after(s.startRating, current, Outcome.PLAYER_WON, s)
            bot.player.health = 0.0
            val ended = waitUntil(40) { manager.tracker(owner.id) == null }
            val after = stored(owner.bukkit)
            t.check(
                "S1 adaptive rating",
                spawned >= 0 && startOk && adjusted >= 0 && current == s.startRating + s.step && retuned && ended >= 0 &&
                    after != null && abs(after - expected) < 1e-9,
                "start profile for ${s.startRating} as blended: $startOk; first adjustment after $adjusted ticks: ${s.startRating} -> $current " +
                    "(expected ${s.startRating + s.step}); profile retuned to the blend: $retuned; round ended after $ended ticks; " +
                    "stored rating $after, expected $expected",
            )
        }
        plugin.director.onAreaChange(owner.id, arena, null, t.nowMs())
        t.removeDummy(owner)
    }

    /** S1 pinned arena: the same fight with `pvpbot-difficulty` set measures nothing and leaves the stored rating alone. */
    private suspend fun SequenceScope<Int>.pinned() {
        val s = manager.config.settings.skill
        val arena = area("selftest_s1_pinned", 1560, Difficulty.HARD)
        val owner = t.spawnDummy("Pinned", 1560, 0)
        yield(5)
        val key = SessionKey.Trainer(owner.id)
        plugin.director.onAreaChange(owner.id, null, arena, t.nowMs())
        waitUntil(60) { t.trainerBot(key) != null }
        val bot = t.trainerBot(key)
        val fighting = bot != null && waitUntil(200) { plugin.director.fighting(bot.id) } >= 0
        if (bot == null || !fighting) {
            t.check("S1 pinned arena", false, "no fight (bot ${bot != null}, fighting $fighting)")
        } else {
            val trackerDuringFight = manager.tracker(owner.id)
            bot.player.health = 2.0
            yield(s.adjustEverySeconds * 20 + 20) // an adaptive fight would have adjusted by now
            val hard = bot.controller.profile == manager.config.profiles.getValue(Difficulty.HARD)
            bot.player.health = 0.0
            val ended = waitUntil(40) { !manager.isBot(bot.id) }
            yield(3)
            val after = stored(owner.bukkit)
            t.check(
                "S1 pinned arena",
                trackerDuringFight == null && hard && ended >= 0 && after == null,
                "tracker during the fight ${trackerDuringFight != null}, profile still the hard one $hard, bot gone after $ended ticks, stored rating $after",
            )
        }
        plugin.director.onAreaChange(owner.id, arena, null, t.nowMs())
        t.removeDummy(owner)
    }

    /** S1 leave and storage: leaving mid-fight changes nothing and leaks no tracker; bad stored values read safely. */
    private suspend fun SequenceScope<Int>.leaveAndStorage() {
        val s = manager.config.settings.skill
        val arena = area("selftest_s1_leave", 1620, null)
        val owner = t.spawnDummy("Leaver", 1620, 0)
        yield(5)
        val key = SessionKey.Trainer(owner.id)
        plugin.director.onAreaChange(owner.id, null, arena, t.nowMs())
        waitUntil(60) { t.trainerBot(key) != null }
        val bot = t.trainerBot(key)
        val fighting = bot != null && waitUntil(200) { plugin.director.fighting(bot.id) } >= 0
        val trackedWhileFighting = manager.tracker(owner.id) != null
        plugin.director.onAreaChange(owner.id, arena, null, t.nowMs())
        yield(2)
        val leaked = manager.tracker(owner.id) != null
        val unchanged = stored(owner.bukkit) == null
        val pdc = owner.bukkit.persistentDataContainer
        pdc.set(pdcKey, PersistentDataType.STRING, "not a number")
        val wrongType = manager.skillStore.get(owner.bukkit)
        pdc.set(pdcKey, PersistentDataType.DOUBLE, 250.0)
        val clamped = manager.skillStore.get(owner.bukkit)
        pdc.remove(pdcKey)
        t.check(
            "S1 leave and storage",
            fighting && trackedWhileFighting && !leaked && unchanged && wrongType == s.startRating && clamped == 100.0,
            "fighting $fighting, tracker while fighting $trackedWhileFighting, tracker left over after leaving $leaked, rating unchanged $unchanged; " +
                "a wrongly typed value reads ${wrongType} (start ${s.startRating}), a stored 250 reads $clamped",
        )
        t.removeDummy(owner)
    }
}
