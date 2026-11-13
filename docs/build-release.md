---
type: Guide
title: "Build, CI and release"
description: "How okf-cli is built and tested (bytecode 17 on JDK 25, the three jars and the licence files inside them), what CI runs, how a GitHub release gets its jar, checksum and build-provenance attestation, how this repo checks its own docs bundle, how dependency updates are handled, and where the third-party licences live."
tags: [build, ci, release, attestation, licences]
code_paths: ["build.gradle.kts", "gradle.properties", "gradle/libs.versions.toml", ".github/workflows/**", ".github/dependabot.yml", "src/main/resources/com/sharpbitstudio/okf/version.properties", "LICENSE", "NOTICE", "third-party-licenses/**"]
---
# Build, CI and release

## Build

```bash
./gradlew build                                  # compile (bytecode 17, built on JDK 25), tests, all jars
java -jar build/libs/okf-cli-<version>-all.jar version
```

The version comes from `gradle.properties` (`1.0.0-SNAPSHOT` between releases); the release workflow passes
`-Pversion=<version>` with the version it was started with. `okf version` prints it from
`com/sharpbitstudio/okf/version.properties`, which is expanded at build time.

`build` produces three jars under `build/libs/`:

- the thin jar `okf-cli-<version>.jar`, which has no `Main-Class`;
- the sources jar `okf-cli-<version>-sources.jar`;
- the self-contained CLI jar `okf-cli-<version>-all.jar` (classifier `all`), which bundles the Kotlin stdlib,
  JetBrains Annotations and SnakeYAML and runs with `java -jar`.

