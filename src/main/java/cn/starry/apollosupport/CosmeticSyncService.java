package cn.starry.apollosupport;

import java.util.UUID;

/**
 * Kept as a small compatibility facade for older command/status code.
 * Real player cosmetic visibility is handled by UUID display mapping, not Apollo CosmeticModule.
 */
final class CosmeticSyncService {
    private final UuidMappingService uuidMappingService;

    CosmeticSyncService(UuidMappingService uuidMappingService) {
        this.uuidMappingService = uuidMappingService;
    }

    void updateConfig(ApolloSupportConfig config) {
        uuidMappingService.updateConfig(config);
    }

    boolean isLunarPlayer(UUID uniqueId) {
        return uuidMappingService.premiumByOffline(uniqueId).isPresent();
    }

    int getLunarPlayerCount() {
        return uuidMappingService.mappedOfflineUuidCount();
    }

    boolean isCosmeticModuleAvailable() {
        return true;
    }

    void applyNow(String playerName, UUID uniqueId) {
        uuidMappingService.resolveAndRemember(playerName, uniqueId);
    }
}
