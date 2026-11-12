package okf

import java.net.URLDecoder

/** A document handed to [OkfRefChecker]: bundle-relative path and full text (frontmatter included). */
data class RefDoc(val path: String, val text: String)

/** One reference that does not hold; [doc] is bundle-relative, [line] is 1-based in the whole file. */
data class RefFinding(val doc: String, val line: Int, val message: String) {
    override fun toString() = "$doc:$line: $message"
}

/** Outcome of [OkfRefChecker.check]. */
data class RefCheckResult(val findings: List<RefFinding>, val checked: Int)

/**
 * A code-span reference into a sibling repository ([OkfRefChecker.siblingRefs]): written in [doc] (bundle-relative)
 * at [line] as `<repo>/<path>`; [path] is relative to [repo]'s root (empty when the reference names the repo itself).
 */
internal data class SiblingRef(val doc: String, val line: Int, val repo: String, val path: String, val ref: OkfRefChecker.PathRef)

/**
 * Outcome of [OkfRefChecker.checkSiblingRefs]: the references that do not hold, each with the reason, and how many
 * references were looked up in the target repo ([checked]; one that names the repo itself or climbs out of it is not).
 */
internal data class SiblingCheckResult(val findings: List<Pair<SiblingRef, String>>, val checked: Int)

/**
 * Validates code references in document bodies against the repository ([RepoIndex]): every inline
 * code span that looks like a path and every relative Markdown link must resolve to a file or
 * directory that exists, and a `path:N` / `path:N-M` / `path:N,M` line reference must lie within the file.
 *
 * Only the body after the frontmatter is scanned, with fenced blocks removed
 * ([OkfMarkdown.stripFencedBlocks]); lines can opt out with [IGNORE_LINE] or an
 * [IGNORE_START] … [IGNORE_END] block. Code-span grammar (the whole span must match):
 * `PATH`, `PATH:LINES`, `PATH:ident` for a file with an extension in [EXTENSIONS] (plus [refExtensions]), or `DIR/`, where
 * `LINES` is a comma list of `N` / `N-M` (`:46,93`, `:23, 35`, `:1-3,9`); `{a,b}` alternatives are
 * expanded; spans containing [SKIP_MARKERS] (outside such a line list) are placeholders or patterns
 * and are not references.
 *
 * A code-span path resolves, first match wins: repo root → relative to the document (a path that
 * leaves the repo is skipped) → a source/package root ([RepoIndex.packageRoots]) → a unique path
 * suffix anywhere in the repo (a single segment = the basename exists somewhere) → a sibling repo
 * written as `<repo>/<path>` ([externalRepos], skipped) → a gitignored path (build output,
 * skipped) → not found. A suffix of two or more segments must match a single file or directory
 * (several candidates = "qualify the path"); a single segment only needs to exist, and a
 * package-relative path may exist under several roots (main + test = one package) — unless it
 * carries a line number, which always needs a unique file. Markdown links resolve relative to the
 * document only (as rendered).
 *
 * `okf xcheck` reuses both halves across repositories: [siblingRefs] yields the references this repo's [check]
 * skips as sibling-repo references, and [checkSiblingRefs] on the checker of the target repo resolves them there.
 */
