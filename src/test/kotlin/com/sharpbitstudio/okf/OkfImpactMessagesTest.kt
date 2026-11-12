package com.sharpbitstudio.okf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The exact text of the `okf impact` entry lines ([ImpactEntry.line]) and of every [ImpactFinding] of
 * [OkfImpact.evaluate]. Case labels (`<SourceFile>#<n>`) are stable names assigned once to each message template;
 * they are not line numbers.
 */
class OkfImpactMessagesTest {
    private val docs = listOf(
        RefDoc("guide.md", "---\ntype: Guide\nstatus: stable\ncode_paths: [\"src/**\"]\n---\n# Guide\n"),
        RefDoc("adr/ADR-001-example.md", "---\ntype: ADR\nstatus: stable\ncode_paths: [\"lib/**\"]\n---\n# ADR\n"),
    )

    @Test
    fun `entry lines`() {
        val files = listOf("src/A.kt", "src/B.kt", "src/C.kt", "src/D.kt", "src/E.kt")
        assertEquals(
            "[updated] docs/guide.md <- src/A.kt, src/B.kt, src/C.kt (+2 more)",
            ImpactEntry("docs/guide.md", ImpactState.UPDATED, files).line(),
            "OkfImpact#5",
        )
        assertEquals(
            "[acknowledged: 1234567] docs/guide.md <- src/A.kt",
            ImpactEntry("docs/guide.md", ImpactState.ACKNOWLEDGED, listOf("src/A.kt"), "1234567890abcdef").line(),
            "OkfImpact#5",
        )
        assertEquals(
            "[NOT UPDATED] docs/guide.md <- src/A.kt, src/B.kt, src/C.kt",
            ImpactEntry("docs/guide.md", ImpactState.NOT_UPDATED, files.take(3)).line(),
            "OkfImpact#5",
        )
    }

    @Test
    fun `not updated`() {
        assertEquals(
            listOf(
                "docs/adr/ADR-001-example.md: not updated although code it covers changed (lib/A.kt) — update it " +
                    "(a `verified.at` bump counts once the content is re-checked) or add a commit trailer " +
                    "`Docs-Impact: ADR-001 — <why it stays valid>`",
            ),
            evaluate(changed = listOf("lib/A.kt")).findings.map(ImpactFinding::toString),
            "OkfImpact#9",
        )
    }

    @Test
    fun `updated, but covered code changed after the update`() {
        val result = evaluate(
            changed = listOf("docs/guide.md", "src/A.kt"),
            commits = listOf(commit(C2, "Change code"), commit(C1, "Update guide")),
            touchers = mapOf("docs/guide.md" to listOf(C1), "src/A.kt" to listOf(C2)),
        )
        assertEquals(
            listOf(
                "docs/guide.md: updated in commit 1111111 only for the changes it could see, but code it covers changed after it " +
                    "or outside its history (src/A.kt) — update it again or add a trailer `Docs-Impact: guide — <why it stays valid>` " +
                    "in a later commit",
            ),
            result.findings.map(ImpactFinding::toString),
            "OkfImpact#10",
        )
    }

    @Test
    fun `acknowledged, but covered code changed after the trailer`() {
        val result = evaluate(
            changed = listOf("src/A.kt"),
            commits = listOf(commit(C2, "Change code"), commit(C1, "Refactor\n\nDocs-Impact: guide — rename only")),
            touchers = mapOf("src/A.kt" to listOf(C2)),
        )
        assertEquals(
            listOf(
                "docs/guide.md: acknowledged by commit 1111111 only for the changes it could see, but code it covers changed after it " +
                    "or outside its history (src/A.kt) — update it or add a new trailer `Docs-Impact: guide — <why it stays valid>` " +
                    "in a later commit",
            ),
            result.findings.map(ImpactFinding::toString),
            "OkfImpact#11",
        )
    }

    @Test
    fun `invalid trailers`() {
        assertEquals(
            "commit 1111111: `Docs-Impact: — no reason given` names no document — write `none — <reason>` or document IDs",
            trailerFinding("Docs-Impact: — no reason given"),
            "OkfImpact#13",
        )
        assertEquals(
            "commit 1111111: `Docs-Impact: none, guide — formatting` combines `none` with document IDs — use one or the other",
            trailerFinding("Docs-Impact: none, guide — formatting"),
            "OkfImpact#14",
        )
        assertEquals(
            "commit 1111111: `Docs-Impact: none`: `none` requires a reason (`Docs-Impact: none — <why the docs stay valid>`)",
            trailerFinding("Docs-Impact: none"),
            "OkfImpact#15",
        )
    }

    @Test
    fun `trailer document IDs`() {
        val twoGuides = listOf(
            RefDoc("a/guide.md", "---\ntype: Guide\n---\n# A\n"),
            RefDoc("b/guide.md", "---\ntype: Guide\n---\n# B\n"),
        )
        assertEquals(
            "commit 1111111: `Docs-Impact: guide — formatting`: document ID 'guide' is ambiguous (a/guide.md, b/guide.md) — use the docs path",
            trailerFinding("Docs-Impact: guide — formatting", twoGuides),
            "OkfImpact#16",
        )
        assertEquals(
            "commit 1111111: `Docs-Impact: nope — formatting`: unknown document ID 'nope' — use ADR-NNN, a docs path without .md " +
                "(features/otp-login) or a unique file name (RUNTIME)",
            trailerFinding("Docs-Impact: nope — formatting"),
            "OkfImpact#17",
        )
    }

    /** The findings of a single commit [C1] whose trailer block is [trailer]; every finding when there is not exactly one. */
    private fun trailerFinding(trailer: String, documents: List<RefDoc> = docs): String {
        val findings = evaluate(commits = listOf(commit(C1, "Change\n\n$trailer")), documents = documents).findings
        return findings.singleOrNull()?.toString() ?: findings.joinToString("\n")
    }

    /**
     * [commits] newest first, as a linear history (each commit's parent is the next older one); [touchers] = the commits
     * that touched each path, newest first (none = covered).
     */
    private fun evaluate(
        changed: List<String> = emptyList(),
        commits: List<ImpactCommit> = emptyList(),
        touchers: Map<String, List<String>> = emptyMap(),
        documents: List<RefDoc> = docs,
    ): ImpactResult {
        val parents = commits.zipWithNext { newer, older -> newer.sha to older.sha }.toMap()
        fun isAncestorOrSelf(a: String, b: String): Boolean = a == b || parents[b]?.let { isAncestorOrSelf(a, it) } == true
        return OkfImpact.evaluate(documents, "docs", changed, commits, emptySet(), { touchers[it].orEmpty() }, ::isAncestorOrSelf)
    }

    private fun commit(sha: String, message: String) = ImpactCommit(sha, message)

    private companion object {
        const val C1 = "1111111111"
        const val C2 = "2222222222"
    }
}
