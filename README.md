# okf-cli

`okf` is a command-line tool that keeps the documentation bundle of a software repository (its `docs/` directory)
consistent with the code, locally and in CI. The bundle follows the
[Open Knowledge Format (OKF)](https://github.com/GoogleCloudPlatform/open-knowledge-format), a specification
published by Google Cloud for knowledge bundles: a directory of Markdown documents with YAML frontmatter and an
`index.md` at its root ([SPEC.md](https://github.com/GoogleCloudPlatform/open-knowledge-format/blob/main/SPEC.md)).

## Commands

- `okf index` regenerates the generated sub-indexes (`adr/index.md`, `features/index.md`, each only when its
  directory exists) and, in a hub repo, the "Repos" section of the bundle-root `index.md`.
- `okf check` validates frontmatter (including `code_paths` globs), the freshness of the generated indexes and
  the code references in document bodies.
- `okf impact` reports documents whose `code_paths` match code changed since the merge-base with a base ref and
  that were neither updated nor acknowledged by a `Docs-Impact:` commit trailer.
- `okf xcheck` runs in a hub repo and checks the hub and its satellite repos together: cross-repo code
  references, `hub:` / `repos:` links, drift between satellite notes and hub records, satellite index URLs.
- `okf version` prints `okf-cli <version>`.

## OKF version

| | |
|---|---|
| OKF spec implemented | v0.2: the bundle-root `index.md` must declare `okf_version: "0.2"` |
| Latest published OKF spec | v0.2 (checked 2026-11-12) |

How the checks relate to the spec:

- **From the spec:** `type` is required; `status`, when present, is `draft`, `stable` or `deprecated`;
  `generated` and `verified` stamps carry `by` and an ISO 8601 `at` with an offset or `Z` (`verified` may also be
  a list of stamps); `stale_after` is an ISO 8601 instant and gives a warning once it has passed; `index.md` is
  reserved and carries no frontmatter, except `okf_version` on the bundle root.
- **Stricter than the spec:** the bundle-root `index.md` with `okf_version: "0.2"` is required; `type` comes
  from a taxonomy of eight built-in types (`ADR`, `Feature Record`, `Plan`, `Guide`, `Risk Register`,
  `Convention`, `Product Spec`, `Satellite Note`), which a repo can extend with `extra_types`; ADRs are named
  `adr/ADR-NNN-<slug>.md` and need `title`, `description`, `tags`, an `adr_status` consistent with `status`, and
  an `adr_date`.
- **Additions beyond the spec:** `code_paths` (the code a document describes), `superseded_by` and `amended` on
  ADRs, `hub` / `repos` (documentation spread over several repos), `Docs-Impact:` commit trailers, code
  references in document bodies, generated sub-indexes.
- **Not checked:** `sources`, `usage_window`, the Attested Computation fields, and `log.md` (skipped as a
  reserved name).

A new OKF version arrives as a new okf-cli release; a bundle that declares `0.2` keeps the 0.2 rules within a
major version (see [Compatibility](docs/reference.md#compatibility)).

## Status

Pre-1.0. Releases are on the [releases page](https://github.com/sharpbitstudio/okf-cli/releases); the first is the
pre-release `1.0.0-alpha.1`. Running the tool needs Java 17 or later and `git` on the `PATH`; building it from
source needs JDK 17 or later (CI builds on JDK 25) and runs through the Gradle wrapper.

Before 1.0.0 some defaults still change. Today `index_language` defaults to `sr`, so the fixed text of the
generated indexes is Serbian; before 1.0.0 the defaults switch to English. Set `index_language` and
`index_command` explicitly, as in the quick start below. `main` also knows `okf.yaml` keys that `1.0.0-alpha.1`
rejects as unknown: `base_ref`, `extra_types`, `drift_days`.

## Quick start

Download the self-contained jar and its checksum; the jar runs only after the checksum passes:

```bash
VERSION=1.0.0-alpha.1
BASE="https://github.com/sharpbitstudio/okf-cli/releases/download/v$VERSION"
curl -fsSLO "$BASE/okf-cli-$VERSION-all.jar" &&
  curl -fsSLO "$BASE/okf-cli-$VERSION-all.jar.sha256" &&
  sha256sum -c "okf-cli-$VERSION-all.jar.sha256" &&
  java -jar "okf-cli-$VERSION-all.jar" version     # okf-cli 1.0.0-alpha.1
```

[`docs/build-release.md`](docs/build-release.md#release) also shows how to verify its build provenance. To build
the jar from source instead, follow the "Build" section of [`docs/build-release.md`](docs/build-release.md#build),
which produces `build/libs/okf-cli-<version>-all.jar`.

Copy the jar into the repository whose docs you want to check (here as `tools/okf-cli.jar`) and add an `okf.yaml`
at the root:

```yaml
schema_version: 1
repo: my-repo
index_language: en
index_command: java -jar tools/okf-cli.jar index
```

and a bundle root `docs/index.md`:

```markdown
---
okf_version: "0.2"
---
# my-repo — docs
```

Then run, from the root of the git work tree:

```bash
java -jar tools/okf-cli.jar index
java -jar tools/okf-cli.jar check --strict --refs-strict --code-paths-required
java -jar tools/okf-cli.jar impact --base=origin/main --impact-strict
```

`index_command` is the command named in the generated indexes and in every "run …" hint; it must be one line.
All `okf.yaml` keys and their rules are in [`docs/reference.md`](docs/reference.md).

## Exit codes and CI annotations

`0` OK; `1` findings that fail under the given strictness, or the command could not complete (for example
broken frontmatter for `index`, or git not runnable); `2` a usage, `--repo` or `okf.yaml` error. Info lines go
to stdout, warnings and failures to stderr. Under GitHub Actions (`GITHUB_ACTIONS=true`) the code-reference
findings of `check` and the findings of `impact` are also printed as `::warning` / `::error` workflow
commands, so they show up as annotations on the offending doc line.

## Documentation

The documentation is this repo's own OKF bundle ([`docs/index.md`](docs/index.md)), checked by its own build:

- [`docs/reference.md`](docs/reference.md): the CLI (options, exit codes), the `okf.yaml` schema, the bundle
  rules, the cross-repo check (`xcheck`), how to run the tool, and the compatibility contract;
- [`docs/build-release.md`](docs/build-release.md): build, CI, the release flow and how to verify release
  assets, the self-check of `docs/`, third-party licences;
- [`CHANGELOG.md`](CHANGELOG.md): changes per version.

## Licence

Apache-2.0: see [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE). The self-contained `-all` jar also bundles
third-party components (Kotlin Standard Library, JetBrains Annotations, SnakeYAML); their licences are in
`META-INF/licenses/` inside that jar and in [`third-party-licenses/`](third-party-licenses/).

OKF is a specification published by Google Cloud; okf-cli is an independent project, not affiliated with or
endorsed by Google.