class OkfRefChecker(
    /** The repository's files; [OkfChecker] also matches `code_paths` globs against it. */
    val index: RepoIndex,
    /** Repo-relative path of the bundle root (`docs`), to place documents in the repo. */
    val bundlePath: String,
    /** Sibling repositories (`external_repos`, [OkfConfig]); a path written as `<repo>/<path>` points there and is not checked. */
    private val externalRepos: Set<String> = emptySet(),
    /** Extensions (`ref_extensions`, [OkfConfig]) accepted on top of [EXTENSIONS]. */
    refExtensions: List<String> = emptyList(),
    /** Returns the subset of the given repo-relative paths that `.gitignore` excludes; called once per [check]. */
    private val ignored: (Collection<String>) -> Set<String> = { emptySet() },
) {
    init {
        // The extensions are interpolated into the reference regexes: only plain letters and digits.
        refExtensions.forEach { require(EXTENSION.matches(it)) { "ref extension '$it' must match ${EXTENSION.pattern}" } }
    }

    private val extensions = (EXTENSIONS + refExtensions).distinct()

    /** `PATH` + optional `:N[,N…]` (each `N` or `N-M`, hyphen or en dash) or `:ident`; group 1 = path, 3 = line list. */
    private val fileRef = Regex(
        """((?:$SEGMENT/)*$SEGMENT\.(${extensions.joinToString("|")}))(?::(?:($LINES(?:,$LINES)*)|[A-Za-z_][\w.#()]*))?""",
    )

    /** `PATH:` + anything that [fileRef] rejects (`:30,`, `:5-`, `:1,,2`); group 1 = path. */
    private val malformedLinesRef = Regex("""((?:$SEGMENT/)*$SEGMENT\.(${extensions.joinToString("|")})):(.*)""")

    /**
     * The code-span references of [docs] that [check] skips as sibling-repo references (`<repo>/<path>` with `<repo>`
     * in [externalRepos], not resolvable in this repo) — scanned exactly as [check] scans, in document and line order.
     */
    internal fun siblingRefs(docs: List<RefDoc>): List<SiblingRef> = buildList {
        for (doc in docs) {
            val docRepoPath = if (bundlePath.isEmpty()) doc.path else "$bundlePath/${doc.path}"
            val docDir = docRepoPath.substringBeforeLast('/', "")
            val seen = mutableSetOf<Pair<Int, String>>()
            scannedLines(doc.text).forEach { (lineNo, line) ->
                for (ref in refsOnLine(line)) {
                    if (ref !is PathRef || !seen.add(lineNo to ref.text)) continue
                    val resolution = resolve(ref, docDir)
                    if (resolution is Resolution.Sibling) add(SiblingRef(doc.path, lineNo, resolution.repo, resolution.path, ref))
                }
            }
        }
    }

    /**
     * Resolves references that another repo's [siblingRefs] found pointing at this repository, by the chain [check]
     * uses minus the document-relative step: repo root → package roots → unique suffix → gitignored here (skipped) →
     * not found. A path with `.` / `..` segments after the repo name is explicitly relative: like in [check], package
     * roots and suffixes do not apply to it. Line references are validated against this repo's files.
     */
    internal fun checkSiblingRefs(refs: List<SiblingRef>): SiblingCheckResult {
        val findings = mutableListOf<Pair<SiblingRef, String>>()
        val pending = mutableListOf<Triple<SiblingRef, String, List<String>>>()
        var checked = 0
        for (sibling in refs) {
            val ref = sibling.ref
            val resolution = resolveInRepo(ref, sibling.path)
            if (resolution is Resolution.Found || resolution is Resolution.Missing) checked++
            val outcome = when (resolution) {
                is Resolution.Found -> verifyFound(ref, resolution, sibling.path)
                is Resolution.Missing -> Outcome.Unresolved(
                    "`${ref.text}`: ${if (ref.dir) "directory" else "file"} not found in ${sibling.repo} — fix the path, " +
                        "or opt the line out with `$IGNORE_LINE`",
                    resolution.probes,
                )
                Resolution.Skip, is Resolution.Sibling -> Outcome.Ok
            }
            when (outcome) {
                Outcome.Ok -> Unit
                is Outcome.Problem -> findings += sibling to outcome.message
                is Outcome.Unresolved -> pending += Triple(sibling, outcome.message, outcome.probes)
            }
        }
        val ignoredPaths = ignored(pending.flatMap { it.third }.distinct())
        pending.filter { (_, _, probes) -> probes.none { it in ignoredPaths } }.forEach { findings += it.first to it.second }
        return SiblingCheckResult(findings, checked)
    }

    fun check(docs: List<RefDoc>): RefCheckResult {
        val findings = mutableListOf<RefFinding>()
        val pending = mutableListOf<Pair<RefFinding, List<String>>>()
        var checked = 0
        for (doc in docs) {
            val docRepoPath = if (bundlePath.isEmpty()) doc.path else "$bundlePath/${doc.path}"
            val docDir = docRepoPath.substringBeforeLast('/', "")
            val seen = mutableSetOf<Pair<Int, String>>()
            scannedLines(doc.text).forEach { (lineNo, line) ->
                for (ref in refsOnLine(line)) {
                    if (!seen.add(lineNo to ref.text)) continue
                    checked++
                    when (val outcome = verify(ref, docDir)) {
                        Outcome.Ok -> Unit
                        is Outcome.Problem -> findings += RefFinding(doc.path, lineNo, outcome.message)
                        is Outcome.Unresolved -> pending += RefFinding(doc.path, lineNo, outcome.message) to outcome.probes
                    }
                }
            }
        }
        val ignoredPaths = ignored(pending.flatMap { it.second }.distinct())
        pending.filter { (_, probes) -> probes.none { it in ignoredPaths } }.forEach { findings += it.first }
        return RefCheckResult(findings.sortedWith(compareBy({ it.doc }, { it.line })), checked)
    }

    /** Body lines (1-based file line numbers) that are scanned: frontmatter, fences and opted-out lines are left out. */
    private fun scannedLines(text: String): List<Pair<Int, String>> {
        val body = OkfFrontmatter.body(text)
        val offset = OkfFrontmatter.normalize(text).split('\n').size - body.split('\n').size
        var inIgnoreBlock = false
        return OkfMarkdown.stripFencedBlocks(body).split('\n').mapIndexedNotNull { i, line ->
            // Markers quoted as code (e.g. in docs that describe them) are not markers.
            val prose = OkfMarkdown.stripCodeSpans(line)
            when {
                IGNORE_START in prose -> null.also { inIgnoreBlock = true }
                IGNORE_END in prose -> null.also { inIgnoreBlock = false }
                inIgnoreBlock || IGNORE_LINE in prose -> null
                else -> offset + i + 1 to line
            }
        }
    }

    internal sealed interface Ref {
        val text: String
    }

    /** [lines] is `null` without a line part; [badLines] marks a malformed line part or a number that does not fit an `Int`. */
    internal data class PathRef(
        override val text: String,
        val path: String,
        val dir: Boolean,
        val lines: List<IntRange>?,
        val badLines: Boolean = false,
    ) : Ref

    private data class LinkRef(override val text: String, val target: String) : Ref

    private fun refsOnLine(line: String): List<Ref> = buildList {
        val links = OkfMarkdown.links(line)
        for (span in OkfMarkdown.codeSpans(line)) {
            // A code span as link text (`[`API_PLAN.md`](../../x/API_PLAN.md)`) is a label; the target is the reference.
            if (links.any { span.column in it.text }) continue
            val written = span.content.trim()
            // `Foo.java:23, 35`: the space inside a line list is not a placeholder marker.
            val token = LINE_LIST_SPACE.replace(written, ",")
            if (token.isEmpty() || SKIP_MARKERS.any { it in token }) continue
            expandBraces(token).forEach { variant ->
                parsePathRef(if (variant == token) written else variantLabel(token, variant), variant)?.let(::add)
            }
        }
        for (link in links) {
            val path = link.target.substringBefore('#').substringBefore('?')
            if (path.isEmpty() || path.startsWith('/') || URL_SCHEME.containsMatchIn(path)) continue
            add(LinkRef(link.target, percentDecode(path)))
        }
    }

    private fun parsePathRef(label: String, variant: String): PathRef? {
        DIR_REF.matchEntire(variant)?.let { return PathRef(label, it.groupValues[1].trimEnd('/'), true, null) }
        val match = fileRef.matchEntire(variant)
            // `Foo.java:30,`, `Foo.java:5-`: a path with a malformed line part is still a reference, not prose.
            ?: return malformedLinesRef.matchEntire(variant)?.let { PathRef(label, it.groupValues[1], false, null, badLines = true) }
        val lineList = match.groups[3]?.value ?: return PathRef(label, match.groupValues[1], false, null)
        val ranges = lineList.split(',').map { part ->
            val bounds = part.split('-', '–').map { it.toIntOrNull() ?: return PathRef(label, match.groupValues[1], false, null, badLines = true) }
            bounds.first()..bounds.last()
        }
        return PathRef(label, match.groupValues[1], false, ranges)
    }

    private fun variantLabel(token: String, variant: String) = if (token == variant) token else "$variant (from $token)"

    private sealed interface Outcome {
        data object Ok : Outcome

        data class Problem(val message: String) : Outcome

        /** Not found; becomes a finding unless one of [probes] is gitignored. */
        data class Unresolved(val message: String, val probes: List<String>) : Outcome
    }

    private fun verify(ref: Ref, docDir: String): Outcome = when (ref) {
        is LinkRef -> {
            val resolved = normalize(join(docDir, ref.target.trimEnd('/')))
            when {
                resolved == null -> Outcome.Ok // leaves the repo: a sibling checkout, not checkable here
                index.isFile(resolved) || index.isDir(resolved) -> Outcome.Ok
                else -> Outcome.Unresolved("link target `${ref.target}` not found (resolved against the document: $resolved)", listOf(resolved))
            }
        }
        is PathRef -> verifyPath(ref, docDir)
    }

    private fun verifyPath(ref: PathRef, docDir: String): Outcome {
        val kind = if (ref.dir) "directory" else "file"
        return when (val resolution = resolve(ref, docDir)) {
            Resolution.Skip, is Resolution.Sibling -> Outcome.Ok
            is Resolution.Missing -> Outcome.Unresolved(
                "`${ref.text}`: $kind not found in the repo — fix the path, write a sibling-repo path as `<repo>/<path>`, " +
                    "or opt the line out with `$IGNORE_LINE`",
                resolution.probes,
            )
            is Resolution.Found -> verifyFound(ref, resolution, ref.path)
        }
    }

    /** An existing path ([lookup]: the path as looked up): ambiguity and the line part are checked against its matches. */
    private fun verifyFound(ref: PathRef, found: Resolution.Found, lookup: String): Outcome {
        val candidates = found.candidates
        if (ref.badLines) return Outcome.Problem("`${ref.text}`: invalid line reference")
        // A package-relative path present under several roots (main + test) is one package, not an ambiguity.
        if (candidates.size > 1 && (ref.lines != null || (found.bySuffix && '/' in lookup))) {
            val shown = candidates.take(MAX_CANDIDATES_SHOWN).joinToString(", ") + if (candidates.size > MAX_CANDIDATES_SHOWN) ", …" else ""
            return Outcome.Problem("`${ref.text}`: ambiguous: qualify the path (${candidates.size} candidates: $shown)")
        }
        val lines = ref.lines ?: return Outcome.Ok
        val path = candidates.first()
        val lineCount = index.lineCount(path) ?: return Outcome.Ok
        val last = lines.maxOf { it.last }
        return when {
            lines.any { it.first < 1 || it.last < it.first } -> Outcome.Problem("`${ref.text}`: invalid line range")
            last > lineCount -> Outcome.Problem("`${ref.text}`: line $last is past the end of $path ($lineCount lines)")
            else -> Outcome.Ok
        }
    }

    private sealed interface Resolution {
        data object Skip : Resolution

        /** A sibling-repo path (`<repo>/<path>`, [externalRepos]): not checked here ([siblingRefs]). */
        data class Sibling(val repo: String, val path: String) : Resolution

        /** Existing matches — several only when the path is an ambiguous suffix ([bySuffix]) or package-relative path. */
        data class Found(val candidates: List<String>, val bySuffix: Boolean = false) : Resolution

        data class Missing(val probes: List<String>) : Resolution
    }

    private fun resolve(ref: PathRef, docDir: String): Resolution {
        val path = ref.path
        val segments = path.split('/')
        if (ref.dir && segments.size == 1) return Resolution.Skip // `api/`, `internal/`: generic, not a location
        val exists: (String) -> Boolean = if (ref.dir) index::isDir else index::isFile

        val fromRoot = normalize(path)
        if (fromRoot != null && exists(fromRoot)) return Resolution.Found(listOf(fromRoot))
        val fromDoc = normalize(join(docDir, path)) ?: return Resolution.Skip // leaves the repo
        if (exists(fromDoc)) return Resolution.Found(listOf(fromDoc))

        // `./x` and `../x` are explicitly relative; source roots and suffixes do not apply to them.
        val explicitlyRelative = segments.any { it == "." || it == ".." }
        if (!explicitlyRelative) locate(path, ref.dir)?.let { return it }
        val repoAt = segments.indexOfFirst { it != "." && it != ".." }
        if (repoAt >= 0 && segments[repoAt] in externalRepos) {
            return Resolution.Sibling(segments[repoAt], segments.drop(repoAt + 1).joinToString("/"))
        }
        // A directory is probed through a child path: dir-only rules (`build/`) then match its parent,
        // and git's odd match of any trailing-slash path against a blank line of a CRLF .gitignore is avoided.
        return Resolution.Missing(listOfNotNull(fromRoot, fromDoc).distinct().map { if (ref.dir) "$it/$DIR_PROBE" else it })
    }

    /** [path] below a source/package root, else as a path suffix (possibly ambiguous); `null` when neither exists. */
    private fun locate(path: String, dir: Boolean): Resolution.Found? {
        val exists: (String) -> Boolean = if (dir) index::isDir else index::isFile
        val underRoots = index.packageRoots.map { "$it/$path" }.filter(exists)
        if (underRoots.isNotEmpty()) return Resolution.Found(underRoots)
        val bySuffix = if (dir) index.dirsWithSuffix(path) else index.filesWithSuffix(path)
        if (bySuffix.isNotEmpty()) return Resolution.Found(bySuffix, bySuffix = true)
        return null
    }

    /** [resolve] for a path relative to this repo's root, without a document: repo root → [locate] → missing. */
    private fun resolveInRepo(ref: PathRef, path: String): Resolution {
        // `<repo>/` alone names the repository itself; a path that climbs out of it cannot be checked.
        val normalized = normalize(path)?.takeIf { it.isNotEmpty() } ?: return Resolution.Skip
        val exists: (String) -> Boolean = if (ref.dir) index::isDir else index::isFile
        if (exists(normalized)) return Resolution.Found(listOf(normalized))
        // As written (`<repo>/./x`, `<repo>/a/../x`): explicitly relative, like in [resolve].
        val explicitlyRelative = path.split('/').any { it == "." || it == ".." }
        val located = if (explicitlyRelative) null else locate(normalized, ref.dir)
        return located ?: Resolution.Missing(listOf(if (ref.dir) "$normalized/$DIR_PROBE" else normalized))
    }

    companion object {
        /** Opt-out for one line: references on the line with this comment are not checked. */
        const val IGNORE_LINE = "<!-- okf: ignore-refs -->"

        /** Opt-out block start: lines up to [IGNORE_END] (or the end of the document) are not checked. */
        const val IGNORE_START = "<!-- okf: ignore-refs-start -->"

        const val IGNORE_END = "<!-- okf: ignore-refs-end -->"

        /**
         * Base file extensions a code-span path must end with to be taken as a reference; a repo adds its own
         * (`proto`, `dart`, …) through `ref_extensions` ([OkfConfig]).
         */
        val EXTENSIONS = listOf("java", "kt", "kts", "yml", "yaml", "xml", "json", "sql", "properties", "puml", "toml", "sh", "md")

        /** What an extension in [EXTENSIONS] / `ref_extensions` may look like (no dot). */
        val EXTENSION = Regex("[A-Za-z0-9]+")

        /** A code span containing any of these is a placeholder or a pattern, not a reference. */
        private val SKIP_MARKERS = listOf("...", "…", "<", ">", "*", "$", " ")

        private const val MAX_CANDIDATES_SHOWN = 3

        /** Child name used to ask git whether a (missing) directory is ignored. */
        private const val DIR_PROBE = "okf-dir-probe"

        private const val SEGMENT = """[A-Za-z0-9_.@+-]+"""

        private const val LINES = """\d+(?:[-–]\d+)?"""

        /** Whitespace after a comma between two digits (`:23, 35`), removed before the placeholder check. */
        private val LINE_LIST_SPACE = Regex("""(?<=\d),\s+(?=\d)""")

        /** `DIR/` — at least one segment, trailing slash required. */
        private val DIR_REF = Regex("""((?:$SEGMENT/)+)""")

        private val URL_SCHEME = Regex("""^[A-Za-z][A-Za-z0-9+.-]*:""")

        private const val MAX_BRACE_VARIANTS = 64

        /** Expands `{a,b}` groups (not nested) into every combination; a token without a complete group is returned as is. */
        fun expandBraces(token: String): List<String> {
            val open = token.indexOf('{')
            val close = token.indexOf('}', open + 1)
            if (open < 0 || close < 0) return listOf(token)
            val head = token.substring(0, open)
            val tail = token.substring(close + 1)
            return token.substring(open + 1, close).split(',')
                .flatMap { expandBraces(head + it + tail) }
                .take(MAX_BRACE_VARIANTS)
        }

        /** Resolves `.` and `..` segments; `null` when the path climbs above the repo root. */
        fun normalize(path: String): String? {
            val out = ArrayDeque<String>()
            for (segment in path.split('/')) {
                when (segment) {
                    "", "." -> Unit
                    ".." -> out.removeLastOrNull() ?: return null
                    else -> out.addLast(segment)
                }
            }
            return out.joinToString("/")
        }

        private fun join(dir: String, path: String) = if (dir.isEmpty()) path else "$dir/$path"

        /** `%20`-style escapes in link targets (`Plaza%20Notes.md`); a malformed escape leaves the target as written. */
        private fun percentDecode(path: String): String =
            try {
                URLDecoder.decode(path.replace("+", "%2B"), Charsets.UTF_8)
            } catch (_: IllegalArgumentException) {
                path
            }
    }
}
