package okf

/** A commit in `<merge-base>..HEAD`: full SHA and message (trailers included). */
data class ImpactCommit(val sha: String, val message: String)

enum class ImpactState(val label: String) {
    UPDATED("updated"),
    ACKNOWLEDGED("acknowledged"),
    NOT_UPDATED("NOT UPDATED"),
}

/**
 * A document whose `code_paths` match changed code; [doc] is repo-relative, [files] are the matched
 * changed files, [commit] the SHA of the `Docs-Impact:` trailer that acknowledged it.
 */
data class ImpactEntry(val doc: String, val state: ImpactState, val files: List<String>, val commit: String? = null) {
    /** Console line: `[updated] <doc> <- a, b, c (+N more)`, `[acknowledged: sha7] …`, `[NOT UPDATED] …`. */
    fun line(): String {
        val tag = if (commit == null) state.label else "${state.label}: ${commit.take(7)}"
        return "[$tag] $doc <- ${OkfImpact.fileList(files)}"
    }
}

/** A finding that fails `okf impact` under `--impact-strict`; [doc] is repo-relative, `null` for a trailer finding. */
data class ImpactFinding(val doc: String?, val message: String) {
    override fun toString() = if (doc == null) message else "$doc: $message"
}

/** Outcome of [OkfImpact.evaluate]. */
data class ImpactResult(
    /** Impacted documents, sorted by path. */
    val entries: List<ImpactEntry>,
    val findings: List<ImpactFinding>,
    /** Documents that take part: non-empty `code_paths`, not deprecated. */
    val documents: Int,
    /** Documents with `code_paths` skipped because their `status` is `deprecated` (Superseded/Deprecated ADRs). */
    val deprecatedSkipped: Int,
    /** Changed files outside the docs bundle, sorted. */
    val changedCode: List<String>,
)

/**
 * Code → docs impact check without git (so it is unit-testable): which documents'
 * `code_paths` ([OkfCodePaths]) match changed code, and whether each was updated in the same change
 * or acknowledged by a `Docs-Impact:` commit trailer. `okf impact` ([okf.cli.OkfCli]) collects the inputs from git.
 *
 * A document counts as updated when its own file is among the changed files (any edit, a
 * `verified.at` bump included). A trailer in one of the commits can acknowledge it instead:
 * `Docs-Impact: none — <reason>` for the whole change, or `Docs-Impact: ADR-026, features/otp-login — <reason>`
 * for the named documents ([resolveId] lists the accepted ID forms). The reason is separated by
 * ` — `, ` – `, ` -- ` or ` - ` and is required with `none`. Only the trailer block counts ([trailers]);
 * an invalid trailer (`none` without a reason or mixed with IDs) acknowledges nothing.
 *
 * Coverage: every commit that updated the document and every valid trailer that applies to it can
 * cover a matched file F; F is covered when it has no uncommitted change and every commit that touched
 * it is such a commit K or an ancestor of one. Code changed later (a later commit, a merged-in side commit that K could
 * not see, the work tree, untracked files) needs a new trailer or a doc update. An update in the work
 * tree is the newest change and covers everything. The newest of update and trailer (the descendant,
 * when one is an ancestor of the other; else the `--topo-order` rank) names the entry.
 */
object OkfImpact {
    const val TRAILER = "Docs-Impact"

    /** Changed files listed per entry before `(+N more)`. */
    const val MAX_FILES_SHOWN = 3

    private val TRAILER_LINE = Regex("""$TRAILER[ \t]*:(.*)""", RegexOption.IGNORE_CASE)

    /** A git trailer line: `Key: value`, the key made of letters, digits and hyphens. */
    private val TRAILER_SHAPE = Regex("""^[A-Za-z0-9][A-Za-z0-9-]*[ \t]*:""")

    /** An indented line continuing the previous trailer's value. */
    private val CONTINUATION = Regex("""[ \t]+\S.*""")

    /** Separator between the ID list and the reason; a plain hyphen needs spaces around it (IDs contain hyphens). */
    private val REASON_SEPARATOR = Regex("""\s*[—–]\s*|\s+--?\s+""")

