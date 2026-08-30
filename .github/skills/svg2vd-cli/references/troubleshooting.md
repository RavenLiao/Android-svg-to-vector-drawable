# Troubleshooting

## Java

The Release JAR targets Java 11. Check `java -version`; use the `SVG2VD_JAVA` path or an explicit Java executable when multiple JDKs are installed. Gradle's JDK 17 requirement applies only to building this repository.

## Missing JAR

Do not download automatically. Ask the user whether to use a local build or explicitly download a Release with `scripts/resolve_release.py`.

## Exit Code 3

This is a partial conversion failure. Parse the JSON result, list failed inputs and diagnostics, and preserve the successful outputs.

## unsafe_symlink

The tool intentionally refuses symbolic-link inputs or unsafe output paths. Do not replace the input with its target or disable the check.

## Network Failure

Use an exact verified cache entry only if the user accepts a possibly older version. Never execute an unverified or partially downloaded artifact.

## Windows

Use absolute paths and pass arguments without shell interpolation. If Java is installed but not on PATH, provide its full executable path.
