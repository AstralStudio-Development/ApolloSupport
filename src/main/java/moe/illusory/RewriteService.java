package moe.illusory;

import net.md_5.bungee.api.connection.PendingConnection;
import net.md_5.bungee.api.connection.ProxiedPlayer;

import java.util.UUID;

interface RewriteService {
    boolean register();

    boolean isRegistered();

    String getStatusMessage();

    default boolean inject(PendingConnection connection, String playerName, UUID offlineUuid) {
        return false;
    }

    default boolean inject(ProxiedPlayer player) {
        return false;
    }

    default void uninject(ProxiedPlayer player) {
    }

    default int injectedCount() {
        return 0;
    }

    default long rewrittenPacketCount() {
        return 0L;
    }
}
