# Changelog

## Unreleased

- xcheck action: the token goes only to the server the workflow runs on (`GITHUB_SERVER_URL`: its scheme, host and
  port), for repository requests. git now gets it from a credential helper scoped to that URL,
  `xcheck/credential.sh`, instead of an askpass helper that answered every username and password prompt. A clone
  that the server redirects to another host, or that the runner's git configuration rewrites to another host
  (`url.<base>.insteadOf`, e.g. a mirror), no longer receives the token; if that host requires credentials, the
  clone fails with exit `2` and the `cannot clone …` annotation, after git's own message. A clone through a proxy
  whose configured URL names a user but no password now always fails that way, even when the proxy does not enforce
  authentication: git asks for the proxy's password before the first request, and nothing answers. A proxy URL with
  a password (`http://<user>:<password>@<host>:<port>`) carries its own credential and works as before. Satellites
  from a mirror or behind such a proxy: check them out into `<workspace>/<repo>` before the action. The clone's log
  line now reads
  `okf-cli xcheck action: git -c credential.helper= -c core.askPass= -c credential.useHttpPath=true -c 'credential.<server>.helper=!sh "$GITHUB_ACTION_PATH/credential.sh"' clone --no-tags -- <url> <directory>`.

## 1.0.0 — 2026-11-17

First stable release: from this version on, the compatibility contract applies (`docs/reference.md`,
"Compatibility"). Same code as `1.0.0-rc.1`; the changes since `1.0.0-alpha.1` and the upgrade notes are listed
under 1.0.0-rc.1.

## 1.0.0-rc.1 — 2026-11-17

Release candidate of 1.0.0. Pre-releases are exempt from the compatibility contract; 1.0.0 will be under it.

Upgrading from `1.0.0-alpha.1`: an `okf.yaml` that leaves `index_language` unset now gets English index text, and
one that leaves `index_command` unset gets `okf index` in the generated marker and the "run …" hints; either way its
committed indexes are stale. Run the index command once, or set `index_language: sr` and
`index_command: ./gradlew okfIndex` to keep them. A bundle with
`adr/` but no `adr/README.md` loses the link to that file in the ADR index; run the index command once, or set
`adr_readme_link: always` to keep it. In a federation, upgrade the hub first.

- Kotlin API: `OkfChecker.check` takes the parsed `okf.yaml` (`check(root, config, …)`) instead of one parameter per
  key, and the `OkfIndexRenderer` functions no longer default the index command (nor, for the sub-indexes, the
  language). The CLI is unchanged.
- New optional `okf.yaml` keys: `base_ref` (the branch `okf impact` compares against when `--base` is absent;
  default `main`), `extra_types` (document types added to the built-in ones, without rules of their own) and
  `drift_days` (hub only: the drift threshold of `okf xcheck`; default 14). In a federation a satellite may use a
  new key only once the hub's version knows it. The usage text of `--base` names `base_ref`; the constant
  `OkfXcheck.DRIFT_DAYS` is gone (`OkfConfig.DEFAULT_DRIFT_DAYS`).
- Defaults: `index_language` is `en` (was `sr`) and `index_command` is `okf index` (was `./gradlew okfIndex`).
- New optional key `adr_readme_link` (`always` or `auto`, default `auto`): the generated ADR index links
  `adr/README.md` only when that file exists; `always` renders the link unconditionally, as before. Kotlin API:
  `OkfIndexRenderer.renderAdrIndex` takes the setting as a parameter, and `OkfConfig` gains a property for it (as
  for each new key).
- ADR status `Rejected` (`status: deprecated`), listed in its own section of the ADR index after
  "Superseded / Deprecated"; messages that list the allowed `adr_status` values name it.
- `okf.yaml` errors: `schema_version` is checked before unknown keys.
- `okf.yaml`: a date-like value (`2026-01-01`) is read as the text written, as in frontmatter; an error message
  echoes it as written, and a value that was rejected only because it was read as a date is now accepted.
