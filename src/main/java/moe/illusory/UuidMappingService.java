package moe.illusory;

import net.md_5.bungee.api.plugin.Plugin;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

/** Name resolution supplies display data only. It never authenticates or changes a connection UUID. */
public final class UuidMappingService implements AutoCloseable {
    private final Logger logger;
    private final Map<String, CacheEntry> nameCache = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> offlineToPremium = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Optional<UUID>>> inFlight = new ConcurrentHashMap<>();
    private final Map<String, SourceBackoff> sourceBackoffs = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor executor;
    private final LongSupplier clock;
    private volatile ApolloSupportConfig config;
    private volatile boolean closed;

    UuidMappingService(Plugin plugin, ApolloSupportConfig config) {
        this(plugin.getLogger(), config);
    }

    UuidMappingService(Logger logger, ApolloSupportConfig config) {
        this(logger, config, new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(128), task -> {
                    Thread thread = new Thread(task, "ApolloSupport-uuid-resolver");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy()), System::currentTimeMillis);
    }

    UuidMappingService(Logger logger, ApolloSupportConfig config, ThreadPoolExecutor executor, LongSupplier clock) {
        this.logger = logger;
        this.config = config;
        this.executor = executor;
        this.clock = clock;
    }

    void updateConfig(ApolloSupportConfig config) {
        this.config = config;
        nameCache.clear();
    }

    int cachedNameCount() { return nameCache.size() + config.getManualMappings().size(); }
    int mappedOfflineUuidCount() { return offlineToPremium.size(); }
    public Optional<UUID> premiumByOffline(UUID offlineUuid) { return Optional.ofNullable(premiumByOfflineOrNull(offlineUuid)); }
    public UUID premiumByOfflineOrNull(UUID offlineUuid) { return offlineToPremium.get(offlineUuid); }
    public boolean hasOfflineMappings() { return !offlineToPremium.isEmpty(); }
    public Map<UUID, UUID> offlineToPremiumSnapshot() { return new HashMap<>(offlineToPremium); }

    public Optional<UUID> premiumByNameIfCached(String playerName) {
        String normalized = ApolloSupportConfig.normalizeName(playerName);
        UUID manual = config.getManualMappings().get(normalized);
        if (manual != null) { return Optional.of(manual); }
        CacheEntry entry = cachedEntry(normalized);
        return entry == null ? Optional.empty() : Optional.ofNullable(entry.premiumUuid);
    }

    /** Cache/manual-only path for LoginEvent; never queues or waits for HTTP. */
    Optional<UUID> rememberCachedMapping(String playerName, UUID nativeUuid) {
        if (!config.isUuidResolverEnabled() || nativeUuid == null || closed) { return Optional.empty(); }
        Optional<UUID> premium = premiumByNameIfCached(playerName);
        premium.ifPresent(uuid -> offlineToPremium.put(nativeUuid, uuid));
        return premium;
    }

    /** Compatibility path for commands; returns immediately and resolves cold names in the bounded pool. */
    Optional<UUID> resolveAndRemember(String playerName, UUID nativeUuid) {
        Optional<UUID> cached = rememberCachedMapping(playerName, nativeUuid);
        if (!cached.isPresent()) { resolveAndRememberAsync(playerName, nativeUuid); }
        return cached;
    }

    CompletableFuture<Optional<UUID>> resolveAndRememberAsync(String playerName, UUID nativeUuid) {
        return resolvePremiumUuidAsync(playerName).thenApply(resolved -> rememberCachedMapping(playerName, nativeUuid));
    }

