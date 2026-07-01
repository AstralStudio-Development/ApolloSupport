package moe.illusory.protocol;

import moe.illusory.UuidMappingService;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

public final class PrecisePacketUuidRewriter {
    private final UuidMappingService uuidMappingService;
    private final BooleanSupplier rewriteLoginSuccessEnabled;
    private final Map<Class<?>, PacketPlan> planCache = new ConcurrentHashMap<>();
    private final AtomicLong rewrittenUuidCount = new AtomicLong();
    private final AtomicLong loginPackets = new AtomicLong();
    private final AtomicLong tabPackets = new AtomicLong();
    private final AtomicLong tabItems = new AtomicLong();
    private volatile String lastPacketClass = "<none>";

    public PrecisePacketUuidRewriter(
            UuidMappingService uuidMappingService,
            BooleanSupplier rewriteLoginSuccessEnabled
    ) {
        this.uuidMappingService = uuidMappingService;
        this.rewriteLoginSuccessEnabled = rewriteLoginSuccessEnabled;
    }

    public boolean rewrite(Object packet) {
        if (packet == null || !uuidMappingService.hasOfflineMappings()) {
            return false;
        }

        Class<?> packetClass = packet.getClass();
        String name = packetClass.getName();
        if (!isTargetPacket(name)) {
            return false;
        }
        if (name.endsWith(".LoginSuccess") && !rewriteLoginSuccessEnabled.getAsBoolean()) {
            return false;
        }

        try {
            PacketPlan plan = planCache.computeIfAbsent(packetClass, this::buildPlan);
            if (plan == PacketPlan.EMPTY) {
                return false;
            }

            RewriteStats stats = new RewriteStats();
            plan.rewrite(packet, stats);
            if (stats.changed) {
                lastPacketClass = name;
                rewrittenUuidCount.addAndGet(stats.uuidChanges);
                if (plan.kind() == PacketKind.LOGIN) {
                    loginPackets.incrementAndGet();
                } else if (plan.kind() == PacketKind.TAB) {
                    tabPackets.incrementAndGet();
                    tabItems.addAndGet(stats.itemVisits);
                }
            }
            return stats.changed;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public String lastPacketClass() {
        return lastPacketClass;
    }

    public long rewrittenUuidCount() {
        return rewrittenUuidCount.get();
    }

    public long loginPackets() {
        return loginPackets.get();
    }

    public long tabPackets() {
        return tabPackets.get();
    }

    public long tabItems() {
        return tabItems.get();
    }

    private boolean isTargetPacket(String className) {
        return className.endsWith(".LoginSuccess")
                || className.endsWith(".PlayerListItem")
                || className.endsWith(".PlayerListItemUpdate")
                || className.endsWith(".PlayerListItemRemove");
    }

    private PacketPlan buildPlan(Class<?> type) {
        String name = type.getName();
        try {
            if (name.endsWith(".LoginSuccess")) {
                return new UuidFieldPlan(field(type, "uuid"));
            }
            if (name.endsWith(".PlayerListItemRemove")) {
                return new UuidArrayPlan(field(type, "uuids"));
            }
            if (name.endsWith(".PlayerListItem") || name.endsWith(".PlayerListItemUpdate")) {
                Field items = field(type, "items");
                Class<?> component = items.getType().getComponentType();
                if (component == null) {
                    return PacketPlan.EMPTY;
                }
                return new PlayerListItemsPlan(items, field(component, "uuid"));
            }
        } catch (ReflectiveOperationException ignored) {
            return PacketPlan.EMPTY;
        }
        return PacketPlan.EMPTY;
    }

    private Field field(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null && current != Object.class) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }

    private UUID rewriteUuid(UUID original) {
        if (original == null) {
            return null;
        }
        UUID rewritten = uuidMappingService.premiumByOfflineOrNull(original);
        return rewritten == null ? original : rewritten;
    }

    private interface PacketPlan {
        PacketPlan EMPTY = new PacketPlan() {
            @Override
            public void rewrite(Object packet, RewriteStats stats) {
            }

            @Override
            public PacketKind kind() {
                return PacketKind.OTHER;
            }
        };

        void rewrite(Object packet, RewriteStats stats) throws IllegalAccessException;

        PacketKind kind();
    }

    private enum PacketKind {
        LOGIN,
        TAB,
        OTHER
    }

    private final class UuidFieldPlan implements PacketPlan {
        private final Field uuidField;

        private UuidFieldPlan(Field uuidField) {
            this.uuidField = uuidField;
        }

        @Override
        public void rewrite(Object packet, RewriteStats stats) throws IllegalAccessException {
            rewriteUuidField(packet, uuidField, stats);
        }

        @Override
        public PacketKind kind() {
            return PacketKind.LOGIN;
        }
    }

    private final class UuidArrayPlan implements PacketPlan {
        private final Field uuidsField;

        private UuidArrayPlan(Field uuidsField) {
            this.uuidsField = uuidsField;
        }

        @Override
        public void rewrite(Object packet, RewriteStats stats) throws IllegalAccessException {
            Object array = uuidsField.get(packet);
            if (array == null || !array.getClass().isArray()) {
                return;
            }
            int length = Array.getLength(array);
            stats.itemVisits += length;
            for (int i = 0; i < length; i++) {
                Object value = Array.get(array, i);
                if (!(value instanceof UUID)) {
                    continue;
                }
                UUID original = (UUID) value;
                UUID rewritten = rewriteUuid(original);
                if (!rewritten.equals(original)) {
                    Array.set(array, i, rewritten);
                    stats.changed = true;
                    stats.uuidChanges++;
                }
            }
        }

        @Override
        public PacketKind kind() {
            return PacketKind.TAB;
        }
    }

    private final class PlayerListItemsPlan implements PacketPlan {
        private final Field itemsField;
        private final Field itemUuidField;

        private PlayerListItemsPlan(Field itemsField, Field itemUuidField) {
            this.itemsField = itemsField;
            this.itemUuidField = itemUuidField;
        }

        @Override
        public void rewrite(Object packet, RewriteStats stats) throws IllegalAccessException {
            Object items = itemsField.get(packet);
            if (items == null || !items.getClass().isArray()) {
                return;
            }
            int length = Array.getLength(items);
            stats.itemVisits += length;
            for (int i = 0; i < length; i++) {
                Object item = Array.get(items, i);
                if (item != null) {
                    rewriteUuidField(item, itemUuidField, stats);
                }
            }
        }

        @Override
        public PacketKind kind() {
            return PacketKind.TAB;
        }
    }

    private void rewriteUuidField(Object target, Field field, RewriteStats stats) throws IllegalAccessException {
        Object raw = field.get(target);
        if (!(raw instanceof UUID)) {
            return;
        }
        UUID original = (UUID) raw;
        UUID rewritten = rewriteUuid(original);
        if (!rewritten.equals(original)) {
            field.set(target, rewritten);
            stats.changed = true;
            stats.uuidChanges++;
        }
    }

    private static final class RewriteStats {
        private boolean changed;
        private int uuidChanges;
        private int itemVisits;
    }
}
