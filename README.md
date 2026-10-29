# Voxy Server Side Horizon

VSS 的独立 Fabric 移植版本。服务端读取、生成、缓存并同步真实远景列；客户端使用 Rust / Java 预测远处地形、完整植被和结构。真实数据与预测体素共同接入 Voxy 构网、细化与渲染，真实数据优先。

[文档目录](docs/README.md) · [版本与依赖](docs/compatibility/README.md) · [更新日志](CHANGELOG.md) · [当前计划](PLAN.md)

## 版本

| Minecraft | VSS 运行环境 | 客户端依赖 |
| --- | --- | --- |
| 1.20.1 | Java 17；部分社区 Voxy 客户端要求 Java 21 | 固定社区 Voxy 与配套 Sodium / Iris |
| 1.21.1 | Java 21 | 固定社区 Voxy 与配套 Sodium / Iris |
| 1.21.11 | Java 21 | Voxy 0.2.16-beta 与对应依赖 |
| 26.1 / 26.1.1 / 26.1.2 / 26.2 / 26.3 | Java 25 | 每个目标对应的 Voxy 与依赖 |

Fabric Loader 最低 0.20.1，每个目标还需对应的 Fabric API。每个 Minecraft 版本独立构建，安装包不能跨目标或加载器混用。完整版本号、固定发布包与源码提交见 [兼容矩阵](docs/compatibility/README.md)。

## 安装与使用

1. 服务端安装 Fabric Loader、对应 Fabric API 和 VSS；专用服务器不需要 Voxy。
2. 客户端安装对应 Loader、Fabric API、VSS、Voxy 及其依赖；只安装一个 Voxy。Mod Menu 为可选配置入口。
3. 从 `lib/` 选择对应目标的标准 JAR，联机两端使用同次构建。合包 `vss-0.3.6.zip` 按版本分目录，仅包含 Fabric 版本。

[安装与升级](docs/user-guide/installation.md) · [设置、命令与缓存](docs/user-guide/usage.md) · [世界生成适配](docs/compatibility/worldgen.md)

当前为候选移植版，八目标已完成生产构建、回归、实际依赖接口与专服检查。社区组合的图形、启动和世界流程按阶段分别记录，实际整合包、连续游玩与具体光影包的画面和帧时间仍需实机确认。验证范围见 [阶段记录](docs/history/validation.md)。

## 开发与来源

[构建与源码组织](docs/development/build.md) · [架构](docs/development/architecture.md) · [历史计划与验证](docs/history/README.md)

本仓库基于 VSS 0.3.6 继续开发。[Forge 1.20.1](https://github.com/wish131400/voxy-server-side-forge) 和 [NeoForge 1.21.1](https://github.com/wish131400/voxy-server-side-neoforge) 独立维护，安装包与协议不能直接混用。

保留上游 VSS、Voxy 及相关作者信息。项目使用 [MIT 许可证](LICENSE)，原生库和第三方依赖见 [第三方许可证](src/main/resources/META-INF/THIRD_PARTY_LICENSES.md)。外部模组 JAR 和 Minecraft 文件不纳入源码仓库。
