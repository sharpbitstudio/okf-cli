---
type: Guide
title: "The okfw launcher and the pin file"
description: "How ./okfw runs the okf-cli version a repository pins: the pin file okf-wrapper.properties (place, grammar, the bytes okf-cli writes, hand-written pins), what the launcher does (download, SHA-256 check, Java lookup), okfw --update and okfw --verify (the build-provenance policy with the GitHub CLI, its minimum version, the version tie, the token-free gh environment, the manual and recovery paths), its command line, environment, exit codes and messages, the cache and offline use, requirements, Windows, and the first bootstrap. Read it before adding the launcher to a repository, before changing the pinned version, or when ./okfw stops with an okfw: message."
tags: [launcher, okfw, pin, okf-wrapper, provenance, gh, windows, bootstrap]
code_paths: ["launcher/**"]
---
# The okfw launcher and the pin file

`okfw` runs okf-cli in a repository without a manual download: `./okfw check --strict` behaves like
`java -jar okf-cli-<version>-all.jar check --strict`, with the version the repository pins. `okf init` writes the
launcher together with a new bundle, and `okf wrapper` adds it to an existing repository or refreshes it
([reference.md](reference.md#the-launcher-okf-wrapper)). Both write these files at the work-tree root:

| File | What it is |
|---|---|
| `okfw` | The launcher, a POSIX `sh` script (LF line ends, executable). |
| `okfw.cmd` | A shim for `cmd` and PowerShell on Windows, which runs `okfw` with Git for Windows' `sh.exe` (CRLF line ends). |
| `<repo>/okf-wrapper.properties` | The pin: the okf-cli version this repository runs, and the SHA-256 of its jar. |
| `.gitattributes` | Created when absent, so `okfw` keeps LF and `okfw.cmd` CRLF on every checkout (an existing one is never changed). |

Commit all of them, `okfw` as executable: `git add --chmod=+x okfw` records the executable bit in git on every system
(Windows file systems cannot hold it).

The launcher only fetches, verifies and invokes the jar: every check is the jar's. Its source is
[`launcher/okfw`](../launcher/okfw) and [`launcher/okfw.cmd`](../launcher/okfw.cmd) in this repository; each
okf-cli jar embeds the copy it was built with, and `okf wrapper` writes that copy.

## What `./okfw` does

1. Reads the pin file next to the script (or `OKFW_PIN`), and stops with exit `2` when it is missing or malformed.
2. Finds a SHA-256 tool (`sha256sum`, else `shasum -a 256`) and Java (`$JAVA_HOME/bin/java` when `JAVA_HOME` is set
   and that file is executable, else `java` on the `PATH`). It runs `java -version` once: a version below 17 stops it
   with exit `3`; a version string it cannot read does not.
3. Looks for the jar in the cache, `<cache>/okf-cli/<version>/okf-cli-<version>-all.jar`. A cached jar whose SHA-256
   differs from the pin is deleted. When there is no matching jar, it downloads
   `https://github.com/sharpbitstudio/okf-cli/releases/download/v<version>/okf-cli-<version>-all.jar` once, with
   `curl` over HTTPS only (redirects too, TLS 1.2 or later, no `.curlrc`), to a temporary file next to the cache
   path; it checks the SHA-256 of the download against the pin and only then moves it into place. A mismatch stops it
   with exit `3` and leaves no file behind.
4. Runs `java -jar <the jar> <arguments>`, replacing itself (`exec`): the jar's exit code is the launcher's, and the
   jar's output is all there is on standard output.

The SHA-256 is checked before every run, not only after a download. The download URL is fixed in the script: a pin
can only select a release asset of this project, and only the one whose SHA-256 it names. There is no option or
variable that changes the URL or skips the check.

## The pin file

The pin file is `<repo>/okf-wrapper.properties`: at the repository root, next to `okf.yaml`.

```properties
# okf-cli version pinned for this repository; read by ./okfw and the okf-cli GitHub Action.
# Docs: https://github.com/sharpbitstudio/okf-cli/blob/main/docs/launcher.md
version=1.2.3
sha256=<64 hex digits>
```

These are the bytes okf-cli writes (two comment lines, then `version` and `sha256`; LF line ends, ASCII; the hash
in lowercase); the comment text may change between versions. Readers accept:

- lines that are blank, comments (the first character is `#`), or `key=value` with nothing between the key, `=` and
  the value;
- exactly the two keys `version` and `sha256`, each once, in any order; any other key is an error;
- `version`: a SemVer 2.0 version without build metadata and without a leading `v`, such as `1.2.3` or
  `1.2.3-rc.1` (the versions okf-cli releases);
- `sha256`: 64 hexadecimal digits, in either case;
- a UTF-8 byte order mark at the start, and spaces, tabs and a carriage return at the end of each line (an editor's
  or a CRLF checkout's), which are ignored. Nothing else is tolerated.

`okf wrapper` and `okf init` write a pin only when there is none and they run from the `all` jar of a release; the
pin then names that jar's own version and SHA-256. They never read or change an existing pin. A pin can also be
written by hand, from a jar verified as [build-release.md](build-release.md#release) shows: the two lines
`version=<version>` and `sha256=<the SHA-256 of okf-cli-<version>-all.jar>` are enough.

## Command line and environment

```text
./okfw <okf arguments>        run okf-cli with these arguments, unchanged (okfw in cmd)
./okfw --update <version>     pin that release after checking its build provenance, then refresh the launcher
./okfw --verify               check the build provenance of the pinned release; changes nothing
```

`--update` and `--verify` are the launcher's own options only as the first argument; anywhere else they go to
okf-cli like every other argument. See [Changing and verifying the pin](#changing-and-verifying-the-pin).

| Variable | Meaning |
|---|---|
| `OKFW_PIN` | The pin file to read instead of the one next to the script. |
| `OKFW_CACHE_DIR` | The cache directory; default `$XDG_CACHE_HOME`, else `$HOME/.cache`. |
| `JAVA_HOME` | The Java installation to use (its `bin/java`), before `java` on the `PATH`. |

`curl` reads the standard proxy variables (`https_proxy`, `no_proxy`). `OKFW_PIN` and `OKFW_CACHE_DIR` change
where the launcher reads and writes, never the download URL; the cache directory must still be one only you can
write to (see [Cache and offline use](#cache-and-offline-use)).

## Exit codes and messages

The launcher's exit code is the jar's, except for its own errors: `2` for a usage or pin error, `3` when no verified
jar or no runtime is available. Its own lines go to standard error and start with `okfw: `; on the run path it writes
nothing to standard output. `<path>` is the pin path as the launcher used it.

Exit `2`, nothing runs:

- `okfw: pin file not found: <path> — run okfw --update <version>`
- `okfw: <path>: line N is not key=value`
- `okfw: <path>: unknown key '<key>'`
- `okfw: <path>: '<key>' is given more than once` (`<key>` is `version` or `sha256`)
- `okfw: <path>: '<key>' is missing` (`version` is checked first)
- `okfw: <path>: version '<value>' is not a version such as 1.2.3 or 1.2.3-rc.1`
- `okfw: <path>: sha256 must be 64 hexadecimal digits`
- `okfw: <path>: is not a text file (it contains a NUL byte)` (checked before anything else in the file)
- `okfw: --update needs one version, such as okfw --update 1.2.3 (releases: https://github.com/sharpbitstudio/okf-cli/releases)`
  (no argument after `--update`, or more than one)
- `okfw: '<version>' is not a version such as 1.2.3 or 1.2.3-rc.1` (the argument of `--update`; when it is not
  printable ASCII of at most 64 characters on one line:
  `okfw: --update: the argument is not a version such as 1.2.3 or 1.2.3-rc.1`)
- `okfw: --update cannot be used with OKFW_PIN set`
- `okfw: --verify takes no arguments`

The first problem in line order is reported, then a missing key, then the values. A pin is input a pull request can
change, so a key or version is echoed only when it is printable ASCII of at most 64 characters; otherwise the line
names its number instead: `okfw: <path>: line N: unknown key`,
`okfw: <path>: line N: version is not a version such as 1.2.3 or 1.2.3-rc.1`.

Exit `3`, nothing runs:

- `okfw: no SHA-256 tool found (sha256sum or shasum)`
- `okfw: Java not found — okf-cli needs Java 17 or later (set JAVA_HOME or put java on the PATH)`
- `okfw: Java not usable: <java> -version exited <n> — okf-cli needs Java 17 or later (set JAVA_HOME or put java on the PATH)`
  (the probe failed, such as the `java` of macOS without a JDK installed)
- `okfw: Java 17 or later is required; <java> is version <major>` (`<java>` is the path the launcher found)
- `okfw: no cache directory: set OKFW_CACHE_DIR, XDG_CACHE_HOME or HOME` (all three unset or empty)
- `okfw: cannot use the cache: <cache>/okf-cli/<version>/okf-cli-<version>-all.jar is not a file` (a directory or
  another non-file sits at the jar's path)
- `okfw: cannot replace the cached jar <cache>/okf-cli/<version>/okf-cli-<version>-all.jar` (a cached jar that does
  not match the pin cannot be deleted)
- `okfw: curl not found` (only when a download is needed)
- `okfw: download failed: <url>` (after `curl`'s own message)
- `okfw: SHA-256 mismatch for okf-cli-<version>-all.jar: the pin says <hex>, the download has <hex>`
- `okfw: sh.exe not found - okfw.cmd runs okfw with Git for Windows' sh (install Git for Windows)` (from
  `okfw.cmd`; the file is ASCII, so the dash is a plain hyphen)

Exit `3` from `--update` and `--verify` only (`<end>` is `the pin was not changed` for `--update` and
`nothing was changed` for `--verify`):

- `okfw: gh not found — --update and --verify check the build provenance with the GitHub CLI 2.102.0 or later (https://cli.github.com)`
- `okfw: gh <found> is too old — 2.102.0 or later is required` (`<found>` is the version `gh --version` names, or
  `(unknown version)` when its first line does not read `gh version X.Y.Z…`)
- `okfw: the build provenance of okf-cli-<version>-all.jar could not be verified (gh attestation verify exited <n>); <end>`
  (after the output of `gh`)
- `okfw: the verified jar reports '<output>', not 'okf-cli <version>'; <end>` (when the output is not printable
  ASCII of at most 64 characters on one line, or the jar exits with another code than `0`:
  `okfw: the verified jar does not report 'okf-cli <version>'; <end>`)
- `okfw: cannot write the pin: <path> is not a file` (any symbolic link, also one to a regular file, a directory or
  anything else that is not a regular file at the pin's path; it is left as it is, and so is a link's target)
- `okfw: cannot write the pin: <path>` (after the system tool's message: the new pin file cannot be created,
  written or moved over the old one; the old pin stays)
- `okfw: okf-cli <version> is pinned, but its launcher files could not be written (okf wrapper exited <n>)` (the pin
  is written; see [Recovery](#recovery))

When the cache directory cannot be created or written, the system tool's message comes first, and the launcher exits
with `3`. When a download cannot be moved onto the cache path because a jar is already there (on Windows, a running
Java holds the jar a parallel first run just placed), that jar is used if it matches the pin; otherwise the launcher
exits with `3` after the system tool's message.

Before a download, one progress line goes to standard error: `okfw: downloading okf-cli <version>` (once per
invocation, also when `--verify` downloads only the Sigstore bundle).

On standard output, `--update` prints `okfw: pinned okf-cli <version> (sha256 <hex>)` or
`okfw: okf-cli <version> is already pinned`, followed by the output of `okf wrapper`; `--verify` prints
`okfw: okf-cli <version> verified (built by .github/workflows/release.yml on refs/heads/main)`.

## Changing and verifying the pin

`./okfw --update <version>` moves the repository to another release in one step, and `./okfw --verify` checks the
release the pin names. Both need the [GitHub CLI](https://cli.github.com) (`gh`) 2.102.0 or later; neither needs a
GitHub account or token.

`--update <version>`:

1. Checks the argument: exactly one, a version as in the pin (`1.2.3`, `1.2.3-rc.1`; no `v`, no build metadata).
   `OKFW_PIN` must not be set: `--update` writes the pin next to the script.
2. Finds a SHA-256 tool, Java (as for a run), `curl` and `gh`, and checks the version of `gh`.
3. Downloads `okf-cli-<version>-all.jar` and `okf-cli-<version>-all.jar.sigstore.json` from the release
   `v<version>` (the URL form and `curl` options of a run) into the cache: the jar to a temporary file, the bundle
   under its own name into a new temporary directory (`gh` accepts a bundle only as a `.json` or `.jsonl` file).
4. Checks the build provenance of the jar against the bundle ([the policy](#the-provenance-policy)).
5. The version tie: runs the verified jar's `version` command, which must exit with `0` and print exactly
   `okf-cli <version>`, followed by at most one line end (LF, or CRLF on Windows).
6. Moves the jar to its cache path and writes the pin next to the script: a new file beside it, moved over the old
   one, with the bytes okf-cli writes and the mode the umask gives (as `okf wrapper` writes it); the new file is
   written in a new temporary directory beside the pin, which is removed afterwards. Any symbolic link at the pin's path (also one to a regular file, even one that
   holds the very pin that would be written), and anything else that is not a regular file there, is refused before
   the pin is read and left as it is. A pin that already names this version and this SHA-256 is left as it is
   (`is already pinned`); any other is replaced, also one of the same version with another SHA-256 (so a
   hand-written pin becomes a verified one), a malformed one, or none. A pin that cannot be written (a read-only
   directory, say) stops `--update` with exit `3`, and the old pin stays.
7. Runs the new jar's `okf wrapper --repo=<the directory of the pin>`, which refreshes `okfw` and `okfw.cmd` to the
   copies that version embeds (they may be replaced while they run). Its output passes through.

A failure in steps 1 to 5 leaves the pin and the launcher files as they were (exit `2` in step 1, `3` otherwise).
`--update` to the version already pinned changes nothing: in a clean work tree, `git status` stays clean.

`--verify` reads the pin (`OKFW_PIN` is allowed), takes the jar from the cache or downloads it as a run does (with the
SHA-256 check against the pin), downloads the bundle, and applies the same policy and version tie. It writes nothing
but the cache, and exits `0` when both hold.

### The provenance policy

`gh` runs as:

```text
gh attestation verify <jar> --bundle <bundle> --repo sharpbitstudio/okf-cli \
  --predicate-type https://slsa.dev/provenance/v1 \
  --cert-identity https://github.com/sharpbitstudio/okf-cli/.github/workflows/release.yml@refs/heads/main \
  --source-ref refs/heads/main --deny-self-hosted-runners
```

- The bundle is the release's third asset: with `--bundle`, `gh` reads the attestation from that file instead of
  fetching it from GitHub, so no login is needed. The `.sha256` asset is never read.
- `--cert-identity` requires the identity in the signing certificate to equal this repository's release workflow on
  `main` exactly, with no prefix or pattern match: an attestation made by another workflow, branch or repository
  fails. `--source-ref` checks the source ref recorded in the provenance, and `--deny-self-hosted-runners` requires a
  GitHub-hosted runner, which is where [the release workflow](build-release.md#release) runs.
- The minimum `gh` is 2.102.0. Older versions have advisories about exactly these checks:
  [GHSA-4mq3-hpgx-9cx8](https://github.com/advisories/GHSA-4mq3-hpgx-9cx8) (`--source-ref` compared without regard
  to case; fixed in 2.102.0), [GHSA-wjmr-j3rp-mh2g](https://github.com/advisories/GHSA-wjmr-j3rp-mh2g)
  (`--signer-workflow` matched as a prefix; fixed in 2.102.0) and
  [GHSA-mm27-mwq9-fr5g](https://github.com/advisories/GHSA-mm27-mwq9-fr5g) (regular-expression characters not
  escaped when matching the certificate identity; fixed in 2.97.0). A pre-release of 2.102.0 counts as older.
- `--source-digest` is not used: the launcher does not know which commit a release was built from. The version tie
  binds the version instead. The release workflow builds the jar with the version being released and checks that
  `okf version` prints it, so a jar that passes the policy reports the version it was released as. A genuine jar of
  another release (an older one, for a downgrade) passes the policy but fails the tie.
- The minimum `gh` version and the policy may be tightened in any release
  ([reference.md](reference.md#compatibility)); each change is listed in `CHANGELOG.md`.

`gh` runs with no token or host from the environment (`GH_TOKEN`, `GITHUB_TOKEN`, `GH_ENTERPRISE_TOKEN`,
`GITHUB_ENTERPRISE_TOKEN` and `GH_HOST` unset), with an empty, temporary configuration directory (`GH_CONFIG_DIR`,
a new directory in the cache for each call, removed afterwards), and with `GH_NO_UPDATE_NOTIFIER=1` and
`GH_PROMPT_DISABLED=1`. Its standard output goes to standard error, so the launcher's standard output holds only its
own result lines and those of `okf wrapper`.

### Without gh

No option skips the provenance check. Without `gh` 2.102.0 or later, verify a jar another way (the
[consumer commands](build-release.md#release) on another machine, or a jar from a store you trust), write the pin by
hand (the two lines `version=<version>` and `sha256=<the SHA-256 of okf-cli-<version>-all.jar>`), and run
`./okfw wrapper` to refresh the launcher files. The launcher downloads only from the fixed release URL and checks the
SHA-256 before every run, so a hand-written pin can only select a genuine release asset of this project.

### Recovery

If `okfw --update` fails after writing the pin (`its launcher files could not be written`), or a released
`--update` itself is broken, edit the pin by hand to the version wanted (as above) and run `./okfw wrapper`: it runs
the pinned jar, whose `okf wrapper` writes the launcher files that version embeds.
`./okfw wrapper && git diff --exit-code` shows whether the committed launcher files are the ones the pinned version
writes.

## Cache and offline use

The cache holds one directory per version; on POSIX systems the directories the launcher creates are accessible to
their owner only (under Git for Windows the file mode has no effect, and the profile directory's permissions apply).
A jar there is used only if its SHA-256 matches the pin, so the cache can be shared between repositories and cleared
at any time.

The cache directory (`OKFW_CACHE_DIR`, `XDG_CACHE_HOME` or `HOME`) must be writable only by the user who runs the
launcher; never point it at a directory other users can write to. The launcher hashes the jar and then starts Java on
that path, so whoever can replace the file between the two steps chooses the code that runs. A cache may be shared
between repositories, never between users.

Offline, or without access to GitHub, place the jar at the cache path by hand (for example from an artifact
store): `<cache>/okf-cli/<version>/okf-cli-<version>-all.jar`. It runs only if it matches the pin; otherwise the
launcher deletes it and tries to download. The layout of the cache is not part of the compatibility contract.

## Requirements

- POSIX `sh` with `sed`, `grep`, `tr`, `head`, `dirname`, `wc`, `mkdir`, `mktemp`, `mv` and `rm` (any Linux, macOS,
  BSD; dash, bash, ksh and the shell of Git for Windows run it);
- `sha256sum`, or `shasum` (macOS);
- `curl`, for the first run of a version;
- the [GitHub CLI](https://cli.github.com) 2.102.0 or later, for `--update` and `--verify` only;
- Java 17 or later;
- on Windows, [Git for Windows](https://gitforwindows.org/), which supplies `sh.exe`, the tools and `curl`
  (okf-cli needs git anyway).

## Windows

- In Git Bash: `./okfw <arguments>`, as on Linux and macOS.
- In PowerShell: `./okfw <arguments>` runs `okfw.cmd` (PowerShell looks for `okfw` with the extensions of
  `PATHEXT`). In `cmd`: `okfw <arguments>`.
- `okfw.cmd` finds `sh.exe` from `git --exec-path` (`<git>/<prefix>/libexec/git-core` → `<git>/bin/sh.exe`;
  `<prefix>` is e.g. `mingw64` or `ucrt64`) when that path starts with a drive (`X:`) or a UNC prefix (`\\`, `//`)
  and that `sh.exe` exists, else it takes `sh.exe` on the `PATH`; it passes the exit code on. `cmd` looks for `git`
  in the current directory before the `PATH`, so run `okfw.cmd` from the repository it belongs to, not from a
  directory someone else controls.
- Arguments pass through `cmd`, which cannot carry a double quote inside an argument; and the shell of Git for
  Windows expands `*` and `?` and removes single quotes in arguments it receives from Windows programs. okf-cli's
  options need none of these. PowerShell drops an empty argument before `okfw.cmd` sees it: write `--base=` (one
  argument), not `--base ''`.
- `okfw.cmd` passes its arguments on as `%*`, so `cmd` interprets `&`, `|`, `<`, `>`, `^` and `%VAR%` in an argument
  (a `&` starts another command). Never pass untrusted values, such as branch names or pull request titles, through
  `okfw.cmd`; run `sh okfw …` or the jar instead.
- The shell of Git for Windows rewrites an argument that looks like a POSIX path (`--opt=/x`, `/x`) into a Windows
  path before Java sees it. okf-cli takes Windows paths: write `--repo=C:/work/repo`, not `--repo=/c/work/repo`.
- `okf wrapper` may replace `okfw` and `okfw.cmd` while they run: `okfw` is one function called on its last line,
  and the last line of `okfw.cmd` runs `okfw` and exits in one statement. Where Windows refuses to replace a file
  that a running process holds open, `okf wrapper` rewrites it in place.

## First bootstrap

The launcher needs a pin, and a pin needs a jar someone trusted once:

1. Download `okf-cli-<version>-all.jar` and its `.sha256` from the
   [releases page](https://github.com/sharpbitstudio/okf-cli/releases) and verify the jar as
   [build-release.md](build-release.md#release) shows (checksum; the build provenance with `gh attestation verify`).
2. In the work-tree root, run `java -jar okf-cli-<version>-all.jar init` for a new bundle, or
   `java -jar okf-cli-<version>-all.jar wrapper` in a repository that already has one. The running release jar
   writes the pin with its own SHA-256.
3. Commit the files: `git add okfw okfw.cmd .gitattributes okf-wrapper.properties` and
   `git add --chmod=+x okfw`.
4. From then on: `./okfw check --strict --refs-strict --code-paths-required`.

A build that is not a release (a `-SNAPSHOT`, or the classes of a source build) writes the launcher but no pin, and
says so: then run `./okfw --update <version>` to pin a release, which checks its build provenance first. To move to
another version later, run `./okfw --update <version>` and commit the changed files.

The bootstrap itself checks no provenance: the pin records the SHA-256 of the jar that was run, so it rests on the
verification of the first download. Every later change through `--update` is checked before the pin is written.
