# 安装与升级

[文档目录](../README.md)

- 服务端安装对应 Minecraft 版本的 Fabric Loader、Fabric API 和 VSS。专用服务器不需要 Voxy。
- 客户端安装对应版本的 Fabric Loader、Fabric API、VSS，以及 Voxy 要求的依赖。远景显示需要 Voxy。
- Mod Menu 为可选依赖，用于打开配置界面。
- 光影兼容按对应版本的 Iris / Voxy 实际接口验证，不以模组名称相同代替兼容检查。
- 服务端与客户端使用匹配的 VSS 协议。旧 Forge / NeoForge 的列体和世界生成格式不能与本移植版混用。
- 本轮世界生成快照修复需要联机服务端与客户端一起更新；单人游戏会随同一个 VSS JAR 更新两端。
- 八个 Minecraft 目标的协议和列缓存格式也分别隔离，升级目标时不要混用旧列缓存。
- 未列出的 Voxy 社区分支、世界生成模组、地图模组和光影包没有获得本矩阵的兼容结论，保留的可选适配入口不等于已完成实测。

## 选择安装包

从 `lib/` 选择与 Minecraft 目标对应的标准 JAR，例如 `vss-0.3.6-fabric-1.21.1.jar`。合包 `vss-0.3.6.zip` 仅包含本仓库的 Fabric 版本，按游戏版本分目录；外部 Voxy、Sodium、Iris 和 Fabric API 需按 [依赖矩阵](../compatibility/README.md)另行安装。

1.20.1 的 VSS 服务端使用 Java 17，部分社区 Voxy 客户端要求 Java 21；以依赖矩阵中的具体组合为准。1.21.1 / 1.21.11 使用 Java 21，26.x 使用 Java 25。

升级前关闭游戏或服务器，移除旧 VSS JAR。联机两端同步更新，不混用不同 Minecraft 目标的协议和列缓存。世界生成快照修复会使用独立成品缓存身份，旧格式按可用性恢复，无需因正常升级清空整个存档或缓存。

配置和开关见 [使用说明](usage.md)，候选包验证范围见 [阶段验证](../history/validation.md)。
