package pvpbots.session

sealed interface SessionKey {
    data class Trainer(val owner: java.util.UUID) : SessionKey
    data class Squad(val world: String, val regionId: String) : SessionKey
}
