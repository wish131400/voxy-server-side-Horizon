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

标准产物位于 `lib`，例如 `vss-0.3.7-fabric-1.21.11.jar`。文件名不追加临时修复后缀，构建标识和源码摘要写入 JAR 清单。

编译依赖与运行下限分别维护：`loader_version` 和目标表中的 `api` 固定常规构建依赖；`loader_min_version`、各目标可选的 `loaderMin` 和 `apiMin` 写入安装包的最低依赖声明。不要将编译版本直接用作运行下限。

Loader 的编译基线同时约束 Mixin 注解格式：新版 `Redirect.at` 的数组编码不能直接供较旧的 MixinExtras 读取。1.x 的 Loader 编译版本固定为 0.17.3，较新目标使用各自的 Loader 基线；发布校验拒绝遗留的新数组编码，避免只放宽声明却在初始化时崩溃。降低下限时，使用真实最低依赖重新编译对应目标，例如：

```text
gradlew compileJava validateAccessWidener -PmcTarget=1.21.1 -PfabricCompatibilityBaseline=true
```

CI 对每个目标执行最低依赖编译，再使用固定编译依赖运行原有回归和打包。发布校验核对最终 JAR 的依赖声明与目标表一致。Minecraft 目标和 Java 要求继续分别维护；最低依赖编译不替代生产客户端启动与实机验收。

## 标准产物

依赖兼容更新 `fabric-dependency-baseline-20261030` 的旧派生包及记录保存在历史归档中；对应最低依赖编译和旧版启动范围见[阶段验证](../history/validation.md)。

当前八目标候选包的构建标识为 `20261102-modern-generation`，包含此前兼容修复，并修复 26.x 树木环境准入与 26.3 GPU 成本选择，按群系专门化材质规则和按需噪声输入。八目标均由源码重新构建，使用本轮重新编译并审查的五平台原生库。生产源码摘要记录在 JAR 清单和 `lib/manifest.json`；该摘要不等同于 Git 提交号。发布时逐目标核对生产源码、实际字节码、版本和五平台原生库，`lib/SHA256SUMS.txt` 列出各标准 JAR 和合包的校验值。

`lib/vss-0.3.7.zip` 按 Minecraft 版本分目录收纳构建，附带构建清单和源码摘要。该合包只包含本仓库的 Fabric 版本。

本轮原生源码摘要与五平台文件校验值记录在包内元数据，成品核对实际 GPU 调用链、现代装饰缓存身份及公共修复。Windows 完整生成复测与 Linux / macOS 交叉编译分别记录；其他平台硬件和游戏内帧率没有执行验证。26.3 首轮完整路径改善约 1.8%–4.0%，仍未追平 NeoForge 1.21.1，见[本轮记录](../history/performance/modern-generation-repair-20261102.md)。此前 FreeTerraForged 实际发行包检查保留其[阶段边界](../history/validation.md#freeterraforged-fabric-新旧运行时兼容2026-10-31)，不自动计作本轮重新执行。

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

仅调整已发布文件的依赖兼容性时，`tools/verification/update_fabric_dependency_metadata.py` 可在独立目录派生产物。它校验原包清单，只更新依赖、构建派生记录和单元素 `Redirect.at` 编码；保留原代码构建身份、方法指令与原生库，单独记录配置与原包摘要到 `dependency-snapshot.json`。这种派生检查不计作当前工作树的完整重新构建，也不能用于交付正在开发中的算法改动。
