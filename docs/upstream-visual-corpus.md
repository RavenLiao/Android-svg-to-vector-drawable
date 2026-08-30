# Upstream Visual Corpus

The release JAR is checked against static VectorDrawable fixtures from one immutable Android Studio commit. The corpus is deliberately separate from `upstream-scope.yaml`: it is a test input, not a production-source input and does not alter the engine fingerprint.

`corpus.lock.json` is a canonical UTF-8 JSON document with a final LF. It records the accepted stable tag, annotated tag object, peeled commit, materialized corpus manifest SHA-256, every asset identity, and every paired SVG/XML-to-PNG case with its exact `render_size`. Do not edit it by hand or create an empty/fixture lock. It is only written after an actual fixed-commit materialization succeeds and its canonical manifest hash agrees with the lock.

For a PR, CI first runs `:upstream-sync:discoverLockedCandidate` from this lock. That operation never reads Gitiles refs; it reads only the locked tag identity and the current checked-in source/dependency inputs, then writes an external immutable candidate manifest and material sidecar under `${RUNNER_TEMP}`. CI synchronizes the corpus from that candidate, hashes `manifest.json`, and rejects a mismatch with the lock before using the release JAR.

The standard CI workflow runs this discovery once on Ubuntu and uploads the immutable candidate as an artifact. The Linux, macOS, and Windows verification jobs download that same candidate instead of repeating the Gitiles requests. Gradle distributions and dependency caches remain outside the repository; locally they may be grouped by setting `GRADLE_USER_HOME` to an ignored `.cache/gradle` directory.

Gradle runs on JDK 17 or newer; the release JAR is invoked with a separately installed Java 11 executable. The Linux job materializes and compares the full locked PNG corpus. macOS and Windows run only the committed minimal corpus to prove the Java 11 JAR process contract. The scheduled `Upstream Update Check` workflow creates a PR for the newest stable tag and increments the tool patch version. After that PR or a manual version bump reaches `main`, the `Release` workflow publishes the next `vX.Y.Z` tag only after the matching CI run succeeds.

When a network environment can reach the fixed commit reliably, create the lock outside the checkout:

```bash
./gradlew :upstream-sync:discoverCandidate \
  -PcandidateTag=studio-2026.1.2 \
  -PcandidateOutputDirectory=/private/tmp/svg2vd-candidate \
  --dependency-verification strict
./gradlew :upstream-sync:syncCorpus \
  -PcandidateManifest=/private/tmp/svg2vd-candidate/<manifest-sha>.json \
  -PcorpusOutputDirectory=/private/tmp/svg2vd-corpus \
  --dependency-verification strict
```

Create the lock only through the checked writer, which rechecks canonical manifest bytes, materialization-root identity, every asset hash, and every case before accepting the fixed target:

```bash
./gradlew :upstream-sync:writeCorpusLock \
  -PcorpusManifest=/private/tmp/svg2vd-corpus/<manifest-sha>/manifest.json \
  -PcorpusLockOutput="$PWD/corpus.lock.json" \
  --dependency-verification strict
```

The corpus download reads the fixed Git tree and one fixed directory archive, then proves each extracted file's Git blob object ID and SHA-256 against that tree before materializing it. Preserve the candidate manifest and its `.materials` sidecar as the audit input. A Gitiles HTTP 429, timeout, hash mismatch, malformed archive/PNG, or any missing asset is a failure, not a reason to substitute a partial lock.
