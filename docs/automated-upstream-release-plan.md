# Automated Upstream And Release Plan

This document defines the target operating model for a tool that changes rarely while its Android Studio upstream changes regularly. Automation may prepare and validate changes, but it must never publish an unverified or ambiguous artifact.

## Goals

- Check `platform/tools/base` stable tags daily.
- Preserve the exact upstream tag object, peeled commit, source identity, corpus hash, and dependency closure.
- Create one deterministic update PR for the newest stable tag (intermediate tags are intentionally skipped).
- Increment the tool patch version for an upstream-only update.
- Run the complete cross-platform CI gate before merging or releasing.
- Automatically merge a generated update PR only after its exact CI run succeeds.
- Automatically create the immutable tool Git tag and GitHub Release after successful CI on `main`.
- Keep the tool version and Android Studio version separate.

## Version Contract

The tool version is the SemVer value in `gradle.properties`. The tool Git tag is derived from it: `svg2vdVersion=0.1.1` becomes `v0.1.1`.

Android Studio remains a separate upstream identity:

- Tool tag: `v0.1.1`
- Upstream tag: `studio-2026.1.3`
- JAR: `svg2vd-0.1.1-studio-2026.1.3-all.jar`

The upstream tag is recorded in `corpus.lock.json`, the JAR name, and `provenance.json`; it is not embedded in the tool Git tag. For an upstream-only update, automation increments only `z` (`0.1.0 -> 0.1.1`). Invalid, decreasing, or human-conflicting versions fail closed. Minor and major changes remain manual.

The version utility must parse exactly one `svg2vdVersion` assignment with `X.Y.Z` numeric components, preserve unrelated file bytes and line endings, and update only the value. It must reject duplicate assignments, prerelease/build suffixes, and ambiguous comments.

## Upstream Discovery

The scheduled workflow runs once per day. It calls the existing Gitiles client and stable-tag classifier, then selects the greatest accepted stable version after the current `corpus.lock.json` anchor.

Accepted tags are `studio-X.Y.Z` and historical `studio-X.Y.Z-patchN` spellings. Alpha, beta, canary, RC, legacy, malformed, and ambiguous tags are rejected or ignored according to the existing classifier. Numeric tuple ordering is mandatory; string ordering is not allowed.

When no newer stable tag exists, the workflow exits successfully without creating a branch, PR, commit, or release.

## Generated Update PR

For the newest tag, the discover job performs `discoverCandidate -> syncCandidate / syncCorpus -> writeCorpusLock` outside the checkout, then validates canonical lock, manifest hashes, and materialization identities. The candidate manifest and a trusted metadata sidecar (including its SHA-256 and engine fingerprint) travel together; PR text is generated only from that sidecar, never guessed from the lock.

The generated branch may change exactly `corpus.lock.json` and `gradle.properties`. The next version is calculated from the `main` base and must be exactly the old patch version plus one. The workflow fails if any other file changes or if a human changed the version concurrently.

The branch name is deterministic, for example `automation/upstream-studio-2026.1.3`. A rerun force-updates that branch from `origin/main`, so at most one PR exists for a given upstream tag. The PR body records the upstream tag, tag object, peeled commit, engine fingerprint, corpus manifest hash, and generated tool version.

The read-only discovery job uploads only the generated lock/version bundle. The proposal job has only the permissions needed to push the bot branch, create/update the PR, and dispatch CI. No secrets are passed to source compilation or tests.

## CI Gate And Auto-Merge

Linux full-corpus, macOS minimal-corpus, Windows minimal-corpus, Java 11 runtime, unit-test, and artifact checks remain mandatory. The update workflow explicitly dispatches CI for the bot branch because `GITHUB_TOKEN` pushes do not reliably create another workflow event.

The proposal job waits for the exact dispatched run ID identified by branch and commit. It may merge only when the run is successful, the commit is still the PR head, the two-file allowlist holds, and the PR is open and conflict-free. The merge call must include `--match-head-commit <verified-sha>` to prevent a time-of-check/time-of-use race. If branch protection or required approvals block the merge, the PR remains open; repository policy must explicitly allow bot auto-merge for this generated branch before full automation is enabled. No release is attempted from the PR branch.

## Automatic Release

Release is driven by a successful CI `workflow_run` on `main`, not by a tag-triggered second workflow. This avoids relying on recursive workflow events after a bot creates a tag.

