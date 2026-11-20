package moe.illusory.fixture;

import java.util.UUID;

/** Synthetic newer packet: its inherited UUID is display data; its extra sessionId is not. */
public final class LoginSuccess extends net.md_5.bungee.protocol.packet.LoginSuccess {
    private final UUID sessionId;
    private final byte[] extension;

    public LoginSuccess(UUID uuid, UUID sessionId, byte[] extension) {
        super(uuid, "SyntheticUser", new net.md_5.bungee.protocol.data.Property[0]);
        this.sessionId = sessionId;
        this.extension = extension;
    }

    public UUID getSessionId() { return sessionId; }
    public byte[] getExtension() { return extension; }
}
