package pvpbots.adapter.v26_3

import io.netty.channel.ChannelFutureListener
import io.netty.channel.embedded.EmbeddedChannel
import net.minecraft.network.Connection
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.PacketFlow
import java.net.InetSocketAddress

/**
 * A connection with no network behind it. The embedded channel is open, so `isConnected()` is true,
 * and every outbound packet is dropped. The 1- and 2-argument `send` overloads delegate to this one.
 */
internal class FakeConnection : Connection(PacketFlow.SERVERBOUND) {
    init {
        channel = EmbeddedChannel()
        address = InetSocketAddress("127.0.0.1", 0)
    }

    override fun send(packet: Packet<*>, sendListener: ChannelFutureListener?, flush: Boolean) {
        // dropped: bots have no client
    }
}