    private val ADR_ID = Regex("""ADR-(\d{3})""")

    /**
     * [docs] are all documents of the bundle (bundle-relative paths, as for [OkfRefChecker]); they
     * are also the namespace for trailer IDs. [changed] are repo-relative changed files (the docs
     * bundle included — that is how updates are seen); [commits] are `<merge-base>..HEAD` in
     * `--topo-order`, newest first, so the newest applicable trailer wins. [uncommitted] are the files
     * that differ between `HEAD` and the work tree or are untracked — no commit covers them.
     * [touchers] returns, for a repo-relative path, the commits of [commits] that touched it, newest
     * first — the non-merge ones when there are any ([OkfGit.commitsTouching]) — (empty = none: a code
     * file then counts as covered, a changed doc as updated in the work tree);
     * [isAncestorOrSelf] tells whether commit `a` is commit `b` or an ancestor of it.
     */
    fun evaluate(
        docs: List<RefDoc>,
        bundlePath: String,
        changed: Collection<String>,
        commits: List<ImpactCommit>,
        uncommitted: Collection<String>,
        touchers: (String) -> List<String>,
        isAncestorOrSelf: (String, String) -> Boolean,
    ): ImpactResult {
        val findings = mutableListOf<ImpactFinding>()
        val docPaths = docs.map { it.path }.toSortedSet()
        val changedSet = changed.toSet()
        val uncommittedSet = uncommitted.toSet()
        val code = changedSet.filterNot { it == bundlePath || it.startsWith("$bundlePath/") }.sorted()
        val acks = commits.mapNotNull { acknowledgement(it, docPaths, bundlePath, findings) }
        val rank = commits.withIndex().associate { (i, commit) -> commit.sha to i }

        val entries = mutableListOf<ImpactEntry>()
        var documents = 0
        var deprecatedSkipped = 0
        for (doc in docs.sortedBy { it.path }) {
            val fm = OkfFrontmatter.parse(doc.text) as? Frontmatter.Present ?: continue
            val globs = OkfCodePaths.globs(fm)?.takeIf { it.isNotEmpty() } ?: continue
            if (fm.string("status") == "deprecated") {
                deprecatedSkipped++
                continue
            }
            documents++
            // Invalid globs are okf check's to report; here they simply match nothing.
            val compiled = globs.mapNotNull { runCatching { OkfGlob.compile(it) }.getOrNull() }
            val files = code.filter { file -> compiled.any { it.matches(file) } }
            if (files.isEmpty()) continue
            val repoPath = if (bundlePath.isEmpty()) doc.path else "$bundlePath/${doc.path}"
            // The doc update and every applicable ack can cover a file, each only what its commit could see;
            // the newest of them names the entry. A doc reverted in the work tree is not changed, so the
            // changed check comes first.
            val docTouchers = if (repoPath in changedSet && repoPath !in uncommittedSet) touchers(repoPath) else emptyList()
            val update = when {
                repoPath !in changedSet -> null
                repoPath in uncommittedSet -> Coverage(null, ImpactState.UPDATED)
                else -> Coverage(docTouchers.firstOrNull(), ImpactState.UPDATED)
            }
            val applicable = acks.filter { it.all || doc.path in it.docs }
            val ack = applicable.firstOrNull()?.let { Coverage(it.sha, ImpactState.ACKNOWLEDGED) }
            val newest = when {
                update == null || ack == null -> update ?: ack
                else -> newer(update, ack, rank, isAncestorOrSelf)
            }
            val uncovered = if (newest == null || newest.sha == null) {
                emptyList() // no coverage at all (reported below), or a work-tree update, which covers everything
            } else {
                val coverers = docTouchers + applicable.map { it.sha }
                files.filter { file ->
                    file in uncommittedSet || touchers(file).any { toucher -> coverers.none { isAncestorOrSelf(toucher, it) } }
                }
            }
            if (newest != null && uncovered.isEmpty()) {
                entries += ImpactEntry(repoPath, newest.state, files, newest.sha.takeIf { newest.state == ImpactState.ACKNOWLEDGED })
                continue
            }
            val trailer = "`$TRAILER: ${suggestedId(doc.path)} — <why it stays valid>`"
            val after = "only for the changes it could see, but code it covers changed after it or outside its history (${fileList(uncovered)})"
            findings += ImpactFinding(
                repoPath,
                when (newest?.state) {
                    null ->
                        "not updated although code it covers changed (${fileList(files)}) — update it (a `verified.at` bump counts " +
                            "once the content is re-checked) or add a commit trailer $trailer"
                    ImpactState.UPDATED ->
                        "updated in commit ${newest.sha?.take(7)} $after — update it again or add a trailer $trailer in a later commit"
                    else -> "acknowledged by commit ${newest.sha?.take(7)} $after — update it or add a new trailer $trailer in a later commit"
                },
            )
            entries += ImpactEntry(repoPath, ImpactState.NOT_UPDATED, files)
        }
        return ImpactResult(entries, findings, documents, deprecatedSkipped, code)
    }

