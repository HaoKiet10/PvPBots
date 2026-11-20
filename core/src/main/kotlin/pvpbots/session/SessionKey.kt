package pvpbots.session

sealed interface SessionKey {
    data class Trainer(val owner: java.util.UUID) : SessionKey
    data class Squad(val world: String, val regionId: String) : SessionKey
    data class Team(val world: String, val regionId: String) : SessionKey

    /** The boss of the boss arena named [arena]; owned by the boss director, never by [AreaDirector]. */
    data class Boss(val arena: String) : SessionKey
}
