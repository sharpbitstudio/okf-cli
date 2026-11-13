# Changelog

## Unreleased

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
