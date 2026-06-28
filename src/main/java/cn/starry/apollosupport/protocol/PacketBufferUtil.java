package cn.starry.apollosupport.protocol;

import io.netty.buffer.ByteBuf;

import java.util.UUID;

final class PacketBufferUtil {
    private PacketBufferUtil() {
    }

    static int readVarInt(ByteBuf input) {
        int value = 0;
        int position = 0;
        byte currentByte;

        do {
            currentByte = input.readByte();
            value |= (currentByte & 0x7F) << position;
            position += 7;
            if (position > 35) {
                throw new IllegalArgumentException("VarInt is too big");
            }
        } while ((currentByte & 0x80) == 0x80);

        return value;
    }

    static void writeVarInt(ByteBuf output, int value) {
        while ((value & 0xFFFFFF80) != 0L) {
            output.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        output.writeByte(value & 0x7F);
    }

    static UUID readUuid(ByteBuf input) {
        return new UUID(input.readLong(), input.readLong());
    }

    static void writeUuid(ByteBuf output, UUID uuid) {
        output.writeLong(uuid.getMostSignificantBits());
        output.writeLong(uuid.getLeastSignificantBits());
    }

    static String readString(ByteBuf input) {
        int length = readVarInt(input);
        byte[] bytes = new byte[length];
        input.readBytes(bytes);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    static void writeString(ByteBuf output, String value) {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        writeVarInt(output, bytes.length);
        output.writeBytes(bytes);
    }

    static void copyString(ByteBuf input, ByteBuf output) {
        writeString(output, readString(input));
    }

    static byte[] remainingBytes(ByteBuf input) {
        byte[] remaining = new byte[input.readableBytes()];
        input.readBytes(remaining);
        return remaining;
    }
}
