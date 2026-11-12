---
type: Guide
title: "Build, CI and release"
description: "How okf-cli is built and tested (bytecode 17 on JDK 25, the three jars and the licence files inside them), what CI runs, how a GitHub release gets its jar, checksum and build-provenance attestation, how this repo checks its own docs bundle, and where the third-party licences live."
tags: [build, ci, release, attestation, licences]
code_paths: ["build.gradle.kts", "gradle.properties", "gradle/libs.versions.toml", ".github/workflows/**", "src/main/resources/okf/version.properties", "LICENSE", "NOTICE", "third-party-licenses/**"]
---
# Build, CI and release

## Build

```bash
./gradlew build                                  # compile (bytecode 17, built on JDK 25), tests, all jars
java -jar build/libs/okf-cli-<version>-all.jar version
```

The version comes from `gradle.properties` (`1.0.0-SNAPSHOT` between releases); the release workflow passes
`-Pversion=<tag without the v>`. `okf version` prints it from `okf/version.properties`, which is expanded at
build time.

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
  - smoke-tests it again on a real Java 17 runtime against a minimal fixture repo (`index`, then a strict
    `check`), so a Java 18+ API that slips into the code fails here.
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

A release is a GitHub release with a `vX.Y.Z` tag (a pre-release such as `vX.Y.Z-alpha.1` works the same way; the
tag must be SemVer 2.0 without build metadata, i.e. no `+…` suffix).
Publishing it starts `.github/workflows/release.yml`; nothing is published to a Maven repository. The workflow
has read-only permissions, except for its second job.

1. **`build`** takes the version from the tag without the leading `v` and checks it against SemVer 2.0 (no build
   metadata), checks out the commit the release was published for and, on JDK 25, runs
   `./gradlew build -Pversion=<version>`. It then checks that `version` prints exactly `okf-cli <version>`, checks this repo's own docs bundle with the strict flags, and smoke-tests the `all` jar on
   Java 17 against a fixture repo, as in CI. It writes the SHA-256 of the jar to
   `okf-cli-<version>-all.jar.sha256` (`sha256sum` format: `<hex>  <file name>`), adds the version and the hash to
   the run summary, and uploads the jar and the checksum as a workflow artifact.
2. **`release-assets`** runs after `build`. It is the only job with `contents: write`, `id-token: write` and
   `attestations: write`, and it never runs Gradle (it has no checkout). It downloads the artifact, verifies it
   with `sha256sum -c`, creates a build-provenance attestation for the jar, and attaches
   `okf-cli-<version>-all.jar` and `okf-cli-<version>-all.jar.sha256` to the release
   (`gh release upload --clobber`).

Consumers verify a downloaded jar with:

```bash
sha256sum -c okf-cli-<version>-all.jar.sha256
gh attestation verify okf-cli-<version>-all.jar --repo sharpbitstudio/okf-cli
```

Until the first release, there are no assets: build the jar from source ([Build](#build)).

## Own docs bundle

This repo checks its own `docs/` with the code it builds, not with a released jar. The `okfIndex` / `okfCheck` /
`okfImpact` tasks in `build.gradle.kts` run `okf.cli.Main` on `sourceSets.main.runtimeClasspath`, with the
settings in `okf.yaml` (`index_command: ./gradlew okfIndex`). Two consequences:

- CI needs no previous release.
- A change to the checks is applied to these docs in the same build.

```bash
./gradlew okfIndex okfCheck okfImpact -Pokf.strict=true -Pokf.refs.strict=true -Pokf.codePaths.required=true -Pokf.impact.strict=true
```

Each `-Pokf.*` property that is set becomes one `--<option>=<value>` (an empty value is passed as such:
`-Pokf.base=` skips the impact check).

[reference.md](reference.md) carries `code_paths` on `src/main/kotlin/okf/**`, and this document on the build,
the workflows and the licence files. A change there therefore fails `okfImpact` on a branch until the matching
document is updated, or until the change is acknowledged with a commit trailer naming it:
`Docs-Impact: reference — <reason>` for a Kotlin change, `Docs-Impact: build-release — <reason>` for a change to
the build, the workflows or the licence files.

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
