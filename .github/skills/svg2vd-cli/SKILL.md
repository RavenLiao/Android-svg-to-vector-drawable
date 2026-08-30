---
name: svg2vd-cli
description: Use the svg2vd CLI to convert SVG assets to Android VectorDrawable XML or render SVG/VectorDrawable XML to PNG. Use when a user asks to run svg2vd, convert Android vector assets, render previews, or explicitly upgrade/download the svg2vd release.
license: Apache-2.0
---

# svg2vd CLI

Use the published svg2vd fat JAR for conversion and rendering.

## Default Behavior

Do not contact GitHub or download files for an ordinary conversion. Resolve the JAR in this order:

1. A JAR path explicitly provided by the user.
2. The `SVG2VD_JAR` environment variable.
3. A local `cli/build/libs/svg2vd-*-all.jar` in the current project.
4. A previously downloaded cache entry selected by the user.

If no local JAR is available for a requested conversion, resolve the latest published Release with `scripts/resolve_release.py` and use its verified JAR. This is on-demand availability recovery, not background update checking.

## Explicit Upgrade

When the user explicitly asks to upgrade, download, or use a release version, or when a requested conversion has no usable local JAR:

1. Run `scripts/resolve_release.py` with `--version latest` or an explicit `vX.Y.Z`.
2. The resolver downloads into the platform cache, reuses an exact cached tag, and prints JSON metadata.
3. Use the returned `jar` path. Never execute a partially downloaded file.
4. Report the selected release tag and tool version when available.

The bundled resolver uses only the Python standard library and supports Python 3.8 or newer. Ordinary use with an already available JAR does not require Python.

A normal release contains a fat JAR and needs Java 11 or newer at runtime.

## Running

For a single conversion:

```text
java -jar <path-to-svg2vd.jar> convert --input <svg> --output <directory>
```

For recursive conversion:

```text
java -jar <path-to-svg2vd.jar> convert --input <directory> --output <directory> --recursive
```

For rendering:

```text
java -jar <path-to-svg2vd.jar> render --input <svg-or-xml> --output <png> --size 64
```

After an explicit upgrade, parse the JSON returned by `resolve_release.py`, take its `jar` field, and run the normal Java command:

```text
java -jar <resolved-jar-path> convert --input <svg> --output <directory>
```

Pass CLI options after `convert` or `render` unchanged. Use `--overwrite` only when the user explicitly requests replacement. Keep paths and arguments as separate process arguments rather than interpolating an untrusted shell string.

## Result Handling

The CLI writes one JSON document to stdout and uses stable exit codes:

- `0`: success
- `2`: invalid CLI usage
- `3`: one or more requested files failed
- `4`: required environment unavailable
- `5`: unexpected internal error

Parse stdout as JSON. Treat stderr as diagnostics, not as the result. For batch operations, report successful and failed files separately; successful outputs remain valid when another input fails.

Do not bypass `unsafe_symlink` or other path-safety diagnostics. The CLI rejects unsafe symbolic-link inputs and uses atomic output writes.

## Runtime And Artifact Rules

- Require Java 11 or newer; use `SVG2VD_JAVA` when the executable is not on PATH.
- Verify a newly downloaded JAR before executing it. `SHA256SUMS` is the required integrity check.
- If the network is unavailable, use an exact verified cache entry only when the user accepts the cached version. Otherwise stop and report the error.

## References

Read [references/cli-contract.md](references/cli-contract.md) when interpreting JSON results or exit codes. Read [references/artifact-resolution.md](references/artifact-resolution.md) when downloading or upgrading a release. Read [references/troubleshooting.md](references/troubleshooting.md) when Java, paths, output, or diagnostics fail.
