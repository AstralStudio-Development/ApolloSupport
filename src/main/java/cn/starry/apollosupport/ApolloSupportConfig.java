package cn.starry.apollosupport;

import net.md_5.bungee.config.Configuration;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

final class ApolloSupportConfig {
    private final boolean uuidResolverEnabled;
    private final int requestTimeoutMillis;
    private final long cacheMillis;
    private final boolean packetRewriteEnabled;
    private final int tabUpdateThrottleMillis;
    private final boolean tabUpdateDedupeEnabled;
    private final boolean playerEntityFilterEnabled;
    private final int playerEntityTypeId;
    private final boolean debug;
    private final Map<String, UUID> manualMappings;

    private ApolloSupportConfig(
            boolean uuidResolverEnabled,
            int requestTimeoutMillis,
            long cacheMillis,
            boolean packetRewriteEnabled,
            int tabUpdateThrottleMillis,
            boolean tabUpdateDedupeEnabled,
            boolean playerEntityFilterEnabled,
            int playerEntityTypeId,
            boolean debug,
            Map<String, UUID> manualMappings
    ) {
        this.uuidResolverEnabled = uuidResolverEnabled;
        this.requestTimeoutMillis = requestTimeoutMillis;
        this.cacheMillis = cacheMillis;
        this.packetRewriteEnabled = packetRewriteEnabled;
        this.tabUpdateThrottleMillis = tabUpdateThrottleMillis;
        this.tabUpdateDedupeEnabled = tabUpdateDedupeEnabled;
        this.playerEntityFilterEnabled = playerEntityFilterEnabled;
        this.playerEntityTypeId = playerEntityTypeId;
        this.debug = debug;
        this.manualMappings = manualMappings;
    }

    static ApolloSupportConfig from(Configuration configuration) {
        return new ApolloSupportConfig(
                configuration.getBoolean("uuid-resolver.enabled", true),
                Math.max(500, configuration.getInt("uuid-resolver.request-timeout-millis", 2500)),
                Math.max(60000L, configuration.getLong("uuid-resolver.cache-minutes", 1440L) * 60_000L),
                configuration.getBoolean("packet-rewrite.enabled", true),
                Math.max(0, configuration.getInt("packet-rewrite.tab-update-throttle-millis", 0)),
                configuration.getBoolean("packet-rewrite.tab-update-dedupe", true),
                configuration.getBoolean("packet-rewrite.player-entity-filter.enabled", false),
                configuration.getInt("packet-rewrite.player-entity-filter.type-id", -1),
                configuration.getBoolean("debug", false),
                readManualMappings(configuration.getSection("manual-mappings"))
        );
    }

    private static Map<String, UUID> readManualMappings(Configuration section) {
        if (section == null) {
            return Collections.emptyMap();
        }

        Map<String, UUID> mappings = new HashMap<>();
        for (String playerName : section.getKeys()) {
            String value = section.getString(playerName, "");
            try {
                mappings.put(normalizeName(playerName), parseUuid(value));
            } catch (IllegalArgumentException ignored) {
                // Invalid entries are ignored and reported by the command/status path through debug logs.
            }
        }
        return Collections.unmodifiableMap(mappings);
    }

    static String normalizeName(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    static UUID parseUuid(String raw) {
        String value = raw.trim();
        if (value.length() == 32) {
            value = value.substring(0, 8) + '-'
                    + value.substring(8, 12) + '-'
                    + value.substring(12, 16) + '-'
                    + value.substring(16, 20) + '-'
                    + value.substring(20);
        }
        return UUID.fromString(value);
    }

    boolean isUuidResolverEnabled() {
        return uuidResolverEnabled;
    }

    int getRequestTimeoutMillis() {
        return requestTimeoutMillis;
    }

    long getCacheMillis() {
        return cacheMillis;
    }

    boolean isPacketRewriteEnabled() {
        return packetRewriteEnabled;
    }

    int getTabUpdateThrottleMillis() {
        return tabUpdateThrottleMillis;
    }

    boolean isTabUpdateDedupeEnabled() {
        return tabUpdateDedupeEnabled;
    }

    boolean isPlayerEntityFilterEnabled() {
        return playerEntityFilterEnabled;
    }

    int getPlayerEntityTypeId() {
        return playerEntityTypeId;
    }

    boolean isDebug() {
        return debug;
    }

    Map<String, UUID> getManualMappings() {
        return manualMappings;
    }
}
