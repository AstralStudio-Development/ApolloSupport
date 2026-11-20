package moe.illusory;

import net.md_5.bungee.config.Configuration;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.Assert.*;

public class UuidMappingServiceTest {
    static final UUID DISPLAY = UUID.fromString("11111111-2222-4333-8444-555555555555");
    static final UUID NATIVE = UUID.fromString("261313ce-02a4-3688-b8cc-869c8d0f5256");
    static final AtomicInteger CALLS = new AtomicInteger();
    static volatile int status;
    static volatile long delay;
    static volatile String retryAfter;
    static volatile java.util.concurrent.CountDownLatch entered;
    static volatile java.util.concurrent.CountDownLatch release;
    static volatile boolean malformed;
    static volatile boolean disconnected;

    private static boolean installed;

    @BeforeClass
    public static synchronized void syntheticHttpOnly() {
        if (installed) { return; }
        installed = true;
        URL.setURLStreamHandlerFactory(protocol -> "https".equals(protocol) ? new URLStreamHandler() {
            @Override protected URLConnection openConnection(URL url) {
                return new HttpURLConnection(url) {
                    @Override public int getResponseCode() {
                        CALLS.incrementAndGet();
                        if (entered != null) { entered.countDown(); }
                        try {
                            if (release != null) { release.await(3, java.util.concurrent.TimeUnit.SECONDS); }
                            if (delay > 0) { Thread.sleep(delay); }
                        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                        return status;
                    }
                    @Override public String getHeaderField(String name) { return "Retry-After".equals(name) ? retryAfter : null; }
                    @Override public InputStream getInputStream() {
                        return new ByteArrayInputStream((malformed ? "{\"id\":null,\"next\":\"ignored\"}" : "{\"id\":\"" + DISPLAY + "\",\"raw_id\":\"" + DISPLAY + "\",\"uuid\":\"" + DISPLAY + "\"}").getBytes(StandardCharsets.UTF_8));
                    }
                    @Override public void disconnect() { disconnected = true; }
                    @Override public boolean usingProxy() { return false; }
                    @Override public void connect() { }
                };
            }
        } : null);
    }

    @Before public void resetHttp() {
        CALLS.set(0); status = 200; delay = 0; retryAfter = null;
        entered = null; release = null; malformed = false; disconnected = false;
    }

    static UuidMappingService service() {
        return new UuidMappingService(Logger.getLogger("ApolloSupportTest"), ApolloSupportConfig.from(new Configuration()));
    }

    @Test public void loginLookupMustNotBlockOnHttp() throws Exception {
        entered = new java.util.concurrent.CountDownLatch(1);
        release = new java.util.concurrent.CountDownLatch(1);
        try (UuidMappingService service = service()) {
            long start = System.nanoTime();
            assertFalse(service.resolveAndRemember("SyntheticUser", NATIVE).isPresent());
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;
            assertTrue("login lookup blocked for " + elapsedMillis + " ms", elapsedMillis < 200);
            assertTrue(entered.await(1, java.util.concurrent.TimeUnit.SECONDS));
            java.util.concurrent.CompletableFuture<java.util.Optional<UUID>> result = service.resolveAndRememberAsync("SyntheticUser", NATIVE);
            release.countDown();
            assertEquals(DISPLAY, result.get(2, java.util.concurrent.TimeUnit.SECONDS).get());
            assertEquals(DISPLAY, service.premiumByOffline(NATIVE).get());
            assertTrue(disconnected);
        } finally { release.countDown(); }
    }

    @Test public void repeatedMissingNameDoesNotQueryAgain() throws Exception {
        status = 404;
        try (UuidMappingService service = service()) {
            assertFalse(service.resolveAndRememberAsync("MissingUser", NATIVE).get().isPresent());
            int firstLookupCalls = CALLS.get();
            assertFalse(service.resolveAndRememberAsync("MissingUser", NATIVE).get().isPresent());
            assertEquals(1, firstLookupCalls);
            assertEquals(firstLookupCalls, CALLS.get());
        }
    }

    @Test public void caseInsensitiveConcurrentNamesShareOneNetworkLookup() throws Exception {
        entered = new java.util.concurrent.CountDownLatch(1);
        release = new java.util.concurrent.CountDownLatch(1);
        try (UuidMappingService service = service()) {
            java.util.List<java.util.concurrent.CompletableFuture<java.util.Optional<UUID>>> futures = new java.util.ArrayList<>();
            futures.add(service.resolvePremiumUuidAsync("SyntheticUser"));
            assertTrue(entered.await(1, java.util.concurrent.TimeUnit.SECONDS));
            for (int i = 0; i < 50; i++) { futures.add(service.resolvePremiumUuidAsync("syntheticuser")); }
            release.countDown();
            for (java.util.concurrent.CompletableFuture<java.util.Optional<UUID>> future : futures) {
                assertEquals(DISPLAY, future.get(2, java.util.concurrent.TimeUnit.SECONDS).get());
            }
            assertEquals(1, CALLS.get());
        } finally { release.countDown(); }
    }

    @Test public void badRequestDoesNotFanOutOrBindIdentity() throws Exception {
        status = 400;
        try (UuidMappingService service = service()) {
            assertFalse(service.resolveAndRememberAsync("SyntheticUser", NATIVE).get().isPresent());
            assertFalse(service.resolveAndRememberAsync("SyntheticUser", NATIVE).get().isPresent());
            assertEquals(1, CALLS.get());
            assertFalse(service.hasOfflineMappings());
        }
    }

    @Test public void transientFailuresAreCachedAndExpire() throws Exception {
        status = 500;
        java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong(1_000_000);
        try (UuidMappingService service = timedService(now, 2, 128)) {
            assertFalse(service.resolvePremiumUuidAsync("SyntheticUser").get().isPresent());
            assertEquals(3, CALLS.get());
            assertFalse(service.resolvePremiumUuidAsync("SyntheticUser").get().isPresent());
            assertEquals(3, CALLS.get());
            now.addAndGet(60_001);
            status = 200;
            assertEquals(DISPLAY, service.resolvePremiumUuidAsync("SyntheticUser").get().get());
            assertEquals(4, CALLS.get());
        }
    }

    @Test public void rateLimitBackoffIsPerSourceAndHonorsRetryAfterAcrossNames() throws Exception {
        rateLimitScenario("120", 1_000_000L);
    }

    @Test public void httpDateRetryAfterIsHonored() throws Exception {
        rateLimitScenario("Wed, 1 Jan 2025 00:02:00 GMT", 1735689600000L);
    }

    private void rateLimitScenario(String header, long start) throws Exception {
        status = 429;
        retryAfter = header;
        java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong(start);
        try (UuidMappingService service = timedService(now, 2, 128)) {
            assertFalse(service.resolvePremiumUuidAsync("FirstUser").get().isPresent());
            assertEquals(3, CALLS.get());
            assertFalse(service.resolvePremiumUuidAsync("OtherUser").get().isPresent());
            assertEquals(3, CALLS.get());
            now.addAndGet(70_000);
            assertFalse(service.resolvePremiumUuidAsync("ThirdUser").get().isPresent());
            assertEquals(3, CALLS.get());
            now.addAndGet(60_001);
            status = 200;
            assertEquals(DISPLAY, service.resolvePremiumUuidAsync("FirstUser").get().get());
            assertEquals(4, CALLS.get());
        }
    }

    @Test public void saturatedExecutorNeverRunsHttpOnCaller() throws Exception {
        entered = new java.util.concurrent.CountDownLatch(1);
        release = new java.util.concurrent.CountDownLatch(1);
        try (UuidMappingService service = timedService(new java.util.concurrent.atomic.AtomicLong(1_000_000), 1, 1)) {
            java.util.concurrent.CompletableFuture<java.util.Optional<UUID>> first = service.resolvePremiumUuidAsync("FirstUser");
            assertTrue(entered.await(1, java.util.concurrent.TimeUnit.SECONDS));
            java.util.concurrent.CompletableFuture<java.util.Optional<UUID>> second = service.resolvePremiumUuidAsync("SecondUser");
            assertFalse(service.resolvePremiumUuidAsync("RejectedUser").get(1, java.util.concurrent.TimeUnit.SECONDS).isPresent());
            assertEquals(1, CALLS.get());
            release.countDown();
            assertEquals(DISPLAY, first.get(2, java.util.concurrent.TimeUnit.SECONDS).get());
            assertEquals(DISPLAY, second.get(2, java.util.concurrent.TimeUnit.SECONDS).get());
            assertEquals(2, CALLS.get());
        } finally { release.countDown(); }
    }

    @Test public void invalidBedrockNamesNeverReachPremiumHttp() throws Exception {
        try (UuidMappingService service = service()) {
            assertFalse(service.resolveAndRememberAsync(".SyntheticUser", NATIVE).get().isPresent());
            assertEquals(0, CALLS.get());
            assertFalse(service.hasOfflineMappings());
        }
    }

    @Test public void malformedResponseDoesNotBorrowAnUnrelatedJsonField() throws Exception {
        malformed = true;
        try (UuidMappingService service = service()) {
            assertFalse(service.resolveAndRememberAsync("SyntheticUser", NATIVE).get().isPresent());
            assertFalse(service.hasOfflineMappings());
            assertTrue(disconnected);
        }
    }

    @Test public void reloadDoesNotPublishAnOldInFlightLookup() throws Exception {
        entered = new java.util.concurrent.CountDownLatch(1);
        release = new java.util.concurrent.CountDownLatch(1);
        try (UuidMappingService service = service()) {
            java.util.concurrent.CompletableFuture<java.util.Optional<UUID>> pending = service.resolveAndRememberAsync("SyntheticUser", NATIVE);
            assertTrue(entered.await(1, java.util.concurrent.TimeUnit.SECONDS));
            service.updateConfig(ApolloSupportConfig.from(new Configuration()));
            release.countDown();
            assertFalse(pending.get(2, java.util.concurrent.TimeUnit.SECONDS).isPresent());
            assertFalse(service.hasOfflineMappings());
        } finally { release.countDown(); }
    }

    @Test public void invalidRetryAfterUsesIncreasingSourceBackoff() throws Exception {
        status = 429;
        retryAfter = "not-a-date";
        java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong(1_000_000);
        try (UuidMappingService service = timedService(now, 2, 128)) {
            assertFalse(service.resolvePremiumUuidAsync("FirstUser").get().isPresent());
            assertEquals(3, CALLS.get());
            now.addAndGet(61_000);
            assertFalse(service.resolvePremiumUuidAsync("SecondUser").get().isPresent());
            assertEquals(6, CALLS.get());
            now.addAndGet(61_000);
            assertFalse(service.resolvePremiumUuidAsync("ThirdUser").get().isPresent());
            assertEquals(6, CALLS.get());
            now.addAndGet(61_000);
            status = 200;
            assertEquals(DISPLAY, service.resolvePremiumUuidAsync("FourthUser").get().get());
            assertEquals(7, CALLS.get());
        }
    }

    @Test public void closeCompletesQueuedLookupsWithoutPublishingIdentity() throws Exception {
        entered = new java.util.concurrent.CountDownLatch(1);
        release = new java.util.concurrent.CountDownLatch(1);
        UuidMappingService service = timedService(new java.util.concurrent.atomic.AtomicLong(1_000_000), 1, 1);
        try {
            java.util.concurrent.CompletableFuture<java.util.Optional<UUID>> first = service.resolveAndRememberAsync("FirstUser", NATIVE);
            assertTrue(entered.await(1, java.util.concurrent.TimeUnit.SECONDS));
            java.util.concurrent.CompletableFuture<java.util.Optional<UUID>> second = service.resolveAndRememberAsync("SecondUser", NATIVE);
            service.close();
            assertFalse(first.get(1, java.util.concurrent.TimeUnit.SECONDS).isPresent());
            assertFalse(second.get(1, java.util.concurrent.TimeUnit.SECONDS).isPresent());
            assertFalse(service.hasOfflineMappings());
        } finally { release.countDown(); service.close(); }
    }

    private static UuidMappingService timedService(java.util.concurrent.atomic.AtomicLong clock, int workers, int queue) {
        java.util.concurrent.ThreadPoolExecutor executor = new java.util.concurrent.ThreadPoolExecutor(workers, workers, 0L,
                java.util.concurrent.TimeUnit.MILLISECONDS, new java.util.concurrent.ArrayBlockingQueue<>(queue));
        return new UuidMappingService(Logger.getLogger("test"), ApolloSupportConfig.from(new Configuration()), executor, clock::get);
    }
}
