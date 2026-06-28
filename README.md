# ApolloSupport

[![Java](https://img.shields.io/badge/Java-8+-orange.svg)](https://adoptium.net/)
[![BungeeCord](https://img.shields.io/badge/BungeeCord-1.21--R0.4-blue.svg)](https://www.spigotmc.org/wiki/bungeecord/)
[![Gradle](https://img.shields.io/badge/Gradle-build-02303A.svg)](https://gradle.org/)
[![Platform](https://img.shields.io/badge/Platform-Proxy-lightgrey.svg)](#)

ApolloSupport is a proxy-side display UUID rewriter for BungeeCord / XCord servers.

It lets Lunar Client resolve players by their premium UUID for cosmetic rendering, while keeping the server-side player data UUID untouched.

> Internal player data stays on the offline UUID. Only selected outbound display packets are rewritten.

## Resources

- [Commands](#commands)
- [Configuration](#configuration)
- [Status Metrics](#status-metrics)
- [Building](#building)
- [Troubleshooting](#troubleshooting)

## Why

Offline / mixed-mode proxy networks usually identify players with an offline UUID internally. Lunar Client cosmetics, however, are resolved by the player's real premium UUID.

That means the client may see:

```text
Server-side UUID: offline UUID
Lunar profile UUID: premium UUID
```

ApolloSupport bridges this display-layer mismatch by rewriting only the UUIDs that the client sees in selected packets.

It does **not**:

- call `PendingConnection#setUniqueId()`
- migrate player data
- change permission, economy, inventory, region, or statistic ownership
- provide or spoof cosmetic data itself

Lunar Client still handles cosmetic loading. ApolloSupport only makes sure the client sees the correct player identity.

## Features

- Premium UUID resolving with local cache.
- Manual player-name to UUID mappings.
- Netty-based outbound display UUID rewriting.
- Login, Tab, Tab remove, and entity spawn UUID handling.
- Lunar-aware handler cleanup for non-Lunar clients.
- Tab update deduplication for static Tab layouts.
- Tab update throttling for high-frequency Tab plugins.
- Runtime status metrics for packet, UUID, Tab, and entity rewrite counters.
- Experimental entity type filtering.

## Packet Scope

ApolloSupport only targets display-related packets:

| Area | Packet / Object |
| --- | --- |
| Login | `LoginSuccess` |
| Tab | `PlayerListItem` |
| Tab | `PlayerListItemUpdate` |
| Tab | `PlayerListItemRemove` |
| Entity | modern Add Entity UUID field |

It does not scan every packet payload for UUID-like bytes.

## Installation

1. Build the plugin or download the jar.
2. Place it in the proxy plugin directory:

```text
plugins/ApolloSupport-1.0.0.jar
```

3. Start or restart the proxy.
4. Edit the generated config if needed:

```text
plugins/ApolloSupport/config.yml
```

5. Reload the plugin:

```text
/apollosupport reload
```

## Configuration

Default configuration:

```yml
uuid-resolver:
  enabled: true
  request-timeout-millis: 2500
  cache-minutes: 1440

packet-rewrite:
  enabled: true
  tab-update-throttle-millis: 250
  tab-update-dedupe: true
  player-entity-filter:
    enabled: false
    type-id: -1

manual-mappings: {}

debug: false
```

### UUID Resolver

```yml
uuid-resolver:
  enabled: true
  request-timeout-millis: 2500
  cache-minutes: 1440
```

Controls premium UUID lookup and name cache lifetime.

### Packet Rewrite

```yml
packet-rewrite:
  enabled: true
```

Enables outbound display UUID rewriting. If disabled, ApolloSupport will still be able to resolve UUIDs, but Lunar cosmetics will not be fixed.

### Tab Update Dedupe

```yml
packet-rewrite:
  tab-update-dedupe: true
```

Drops repeated `PlayerListItemUpdate` packets when their content fingerprint is unchanged.

This is useful for static Tab layouts where the Tab plugin sends frequent duplicate updates.

### Tab Update Throttle

```yml
packet-rewrite:
  tab-update-throttle-millis: 250
```

Limits ordinary `PlayerListItemUpdate` packets per client connection.

Set to `0` to disable throttling:

```yml
packet-rewrite:
  tab-update-throttle-millis: 0
```

For static Tab layouts, values like `1000`, `2000`, or higher may be acceptable.

### Entity Type Filter

```yml
packet-rewrite:
  player-entity-filter:
    enabled: false
    type-id: -1
```

Experimental option. Keep it disabled unless you have confirmed the player entity type id for your proxy/protocol mapping.

If this is configured incorrectly, entity cosmetics may stop showing.

### Manual Mappings

```yml
manual-mappings:
  Qlickly_: "6ec148d8-a1dc-4827-ad4b-409a40ee86d5"
```

Manual mappings support dashed and non-dashed UUID formats.

## Commands

| Command | Description |
| --- | --- |
| `/apollosupport status` | Shows resolver, rewrite, Tab, and entity metrics. |
| `/apollosupport reload` | Reloads the configuration. |

Aliases:

```text
/asupport
/lunarcosmetics
```

Permission:

```text
apollosupport.admin
```

## Status Metrics

`/apollosupport status` exposes runtime counters:

| Metric | Meaning |
| --- | --- |
| `packets` | Successfully rewritten packets. |
| `uuids` | Successfully rewritten UUID values. |
| `loginPackets` | Rewritten login packets. |
| `tabPackets` | Rewritten Tab packets. |
| `tabItems` | Visited Tab items in rewritten Tab packets. |
| `tabUpdateDeduped` | Duplicate Tab update packets dropped by content dedupe. |
| `tabUpdateThrottled` | Tab update packets dropped by throttling. |
| `entities` | Rewritten entity spawn UUIDs. |
| `entityTypeSkipped` | Entity packets skipped by experimental type filtering. |
| `last` | Last successfully rewritten object packet class. |

For high-frequency Tab plugins, watch these first:

```text
tabPackets=
tabItems=
tabUpdateDeduped=
tabUpdateThrottled=
```

## Building

Windows:

```bat
.\gradlew.bat build
```

Git Bash / Linux / macOS:

```bash
./gradlew build
```

Build output:

```text
build/libs/ApolloSupport-1.0.0.jar
```

## Troubleshooting

### Cosmetics do not show

Check:

```yml
packet-rewrite:
  enabled: true
```

Then run:

```text
/apollosupport status
```

Look for non-zero `packets`, `uuids`, and `entities` after players join or enter each other's view.

### Tab updates are too frequent

Enable or tune:

```yml
packet-rewrite:
  tab-update-dedupe: true
  tab-update-throttle-millis: 1000
```

For static Tab layouts, dedupe should usually remove most duplicate updates.

### Tab does not update correctly

Disable the experimental reductions:

```yml
packet-rewrite:
  tab-update-dedupe: false
  tab-update-throttle-millis: 0
```

Then reload:

```text
/apollosupport reload
```

### Entity cosmetics disappear

Keep this disabled unless you know the correct type id:

```yml
packet-rewrite:
  player-entity-filter:
    enabled: false
```

## Notes

- This is a proxy plugin, not a Spigot/Paper backend plugin.
- Existing generated configs are not overwritten by jar updates. Add new config keys manually when updating.
- ApolloSupport is a display-layer compatibility tool. It does not replace full premium UUID migration.