- Message texts, changed once before 1.0.0:
  - `okfCheck OK: …` → `okf check OK: …`
  - `okfCheck found N problem(s):` → `okf check found N problem(s):`
  - `okfImpact skipped: no base to compare against …` → `okf impact skipped: no base to compare against …`
  - `okfImpact: nothing changed since …` → `okf impact: nothing changed since …`
  - `okfImpact: 0 documents with code_paths — …` → `okf impact: 0 documents with code_paths — …`
  - `okfImpact: N changed files outside …` → `okf impact: N changed files outside …`
  - `okfImpact found N problem(s):` → `okf impact found N problem(s):`
  - `okfImpact: <reason>` (no base or no merge-base, under `--impact-strict`) → `okf impact: <reason>`
  - `okfXcheck OK: …` → `okf xcheck OK: …`
  - `okfXcheck: …; N problem(s), M warning(s).` → `okf xcheck: …; N problem(s), M warning(s).`
  - `okfXcheck: setup failed — no check ran.` (footer of the `xcheck --report` file) →
    `okf xcheck: setup failed — no check ran.`
  - ``ADR requires a non-empty `description` („kada čitati")`` →
    ``ADR requires a non-empty `description` ("when to read")``
  - `cannot run git (required by okf index / check / impact): …` → `cannot run git (okf needs git on the PATH): …`
  - `code_paths[i] '<glob>' is not a valid glob: Illegal character range near index …` (several lines) →
    ``code_paths[i] '<glob>' is not a valid glob: a `[...]` character class has a range whose start comes after its end (e.g. `[z-a]`)``
    (when the glob expands to more than one `{}` alternative, followed by `in alternative '<expanded glob>'`, like
    the other findings raised per alternative)
- The compatibility contract now says what it covers for message text (`docs/reference.md`, "Compatibility").
- Fix: in a `code_paths` glob, a negated class that starts with `-` (`[!-a]`) treated the `-` as the start of a
  range (it is a literal `-` now, as in POSIX globs, so `[!--]` is no longer rejected and `[!-a-0]` is), and a
  class whose range spans `/` (`[+-0]`) could match `/` (a class never matches `/`, as the finding for a `/` inside
  a class already says).
- New command `okf init`: writes `okf.yaml` (with `repo`, `index_language: en`, `index_command` and, when not
  `docs`, `docs_root`) and the bundle-root `<docs_root>/index.md` for a new bundle, and by default the launcher
  (below). `--index-command=<command>` defaults to `./okfw index` (required with `--launcher=false`);
  `--name=<repo>` (default: the name of the `--repo` directory) and `--docs-root=<dir>` (default `docs`) are
  optional, and `--adopt` uses a docs root that already holds documents as the bundle. It never overwrites an
  existing file and writes all or nothing: exit `0` when the files were created, `1` for conflicts (all reported,
  nothing written) or a failed write (what the run created is removed), `2` for a usage error, a `--repo` that is
  not the work-tree root, or an invalid `--name`, `--docs-root` or `--index-command`. In a repository without
  documentation the bundle it creates passes `okf check --strict --refs-strict --code-paths-required` right away
  (`docs/reference.md`, "Starting a bundle").
- New launcher `okfw` (a POSIX `sh` script, with `okfw.cmd` for `cmd` and PowerShell through Git for Windows'
  `sh.exe`) and pin file `okf-wrapper.properties` (`version`, `sha256`) at the repository root: `./okfw <command>`
  downloads the pinned release jar once into a per-user cache, checks its SHA-256 against the pin before every run
  and runs it with Java 17 or later; exit `2` for a pin error, `3` when no verified jar or Java is available, the
  jar's code otherwise; variables `OKFW_PIN` and `OKFW_CACHE_DIR`. See `docs/launcher.md`.
- `okfw --update <version>` pins a release: it downloads the jar and its Sigstore bundle, checks the build provenance
  with the GitHub CLI (`gh` 2.102.0 or later; `gh attestation verify` with the exact signer identity of
  `.github/workflows/release.yml` on `refs/heads/main`, the source ref and GitHub-hosted runners only; no token or
  host from the environment and an empty, temporary `gh` configuration directory), checks that the verified jar
  reports the version asked for, writes the pin and refreshes the launcher files with that version's `okf wrapper`.
  `okfw --verify` runs the same checks on the pinned jar and changes nothing. Exit `2` for a usage error, `3` when
  `gh` is missing or too old or a check fails (the pin unchanged). The minimum `gh` version and this policy may be
  tightened in any release, each change listed here.
- New command `okf wrapper`: writes the launcher files, refreshes ones that carry the launcher marker, writes the pin
  with its own SHA-256 when there is none and it runs from a release `all` jar, and creates `.gitattributes` when
  absent; exit `1` when a file without the marker is in the way (nothing written). `okf init` writes the same files
  create-only; `--launcher=false` skips them. The unknown-command message lists `init` and `wrapper` last, and
  init's `okf.yaml already exists` conflict now points to `okf wrapper`.
- GitHub Action at the repository root (`uses: sharpbitstudio/okf-cli@<commit-sha>`, `action.yml`): runs
  `okf check --strict --refs-strict --code-paths-required` and `okf impact` with the version the repository pins,
  through the launcher, with the jar cached in the runner's temporary directory. Inputs `repo`, `strict`,
  `refs-strict`, `code-paths-required`, `impact`, `base` and `impact-strict` (`auto`: chosen from the event, with
  Dependabot recognised by pull request author or actor), `verify-provenance` (`okfw --verify` first); output
  `version`. Exit `2` with an `okf-cli` error annotation for an invalid input or a shallow clone with impact on. It
  uses no other action and needs Java 17 or later on the runner. See `docs/github-actions.md`.
- GitHub Action for the cross-repo check (`uses: sharpbitstudio/okf-cli/xcheck@<commit-sha>`, `xcheck/action.yml`):
  clones a hub's satellites (input `satellites`: `owner/repo` or `repo`, each name checked before the first clone;
  full history, no tags, from the default branch) into `<workspace>/<repo>`, keeping a directory already there,
  and runs `okf xcheck` with the version the hub pins, through the launcher; the report is appended to the job
  summary. Inputs `hub`, `satellites`, `token` (default `github.token`; given to git only through an askpass
  helper, with credential helpers off and no prompts) and `workspace`; outputs `report` and `exit-code`. Exit `2`
  with an `okf-cli` error annotation for an invalid input or a failed clone, the code of `okf xcheck` otherwise.
  See `docs/github-actions.md`.
