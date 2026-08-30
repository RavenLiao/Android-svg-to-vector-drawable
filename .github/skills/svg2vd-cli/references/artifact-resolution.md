# Artifact Resolution

Normal use does not query GitHub. Download or upgrade only after the user explicitly requests it.

`scripts/resolve_release.py` requires Python 3.8+ and has no third-party dependencies. The CLI itself still requires Java 11+.

The public repository is:

`RavenLiao/Android-svg-to-vector-drawable`

The resolver selects a published, non-draft, non-prerelease Release and expects:

- exactly one JAR named `svg2vd-X.Y.Z-studio-A.B.C-all.jar`;
- `SHA256SUMS`;
- `provenance.json`.

The required integrity check is the JAR SHA-256 recorded in `SHA256SUMS`. The resolver also reads provenance fields when present so the AI can report the tool version and Android Studio upstream tag. Full provenance auditing is optional and should be requested explicitly.

Cache locations:

- Windows: `%LOCALAPPDATA%\svg2vd\cache`
- Linux/macOS: `$XDG_CACHE_HOME/svg2vd`, falling back to `~/.cache/svg2vd`

A cache entry is keyed by the immutable tool release tag. Reusing a cached tag does not perform an update check. `--refresh` is only for an explicit user-requested redownload.