    /**
     * Values of the `Docs-Impact:` trailers of a commit message, from a simplified git trailer block: the
     * last paragraph (or, for a single-paragraph message, the lines after the subject), and only when every
     * line of it is a `Key: value` trailer or an indented continuation (folded into the value). The key is
     * case-insensitive; CRLF is tolerated. Fenced code blocks ([OkfMarkdown.stripFencedBlocks]) are removed
     * first — they are examples, never trailers.
     */
    fun trailers(message: String): List<String> {
        // Fenced examples (``` / ~~~, indented closing fences included) are blanked first, so they never form a block.
        val lines = OkfMarkdown.stripFencedBlocks(message.replace("\r\n", "\n")).split('\n').map(String::trimEnd).dropLastWhile(String::isEmpty)
        val lastBlank = lines.indexOfLast(String::isEmpty)
        val block = if (lastBlank >= 0) lines.drop(lastBlank + 1) else lines.drop(1)
        if (block.isEmpty() || !TRAILER_SHAPE.containsMatchIn(block.first())) return emptyList()
        if (block.any { !TRAILER_SHAPE.containsMatchIn(it) && !CONTINUATION.matches(it) }) return emptyList()
        val folded = mutableListOf<String>()
        block.forEach { if (CONTINUATION.matches(it)) folded[folded.lastIndex] += " " + it.trim() else folded += it }
        return folded.mapNotNull { TRAILER_LINE.matchEntire(it)?.groupValues?.get(1)?.trim() }
    }

    /** A doc update or acknowledgement at commit [sha] (`null` = the work tree, which covers everything). */
    private class Coverage(val sha: String?, val state: ImpactState)

    /**
     * The newer of [update] and [ack]: the work tree, else the descendant when one is an ancestor of the
     * other (the update on a tie — same commit), else (parallel histories) the lower `--topo-order` [rank].
     */
    private fun newer(update: Coverage, ack: Coverage, rank: Map<String, Int>, isAncestorOrSelf: (String, String) -> Boolean): Coverage {
        val u = update.sha ?: return update
        val a = ack.sha ?: return ack
        return when {
            isAncestorOrSelf(a, u) -> update
            isAncestorOrSelf(u, a) -> ack
            else -> if ((rank[u] ?: -1) <= (rank[a] ?: -1)) update else ack
        }
    }

    /** What the valid trailers of one commit acknowledge: every document (`none`) and/or the named ones (bundle-relative). */
    private class Acknowledgement(val sha: String, val all: Boolean, val docs: Set<String>)