    /** One task per case-insensitive name, with bounded concurrency and no caller-runs fallback. */
    CompletableFuture<Optional<UUID>> resolvePremiumUuidAsync(String playerName) {
        if (closed || !config.isUuidResolverEnabled()) { return CompletableFuture.completedFuture(Optional.empty()); }
        String normalized = ApolloSupportConfig.normalizeName(playerName);
        Optional<UUID> manualOrCached = premiumByNameIfCached(playerName);
        if (manualOrCached.isPresent() || cachedEntry(normalized) != null) {
            return CompletableFuture.completedFuture(manualOrCached);
        }
        if (!playerName.matches("[A-Za-z0-9_]{1,16}")) {
            rememberNegative(normalized, config.getNoProfileCacheMillis());
            return CompletableFuture.completedFuture(Optional.empty());
        }
        CompletableFuture<Optional<UUID>> future = new CompletableFuture<>();
        CompletableFuture<Optional<UUID>> existing = inFlight.putIfAbsent(normalized, future);
        if (existing != null) { return existing; }
        ApolloSupportConfig requestConfig = config;
        try {
            executor.execute(() -> {
                try {
                    LookupResult result = resolvePremiumUuid(playerName, requestConfig);
                    if (!closed && config == requestConfig) {
                        long ttl = result.uuid != null ? requestConfig.getCacheMillis()
                                : result.noProfile ? requestConfig.getNoProfileCacheMillis() : requestConfig.getFailureCacheMillis();
                        nameCache.put(normalized, new CacheEntry(result.uuid, clock.getAsLong() + ttl));
                    }
                    future.complete(closed || config != requestConfig ? Optional.empty() : Optional.ofNullable(result.uuid));
                } catch (RuntimeException exception) {
                    rememberNegative(normalized, requestConfig.getFailureCacheMillis());
                    future.complete(Optional.empty());
                    logger.warning("UUID display lookup failed: " + exception.getClass().getSimpleName());
                } finally {
                    inFlight.remove(normalized, future);
                }
            });
        } catch (RejectedExecutionException rejected) {
            if (!closed) { rememberNegative(normalized, requestConfig.getFailureCacheMillis()); }
            inFlight.remove(normalized, future);
            future.complete(Optional.empty());
        }
        return future;
    }

    void removeOffline(UUID nativeUuid) { offlineToPremium.remove(nativeUuid); }

    int cleanupExpiredNameCache() {
        long now = clock.getAsLong();
        int removed = 0;
        for (Map.Entry<String, CacheEntry> entry : nameCache.entrySet()) {
            if (entry.getValue().expiresAtMillis <= now && nameCache.remove(entry.getKey(), entry.getValue())) { removed++; }
        }
        return removed;
    }

    private CacheEntry cachedEntry(String normalized) {
        CacheEntry entry = nameCache.get(normalized);
        if (entry != null && entry.expiresAtMillis <= clock.getAsLong()) {
            nameCache.remove(normalized, entry);
            return null;
        }
        return entry;
    }

    private void rememberNegative(String normalized, long ttl) {
        nameCache.put(normalized, new CacheEntry(null, clock.getAsLong() + ttl));
    }

    private LookupResult resolvePremiumUuid(String name, ApolloSupportConfig requestConfig) {
        LookupResult result = queryUuidSource("Mojang", "https://api.mojang.com/users/profiles/minecraft/" + name, requestConfig, "id");
        if (!result.terminal()) { result = queryUuidSource("PlayerDB", "https://playerdb.co/api/player/minecraft/" + name, requestConfig, "raw_id", "id"); }
        if (!result.terminal()) { result = queryUuidSource("Ashcon", "https://api.ashcon.app/mojang/v2/user/" + name, requestConfig, "uuid"); }
        return result;
    }

