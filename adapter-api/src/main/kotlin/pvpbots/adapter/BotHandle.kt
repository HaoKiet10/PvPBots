package pvpbots.adapter

import java.util.UUID

interface BotHandle {
    val id: UUID
    val bukkit: org.bukkit.entity.Player
}
