package pvpbots.adapter.v26_1

import com.google.common.collect.ImmutableMultimap
import com.mojang.authlib.GameProfile
import com.mojang.authlib.properties.Property
import com.mojang.authlib.properties.PropertyMap
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ClientInformation
import net.minecraft.server.level.ParticleStatus
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.network.CommonListenerCookie
import net.minecraft.server.network.ServerGamePacketListenerImpl
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.ChatVisiblity
import net.minecraft.world.entity.player.Input
import net.minecraft.world.entity.player.Player
import org.bukkit.Location
import org.bukkit.craftbukkit.CraftWorld
import pvpbots.adapter.BotInput
import pvpbots.adapter.BotSpec

/**
 * Fake server player driven by stored input (M0 spike technique, variant A): it lives in the level only and is
 * never added to the server's PlayerList, so there is no join/quit message, no playerdata load or save, and it is
 * not in `Bukkit.getOnlinePlayers()`.
 */
internal class FakeBot private constructor(
    server: MinecraftServer,
    level: ServerLevel,
    profile: GameProfile,
) : ServerPlayer(server, level, profile, BOT_CLIENT_INFO) {

    /** Latest input; re-applied every tick because vanilla decays xxa/zza. */
    var input: BotInput? = null

    /** Set once [removeBot] has run; makes removal idempotent. */
    var botRemoved = false
        private set

    override fun tick() {
        // ServerPlayer.tick(): server-side bookkeeping (game mode, containers, advancements); no physics.
        super.tick()
        if (isRemoved) return
        input?.let(::applyInput)
        // What ServerGamePacketListenerImpl.tickPlayer() does for a real player, minus the snap-back to a
        // client-reported position (the server is the authority): Player.tick() -> aiStep() -> travel().
        xo = x; yo = y; zo = z
        doTick()
    }

    private fun applyInput(i: BotInput) {
        yRot = i.yaw
        xRot = i.pitch
        yHeadRot = i.yaw
        zza = i.forward
        xxa = i.strafe // +1 = left (vanilla convention)
        setJumping(i.jump)
        isSprinting = i.sprint && i.forward > 0f
        isShiftKeyDown = i.sneak
        setLastClientInput(
            Input(i.forward > 0f, i.forward < 0f, i.strafe > 0f, i.strafe < 0f, i.jump, i.sneak, i.sprint),
        )
    }

    /** Removes the bot from its level and detaches advancement triggers. Safe to call more than once. */
    fun removeBot() {
        if (botRemoved) return
        botRemoved = true
        advancements.stopListening()
        if (!isRemoved) {
            level().removePlayerImmediately(this, Entity.RemovalReason.DISCARDED)
        }
    }

    companion object {
        /** View distance 2 (R10); all skin layers shown so custom skins render fully. */
        private val BOT_CLIENT_INFO = ClientInformation(
            "en_us", 2, ChatVisiblity.FULL, true, 0x7F, Player.DEFAULT_MAIN_HAND, false, false, ParticleStatus.MINIMAL,
        )

        fun profileOf(spec: BotSpec): GameProfile {
            val value = spec.skinValue
            val signature = spec.skinSignature
            if (value == null || signature == null) return GameProfile(spec.uuid, spec.name)
            val props = PropertyMap(ImmutableMultimap.of("textures", Property("textures", value, signature)))
            return GameProfile(spec.uuid, spec.name, props)
        }

        /** Creates the bot and adds it to the level at [at] (variant A). Main thread only. */
        fun spawn(spec: BotSpec, at: Location): FakeBot {
            val world = requireNotNull(at.world) { "Location has no world" }
            val server = MinecraftServer.getServer()
            val level = (world as CraftWorld).handle
            val profile = profileOf(spec)
            val bot = FakeBot(server, level, profile)
            // The constructor assigns bot.connection.
            ServerGamePacketListenerImpl(server, FakeConnection(), bot, CommonListenerCookie.createInitial(profile, false))
            // Required: until the client-loaded flag is set, ServerPlayer.isInvulnerableTo() returns true.
            bot.connection.handleAcceptPlayerLoad(ServerboundPlayerLoadedPacket())
            // Bots never earn advancements (no "has made the advancement" broadcasts, nothing to save).
            bot.advancements.stopListening()
            bot.absSnapTo(at.x, at.y, at.z, at.yaw, at.pitch)
            bot.yHeadRot = at.yaw
            // Paper: PlayerList.placeNewPlayer sets this; without it Moonrise's player chunk loader ignores the bot,
            // so chunks around it are never loaded/entity-ticking (mobs it fights freeze, keep hurt cooldowns) and
            // its per-player view/simulation distance is not applied. Only the chunk loader reads this flag.
            bot.isRealPlayer = true
            level.addNewPlayer(bot)
            if (bot.isRemoved || level.getEntity(spec.uuid) !== bot) {
                bot.botRemoved = true
                throw IllegalStateException("Could not add bot ${spec.name} (${spec.uuid}) to ${world.name}: UUID already in use?")
            }
            return bot
        }
    }
}
