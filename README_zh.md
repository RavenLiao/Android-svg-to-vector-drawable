# Android SVG to VectorDrawable

其他语言：[English](README.md)

`svg2vd` 是一个 JSON 优先的命令行工具，用于将 SVG 资源转换为 Android VectorDrawable XML，并将 SVG 或 VectorDrawable XML 渲染为 PNG。它从固定的 Android Studio 上游源码候选重新构建转换引擎，因此每次构建都可以追溯到不可变的 Android Studio tag、commit、源码身份和依赖闭包。

它面向自动化 Android 资源导入：编辑器、CI 或 AI Agent 只需调用一条命令、读取一份 JSON，并根据稳定的退出码判断结果。

## 环境要求

- 使用 JDK 17 或更高版本运行 Gradle 构建。
- 使用 Java 11 运行产出的 fat JAR。

产物 JAR 的目标版本是 Java 11，运行时不需要安装 Android Studio。Gradle 构建需要 JDK 17 或更高版本；视觉验证使用独立的 Java 11 运行时来确认产物的最低兼容版本。

## 构建

`corpus.lock.json` 记录已接受的 Android Studio 源码 commit。先基于它生成外部、不可变的候选输入，再构建 JAR：

```bash
CANDIDATE_DIR="$(mktemp -d)"
./gradlew :upstream-sync:discoverLockedCandidate \
  -PcorpusLock="$PWD/corpus.lock.json" \
  -PcandidateOutputDirectory="$CANDIDATE_DIR" \
  --dependency-verification strict

CANDIDATE_MANIFEST="$(find "$CANDIDATE_DIR" -maxdepth 1 -type f -name '*.json' -print -quit)"
./gradlew :cli:shadowJar \
  -PcandidateManifest="$CANDIDATE_MANIFEST" \
  --dependency-verification strict
```

可执行文件为 `cli/build/libs/svg2vd-0.1.0-all.jar`。Gradle 项目版本由 `gradle.properties` 中的 `svg2vdVersion` 配置，发布构建时可用 `-Psvg2vdVersion=<版本>` 覆盖。

## 使用方式

每次正常调用都会仅向 stdout 输出一份 JSON 文档及一个结尾换行；stderr 保持为空。这一约定适用于无人值守的调用方。

将单个 SVG 转换到 Android 资源目录：

```bash
java -jar cli/build/libs/svg2vd-0.1.0-all.jar \
  convert --input assets/icon.svg --output app/src/main/res/drawable
```

递归转换一个资源目录：

```bash
java -jar cli/build/libs/svg2vd-0.1.0-all.jar \
  convert --input assets/icons --output app/src/main/res/drawable --recursive
```

将 SVG 或 VectorDrawable XML 渲染为 PNG 预览：

```bash
java -jar cli/build/libs/svg2vd-0.1.0-all.jar \
  render --input app/src/main/res/drawable/icon.xml --output build/icon.png --size 64
```

`--overwrite` 可覆盖已有输出。`convert` 还支持重复传入 `--input`，以及 `--width-dp`、`--height-dp`、`--add-aosp-header`。执行 `java -jar cli/build/libs/svg2vd-0.1.0-all.jar <command> --help` 会得到 JSON 格式的用法说明。

## 机器调用契约

每份结果都包含 `schema_version`、`command`、`outcome`、逐文件结果和诊断信息。退出码是稳定契约：

| 退出码 | 含义 |
| --- | --- |
| `0` | 成功 |
| `2` | CLI 参数或用法无效 |
| `3` | 一个或多个请求文件失败 |
| `4` | 必需运行环境不可用 |
| `5` | 未预期的内部错误 |

批量转换会保留已成功生成的输出，在 JSON 中报告所有文件；只要任一请求输入失败，进程就返回 `3`。

## 上游与 CI 验证

视觉语料是测试数据，与生产引擎范围分离。`corpus.lock.json` 固定 Android Studio `studio-2026.1.2`；物化后的语料包含 470 个静态资源，以及 231 个可渲染的 SVG/XML 到 PNG 用例。同步器读取固定 Git tree 和固定目录归档，并在使用前验证每个解压文件的 Git blob ID 与 SHA-256。

GitHub Actions 使用 JDK 17 构建，并以独立的 Java 11 可执行程序验证产物兼容性。Linux 运行完整视觉语料；macOS 和 Windows 运行仓库内提交的最小语料契约。图像比较器、语料运行器和审计产物均只属于测试，不会被打进 fat JAR。CI 始终以 headless 模式运行，不会打开图形窗口。

维护与更新 lock 的流程见 [docs/upstream-visual-corpus.md](docs/upstream-visual-corpus.md)。`Upstream Update Check` 每天检查新的 Android Studio 稳定 Tag，自动递增工具 patch 版本，重新生成 `corpus.lock.json` 并创建更新 PR。PR 或手工版本更新进入 `main` 后，`Release` workflow 会等待对应的 CI 成功，自动创建不可变的 `vX.Y.Z` Tag，并发布 JAR、`SHA256SUMS` 和 `provenance.json`。工具版本与 Android Studio 版本独立，后者会记录在产物名称和 provenance 中。
