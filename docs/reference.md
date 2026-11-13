---
type: Guide
title: "okf CLI reference"
description: "The okf CLI (commands, options, exit codes), the okf.yaml schema, the bundle rules the tool enforces in multi-repo setups, the hub's cross-repo check (xcheck), how to run the tool, and the compatibility contract."
tags: [okf, cli, okf-yaml, satellite-note, xcheck, compatibility, reference]
code_paths: ["src/main/kotlin/com/sharpbitstudio/okf/**"]
---
# okf CLI reference

okf-cli builds one CLI, `okf`, for OKF (Open Knowledge Format, v0.2) docs bundles. Three commands work on a
repo's bundle (`docs_root` in `okf.yaml`, default `docs`):

- `okf index` regenerates `<docs_root>/adr/index.md` and `<docs_root>/features/index.md` from frontmatter
  (each only when its directory exists) and, in the hub, the "Repos" section of `<docs_root>/index.md`;
- `okf check` validates frontmatter (incl. `code_paths` globs), the freshness of the generated indexes
  and the code references in document bodies (against `git ls-files`);
- `okf impact` reports documents whose `code_paths` match code changed since the merge-base with a base
  ref and that were neither updated nor acknowledged by a `Docs-Impact:` commit trailer.

A fourth command, `okf xcheck`, runs in the hub and checks the hub and its satellites together
([Cross-repo check](#cross-repo-check-xcheck)). `okf version` prints `okf-cli <version>`.

Documentation can be spread over several repos (a federation): one **hub** repo holds the Feature Records, and
**satellite** repos hold `Satellite Note`s that belong to a feature recorded in the hub. A single repo needs none
of this: it uses `index`, `check` and `impact` and leaves `hub_repo` and `satellites` out of its `okf.yaml`.

## CLI

```
okf <index|check|impact|xcheck|version> [options]      (--help / -h anywhere: print the usage)

  --repo=<dir>             git work-tree root holding okf.yaml                          [index, check, impact, xcheck]
                           (default: working directory)
  --strict                 files without frontmatter are failures                       [check]
  --refs-strict            broken code references are failures                          [check]
  --code-paths-required    Accepted/Resolved ADRs, Feature Records and Satellite Notes   [check]
                           need code_paths
  --base=<ref>             merge-base with <ref>; absent = origin/<base_ref>, fallback   [impact]
                           <base_ref> (base_ref in okf.yaml, default main); empty or an
                           all-zero SHA = skip the check
  --impact-strict          impact findings are failures                                  [impact]
  --workspace=<dir>        directory holding every satellite checkout as <dir>/<repo>    [xcheck, required]
  --report=<file>          Markdown report, written when there are findings and deleted  [xcheck]
                           after a clean run
```

Boolean flags are `--flag` (true) or `--flag=true|false`; any other value is a usage error. Value
options take `--opt=<value>` or `--opt <value>` (the space form never takes a value starting with `--`).
An option that does not apply to the command is a usage error too. `--repo` must be the git work-tree
root (`git rev-parse --show-toplevel`), not a directory below it.

Exit codes: `0` OK, `1` findings that fail under the given strictness (or the command could not
complete, e.g. broken frontmatter for `index`, git not runnable), `2` usage, `--repo` or `okf.yaml` error.
For `xcheck`, `0` also covers drift warnings only, and `2` also a missing or misconfigured satellite checkout.
Info lines and annotations go to stdout, warnings and failures to stderr. Under GitHub Actions
(`GITHUB_ACTIONS=true`) the code-reference findings of `check` and the findings of `impact` are also printed as
`::warning` / `::error` workflow commands, so they show up as annotations on the offending doc line.

```bash
java -jar okf-cli-<version>-all.jar check --repo=. --strict --refs-strict --code-paths-required
java -jar okf-cli-<version>-all.jar impact --base=origin/main --impact-strict
java -jar okf-cli-<version>-all.jar xcheck --repo=<hub> --workspace=<dir> --report=<file>
```

### Console lines

A line that starts with `okf <command>` reports on that command; a line that starts with `OKF ` is a labelled
notice. `N`, `<problem>` and the other placeholders stand for the variable parts; `(1 warnings)` and similar plurals
are written as shown.

Every command:

- `okf: <usage error>` on stderr, followed by the usage text (exit `2`);
- `okf <command>: <message>` on stderr for an `okf.yaml` or `--repo` error (exit `2`) and for an operation that
  cannot complete (exit `1`), e.g. `okf index: adr/<file>: unknown or missing adr_status '<value>'`.

`okf index` prints nothing when it succeeds.

`okf check`:

- `OKF WARNING: <warning>` on stderr, one per warning (e.g. a `stale_after` in the past, an over-broad
  `code_paths` glob, and the code-reference findings without `--refs-strict`);
- `OKF INFO: N files not yet migrated (no frontmatter); --strict makes them failures.` on stdout, without `--strict`;
- ``OKF INFO: N <kinds> have no `code_paths`; --code-paths-required makes them failures.`` on stdout, without
  `--code-paths-required`; `<kinds>` is `Accepted/Resolved ADRs / Feature Records`, `Satellite Notes`, or both
  joined by ` / `;
- on failure, one block on stderr (exit `1`): `okf check found N problem(s):`, then one line `  - <problem>` per
  problem;
- otherwise one summary line on stdout, without or with code-reference warnings:
  - `okf check OK: N documents with frontmatter validated, M code references resolved.`
  - `okf check OK: N documents with frontmatter validated, M code references checked (K warnings; --refs-strict fails on them).`
- under GitHub Actions, each code-reference finding also as an annotation with the title `OKF ref` (`::warning`,
  `::error` with `--refs-strict`).

`okf impact`:

- one line per impacted document: `[updated] <doc> <- <files>` and `[acknowledged: <sha7>] <doc> <- <files>` on
  stdout, `[NOT UPDATED] <doc> <- <files>` on stderr; `<files>` lists at most three files, then `(+N more)`;
- without `--impact-strict`: `OKF IMPACT WARNING: <finding>` on stderr (under GitHub Actions only for findings
  without a document), then `OKF IMPACT: N finding(s); --impact-strict fails on them.`;
- with `--impact-strict` and findings, one block on stderr (exit `1`): `okf impact found N problem(s):`, then one
  line `  - <finding>` per finding, and no summary line;
- otherwise one summary line on stdout, where `<against>` is `merge-base <sha7> with <base>`:
  - `okf impact: nothing changed since <against>.`
  - `okf impact: 0 documents with code_paths — nothing to match (N changed files outside <docs_root>/ since <against>).`
  - `okf impact: N changed files outside <docs_root>/ since <against>; D documents with code_paths, I impacted (U updated, A acknowledged, X NOT UPDATED)`,
    then `; S deprecated skipped.` when documents with `code_paths` were skipped as `deprecated`, `.` otherwise;
- `okf impact skipped: no base to compare against (--base='<value>').` on stdout when `--base` is empty or an
  all-zero SHA;
- when the base or the merge-base cannot be found: `OKF IMPACT WARNING: <reason> — impact check skipped.` on
  stderr, or with `--impact-strict` `okf impact: <reason>` (exit `1`);
- under GitHub Actions, each finding also as an annotation with the title `OKF docs impact` (`::warning`, `::error`
  with `--impact-strict`), and without `--impact-strict` a base or merge-base that cannot be found also as a
  `::warning` annotation with that title.

`okf xcheck` ([Cross-repo check](#cross-repo-check-xcheck)), where `<counts>` is
`R repos checked, <refs>, U references to non-federated repos not checked, S Satellite Notes linked to F Feature Records`
and `<refs>` is `C cross-repo references resolved`, or `C cross-repo references checked (B broken)` when a reference
is broken:

- a setup error (exit `2`): `okf xcheck: <repo>: <reason>` on stderr, or for several satellites
  `okf xcheck: N setup problems:`, then one line `  - <repo>: <reason>` per satellite; no check runs and no summary
  line is printed;
- `OKF XCHECK WARNING: <finding>` on stderr, one per warning;
- on failure, one block on stderr (exit `1`): `okf xcheck found N problem(s):`, then one line `  - <finding>` per
  problem;
- then one summary line on stdout:
  - `okf xcheck OK: <counts>.`
  - `okf xcheck OK: <counts>; M warning(s).`
  - `okf xcheck: <counts>; N problem(s), M warning(s).`

## `okf.yaml` (schema v1)

Every repo under OKF has an `okf.yaml` at its root. Parsing is strict: an unknown key, a wrong type, a
missing file or an unsupported `schema_version` is an error (exit `2`). `schema_version` is checked first, so a
file written for a newer schema is reported as that and not as unknown keys. A date-like value (`2026-01-01`) is
read as the text written, as in frontmatter.

| Key | Required | Default | Meaning |
|---|---|---|---|
| `schema_version` | yes | — | must be `1` |
| `repo` | yes | — | repository name |
| `docs_root` | no | `docs` | repo-relative path of the OKF bundle (`/`-separated; no `./`, `..`, `//` or trailing `/`) |
| `index_command` | no | `okf index` | command that regenerates the indexes; named in the generated marker, the ADR index header and every "run …" hint (one line; no backtick, no `-->`) |
| `external_repos` | no | `[]` | sibling repositories; a code reference written as `<repo>/<path>` points there and is not checked |
| `ref_extensions` | no | `[]` | extensions (no dot) added to the base list a code-span path must end with (`java`, `kt`, `kts`, `yml`, `yaml`, `xml`, `json`, `sql`, `properties`, `puml`, `toml`, `sh`, `md`) |
| `source_roots` | no | `[]` | extra repo-relative code roots, resolved like the `src/{main,test}/{java,kotlin,resources}` convention roots (incl. the descent to the base package); same path rules as `docs_root` |
| `hub_repo` | no | — | the hub repository holding the Feature Records that `Satellite Note`s point to; a repository name that must also be in `external_repos` and differ from `repo`. Required for any `Satellite Note` in the repo; absent in the hub itself |
| `satellites` | no | `[]` | hub only (not allowed together with `hub_repo`): the satellite repositories, a list of mappings with exactly `repo` (a repository name from `external_repos`, not this repo, listed once; no `/`, `\`, `.` or `..`), `description` (one line) and `index` (an `https://` URL with a host, as `java.net.URI` parses it, of the satellite's bundle-root `index.md`; one line, no whitespace and none of `(`, `)`, `<`, `>`, since it becomes a Markdown link target). Order is kept. Turns on the "Repos" section of `<docs_root>/index.md` and `okf xcheck` |
| `index_language` | no | `en` | language of the fixed text of the generated `<docs_root>/adr/index.md` and `<docs_root>/features/index.md` (`sr` or `en`, case-sensitive): the ADR index title and intro, the "not migrated" section and its entries, the amended suffix; the marker line, status headings, entry format and the Repos section are the same in both |
| `adr_readme_link` | no | `auto` | whether the intro of the generated `<docs_root>/adr/index.md` ends with a link to `<docs_root>/adr/README.md` ("Conventions, template and lifecycle"): `auto` only when that file exists (the name is matched exactly), `always` unconditionally (case-sensitive: `always` or `auto`). With `auto`, adding or removing the file makes the index stale until `okf index` runs |
| `base_ref` | no | `main` | the branch `okf impact` compares against when `--base` is absent: the merge-base with `origin/<base_ref>`, or with `<base_ref>` when that remote-tracking ref does not exist (resolved as `git rev-parse` resolves it, so a tag or a commit of that spelling also matches). A branch name without the remote: parts of ASCII letters, digits, `.`, `_` or `-` joined by `/`, each starting with a letter, digit or `_` and not ending with `.` or `.lock`, without `..`; not `HEAD` or a name without `/` ending in `_HEAD` (git reserves those, such as `FETCH_HEAD` and `ORIG_HEAD`), compared ignoring case; quote a name that YAML would read as a number, a boolean or null |
| `extra_types` | no | `[]` | document types added to the built-in ones ([Bundle rules](#bundle-rules)): a list of one-line names. An added type has no rules of its own (it is not an ADR, a Feature Record or a Satellite Note). A name equal to a built-in type, ignoring case, is an error; a repeated name counts once. The built-in types do not change within a major version, so an added name never collides later |
| `drift_days` | no | `14` | hub only (an error in a repo without a non-empty `satellites`): after how many days, `1` to `3650`, `okf xcheck` reports drift between a Satellite Note and its hub record (check (d)). Write a plain decimal number, unquoted (YAML 1.1 reads forms such as a leading `0` as octal) |

A key unknown to the jar is an error. In a federation, a repo checked by the hub's `okf xcheck` may use a new
key only once the hub's tool version knows it, because `xcheck` parses every satellite's `okf.yaml` with the
hub's jar (see [Compatibility](#compatibility)).

A satellite:

```yaml
schema_version: 1
repo: plaza-web-app
index_command: npm run okf:index
external_repos: [plaza-server-app, plaza-mobile-protos]
hub_repo: plaza-server-app
ref_extensions: [ts, tsx]
source_roots: [web/src, web/test]
```

The hub lists its satellites:

```yaml
schema_version: 1
repo: plaza-server-app
external_repos: [plaza-web-app, plaza-mobile-protos, fiord-server]
satellites:
  - repo: plaza-web-app
    description: Web app
    index: https://github.com/example-org/plaza-web-app/blob/main/docs/index.md
```

## Bundle rules

What the tool enforces beyond the frontmatter of single documents (how those rules relate to the OKF spec is
summarised in the [README](../README.md)):

- **Document types** (`type`): the built-in types `ADR`, `Feature Record`, `Plan`, `Guide`, `Risk Register`,
  `Convention`, `Product Spec`, `Satellite Note`, plus the names in `extra_types`. Only the built-in `ADR`,
  `Feature Record` and `Satellite Note` carry rules of their own.
- **ADRs** (`type: ADR`, files named `adr/ADR-NNN-<slug>.md` with a three-digit number): an ADR needs a non-empty
  `title` and `description`, `tags` as a non-empty YAML list of strings, and `adr_date` as a valid `YYYY-MM-DD` date;
  `amended`, when present, lists the dates of later changes, strictly ascending and each after `adr_date`. It is kept
  in sync both ways with the amendment markers in the body (such as `**Update YYYY-MM-DD:**`; fenced blocks and code
  spans are not scanned): a listed date without a marker and a marker whose date is not listed are both problems,
  and a marker dated the same day as `adr_date` is reported as one to drop, since same-day changes are part of the
  original decision. `adr_status` is one of
  `Proposed`, `Accepted`, `Resolved`, `Superseded`, `Deprecated`, `Rejected`, and `status` must be the matching
  lifecycle value: `draft` for `Proposed`, `stable` for `Accepted` and `Resolved`, `deprecated` for `Superseded`,
  `Deprecated` and `Rejected`. The generated ADR index groups the entries under the headings `Accepted`,
  `Proposed`, `Resolved`, `Superseded / Deprecated` and `Rejected`, in that order, and leaves out empty ones.
  `superseded_by` is required with `Superseded` and not allowed otherwise. `code_paths` is mandatory under
  `--code-paths-required` only for `Accepted` and `Resolved`; `okf impact` skips documents whose `status` is
  `deprecated`.
- **`Satellite Note`**: a note in a sibling repo that belongs to a feature whose record lives in the hub.
  `hub: features/<slug>` is required (slug: lowercase letters and digits, words joined by single hyphens,
  e.g. `hub: features/checkout`) and names the hub's Feature Record in the `Docs-Impact:` ID form.
  `okf check` validates the format only; whether the record exists is a cross-repo check. A Satellite Note
  needs `hub_repo` in `okf.yaml`; `hub:` on any other type is a problem. `code_paths` covers the
  satellite repo's own code and is mandatory under `--code-paths-required`, like on a Feature Record.
- **`repos:` on a Feature Record** (optional): a YAML list of the sibling repos that hold Satellite Notes for
  it, e.g. `repos: [plaza-mobile-protos]`; each entry must be in `external_repos`, no duplicates. Not
  allowed on other types.
- **Sub-indexes are optional**: `okf index` writes `<docs_root>/adr/index.md` and
  `<docs_root>/features/index.md` only when that directory exists, and `okf check` requires (and checks the
  freshness of) only those. A repo without `adr/` and `features/` needs just the bundle-root `index.md`.
- **"Repos" section (hub)**: with `satellites` in `okf.yaml`, the bundle-root `index.md` must hold one
  `<!-- okf:repos-start -->` line followed by one `<!-- okf:repos-end -->` line (each alone on its line). Marker
  lines are matched as whole lines anywhere in the file, fenced blocks included, so a document that shows them must
  quote them inline or with other text on the line, never as a whole line inside a fence. `okf index`
  replaces everything between them with a generated note naming `index_command`, a blank line and one line
  `* [<repo>](<index>) - <description>` per satellite, in `okf.yaml` order; the text outside the markers, including
  the heading above them, is the repo's own, and a UTF-8 BOM of the file is kept. Missing, duplicated or
  out-of-order markers, or a missing bundle-root `index.md`, fail `okf index` (exit `1`, nothing is written) and
  are a problem in `okf check`, which also reports a stale or hand-edited section
  (`index.md: Repos section out of date or edited by hand — run <index_command>`). Without `satellites` the
  bundle-root `index.md` is neither written nor checked for it.
- **Line endings**: `okf index` does not rewrite an index whose content is unchanged (compared with BOM and
  line endings normalized, as `okf check` compares); when it does write over an existing index it keeps that
  file's line endings (CRLF if it has any, else LF), and a new index gets LF. On Windows with
  `core.autocrlf=true`, `okf index` on an unchanged tree therefore leaves `git status` clean.

## Cross-repo check (`xcheck`)

`okf xcheck` runs in the hub (`--repo`, whose `okf.yaml` must have `satellites`; otherwise exit `2`) and checks the
federation, the hub plus every satellite, from work trees only, without network access. `--workspace` holds each
satellite checkout as `<workspace>/<repo>`. A missing checkout, a checkout that is not a git work-tree root, a
satellite without `okf.yaml`, a satellite `okf.yaml` that does not parse, whose `repo` is not its directory name,
whose `hub_repo` is not the hub's `repo` or whose `docs_root` does not exist is a setup error (exit `2`; every
failing satellite is listed, prefixed with its name).

The checks (failures exit `1`, warnings alone exit `0`):

- **(a) Cross-repo references.** Every bundle document of every federated repo is scanned exactly as `okf check`
  scans it (body, fenced blocks and opt-out markers, code-span grammar). The grammar is the SOURCE repo's: its own
  `ref_extensions` decide what is a reference, so a hub document writing `plaza-web-app/src/x.tsx`
  is not a reference unless the hub lists `tsx`. A
  code-span reference that `okf check` skips as `<repo>/<path>` is resolved in the target repo when that repo is
  federated: repo root, then its package roots (incl. `source_roots`), then a path suffix (two or more segments must
  be unique; a line reference needs a unique file), with line numbers checked against the target's file and
  gitignored paths in the target skipped; otherwise it is "not found in `<repo>`". As in `okf check`, a path with
  `.` / `..` segments after the repo name is explicitly relative and resolves from the target's root only; one
  that names just the repo or climbs out of it is not looked up (and not counted). Findings come in document and
  line order. References to repos that are only
  in `external_repos` (reference projects outside the federation) are counted, not checked. Markdown links are not
  part of the check.
- **(b) `hub:`.** Every `Satellite Note` in every satellite must name, with `hub: features/<slug>`, a file
  `<docs_root>/features/<slug>.md` in the hub whose `type` is `Feature Record`.
- **(c) `repos:` and Satellite Notes agree.** Every repo in a hub Feature Record's `repos:` must be a satellite holding
  at least one Satellite Note whose `hub:` names that record, and the record of every Satellite Note (when it exists)
  must list the note's repo in `repos:`.
- **(d) Drift (warning).** For every Satellite Note and its hub record, the last commit time of each file is compared
  (committer time, `git log -1 --format=%ct`). A note committed more than `drift_days` days (14 unless the hub's
  `okf.yaml` sets it) after its record is reported:
  `hub record <path> is N days older than <repo>/<note> — distill the satellite changes into the record`. A
  shallow clone anywhere in the federation is a failure (`xcheck needs full history: fetch-depth: 0`).
- **(e) Satellite index URL.** Every `satellites[].index` must end with `/<docs_root>/index.md`, the `docs_root` taken
  from that satellite's own `okf.yaml`.

Findings are written as `<repo>/<repo-relative path>:<line>: <message>`; for (b) and (c) the line is that of the
`hub:` / `repos:` key in the frontmatter, for (e) that of the URL in the hub's `okf.yaml`, and a repo-wide finding
(shallow clone) is named by the repo alone. Warnings go to stderr as `OKF XCHECK WARNING: …`, failures as one block
`okf xcheck found N problem(s):` on stderr, and a summary line on stdout: repos checked, cross-repo references
resolved, references to non-federated repos not checked, and Satellite Notes linked to Feature Records
(`okf xcheck OK: …` when nothing failed). No GitHub annotations are printed: the findings are in other repos.

With `--report=<file>`, a run with at least one failure or warning (setup errors included) writes a Markdown report
there: the findings grouped by check, one bullet each, and as footer the summary line, or after a setup error the
fixed line `okf xcheck: setup failed — no check ran.` (no summary line exists then). A clean run writes nothing
and deletes a report left at that path, so a CI step can test for the file (`[ -s <file> ]`) to open or update an
issue, and close it when the run is clean. A report that cannot be written or deleted (e.g. the path is a directory)
is reported as `okf xcheck: cannot write report …` / `cannot delete report …` and exits `1`, after the findings
and the summary line; after a setup error the exit code stays `2`.

## Running it

The tool is the self-contained jar `okf-cli-<version>-all.jar` (classifier `all`; Kotlin stdlib, JetBrains
Annotations and SnakeYAML included). It runs with `java -jar` on Java 17 or later and needs `git` on the `PATH`:

```bash
java -jar okf-cli-<version>-all.jar index --repo=.
java -jar okf-cli-<version>-all.jar check --repo=. --strict --refs-strict --code-paths-required
```

Download the jar from the [releases page](https://github.com/sharpbitstudio/okf-cli/releases) and verify it
([build-release.md](build-release.md#release)), or build it from source.

A Gradle repo can wrap the jar in `JavaExec` tasks. A sketch over a local copy of the jar, with one
`-Pokf.*` property per option:

```kotlin
// The self-contained jar, at a fixed path in the repo.
val okfJar = files("tools/okf-cli.jar")

fun okfTask(name: String, command: String, vararg flags: Pair<String, String>) =
    tasks.register<JavaExec>(name) {
        group = "documentation"
        classpath = okfJar
        mainClass.set("com.sharpbitstudio.okf.cli.Main")
        args(command, "--repo=${layout.projectDirectory.asFile}")
        val properties = flags.map { (property, option) -> option to providers.gradleProperty(property) }
        argumentProviders.add(
            CommandLineArgumentProvider { properties.mapNotNull { (option, value) -> value.orNull?.let { "$option=$it" } } },
        )
    }

okfTask("okfIndex", "index")
okfTask(
    "okfCheck", "check",
    "okf.strict" to "--strict", "okf.refs.strict" to "--refs-strict", "okf.codePaths.required" to "--code-paths-required",
).configure { mustRunAfter("okfIndex") }
okfTask("okfImpact", "impact", "okf.base" to "--base", "okf.impact.strict" to "--impact-strict")
    .configure { mustRunAfter("okfCheck") }
```

With these task names, `index_command: ./gradlew okfIndex` names the task that regenerates the indexes.

## Compatibility

- Semver. From **1.0.0 final**, within a major: for the same repository content, `okf.yaml`, command-line flags,
  git state and clock (`stale_after`), the findings, exit codes and generated indexes stay identical. New
  behaviour arrives only behind new optional keys; bug fixes are listed in `CHANGELOG.md`. Pre-releases are
  exempt.
- For message text, "identical" covers what the tool writes itself: every finding, the console lines (summary
  lines, the `OKF …` info and warning lines, the `found N problem(s)` blocks), the `okf.yaml` and command-line
  error messages (`okf <command>: …`, `okf: …`), the `::warning` / `::error` annotations with their titles, and
  the `xcheck --report` Markdown. Three things are outside it:
  - text passed through from elsewhere: the YAML parser's description of a syntax error (what follows
    `does not parse:`) and the type name it reports in `must be a YAML mapping, got …`, git's own error output
    (what follows `git <command> failed (exit N) in <dir>:`) and operating-system messages (a file that cannot be
    read or written, a program that cannot be started). They can change with a dependency, git or Java version;
  - lists of allowed values inside a message (the `okf.yaml` keys, `index_language`, `adr_readme_link`, the
    `adr_status` values, the commands): a minor version may add an entry, and the message then names it. The
    built-in document types do not change within a major version;
  - the usage text printed by `--help` and after a usage error.
- Input that a version rejects may be valid in a later minor version: a new optional key or a new allowed value
  turns the former `okf.yaml` error (exit `2`) or finding into accepted input. The other direction is covered by
  the first rule: input that is valid stays valid, with the same output.
- The file name `okf.yaml` and the keys `repo`, `hub_repo`, `satellites` never change (tooling built around
  `okf.yaml` may rely on them).
- Federation rule: a repo checked by a hub's `okf xcheck` may use a new key only once the hub's tool version
  knows it (`xcheck` parses every satellite's `okf.yaml` with the hub's jar).
