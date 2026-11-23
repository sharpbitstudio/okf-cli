package com.sharpbitstudio.okf

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.fail
import java.util.Random

/**
 * [OkfGlob] against [LegacyGlob] (the glob as it was before the long-input fixes, the oracle): the same compile outcome
 * (success, or the same message) and the same answer on every path. The oracle runs on a thread with a 256 MiB stack,
 * where its recursion fits the deep paths; [OkfGlob] runs on the test thread.
 */
class OkfGlobDifferentialTest {
    @Test
    fun `every glob of up to two tokens agrees with the previous implementation on every short path`() {
        val paths = allStrings(SHORT_PATH_TOKENS, 4)
        assertSameOutcomes(allStrings(GLOB_TOKENS, 2).map { Case(it, paths) })
    }

    @Test
    fun `random globs agree with the previous implementation`() {
        val r = Random(SEED)
        repeat(BATCHES) {
            val cases = List(BATCH) {
                val glob = tokens(r, GLOB_TOKENS, 8)
                Case(glob, List(40) { tokens(r, PATH_TOKENS, 12) } + List(10) { segmentPath(r) })
            }
            assertSameOutcomes(cases)
        }
    }

    @Test
    fun `segment-structured globs agree with the previous implementation and exercise the double-star step`() {
        val r = Random(STRUCTURED_SEED)
        var multiChunk = 0
        var middleNonEmpty = 0
        var middleTwoSlashes = 0
        var matched = 0
        repeat(BATCHES) {
            val cases = List(BATCH) {
                val glob = joinSegments(r, SEGMENTS, 9)
                Case(
                    glob,
                    List(50) {
                        var path = joinSegments(r, PATH_SEGMENTS, 10)
                        if (r.nextInt(20) == 0) path += "/"
                        if (r.nextInt(20) == 0) path = "/$path"
                        path
                    },
                )
            }
            cases.zip(assertSameOutcomes(cases)).forEach { (case, outcome) ->
                if (outcome.error != null) return@forEach
                val chunked = LegacyGlob.expandAlternatives(case.glob).map(::chunks)
                if (chunked.any { it.size >= 2 }) multiChunk++
                val middles = chunked.flatMap { it.drop(1).dropLast(1) }.filter { it.nonEmpty }
                if (middles.isNotEmpty()) middleNonEmpty++
                if (middles.any { it.slashes >= 2 }) middleTwoSlashes++
                matched += outcome.matches.count { it }
            }
        }
        // About three quarters of the rates measured on 200,000 globs of this generator (105,931 / 33,944 / 22,530 globs,
        // 334,518 matching pairs).
        assertAll(
            { assertTrue(multiChunk >= 8_000, "globs with an alternative of two or more chunks: $multiChunk") },
            { assertTrue(middleNonEmpty >= 2_500, "globs with a non-empty chunk between two double-star segments: $middleNonEmpty") },
            { assertTrue(middleTwoSlashes >= 1_500, "globs with such a chunk of two or more slashes: $middleTwoSlashes") },
            { assertTrue(matched >= 25_000, "matching pairs: $matched") },
        )
    }

    @Test
    fun `deep paths and long double-star runs agree with the previous implementation`() {
        // Only inputs on which the previous implementation runs in linear time (a failing match after two double-star
        // segments backtracks quadratically there).
        val cases = buildList {
            for (depth in listOf(1_000, 50_000)) {
                val deep = "a/".repeat(depth) + "x.kt"
                val other = "a/".repeat(depth) + "y.kt"
                val wide = "ab/".repeat(depth) + "a/x.kt"
                val gap = "a/".repeat(depth) + "/x.kt"
                add(Case("**/x.kt", listOf(deep, other, wide, gap)))
                add(Case("a/**/x.kt", listOf(deep, other, wide, gap)))
                add(Case("**/a/*.kt", listOf(deep, other, wide)))
                add(Case("**/a/**/x.kt", listOf(deep, wide)))
                add(Case("a/**", listOf(deep, wide, gap)))
                add(Case("**", listOf(deep, gap)))
                add(Case("{**/x.kt,a/**/y.kt}", listOf(deep, other)))
            }
            add(Case("**/".repeat(50_000) + "x.kt", listOf("x.kt", "a/b/x.kt", "a/".repeat(2_000) + "x.kt")))
        }
        assertSameOutcomes(cases)
    }

    private class Case(val glob: String, val paths: List<String>)

    /** A glob's compile [error] (`null` when it compiles) and, when it compiles, whether it matches each path. */
    private class Outcome(val error: String?, val matches: BooleanArray)

    /**
     * Fails unless [OkfGlob] gives the outcome of [LegacyGlob] for every case; returns the oracle's outcomes. The oracle
     * runs on a thread with a 256 MiB stack, [OkfGlob] on the test thread.
     */
    private fun assertSameOutcomes(cases: List<Case>): List<Outcome> {
        val expected = try {
            onStack(256 * 1024) { cases.map(::previous) }
        } catch (e: Throwable) {
            throw AssertionError("the previous implementation (the oracle) failed on its 256 MiB thread", e)
        }
        cases.forEachIndexed { i, case ->
            val want = expected[i]
            val actual = current(case)
            if (actual.error != want.error) {
                fail("glob ${show(case.glob)}: compile outcome differs: previous <${want.error ?: "compiles"}>, now <${actual.error ?: "compiles"}>")
            }
            if (want.error != null) return@forEachIndexed
            val at = case.paths.indices.firstOrNull { want.matches[it] != actual.matches[it] } ?: return@forEachIndexed
            fail("glob ${show(case.glob)} on path ${show(case.paths[at])}: previous ${want.matches[at]}, now ${actual.matches[at]}")
        }
        return expected
    }

