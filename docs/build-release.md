---
type: Guide
title: "Build, CI and release"
description: "How okf-cli is built and tested (bytecode 17 on JDK 25, the three jars, the licence files inside them and the launcher in the thin and all jars, the network tests), what CI runs (build, e2e), how a GitHub release gets its jar, checksum and build-provenance attestation, how this repo checks its own docs bundle, how dependency updates are handled, and where the third-party licences live."
tags: [build, ci, e2e, release, attestation, licences]
code_paths: ["build.gradle.kts", "gradle.properties", "gradle/libs.versions.toml", ".github/workflows/**", ".github/dependabot.yml", "src/main/resources/com/sharpbitstudio/okf/version.properties", "LICENSE", "NOTICE", "third-party-licenses/**"]
---
# Build, CI and release

## Build

```bash
./gradlew build                                  # compile (bytecode 17, built on JDK 25), tests, all jars
java -jar build/libs/okf-cli-<version>-all.jar version
./gradlew networkTest                            # the tests that download a published release (not in build)
```

The version comes from `gradle.properties` (the next version with `-SNAPSHOT` between releases, see
[Release](#release)); the release workflow passes `-Pversion=<version>` with the version it was started with.
`okf version` prints it from `com/sharpbitstudio/okf/version.properties`, which is expanded at build time.

`build` produces three jars under `build/libs/`:

- the thin jar `okf-cli-<version>.jar`, which has no `Main-Class`;
- the sources jar `okf-cli-<version>-sources.jar`;
- the self-contained CLI jar `okf-cli-<version>-all.jar` (classifier `all`), which bundles the Kotlin stdlib,
  JetBrains Annotations and SnakeYAML and runs with `java -jar`.

Every jar carries this repo's `LICENSE` and `NOTICE` in `META-INF/`. The `all` jar also carries the licences of
the components it bundles, under `META-INF/licenses/<component>/…`, copied from `third-party-licenses/` (see
[Third-party licences](#third-party-licences)).

The thin and the `all` jar also carry the launcher, `launcher/okfw` and `launcher/okfw.cmd`, as resources under
`com/sharpbitstudio/okf/launcher/`: `okf wrapper` and `okf init` write the copy the running jar was built with
([launcher.md](launcher.md)). So a release freezes the launcher it ships, and the hermetic launcher tests run in
every build, the release build included.

The bytecode and the compile API are pinned to Java 17, so consumers may run the jar on Java 17; the build itself
runs on JDK 25.

`test` runs every test except those tagged `network`. It depends on the `all` jar and passes the tests two system
properties: `okf.allJar` (the jar, which the launcher tests run through `okfw` with a real Java) and `okf.repoRoot`
(this checkout, whose `launcher/` the tests compare with the embedded copy, and whose `action/`, `action.yml` and
`xcheck/` the GitHub Actions' tests run). The launcher tests run `okfw` with a real `sh` (`/bin/sh`; on Windows Git
for Windows' `sh.exe`) against `curl` and `java` stubs, with a `PATH` that holds only those stubs and the tools the
script may use (`curl`, `gh` and `java` stubs for `--update` and `--verify`). The Action's tests run its scripts with
the same `sh`: the selection table with an empty `PATH`, and `action/check.sh` against a stub launcher, with only
`sh`, `grep`, `tr` and `git` on the `PATH`. The xcheck action's tests run `xcheck/xcheck.sh` with the committed
launcher and this build's jar from a seeded cache, against satellites published as bare repositories: over `file://`,
and for the token over dumb HTTP with Basic auth from a local `com.sun.net.httpserver` server, with a credential
helper in the git configuration that must never be called. A `git` wrapper first on the `PATH` logs every git
command line.
`networkTest` runs only the `network` tests: `okfw` downloads the published `1.0.0` with real `curl` and runs it, and
`okfw --verify` / `okfw --update` check its build provenance with the real `gh`. `okfw --update 1.0.0` runs in a git
work tree with the real `git`: it writes the pin, the release's `okf wrapper` writes the launcher files, and after a
commit a second `--update` reports the release as already pinned and leaves the work tree clean; a download changed
by one byte fails the provenance check and leaves the pin unchanged. In a work tree that pins the previous release,
`1.0.0-rc.1`, `okfw --update 1.0.0` pins the new one and changes only the pin, since both releases carry the same
launcher files. `okfw --update 1.0.0-alpha.1`, a published release without `okf wrapper`, writes the pin and exits
3 because the launcher files could not be written. The `gh` cases are skipped where `gh` is missing or older than
2.102.0, except on GitHub Actions, where that fails them. `networkTest` is not part of `check`; the `e2e` workflow
runs it.

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

`.github/workflows/e2e.yml` runs on the same events, with the same skip of the `pull_request` run for a branch of
this repo and its own concurrency group. Its jobs are not required checks:

- **`Launcher (<os>)`** on `ubuntu-latest`, `macos-latest` and `windows-latest`: `./gradlew build networkTest`, so the
  whole test suite, the launcher tests included, runs under dash, macOS `/bin/sh` and Git for Windows' `sh.exe`
  (and `okfw.cmd` under `cmd`, Windows PowerShell and pwsh), and the real download and provenance check of the
  published release run on each system. The log shows `java -version` and `gh --version` of the runner.
- **`Launcher (release-like version)`** on `ubuntu-latest`: `./gradlew build -Pversion=0.0.0-e2e.1`, so the tests
  whose result depends on whether the jar is a release (it then writes the pin itself) run as in a release build,
  and `shellcheck -s sh` on `launcher/okfw` and the GitHub Actions' scripts `action/*.sh` and `xcheck/*.sh`.
- **`Action (<os>)`** on `ubuntu-latest`, `macos-latest` and `windows-latest`: the GitHub Action of the commit
  (`uses: ./`, [github-actions.md](github-actions.md)) against fixture repositories. The job first checks that the
  image's own Java (before `actions/setup-java`, found as the launcher finds it) is 17 or later. It builds the
  `all` jar as `0.0.0-e2e.1`, whose `okf init` writes a fixture bundle with a pin naming that jar, and seeds the
  action's cache with it. With explicit `base` and `impact-strict` inputs, so the result does not depend on the
  event, the action passes on the clean bundle (and its `version` output names the pinned version), fails on a
  branch that changes code under a document's `code_paths` without updating it, and passes on that branch with
  `impact-strict: 'false'`, so the failure is the impact finding's. It also passes on the clean bundle with `base`
  and `impact-strict` at `auto` for whatever event started the run: on a push to `main` the selected base is this
  repository's previous `main` commit, which the fixture does not have, so the run exercises the CLI's
  unreachable-base path (a non-strict skip with a warning annotation); on other events it exercises the real
  selection (for a fork pull request the job pushes the fixture's `main` as the pull request's base branch, so
  `origin/<base branch>` exists). The job also checks that the event names the default branch. A third fixture,
  with an `origin/main`, pins the published `1.0.0`, which the action downloads from the release, checks
  with `verify-provenance` (`okfw --verify` with the runner's `gh`) and runs `check` and `impact` with. Then, with
  that jar removed from the cache, the same fixture runs through the action as a consumer references it,
  `uses: sharpbitstudio/okf-cli@<commit>` with the commit `1.0.0` was built from, so the runner downloads the
  action from this repository instead of using the checkout; it downloads the release again. After each release the
  step moves to that release's commit, by hand (see [Dependency updates](#dependency-updates)). On `ubuntu-latest`
  the job also runs the release workflow's attestation check (see [Release](#release)) as written there, tokenless,
  on that release's jar and Sigstore bundle: it passes with the commit the release was built from and fails with its
  parent.
- **`xcheck action`** on `ubuntu-latest`: the xcheck action of the commit (`uses: ./xcheck`,
  [github-actions.md](github-actions.md#the-xcheck-action)). It builds the `all` jar as `0.0.0-e2e.1`, whose
  `okf init` writes a fixture hub with a pin naming that jar (the action's cache is seeded with it), and fills a
  workspace with two fixture satellites. With `satellites: ''` the action clones nothing: it passes on the clean
  federation (its `exit-code` output is `0` and no report exists), and fails on a broken cross-repo reference with
  `exit-code` `1` and the report at its `report` output. One more run clones this repository anonymously
  (`token: ''`) into an empty workspace, which shows that the clone settings (credential helpers off, no prompts)
  do not get in the way of a public clone; `okf xcheck` then stops with a setup error (`exit-code` `2`), since the
  hub's satellites are missing there. A further run repairs the satellite and pins the hub to the published
  `1.0.0` with no cached jar, so the action downloads the release: it passes (`exit-code` `0`, no report) and
  leaves the jar in the cache with the release's SHA-256. The last run repeats that, with the cached jar removed,
  through the xcheck action as a consumer references it, `uses: sharpbitstudio/okf-cli/xcheck@<commit>` with the
  commit `1.0.0` was built from (moved like the Action's consumer step above).

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

After a release without a pre-release part is published, a branch of this repo moves `version` in
`gradle.properties` to the next version with `-SNAPSHOT`, like the move of the `e2e` workflow's two consumer steps to
that release's commit (see [Dependency updates](#dependency-updates)); from its merge on, a local build from `main`
sorts after that release. A pre-release leaves `version` as it is (a `-SNAPSHOT` version sorts before every
pre-release of the same version).

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
   for it, already exists, or if the runner's `gh` is older than 2.102.0 (the launcher's minimum, with the
   launcher's rule: a pre-release counts as older than its release); it checks both before attesting, so a run
   that stops there leaves no attestation behind. It then creates a build-provenance attestation for the jar with
   `actions/attest` (SLSA provenance), checks that the attestation's Sigstore bundle holds exactly one entry, and
   verifies the attestation against that bundle with the policy of `okfw --update` and `okfw --verify` (the
   consumer command below: predicate type, exact signer identity, source ref, GitHub-hosted runners) plus the built
   commit, tokenless as the launcher runs it (no token or host from the environment, a new empty `gh`
   configuration directory). Finally it creates the draft release `v<version>` targeting the built commit, with
   three assets: `okf-cli-<version>-all.jar`, `okf-cli-<version>-all.jar.sha256` and the bundle as
   `okf-cli-<version>-all.jar.sigstore.json`. The draft is marked as a pre-release when the version has a
   pre-release part, and its notes show the consumer commands below with the version filled in. The job checks
   afterwards that exactly one draft for the tag exists, and it never deletes a release.

The workflow never publishes a release or creates its tag. A failed run can leave a partial draft, visible only
to maintainers (a maintainer deletes it in the UI before running again), and public provenance records (the
attestation and its Sigstore log entry). A published release is never changed; a fix is a new version. Keep the
workflow files on `main` unchanged while a release run is in flight: creating a release for a target commit
whose workflow files differ from the default branch's can need the `Workflows: write` permission, which the
workflow token cannot have. If that happens, start a new run from the current `main`. If publishing is refused
because the tag name was used by a deleted release, delete the draft and release a new version.

Consumers verify a downloaded jar with `gh` 2.102.0 or later (older versions have advisories about these checks; see
[launcher.md](launcher.md#the-provenance-policy)):

```bash
sha256sum -c okf-cli-<version>-all.jar.sha256
# build provenance, without a GitHub account (the bundle is the third asset):
gh attestation verify okf-cli-<version>-all.jar --repo sharpbitstudio/okf-cli \
  --bundle okf-cli-<version>-all.jar.sigstore.json --predicate-type https://slsa.dev/provenance/v1 \
  --cert-identity https://github.com/sharpbitstudio/okf-cli/.github/workflows/release.yml@refs/heads/main \
  --source-ref refs/heads/main --deny-self-hosted-runners
# with a GitHub login: the same check online, and the release attestation that ties the jar to the tag
gh attestation verify okf-cli-<version>-all.jar --repo sharpbitstudio/okf-cli \
  --predicate-type https://slsa.dev/provenance/v1 \
  --cert-identity https://github.com/sharpbitstudio/okf-cli/.github/workflows/release.yml@refs/heads/main \
  --source-ref refs/heads/main --deny-self-hosted-runners
gh release verify-asset v<version> okf-cli-<version>-all.jar --repo sharpbitstudio/okf-cli
```

The checksum detects a damaged download. `gh attestation verify --bundle` checks against the bundle asset,
without a GitHub account, that this repo's release workflow built the jar on `main`: `--cert-identity` requires the
signer identity to be exactly that workflow on `refs/heads/main` (`--signer-workflow` would also accept it, but
matches a pattern), and without it an attestation from any workflow of the repository would pass. The first command
is the policy `okfw --update` and `okfw --verify` apply ([launcher.md](launcher.md#the-provenance-policy)), which
add a check that the jar reports the version asked for. With a login, the same check runs online, and
`gh release verify-asset` checks the release attestation GitHub adds on publication, which ties the jar to the tag.
The build-provenance attestation names `refs/heads/main` and the built commit as its source, because the tag does not
exist when the jar is built.

Launchers already deployed in other repositories hard-code this repository (`sharpbitstudio/okf-cli`), the workflow
path `.github/workflows/release.yml`, the source ref `refs/heads/main`, the tag form `v<version>` and the asset names
`okf-cli-<version>-all.jar` and `okf-cli-<version>-all.jar.sigstore.json`: their downloads and provenance checks
depend on them. None of these may be renamed within a major version.

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

The GitHub Actions this repository ships (`action.yml` and `xcheck/action.yml`, [github-actions.md](github-actions.md))
use no other action, so no Dependabot pull request changes what their consumers run; the actions of the `e2e`
workflow are CI-only and follow the rules above. The exception is this repository's own actions, which `e2e` also
runs as a consumer references them (`sharpbitstudio/okf-cli@<commit>` and `sharpbitstudio/okf-cli/xcheck@<commit>`):
Dependabot ignores them (`ignore` in `.github/dependabot.yml`), and a branch of this repo moves them to the commit of
the next release by hand, after that release is published. A change to `action.yml`, `action/**`, `xcheck/**` or
`launcher/**` is a product change and gets a `CHANGELOG.md` entry. This repository's `build.yml` recognises Dependabot
by its branch name (`dependabot/**`; only Dependabot pushes such branches here), while the Action recognises it by the
pull request's author or the actor, since a fork can name a branch `dependabot/…`.

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
