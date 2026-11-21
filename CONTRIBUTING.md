# Contributing to okf-cli

Bug reports and pull requests are welcome. Report a vulnerability privately as [SECURITY.md](SECURITY.md) describes,
not in an issue.

## Before you start

- For a larger change or a new feature, open an issue first, so the approach is agreed before you write it.
- From 1.0.0 on, okf-cli is under a compatibility contract ([docs/reference.md](docs/reference.md#compatibility)).
  In short: within a major version, input that a release accepts keeps giving the same findings, exit codes,
  generated indexes and messages in later releases (for the same repository content, `okf.yaml`, command-line flags,
  git state and clock), apart from bug fixes listed in `CHANGELOG.md`; input that a release rejects may become valid
  in a later minor release, through a new optional `okf.yaml` key or a new allowed value. The contract also lists
  what it leaves out, such as text passed through from the YAML parser, git or the operating system, and the usage
  text, and its rules for the launcher, the pin and the GitHub Actions. The tool's own message texts are pinned by
  tests.
- Every check belongs in the jar. The launcher (`launcher/`) and the GitHub Actions (`action.yml`, `action/`,
  `xcheck/`) fetch, verify and run it, and add only the input handling and workspace setup they need: the xcheck
  action clones the satellites, while `okf xcheck` works on checkouts already there. Anything specific to a
  repository goes through `okf.yaml`, not a new hard-coded value.

## Build and test

You need JDK 17 or later to build (CI and the release build use JDK 25), a current `git` on the `PATH` (2.32 or
later: the tests isolate git's configuration with `GIT_CONFIG_GLOBAL` and create repositories with `git init -b`),
and for the launcher and action tests a POSIX `sh` with the usual command-line tools, `sha256sum` or `shasum` among
them (on Windows, Git for Windows provides them).

```bash
./gradlew build                                  # compile, tests, and the thin, sources and all jars
java -jar build/libs/okf-cli-<version>-all.jar version   # <version>: `version` in gradle.properties
./gradlew networkTest                            # tests against a published release (network)
```

`networkTest` downloads a published release from GitHub with the real `curl`; it needs no GitHub token. Its
`okfw --update` and `okfw --verify` cases use the GitHub CLI, 2.102.0 or later, and the `--update` cases also the
real `git`; where `gh` is missing or older, those cases are skipped, except on GitHub Actions, where they fail. CI
runs `networkTest` in the `e2e` workflow.

The code is Kotlin in the package `com.sharpbitstudio.okf`, compiled for Java 17: consumers may run the jar on Java
17, and CI smoke-tests it there. Code, comments, documentation and commit messages are in English. Markdown is
wrapped at 120 columns.

## Documentation

The documentation is this repository's own OKF bundle in `docs/` ([docs/index.md](docs/index.md)). `./gradlew build`
does not check it: the `okf*` Gradle tasks run the CLI this build compiles on `docs/`. Run the strict check locally
after you commit and before you push (a document update that is not committed yet covers every change, so a dirty
work tree can pass where CI fails):

```bash
./gradlew okfIndex okfCheck okfImpact -Pokf.strict=true -Pokf.refs.strict=true -Pokf.codePaths.required=true -Pokf.impact.strict=true
```

CI runs `okfCheck` and `okfImpact` with the same check flags and chooses the impact base and strictness per event
([docs/build-release.md](docs/build-release.md#ci)). It does not run `okfIndex`: commit what `okfIndex` writes.
Locally the impact check compares with `origin/main`; in a fork, keep that branch current with this repository's
`main`, or pass `-Pokf.base=<remote>/main`.

Each document names the code it describes in its `code_paths` frontmatter; for example `docs/reference.md` covers
the Kotlin sources under `src/main/kotlin/`. A change to such code impacts the document, and the strict impact check
fails until the document is updated or a commit acknowledges the change with a `Docs-Impact:` trailer, as the last
paragraph of its message:

```text
Docs-Impact: reference — refactoring, no change in behaviour
```

The trailer names the document (its path in `docs/` without `.md`) and why it stays valid. An update or a trailer
covers only the changes its commit could see, those of the commit itself and its ancestors: update or acknowledge
each impacted document in that commit or in a commit that descends from it. One change can impact more than one
document; one trailer can name them all (`Docs-Impact: reference, authoring — <reason>`). The rules for trailers,
`code_paths` and code references are in [docs/authoring.md](docs/authoring.md).

## Changelog

A change to what users run (the CLI in `src/main/`, the launcher, the GitHub Actions, a runtime dependency, or what
the released jar contains) gets an entry under `## Unreleased` in `CHANGELOG.md`. A change to tests, CI or
documentation alone gets none.

## Pull requests

- Work on a branch named `feature/<scope>`, `fix/<scope>` or `docs/<scope>` in your fork.
- Pull requests are merged by rebase: each commit is applied to `main` on its own, with a new SHA but with its
  message and trailers unchanged. Keep each commit self-contained, with a message that says what changes and why,
  and run `./gradlew build` before you commit. `main` accepts a pull request only when its branch is up to date, so
  you may be asked to rebase it.
- The required checks are `Build + test` and `OKF docs bundle check (+ docs impact)`. On a pull request from a fork,
  the workflows run once a maintainer approves them, with a read-only token and without secrets. The `e2e` jobs,
  which are not required, run too: the launcher and the GitHub Action on Linux, macOS and Windows; on Linux the
  xcheck action, and a build as a release-like version with ShellCheck on the shell scripts.
- A pull request that bumps a runtime dependency also updates `third-party-licenses/` and `NOTICE` against the
  upstream licence files of the exact new version
  ([docs/build-release.md](docs/build-release.md#third-party-licences)).

## Licence

okf-cli is licensed under the Apache License 2.0 ([LICENSE](LICENSE)). Under its section 5, unless you explicitly
state otherwise, a contribution you intentionally submit for inclusion is licensed under the same terms, without
any additional terms or conditions.
