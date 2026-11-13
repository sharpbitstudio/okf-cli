---
okf_version: "0.2"
---
# okf-cli — docs

This directory is an [Open Knowledge Format (OKF) v0.2](https://github.com/GoogleCloudPlatform/open-knowledge-format/blob/main/SPEC.md) bundle: the documentation of okf-cli. It is checked by the code this repo builds: `./gradlew okfIndex okfCheck okfImpact` runs `com.sharpbitstudio.okf.cli.Main` from this build's own output, not a released jar. Settings live in the root [`okf.yaml`](../okf.yaml); the landing page is the root [`README.md`](../README.md).

## Documents

| File | What's inside |
|---|---|
| [reference.md](reference.md) | The `okf` CLI (commands, options, exit codes), the `okf.yaml` schema, the bundle rules for multi-repo setups (`Satellite Note`, `repos:`, optional sub-indexes, the hub's "Repos" section, line endings), the hub's cross-repo check (`xcheck`), how to run the tool, and the compatibility contract |
| [build-release.md](build-release.md) | Build and test (bytecode 17, the three jars and the licence files inside them), CI, the release flow (assets, checksum, build-provenance attestation), how this repo checks its own docs, dependency updates, and the third-party licences |
