# 使用与缓存

[文档目录](../README.md)

- 服务端真实列读取、预生成、持久缓存、传输、取消与脏列更新。
- 客户端 Java / Rust 地形、生物群系、植被和结构预测，精细体素缓存及近处优先调度。
- 预测和真实数据共同接入 Voxy 构网、细化、遮挡与绘制；真实数据优先，预测不写入 Voxy 的真实持久缓存。
- 世界、维度与种子身份隔离，以及真实列捕获和预测交接。

JAR 内包含 Windows x64、Linux x64 / ARM64、macOS Intel / Apple Silicon 的 Rust 库。Linux 库以 glibc 2.28 为基线，macOS 库以 11.0 为基线；这些是原生库的构建要求，Minecraft、Java 和图形驱动仍有各自的系统要求。

## 配置与预测

服务端配置位于 `config/vss-server-config.json`，客户端配置位于 `config/vss-client-config.json`。直接编辑前关闭相应进程；已有配置保留保存值。

真实区块自动生成的每玩家并发分开设置：单人游戏（含开放局域网）使用 `singleplayerGenerationConcurrencyLimit`，默认 `2`；独立服务器使用 `generationConcurrencyLimitPerPlayer`，默认 `4`。已有服务器自定义值保留；旧配置缺少单人字段时使用新默认值。显式 `/vss chunky` 任务沿用自身额度。

| 设置 | 默认值 | 作用 |
| --- | --- | --- |
| `enablePrediction` | `true` | 预测总开关，功能标记为 Beta |
| `enablePredictionLoading` | `true` | 是否继续加载新预测；关闭后已有数据仍可显示 |
| `predictionDistanceBlocks` | `4096` | 预测距离，单位方块 |
| `predictionSpyglassLoading` | `true` | 望远镜额外加载 |
| `rememberTerrain` | `true` | 保存并恢复本地预测结果 |

预测使用同步的世界生成信息，由 Rust / Java 计算；真实数据优先，画面交给 Voxy 构网与绘制。Voxy 适配不可用时不能用旧独立渲染器补位。缺少必需生成快照或路由时，该维度的预测不能冒充已兼容。

## 命令与排错

`/vss` 显示服务端帮助，需要管理员权限 2；`/vssclient` 显示客户端帮助，不需要管理员权限。`/vssclient stats details` 查看世界生成快照、预测瓦片与队列。真实列预生成使用 `/vss chunky`，具体参数以当前目标的 `/vss help chunky` 和游戏内补全为准。

加载慢时先区分服务端生成、真实列传输、预测采样和 Voxy 构网队列。确认两端使用同次构建、客户端依赖匹配，再查看服务端与客户端状态。只提高预测距离或并发不能说明瓶颈已经解决。

## 缓存与准确性

真实列缓存和预测缓存按世界、维度与种子隔离；各 Minecraft 目标使用独立协议和列格式。兼容记录优先恢复，生成规则修复通过成品缓存身份区分旧结果；正常升级不要求删除整个缓存。预测不写入 Voxy 的真实持久缓存。

预测是生成规则的近似，实际玩家修改、部分模组后处理及未验证生成内容以服务端真实列为准。接口和算法检查不代表任意整合包、地图模组或光影包均已验收，具体范围见 [兼容矩阵](../compatibility/README.md)和[世界生成适配](../compatibility/worldgen.md)。
