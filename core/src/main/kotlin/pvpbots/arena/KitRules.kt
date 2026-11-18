package pvpbots.arena

/** What to do with a player's inventory after an area change (§5.6). */
enum class Action { STASH_AND_GIVE, RESTORE, RESTORE_THEN_STASH_AND_GIVE, NONE }

/**
 * Kit-arena inventory rules (pure). A stash on disk is always restored before a new one is made, so a stash is never
 * overwritten; the caller must not stash when that restore fails. Dead players are never touched: the respawn resets
 * their inventory, so the respawn hook restores instead. Creative and spectator players get no kit (no trainer either),
 * but a stash they still have is restored.
 *
 * [wasInKitArena] does not change the answer today (a kit arena to another kit arena is a restore then a fresh stash,
 * the same as entering with any leftover stash); it is part of the contract so callers always report it.
 */
object KitRules {
    private val KIT_MODES = setOf("SURVIVAL", "ADVENTURE")

    @Suppress("UNUSED_PARAMETER")
    fun action(inKitArena: Boolean, wasInKitArena: Boolean, gameMode: String, hasStash: Boolean, dead: Boolean): Action {
        if (dead) return Action.NONE
        val getsKit = inKitArena && gameMode.uppercase() in KIT_MODES
        return when {
            getsKit && hasStash -> Action.RESTORE_THEN_STASH_AND_GIVE
            getsKit -> Action.STASH_AND_GIVE
            hasStash -> Action.RESTORE
            else -> Action.NONE
        }
    }
}
