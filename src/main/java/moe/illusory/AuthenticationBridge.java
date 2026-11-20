package moe.illusory;

import net.md_5.bungee.api.connection.PendingConnection;
import net.md_5.bungee.api.connection.ProxiedPlayer;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Logger;

/** Optional public, read-only KyokouAuth contract. These checks never grant authentication. */
final class AuthenticationBridge {
    private final Supplier<Object> authPlugin;
    private final Logger logger;
    private final AtomicBoolean warned = new AtomicBoolean();

    AuthenticationBridge(Supplier<Object> authPlugin, Logger logger) {
        this.authPlugin = authPlugin;
        this.logger = logger;
    }

    boolean mayUseLoginDisplay(PendingConnection connection) {
        Object auth = authPlugin.get();
        return auth == null || Boolean.FALSE.equals(query(auth, "isTemporaryConnection", PendingConnection.class, connection));
    }

    boolean mayUsePlayerDisplay(ProxiedPlayer player) {
        Object auth = authPlugin.get();
        return auth == null || (Boolean.FALSE.equals(query(auth, "isTemporaryConnection", PendingConnection.class, player.getPendingConnection()))
                && Boolean.TRUE.equals(query(auth, "isAuthenticated", ProxiedPlayer.class, player)));
    }

    private Boolean query(Object auth, String methodName, Class<?> argumentType, Object argument) {
        try {
            Method method = auth.getClass().getMethod(methodName, argumentType);
            Object result = method.invoke(auth, argument);
            if (result instanceof Boolean) { return (Boolean) result; }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // A present but incompatible authentication plugin must fail closed, not guess from UUID/name.
        }
        if (warned.compareAndSet(false, true)) {
            logger.warning("KyokouAuth public display-readiness API is unavailable; skipping its connection display mappings. Update KyokouAuth; native identities are unchanged.");
        }
        return null;
    }
}
