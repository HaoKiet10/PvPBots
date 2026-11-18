package pvpbots.skill

import kotlin.test.Test
import kotlin.test.assertEquals

class RatingUpdateTest {
    private val s = SkillSettings()

    @Test fun playerWon() = assertEquals(57.0, RatingUpdate.after(50.0, 60.0, Outcome.PLAYER_WON, s))
    @Test fun botWon() = assertEquals(45.0, RatingUpdate.after(50.0, 40.0, Outcome.BOT_WON, s))
    @Test fun draw() = assertEquals(53.0, RatingUpdate.after(50.0, 60.0, Outcome.DRAW, s))
    @Test fun noResult_unchanged() = assertEquals(50.0, RatingUpdate.after(50.0, 90.0, Outcome.NO_RESULT, s))
    @Test fun clampedHigh() = assertEquals(100.0, RatingUpdate.after(99.0, 100.0, Outcome.PLAYER_WON, s))
    @Test fun clampedLow() = assertEquals(0.0, RatingUpdate.after(1.0, 0.0, Outcome.BOT_WON, s))
}
