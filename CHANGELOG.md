# Changelog

## 1.0.0 — unreleased

- The `okf` CLI over an OKF v0.2 docs bundle, configured per repo in `okf.yaml` (schema version 1):
  `okf index`, `okf check`, `okf impact`, `okf xcheck` and `okf version` (prints `okf-cli <version>`);
  configuration errors name `okf-cli` and point to
  https://github.com/sharpbitstudio/okf-cli/blob/main/docs/reference.md.
- Licensed under Apache-2.0 (`LICENSE`, `NOTICE`); the self-contained `-all` jar also carries the licences of
  the third-party components it bundles.
- CI: the skipped jobs of a pull request from a branch of this repo no longer share their names with the checks
  meant to be required on `main`, `Build + test` and `OKF docs bundle check (+ docs impact)`.
