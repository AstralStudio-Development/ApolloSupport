package moe.illusory;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import moe.illusory.protocol.PrecisePacketUuidRewriter;
import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.protocol.ProtocolConstants;
import net.md_5.bungee.protocol.data.Property;
import net.md_5.bungee.protocol.packet.LoginSuccess;
import org.junit.Test;

import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.Assert.*;

public class PrecisePacketUuidRewriterTest {
    private static UuidMappingService mappedService() {
        Configuration values = new Configuration();
        values.set("manual-mappings.SyntheticUser", UuidMappingServiceTest.DISPLAY.toString());
        UuidMappingService service = new UuidMappingService(Logger.getLogger("test"), ApolloSupportConfig.from(values));
        service.rememberCachedMapping("SyntheticUser", UuidMappingServiceTest.NATIVE);
        return service;
    }

    @Test public void nativeLoginPacketRoundTripPreservesUsernameAndSignedProperties() {
        try (UuidMappingService service = mappedService()) {
            Property[] properties = { new Property("textures", "synthetic-texture", "synthetic-signature") };
            LoginSuccess packet = new LoginSuccess(UuidMappingServiceTest.NATIVE, "SyntheticUser", properties);
            assertTrue(new PrecisePacketUuidRewriter(service, () -> true).rewrite(packet));
            assertSame(properties, packet.getProperties());
            ByteBuf encoded = Unpooled.buffer();
            try {
                packet.write(encoded, ProtocolConstants.Direction.TO_CLIENT, ProtocolConstants.MINECRAFT_1_21_9);
                LoginSuccess decoded = new LoginSuccess();
                decoded.read(encoded, ProtocolConstants.Direction.TO_CLIENT, ProtocolConstants.MINECRAFT_1_21_9);
                assertEquals(UuidMappingServiceTest.DISPLAY, decoded.getUuid());
                assertEquals("SyntheticUser", decoded.getUsername());
                assertArrayEquals(properties, decoded.getProperties());
                assertEquals(0, encoded.readableBytes());
            } finally { encoded.release(); }
        }
    }

    @Test public void newerSessionIdAndOpaqueExtensionAreNotRebuiltOrScanned() {
        try (UuidMappingService service = mappedService()) {
            UUID sessionId = UuidMappingServiceTest.NATIVE;
            byte[] extension = new byte[16];
            java.nio.ByteBuffer.wrap(extension).putLong(sessionId.getMostSignificantBits()).putLong(sessionId.getLeastSignificantBits());
            byte[] original = extension.clone();
            moe.illusory.fixture.LoginSuccess packet = new moe.illusory.fixture.LoginSuccess(UuidMappingServiceTest.NATIVE, sessionId, extension);
            assertTrue(new PrecisePacketUuidRewriter(service, () -> true).rewrite(packet));
            assertEquals(UuidMappingServiceTest.DISPLAY, packet.getUuid());
            assertEquals(sessionId, packet.getSessionId());
            assertSame(extension, packet.getExtension());
            assertArrayEquals(original, packet.getExtension());
        }
    }

    @Test public void disabledLoginDisplayLeavesTheNativePacketUntouched() {
        try (UuidMappingService service = mappedService()) {
            LoginSuccess packet = new LoginSuccess(UuidMappingServiceTest.NATIVE, "SyntheticUser", new Property[0]);
            assertFalse(new PrecisePacketUuidRewriter(service, () -> false).rewrite(packet));
            assertEquals(UuidMappingServiceTest.NATIVE, packet.getUuid());
        }
    }

    @Test public void unrelatedPacketIsNeverScannedForKnownUuids() {
        try (UuidMappingService service = mappedService()) {
            UnrelatedPacket packet = new UnrelatedPacket();
            assertFalse(new PrecisePacketUuidRewriter(service, () -> true).rewrite(packet));
            assertEquals(UuidMappingServiceTest.NATIVE, packet.uuid);
        }
    }

    private static final class UnrelatedPacket {
        private final UUID uuid = UuidMappingServiceTest.NATIVE;
    }
}
