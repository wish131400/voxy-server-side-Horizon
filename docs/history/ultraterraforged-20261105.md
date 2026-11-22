# UltraTerraForged 多版本兼容验证（2026-11-05）

[当前适配范围](../compatibility/ultraterraforged.md) · [历史目录](README.md)

本轮将 UTF 1.0.3 接入已有的地形快照、精确单元读取及预测上下文生命周期。各目标使用独立的 Minecraft API 与实际发布文件；没有把旧 FTF 的 Rust 滤波布局套到 UTF，也没有降低 UTF 原生滤波的计算量。

## Fabric 验证矩阵

| Minecraft | UTF 输入 | 聚焦测试 | 额外架构检查 |
| --- | --- | --- | --- |
| 1.20.1 | 实际 Fabric 1.0.3 发布包 | 15 通过 | 本轮未单独重跑 |
| 1.21.1 | 实际 Fabric 1.0.3 发布包 | 15 通过 | 12 通过 |
| 1.21.11 | 无对应独立发布包 | 14 通过，1 项真实依赖测试跳过 | 本轮未单独重跑 |
| 26.1 | 无对应独立发布包 | 14 通过，1 项真实依赖测试跳过 | 本轮未单独重跑 |
| 26.1.1 | 无对应独立发布包 | 14 通过，1 项真实依赖测试跳过 | 本轮未单独重跑 |
| 26.1.2 | 实际 Fabric 1.0.3 发布包 | 15 通过 | 本轮未单独重跑 |
| 26.2 | 实际 Fabric 1.0.3 发布包 | 15 通过 | 本轮未单独重跑 |
| 26.3 | 实际 Fabric 1.0.3 发布包 | 15 通过 | 12 通过 |

八目标聚焦检查共 120 项，117 通过，3 项因没有对应的 UTF 发行输入跳过，无失败。它们覆盖 UTF 真实发布测试、世界生成能力边界、服务端变体快照，以及线程上下文在正常返回和异常返回时的恢复。另在 Fabric 1.21.1 分别运行实际 FTF 1.0.0、RTF 0.0.6005、FTF 1.0.0023 的原有兼容测试，各 2 项通过；未将这一聚焦矩阵表述为八目标全部模块测试。

## 实际发行代码的检查内容

`UltraTerraForgedReleasedTest` 使用对应发布 JAR，普通 JUnit 环境仅隔离 Fabric 注册表分配。它保留发布版预设、噪声 codec、初始化、瓦片生成和完整滤波算法。

- 使用真实默认预设、完整噪声注册表和 RegistryOps，经 VSS 服务端编码及客户端解码后逐条对照噪声输出。
- 执行发布版 RandomState Mixin 的初始化方法，分别对照原始注册表和网络往返后的注册表，避免只用一个手工 GeneratorContext 代替初始化链路。
- 检查精细高度、温度和侵蚀字段，在非四格整数坐标及多个高度上与同一滤波瓦片精确一致；26.3 走其密度编译接口。
- 检查 UTF 单参数 `CellField.read(Cell)` 不读取 `WorldLookup.getHeightmap()`，不跨线程捕获可变高度图。
- 按发布版 RaisedWater 规则检查水位、海平面阈值和水面顶面坐标。
- 关闭一个世界的缓存后，检查另一个世界的轮询及负坐标瓦片生成继续可用；UTF 的弱引用缓存管理不依赖旧全局 CACHES 字段。
- 对仍提供气候出生点接口的发布版本检查上下文复制；26.3 没有这一可变气候接口，不虚构该接口。

真实依赖测试输入通过 `tools/verification/remap_worldgen_test_jar.py` 准备。1.x 仅为普通 JUnit 将发布包映射为 Mojang 名称，26.x 保留发布字节码；转换文件不作为模组分发。

## 固定原件摘要

| 实际 Fabric 发布文件 | SHA-256 |
| --- | --- |
| ultraterraforged-1.0.3+1.20.1-fabric.jar | `91ebf263d18a37127f2a0fdb025206c0cd29e6e01eeca33e70c91272133dd6cb` |
| ultraterraforged-1.0.3+1.21.1-fabric.jar | `d03ca383e6a94df1b4e3ee189e2b37e02ddfcb43854c7c0159a0006ddc5b11e8` |
| ultraterraforged-1.0.3+26.1.2-fabric.jar | `eb65c6c2e14f1d55869531b7a7efe7f5eb978bf7b532eb588104ce0188d2e00c` |
| ultraterraforged-1.0.3+26.2-fabric.jar | `32c982b32ff8cf827b2b4609b318cd05ab269f3cb4343bb65d4854b45bf54dfe` |
| ultraterraforged-1.0.3+26.3-fabric.jar | `c247fe3f8fcb277c39ae16fd0790d14bbdd9772a8fd070b77d31f27363bf5c2d` |
| freeterraforged-1.0.0-fabric-1.21.1.jar | `243007e5f7aee168e4b31fae75f38e5261dc156c392cdd03cad5326376a112d5` |
| reterraforged-0.0.6005-fabric-1.21.1.jar | `0eb0baa327050412bb169ac50fd8b2efbd39684b3d846dd612adb44f0d4fb60e` |
| ftf-unstable-1.0.0023+mc1.21.1-fabric.jar | `cb096318dc5d72997fa62e9cfb3654755b087cffffaad23002960f071bffaa51` |

## 验证边界

本轮没有启动完整游戏、执行多人联机或比较最终远景画面，没有据此承诺加载时间、帧率提升或上游所有地表后处理逐方块一致。最终标准 JAR 的构建标识、源码快照、字节码与校验值由统一发布流程继续验证；本记录描述兼容源码及其离线回归。