    /** Parses [commit]'s trailers; an invalid one becomes a finding and acknowledges nothing. `null` = the commit acknowledges nothing. */
    private fun acknowledgement(
        commit: ImpactCommit,
        docPaths: Set<String>,
        bundlePath: String,
        findings: MutableList<ImpactFinding>,
    ): Acknowledgement? {
        var all = false
        val named = mutableSetOf<String>()
        for (trailer in trailers(commit.message)) {
            val at = "commit ${commit.sha.take(7)}: `$TRAILER: $trailer`"
            val (idPart, reason) = splitReason(trailer)
            val ids = idPart.split(',').map(String::trim).filter(String::isNotEmpty)
            when {
                ids.isEmpty() -> findings += ImpactFinding(null, "$at names no document — write `none — <reason>` or document IDs")
                ids.any { it.equals("none", ignoreCase = true) } -> when {
                    ids.size > 1 -> findings += ImpactFinding(null, "$at combines `none` with document IDs — use one or the other")
                    reason.isEmpty() ->
                        findings += ImpactFinding(null, "$at: `none` requires a reason (`$TRAILER: none — <why the docs stay valid>`)")
                    else -> all = true
                }
                else -> ids.forEach { id ->
                    when (val resolved = resolveId(id, docPaths, bundlePath)) {
                        is IdResolution.Doc -> named += resolved.path
                        is IdResolution.Ambiguous -> findings += ImpactFinding(
                            null,
                            "$at: document ID '$id' is ambiguous (${resolved.candidates.joinToString(", ")}) — use the docs path",
                        )
                        IdResolution.Unknown -> findings += ImpactFinding(
                            null,
                            "$at: unknown document ID '$id' — use ADR-NNN, a docs path without .md (features/otp-login) " +
                                "or a unique file name (RUNTIME)",
                        )
                    }
                }
            }
        }
        return if (all || named.isNotEmpty()) Acknowledgement(commit.sha, all, named) else null
    }

    sealed interface IdResolution {
        data class Doc(val path: String) : IdResolution

        data class Ambiguous(val candidates: List<String>) : IdResolution

        data object Unknown : IdResolution
    }

    /**
     * Resolves a trailer document ID against bundle-relative [docPaths]. Accepted forms (an optional
     * `<bundlePath>/` prefix and `.md` suffix are ignored): `ADR-NNN` (the ADR with that number), the
     * bundle-relative path (`features/otp-login`, `RUNTIME`), or a file name that is unique in the
     * bundle (`otp-login`).
     */
    fun resolveId(id: String, docPaths: Set<String>, bundlePath: String = "docs"): IdResolution {
        val bare = id.removePrefix("$bundlePath/").removeSuffix(".md")
        ADR_ID.matchEntire(bare)?.let { match ->
            val adrs = docPaths.filter { it.startsWith("${OkfIndexRenderer.ADR_DIR}/ADR-${match.groupValues[1]}-") }
            return when (adrs.size) {
                0 -> IdResolution.Unknown
                1 -> IdResolution.Doc(adrs.single())
                else -> IdResolution.Ambiguous(adrs)
            }
        }
        if ("$bare.md" in docPaths) return IdResolution.Doc("$bare.md")
        if ('/' in bare) return IdResolution.Unknown
        val byName = docPaths.filter { it.substringAfterLast('/') == "$bare.md" }
        return when (byName.size) {
            0 -> IdResolution.Unknown
            1 -> IdResolution.Doc(byName.single())
            else -> IdResolution.Ambiguous(byName)
        }
    }

    /** Up to [MAX_FILES_SHOWN] files, then `(+N more)`. */
    fun fileList(files: List<String>): String {
        val shown = files.take(MAX_FILES_SHOWN).joinToString(", ")
        return if (files.size > MAX_FILES_SHOWN) "$shown (+${files.size - MAX_FILES_SHOWN} more)" else shown
    }

    private fun splitReason(value: String): Pair<String, String> {
        val separator = REASON_SEPARATOR.find(value) ?: return value.trim() to ""
        return value.substring(0, separator.range.first).trim() to value.substring(separator.range.last + 1).trim()
    }

    /** The shortest accepted ID of a document, for the suggested trailer. */
    private fun suggestedId(path: String): String =
        OkfIndexRenderer.ADR_FILE.matchEntire(path.removePrefix("${OkfIndexRenderer.ADR_DIR}/"))
            ?.takeIf { path.startsWith("${OkfIndexRenderer.ADR_DIR}/") }
            ?.let { "ADR-${it.groupValues[1]}" }
            ?: path.removeSuffix(".md")
}
