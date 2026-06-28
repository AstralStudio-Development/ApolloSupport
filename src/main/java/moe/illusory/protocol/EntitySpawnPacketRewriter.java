package moe.illusory.protocol;

import moe.illusory.UuidMappingService;
import io.netty.buffer.ByteBuf;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

public final class EntitySpawnPacketRewriter {
    // Clientbound Add Entity packet id for modern 1.21.x as used by vanilla protocol mappings.
    // Kept isolated so it can be adjusted if XCord's protocol mapping differs.
    private static final int ADD_ENTITY_PACKET_ID_1_21 = 0x01;

    private final UuidMappingService uuidMappingService;
    private final BooleanSupplier playerEntityFilterEnabled;
    private final IntSupplier playerEntityTypeId;
    private final AtomicLong rewrittenEntities = new AtomicLong();
    private final AtomicLong skippedByType = new AtomicLong();

    public EntitySpawnPacketRewriter(
            UuidMappingService uuidMappingService,
            BooleanSupplier playerEntityFilterEnabled,
            IntSupplier playerEntityTypeId
    ) {
        this.uuidMappingService = uuidMappingService;
        this.playerEntityFilterEnabled = playerEntityFilterEnabled;
        this.playerEntityTypeId = playerEntityTypeId;
    }

    public boolean rewrite(ByteBuf buffer) {
        if (!uuidMappingService.hasOfflineMappings()) {
            return false;
        }

        int readerIndex = buffer.readerIndex();
        try {
            int packetIdStart = buffer.readerIndex();
            int packetId = PacketBufferUtil.readVarInt(buffer);
            if (packetId != ADD_ENTITY_PACKET_ID_1_21) {
                buffer.readerIndex(readerIndex);
                return false;
            }

            // AddEntity packet starts with: entityId VarInt, uuid UUID, type VarInt, ...
            PacketBufferUtil.readVarInt(buffer);
            int uuidIndex = buffer.readerIndex();
            UUID original = PacketBufferUtil.readUuid(buffer);
            int entityType = PacketBufferUtil.readVarInt(buffer);
            if (shouldSkipByEntityType(entityType)) {
                skippedByType.incrementAndGet();
                buffer.readerIndex(readerIndex);
                return false;
            }

            UUID rewritten = uuidMappingService.premiumByOfflineOrNull(original);
            buffer.readerIndex(readerIndex);

            if (rewritten == null || rewritten.equals(original)) {
                return false;
            }

            writeUuidAt(buffer, uuidIndex, rewritten);
            rewrittenEntities.incrementAndGet();
            return true;
        } catch (RuntimeException ignored) {
            buffer.readerIndex(readerIndex);
            return false;
        }
    }

    public long rewrittenEntities() {
        return rewrittenEntities.get();
    }

    public long skippedByType() {
        return skippedByType.get();
    }

    private boolean shouldSkipByEntityType(int entityType) {
        int configuredTypeId = playerEntityTypeId.getAsInt();
        return playerEntityFilterEnabled.getAsBoolean()
                && configuredTypeId >= 0
                && entityType != configuredTypeId;
    }

    private static void writeUuidAt(ByteBuf buffer, int index, UUID uuid) {
        buffer.setLong(index, uuid.getMostSignificantBits());
        buffer.setLong(index + 8, uuid.getLeastSignificantBits());
    }
}
