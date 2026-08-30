# Android SVG to VectorDrawable

Other Language: [中文](README_zh.md)

`svg2vd` is a JSON-first command-line tool for converting SVG assets to Android VectorDrawable XML and rendering SVG or VectorDrawable XML to PNG. It rebuilds the Android Studio conversion engine from a fixed upstream source candidate, so every build is traceable to an immutable Android Studio tag, commit, source identity, and dependency closure.

It is intended for automated Android resource import. An editor, CI job, or AI agent runs one command, reads one JSON document, and determines the outcome from a stable exit code.

## Requirements

- JDK 17 or newer to run Gradle builds.
- Java 11 to run the produced fat JAR.

The JAR targets Java 11 and does not require Android Studio at runtime. Gradle requires JDK 17 or newer, while the separate Java 11 runtime used by the visual gate verifies the minimum supported JAR runtime.

## Build

`corpus.lock.json` identifies the accepted Android Studio source commit. Create an external immutable candidate from that lock, then build the JAR:

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

The executable is `cli/build/libs/svg2vd-0.1.0-all.jar`. The Gradle project version is configured by `svg2vdVersion` in `gradle.properties` and can be overridden with `-Psvg2vdVersion=<version>` for a release build.

## Usage

Every normal invocation writes exactly one JSON document followed by a final newline to stdout. Stderr is empty. This contract is designed for unattended callers.

Convert one SVG into an Android resource directory:

```bash
java -jar cli/build/libs/svg2vd-0.1.0-all.jar \
  convert --input assets/icon.svg --output app/src/main/res/drawable
```

Convert an asset tree:

```bash
java -jar cli/build/libs/svg2vd-0.1.0-all.jar \
  convert --input assets/icons --output app/src/main/res/drawable --recursive
```

Render an SVG or VectorDrawable XML file to a PNG preview:

```bash
java -jar cli/build/libs/svg2vd-0.1.0-all.jar \
  render --input app/src/main/res/drawable/icon.xml --output build/icon.png --size 64
```

Use `--overwrite` to replace an existing output. `convert` also accepts repeated `--input`, `--width-dp`, `--height-dp`, and `--add-aosp-header`. Use `java -jar cli/build/libs/svg2vd-0.1.0-all.jar <command> --help` to obtain a JSON usage response.

## Agent Skill

This repository includes the portable `svg2vd-cli` Agent Skill under `.github/skills/svg2vd-cli`. It teaches AI coding agents to run the CLI, parse its JSON contract, and handle safe output paths. It does not contact GitHub or upgrade the tool during ordinary conversions; downloading a Release is performed only after an explicit upgrade request.

With GitHub CLI 2.90.0 or newer, preview and install it with:

```bash
gh skill preview RavenLiao/Android-svg-to-vector-drawable svg2vd-cli
gh skill install RavenLiao/Android-svg-to-vector-drawable svg2vd-cli
```

## Machine Contract

Each result includes `schema_version`, `command`, `outcome`, per-file results, and diagnostics. Exit codes are stable:

| Exit code | Meaning |
| --- | --- |
| `0` | Success |
| `2` | Invalid CLI usage |
| `3` | One or more requested files failed |
| `4` | Required environment is unavailable |
| `5` | Unexpected internal error |

Batch conversion preserves outputs already produced for successful inputs, reports every file in JSON, and returns `3` when any requested input fails.

## Upstream And CI Verification

The visual corpus is test data, separate from the production engine scope. `corpus.lock.json` pins Android Studio `studio-2026.1.2`; the materialized corpus contains 470 static assets and 231 renderable SVG/XML-to-PNG cases. The synchronizer reads a fixed Git tree and a fixed directory archive, then verifies every extracted file's Git blob ID and SHA-256 before use.

GitHub Actions builds with JDK 17 and invokes the release JAR with a separate Java 11 executable for compatibility verification. Linux runs the full visual corpus; macOS and Windows run the committed minimal corpus contract. The image comparator, corpus runner, and audit artifacts are test-only and are not packaged in the fat JAR. CI is headless and never opens a graphical window.

For maintenance and lock refresh, see [docs/upstream-visual-corpus.md](docs/upstream-visual-corpus.md). The `Upstream Update Check` workflow checks for newer accepted stable Android Studio tags daily, increments the tool patch version, and opens an update PR after regenerating `corpus.lock.json`. Once the PR or a manual version bump reaches `main`, the `Release` workflow waits for the matching successful CI run, creates the immutable `vX.Y.Z` tag, and publishes the JAR, `SHA256SUMS`, and `provenance.json`. The release version is independent from the Android Studio version, which is recorded in the artifact name and provenance.
