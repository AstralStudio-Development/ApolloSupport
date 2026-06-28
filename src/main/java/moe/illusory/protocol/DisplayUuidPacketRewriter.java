package moe.illusory.protocol;

import moe.illusory.UuidMappingService;
import io.netty.buffer.ByteBuf;

import java.util.Map;
import java.util.UUID;

public final class DisplayUuidPacketRewriter {
    private DisplayUuidPacketRewriter() {
    }

    /**
     * Protocol-independent UUID replacement.
     *
     * Instead of parsing version-specific packet layouts, this scans the already encoded outbound
     * packet for any known offline UUID 16-byte sequence and replaces it with the matching premium
     * UUID sequence. UUID fields are fixed-width, so packet length and VarInts are untouched.
     */
    public static boolean rewriteKnownUuids(ByteBuf buffer, UuidMappingService uuidMappingService) {
        Map<UUID, UUID> mappings = uuidMappingService.offlineToPremiumSnapshot();
        if (mappings.isEmpty() || buffer.readableBytes() < 16) {
            return false;
        }

        boolean changed = false;
        int start = buffer.readerIndex();
        int end = buffer.writerIndex() - 16;

        for (Map.Entry<UUID, UUID> entry : mappings.entrySet()) {
            UUID offline = entry.getKey();
            UUID premium = entry.getValue();
            if (offline.equals(premium)) {
                continue;
            }

            byte[] from = toBytes(offline);
            byte[] to = toBytes(premium);

            for (int index = start; index <= end; index++) {
                if (matches(buffer, index, from)) {
                    for (int i = 0; i < 16; i++) {
                        buffer.setByte(index + i, to[i]);
                    }
                    changed = true;
                    index += 15;
                }
            }
        }

        return changed;
    }

    private static boolean matches(ByteBuf buffer, int index, byte[] expected) {
        for (int i = 0; i < expected.length; i++) {
            if (buffer.getByte(index + i) != expected[i]) {
                return false;
            }
        }
        return true;
    }

    private static byte[] toBytes(UUID uuid) {
        byte[] bytes = new byte[16];
        long most = uuid.getMostSignificantBits();
        long least = uuid.getLeastSignificantBits();

        for (int i = 0; i < 8; i++) {
            bytes[i] = (byte) (most >>> (56 - i * 8));
            bytes[8 + i] = (byte) (least >>> (56 - i * 8));
        }
        return bytes;
    }
}
