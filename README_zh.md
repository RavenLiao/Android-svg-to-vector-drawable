# Android SVG to VectorDrawable

其他语言：[English](README.md)

## 项目能力

`svg2vd` 用于将 SVG 资源转换为 Android VectorDrawable XML，也可以将 SVG 或 VectorDrawable XML 渲染为 PNG。

适用场景：

- Android 资源转换
- 批量和递归处理资源目录
- 无界面 CI 和编辑器集成
- 需要稳定 JSON 结果的 AI coding agent

发布的 fat JAR 已包含转换引擎，运行时不需要安装 Android Studio。

## Agent Skill

本仓库在 `.github/skills/svg2vd-cli` 提供可移植的 `svg2vd-cli` Agent Skill，帮助兼容的 AI agent 选择 CLI 命令、安全处理文件、解析 JSON 结果并报告失败。

本地存在可用 JAR 时，Skill 的普通使用不会访问 GitHub，也不会检查更新。如果请求转换时找不到本地 JAR，Skill 可以按需下载最新的已校验 Release；用户也可以明确指定版本。

使用 GitHub CLI 2.90.0 或更高版本，建议先预览再安装：

```bash
gh skill preview RavenLiao/Android-svg-to-vector-drawable svg2vd-cli
gh skill install RavenLiao/Android-svg-to-vector-drawable svg2vd-cli
```

更新已安装的 Skill：

```bash
gh skill update
```

Skill 兼容 GitHub Copilot、Codex、Claude Code 和 VS Code Agent Mode。安装前请先预览 Skill；其中的脚本会在本地 Agent 环境执行。

## 快速开始

从[最新 Release](https://github.com/RavenLiao/Android-svg-to-vector-drawable/releases/latest) 下载 JAR，然后使用 Java 11 或更高版本。

转换单个 SVG：

```bash
java -jar svg2vd-0.1.0-studio-2026.1.2-all.jar \
  convert --input assets/icon.svg --output app/src/main/res/drawable
```

将 SVG 或 VectorDrawable XML 渲染为 PNG：

```bash
java -jar svg2vd-0.1.0-studio-2026.1.2-all.jar \
  render --input app/src/main/res/drawable/icon.xml --output build/icon.png --size 64
```

在 Windows PowerShell 中可以使用反引号换行：

```powershell
java -jar .\svg2vd-0.1.0-studio-2026.1.2-all.jar `
  convert --input .\assets\icon.svg `
  --output .\app\src\main\res\drawable
```

给 AI agent 的示例请求：

```text
使用 svg2vd 将 assets/icons 递归转换到 app/src/main/res/drawable。
不要覆盖已有文件，执行完成后报告失败的输入文件。
```

## 常用命令

递归转换一个资源目录：

```bash
java -jar <svg2vd.jar> convert \
  --input assets/icons --output app/src/main/res/drawable --recursive
```

常用 `convert` 选项：

- `--overwrite`：覆盖已有输出，仅在明确需要替换时使用
- `--width-dp <n>` 和 `--height-dp <n>`：指定尺寸
- `--add-aosp-header`：添加 AOSP 许可证头
- 重复使用 `--input` 处理多个路径

执行 `java -jar <svg2vd.jar> <command> --help` 可获得 JSON 格式的用法帮助。

## 输出契约

每次正常调用只向 stdout 输出一份 JSON 文档，stderr 用于诊断信息。

| 退出码 | 含义 |
| --- | --- |
| `0` | 所有请求均成功 |
| `2` | CLI 参数或用法无效 |
| `3` | 一个或多个请求文件失败 |
| `4` | 必需运行环境不可用 |
| `5` | 未预期的内部错误 |

结果 JSON 包含 `schema_version`、`command`、`outcome`、逐文件结果和诊断信息。批量操作会保留成功输出，只要任一请求文件失败就返回 `3`。

最小成功结果示例：

```json
{"command":"convert","outcome":"success","summary":{"total":1,"succeeded":1,"failed":0}}
```

## 版本与产物

工具版本和上游版本相互独立：

```text
工具版本：       0.1.0
Android Studio： studio-2026.1.2
Release JAR：    svg2vd-0.1.0-studio-2026.1.2-all.jar
```

Release 包含：

- fat JAR
- `SHA256SUMS`
- `provenance.json`

下载 JAR 后使用 `SHA256SUMS` 校验完整性。provenance 会记录上游 tag、commit、engine fingerprint 和 corpus lock 身份。

## 常见问题

- **找不到 JAR：** 如果用户请求执行转换，Agent Skill 可以按需下载最新的已校验 Release；否则提供本地 JAR。
- **Java 错误：** JAR 需要 Java 11 或更高版本；只有构建本仓库时才需要 JDK 17+。
- **输出已存在：** 仅在确实需要替换时添加 `--overwrite`。
- **部分失败：** 查看 JSON 中的 diagnostics，成功文件仍然保留。
- **unsafe_symlink：** 不要绕过 CLI 的路径安全检查。
- **离线升级：** 只有在接受使用旧版本时，才使用已校验的缓存 Release。

## 从源码构建

从源码构建需要 JDK 17 或更高版本，产出的 JAR 目标版本为 Java 11。

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

默认项目版本在 `gradle.properties` 中为 `svg2vdVersion=0.1.0`，可以使用 `-Psvg2vdVersion=<版本>` 覆盖。

## 上游与发布维护

`corpus.lock.json` 固定引擎和视觉语料使用的 Android Studio 源码候选。每日运行的 `Upstream Update Check` 会检查最新接受的稳定 Tag，自动递增工具 patch 版本并创建更新 PR。PR 或手工版本更新进入 `main` 后，`Release` workflow 等待对应 CI 成功，创建 `vX.Y.Z` 并发布产物。

维护者详情见 [docs/upstream-visual-corpus.md](docs/upstream-visual-corpus.md) 和 [docs/automated-upstream-release-plan.md](docs/automated-upstream-release-plan.md)。

## 许可证

项目和随附 Skill 均采用 Apache-2.0 许可证。
