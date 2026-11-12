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
  --base=<ref>             merge-base with <ref>; absent = origin/main, fallback main;
                           empty or an all-zero SHA = skip the check                     [impact]
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

## `okf.yaml` (schema v1)

Every repo under OKF has an `okf.yaml` at its root. Parsing is strict: an unknown key, a wrong type, a
missing file or an unsupported `schema_version` is an error (exit `2`).

| Key | Required | Default | Meaning |
|---|---|---|---|
| `schema_version` | yes | — | must be `1` |
| `repo` | yes | — | repository name |
| `docs_root` | no | `docs` | repo-relative path of the OKF bundle (`/`-separated; no `./`, `..`, `//` or trailing `/`) |
| `index_command` | no | `./gradlew okfIndex` | command that regenerates the indexes; named in the generated marker, the ADR index header and every "run …" hint (one line; no backtick, no `-->`) |
| `external_repos` | no | `[]` | sibling repositories; a code reference written as `<repo>/<path>` points there and is not checked |
| `ref_extensions` | no | `[]` | extensions (no dot) added to the base list a code-span path must end with (`java`, `kt`, `kts`, `yml`, `yaml`, `xml`, `json`, `sql`, `properties`, `puml`, `toml`, `sh`, `md`) |
| `source_roots` | no | `[]` | extra repo-relative code roots, resolved like the `src/{main,test}/{java,kotlin,resources}` convention roots (incl. the descent to the base package); same path rules as `docs_root` |
| `hub_repo` | no | — | the hub repository holding the Feature Records that `Satellite Note`s point to; a repository name that must also be in `external_repos` and differ from `repo`. Required for any `Satellite Note` in the repo; absent in the hub itself |
| `satellites` | no | `[]` | hub only (not allowed together with `hub_repo`): the satellite repositories, a list of mappings with exactly `repo` (a repository name from `external_repos`, not this repo, listed once; no `/`, `\`, `.` or `..`), `description` (one line) and `index` (an `https://` URL with a host, as `java.net.URI` parses it, of the satellite's bundle-root `index.md`; one line, no whitespace and none of `(`, `)`, `<`, `>`, since it becomes a Markdown link target). Order is kept. Turns on the "Repos" section of `<docs_root>/index.md` and `okf xcheck` |
| `index_language` | no | `sr` | language of the fixed text of the generated `<docs_root>/adr/index.md` and `<docs_root>/features/index.md` (`sr` or `en`, case-sensitive): the ADR index title and intro, the "not migrated" section and its entries, the amended suffix; the marker line, status headings, entry format and the Repos section are the same in both |

The defaults of `index_command` and `index_language` change to generic ones before 1.0.0; set both explicitly.

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

- **Document types** (`type`, closed taxonomy): `ADR`, `Feature Record`, `Plan`, `Guide`, `Risk Register`,
  `Convention`, `Product Spec`, `Satellite Note`.
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
  (committer time, `git log -1 --format=%ct`). A note committed more than 14 days after its record is reported:
  `hub record <path> is N days older than <repo>/<note> — distill the satellite changes into the record`. A
  shallow clone anywhere in the federation is a failure (`xcheck needs full history: fetch-depth: 0`).
- **(e) Satellite index URL.** Every `satellites[].index` must end with `/<docs_root>/index.md`, the `docs_root` taken
  from that satellite's own `okf.yaml`.

Findings are written as `<repo>/<repo-relative path>:<line>: <message>`; for (b) and (c) the line is that of the
`hub:` / `repos:` key in the frontmatter, for (e) that of the URL in the hub's `okf.yaml`, and a repo-wide finding
(shallow clone) is named by the repo alone. Warnings go to stderr as `OKF XCHECK WARNING: …`, failures as one block
`okf xcheck found N problem(s):` on stderr, and a summary line on stdout: repos checked, cross-repo references
resolved, references to non-federated repos not checked, and Satellite Notes linked to Feature Records
(`okfXcheck OK: …` when nothing failed). No GitHub annotations are printed: the findings are in other repos.

With `--report=<file>`, a run with at least one failure or warning (setup errors included) writes a Markdown report
there: the findings grouped by check, one bullet each, and the summary line as footer. A clean run writes nothing
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
- The file name `okf.yaml` and the keys `repo`, `hub_repo`, `satellites` never change (tooling built around
  `okf.yaml` may rely on them).
- Federation rule: a repo checked by a hub's `okf xcheck` may use a new key only once the hub's tool version
  knows it (`xcheck` parses every satellite's `okf.yaml` with the hub's jar).
