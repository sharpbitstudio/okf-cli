---
type: Guide
title: "Writing documents for the okf checks"
description: "How to write documents that okf check and okf impact accept: the frontmatter, code_paths and its glob syntax, code references in the body and how to opt a line out, Docs-Impact commit trailers (grammar, document IDs, which commits a trailer covers), and the steps for a first document and for ADRs. Read it before writing or changing a document, or when okf check or okf impact reports a finding about one."
tags: [authoring, frontmatter, code-paths, globs, code-references, docs-impact, adr]
code_paths: ["src/main/kotlin/com/sharpbitstudio/okf/OkfCodePaths.kt", "src/main/kotlin/com/sharpbitstudio/okf/OkfFrontmatter.kt", "src/main/kotlin/com/sharpbitstudio/okf/OkfGit.kt", "src/main/kotlin/com/sharpbitstudio/okf/OkfGlob.kt", "src/main/kotlin/com/sharpbitstudio/okf/OkfImpact.kt", "src/main/kotlin/com/sharpbitstudio/okf/OkfMarkdown.kt", "src/main/kotlin/com/sharpbitstudio/okf/OkfRefChecker.kt", "src/main/kotlin/com/sharpbitstudio/okf/RepoIndex.kt"]
---
# Writing documents for the okf checks

This page is about writing the documents of a bundle that okf-cli checks: what `okf check` and `okf impact` accept,
and how to keep a document in step with the code it describes. It states what okf-cli 1.x checks; where it
simplifies, it says so or links the details. The commands, their options and exit codes, `okf.yaml`, the rules for
ADRs, Feature Records and Satellite Notes, and every console line are in [reference.md](reference.md).

