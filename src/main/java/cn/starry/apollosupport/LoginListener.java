package cn.starry.apollosupport;

import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.connection.PendingConnection;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.LoginEvent;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.event.ServerConnectedEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.event.EventPriority;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class LoginListener implements Listener {
    private static final long NON_LUNAR_UNINJECT_DELAY_SECONDS = 20L;
    private final ApolloSupportPlugin plugin;
    private final UuidMappingService uuidMappingService;
    private final RewriteService rewriteService;
    private final Set<UUID> lunarPlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> lunarDetectionGrace = ConcurrentHashMap.newKeySet();

    LoginListener(ApolloSupportPlugin plugin, UuidMappingService uuidMappingService, RewriteService rewriteService) {
        this.plugin = plugin;
        this.uuidMappingService = uuidMappingService;
        this.rewriteService = rewriteService;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onLogin(LoginEvent event) {
        if (!plugin.getLocalConfig().isPacketRewriteEnabled()) {
            return;
        }

        PendingConnection connection = event.getConnection();
        String playerName = connection.getName();
        UUID offlineUuid = connection.getUniqueId();
        lunarDetectionGrace.add(offlineUuid);
        Optional<UUID> premiumUuid = uuidMappingService.resolveAndRemember(playerName, offlineUuid);
        if (premiumUuid.isPresent()) {
            rewriteService.inject(connection, playerName, offlineUuid);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPostLogin(PostLoginEvent event) {
        resolveAsync(event.getPlayer(), true);
        scheduleNonLunarCleanup(event.getPlayer());
    }

    @EventHandler
    public void onServerConnected(ServerConnectedEvent event) {
        ProxiedPlayer player = event.getPlayer();
        if (!plugin.getLocalConfig().isPacketRewriteEnabled()) {
            return;
        }

        if (uuidMappingService.premiumByOffline(player.getUniqueId()).isPresent()) {
            if (shouldKeepRewriteHandler(player.getUniqueId())) {
                rewriteService.inject(player);
            }
            return;
        }

        resolveAsync(player, true);
    }

    @EventHandler
    public void onPluginMessage(PluginMessageEvent event) {
        ProxiedPlayer player = pluginMessagePlayer(event);
        if (player == null || !isLunarPluginMessage(event.getTag(), event.getData())) {
            return;
        }

        UUID offlineUuid = player.getUniqueId();
        lunarPlayers.add(offlineUuid);
        lunarDetectionGrace.remove(offlineUuid);
        if (plugin.getLocalConfig().isPacketRewriteEnabled() && uuidMappingService.premiumByOffline(offlineUuid).isPresent()) {
            rewriteService.inject(player);
        }
    }

    @EventHandler
    public void onDisconnect(PlayerDisconnectEvent event) {
        UUID offlineUuid = event.getPlayer().getUniqueId();
        lunarPlayers.remove(offlineUuid);
        lunarDetectionGrace.remove(offlineUuid);
        rewriteService.uninject(event.getPlayer());

        // Keep the UUID mapping after disconnect so PlayerListItemRemove / entity cleanup packets
        // sent to other clients can still be rewritten from offline UUID to premium UUID.
        // Removing it too early creates ghost tab entries because clients added the premium UUID
        // but receive a remove packet for the offline UUID.
        ProxyServer.getInstance().getScheduler().schedule(plugin, () -> uuidMappingService.removeOffline(offlineUuid), 10L, TimeUnit.MINUTES);
    }

    private void resolveAsync(ProxiedPlayer player, boolean injectAfterResolve) {
        ProxyServer.getInstance().getScheduler().runAsync(plugin, () -> {
            if (!player.isConnected()) {
                return;
            }
            uuidMappingService.resolveAndRemember(player.getName(), player.getUniqueId());
            if (injectAfterResolve
                    && player.isConnected()
                    && plugin.getLocalConfig().isPacketRewriteEnabled()
                    && shouldKeepRewriteHandler(player.getUniqueId())) {
                rewriteService.inject(player);
            }
        });
    }

    private void scheduleNonLunarCleanup(ProxiedPlayer player) {
        if (!plugin.getLocalConfig().isPacketRewriteEnabled()) {
            return;
        }
        UUID offlineUuid = player.getUniqueId();
        ProxyServer.getInstance().getScheduler().schedule(plugin, () -> {
            lunarDetectionGrace.remove(offlineUuid);
            if (!player.isConnected() || lunarPlayers.contains(offlineUuid)) {
                return;
            }
            rewriteService.uninject(player);
        }, NON_LUNAR_UNINJECT_DELAY_SECONDS, TimeUnit.SECONDS);
    }

    private boolean shouldKeepRewriteHandler(UUID offlineUuid) {
        return lunarDetectionGrace.contains(offlineUuid) || lunarPlayers.contains(offlineUuid);
    }

    private ProxiedPlayer pluginMessagePlayer(PluginMessageEvent event) {
        if (event.getReceiver() instanceof ProxiedPlayer) {
            return (ProxiedPlayer) event.getReceiver();
        }
        if (event.getSender() instanceof ProxiedPlayer) {
            return (ProxiedPlayer) event.getSender();
        }
        return null;
    }

    private boolean isLunarPluginMessage(String tag, byte[] data) {
        if (containsLunar(tag)) {
            return true;
        }
        if (data == null || data.length == 0) {
            return false;
        }
        String payload = new String(data, StandardCharsets.UTF_8);
        return containsLunar(payload);
    }

    private boolean containsLunar(String value) {
        return value != null && value.toLowerCase(Locale.ROOT).contains("lunar");
    }
}
