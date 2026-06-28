package cn.starry.apollosupport;

import cn.starry.apollosupport.protocol.EntitySpawnPacketRewriter;
import cn.starry.apollosupport.protocol.PrecisePacketUuidRewriter;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import net.md_5.bungee.api.connection.PendingConnection;
import net.md_5.bungee.api.connection.ProxiedPlayer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.logging.Level;

final class NettyRewriteService implements RewriteService {
    private static final String OBJECT_HANDLER_NAME = "apollo-support-display-uuid-object";
    private static final String RAW_HANDLER_NAME = "apollo-support-display-uuid-raw";
    private final ApolloSupportPlugin plugin;
    private final UuidMappingService uuidMappingService;
    private final Map<UUID, String> injectedPlayers = new ConcurrentHashMap<>();
    private final AtomicLong rewrittenPackets = new AtomicLong();
    private final AtomicLong rewrittenUuids = new AtomicLong();
    private final AtomicLong tabUpdateDeduped = new AtomicLong();
    private final AtomicLong tabUpdateThrottled = new AtomicLong();
    private final PrecisePacketUuidRewriter packetRewriter;
    private final EntitySpawnPacketRewriter entitySpawnRewriter;
    private volatile String lastStatus = "Netty handler not injected yet";

    NettyRewriteService(ApolloSupportPlugin plugin, UuidMappingService uuidMappingService) {
        this.plugin = plugin;
        this.uuidMappingService = uuidMappingService;
        this.packetRewriter = new PrecisePacketUuidRewriter(uuidMappingService);
        this.entitySpawnRewriter = new EntitySpawnPacketRewriter(
                uuidMappingService,
                () -> plugin.getLocalConfig().isPlayerEntityFilterEnabled(),
                () -> plugin.getLocalConfig().getPlayerEntityTypeId()
        );
    }

    @Override
    public boolean register() {
        lastStatus = "Netty rewrite service is ready";
        return true;
    }

    @Override
    public boolean inject(PendingConnection connection, String playerName, UUID offlineUuid) {
        return injectConnection(connection, playerName, offlineUuid);
    }

    @Override
    public boolean inject(ProxiedPlayer player) {
        return injectConnection(player, player.getName(), player.getUniqueId());
    }

    private boolean injectConnection(Object connectionObject, String playerName, UUID offlineUuid) {
        try {
            Channel channel = findChannelDeep(connectionObject, 0);
            if (channel == null) {
                lastStatus = "Unable to find Netty channel for " + playerName;
                return false;
            }

            channel.eventLoop().execute(() -> {
                ChannelPipeline pipeline = channel.pipeline();
                String anchor = findPacketEncoder(pipeline);
                if (pipeline.get(OBJECT_HANDLER_NAME) == null) {
                    ChannelDuplexHandler objectHandler = new DisplayUuidOutboundHandler(
                            packetRewriter,
                            rewrittenPackets,
                            rewrittenUuids,
                            tabUpdateDeduped,
                            tabUpdateThrottled,
                            () -> plugin.getLocalConfig().isTabUpdateDedupeEnabled(),
                            () -> plugin.getLocalConfig().getTabUpdateThrottleMillis()
                    );
                    if (anchor != null) {
                        pipeline.addAfter(anchor, OBJECT_HANDLER_NAME, objectHandler);
                    } else {
                        pipeline.addLast(OBJECT_HANDLER_NAME, objectHandler);
                    }
                }
                if (anchor != null && pipeline.get(RAW_HANDLER_NAME) == null) {
                    pipeline.addBefore(anchor, RAW_HANDLER_NAME, new EntitySpawnOutboundHandler(entitySpawnRewriter, rewrittenPackets, rewrittenUuids));
                }
                injectedPlayers.put(offlineUuid, playerName);
                lastStatus = "Injected Netty handler for " + playerName;
            });
            return true;
        } catch (RuntimeException exception) {
            lastStatus = "Failed to inject Netty handler for " + playerName + ": " + exception.getMessage();
            plugin.getLogger().log(Level.WARNING, lastStatus, exception);
            return false;
        }
    }

