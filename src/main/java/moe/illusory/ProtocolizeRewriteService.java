package moe.illusory;

/**
 * Deprecated compatibility stub. ApolloSupport no longer depends on Protocolize.
 */
@Deprecated
final class ProtocolizeRewriteService implements RewriteService {
    ProtocolizeRewriteService(net.md_5.bungee.api.plugin.Plugin plugin, UuidMappingService uuidMappingService) {
    }

    @Override
    public boolean register() {
        return false;
    }

    @Override
    public boolean isRegistered() {
        return false;
    }

    @Override
    public String getStatusMessage() {
        return "Protocolize support has been removed; using internal Netty rewrite service instead";
    }
}