`<docs_root>` stands for the bundle directory (`docs_root` in `okf.yaml`, default `docs`). Commands are written as
`./okfw <command>`, the launcher that `okf init` and `okf wrapper` write; without it, run the jar with `java -jar`
([reference.md](reference.md#running-it)).

## A first document

A bundle starts with `okf init` ([README](../README.md#quick-start)). A first document, here
`<docs_root>/billing.md`, describes some code and names it in `code_paths`:

```markdown
---
type: Guide
title: "Billing"
description: "How invoices are built and sent. Read it before changing the billing code."
tags: [billing, invoices]
code_paths: ["src/main/java/com/example/billing/**"]
---
# Billing

`InvoiceBuilder.java` collects the order lines; `InvoiceMailer.java` sends the result.
```

1. Check it:

   ```bash
   ./okfw check --strict --refs-strict --code-paths-required
   ```

   This validates the frontmatter, that each `code_paths` entry matches a file, and that the files named in the
   body's code spans exist ([Code references](#code-references)).
2. Commit the document on a branch. Then change a file that its `code_paths` match and run `./okfw impact`. The
   document is listed as not updated, with a finding that suggests a trailer:

   ```text
   [NOT UPDATED] docs/billing.md <- src/main/java/com/example/billing/InvoiceBuilder.java
   ```

   `okf impact` compares against `origin/<base_ref>`, or `<base_ref>` when that does not exist (`base_ref` in
   `okf.yaml`, default `main`); set `base_ref` or pass `--base` when your base branch differs. When the base or the
   merge-base cannot be found, it warns and skips the check, or fails with `--impact-strict`; an empty `--base=` (or
   an all-zero SHA) skips the check on purpose, also with `--impact-strict`. Without `--impact-strict` the finding is
   a warning; with it, as the GitHub Action runs it by default on a pull request that Dependabot did not open, the
   check fails ([github-actions.md](github-actions.md#base-and-strictness)).
3. Update the document where the change made it wrong, in the commit that changes the code or a later one on the
   same branch; it is then listed as `[updated]`. When the document is still right, acknowledge the change with a
   trailer, the last paragraph of the commit message, instead:

   ```text
   Rename the invoice builder's helper

   Docs-Impact: billing — internal rename, the billing flow is unchanged
   ```

   The document is then listed as `[acknowledged: <sha7>]`. Both kinds of line are in
   [reference.md](reference.md#console-lines).

The sections below give the rules behind each step.

## Frontmatter

A document starts with a YAML frontmatter block:

- The first line is `---`, and the block ends at the next `---` line (blanks after the dashes are allowed). A UTF-8
  byte order mark is ignored, and CRLF line ends are read as LF.
- The content must be a YAML mapping without repeated keys; an empty block counts as an empty mapping. Date-like
  values stay the text as written: `adr_date: 2026-03-02` is the string `2026-03-02`, not a date.
- A `---` that is the first non-blank line but has blank lines above it is an error: frontmatter must start on
  line 1. A file whose first non-blank line is anything else has no frontmatter.

A file without frontmatter is not migrated yet: without `--strict`, `okf check` counts such files in an info line;
with it, each one is a problem. Invalid frontmatter (never closed, not parseable, not a mapping, a repeated key) is
always a problem. The body of either is not checked for code references; the body of a document with valid
frontmatter is, even when one of its fields breaks a rule.

- `index.md` is reserved: the one at the bundle root carries only `okf_version: "0.2"`, quoted (YAML reads an
  unquoted `0.2` as a number), and an `index.md` in a subdirectory carries no frontmatter. Files named `index.md`
  or `log.md` are not documents: their bodies are not checked, and `okf impact` ignores them. <!-- okf: ignore-refs -->
- `type` is required. The field rules that come from the OKF spec (`type`, `status`, the `generated` and `verified`
  stamps, `stale_after`) and the stricter ones of okf-cli (the type taxonomy, which `extra_types` extends) are
  summarised in the [README](../README.md#okf-version); the rules for ADRs, Feature Records and Satellite Notes are
  in [reference.md](reference.md#bundle-rules).
- `title`, `description` and `tags` are required on ADRs (`tags` as a non-empty YAML list of non-blank strings) and
  optional elsewhere. Give every document all three: the generated ADR and Feature Record indexes show `description`
  and `tags` after each entry, and a `description` that says what the document covers and when to read it tells a
  reader whether to open it.

## `code_paths`

`code_paths` names the code a document describes, as globs relative to the repository root. `okf impact` uses it to
find the documents that a code change may have made wrong ([`Docs-Impact` trailers](#docs-impact-trailers)).

- No `code_paths` key: the document's code is not listed yet. With `--code-paths-required` the key is required on
  Accepted and Resolved ADRs, Feature Records and Satellite Notes; without it, `okf check` counts those in an info
  line. Other types never need it.
- `code_paths: []`: the document covers no code; say why in its body. This satisfies `--code-paths-required`.
- Otherwise a YAML list of globs. Each entry, as written: a non-empty string; relative (no leading `/`, no drive
  letter such as `X:`); with `/` as the separator (no `\`); without a `..` segment or a leading `./`; not the docs
  root and not starting with `<docs_root>/`; and not repeated in the list.
- Each entry must also be a valid glob ([Glob syntax](#glob-syntax)) and match at least one file outside the docs
  root, among the files git knows in the work tree: tracked files, and untracked files that are not ignored (on a
  fresh clone, as in CI, that is the committed files). An entry that matches no file is a problem; one that matches
  more than 100 files is a warning (over-broad: narrow it to the code the document describes).

### Glob syntax

A glob matches the whole repository-relative path, with `/` between segments, case-sensitively and the same way on
every operating system. It names files: `src` matches only a file at the path `src`, `src/**` the files below it.

| Pattern | Matches |
|---|---|
| `*` | Any characters within one segment, also none. A leading `.` is not special: `*` also matches hidden files. |
| `?` | One character other than `/`. |
| `**` | Directories, and only as a whole segment (below). |
| `{a,b}` | Either alternative. Groups may nest and hold wildcards; they are expanded first, so the `**` rule applies to each alternative. At most 1000 distinct alternatives; braces inside a character class are literal. |
| `[abc]`, `[a-z]` | One character from the set or the range. |
| `[!abc]`, `[^abc]` | One character not in the set. |
| anything else | Itself. |

`**` stands for directories, and only as a whole segment. As the last segment it matches every file below, at any
depth, but not the directory itself; as the first or a middle segment it matches zero or more directories, so
`**/<name>` also matches `<name>` at the root, and `a/**/b` also matches `a/b`. `**` alone matches every file.

In a character class, a `]` right after the opening `[` (or after `!` or `^`) is a member, and so is a `-` that comes
first or last. A range runs upwards: `[a-a]` is one character, `[z-a]` an error. A class never matches `/`.

`okf check` reports an invalid glob as a problem that names the entry (`code_paths[N]`) and the reason: a trailing
`/` (globs match files: write `dir/**`), `**` inside a segment (`a**`, `**.kt`), an unclosed `[` or `{`, a `}`
without a `{`, a reversed range, a `/` inside a class, or more than 1000 alternatives.

```yaml
code_paths: ["src/main/java/com/example/billing/**"]        # every file below billing/
code_paths: ["**/package-info.java"]                          # that file in every directory, the root included
code_paths: ["src/{main,test}/kotlin/com/example/cache/**"]   # two trees, one glob
code_paths: ["config/app-[a-z][a-z].yml"]                     # one letter per character class
code_paths: []                                                # covers no code: say why in the body
```

## Code references

`okf check` checks two kinds of reference in the body of every document with valid frontmatter: inline code spans
whose whole content is a path, and relative Markdown links. The frontmatter, fenced code blocks and opted-out lines
([Opting out](#opting-out)) are not scanned. A fenced block is opened by three or more backticks or tildes, indented
by at most three spaces, also inside a block quote; a fence that is never closed runs to the end of the document, or
of its block quote.

Code spans and links are recognised within one line: a code span broken across a line break is neither a reference
nor a quote that hides a marker, and a link broken across lines is not checked.

### Code spans

The content of a code span, trimmed, is a reference when the whole of it has one of these forms (shown here without
the backticks):

```text
InvoiceBuilder.java                 a file
billing/InvoiceBuilder.java         a path, or the end of one
InvoiceBuilder.java:42              the file, and line 42 within it
InvoiceBuilder.java:12-20, 31       the file, and lines 12 to 20 and 31
InvoiceBuilder.java:build           the file; the name after the colon is not checked
src/main/resources/db/              a directory
billing/{Invoice,Payment}.java      each variant: billing/Invoice.java and billing/Payment.java
```

- A path is segments of ASCII letters and digits, `_`, `.`, `@`, `+` and `-`, joined by `/`. It ends in an extension
  from the base list (`java`, `kt`, `kts`, `yml`, `yaml`, `xml`, `json`, `sql`, `properties`, `puml`, `toml`, `sh`,
  `md`) or from `ref_extensions` in `okf.yaml`, compared case-sensitively.
- A line part is a comma-separated list of line numbers `N` and ranges `N-M` (hyphen or en dash); a space after a
  comma between two numbers is allowed. Instead of lines, a name after the colon (an ASCII letter or `_`, then
  ASCII letters and digits, `_`, `.`, `#`, `(` or `)`) may point at a member; only the file is checked. Any other
  suffix after the colon, without spaces (`<file>.java:build(String,int)`), still makes the span a reference, and it
  is reported as an invalid line reference once the file is found.
- A directory ends in `/`. A single segment, such as `api/`, is counted but not looked up: it is too generic.
- `{a,b}` groups are expanded one after another (they do not nest), and each variant is checked, up to 64 variants; a
  finding names the variant and the span it came from.

Not references:

- a span that contains `...`, `…`, `<`, `>`, `*`, `$` or a space (apart from the line list above): it is a
  placeholder or a pattern, such as `<name>.java` or `src/**`;
- a span that has none of the forms above, such as `okfw`, `LICENSE` or `notes.txt`;
- a code span anywhere in the text of an inline link or image: the link target is the reference. (A code span in
  the text of a reference-style link is checked like any other.)

A code-span path resolves as follows; the first match wins:

1. From the repository root.
2. Relative to the document's directory. A path that climbs out of the repository here is skipped (not checked).
3. Unless the path has a `.` or `..` segment: below a source root. The source roots are every directory whose path
   ends in `src/{main,test}/{java,kotlin,resources}`, also inside a module (`server/src/main/java`), and every
   existing `source_roots` entry; for a code root (not `resources`), also the package directory reached by descending
   through directories that hold exactly one subdirectory and no files. So a path written from the base package
   resolves.
4. Unless the path has a `.` or `..` segment: as the end of a path anywhere in the repository, in whole segments.
5. A path whose first segment (after any `.` or `..` segments) is a repository in `external_repos`: it points to that
   repository and is not checked here; `okf xcheck` checks it when that repository belongs to the federation
   ([reference.md](reference.md#cross-repo-check-xcheck)).
6. A path that Git's ignore rules exclude (`.gitignore`, `.git/info/exclude`, the global excludes file; build output,
   for example): skipped.
7. Otherwise, the file or directory is not found.

A path end of two or more segments (step 4) must match exactly one file or directory; with several, the finding asks
to qualify the path. A single segment, a bare file name, only needs to exist somewhere. A path below the source roots
may exist under several of them (the same package in main and test). A reference with a line part always needs
exactly one file.

Line numbers start at 1, a range runs upwards, and the last line referenced must lie within the file, counted as an
editor shows lines (a final line break does not start another line). A malformed line part, such as a trailing comma
or an open range, is reported as an invalid line reference once the file is found; a missing file is reported as not
found.

The files that exist are the ones git knows in the work tree: tracked files, and untracked files that are not
ignored. A directory exists through the files below it, so an empty directory does not.

### Links

Inline links and images (`[text](target)`, `![alt](target)`) on one line, outside code spans, without brackets
inside the link text and with no title or one in single or double quotes, are checked; other forms, such as a title
in parentheses, are not recognised and so not checked:

- The part of the target before `#` or `?` is taken. A target that is then empty (a link to an anchor), starts with
  `/` or has a scheme (`https:`, `mailto:`) is skipped. Percent escapes such as `%20` are decoded.
- The target resolves against the document's directory only, as a rendered page resolves it. A target that leaves
  the repository is not checked. Otherwise it must be an existing file or directory, of any kind; a missing target is
  a finding unless Git's ignore rules exclude it. Do not link the repository root itself (`..` from the docs root):
  `okf check` cannot check that link and stops with a git error. Link a file there, such as `README.md`. A code span
  that names the repository root as a directory (`../../` in a subdirectory of the docs root) stops it the same way.
- Anchors are not checked. Reference-style links and autolinks are not recognised, so they are not checked either.

### Findings

Code-reference findings are warnings (`OKF WARNING:` lines), and failures with `--refs-strict`; under GitHub Actions
each one is also an annotation on the line of the reference ([reference.md](reference.md#console-lines)).

### Opting out

A line that contains `<!-- okf: ignore-refs -->` is not checked. Everything from a line with
`<!-- okf: ignore-refs-start -->` to a line with `<!-- okf: ignore-refs-end -->` is not checked either, both lines
included; without the end marker, up to the end of the document. Blocks do not nest, and a line with both markers
starts a block and leaves it open, so write each marker on a line of its own. A marker inside a code span or a
fenced block is not a marker, which is how this page can show them.

Write examples in fenced blocks, or with placeholders such as `<name>` or `...`, which are never references. Keep
the markers for real paths that cannot resolve in this repository, such as a path in another repository that is not
in `external_repos`.

## `Docs-Impact` trailers

`okf impact` compares the work tree with the merge-base of `HEAD` and a base ref (`--base`; by default
`origin/<base_ref>`, else `<base_ref>`; [reference.md](reference.md#cli)). A document whose `code_paths` match a
changed file outside the docs root must be updated or acknowledged; otherwise it is listed as `[NOT UPDATED]`, with
a finding that suggests a trailer.

- The changed files are the files that differ between the merge-base and the work tree (committed, staged or
  unstaged; a file changed and changed back does not count; deleted files count, and a rename counts as its old and
  its new path), plus untracked files that are not ignored.
- The documents that take part are those with frontmatter whose `code_paths` is a non-empty list of strings and
  whose `status` is not `deprecated`. A list with an entry that is not a string takes no part at all; a string that
  is not a valid glob matches nothing. `okf check` reports both.
- A document counts as updated when its own file is among the changed files. Any edit counts, a bump of
  `verified.at` included, so make one only once the content is re-checked. A committed update covers only the code
  changes its commit could see ([Which changes a trailer covers](#which-changes-a-trailer-covers)).
- A `Docs-Impact:` trailer in a commit message acknowledges the document instead, with the same limit. Updates and
  trailers in different commits can together cover the changed files.

### Trailer grammar

`okf impact` reads the trailers of every commit between the merge-base and `HEAD`. A commit's trailers are the last
paragraph of its message (for a message of one paragraph, the lines after the subject), and only when every line of
that paragraph is a trailer, `Key: value` with a key that starts with a letter or digit and continues with letters,
digits and hyphens, or an indented line that continues the one before. Fenced code blocks in the message are
ignored, so an example there is never a trailer. Blanks before the colon are allowed, and several `Docs-Impact:`
lines in one commit add up.

The value is the documents, a separator and the reason; with document IDs, the separator and the reason may both be
left out (`Docs-Impact: billing`):

- `Docs-Impact: none — <reason>` acknowledges every document. `none` stands alone, and its reason is required.
- `Docs-Impact: <ID>, <ID> — <reason>` acknowledges the named documents. Empty items between commas are dropped.

Either form covers only the changes its commit could see
([Which changes a trailer covers](#which-changes-a-trailer-covers)).

The separator is an em dash or an en dash, with or without spaces around it, or a hyphen or two hyphens with a space
on each side; a hyphen without spaces belongs to a name such as `build-release`. The first separator in the value
counts. The reason is required with `none` and optional with document IDs; write one anyway. Document IDs and the ADR
form are case-sensitive; the key `Docs-Impact` and the word `none` are not.

A document ID is one of:

- `ADR-NNN` for the ADR `<docs_root>/adr/ADR-NNN-<slug>.md` (three digits); this form is looked up first;
- the document's path in the docs root without `.md`, such as `reference` or `features/checkout`;
- without a `/`, a file name without `.md` that is unique in the bundle, such as `checkout`.

A `<docs_root>/` prefix and a `.md` suffix on an ID are ignored. An unknown or ambiguous ID is a finding; the other
IDs of the trailer still count. The finding of a document that is not updated suggests a trailer with the ADR number
for an ADR, and with the path in the docs root without `.md` for any other document.

```text
Rename the cache key builder

The key builder moves to its own file; the keys it builds do not change.

Docs-Impact: features/checkout — rename only, behaviour unchanged
```

```text
Move the cache settings into one class

Docs-Impact: reference,
  launcher — refactoring, no change in behaviour
```

### Which changes a trailer covers

A trailer or a committed update of the document acknowledges only what its commit could see. A changed file that the
document's `code_paths` match is covered when it has no uncommitted change and every commit in the range that touched
it is a commit with an applicable trailer or one that updated the document, or an ancestor of such a commit;
different commits may cover different changes. So an update of the document in an early commit does not cover a code
change made after it. The changes of a side branch merged in later need a trailer on that branch, in the merge
commit or in a commit after it, or a document update on that branch or in a commit after the merge; an edit of the
document made in the merge commit itself counts only when no non-merge commit in the range changed the document. An
update that is not committed yet covers everything.

The commits that touched a file (code or document) are its non-merge commits in the range; only when there are
none, the merge commits that touched it (an edit made while resolving a conflict). Both come from a path-limited
`git log` of the range, so Git's history simplification applies. Whichever is newer, the document's latest update or
its latest applicable trailer, decides whether the entry is listed as `[updated]` or `[acknowledged: <sha7>]`; when
both are in the same commit, it is `[updated]`.

### Invalid trailers

A trailer that names no document, combines `none` with document IDs, gives `none` without a reason, or names an
unknown or ambiguous document is a finding about its commit, not about a document; under GitHub Actions its
annotation names no file. Without `--impact-strict` it is a warning; with it, it fails the check by itself, even when
every document is covered. IDs are resolved against the bundle as it is now, so an unknown or ambiguous ID can also
be fixed by adding or renaming a document. A trailer that is malformed in itself (no document, `none` with IDs,
`none` without a reason) is not cleared by a later commit: reword that commit, for example with an interactive
rebase, before it is merged.

### Merges that write new commits

Trailers come from the commits between the merge-base and `HEAD`. A merge that writes new commits (a squash merge,
or a merge queue that squashes or rebases) keeps a trailer only if the message it writes has it in its final trailer
block (other trailers may follow it). With `impact-strict` at its default, the GitHub Action makes impact findings
warnings on `merge_group` events and on pushes to the default branch or to a merge-queue branch
(`gh-readonly-queue/…`); a push to any other branch is strict unless Dependabot made it
([github-actions.md](github-actions.md#base-and-strictness)).

## ADRs

The rules for ADRs (file name, required keys, `adr_status` and `status`, `amended` and its markers, `superseded_by`,
the generated index) are in [reference.md](reference.md#bundle-rules). The steps:

**A first ADR.**

1. Create the directory `<docs_root>/adr/` and in it the file `ADR-NNN-<slug>.md`, with a three-digit number.
2. Give it `type: ADR`, `title`, `description`, `tags`, `adr_status` with the matching `status`, and `adr_date`;
   under `--code-paths-required`, an Accepted or Resolved ADR also needs `code_paths` (globs, or `[]` with the reason
   in the body).
3. Run the index command (`index_command` in `okf.yaml`). Once `<docs_root>/adr/` exists, `okf check` requires the
   generated `<docs_root>/adr/index.md` and fails while it is missing or out of date.
4. Commit the ADR and the index together.

```yaml
---
type: ADR
title: "Invoices are numbered per year"
description: "Why invoice numbers restart every year. Read it before changing invoice numbering."
tags: [billing, invoices]
adr_status: Accepted
status: stable
adr_date: 2026-03-02
code_paths: ["src/main/java/com/example/billing/numbering/**"]
---
```

**Amending an ADR.** Where a later change alters the ADR, add a marker in the body, usually `**Update YYYY-MM-DD:**`,
and the same date to `amended` (strictly ascending, each date after `adr_date`). Run the index command, since the ADR
index shows the latest `amended` date, and commit the index with the ADR. A change made on the day of `adr_date` is
part of the original decision and needs neither.

**Superseding an ADR.** Write the new ADR. In the old one, set `adr_status: Superseded`, `status: deprecated` and
`superseded_by: ./ADR-NNN-<slug>.md`, the new file relative to the old one. Run the index command, which lists the old
ADR under `Superseded / Deprecated` with a link to the new one, and commit both ADRs and the index. From then on
`okf impact` skips the old ADR, since its `status` is `deprecated`.
