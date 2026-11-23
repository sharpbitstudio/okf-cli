package com.sharpbitstudio.okf

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import java.util.Random
import java.util.regex.Pattern

/**
 * The reference patterns `fileRef`, `malformedLinesRef` and `DIR_REF` of [OkfRefChecker], [OkfChecker.HUB_ID] and
 * [OkfConfig.BRANCH_NAME] against their text before the repeated groups became possessive (the oracles, copied
 * literally): whether each input matches as a whole, and the range of every group. The oracles run on a thread with a
 * 256 MiB stack, where their recursion fits the long inputs; the production patterns run on the test thread.
 */
class OkfReferencePatternsDifferentialTest {
    @Test
    fun `fileRef and malformedLinesRef agree with their previous patterns for three extension lists`() {
        val inputs = buildList {
            exhaustive("", "a./:1,-kt", 5, this)
            exhaustive("x.kt:", "1,-–a", 5, this)
            val random = Random(SEED)
            repeat(30_000) { add(path(random)) }
            addAll(longPaths())
        }
        for (extra in listOf(emptyList<String>(), listOf("k", "kt2", "proto"), listOf("KT", "1"))) {
            val checker = OkfRefChecker(RepoIndex(emptyList()), "docs", refExtensions = extra)
            val extensions = (listOf(DEFAULT_EXTENSIONS) + extra).joinToString("|")
            assertSameAnswers("fileRef $extra", checker.fileRef, previousFileRef(extensions), inputs)
            assertSameAnswers("malformedLinesRef $extra", checker.malformedLinesRef, previousMalformedLinesRef(extensions), inputs)
        }
    }

    @Test
    fun `DIR_REF agrees with its previous pattern`() {
        val inputs = buildList {
            exhaustive("", "a/.:@", 7, this)
            val random = Random(SEED + 1)
            repeat(30_000) { add(path(random)) }
            addAll(longPaths())
        }
        assertSameAnswers("DIR_REF", OkfRefChecker.DIR_REF, PREVIOUS_DIR_REF, inputs)
    }

    @Test
    fun `HUB_ID agrees with its previous pattern`() {
        val inputs = buildList {
            exhaustive("features/", "a1-/A_", 7, this)
            exhaustive("", "featurs/-a", 5, this)
            val random = Random(SEED + 2)
            repeat(100_000) {
                add(pick(random, "features/", "features/", "feature/", "features", "") + pieces(random, HUB_PIECES, 12))
            }
            for (n in LONG) {
                add("features/" + "a-".repeat(n) + "a")
                add("features/" + "a-".repeat(n))
                add("features/" + "ab-".repeat(n) + "A")
            }
        }
        assertSameAnswers("HUB_ID", OkfChecker.HUB_ID, PREVIOUS_HUB_ID, inputs)
    }

    @Test
    fun `BRANCH_NAME agrees with its previous pattern`() {
        val inputs = buildList {
            exhaustive("", "a._-/$", 7, this)
            val random = Random(SEED + 3)
            repeat(100_000) { add(pieces(random, BRANCH_PIECES, 12)) }
            for (n in LONG) {
                add("a/".repeat(n) + "a")
                add("a/".repeat(n))
                add("a./".repeat(n) + "_")
            }
        }
        assertSameAnswers("BRANCH_NAME", OkfConfig.BRANCH_NAME, PREVIOUS_BRANCH_NAME, inputs)
    }

    /**
     * Fails unless [production] and the [previous] pattern give the same answers on every input; the previous pattern
     * runs on a thread with a 256 MiB stack, [production] on the test thread.
     */
    private fun assertSameAnswers(what: String, production: Regex, previous: String, inputs: List<String>) {
        var result: Result<IntArray>? = null
        val thread = Thread(null, { result = runCatching { answers(Pattern.compile(previous), inputs) } }, "oracle", 256L shl 20)
        thread.start()
        thread.join()
        val expected = checkNotNull(result).getOrElse {
            throw AssertionError("$what: the previous pattern (the oracle) failed on its 256 MiB thread", it)
        }
        val actual = answers(production.toPattern(), inputs)
        if (expected.size != actual.size) fail("$what: the production pattern has another number of groups than the previous one")
        if (expected.contentEquals(actual)) return
        val width = expected.size / inputs.size
        val at = inputs.indices.first { i -> (0 until width).any { expected[i * width + it] != actual[i * width + it] } }
        fail(
            "$what: input $at differs: <${cut(inputs[at])}>\n  previous pattern: ${expected.slice(at * width until (at + 1) * width)}\n" +
                "  production: ${actual.slice(at * width until (at + 1) * width)}",
        )
    }

