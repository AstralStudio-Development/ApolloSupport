package moe.illusory;

import net.md_5.bungee.api.plugin.Plugin;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class UuidMappingService {
    private final Plugin plugin;
    private final Map<String, CacheEntry> nameCache = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> offlineToPremium = new ConcurrentHashMap<>();
    private volatile ApolloSupportConfig config;

    UuidMappingService(Plugin plugin, ApolloSupportConfig config) {
        this.plugin = plugin;
        this.config = config;
        loadManualMappings(config);
    }

    void updateConfig(ApolloSupportConfig config) {
        this.config = config;
        loadManualMappings(config);
    }

    int cachedNameCount() {
        return nameCache.size();
    }

    int mappedOfflineUuidCount() {
        return offlineToPremium.size();
    }

    public Optional<UUID> premiumByOffline(UUID offlineUuid) {
        return Optional.ofNullable(premiumByOfflineOrNull(offlineUuid));
    }

    public UUID premiumByOfflineOrNull(UUID offlineUuid) {
        return offlineToPremium.get(offlineUuid);
    }

    public boolean hasOfflineMappings() {
        return !offlineToPremium.isEmpty();
    }

    public Map<UUID, UUID> offlineToPremiumSnapshot() {
        return new java.util.HashMap<>(offlineToPremium);
    }

    public Optional<UUID> premiumByNameIfCached(String playerName) {
        CacheEntry entry = nameCache.get(ApolloSupportConfig.normalizeName(playerName));
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.expiresAtMillis < System.currentTimeMillis()) {
            nameCache.remove(ApolloSupportConfig.normalizeName(playerName), entry);
            return Optional.empty();
        }
        return Optional.of(entry.premiumUuid);
    }

    Optional<UUID> resolveAndRemember(String playerName, UUID offlineUuid) {
        if (!config.isUuidResolverEnabled()) {
            return Optional.empty();
        }

        Optional<UUID> premiumUuid = premiumByNameIfCached(playerName);
        if (!premiumUuid.isPresent()) {
            premiumUuid = resolvePremiumUuid(playerName);
        }

        premiumUuid.ifPresent(uuid -> offlineToPremium.put(offlineUuid, uuid));
        debug("UUID mapping " + playerName + ": " + offlineUuid + " -> " + premiumUuid.map(UUID::toString).orElse("<none>"));
        return premiumUuid;
    }

    void removeOffline(UUID offlineUuid) {
        offlineToPremium.remove(offlineUuid);
    }

    int cleanupExpiredNameCache() {
        long now = System.currentTimeMillis();
        int removed = 0;
        for (Map.Entry<String, CacheEntry> entry : nameCache.entrySet()) {
            if (entry.getValue().expiresAtMillis < now && nameCache.remove(entry.getKey(), entry.getValue())) {
                removed++;
            }
        }
        return removed;
    }

    private Optional<UUID> resolvePremiumUuid(String playerName) {
        String normalized = ApolloSupportConfig.normalizeName(playerName);
        UUID manual = config.getManualMappings().get(normalized);
        if (manual != null) {
            rememberName(normalized, manual);
            return Optional.of(manual);
        }

        Optional<UUID> resolved = queryUuidSource(playerName, "Mojang", "https://api.mojang.com/users/profiles/minecraft/" + playerName, "id");
        if (!resolved.isPresent()) {
            resolved = queryUuidSource(playerName, "PlayerDB", "https://playerdb.co/api/player/minecraft/" + playerName, "raw_id", "id");
        }
        if (!resolved.isPresent()) {
            resolved = queryUuidSource(playerName, "Ashcon", "https://api.ashcon.app/mojang/v2/user/" + playerName, "uuid");
        }

        resolved.ifPresent(uuid -> rememberName(normalized, uuid));
        return resolved;
    }

    private void rememberName(String normalizedName, UUID premiumUuid) {
        nameCache.put(normalizedName, new CacheEntry(premiumUuid, System.currentTimeMillis() + config.getCacheMillis()));
    }

    private void loadManualMappings(ApolloSupportConfig config) {
        long expiresAt = System.currentTimeMillis() + config.getCacheMillis();
        for (Map.Entry<String, UUID> entry : config.getManualMappings().entrySet()) {
            nameCache.put(entry.getKey(), new CacheEntry(entry.getValue(), expiresAt));
        }
    }

    private Optional<UUID> queryUuidSource(String playerName, String sourceName, String url, String... keys) {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(config.getRequestTimeoutMillis());
            connection.setReadTimeout(config.getRequestTimeoutMillis());
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", "ApolloSupport/1.0");

            int responseCode = connection.getResponseCode();
            if (responseCode == 204 || responseCode == 404) {
                debug(sourceName + " UUID lookup found no profile for " + playerName);
                return Optional.empty();
            }
            if (responseCode < 200 || responseCode >= 300) {
                plugin.getLogger().warning(sourceName + " UUID lookup failed for " + playerName + ": HTTP " + responseCode);
                return Optional.empty();
            }

            String body;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder builder = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    builder.append(line);
                }
                body = builder.toString();
            }

            for (String key : keys) {
                String id = extractJsonString(body, key);
                if (id == null || id.isEmpty()) {
                    continue;
                }
                UUID uuid = ApolloSupportConfig.parseUuid(id);
                debug(sourceName + " resolved " + playerName + " -> " + uuid);
                return Optional.of(uuid);
            }
            debug(sourceName + " UUID lookup returned no usable id for " + playerName);
            return Optional.empty();
        } catch (IOException | IllegalArgumentException exception) {
            plugin.getLogger().warning(sourceName + " UUID lookup failed for " + playerName + ": " + exception.getMessage());
            return Optional.empty();
        }
    }

    private static String extractJsonString(String json, String key) {
        if (json == null) {
            return null;
        }
        String marker = "\"" + key + "\"";
        int keyIndex = json.indexOf(marker);
        if (keyIndex < 0) {
            return null;
        }
        int colonIndex = json.indexOf(':', keyIndex + marker.length());
        if (colonIndex < 0) {
            return null;
        }
        int startQuote = json.indexOf('"', colonIndex + 1);
        if (startQuote < 0) {
            return null;
        }
        int endQuote = json.indexOf('"', startQuote + 1);
        if (endQuote < 0) {
            return null;
        }
        return json.substring(startQuote + 1, endQuote);
    }

    private void debug(String message) {
        if (config.isDebug()) {
            plugin.getLogger().info("[Debug] " + message);
        }
    }

    private static final class CacheEntry {
        private final UUID premiumUuid;
        private final long expiresAtMillis;

        private CacheEntry(UUID premiumUuid, long expiresAtMillis) {
            this.premiumUuid = premiumUuid;
            this.expiresAtMillis = expiresAtMillis;
        }
    }
}
