# Android SVG to VectorDrawable

Other Language: [中文](README_zh.md)

## What It Does

`svg2vd` converts SVG assets to Android VectorDrawable XML and renders SVG or VectorDrawable XML to PNG.

It is designed for:

- Android resource conversion
- Batch and recursive asset processing
- Headless CI and editor integrations
- AI coding agents that need a stable JSON result

The published fat JAR includes the conversion engine and does not require Android Studio at runtime.

## Agent Skill

This repository includes the portable `svg2vd-cli` Agent Skill at `.github/skills/svg2vd-cli`. It teaches compatible AI agents how to select the CLI command, preserve safe file handling, parse JSON results, and report failures.

When a usable local JAR exists, ordinary use of the Skill does not contact GitHub or check for updates. If the requested conversion has no local JAR, the Skill can download the latest verified Release on demand; explicit version requests are also supported.

With GitHub CLI 2.90.0 or newer, preview the Skill before installing it:

```bash
gh skill preview RavenLiao/Android-svg-to-vector-drawable svg2vd-cli
gh skill install RavenLiao/Android-svg-to-vector-drawable svg2vd-cli
```

To update installed Skills:

```bash
gh skill update
```

The Skill is compatible with GitHub Copilot, Codex, Claude Code, and VS Code Agent Mode. Always preview a Skill before installation; its scripts run in the local agent environment.

## Quick Start

Download a JAR from the [latest Release](https://github.com/RavenLiao/Android-svg-to-vector-drawable/releases/latest), then use Java 11 or newer.

Convert one SVG:

```bash
java -jar svg2vd-0.1.0-studio-2026.1.2-all.jar \
  convert --input assets/icon.svg --output app/src/main/res/drawable
```

Render an SVG or VectorDrawable XML to PNG:

```bash
java -jar svg2vd-0.1.0-studio-2026.1.2-all.jar \
  render --input app/src/main/res/drawable/icon.xml --output build/icon.png --size 64
```

On Windows PowerShell, use the same command with PowerShell line continuation:

```powershell
java -jar .\svg2vd-0.1.0-studio-2026.1.2-all.jar `
  convert --input .\assets\icon.svg `
  --output .\app\src\main\res\drawable
```

Ask an AI agent:

```text
Use svg2vd to recursively convert assets/icons to app/src/main/res/drawable.
Do not overwrite existing files, and report failed inputs after the run.
```

## Common Commands

Convert a directory recursively:

```bash
java -jar <svg2vd.jar> convert \
  --input assets/icons --output app/src/main/res/drawable --recursive
```

Useful `convert` options:

- `--overwrite`: replace existing outputs; use only when intended
- `--width-dp <n>` and `--height-dp <n>`: request dimensions
- `--add-aosp-header`: prepend the AOSP license header
- Repeat `--input` to process multiple paths

Use `java -jar <svg2vd.jar> <command> --help` for JSON-formatted usage help.

## Output Contract

Each normal invocation writes one JSON document to stdout. Stderr is reserved for diagnostics.

| Exit code | Meaning |
| --- | --- |
| `0` | All requested work succeeded |
| `2` | Invalid CLI usage |
| `3` | One or more requested files failed |
| `4` | Required environment is unavailable |
| `5` | Unexpected internal error |

The JSON result includes `schema_version`, `command`, `outcome`, per-file results, and diagnostics. Batch operations retain successful outputs and return `3` if any requested file fails.

Minimal successful result:

```json
{"command":"convert","outcome":"success","summary":{"total":1,"succeeded":1,"failed":0}}
```

## Versions And Artifacts

Tool and upstream versions are separate:

```text
Tool version:     0.1.0
Android Studio:   studio-2026.1.2
Release JAR:      svg2vd-0.1.0-studio-2026.1.2-all.jar
```

A Release contains:

- The fat JAR
- `SHA256SUMS`
- `provenance.json`

Use `SHA256SUMS` to verify a downloaded JAR. Provenance records the upstream tag, commit, engine fingerprint, and corpus lock identity.

## Troubleshooting

- **JAR not found:** for a requested conversion, the Agent Skill can download the latest verified Release on demand; otherwise provide a local JAR.
- **Java error:** the JAR requires Java 11 or newer. JDK 17+ is required only to build this repository.
- **Existing output:** add `--overwrite` only when replacement is intended.
- **Partial failure:** inspect the JSON diagnostics; successful files remain available.
- **Unsafe symlink:** do not bypass the CLI's path-safety checks.
- **Offline upgrade:** use an exact verified cached Release only if an older version is acceptable.

## Build From Source

Building from source requires JDK 17 or newer. The produced JAR targets Java 11.

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

The default project version is `svg2vdVersion=0.1.0` in `gradle.properties`. Override it with `-Psvg2vdVersion=<version>`.

## Upstream And Release Maintenance

`corpus.lock.json` pins the exact Android Studio source candidate used by the engine and visual corpus. The daily `Upstream Update Check` workflow checks for the newest accepted stable tag, increments the tool patch version, and opens an update PR. After the PR or a manual version bump reaches `main`, the `Release` workflow waits for the matching CI success, creates `vX.Y.Z`, and publishes the artifacts.

See [docs/upstream-visual-corpus.md](docs/upstream-visual-corpus.md) and [docs/automated-upstream-release-plan.md](docs/automated-upstream-release-plan.md) for maintainer details.

## License

The project and included Skill are licensed under Apache-2.0.
