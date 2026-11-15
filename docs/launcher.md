---
type: Guide
title: "The okfw launcher and the pin file"
description: "How ./okfw runs the okf-cli version a repository pins: the pin file okf-wrapper.properties (place, grammar, the bytes okf-cli writes, hand-written pins), what the launcher does (download, SHA-256 check, Java lookup), its command line, environment, exit codes and messages, the cache and offline use, requirements, Windows, and the first bootstrap. Read it before adding the launcher to a repository or when ./okfw stops with an okfw: message."
tags: [launcher, okfw, pin, okf-wrapper, windows, bootstrap]
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
```

`--update` and `--verify` as the first argument are reserved for the launcher: this launcher revision answers them
with exit `2`; a later revision uses them to change and verify the pin. Anywhere else they go to okf-cli like every
other argument.

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
- `okfw: --update and --verify are not available in this launcher revision`

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

When the cache directory cannot be created or written, the system tool's message comes first, and the launcher exits
with `3`. When a download cannot be moved onto the cache path because a jar is already there (on Windows, a running
Java holds the jar a parallel first run just placed), that jar is used if it matches the pin; otherwise the launcher
exits with `3` after the system tool's message.

Before a download, one progress line goes to standard error: `okfw: downloading okf-cli <version>`.

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
- Java 17 or later;
- on Windows, [Git for Windows](https://gitforwindows.org/), which supplies `sh.exe`, the tools and `curl`
  (okf-cli needs git anyway).

## Windows

- In Git Bash: `./okfw <arguments>`, as on Linux and macOS.
- In PowerShell: `./okfw <arguments>` runs `okfw.cmd` (PowerShell looks for `okfw` with the extensions of
  `PATHEXT`). In `cmd`: `okfw <arguments>`.
- `okfw.cmd` finds `sh.exe` from `git --exec-path` (`<git>/<prefix>/libexec/git-core`, e.g. `mingw64` or `ucrt64`
  → `<git>/bin/sh.exe`), else `sh.exe` on the `PATH`, and passes the exit code on. `cmd` looks for `git` in the
  current directory before the `PATH`, so run `okfw.cmd` from the repository it belongs to, not from a directory
  someone else controls.
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
says so. To move to another version, change both lines of the pin (from a jar verified as above) and run
`./okfw wrapper`, which refreshes the launcher files to the ones that version embeds; `okfw --update` and
`okfw --verify`, which download, verify the build provenance and write the pin in one step, arrive in a later
launcher revision.
