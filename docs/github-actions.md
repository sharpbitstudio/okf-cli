---
type: Guide
title: "The okf-cli GitHub Action"
description: "How to run okf check and okf impact in a GitHub Actions workflow with the okf-cli GitHub Action: usage, inputs and output, how it chooses the impact base and strictness per event (Dependabot included), the full-history rule, requirements (Java, checkout, permissions), how the action's commit and the repository's pin relate, pull requests from forks, the optional provenance check, and its exit codes and messages. Also the xcheck action, which clones a hub's satellites and runs okf xcheck: usage, inputs and outputs, the token and permissions, exit codes and messages. Read it before adding either action to a workflow or when an okf-cli action step fails."
tags: [github-actions, action, ci, impact, pin, provenance, xcheck]
code_paths: ["action.yml", "action/**", "xcheck/**"]
---
# The okf-cli GitHub Action

The repository root of okf-cli is a GitHub Action. It runs `okf check` with every strict flag and then `okf impact`,
with the okf-cli version the checked repository pins in `<repo>/okf-wrapper.properties`
([launcher.md](launcher.md#the-pin-file)), through the action's own copy of the launcher: the jar is downloaded from
this project's release into the runner's temporary directory, checked against the pinned SHA-256 before every run,
and run with the runner's Java. It chooses the base and the strictness of `okf impact` from the event, so one step
fits pull requests, merge queues and pushes.

The action uses no other action: it does not check out, set up Java or cache anything. Its logic is in
[`action/check.sh`](../action/check.sh) and [`action/impact-args.sh`](../action/impact-args.sh), POSIX `sh` scripts
that [`action.yml`](../action.yml) runs in one `bash` step; every input and event fact reaches them through
environment variables. Like the launcher, it only fetches, verifies and invokes the jar: every check is the jar's.

A second action, in `xcheck/`, runs the cross-repo check of a hub and its satellites:
[The xcheck action](#the-xcheck-action).

## Usage

```yaml
name: docs
on:
  pull_request:
  push:
    branches: [main]
permissions:
  contents: read
jobs:
  okf:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7
        with:
          fetch-depth: 0            # okf impact needs the history and origin/<base branch>
          persist-credentials: false
      # Optional: the hosted images have a Java; pin one when the job needs a particular Java.
      # - uses: actions/setup-java@v6
      #   with: {distribution: temurin, java-version: 21}
      - uses: sharpbitstudio/okf-cli@<commit-sha> # <version>
```

Reference the action by the full SHA of a commit of this repository, with the release it belongs to as a comment:
the release notes name the commit each release was built from. A commit cannot name itself, so this page shows
`<commit-sha>` as a placeholder. A release tag (`@v<version>`) also works and cannot move, since releases are
immutable. There is no moving major tag.

The repository needs the pin (and the `okf.yaml` and bundle `okf check` reads): `okf init` or `okf wrapper` write it
([launcher.md](launcher.md#first-bootstrap)).

## Inputs and output

| Input | Default | Meaning |
|---|---|---|
| `repo` | `.` | The work-tree root to check, relative to the workspace; not empty. The pin is `<repo>/okf-wrapper.properties`. |
| `strict` | `true` | `okf check --strict=<value>`: files without frontmatter are failures. |
| `refs-strict` | `true` | `okf check --refs-strict=<value>`: broken code references are failures. |
| `code-paths-required` | `true` | `okf check --code-paths-required=<value>`. |
| `impact` | `true` | Run `okf impact` after `okf check`; `false` skips it. |
| `base` | `auto` | `auto`, empty or blank: the base from the table below. Any other value is passed as `--base=<value>`. |
| `impact-strict` | `auto` | `auto`: the strictness from the table below. `true` or `false`: `--impact-strict=<value>`. |
| `verify-provenance` | `false` | Run `okfw --verify` before `okf check` (see [Provenance](#provenance)). |

The three `check` flags are opt-outs for a bundle that is still being migrated; they never depend on the event. An
empty or blank `base` (only spaces) means `auto`, so an expression that evaluates to nothing on some event cannot
turn the impact check off: that is `impact: false`.

Output `version`: the pinned okf-cli version, as `okf version` of the pinned jar reports it. It is set only when that
is a version in the form okf-cli releases use (`1.2.3`, `1.2.3-rc.1`).

## Base and strictness

With `base` and `impact-strict` at `auto`, the action passes these arguments to `okf impact`:

| Event | `--base` | `--impact-strict` |
|---|---|---|
| `pull_request` | `origin/<base branch of the pull request>` | `true`, unless the pull request's author is `dependabot[bot]` |
| `merge_group` | the base SHA of the merge group | `false` |
| `push` to the default branch | the commit the branch moved from (`github.event.before`) | `false` |
| `push` to a merge-queue branch (`refs/heads/gh-readonly-queue/…`) | not passed | `false` |
| `push` or `workflow_dispatch` on another branch | not passed: `okf impact` uses `origin/<base_ref>`, else `<base_ref>` (`base_ref` in `okf.yaml`, default `main`) | `true`, unless the actor is `dependabot[bot]` |
| anything else (a tag push, `schedule`, `pull_request_target`, `workflow_dispatch` on the default branch, …) | not passed | `false` |

- The default branch is the repository's, from the event; `main` when the event does not name one.
- On a push that creates the default branch the commit it moved from is all zeros, and `okf impact` skips the check;
  after a force-push it may be unreachable, and `okf impact` warns and skips.
- Findings are warnings where a merge may have dropped the commit trailers (a squash merge, a merge queue) and
  where Dependabot cannot add a `Docs-Impact:` trailer. Dependabot is recognised by the pull request's author or the
  actor of the run, never by the branch name: anyone who can push a branch can name it `dependabot/…`.
- An explicit `base` or `impact-strict` replaces its column of the table; the other column still comes from the
  table. A repository whose pull requests target another branch than its `base_ref` is covered by the
  `pull_request` row; for a push to such a branch, pass `base` yourself.

The action writes the arguments it uses to the log, as
`okf-cli action: okf impact --repo=… --base=… --impact-strict=…`.

## Full history

`okf impact` compares with the merge-base of `HEAD` and the base, and reads the commit trailers in between: it needs
the history. Check out with `fetch-depth: 0`. When `impact` is on and the repository is a shallow clone (the default
of `actions/checkout`), the action stops before running anything, with exit `2` and the error
`okf impact needs the full history: check out with fetch-depth: 0 (or set impact: false)`.

## Requirements

- A checkout of the repository: the action reads `<repo>` from the workspace.
- Java 17 or later on the runner: `JAVA_HOME`, else `java` on the `PATH`, as for the launcher
  ([launcher.md](launcher.md#what-okfw-does)). The GitHub-hosted images provide one; `actions/setup-java` selects
  another.
- `git`: the action checks for a shallow clone with it, and okf-cli reads the repository with it.
- `bash`, and a POSIX `sh` with `grep` and `tr` for the action's scripts, plus the tools the launcher needs
  (`curl`, `sha256sum` or `shasum`, and the others [launcher.md](launcher.md#requirements) lists): on Linux and
  macOS runners, and on Windows runners through Git for Windows, as the hosted images have them.
- Permissions: the caller grants `contents: read` (for the checkout). The action itself needs no token, and passes
  none on: the release download is public.

The jar is cached in the runner's temporary directory (`OKFW_CACHE_DIR` = `<runner.temp>/okf-cli-cache`), which is
emptied for every job: each job downloads it once.

## Versions: the action's commit and the pin

The two are independent. The commit in `uses:` chooses the action (its inputs, the table above, the launcher copy
it runs); the pin chooses okf-cli, whose findings decide the result. Changing the okf-cli version is a change to the
pin (`./okfw --update <version>`), not to the workflow, and moving the action to a later commit does not change the
findings of a given pin. A launcher or action of the same major version reads every pin written by one
([reference.md](reference.md#compatibility)).

The action passes only options that every okf-cli release accepts, so it runs any pinned release.

## Pull requests from forks

On a pull request from a fork, the pin is the pull request's: its author chooses the version. The trust anchor is
then the fixed download URL: the launcher downloads only release assets of this project, and only the one whose
SHA-256 the pin names, so a fork can select another genuine release of okf-cli, never other code. Run the action on
`pull_request`, not `pull_request_target`, so that a fork's run gets a read-only token and no secrets.

## Provenance

`verify-provenance: true` runs `okfw --verify` before `okf check`: it checks the build provenance of the pinned
release with the GitHub CLI, under the same policy and minimum `gh` version as the launcher
([launcher.md](launcher.md#the-provenance-policy)). `gh` must be on the runner (the hosted images have it); it runs
without a token. When the check fails, the action stops with its exit code (`3`) before `okf check`. It is off by
default because the pin already fixes the SHA-256 of the jar each run uses, and the provenance check adds a network
dependency to every run; it suits a workflow that wants each run to re-check the release itself.

## Exit codes and messages

The step fails when the action exits with a code other than `0`:

- `2` with one error annotation, nothing run: an empty `repo` (`input 'repo' must not be empty`), a `repo` or
  `base` that holds a line break, a tab or another control character
  (`input '<name>' must be one line without control characters`; each value is passed to okf-cli as one argument),
  an input that is not one of its allowed values (`input '<name>' must be true or false`,
  `input 'impact-strict' must be auto, true or false`), or the shallow clone above;
- the code of `okfw --verify`, when it fails;
- otherwise the first non-zero exit code of `okf check` and `okf impact` ([reference.md](reference.md#cli)), or the
  launcher's `2` and `3` ([launcher.md](launcher.md#exit-codes-and-messages)). `okf impact` is not run after a
  `check` exit of `2` or `3` (a usage, `okf.yaml` or pin error, or no verified jar or Java).

The error annotations have the title `okf-cli`. Before each command, the action writes one line to standard error,
`okf-cli action: <command and arguments>`; the output of the launcher and the jar, with their own annotations, is
passed on unchanged. The jar's annotations (`::error file=…`, `::warning file=…`) name files relative to `repo`, so
with a `repo` other than the workspace root GitHub may not link them to the file: check out the repository at the
workspace root for annotations that point at the offending line.

## The xcheck action

The directory `xcheck/` of this repository is a second action, for the hub of a federation
([reference.md](reference.md#cross-repo-check-xcheck)). It clones the hub's satellites into a workspace and runs
`okf xcheck` on the hub with the okf-cli version the hub pins in `<hub>/okf-wrapper.properties`, through the same
copy of the launcher as the action above, so the hub's version reads every satellite's `okf.yaml`. Like that action,
it uses no other action and parses no `okf.yaml`: its logic is in [`xcheck/xcheck.sh`](../xcheck/xcheck.sh), which
[`xcheck/action.yml`](../xcheck/action.yml) runs in one `bash` step, and every check is the jar's.

### Usage

```yaml
name: okf xcheck
on:
  schedule:
    - cron: '0 6 * * 1'           # every Monday
  workflow_dispatch:
permissions:
  contents: read
jobs:
  xcheck:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7
        with:
          fetch-depth: 0            # okf xcheck needs the hub's history
          persist-credentials: false
      - id: xcheck
        uses: sharpbitstudio/okf-cli/xcheck@<commit-sha> # <version>
        with:
          satellites: web-app other-owner/api
          token: ${{ secrets.SATELLITES_READ_TOKEN }}   # only for private satellites
      - if: failure() && steps.xcheck.outputs.report != ''   # empty after an input or clone error
        uses: actions/upload-artifact@v7
        with:
          name: okf-xcheck-report
          path: ${{ steps.xcheck.outputs.report }}
          if-no-files-found: ignore
```

Reference it by the full SHA of a commit of this repository, as the action above. The step fails when the
federation has problems; the last step keeps the report (the job summary holds it too). A workflow may as well open
or update an issue from the report, and close it when a run is clean: the report exists only when there is
something to report.

### Inputs and outputs

| Input | Default | Meaning |
|---|---|---|
| `hub` | `.` | The hub's work-tree root, relative to the job's working directory (`GITHUB_WORKSPACE`); not empty. The pin is `<hub>/okf-wrapper.properties`. |
| `satellites` | empty | The satellites to clone, separated by whitespace: `owner/repo`, or `repo` for a repository of the owner of the repository the workflow runs in. Empty: nothing is cloned. |
| `token` | `github.token` | The token git uses for the clones (see [Token and permissions](#token-and-permissions)). |
| `workspace` | empty: `<runner.temp>/okf-xcheck-workspace` | The directory that holds every satellite as `<workspace>/<repo>` (`okf xcheck --workspace`); a relative path is relative to the job's working directory. |

- Every name (owner and repository) consists of letters, digits, `.`, `_` and `-`, and is not `.` or `..`. All
  entries are checked before the first clone.
- Each satellite is cloned from its default branch, with its full history and without tags, from
  `<server>/<owner>/<repo>.git` (`<server>` is the `GITHUB_SERVER_URL` of the run) into `<workspace>/<repo>`.
- A `<workspace>/<repo>` that already exists is used as it is, neither cloned nor updated. A job may thus check out
  some satellites itself (with `actions/checkout` and its `path`) and list only the others, or none.

| Output | Meaning |
|---|---|
| `report` | The path of the report of `okf xcheck --report` (`<runner.temp>/okf-xcheck-report.md`). The file exists only when the run found problems or warnings, or a setup error; the action also appends it to the job summary. |
| `exit-code` | The exit code of `okf xcheck`, as the launcher returned it. |

Both outputs are set once `okf xcheck` has run, not after an input or clone error. A report left at that path by an
earlier run in the same job is removed when the action starts.

### Token and permissions

- git uses the token only when a server asks for credentials. It gets it through an askpass helper,
  [`xcheck/askpass.sh`](../xcheck/askpass.sh), as the password of the user `x-access-token`, from a variable set for
  each `git clone` alone. The token never appears in a URL, a command line or a git configuration, and it is not
  passed on to the launcher or okf-cli.
- Every clone runs with git's credential helpers switched off (`-c credential.helper=`) and without prompts
  (`GIT_TERMINAL_PROMPT=0`, `GCM_INTERACTIVE=never`), so no credential is stored, and a missing or rejected token
  fails the clone instead of waiting for input. Git LFS content is not downloaded (`GIT_LFS_SKIP_SMUDGE=1`).
- The default, the workflow's `github.token`, can read the repository the workflow runs in, and public repositories
  need no token. For private satellites, pass a secret holding a token with read access to their contents, such as
  a fine-grained personal access token or a GitHub App installation token with the `Contents: read` permission.
- The job needs `contents: read`, for the hub's checkout. The action writes to no repository.

### Requirements

As for the action above ([Requirements](#requirements)); the action's script uses `tr`, `cat` and `rm`. The
hub's checkout needs its full history (`fetch-depth: 0`): `okf xcheck` reports a shallow clone anywhere in the
federation as a failure. The jar is cached in the same place as for the action above. The `e2e` workflow of this
repository runs the action on `ubuntu-latest`.

### Exit codes and messages

The step fails when the action exits with a code other than `0`:

- `2` with one error annotation (title `okf-cli`), and `okf xcheck` not run:
  - an empty `hub` (`input 'hub' must not be empty`);
  - a `hub` or `workspace` that holds a line break, a tab or another control character
    (`input '<name>' must be one line without control characters`);
  - a satellite entry that is not a name or `owner/name`
    (`input 'satellites': entry <n> is not owner/repo or repo (each name only letters, digits, '.', '_' and '-', and not '.' or '..')`;
    entries are counted from 1 and not repeated in the log);
  - a failed clone, after git's own message (`cannot clone <owner>/<repo>: git clone exited with <code>`); the
    satellites cloned before it stay in the workspace;
  - outside GitHub Actions only, no workspace at all (`input 'workspace' must not be empty`).
- otherwise the exit code of `okf xcheck` ([reference.md](reference.md#cli)): `0` when the federation is clean or
  has warnings only, `1` for problems, `2` for a setup error such as a satellite missing from the workspace; or the
  launcher's `2` and `3` ([launcher.md](launcher.md#exit-codes-and-messages)).

Before each clone, and before `okf xcheck`, the action writes one line to standard error:
`okf-cli xcheck action: git -c credential.helper= clone --no-tags -- <url> <directory>`,
`okf-cli xcheck action: <directory> exists, not cloned`, or
`okf-cli xcheck action: okf xcheck --repo=<hub> --workspace=<workspace> --report=<file>`. The output of git, the
launcher and the jar is passed on unchanged.
