package cn.starry.apollosupport;

import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public final class ApolloSupportPlugin extends Plugin {
    private UuidMappingService uuidMappingService;
    private CosmeticSyncService cosmeticSyncService;
    private RewriteService rewriteService;
    private ApolloSupportConfig config;

    @Override
    public void onEnable() {
        try {
            reloadLocalConfig();
        } catch (IOException exception) {
            getLogger().log(Level.SEVERE, "Failed to load config.yml, disabling ApolloSupport.", exception);
            return;
        }

        this.uuidMappingService = new UuidMappingService(this, config);
        this.cosmeticSyncService = new CosmeticSyncService(uuidMappingService);
        this.rewriteService = RewriteServiceFactory.create(this, uuidMappingService);
        scheduleNameCacheCleanup();

        getProxy().getPluginManager().registerListener(this, new LoginListener(this, uuidMappingService, rewriteService));
        getProxy().getPluginManager().registerCommand(this, new ApolloSupportCommand(this));

        if (config.isPacketRewriteEnabled()) {
            rewriteService.register();
        } else {
            getLogger().warning("Packet rewrite is disabled. Lunar cosmetics will not be fixed without display UUID rewriting.");
        }

        getLogger().info("ApolloSupport enabled. Player data UUIDs are not modified.");
    }

    @Override
    public void onDisable() {
        getProxy().getScheduler().cancel(this);
    }

    void reloadPlugin() throws IOException {
        reloadLocalConfig();
        if (uuidMappingService != null) {
            uuidMappingService.updateConfig(config);
        }
        if (cosmeticSyncService != null) {
            cosmeticSyncService.updateConfig(config);
        }
        if (rewriteService != null && config.isPacketRewriteEnabled()) {
            rewriteService.register();
        }
    }

    CosmeticSyncService getCosmeticSyncService() {
        return cosmeticSyncService;
    }

    UuidMappingService getUuidMappingService() {
        return uuidMappingService;
    }

    RewriteService getRewriteService() {
        return rewriteService;
    }

    ApolloSupportConfig getLocalConfig() {
        return config;
    }

    private void scheduleNameCacheCleanup() {
        getProxy().getScheduler().schedule(this, () -> {
            if (uuidMappingService != null) {
                uuidMappingService.cleanupExpiredNameCache();
            }
        }, 10L, 10L, TimeUnit.MINUTES);
    }

    private void reloadLocalConfig() throws IOException {
        ensureDefaultConfig();
        Configuration configuration = ConfigurationProvider.getProvider(YamlConfiguration.class).load(getDataPath().resolve("config.yml").toFile());
        this.config = ApolloSupportConfig.from(configuration);
    }

    private void ensureDefaultConfig() throws IOException {
        Path dataPath = getDataPath();
        Files.createDirectories(dataPath);

        Path configPath = dataPath.resolve("config.yml");
        if (Files.exists(configPath)) {
            return;
        }

        try (InputStream inputStream = getResourceAsStream("config.yml")) {
            if (inputStream == null) {
                throw new IOException("Default config.yml is missing from plugin jar.");
            }
            Files.copy(inputStream, configPath);
        }
    }

    private Path getDataPath() {
        return getDataFolder().toPath();
    }
}
