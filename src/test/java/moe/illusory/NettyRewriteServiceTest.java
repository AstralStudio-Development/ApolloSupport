package moe.illusory;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.MessageToByteEncoder;
import net.md_5.bungee.api.connection.PendingConnection;
import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.protocol.DefinedPacket;
import net.md_5.bungee.protocol.MinecraftEncoder;
import net.md_5.bungee.protocol.Protocol;
import net.md_5.bungee.protocol.ProtocolConstants;
import net.md_5.bungee.protocol.data.Property;
import net.md_5.bungee.protocol.packet.LoginSuccess;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.Assert.*;

public class NettyRewriteServiceTest {
    @Test public void frameEncoderBeforePacketEncoderMustNotHideLoginSuccessObject() {
        assertWireLogin("packet-encoder", false);
    }

    @Test public void nativeEncoderIsIdentifiedByTypeNotAFragileHandlerName() {
        assertWireLogin("native-minecraft-output", false);
    }

    @Test public void nativeEncoderSubclassKeepsSyntheticModernSessionIdOnWire() {
        assertWireLogin("native-minecraft-output", true);
    }

    @Test public void actualViaPipelineKeepsObjectRewriteBeforeNativeSerialization() {
        assertWireLogin("packet-encoder", false, true);
    }

    private void assertWireLogin(String nativeEncoderName, boolean newerPacket) {
        assertWireLogin(nativeEncoderName, newerPacket, false);
    }

    private void assertWireLogin(String nativeEncoderName, boolean newerPacket, boolean viaPipeline) {
        Configuration values = new Configuration();
        values.set("manual-mappings.SyntheticUser", UuidMappingServiceTest.DISPLAY.toString());
        ApolloSupportConfig config = ApolloSupportConfig.from(values);
        try (UuidMappingService mapping = new UuidMappingService(Logger.getLogger("test"), config)) {
            mapping.rememberCachedMapping("SyntheticUser", UuidMappingServiceTest.NATIVE);
            EmbeddedChannel channel = new EmbeddedChannel();
            try {
                channel.pipeline().addLast(viaPipeline ? "frame-prepender-compress" : "frame_encoder", new FrameEncoder());
                if (viaPipeline) { channel.pipeline().addLast("via-encoder", new FrameEncoder()); }
                channel.pipeline().addLast(nativeEncoderName, newerPacket ? new NewerLoginEncoder()
                        : new MinecraftEncoder(Protocol.LOGIN, true, ProtocolConstants.MINECRAFT_1_21_9));
                if (viaPipeline) { channel.pipeline().addLast("via-encode-reorder", new io.netty.channel.ChannelDuplexHandler()); }
                NettyRewriteService rewrite = new NettyRewriteService(null, mapping, () -> config);
                PendingConnection connection = channelConnection(channel);
                assertTrue(rewrite.inject(connection, "SyntheticUser", UuidMappingServiceTest.NATIVE));
                channel.runPendingTasks();
                // Repeated login/server injection must leave one effective object handler.
                assertTrue(rewrite.inject(connection, "SyntheticUser", UuidMappingServiceTest.NATIVE));
                channel.runPendingTasks();
                Property[] properties = { new Property("textures", "synthetic-value", "synthetic-signature") };
                LoginSuccess packet;
                if (newerPacket) {
                    packet = new moe.illusory.fixture.LoginSuccess(UuidMappingServiceTest.NATIVE, UuidMappingServiceTest.NATIVE, new byte[]{1, 2, 3});
                    packet.setProperties(properties);
                } else {
                    packet = new LoginSuccess(UuidMappingServiceTest.NATIVE, "SyntheticUser", properties);
                }
                assertTrue(channel.writeOutbound(packet));
                ByteBuf wire = channel.readOutbound();
                try {
                    assertEquals(2, DefinedPacket.readVarInt(wire));
                    LoginSuccess decoded = new LoginSuccess();
                    decoded.read(wire, ProtocolConstants.Direction.TO_CLIENT, ProtocolConstants.MINECRAFT_1_21_9);
                    assertEquals("wire LoginSuccess must use display UUID", UuidMappingServiceTest.DISPLAY, decoded.getUuid());
                    assertEquals("SyntheticUser", decoded.getUsername());
                    assertArrayEquals(properties, decoded.getProperties());
                    if (newerPacket) {
                        assertEquals("sessionId is not display UUID", UuidMappingServiceTest.NATIVE, DefinedPacket.readUUID(wire));
                        assertEquals(1, wire.readUnsignedByte());
                        assertEquals(2, wire.readUnsignedByte());
                        assertEquals(3, wire.readUnsignedByte());
                    }
                    assertEquals(0, wire.readableBytes());
                } finally { wire.release(); }
                assertEquals(UuidMappingServiceTest.NATIVE, connection.getUniqueId());
                assertEquals(1, rewrite.injectedCount());
            } finally { channel.finishAndReleaseAll(); }
        }
    }

    public interface ChannelConnection extends PendingConnection {
        Channel getChannel();
    }

    private static PendingConnection channelConnection(EmbeddedChannel channel) {
        return (PendingConnection) Proxy.newProxyInstance(NettyRewriteServiceTest.class.getClassLoader(), new Class<?>[]{ChannelConnection.class},
                (proxy, method, args) -> {
                    if ("getChannel".equals(method.getName())) { return channel; }
                    if ("getName".equals(method.getName())) { return "SyntheticUser"; }
                    if ("getUniqueId".equals(method.getName())) { return UuidMappingServiceTest.NATIVE; }
                    return null;
                });
    }

    private static final class FrameEncoder extends MessageToByteEncoder<ByteBuf> {
        @Override protected void encode(ChannelHandlerContext ctx, ByteBuf bytes, ByteBuf out) {
            out.writeBytes(bytes);
        }
    }

    /** Synthetic extension beyond this project's protocol dependency; not a claim to implement protocol 776. */
    private static final class NewerLoginEncoder extends MinecraftEncoder {
        private NewerLoginEncoder() { super(Protocol.LOGIN, true, ProtocolConstants.MINECRAFT_1_21_9); }
        @Override protected void encode(ChannelHandlerContext ctx, DefinedPacket message, ByteBuf out) {
            DefinedPacket.writeVarInt(2, out);
            message.write(out, ProtocolConstants.Direction.TO_CLIENT, ProtocolConstants.MINECRAFT_1_21_9);
            moe.illusory.fixture.LoginSuccess login = (moe.illusory.fixture.LoginSuccess) message;
            DefinedPacket.writeUUID(login.getSessionId(), out);
            out.writeBytes(login.getExtension());
        }
    }
}