    @Override
    public void uninject(ProxiedPlayer player) {
        injectedPlayers.remove(player.getUniqueId());
        try {
            Channel channel = findChannelDeep(player, 0);
            if (channel == null) {
                channel = findChannelDeep(player.getPendingConnection(), 0);
            }
            if (channel == null) {
                return;
            }
            Channel finalChannel = channel;
            finalChannel.eventLoop().execute(() -> {
                if (finalChannel.pipeline().get(OBJECT_HANDLER_NAME) != null) {
                    finalChannel.pipeline().remove(OBJECT_HANDLER_NAME);
                }
                if (finalChannel.pipeline().get(RAW_HANDLER_NAME) != null) {
                    finalChannel.pipeline().remove(RAW_HANDLER_NAME);
                }
            });
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public int injectedCount() {
        return injectedPlayers.size();
    }

    @Override
    public long rewrittenPacketCount() {
        return rewrittenPackets.get();
    }

    @Override
    public boolean isRegistered() {
        return true;
    }

    @Override
    public String getStatusMessage() {
        return lastStatus + ", injected=" + injectedPlayers.size()
                + ", packets=" + rewrittenPackets.get()
                + ", uuids=" + rewrittenUuids.get()
                + ", loginPackets=" + packetRewriter.loginPackets()
                + ", tabPackets=" + packetRewriter.tabPackets()
                + ", tabItems=" + packetRewriter.tabItems()
                + ", tabUpdateDeduped=" + tabUpdateDeduped.get()
                + ", tabUpdateThrottled=" + tabUpdateThrottled.get()
                + ", entities=" + entitySpawnRewriter.rewrittenEntities()
                + ", entityTypeSkipped=" + entitySpawnRewriter.skippedByType()
                + ", last=" + packetRewriter.lastPacketClass();
    }

    private static String findPacketEncoder(ChannelPipeline pipeline) {
        for (String name : pipeline.names()) {
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("encoder") || lower.contains("packet-encoder") || lower.contains("minecraft-encoder")) {
                return name;
            }
        }
        return null;
    }

    private static Channel findChannelDeep(Object object, int depth) {
        if (object == null || depth > 5) {
            return null;
        }
        if (object instanceof Channel) {
            return (Channel) object;
        }

        Class<?> type = object.getClass();
        for (Method method : type.getDeclaredMethods()) {
            if (method.getParameterTypes().length != 0) {
                continue;
            }
            if (!Channel.class.isAssignableFrom(method.getReturnType())) {
                continue;
            }
            try {
                method.setAccessible(true);
                Object value = method.invoke(object);
                if (value instanceof Channel) {
                    return (Channel) value;
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }

        while (type != null && type != Object.class) {
            for (Field field : type.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    Object value = field.get(object);
                    if (value instanceof Channel) {
                        return (Channel) value;
                    }
                    if (value != null && shouldExplore(value)) {
                        Channel nested = findChannelDeep(value, depth + 1);
                        if (nested != null) {
                            return nested;
                        }
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                }
            }
            type = type.getSuperclass();
        }
        return null;
    }

    private static boolean shouldExplore(Object value) {
        String name = value.getClass().getName();
        return name.startsWith("net.md_5.bungee")
                || name.startsWith("io.netty")
                || name.toLowerCase(java.util.Locale.ROOT).contains("connection")
                || name.toLowerCase(java.util.Locale.ROOT).contains("channel");
    }

    private static final class EntitySpawnOutboundHandler extends ChannelDuplexHandler {
        private final EntitySpawnPacketRewriter entitySpawnRewriter;
        private final AtomicLong rewrittenPackets;
        private final AtomicLong rewrittenUuids;

        private EntitySpawnOutboundHandler(EntitySpawnPacketRewriter entitySpawnRewriter, AtomicLong rewrittenPackets, AtomicLong rewrittenUuids) {
            this.entitySpawnRewriter = entitySpawnRewriter;
            this.rewrittenPackets = rewrittenPackets;
            this.rewrittenUuids = rewrittenUuids;
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            try {
                if (msg instanceof ByteBuf && entitySpawnRewriter.rewrite((ByteBuf) msg)) {
                    rewrittenPackets.incrementAndGet();
                    rewrittenUuids.incrementAndGet();
                }
            } catch (Throwable ignored) {
                // Never break the player's connection because of entity UUID rewriting.
            }
            super.write(ctx, msg, promise);
        }
    }

    private static final class DisplayUuidOutboundHandler extends ChannelDuplexHandler {
        private final PrecisePacketUuidRewriter packetRewriter;
        private final AtomicLong rewrittenPackets;
        private final AtomicLong rewrittenUuids;
        private final AtomicLong tabUpdateDeduped;
        private final AtomicLong tabUpdateThrottled;
        private final BooleanSupplier tabUpdateDedupeEnabled;
        private final IntSupplier tabUpdateThrottleMillis;
        private long lastTabUpdateWriteAtMillis;
        private long lastTabUpdateFingerprint;
        private boolean hasLastTabUpdateFingerprint;

        private DisplayUuidOutboundHandler(
                PrecisePacketUuidRewriter packetRewriter,
                AtomicLong rewrittenPackets,
                AtomicLong rewrittenUuids,
                AtomicLong tabUpdateDeduped,
                AtomicLong tabUpdateThrottled,
                BooleanSupplier tabUpdateDedupeEnabled,
                IntSupplier tabUpdateThrottleMillis
        ) {
            this.packetRewriter = packetRewriter;
            this.rewrittenPackets = rewrittenPackets;
            this.rewrittenUuids = rewrittenUuids;
            this.tabUpdateDeduped = tabUpdateDeduped;
            this.tabUpdateThrottled = tabUpdateThrottled;
            this.tabUpdateDedupeEnabled = tabUpdateDedupeEnabled;
            this.tabUpdateThrottleMillis = tabUpdateThrottleMillis;
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            try {
                if (shouldDedupeTabUpdate(msg)) {
                    tabUpdateDeduped.incrementAndGet();
                    ReferenceCountUtil.release(msg);
                    promise.trySuccess();
                    return;
                }

                if (shouldThrottleTabUpdate(msg)) {
                    tabUpdateThrottled.incrementAndGet();
                    ReferenceCountUtil.release(msg);
                    promise.trySuccess();
                    return;
                }

                rememberTabUpdateFingerprint(msg);

                long before = packetRewriter.rewrittenUuidCount();
                if (packetRewriter.rewrite(msg)) {
                    rewrittenPackets.incrementAndGet();
                    rewrittenUuids.addAndGet(packetRewriter.rewrittenUuidCount() - before);
                }
            } catch (Throwable ignored) {
                // Never break the player's connection because of cosmetic display rewriting.
            }
            super.write(ctx, msg, promise);
        }

        private boolean shouldDedupeTabUpdate(Object msg) {
            if (!tabUpdateDedupeEnabled.getAsBoolean() || !isThrottleableTabUpdate(msg)) {
                return false;
            }

            Long fingerprint = tabUpdateFingerprint(msg);
            if (fingerprint == null) {
                return false;
            }
            return hasLastTabUpdateFingerprint && lastTabUpdateFingerprint == fingerprint;
        }

        private void rememberTabUpdateFingerprint(Object msg) {
            if (!tabUpdateDedupeEnabled.getAsBoolean() || !isThrottleableTabUpdate(msg)) {
                return;
            }
            Long fingerprint = tabUpdateFingerprint(msg);
            if (fingerprint == null) {
                return;
            }
            lastTabUpdateFingerprint = fingerprint;
            hasLastTabUpdateFingerprint = true;
        }

        private Long tabUpdateFingerprint(Object msg) {
            Object actions = readActionLikeField(msg);
            Object items = readNamedField(msg, "items");
            if (actions == null || items == null || !items.getClass().isArray()) {
                return null;
            }

            long hash = 1125899906842597L;
            hash = mix(hash, actions.toString().hashCode());
            int length = java.lang.reflect.Array.getLength(items);
            hash = mix(hash, length);
            for (int i = 0; i < length; i++) {
                Object item = java.lang.reflect.Array.get(items, i);
                long itemHash = itemFingerprint(item);
                if (itemHash == Long.MIN_VALUE) {
                    return null;
                }
                hash = mix(hash, itemHash);
            }
            return hash;
        }

        private long itemFingerprint(Object item) {
            if (item == null) {
                return 0L;
            }
            long hash = item.getClass().getName().hashCode();
            Class<?> type = item.getClass();
            while (type != null && type != Object.class) {
                Field[] fields = type.getDeclaredFields();
                java.util.Arrays.sort(fields, java.util.Comparator.comparing(Field::getName));
                for (Field field : fields) {
                    if (field.isSynthetic() || java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }
                    try {
                        field.setAccessible(true);
                        Object value = field.get(item);
                        hash = mix(hash, field.getName().hashCode());
                        hash = mix(hash, value == null ? 0 : value.toString().hashCode());
                    } catch (ReflectiveOperationException | RuntimeException ignored) {
                        return Long.MIN_VALUE;
                    }
                }
                type = type.getSuperclass();
            }
            return hash;
        }

        private long mix(long hash, long value) {
            return (hash * 31L) + value;
        }

        private boolean shouldThrottleTabUpdate(Object msg) {
            int intervalMillis = tabUpdateThrottleMillis.getAsInt();
            if (intervalMillis <= 0 || !isThrottleableTabUpdate(msg)) {
                return false;
            }

            long now = System.currentTimeMillis();
            if (lastTabUpdateWriteAtMillis != 0L && now - lastTabUpdateWriteAtMillis < intervalMillis) {
                return true;
            }
            lastTabUpdateWriteAtMillis = now;
            return false;
        }

        private boolean isThrottleableTabUpdate(Object msg) {
            if (msg == null || !msg.getClass().getName().endsWith(".PlayerListItemUpdate")) {
                return false;
            }

            Object actions = readActionLikeField(msg);
            if (actions == null) {
                return false;
            }

            String actionText = actions.toString().toUpperCase(java.util.Locale.ROOT);
            return !actionText.contains("ADD")
                    && !actionText.contains("INITIALIZE")
                    && !actionText.contains("INITIALISE")
                    && !actionText.contains("REMOVE");
        }

        private Object readNamedField(Object msg, String fieldName) {
            Class<?> type = msg.getClass();
            while (type != null && type != Object.class) {
                try {
                    Field field = type.getDeclaredField(fieldName);
                    field.setAccessible(true);
                    return field.get(msg);
                } catch (NoSuchFieldException ignored) {
                    type = type.getSuperclass();
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    return null;
                }
            }
            return null;
        }

        private Object readActionLikeField(Object msg) {
            Class<?> type = msg.getClass();
            while (type != null && type != Object.class) {
                for (Field field : type.getDeclaredFields()) {
                    if (!field.getName().toLowerCase(java.util.Locale.ROOT).contains("action")) {
                        continue;
                    }
                    try {
                        field.setAccessible(true);
                        return field.get(msg);
                    } catch (ReflectiveOperationException | RuntimeException ignored) {
                        return null;
                    }
                }
                type = type.getSuperclass();
            }
            return null;
        }
    }
}