    /**
     * Per input: 1 when [pattern] matches it as a whole (else 0), then the start and end of every group (`-1` for a group
     * that took no part; all 0 without a match).
     */
    private fun answers(pattern: Pattern, inputs: List<String>): IntArray {
        val matcher = pattern.matcher("")
        val groups = matcher.groupCount()
        val width = 1 + 2 * groups
        val out = IntArray(inputs.size * width)
        inputs.forEachIndexed { i, input ->
            matcher.reset(input)
            if (matcher.matches()) {
                out[i * width] = 1
                for (g in 1..groups) {
                    out[i * width + 2 * g - 1] = matcher.start(g)
                    out[i * width + 2 * g] = matcher.end(g)
                }
            }
        }
        return out
    }

    /** Every string of length 0 to [maxLength] over [alphabet], after [prefix]. */
    private fun exhaustive(prefix: String, alphabet: String, maxLength: Int, into: MutableList<String>) {
        for (length in 0..maxLength) {
            val digits = IntArray(length)
            while (true) {
                into.add(prefix + String(CharArray(length) { alphabet[digits[it]] }))
                var k = length - 1
                while (k >= 0 && ++digits[k] == alphabet.length) {
                    digits[k] = 0
                    k--
                }
                if (k < 0) break
            }
        }
    }

    /** A path-like span: up to six segments, a file name, sometimes a line part. */
    private fun path(r: Random): String = buildString {
        repeat(r.nextInt(7)) { append(pick(r, "a", "ab", "a.b", "..", ".", "a@1", "", "a:b", "k", "a-", "_")).append('/') }
        append(pick(r, "x.kt", "x.md", "x", "x.kt.kt", "x.", ".kt", "x.kts", "x/", "x.k", "x.kt2", "x.proto", "x.ktx", "x.KT"))
        if (r.nextBoolean()) {
            append(':').append(
                pick(
                    r, "1", "1,2", "1-3,9", "1,", "5-", "1,,2", "ident", "a.b#c()", "", "1–2", "x/y", "12,3-45,6", "0",
                    "99999999999", "1-", ",1", "1,2,", "_x", "x y", "1–",
                ),
            )
        }
    }

    /** Long paths, line lists and malformed line parts, 1,000 to 50,000 units. */
    private fun longPaths(): List<String> = LONG.flatMap { n ->
        listOf(
            "a/".repeat(n) + "a.kt",
            "a/".repeat(n) + "a.kt:" + "1,".repeat(n) + "1",
            "a/".repeat(n) + "a.kt:" + "1,".repeat(n),
            "a/".repeat(n) + "a.kt:x,",
            "a/".repeat(n),
            "a/".repeat(n) + "a",
            "ab.".repeat(n) + "kt",
        )
    }

    private fun pieces(r: Random, pieces: List<String>, max: Int) = buildString { repeat(r.nextInt(max + 1)) { append(pieces[r.nextInt(pieces.size)]) } }

    private fun pick(r: Random, vararg options: String) = options[r.nextInt(options.size)]

    private fun cut(text: String) = if (text.length > 200) text.take(200) + "… (${text.length} characters)" else text

    private companion object {
        const val SEED = 20261123L

        val LONG = listOf(1_000, 5_000, 50_000)

        /** The extensions every repo accepts, as the previous patterns spelled them. */
        const val DEFAULT_EXTENSIONS = "java|kt|kts|yml|yaml|xml|json|sql|properties|puml|toml|sh|md"

        const val SEGMENT = """[A-Za-z0-9_.@+-]+"""

        const val LINES = """\d+(?:[-–]\d+)?"""

        /** `fileRef` before the fix: greedy repetitions of the path segments and of the line list items. */
        fun previousFileRef(extensions: String) =
            """((?:$SEGMENT/)*$SEGMENT\.($extensions))(?::(?:($LINES(?:,$LINES)*)|[A-Za-z_][\w.#()]*))?"""

        /** `malformedLinesRef` before the fix. */
        fun previousMalformedLinesRef(extensions: String) = """((?:$SEGMENT/)*$SEGMENT\.($extensions)):(.*)"""

        /** `DIR_REF` before the fix. */
        const val PREVIOUS_DIR_REF = """((?:$SEGMENT/)+)"""

        /** `HUB_ID` before the fix. */
        const val PREVIOUS_HUB_ID = """features/[a-z0-9]+(?:-[a-z0-9]+)*"""

        /** `BRANCH_NAME` before the fix. */
        const val PREVIOUS_BRANCH_NAME = """[A-Za-z0-9_][A-Za-z0-9._-]*(/[A-Za-z0-9_][A-Za-z0-9._-]*)*"""

        val HUB_PIECES = listOf("a", "b1", "-", "--", "/", "A", "_", "x-y", "features/")

        val BRANCH_PIECES = listOf("a", "b", "1", "_", ".", "-", "/", "//", "..", "$", "A.b", "x/y", ".lock")
    }
}
