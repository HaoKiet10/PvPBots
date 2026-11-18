package pvpbots.skill

/** How a trainer round ended. NO_RESULT: it never really started (countdown) or the bot failed. */
enum class Outcome { PLAYER_WON, BOT_WON, DRAW, NO_RESULT }
