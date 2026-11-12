# Changelog

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
