package moe.illusory;

import net.md_5.bungee.api.connection.PendingConnection;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.LoginEvent;
import net.md_5.bungee.config.Configuration;
import org.junit.BeforeClass;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.Assert.*;

public class LoginListenerTest {
    @BeforeClass public static void stubHttp() { UuidMappingServiceTest.syntheticHttpOnly(); }

    @Test public void temporaryLoginMustNotBindCachedDisplayIdentity() {
        runCachedLogin(new AuthApi(true, false), false, false);
    }

    @Test public void stableLoginKeepsManualCosmeticMappingAndNativeIdentity() {
        runCachedLogin(new AuthApi(false, false), true, false);
    }

    @Test public void noAuthenticationPluginKeepsOfflineDisplayMapping() {
        runCachedLogin(null, true, false);
    }

    @Test public void incompatibleAuthenticationApiFailsClosed() {
        runCachedLogin(new Object(), false, false);
    }

    @Test public void cancelledLoginMustNotBindDisplayIdentity() {
        runCachedLogin(null, false, true);
    }

    @Test public void coldLoginDoesNotEvenQueueNetwork() throws Exception {
        UuidMappingServiceTest.CALLS.set(0);
        UuidMappingServiceTest.status = 404;
        ApolloSupportConfig config = ApolloSupportConfig.from(new Configuration());
        AtomicInteger queued = new AtomicInteger();
        java.util.concurrent.ThreadPoolExecutor executor = new java.util.concurrent.ThreadPoolExecutor(1, 1, 0L,
                java.util.concurrent.TimeUnit.MILLISECONDS, new java.util.concurrent.ArrayBlockingQueue<>(1)) {
            @Override public void execute(Runnable command) { queued.incrementAndGet(); }
        };
        try (UuidMappingService service = new UuidMappingService(Logger.getLogger("test"), config, executor, System::currentTimeMillis)) {
            LoginListener listener = new LoginListener(null, service, new CountingRewrite(),
                    new AuthenticationBridge(() -> null, Logger.getLogger("test")), () -> config);
            listener.onLogin(new LoginEvent(connection(new AtomicInteger()), null));
            assertEquals(0, queued.get());
            assertEquals(0, UuidMappingServiceTest.CALLS.get());
            assertFalse(service.hasOfflineMappings());
        }
    }

    @Test public void playerDisplayRequiresAuthenticationAndStableProfile() {
        AuthApi api = new AuthApi(false, false);
        AuthenticationBridge bridge = new AuthenticationBridge(() -> api, Logger.getLogger("test"));
        ProxiedPlayer player = (ProxiedPlayer) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ProxiedPlayer.class},
                (proxy, method, args) -> "getPendingConnection".equals(method.getName()) ? connection(new AtomicInteger()) : null);
        assertFalse(bridge.mayUsePlayerDisplay(player));
        api.authenticated = true;
        assertTrue(bridge.mayUsePlayerDisplay(player));
        api.temporary = true;
        assertFalse(bridge.mayUsePlayerDisplay(player));
    }

    private void runCachedLogin(Object authApi, boolean expectMapping, boolean cancelled) {
        Configuration values = new Configuration();
        values.set("manual-mappings.SyntheticUser", UuidMappingServiceTest.DISPLAY.toString());
        ApolloSupportConfig config = ApolloSupportConfig.from(values);
        AtomicInteger nativeWrites = new AtomicInteger();
        PendingConnection connection = connection(nativeWrites);
        CountingRewrite rewrite = new CountingRewrite();
        try (UuidMappingService service = new UuidMappingService(Logger.getLogger("test"), config)) {
            LoginListener listener = new LoginListener(null, service, rewrite,
                    new AuthenticationBridge(() -> authApi, Logger.getLogger("test")), () -> config);
            LoginEvent event = new LoginEvent(connection, null);
            event.setCancelled(cancelled);
            listener.onLogin(event);
            assertEquals(expectMapping, service.premiumByOffline(UuidMappingServiceTest.NATIVE).isPresent());
            assertEquals(expectMapping ? 1 : 0, rewrite.injections);
            assertEquals(UuidMappingServiceTest.NATIVE, connection.getUniqueId());
            assertEquals(0, nativeWrites.get());
        }
    }

    private static PendingConnection connection(AtomicInteger nativeWrites) {
        return (PendingConnection) Proxy.newProxyInstance(LoginListenerTest.class.getClassLoader(), new Class<?>[]{PendingConnection.class},
                (proxy, method, args) -> {
                    if ("getName".equals(method.getName())) { return "SyntheticUser"; }
                    if ("getUniqueId".equals(method.getName())) { return UuidMappingServiceTest.NATIVE; }
                    if (method.getName().startsWith("set")) { nativeWrites.incrementAndGet(); }
                    return null;
                });
    }

    public static final class AuthApi {
        boolean temporary;
        boolean authenticated;
        AuthApi(boolean temporary, boolean authenticated) { this.temporary = temporary; this.authenticated = authenticated; }
        public boolean isTemporaryConnection(PendingConnection connection) { return temporary; }
        public boolean isAuthenticated(ProxiedPlayer player) { return authenticated; }
    }

    private static final class CountingRewrite implements RewriteService {
        int injections;
        public boolean register() { return true; }
        public boolean isRegistered() { return true; }
        public String getStatusMessage() { return "test"; }
        public boolean inject(PendingConnection connection, String name, UUID uuid) { injections++; return true; }
    }
}