    private fun previous(case: Case): Outcome {
        val glob = try {
            LegacyGlob.compile(case.glob)
        } catch (e: IllegalArgumentException) {
            return Outcome(e.message, BooleanArray(0))
        }
        return Outcome(null, BooleanArray(case.paths.size) { glob.matches(case.paths[it]) })
    }

    private fun current(case: Case): Outcome {
        val glob = try {
            OkfGlob.compile(case.glob)
        } catch (e: IllegalArgumentException) {
            return Outcome(e.message, BooleanArray(0))
        }
        return Outcome(null, BooleanArray(case.paths.size) { glob.matches(case.paths[it]) })
    }

    /** One part of a brace-free alternative between two `**` + `/` segments: whether it holds anything, and its literal `/` count. */
    private class Chunk(val nonEmpty: Boolean, val slashes: Int)

    /** The chunks of a valid brace-free [alternative], cut at every `**` followed by `/` (a final `**` stays in the last chunk). */
    private fun chunks(alternative: String): List<Chunk> {
        val out = mutableListOf<Chunk>()
        var nonEmpty = false
        var slashes = 0
        var i = 0
        while (i < alternative.length) {
            val c = alternative[i]
            if (c == '*' && alternative.startsWith("**", i) && i + 2 < alternative.length) {
                out += Chunk(nonEmpty, slashes)
                nonEmpty = false
                slashes = 0
                i += 3
                continue
            }
            nonEmpty = true
            when {
                c == '*' && alternative.startsWith("**", i) -> i += 2
                c == '[' -> i = classEnd(alternative, i) + 1
                else -> {
                    if (c == '/') slashes++
                    i++
                }
            }
        }
        out += Chunk(nonEmpty, slashes)
        return out
    }

    /** Index of the `]` closing the class at [open] (as [OkfGlob] reads it; a valid glob has one). */
    private fun classEnd(glob: String, open: Int): Int {
        val negated = open + 1 < glob.length && glob[open + 1] in "!^"
        val start = if (negated) open + 2 else open + 1
        return glob.indexOf(']', start + 1)
    }

    /** Every concatenation of up to [maxTokens] of [tokens]. */
    private fun allStrings(tokens: List<String>, maxTokens: Int): List<String> {
        val out = mutableListOf("")
        var layer = listOf("")
        repeat(maxTokens) {
            layer = layer.flatMap { prefix -> tokens.map { prefix + it } }
            out += layer
        }
        return out
    }

    private fun tokens(r: Random, tokens: List<String>, max: Int) = buildString { repeat(r.nextInt(max + 1)) { append(tokens[r.nextInt(tokens.size)]) } }

    /** Up to seven directories of up to three short tokens each, then a file name. */
    private fun segmentPath(r: Random) = buildString {
        repeat(r.nextInt(8)) { append(tokens(r, listOf("a", "b", "x", "ab", ".", ""), 3)).append('/') }
        append(tokens(r, listOf("a", "b", "x", "x.kt", "y.kt", ""), 2))
    }

    /** One to [max] of [segments], joined by `/`. */
    private fun joinSegments(r: Random, segments: List<String>, max: Int) =
        (1..1 + r.nextInt(max)).joinToString("/") { segments[r.nextInt(segments.size)] }

    /** [text] with control, non-ASCII and surrogate characters as `<U+XXXX>`, cut at 200 characters. */
    private fun show(text: String): String {
        val shown = text.take(200).map { if (it.code in 32..126) it.toString() else "<U+%04X>".format(it.code) }.joinToString("")
        return "'$shown'" + if (text.length > 200) "… (${text.length} characters)" else ""
    }

    private companion object {
        const val SEED = 20261122L

        /** The seed of the structured set whose coverage was measured. */
        const val STRUCTURED_SEED = 1L

        const val BATCHES = 10

        const val BATCH = 2_000

        val SMILE = String(Character.toChars(0x1F600))
        val HIGH = Char(0xD83D).toString()
        val LOW = Char(0xDE00).toString()

        /** Glob tokens: wildcards, classes, braces (one spanning `/`, one holding a double star), surrogates, a line feed. */
        val GLOB_TOKENS = listOf(
            "a", "b", "x", "/", "?", "*", "**", "[ab]", "[!a]", "[a-c]", "[-a]", "[^b]", ".", "{a,b}", "{x/,}", "{**/,a}",
            SMILE, HIGH, LOW, "\n", "]", "-",
        )

        val SHORT_PATH_TOKENS = listOf("a", "b", "x", "/", ".", "\n", SMILE, HIGH)

        /** Path tokens with Java's other line terminators (`.` does not match them). */
        val PATH_TOKENS = listOf("a", "b", "x", "/", ".", "ab", "\n", "\r", Char(0x85).toString(), Char(0x2028).toString(), SMILE, HIGH, LOW, "-", "]")

        /** Glob segments, double stars weighted, braces that span `/` or hold a double star. */
        val SEGMENTS = listOf(
            "**", "**", "**", "*", "a", "b", "ab", "?", "a*", "*b", "?b", "[ab]", "[!a]", "{a,b}", "{a,ab}", "{a/b,b}", "{**/a,b}",
            "{a,**}", "*?", "x.kt", "*.kt", "", SMILE, "[^b]*",
        )

        val PATH_SEGMENTS = listOf("a", "a", "b", "ab", "x.kt", "y.kt", "", "aa", "ba", "\n", SMILE, HIGH, "a.kt")
    }
}
