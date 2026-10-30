# 构建与源码组织

[文档目录](../README.md)

## 构建

1.21.11 使用 Java 21；1.21.1 同样使用 Java 21，并显式指定目标：

```text
gradlew remapJar
gradlew remapJar -PmcTarget=1.21.1
```

1.20.1 的生产代码使用 Java 17。使用 Java 21 启动 Gradle，并安装 Java 17 工具链：

```text
gradlew remapJar -PmcTarget=1.20.1
```

26.x 使用 Java 25，并指定目标，例如：

```text
gradlew jar -PmcTarget=26.1.2
```

标准产物位于 `lib`，例如 `vss-0.3.6-fabric-1.21.11.jar`。文件名不追加临时修复后缀，构建标识和源码摘要写入 JAR 清单。

## 标准产物

当前八目标候选包的构建标识为 `fabric-optional-20261030`，包含可选模组适配与 Tectonic 非默认预设修复。包内生产源码 SHA-256 为 `3b724bccaab22db2f06e7ef09573b198e351cdad428b8d7a04b44dfe1ce412d9`；该摘要使用构建脚本约定的生产输入集合计算，不等同于 Git 提交号。发布时逐目标核对生产源码、实际字节码、版本和五平台原生库，`lib/SHA256SUMS.txt` 列出各标准 JAR 和合包的校验值。

`lib/vss-0.3.6.zip` 按 Minecraft 版本分目录收纳构建，附带构建清单和源码摘要。该合包只包含本仓库的 Fabric 版本。

## 源码组织

- `src/main`：共享调度、网络、缓存、预测核心，以及 1.21.11 实现。
- `src/versions/mc120`：1.20.1 的网络通道、世界生成与渲染接口适配。
- `src/versions/mc121`：1.21.1 的世界生成、载荷与渲染接口适配。
- `src/versions/mc26`：26.1 系列及后续版本的差异实现。
- `src/versions/mc262`：26.2 开始的差异实现。
- `src/versions/mc263`：26.3 的世界生成与渲染差异实现。
- `tools/rust/vss-native-core`：共用的 Rust 原生计算与 JNI 接口。

构建时，同一路径的类只选择一个实现，优先使用更具体的版本目录。不能用扩大元数据版本范围的方式跳过接口验证。

## 构建与验收边界

每个目标分别执行生产编译、模块与迁移回归、实际依赖接口检查、专用服务器启动和缓存验证。客户端启动、GPU 小场景与实际游戏画面分别记录；缺少外部输入的可选测试显式记为跳过。工具入口见仓库的 `build.gradle`、`.github/workflows/` 和 `tools/verification/`，不要用扩大版本声明代替目标验证。

可选模组验证使用固定发布文件，下载工具校验平台、版本、模组身份及文件摘要。设置对应 JDK 的 `JAVA_HOME` 后执行：

```text
python tools/verification/optional_matrix.py --minecraft 1.21.1
```

运行器先编译目标，再准备真实输入并执行 `optionalCompatTest`。旧版 Fabric 输入同时保留原始包与离线映射副本，Mixin 挂钩分别检查生产方法名和实际应用结果。1.21.1 的 FreeTerraForged 与旧 ReTerraForged 分两轮验证。没有对应发行文件或没有 DFC 模块的目标按不适用记录；提供了实际输入却未执行、关键行为检查缺席或出现未解释跳过时，验证失败。

下载内容、日志、输入摘要和独立 XML 留在 `external/optional-compat/<目标版本>`，不提交第三方文件。八目标 CI 使用相同入口。依赖版本和范围见 [可选模组适配表](../compatibility/optional-mods.md)。

Rust 由 [原生工具目录](../../tools/rust/README.md)维护；第三方输入遵守上游分发要求，不纳入源码仓库。构建身份和源码摘要放入 JAR 清单，标准文件名不追加临时后缀。固定构建的历史验证见 [阶段记录](../history/validation.md)。
