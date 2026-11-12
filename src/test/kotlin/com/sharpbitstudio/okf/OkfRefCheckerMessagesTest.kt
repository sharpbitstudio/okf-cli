package com.sharpbitstudio.okf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The exact text of every [OkfRefChecker] finding, as `okf check` prints it (`<doc>:<line>: <message>`), and of the
 * sibling-repo findings that `okf xcheck` reports. Case labels (`<SourceFile>#<n>`) are stable names assigned once to
 * each message template; they are not line numbers.
 */
class OkfRefCheckerMessagesTest {
    private val index = RepoIndex(
        listOf("docs/index.md", "docs/guide.md", "lib/A.kt", "a/x/Dup.kt", "b/x/Dup.kt", "c/x/Dup.kt", "d/x/Dup.kt"),
    ) { 10 }

    @Test
    fun `sibling-repo file or directory not found in the target repo`() {
        val source = OkfRefChecker(RepoIndex(listOf("docs/guide.md")), "docs", externalRepos = setOf("sat-a"))
        val refs = source.siblingRefs(listOf(doc("`sat-a/lib/Gone.kt` `sat-a/lib/gone/`\n")))
        val findings = OkfRefChecker(index, "docs").checkSiblingRefs(refs).findings.map { it.second }
        assertEquals(
            "`sat-a/lib/Gone.kt`: file not found in sat-a — fix the path, or opt the line out with `<!-- okf: ignore-refs -->`",
            findings[0],
            "OkfRefChecker#3",
        )
        assertEquals(
            "`sat-a/lib/gone/`: directory not found in sat-a — fix the path, or opt the line out with `<!-- okf: ignore-refs -->`",
            findings[1],
            "OkfRefChecker#3",
        )
    }

    @Test
    fun `link target not found`() {
        assertEquals(
            "guide.md:4: link target `./gone.md` not found (resolved against the document: docs/gone.md)",
            finding("[Gone](./gone.md)\n"),
            "OkfRefChecker#4",
        )
    }

    @Test
    fun `file or directory not found in the repo`() {
        assertEquals(
            "guide.md:4: `lib/Gone.kt`: file not found in the repo — fix the path, write a sibling-repo path as `<repo>/<path>`, " +
                "or opt the line out with `<!-- okf: ignore-refs -->`",
            finding("`lib/Gone.kt`\n"),
            "OkfRefChecker#5",
        )
        assertEquals(
            "guide.md:4: `lib/gone/`: directory not found in the repo — fix the path, write a sibling-repo path as `<repo>/<path>`, " +
                "or opt the line out with `<!-- okf: ignore-refs -->`",
            finding("`lib/gone/`\n"),
            "OkfRefChecker#5",
        )
        assertEquals(
            "guide.md:4: `lib/Gone.kt (from lib/{A,Gone}.kt)`: file not found in the repo — fix the path, " +
                "write a sibling-repo path as `<repo>/<path>`, or opt the line out with `<!-- okf: ignore-refs -->`",
            finding("`lib/{A,Gone}.kt`\n"),
            "OkfRefChecker#5",
        )
    }

    @Test
    fun `invalid line reference`() {
        assertEquals("guide.md:4: `lib/A.kt:3,`: invalid line reference", finding("`lib/A.kt:3,`\n"), "OkfRefChecker#6")
    }

    @Test
    fun `ambiguous path`() {
        assertEquals(
            "guide.md:4: `x/Dup.kt`: ambiguous: qualify the path (4 candidates: a/x/Dup.kt, b/x/Dup.kt, c/x/Dup.kt, …)",
            finding("`x/Dup.kt`\n"),
            "OkfRefChecker#7",
        )
    }

    @Test
    fun `invalid line range`() {
        assertEquals("guide.md:4: `lib/A.kt:3-1`: invalid line range", finding("`lib/A.kt:3-1`\n"), "OkfRefChecker#8")
    }

    @Test
    fun `line past the end of the file`() {
        assertEquals(
            "guide.md:4: `lib/A.kt:20`: line 20 is past the end of lib/A.kt (10 lines)",
            finding("`lib/A.kt:20`\n"),
            "OkfRefChecker#9",
        )
    }

    /** `guide.md` with [body] after a three-line frontmatter: the body starts on line 4. */
    private fun doc(body: String) = RefDoc("guide.md", "---\ntype: Guide\n---\n$body")

    /** The single finding for [body], as `okf check` prints it; every finding when there is not exactly one. */
    private fun finding(body: String): String {
        val findings = OkfRefChecker(index, "docs").check(listOf(doc(body))).findings
        return findings.singleOrNull()?.toString() ?: findings.joinToString("\n")
    }
}