The release workflow verifies that the completed run is CI with event `push`, branch `main`, and conclusion `success`; that its commit is an ancestor of `origin/main`; and that `gradle.properties` contains a valid version. Every checkout, tag target, provenance field, and comparison uses `github.event.workflow_run.head_sha`, never the workflow runner's default `GITHUB_SHA`. The workflow compares the current version with the first parent version and releases when the version increased. A no-change initial release is allowed only when the repository has no existing `vX.Y.Z` release tags; ordinary commits with an unchanged version after the first release are skipped. `v<tool-version>` must either be absent or point to the same commit.

The read-only build job creates the locked candidate and produces `svg2vd-X.Y.Z-studio-A.B.C-all.jar`, `SHA256SUMS`, and `provenance.json`.

Provenance records the tool version, tool source commit, upstream tag and peeled commit, engine fingerprint, corpus lock SHA-256, and hashes of business artifacts. Metadata files are not recursively hashed into their own provenance. The write-only publish job first verifies or creates the `vX.Y.Z` tag at the exact head SHA, then creates the Release. Tag creation and Release creation are not atomic: an orphan tag is retained and a retry must verify its target before continuing. If a Release already exists, it verifies the tag target and the complete expected asset set (including rejecting unexpected business assets), then succeeds only when all bytes match; it never overwrites a mismatch.

## Permissions And Concurrency

- Discovery/test jobs: `contents: read`; `actions: write` only for explicit CI dispatch.
- Proposal job: `contents: write`, `pull-requests: write`, and `actions: write` only for branch/PR/dispatch operations.
- Release build job: `contents: read`, `actions: read`.
- Release publish job: `contents: write` only.
- No write token is exposed to compilation or tests.
- Update and release workflows use concurrency groups with cancellation disabled.

The update workflow should use a GitHub App or fine-grained token for branch pushes when repository policy requires normal pull-request events. Explicit `workflow_dispatch` remains the fallback.

Repository settings must also protect `v*` tags with a ruleset that prevents deletion or force-moving an existing release tag. Workflow checks detect accidental mismatches, while the ruleset enforces immutability at the Git server.

The release-state verifier treats the GitHub Release tag (`vX.Y.Z`) and the upstream tag (`studio-X.Y.Z`) as two distinct identities. It verifies the release Git tag target against `provenance.tool_source_commit`, then uses `provenance.upstream_tag` for stable-version ordering. Legacy releases without `upstream_tag` remain readable only when their release tag itself is an accepted `studio-*` tag; new releases must always include the field.

## Failure And Recovery Rules

| Failure | Result |
| --- | --- |
| Gitiles timeout, 429, 5xx, malformed refs | No branch or PR change; scheduled run reports evidence |
| Candidate, corpus, hash, or canonical-lock failure | No branch or PR change |
| Unexpected generated diff | Proposal fails closed |
| CI failure | PR remains open; no merge or release |
| Stale/conflicting PR | No force merge; next run re-evaluates from `main` |
| Existing tag/release matches exactly | Idempotent success |
| Existing tag/release differs | Fail closed; never overwrite |

Automation never deletes a branch, PR, tag, or release as recovery.

## Implementation Stages

1. Add a tested SemVer utility that increments only `z`.
2. Add a generated-update contract enforcing the two-file allowlist and PR metadata.
3. Refactor the update workflow to calculate the next patch, create/update the deterministic PR, dispatch the exact CI run, and merge only after success.
4. Refactor release triggering to successful CI on `main`, split read-only build and write-only publish jobs, and retain idempotent asset verification.
5. Add tests for version increments, stale heads, duplicate releases, provenance, permissions, and path handling.
6. Update CI and corpus tests to derive the JAR path from the configured project version rather than hard-coded `0.1.0`.
7. Run local tests, YAML/static checks, and a no-update dry run. Enable the schedule first; enable auto-merge and automatic release only after the first generated PR is inspected.

## Acceptance Criteria

- A daily no-update run makes no repository change.
- A new stable tag produces one PR changing only the lock and patch version; if several tags appeared, the newest accepted tag is used and intermediate tags are skipped.
- The PR cannot merge or publish without exact cross-platform CI success.
- A successful merge creates exactly one `vX.Y.Z` tag and Release.
- Release artifacts identify both tool and Android Studio versions and verify offline.
- Rerunning any workflow is safe and deterministic.
