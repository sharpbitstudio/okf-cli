# Security policy

## Reporting a vulnerability

Report a vulnerability privately, through GitHub's private vulnerability reporting: open
<https://github.com/sharpbitstudio/okf-cli/security/advisories/new> (the **Report a vulnerability** form). Do not
open a public issue or pull request for it.

Include what you can: the component and version (the okf-cli version, or the commit of an action), the steps or
input that reproduce the problem, and what an attacker could gain. The report stays private while it is worked on;
the fix and its disclosure are discussed with you in the advisory.

## Supported versions

okf-cli follows semantic versioning, and its releases are immutable: a published release is never changed, so a
fix is a new version.

| Version | Supported |
|---|---|
| The latest `1.x` release | Yes: a fix comes as a new `1.x` release. |
| Earlier `1.x` releases | No: a later `1.x` release keeps the findings and exit codes for input that an earlier one accepts, apart from listed bug fixes, and may accept input that an earlier one rejects ([compatibility](docs/reference.md#compatibility)), so move to the fixed release. |
| Pre-releases (`-alpha.N`, `-rc.N`) | No. |

The GitHub Actions are referenced by commit (or by a release tag): a fix to an action is a commit on `main`, which a
workflow takes by moving its `uses:` reference to that commit, or to the tag of the next release.

## Scope

In scope:

- the `okf` CLI: the jar `okf-cli-<version>-all.jar` built from this repository; it reads repository content
  (`okf.yaml`, Markdown and its YAML frontmatter) and runs `git`;
- the launcher `okfw` and `okfw.cmd` (`launcher/`), as the jar embeds them, `okf init` and `okf wrapper` write them
  and both GitHub Actions run their own copy: the download, the SHA-256 check against the pin, and the
  build-provenance policy of `okfw --update` and `okfw --verify` ([docs/launcher.md](docs/launcher.md));
- the GitHub Actions: `action.yml` with `action/`, and `xcheck/action.yml` with `xcheck/`, including how the xcheck
  action gives its token to `git` ([docs/github-actions.md](docs/github-actions.md));
- the release: the workflow that builds the release assets and attests the build provenance of the `all` jar,
  published with its checksum file and the attestation's Sigstore bundle, and the verification commands in
  [docs/build-release.md](docs/build-release.md#release).

A vulnerability in a bundled component (the Kotlin Standard Library, JetBrains Annotations, SnakeYAML) is in scope
when okf-cli is affected by it; otherwise, report it to that project. Vulnerabilities in Java, `git`, `curl`, the
GitHub CLI or GitHub Actions runners themselves belong to those projects.

## How fixes are published

A fix to the CLI or the launcher is released as a new version, listed in `CHANGELOG.md`. A fix to an action is a
commit on `main`, also listed in `CHANGELOG.md`. Where it applies, a GitHub security advisory names the fixed
version or commit. A repository takes a fixed release with `./okfw --update <version>`, which also refreshes its
launcher files. Without `gh` 2.102.0 or later, it verifies the jar another way, writes the pin by hand and runs
`./okfw wrapper` ([without gh](docs/launcher.md#without-gh)); when `--update` pins the release but cannot write the
launcher files, `./okfw wrapper` writes them ([recovery](docs/launcher.md#recovery)). A failed provenance check is
never a reason to pin by hand. A workflow takes a fixed action, and also a launcher fix, by moving its `uses:`
reference to a commit or release tag that has the fix: the actions run the launcher copy of their own commit, so the
pin alone does not change it.
