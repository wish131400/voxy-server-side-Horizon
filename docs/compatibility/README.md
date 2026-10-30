# 版本与依赖

[文档目录](../README.md)

本页记录固定发布文件或源码提交的兼容范围。每个 Minecraft 目标使用独立的 VSS JAR；发布文件、源码构建、接口验证与实际游玩分别判断。

每个 Minecraft 版本单独构建、单独验证，使用对应的 JAR。以下是接口检查和客户端启动使用的依赖组合；版本号相同但 Minecraft 目标不同的文件仍需按对应目标选择。

| Minecraft | Java | Fabric API | Voxy | Sodium | Iris |
| --- | --- | --- | --- | --- | --- |
| 1.20.1 | VSS 17；DA-57EVE 基线客户端 21 | 0.92.12+1.20.1 | 社区 0.2.14-alpha | 0.5.13 | 1.7.6 |
| 1.21.1 | 21 | 0.116.17+1.21.1 | 社区 0.2.14-alpha（固定源码构建） | 0.6.13 | 1.8.8 |
| 1.21.11 | 21 | 0.141.6+1.21.11 | 0.2.16-beta | 0.8.12+mc1.21.11 | 1.10.8+mc1.21.11 |
| 26.1 | 25 | 0.145.1+26.1 | 0.2.15-beta | 0.8.9+mc26.1.1 | 1.10.9+mc26.1.1 |
| 26.1.1 | 25 | 0.145.4+26.1.1 | 0.2.15-beta | 0.8.9+mc26.1.1 | 1.10.9+mc26.1.1 |
| 26.1.2 | 25 | 0.155.3+26.1.2 | 0.2.18-beta | 0.9.1+mc26.1.2 | 1.11.4+mc26.1.2 |
| 26.2 | 25 | 0.161.0+26.2 | 0.2.19-beta | 0.9.2+mc26.2 | 1.11.4+mc26.2 |
| 26.3 | 25 | 0.165.0+26.3 | 0.2.20-beta | 0.9.3-beta.1+mc26.3 | 1.11.7+mc26.3 |

Fabric Loader 最低版本为 0.20.1。26.1 行中的 Sodium / Iris 文件自身同时声明支持 26.1，不能只按文件名后缀判断兼容。

1.20.1 使用 [DA-57EVE 的 Fabric 社区发布](https://github.com/DA-57EVE/voxy-1.20.1-Fabric/releases/tag/mod)。该 Voxy 文件自身使用 Java 21；VSS 的 1.20.1 服务端仍以 Java 17 编译和启动。1.21.1 使用 [m3t4f1v3/voxy 的 mc_1211 分支固定提交](https://github.com/m3t4f1v3/voxy/tree/f308c254fafc23bdcc250ba3f1e5c6a7585f08d0)进行本地构建和接口验证，该依赖不是上游正式发布包。本仓库及合包均不包含这两个 Voxy 文件，需遵守上游的分发要求。

另外为以下社区分支增加独立兼容路径。同一行的 Voxy、Sodium 和 Iris 必须配套；尤其是 1.21.1 的 Sodium 0.8 回移植不能搭配只接受 Sodium 0.6 的官方 Iris 1.8.8。下面的回移植 Iris 由 wu-bao 仓库的固定依赖提供，不能仅按版本号寻找同名文件。每个客户端只安装一个 Voxy。

| Minecraft | 社区 Voxy 与固定版本 | Sodium | Iris |
| --- | --- | --- | --- |
| 1.20.1 | [mihile](https://github.com/mihile/voxy/releases/tag/v0.2.20-1.20.1-port.9) 0.2.20 port.9，`82188bda` 发布包 | 0.5.13 | 1.7.6 |
| 1.20.1 | [m3t4f1v3 multiversion](https://github.com/m3t4f1v3/voxy/tree/42d5037c19e6a540c4b69fd4156be0487f961663)，固定源码构建 | 0.5.13 | 1.7.6 |
| 1.21.1 | 同上 m3t4f1v3 multiversion 固定提交 | 0.8.12-alpha.3+mc1.21.1 | 1.8.14-beta.1+mc1.21.1 回移植 |
| 1.20.1 | [tkettela server-lod](https://github.com/tkettela/unofficial-backport-voxy/tree/db2371278a7926d222b8346eddb359e967e47435)，固定源码构建 | 0.5.13 | 1.7.6 |
| 1.21.1 | [wu-bao](https://github.com/wu-bao/voxy/releases/tag/v0.2.15-beta-1.21.1-fabric-backport) 0.2.15 发布包，包内提交 `aab0ab95` | 0.8.12+mc1.21.1 | 1.8.14-beta.1+mc1.21.1 回移植 |
| 1.21.1 | [water-fix](https://github.com/mayjhon69669-sys/voxy-water-fix/releases/tag/v0.2.19-mc1.21.1-leaf-tint-fog-fade-test) 0.2.19 leaf-tint-fog-fade 测试发布包 | 0.8.12+mc1.21.1 | 1.8.14-beta.1+mc1.21.1 回移植 |

源码构建保留上游 Java、着色器和依赖声明，只固定构建身份并选择对应 Fabric 目标；它们不等于上游正式发布。wu-bao 的发布包内提交与发布标签对应源码不同，water-fix 发布包没有有效提交标识，因此按实际文件摘要验收。固定依赖工具保留来源和完整校验值；兼容结论仅覆盖这些输入，不自动覆盖各分支后续提交。VSS 安装包不包含上述第三方模组。

1.20.1 的 mihile port.9 发布包使用 Java 17 字节码，m3t4f1v3 与 tkettela 固定源码构建使用 Java 21；VSS 自身的 1.20.1 服务端保持 Java 17。上述六种组合共用对应 Minecraft 版本的 VSS JAR，不需要为每个 Voxy 分支安装单独的 VSS 文件。

本轮适配显式 far 参数的投影接口、不同任务队列的完成回执与邻居平面读取，并避免 water-fix 的旧水面高度校正重复生效。tkettela 原生中间变量形式的模型缩进和液面高度也由该分支保留，VSS 不再叠加一次修正。tkettela 分支在 VSS 会话中由 VSS 负责真实列同步，停止该玩家的重复原生请求与发送；无 VSS 会话时保留原生传输。整列原生任务成功完成后再接管预测，包含真实空气；丢弃、失败、旧会话及旧时间戳的迟到回包不能获得完整列确认或覆盖较新快照。

tkettela 此固定提交还存在上游配置初始化顺序缺陷：新配置或 `preset=auto` 可在原生 `ServerLodConfig` 初始化时访问尚未赋值的 `PRESETS`。客户端的 Voxy 渲染和 VSS 传输可继续工作，但不能据此宣称该提交自带的原生 LOD 服务端已可用。本轮未修改第三方 JAR；VSS 专用服务器无需安装 Voxy，不依赖该配置。保留原生协议兼容入口与实际验证该上游服务端分别记录。

## 世界生成与验证范围

[Fabric 可选模组适配](optional-mods.md)记录饰品、FTB、Create、树木模板与实际发行包的适用范围。

[世界生成模组适配](worldgen.md)记录快照、TerraBlender、BetterEnd 与 Lithostitched 的接口边界；[验证记录](../history/validation.md)列出生产构建、实际依赖、GPU、客户端与服务器验证范围。共享适配代码或标题画面启动不能代替整合包实机验收。
