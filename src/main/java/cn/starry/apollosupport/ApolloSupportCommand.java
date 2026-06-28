package cn.starry.apollosupport;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Command;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

final class ApolloSupportCommand extends Command {
    private final ApolloSupportPlugin plugin;

    ApolloSupportCommand(ApolloSupportPlugin plugin) {
        super("apollosupport", "apollosupport.admin", "asupport", "lunarcosmetics");
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            sendStatus(sender, args.length >= 2 ? args[1] : null);
            return;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            reload(sender);
            return;
        }

        if (args[0].equalsIgnoreCase("resolve")) {
            resolve(sender, args.length >= 2 ? args[1] : null);
            return;
        }

        send(sender, ChatColor.YELLOW + "用法: /apollosupport status [玩家] | reload | resolve <玩家>");
    }

    private void reload(CommandSender sender) {
        try {
            plugin.reloadPlugin();
            send(sender, ChatColor.GREEN + "ApolloSupport 配置已重载。");
        } catch (IOException exception) {
            send(sender, ChatColor.RED + "重载失败，查看控制台获取详细错误。");
            plugin.getLogger().warning("Failed to reload config.yml: " + exception.getMessage());
        }
    }

    private void resolve(CommandSender sender, String playerName) {
        if (playerName == null || playerName.isEmpty()) {
            send(sender, ChatColor.RED + "请指定玩家: /apollosupport resolve <玩家>");
            return;
        }

        ProxiedPlayer proxiedPlayer = ProxyServer.getInstance().getPlayer(playerName);
        if (proxiedPlayer == null) {
            send(sender, ChatColor.RED + "玩家不在线: " + playerName);
            return;
        }

        Optional<UUID> premiumUuid = plugin.getUuidMappingService().resolveAndRemember(proxiedPlayer.getName(), proxiedPlayer.getUniqueId());
        if (premiumUuid.isPresent()) {
            if (plugin.getLocalConfig().isPacketRewriteEnabled()) {
                plugin.getRewriteService().inject(proxiedPlayer);
            }
            send(sender, ChatColor.GREEN + "已解析 " + proxiedPlayer.getName() + " 的显示 UUID: " + premiumUuid.get());
        } else {
            send(sender, ChatColor.RED + "未能解析 " + proxiedPlayer.getName() + " 的正版 UUID。");
        }
    }

    private void sendStatus(CommandSender sender, String playerName) {
        UuidMappingService uuidMappingService = plugin.getUuidMappingService();
        RewriteService rewriteService = plugin.getRewriteService();
        ApolloSupportConfig config = plugin.getLocalConfig();

        send(sender, ChatColor.AQUA + "ApolloSupport 状态:");
        send(sender, ChatColor.GRAY + "- 玩家数据 UUID 修改: " + ChatColor.GREEN + "永不修改");
        send(sender, ChatColor.GRAY + "- 正版 UUID 解析: " + enabled(config.isUuidResolverEnabled()));
        send(sender, ChatColor.GRAY + "- 内置 Netty 包重写: " + enabled(rewriteService != null && rewriteService.isRegistered()));
        if (rewriteService != null) {
            send(sender, ChatColor.GRAY + "  状态: " + rewriteService.getStatusMessage());
            send(sender, ChatColor.GRAY + "  已注入连接/已重写包: " + rewriteService.injectedCount() + "/" + rewriteService.rewrittenPacketCount());
        }
        send(sender, ChatColor.GRAY + "- 已缓存名称 UUID: " + (uuidMappingService == null ? 0 : uuidMappingService.cachedNameCount()));
        send(sender, ChatColor.GRAY + "- 已映射在线 UUID: " + (uuidMappingService == null ? 0 : uuidMappingService.mappedOfflineUuidCount()));
        send(sender, ChatColor.GRAY + "- 重写模式: 协议无关 UUID 字节替换，适配 1.21.11 等新版协议");

        if (playerName == null || playerName.isEmpty()) {
            return;
        }

        ProxiedPlayer proxiedPlayer = ProxyServer.getInstance().getPlayer(playerName);
        if (proxiedPlayer == null) {
            send(sender, ChatColor.RED + "玩家不在线: " + playerName);
            return;
        }

        Optional<UUID> premiumUuid = uuidMappingService.premiumByOffline(proxiedPlayer.getUniqueId());
        send(sender, ChatColor.GRAY + "- 玩家: " + proxiedPlayer.getName());
        send(sender, ChatColor.GRAY + "  数据 UUID: " + proxiedPlayer.getUniqueId());
        send(sender, ChatColor.GRAY + "  显示 UUID: " + premiumUuid.map(UUID::toString).orElse(ChatColor.RED + "未解析"));
    }

    private String enabled(boolean value) {
        return value ? ChatColor.GREEN + "启用" : ChatColor.RED + "不可用/关闭";
    }

    private void send(CommandSender sender, String message) {
        sender.sendMessage(TextComponent.fromLegacyText(message));
    }
}
