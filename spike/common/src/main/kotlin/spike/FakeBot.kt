package spike

import com.mojang.authlib.GameProfile
import io.netty.channel.ChannelFutureListener
import io.netty.channel.embedded.EmbeddedChannel
import net.minecraft.network.Connection
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ClientInformation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.network.CommonListenerCookie
import net.minecraft.server.network.ServerGamePacketListenerImpl
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Input
import org.bukkit.Location
import org.bukkit.craftbukkit.CraftWorld
import java.net.InetSocketAddress
import java.util.UUID

/** Stored controller state, applied during the bot's own vanilla player tick. */
class BotInput {
    var forward = 0f   // +1 forward, -1 back
    var strafe = 0f    // +1 left, -1 right (vanilla xxa convention)
    var jump = false
    var sprint = false
    var sneak = false
    var yaw = 0f
    var pitch = 0f
}

/** Connection with no network: every outbound packet is dropped. */
class FakeConnection : Connection(PacketFlow.SERVERBOUND) {
    init {
        channel = EmbeddedChannel()
        address = InetSocketAddress("127.0.0.1", 0)
    }

    override fun send(packet: Packet<*>, sendListener: ChannelFutureListener?, flush: Boolean) {
        // drop
    }
}

class FakeBot(server: MinecraftServer, level: ServerLevel, profile: GameProfile) :
    ServerPlayer(server, level, profile, ClientInformation.createDefault()) {

    val input = BotInput()
    var tickCalls = 0

    override fun tick() {
        tickCalls++
        // ServerPlayer.tick(): server-side bookkeeping only (no physics).
        super.tick()
        if (isRemoved) return
        applyStoredInput()
        // What ServerGamePacketListenerImpl.tickPlayer() does for a real player, minus the snap-back
        // to the client-reported position: vanilla Player.tick() -> LivingEntity.aiStep() -> travel().
        xo = x; yo = y; zo = z
        doTick()
    }

    private fun applyStoredInput() {
        yRot = input.yaw
        xRot = input.pitch
        yHeadRot = input.yaw
        zza = input.forward
        xxa = input.strafe
        setJumping(input.jump)
        isSprinting = input.sprint && input.forward > 0
        isShiftKeyDown = input.sneak
        setLastClientInput(
            Input(
                input.forward > 0, input.forward < 0, input.strafe > 0, input.strafe < 0,
                input.jump, input.sneak, input.sprint,
            )
        )
    }

    companion object {
        fun spawn(at: Location, name: String): FakeBot {
            val server = MinecraftServer.getServer()
            val level = (at.world as CraftWorld).handle
            val profile = GameProfile(uuidFor(name), name)
            val bot = FakeBot(server, level, profile)
            // Constructor assigns bot.connection = this.
            ServerGamePacketListenerImpl(server, FakeConnection(), bot, CommonListenerCookie.createInitial(profile, false))
            // Clear the "client not loaded yet" invulnerability (normally cleared by the client's PlayerLoaded packet).
            bot.connection.handleAcceptPlayerLoad(ServerboundPlayerLoadedPacket())
            bot.absSnapTo(at.x, at.y, at.z, at.yaw, at.pitch)
            bot.input.yaw = at.yaw
            bot.input.pitch = at.pitch
            // Variant A: add to the level only, never to PlayerList.
            level.addNewPlayer(bot)
            return bot
        }

        fun uuidFor(name: String): UUID = UUID.nameUUIDFromBytes("spike-bot:$name".toByteArray())

        fun remove(bot: FakeBot) {
            (bot.level() as ServerLevel).removePlayerImmediately(bot, Entity.RemovalReason.DISCARDED)
        }
    }
}