    private LookupResult queryUuidSource(String source, String url, ApolloSupportConfig requestConfig, String... keys) {
        SourceBackoff backoff = sourceBackoffs.computeIfAbsent(source, ignored -> new SourceBackoff());
        if (closed || clock.getAsLong() < backoff.retryAtMillis) { return LookupResult.FAILURE; }
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(requestConfig.getRequestTimeoutMillis());
            connection.setReadTimeout(requestConfig.getRequestTimeoutMillis());
            connection.setRequestMethod("GET");
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "ApolloSupport/1.0");
            int code = connection.getResponseCode();
            if (code == 204 || code == 404) { return LookupResult.NO_PROFILE; }
            // Invalid request is not evidence of a premium identity. Do not fan out the same bad request.
            if (code == 400) { return LookupResult.REJECTED; }
            if (code == 429 || code == 503) {
                synchronized (backoff) {
                    backoff.failures = Math.min(5, backoff.failures + 1);
                    long delay = requestConfig.getRateLimitBackoffMillis() * (1L << (backoff.failures - 1));
                    long retryAt = retryAfterMillis(connection.getHeaderField("Retry-After"), clock.getAsLong(), delay);
                    backoff.retryAtMillis = Math.max(backoff.retryAtMillis, retryAt);
                }
                logger.warning(source + " UUID display lookup HTTP " + code + "; backing off until " + backoff.retryAtMillis);
                return LookupResult.FAILURE;
            }
            if (code < 200 || code >= 300) { return LookupResult.FAILURE; }
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                char[] chunk = new char[2048];
                int count;
                while ((count = reader.read(chunk)) != -1) {
                    if (body.length() + count > 65536) { return LookupResult.FAILURE; }
                    body.append(chunk, 0, count);
                }
            }
            for (String key : keys) {
                String value = extractJsonString(body.toString(), key);
                if (value != null && !value.isEmpty()) {
                    UUID uuid = ApolloSupportConfig.parseUuid(value);
                    synchronized (backoff) { backoff.failures = 0; }
                    return new LookupResult(uuid, false, false);
                }
            }
            return LookupResult.FAILURE;
        } catch (IOException | IllegalArgumentException exception) {
            if (requestConfig.isDebug()) { logger.info(source + " UUID display lookup failed: " + exception.getMessage()); }
            return LookupResult.FAILURE;
        } finally {
            if (connection != null) { connection.disconnect(); }
        }
    }

    static long retryAfterMillis(String header, long now, long fallback) {
        if (header != null) {
            try {
                long seconds = Long.parseLong(header.trim());
                if (seconds >= 0 && seconds <= (Long.MAX_VALUE - now) / 1000L) { return Math.max(now + fallback, now + seconds * 1000L); }
            } catch (NumberFormatException ignored) {
                try {
                    return Math.max(now + fallback, ZonedDateTime.parse(header.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli());
                } catch (DateTimeParseException invalid) { /* Use bounded exponential fallback. */ }
            }
        }
        return now + fallback;
    }

    private static String extractJsonString(String json, String key) {
        String marker = "\"" + key + "\"";
        int keyIndex = json.indexOf(marker);
        if (keyIndex < 0) { return null; }
        int colonIndex = json.indexOf(':', keyIndex + marker.length());
        if (colonIndex < 0) { return null; }
        int startQuote = colonIndex + 1;
        while (startQuote < json.length() && Character.isWhitespace(json.charAt(startQuote))) { startQuote++; }
        if (startQuote >= json.length() || json.charAt(startQuote) != '"') { return null; }
        int endQuote = json.indexOf('"', startQuote + 1);
        return endQuote < 0 ? null : json.substring(startQuote + 1, endQuote);
    }

    @Override public void close() {
        closed = true;
        executor.shutdownNow();
        for (CompletableFuture<Optional<UUID>> future : inFlight.values()) { future.complete(Optional.empty()); }
        inFlight.clear();
    }

    private static final class SourceBackoff {
        private volatile long retryAtMillis;
        private int failures;
    }

    private static final class LookupResult {
        private static final LookupResult FAILURE = new LookupResult(null, false, false);
        private static final LookupResult NO_PROFILE = new LookupResult(null, true, true);
        private static final LookupResult REJECTED = new LookupResult(null, false, true);
        private final UUID uuid;
        private final boolean noProfile;
        private final boolean stop;
        private LookupResult(UUID uuid, boolean noProfile, boolean stop) { this.uuid = uuid; this.noProfile = noProfile; this.stop = stop; }
        private boolean terminal() { return uuid != null || stop; }
    }

    private static final class CacheEntry {
        private final UUID premiumUuid;
        private final long expiresAtMillis;
        private CacheEntry(UUID premiumUuid, long expiresAtMillis) { this.premiumUuid = premiumUuid; this.expiresAtMillis = expiresAtMillis; }
    }
}
