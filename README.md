# ApolloSupport

[![Java](https://img.shields.io/badge/Java-8+-orange.svg)](https://adoptium.net/)
[![BungeeCord](https://img.shields.io/badge/BungeeCord-1.21--R0.4-blue.svg)](https://www.spigotmc.org/wiki/bungeecord/)
[![Gradle](https://img.shields.io/badge/Gradle-build-02303A.svg)](https://gradle.org/)
[![Author](https://img.shields.io/badge/Author-AstralStudio-purple.svg)](#)

ApolloSupport 是一个用于 BungeeCord / XCord 的代理端显示 UUID 兼容插件。

它会在不修改服务器内部玩家数据 UUID 的前提下，将客户端显示层中的离线 UUID 映射为正版 UUID，让 Lunar Client 能够按真实正版身份加载玩家饰品。

> 服务端数据仍使用原本的离线 UUID；ApolloSupport 只改写客户端能看到的显示相关数据包。

## 项目信息

| 项目 | 内容 |
| --- | --- |
| 插件名 | `ApolloSupport` |
| 作者 | `AstralStudio` |
| 主类 | `moe.illusory.ApolloSupportPlugin` |
| 运行端 | BungeeCord / XCord 代理端 |
| Java | Java 8+ |
| 构建工具 | Gradle |

## 功能特性

- 解析并缓存玩家正版 UUID。
- 支持手动玩家名到正版 UUID 映射。
- 使用 Netty handler 改写客户端显示层 UUID。
- 支持 Login、Tab、Tab Remove、实体生成相关 UUID 改写。
- 不调用 `PendingConnection#setUniqueId()`。
- 不迁移、不破坏权限、背包、经济、领地等基于 UUID 的玩家数据。
- 非 Lunar 客户端会在登录宽限期后自动移除重写 handler。
- 支持 Tab 高频更新节流。
- 支持 Tab 内容去重。
- 提供详细运行状态统计。

## 工作原理

离线 / 混合模式网络里，服务器内部通常使用离线 UUID：

```text
服务器内部 UUID = 离线 UUID
```

而 Lunar Client 加载玩家真实饰品时需要识别正版 UUID：

```text
Lunar Client 识别 UUID = 正版 UUID
```

ApolloSupport 会维护映射：

```text
离线 UUID -> 正版 UUID
```

当代理向客户端发送显示相关包时，插件会把包里的离线 UUID 临时改写成正版 UUID。这样 Lunar Client 看到的是正确的正版身份，饰品加载仍然由 Lunar Client 自己完成。

## 改写范围

ApolloSupport 只处理必要的显示相关包，不会扫描所有包内容。

| 类型 | 处理内容 |
| --- | --- |
| Login | `LoginSuccess` UUID |
| Tab | `PlayerListItem` UUID |
| Tab | `PlayerListItemUpdate` UUID |
| Tab | `PlayerListItemRemove` UUID |
| Entity | 实体生成包中的 UUID |

## 安装

1. 构建或获取插件 jar。
2. 将 jar 放入代理端插件目录。

```text
plugins/ApolloSupport-1.0.0.jar
```

3. 启动或重启代理端。
4. 修改生成的配置文件。

```text
plugins/ApolloSupport/config.yml
```

5. 使用命令重载。

```text
/apollosupport reload
```

## 配置

默认配置示例：

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

### `packet-rewrite.enabled`

是否启用显示 UUID 包改写。关闭后插件只会解析 UUID，不会修复 Lunar 饰品显示。

### `tab-update-throttle-millis`

普通 `PlayerListItemUpdate` 的最短放行间隔，单位毫秒。设置为 `0` 表示关闭节流。

### `tab-update-dedupe`

是否启用 Tab 内容去重。当普通 `PlayerListItemUpdate` 的内容和上一条已放行更新完全一致时，插件会直接丢弃重复包。

### `player-entity-filter`

实验选项，默认关闭。只有确认当前代理 / 协议版本的玩家实体 type id 后才建议开启。

### `manual-mappings`

手动映射玩家名到正版 UUID。

```yml
manual-mappings:
  Qlickly_: "6ec148d8-a1dc-4827-ad4b-409a40ee86d5"
```

支持带横杠和不带横杠的 UUID 格式。

## 命令

| 命令 | 说明 |
| --- | --- |
| `/apollosupport status` | 查看解析、重写、Tab、实体相关统计。 |
| `/apollosupport reload` | 重载配置。 |

别名：

```text
/asupport
/lunarcosmetics
```

权限：

```text
apollosupport.admin
```

## 状态统计

| 字段 | 说明 |
| --- | --- |
| `packets` | 成功改写过 UUID 的包数量。 |
| `uuids` | 成功改写的 UUID 数量。 |
| `loginPackets` | 成功改写的登录包数量。 |
| `tabPackets` | 成功改写的 Tab 包数量。 |
| `tabItems` | 已处理的 Tab item 数量。 |
| `tabUpdateDeduped` | 被内容去重丢弃的 Tab update 数量。 |
| `tabUpdateThrottled` | 被节流丢弃的 Tab update 数量。 |
| `entities` | 成功改写的实体生成 UUID 数量。 |
| `entityTypeSkipped` | 被实验实体 type 过滤跳过的实体包数量。 |
| `last` | 最近一次成功改写的对象包类名。 |

## 构建

Windows：

```bat
.\gradlew.bat build
```

Git Bash / Linux / macOS：

```bash
./gradlew build
```

构建产物：

```text
build/libs/ApolloSupport-1.0.0.jar
```

## 故障排查

### Lunar 饰品不显示

确认配置：

```yml
packet-rewrite:
  enabled: true
```

然后查看：

```text
/apollosupport status
```

### Tab 刷新太频繁

可以启用或调整：

```yml
packet-rewrite:
  tab-update-dedupe: true
  tab-update-throttle-millis: 1000
```

### Tab 不更新或显示异常

关闭 Tab 降压功能：

```yml
packet-rewrite:
  tab-update-dedupe: false
  tab-update-throttle-millis: 0
```

然后执行：

```text
/apollosupport reload
```

### 实体饰品消失

保持实验实体过滤关闭：

```yml
packet-rewrite:
  player-entity-filter:
    enabled: false
```

## 注意事项

- ApolloSupport 是代理端插件，不是 Spigot / Paper 后端插件。
- 更新 jar 不会覆盖已经生成的 `plugins/ApolloSupport/config.yml`。
- 如果新增配置项没有生效，请手动补充到旧配置文件中。
- 插件只负责显示层 UUID 兼容，不等同于完整正版 UUID 数据迁移。