Every jar carries this repo's `LICENSE` and `NOTICE` in `META-INF/`. The `all` jar also carries the licences of
the components it bundles, under `META-INF/licenses/<component>/…`, copied from `third-party-licenses/` (see
[Third-party licences](#third-party-licences)).

The bytecode and the compile API are pinned to Java 17, so consumers may run the jar on Java 17; the build itself
runs on JDK 25.

## CI

`.github/workflows/build.yml` runs on:

- `workflow_dispatch`;
- pushes to any branch (`dependabot/**` included);
- `pull_request`, for pull requests from forks only: a pull request from a branch of this repo already has the
  run of its push. The fork run gets no secrets; the workflow never uses `pull_request_target`.

It has two jobs:

- **`build`**:
  - builds and runs the tests;
  - smoke-tests the `all` jar on JDK 25;
  - smoke-tests it again on a real Java 17 runtime against a small fixture repo with one ADR (`index`, which
    writes the ADR index, then a strict `check`), so a Java 18+ API that slips into the code fails here.
- **`okf`** checks this repo's own `docs/` bundle (see [Own docs bundle](#own-docs-bundle)) with all strict flags
  of `check`. The impact check compares against the merge-base with a base ref:
  - on a push to `main`, the commit `main` moved from (for a newly created ref there is none, and the impact
    check is skipped); impact findings are warnings;
  - on a manual run on `main`, `origin/main`; impact findings are warnings;
  - on a push to `dependabot/**`, `origin/main`; impact findings are warnings, because Dependabot cannot add a
    `Docs-Impact:` trailer;
  - on a fork pull request, the pull request's base branch; on a push or manual run on any other branch,
    `origin/main`; impact findings are failures.

The job names `Build + test` and `OKF docs bundle check (+ docs impact)` are the status checks meant to be required
on `main`; renaming a job means updating the ruleset in the same step. On a pull request from a branch of this repo
the `pull_request` run's jobs are skipped and never carry these names, so a skipped job can never satisfy a required
check; these checks come from the branch's push run (or a manual run on the branch). On a fork pull request they come
from the `pull_request` run.

A newer push to the same branch cancels a still-running build of it, except on `main`.

## Release

A release is a GitHub release with a `vX.Y.Z` tag; a pre-release such as `vX.Y.Z-alpha.1` works the same way. The
version is SemVer 2.0 without build metadata (no `+…` suffix). Nothing is published to a Maven repository.

Releases are immutable: the repository setting "Enable release immutability" is on (like the `main` ruleset, it
is not part of this tree). Once a release is published, its tag and assets cannot change, so the workflow only
prepares a draft, and a maintainer publishes it:

1. `CHANGELOG.md` on `main` has `## <version> — <date>`, and the README quick start names that version (both
   land in the same pull request, before the run).
2. The maintainer starts `.github/workflows/release.yml` by hand on `main` (Actions, "release", "Run workflow")
   with the version, without the `v`.
3. The maintainer reviews the draft (its three assets, the jar's SHA-256 against the run summary, the target
   commit) and publishes it. Publishing creates the tag `v<version>` at the built commit.

The workflow has read-only permissions, except for its second job:

1. **`build`** checks that the run is on `main`, that the version is SemVer 2.0 without build metadata and that
   the tag `v<version>` does not exist yet. It checks out the commit the run was started on and checks that
   `CHANGELOG.md` has a section `## <version> — <date>`: a typo guard, since a mistyped version would use up a
   tag name for good. On JDK 25 it runs `./gradlew build -Pversion=<version>`. It then checks that `version`
   prints exactly `okf-cli <version>`, checks this repo's own docs bundle with the strict flags, and smoke-tests
   the `all` jar on Java 17 against a fixture repo, as in CI. It writes the SHA-256 of the jar to
   `okf-cli-<version>-all.jar.sha256` (`sha256sum` format: `<hex>  <file name>`), adds the version and the hash to
   the run summary, passes the hash on as a job output, and uploads the jar and the checksum as a workflow
   artifact.
2. **`release-assets`** runs after `build`. It is the only job with `contents: write`, `id-token: write` and
   `attestations: write`, and it never runs Gradle (it has no checkout). It downloads the artifact and verifies it
   with `sha256sum -c` and against the hash from `build`. It stops if the tag `v<version>`, or a release or draft
   for it, already exists; it checks this before attesting, so a run that stops there leaves no attestation
   behind. It then creates a build-provenance attestation for the jar with `actions/attest` (SLSA provenance),
   checks that the attestation's Sigstore bundle holds exactly one entry, and verifies the attestation against
   that bundle (predicate type, signer workflow, source ref and commit). Finally it creates the draft release
   `v<version>` targeting the built commit, with three assets: `okf-cli-<version>-all.jar`,
   `okf-cli-<version>-all.jar.sha256` and the bundle as `okf-cli-<version>-all.jar.sigstore.json`. The draft is
   marked as a pre-release when the version has a pre-release part. The job checks afterwards that exactly one
   draft for the tag exists, and it never deletes a release.

The workflow never publishes a release or creates its tag. A failed run can leave a partial draft, visible only
to maintainers (a maintainer deletes it in the UI before running again), and public provenance records (the
attestation and its Sigstore log entry). A published release is never changed; a fix is a new version. Keep the
workflow files on `main` unchanged while a release run is in flight: creating a release for a target commit
whose workflow files differ from the default branch's can need the `Workflows: write` permission, which the
workflow token cannot have. If that happens, start a new run from the current `main`. If publishing is refused
because the tag name was used by a deleted release, delete the draft and release a new version.

Consumers verify a downloaded jar with:

```bash
sha256sum -c okf-cli-<version>-all.jar.sha256
# build provenance, without a GitHub account (the bundle is the third asset):
gh attestation verify okf-cli-<version>-all.jar --repo sharpbitstudio/okf-cli \
  --bundle okf-cli-<version>-all.jar.sigstore.json \
  --signer-workflow sharpbitstudio/okf-cli/.github/workflows/release.yml --source-ref refs/heads/main
# with a GitHub login: the same check online, and the release attestation that ties the jar to the tag
gh attestation verify okf-cli-<version>-all.jar --repo sharpbitstudio/okf-cli \
  --signer-workflow sharpbitstudio/okf-cli/.github/workflows/release.yml --source-ref refs/heads/main
gh release verify-asset v<version> okf-cli-<version>-all.jar --repo sharpbitstudio/okf-cli
```

The checksum detects a damaged download. `gh attestation verify --bundle` checks against the bundle asset,
without a GitHub account, that this repo's release workflow built the jar on `main`; without `--signer-workflow`
and `--source-ref`, an attestation from any workflow of the repository would pass. With a login, the same check
runs online, and `gh release verify-asset` checks the release attestation GitHub adds on publication, which ties the
jar to the tag. The build-provenance attestation names `refs/heads/main` and the built commit as its source,
because the tag does not exist when the jar is built.

## Own docs bundle

This repo checks its own `docs/` with the code it builds, not with a released jar. The `okfIndex` / `okfCheck` /
`okfImpact` tasks in `build.gradle.kts` run `com.sharpbitstudio.okf.cli.Main` on `sourceSets.main.runtimeClasspath`,
with the settings in `okf.yaml` (`index_command: ./gradlew okfIndex`). Two consequences:

- CI needs no previous release.
- A change to the checks is applied to these docs in the same build.

```bash
./gradlew okfIndex okfCheck okfImpact -Pokf.strict=true -Pokf.refs.strict=true -Pokf.codePaths.required=true -Pokf.impact.strict=true
```

Each `-Pokf.*` property that is set becomes one `--<option>=<value>` (an empty value is passed as such:
`-Pokf.base=` skips the impact check).

[reference.md](reference.md) carries `code_paths` on `src/main/kotlin/com/sharpbitstudio/okf/**`, and this
document on the build, the workflows, the Dependabot configuration and the licence files. A change there therefore
fails `okfImpact` on a branch until the matching document is updated, or until the change is acknowledged with a
commit trailer naming it: `Docs-Impact: reference — <reason>` for a Kotlin change,
`Docs-Impact: build-release — <reason>` for a change to the build, the workflows, the Dependabot configuration or
the licence files.

## Dependency updates

Dependabot (`.github/dependabot.yml`) opens version-update pull requests every Monday, at most five open per
ecosystem: one per Gradle dependency, and one for all GitHub Actions together. Security updates, opened when GitHub
reports a vulnerable version, come outside that schedule, limit and group, and follow the same rules. Dependabot's
branches run the same CI as any other branch (see [CI](#ci)), with impact findings as warnings.

- A bump of a GitHub Action, the Gradle wrapper or a test-only dependency (JUnit) is merged once CI is green; none
  of them is part of the released jar, so it gets no `CHANGELOG.md` entry. The GitHub Actions pull request changes
  the workflow files, so it is not merged while a release run is in flight (see [Release](#release)). CI does not
  run the actions that only `.github/workflows/release.yml` uses (`actions/download-artifact`, `actions/attest`):
  read the release notes of such a bump before merging it.
- A bump of a runtime dependency is not merged as is: SnakeYAML, the Kotlin stdlib (Dependabot names that bump
  after the Kotlin Gradle plugin, `org.jetbrains.kotlin.jvm`, whose version the stdlib follows), or what they pull
  in. Dependabot cannot update `third-party-licenses/`, `NOTICE` and `CHANGELOG.md`, so a branch of this repo
  makes the same bump together with those files (see [Third-party licences](#third-party-licences)). That branch
  changes files this document covers, so its commit carries a `Docs-Impact: build-release — <reason>` trailer
  unless it also updates this document. Once it is merged, Dependabot closes its pull request.
- Dependabot pull requests are merged between other pull requests, never while one of those is open: merging moves
  `main`, which accepts a branch only when it is up to date, so every open branch then has to be rebased (a
  Dependabot branch by commenting `@dependabot rebase` on its pull request).

## Third-party licences

The `all` jar bundles the Kotlin Standard Library (Apache-2.0; it also contains third-party code under Apache-2.0,
BSD-3-Clause and BSL-1.0), JetBrains Annotations (Apache-2.0) and SnakeYAML (Apache-2.0). `third-party-licenses/`
holds their licence texts verbatim, taken from the upstream sources at the exact version the build resolves, one
directory per component; `third-party-licenses/README.md` lists each file with its version and source. The build
copies the directory into the `all` jar as `META-INF/licenses/<component>/…`, and `NOTICE` names each
component, its licence and its path in the jar.

After any bump of a runtime dependency (the Kotlin stdlib, SnakeYAML, or what they pull in), re-check this
inventory against the upstream licence README of the exact new version, and update `third-party-licenses/` and
`NOTICE` in the same change.
