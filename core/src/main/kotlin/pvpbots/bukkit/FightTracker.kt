package pvpbots.bukkit

import pvpbots.skill.Window
import java.util.UUID

/**
 * What one adaptive fight has measured so far: the bot's in-fight rating ([current], moved by the FightJudge) and the
 * damage traded since the last adjustment window. Main thread only; one per owner, kept until the round ends.
 */
class FightTracker(val owner: UUID, val botId: UUID, val start: Double) {
    var current: Double = start

    /** Damage the owner dealt to their own trainer since the last window. */
    var dealtToBot = 0.0
        private set

    /** All damage the owner took, from any source, since the last window. */
    var takenByPlayer = 0.0
        private set

    /** Fighting ticks since the last window. */
    var windowTicks = 0
        private set

    fun dealt(amount: Double) { dealtToBot += amount }

    fun taken(amount: Double) { takenByPlayer += amount }

    /** Counts one fighting tick; true once [every] ticks have passed since the last window. */
    fun tickFighting(every: Int): Boolean = ++windowTicks >= every

    /** The finished window with the counters reset for the next one. */
    fun closeWindow(playerHealthFrac: Double, botHealthFrac: Double): Window {
        val w = Window(playerHealthFrac, botHealthFrac, dealtToBot, takenByPlayer)
        resetWindow()
        return w
    }

    fun resetWindow() {
        dealtToBot = 0.0
        takenByPlayer = 0.0
        windowTicks = 0
    }
}
