package moe.illusory;

final class RewriteServiceFactory {
    private RewriteServiceFactory() {
    }

    static RewriteService create(ApolloSupportPlugin plugin, UuidMappingService uuidMappingService) {
        return new NettyRewriteService(plugin, uuidMappingService);
    }
}