- CI: an `e2e` workflow runs the launcher, its network tests and the GitHub Action on Linux, macOS and Windows, and
  the xcheck action on Linux; the Java 17 smoke test's fixture has an ADR; Dependabot version updates are on
  (`docs/build-release.md`).
- Releases: the release workflow checks the attestation with the policy of `okfw --update` (exact signer identity,
  source ref, GitHub-hosted runners) and the built commit, with `gh` 2.102.0 or later, and the release notes show
  the commands of `docs/build-release.md`.
- Docs: the README quick start starts a bundle with `okf init` and runs it through `./okfw`.

## 1.0.0-alpha.1 — 2026-11-12

First pre-release. Pre-releases are exempt from the compatibility contract: the defaults of `index_language`
(`sr`) and `index_command` (`./gradlew okfIndex`) change before 1.0.0, so set both explicitly.

- The `okf` CLI over an OKF v0.2 docs bundle, configured per repo in `okf.yaml` (schema version 1):
  `okf index`, `okf check`, `okf impact`, `okf xcheck` and `okf version` (prints `okf-cli <version>`);
  configuration errors name `okf-cli` and point to
  https://github.com/sharpbitstudio/okf-cli/blob/main/docs/reference.md.
- Licensed under Apache-2.0 (`LICENSE`, `NOTICE`); the self-contained `-all` jar also carries the licences of
  the third-party components it bundles.
- Kotlin package `com.sharpbitstudio.okf`; a wrapper that runs the jar by main class uses
  `com.sharpbitstudio.okf.cli.Main`.
- CI: the skipped jobs of a pull request from a branch of this repo no longer share their names with the checks
  meant to be required on `main`, `Build + test` and `OKF docs bundle check (+ docs impact)`.
- Releases: a manually started workflow builds the version from `main`, attests the `-all` jar (SLSA build
  provenance) and prepares a draft release with the jar, its `.sha256` and the Sigstore bundle of the attestation;
  published releases are immutable.
- Docs: the README quick start downloads and verifies the released jar.
