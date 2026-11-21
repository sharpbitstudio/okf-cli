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
- `okf init` starts a bundle: it writes `okf.yaml`, the bundle-root `docs/index.md` and, by default, the launcher.
- `okf wrapper` writes or refreshes the launcher `okfw` (with `okfw.cmd`) and, when there is none, writes its pin
  file `okf-wrapper.properties`, so `./okfw <command>` runs the okf-cli version the repository pins.

## OKF version

| | |
|---|---|
| OKF spec implemented | v0.2: the bundle-root `index.md` must declare `okf_version: "0.2"` |
| Latest published OKF spec | v0.2 (checked 2026-11-17) |

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

Stable: `1.0.0` is the first release under the compatibility contract
([`docs/reference.md`](docs/reference.md#compatibility)). Releases are on the
[releases page](https://github.com/sharpbitstudio/okf-cli/releases). Running the tool needs Java 17 or later and
`git` on the `PATH`; building it from source needs JDK 17 or later (CI builds on JDK 25) and runs through the Gradle
wrapper.

Upgrading from `1.0.0-alpha.1`: [`CHANGELOG.md`](CHANGELOG.md) lists what changed (defaults, new keys, console
lines) and how to keep the old output.

## Quick start

In the root of the git work tree whose docs you want to check, download the self-contained jar and its checksum
into a directory outside the work tree (here `$HOME/okf-cli`), then start a bundle with `okf init` and check it
through the launcher. Each step runs only if the one before it passed, so nothing runs the jar unless the download
and the checksum passed (`version` prints `okf-cli 1.0.0`):

```bash
VERSION=1.0.0
BASE="https://github.com/sharpbitstudio/okf-cli/releases/download/v$VERSION"
DL="$HOME/okf-cli"
JAR="$DL/okf-cli-$VERSION-all.jar"
mkdir -p "$DL" &&
  (cd "$DL" &&
    curl -fsSLO "$BASE/okf-cli-$VERSION-all.jar" &&
    curl -fsSLO "$BASE/okf-cli-$VERSION-all.jar.sha256" &&
    sha256sum -c "okf-cli-$VERSION-all.jar.sha256") &&
  java -jar "$JAR" version &&
  java -jar "$JAR" init &&
  git add okf.yaml docs/index.md okfw okfw.cmd okf-wrapper.properties .gitattributes &&
  git add --chmod=+x okfw &&
  ./okfw check --strict --refs-strict --code-paths-required
```

Where there is no `sha256sum` (macOS has `shasum`), use `shasum -a 256 -c` in its place.

`okf init` writes `okf.yaml`, the bundle root `docs/index.md`, the launcher `okfw` / `okfw.cmd`, the pin
`okf-wrapper.properties` with the SHA-256 of the jar it ran from ([`docs/launcher.md`](docs/launcher.md)) and,
when there is none, a `.gitattributes` that keeps the launcher's line ends; commit them. From then on,
`./okfw <command>` downloads the pinned jar once into a per-user cache and checks it before every run.
[`docs/build-release.md`](docs/build-release.md#release) also shows how to verify the jar's build provenance, on
which the pin then rests. To build the jar from source instead, follow the "Build" section of
[`docs/build-release.md`](docs/build-release.md#build), which produces `build/libs/okf-cli-<version>-all.jar`.

- A repository that already has `okf.yaml` gets the launcher and the pin with `java -jar "$JAR" wrapper` instead of
  `init` (then stage the files without `okf.yaml` and `docs/index.md`).
- In CI, the GitHub Action runs `check` and `impact` with the pinned version, and the xcheck action runs a hub's
  cross-repo check ([`docs/github-actions.md`](docs/github-actions.md)).
- Without the launcher, run the jar with `java -jar` ([`docs/reference.md`](docs/reference.md#running-it)).
- Writing documents: what `okf check` and `okf impact` accept, and how to acknowledge a code change with a
  `Docs-Impact:` trailer ([`docs/authoring.md`](docs/authoring.md)).

`okf init` sets `index_command: ./okfw index`, the command named in the generated indexes and in every "run …"
hint; it must be one line. All `okf.yaml` keys and their rules are in [`docs/reference.md`](docs/reference.md).

## Exit codes and CI annotations

`0` OK; `1` findings that fail under the given strictness, or the command could not complete (for example
broken frontmatter for `index`, or git not runnable); `2` a usage, `--repo` or `okf.yaml` error. Info lines go
to stdout, warnings and failures to stderr. Under GitHub Actions (`GITHUB_ACTIONS=true`) the code-reference
findings of `check` and the findings of `impact` are also printed as `::warning` / `::error` workflow commands,
GitHub's annotation format: a code-reference finding names the file and the line of the reference, an impact
finding the document it names and line 1, and an impact finding about a commit trailer no file (details in
[`docs/reference.md`](docs/reference.md#cli)).

## Documentation

The documentation is this repo's own OKF bundle ([`docs/index.md`](docs/index.md)), checked by its own build:

- [`docs/reference.md`](docs/reference.md): the CLI (options, exit codes), the `okf.yaml` schema, the bundle
  rules, the cross-repo check (`xcheck`), how to run the tool, and the compatibility contract;
- [`docs/authoring.md`](docs/authoring.md): writing documents that pass the checks (frontmatter, `code_paths` globs,
  code references and how to opt a line out, `Docs-Impact:` trailers, first steps for documents and ADRs);
- [`docs/launcher.md`](docs/launcher.md): the launcher `okfw`, its pin file, `okfw --update` / `--verify` and the
  provenance policy;
- [`docs/github-actions.md`](docs/github-actions.md): the GitHub Action (`check` and `impact`) and the xcheck
  action (a hub's cross-repo check) for CI;
- [`docs/build-release.md`](docs/build-release.md): build, CI, the release flow and how to verify release
  assets, the self-check of `docs/`, third-party licences;
- [`CHANGELOG.md`](CHANGELOG.md): changes per version.

## Licence

Apache-2.0: see [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE). The self-contained `-all` jar also bundles
third-party components (Kotlin Standard Library, JetBrains Annotations, SnakeYAML); their licences are in
`META-INF/licenses/` inside that jar and in [`third-party-licenses/`](third-party-licenses/).

OKF is a specification published by Google Cloud; okf-cli is an independent project, not affiliated with or
endorsed by Google.
