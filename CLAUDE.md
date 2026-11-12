# CLAUDE.md

`okf-cli` builds `okf`, a Kotlin CLI that keeps an OKF (Open Knowledge Format) v0.2 docs bundle consistent with
the code: `okf index|check|impact` work on one repo's bundle, `okf xcheck` checks a hub repo and its satellites
together, `okf version` prints `okf-cli <version>`. Documentation lives in the repo's own OKF bundle:
`docs/index.md`; CLI, `okf.yaml` schema, bundle rules and the compatibility contract in `docs/reference.md`;
build, CI, release and third-party licences in `docs/build-release.md`. `README.md` is only the landing page;
`CHANGELOG.md` records every change per version.

## Commands

```bash
./gradlew build                                  # tests + thin/sources/all jars (CI gate)
java -jar build/libs/okf-cli-<version>-all.jar version
./gradlew okfIndex okfCheck okfImpact -Pokf.strict=true -Pokf.refs.strict=true -Pokf.codePaths.required=true -Pokf.impact.strict=true   # own docs/, as CI
```

## Docs (OKF, self-checked)

- `docs/` is checked by the code this build compiles: the `okf*` tasks run `com.sharpbitstudio.okf.cli.Main`
  from `sourceSets.main`, not a released jar.
- `docs/reference.md` carries `code_paths: src/main/kotlin/com/sharpbitstudio/okf/**`, so every behaviour change
  fails `okfImpact` on a branch until the reference is updated in the same branch. Alternatively, add a
  `Docs-Impact: reference — <reason>` trailer, which fits pure refactors.
- `docs/build-release.md` covers `build.gradle.kts`, the workflows, `LICENSE`, `NOTICE` and
  `third-party-licenses/`.

## Conventions

- Kotlin, package `com.sharpbitstudio.okf` (CLI in `com.sharpbitstudio.okf.cli`, entry point
  `com.sharpbitstudio.okf.cli.Main`); bytecode 17 (consumers may run the jar on Java 17), built on JDK 25.
- English in code, comments, docs and commit messages.
- Console lines and `::warning` / `::error` annotations are a contract (CI logs, PR annotations), like the
  findings, exit codes and generated indexes.
- Anything repo-specific goes through `okf.yaml` (`OkfConfig`), never a new hard-coded constant.
- Third-party licences: the `all` jar bundles the Kotlin stdlib (with its own third-party parts), JetBrains
  Annotations and SnakeYAML; their licence texts live verbatim in `third-party-licenses/` and are named in
  `NOTICE`. After any bump of a runtime dependency, re-check that inventory against the upstream licence README
  of the exact new version and update both in the same change.

## Compatibility contract

- Semver. From **1.0.0 final**, within a major: for the same repository content, `okf.yaml`, command-line flags,
  git state and clock (`stale_after`), the findings, exit codes and generated indexes stay identical. New
  behaviour arrives only behind new optional keys; bug fixes are listed in `CHANGELOG.md`. Pre-releases are
  exempt.
- The file name `okf.yaml` and the keys `repo`, `hub_repo`, `satellites` never change (tooling built around
  `okf.yaml` may rely on them).
- Federation rule: a repo checked by a hub's `okf xcheck` may use a new key only once the hub's tool version
  knows it (`xcheck` parses every satellite's `okf.yaml` with the hub's jar).

## Commit policy

- Work on a `feature/|fix/|docs/<scope>` branch, created before `git add`; never commit to `main`.
- Run `./gradlew build` before committing.
- Check `git status --short` before staging and stage explicit paths only (`git add -- <paths>`).
- After committing, run the `okf*` line from Commands, then push your branch (`git push -u origin <branch>`).
- Open a pull request only after review.
