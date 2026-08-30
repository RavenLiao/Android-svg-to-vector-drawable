# Artifact Resolution

Normal use does not query GitHub. Download or upgrade only after the user explicitly requests it.

`scripts/resolve_release.py` requires Python 3.8+ and has no third-party dependencies. The CLI requires Java 11+.

The public repository is:

`RavenLiao/Android-svg-to-vector-drawable`

The resolver selects a published, non-draft, non-prerelease Release and expects:

- exactly one JAR named `svg2vd-X.Y.Z-<build>-all.jar`;
- `SHA256SUMS`.

The required integrity check is the JAR SHA-256 recorded in `SHA256SUMS`.

Cache locations:

- Windows: `%LOCALAPPDATA%\svg2vd\cache`
- Linux/macOS: `$XDG_CACHE_HOME/svg2vd`, falling back to `~/.cache/svg2vd`

A cache entry is keyed by the immutable tool release tag. Reusing a cached tag does not perform an update check. `--refresh` is only for an explicit user-requested redownload.
