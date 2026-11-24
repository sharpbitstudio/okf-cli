package com.sharpbitstudio.okf

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import java.util.Random

/**
 * The two brace expansions against their implementations before the long-input fixes (the oracles):
 * [OkfRefChecker.expandBraces] (code spans) against a copy of its previous recursive implementation, and
 * `OkfGlob.expandAlternatives` (`code_paths`: expansion, each alternative once, at most 1000) against
 * [LegacyGlob.expandAlternatives], compared by result or by the exact message. Short inputs; both run on the test thread.
 */
class OkfBraceExpansionDifferentialTest {
    @Test
    fun `expandBraces agrees with its previous implementation`() {
        val inputs = buildList {
            exhaustive("{},ab", 8, this)
            val r = Random(SEED)
            repeat(30_000) { add(pieces(r, SPAN_PIECES, 10)) }
        }
        for (token in inputs) {
            val expected = previousExpandBraces(token)
            val actual = OkfRefChecker.expandBraces(token)
            if (actual != expected) fail("token '$token': previous $expected, now $actual")
        }
    }

    @Test
    fun `expandAlternatives agrees with the previous brace expansion, distinct and capped`() {
        val ten = "{a,b,c,d,e,f,g,h,i,j}"
        val inputs = buildList {
            exhaustive("{},a[]", 6, this)
            val r = Random(SEED + 1)
            repeat(30_000) { add(pieces(r, GLOB_PIECES, 12)) }
            add(ten + ten + ten) // exactly 1000
            add(ten + ten + ten + "{x,y}") // 2000
            add(ten + ten + "{a,b,c,d,e,f,g,h,i,j,k}") // 1100
            add("{a,b}".repeat(10)) // 1024
            add("{a,b}".repeat(9) + "x") // 512
            add("{a,a}".repeat(16)) // one distinct of 65,536
            add("{a,aa}".repeat(12)) // 13 distinct
            add("{a,b}".repeat(11) + "{") // over the cap, then unclosed: the syntax error wins
            add("{a,b}".repeat(11) + "}") // over the cap, then a stray `}`: the syntax error wins
            add("{" + "{a,b}".repeat(11) + "}") // over the cap inside a group
            add("{" + "{a,b}".repeat(11) + ",c}" + "}") // over, closed, then a stray `}`
            add("{".repeat(500) + "a" + "}".repeat(500)) // deep, one alternative
            add("{".repeat(500) + "a,b" + "}".repeat(500))
            add("{a,b}".repeat(16)) // 65,536 before the distinct step
        }
        for (glob in inputs) {
            val expected = outcome { LegacyGlob.expandAlternatives(glob) }
            val actual = outcome { OkfGlob.expandAlternatives(glob) }
            if (actual != expected) fail("glob '${glob.take(200)}': previous ${"$expected".take(500)}, now ${"$actual".take(500)}")
        }
    }

    /** The alternatives, or the message of the [IllegalArgumentException] thrown instead. */
    private fun outcome(expand: () -> List<String>): Any =
        try {
            expand()
        } catch (e: IllegalArgumentException) {
            "IllegalArgumentException: ${e.message}"
        }

    /** Every string of length 0 to [maxLength] over [alphabet]. */
    private fun exhaustive(alphabet: String, maxLength: Int, into: MutableList<String>) {
        for (length in 0..maxLength) {
            val digits = IntArray(length)
            while (true) {
                into.add(String(CharArray(length) { alphabet[digits[it]] }))
                var k = length - 1
                while (k >= 0 && ++digits[k] == alphabet.length) {
                    digits[k] = 0
                    k--
                }
                if (k < 0) break
            }
        }
    }

    private fun pieces(r: Random, pieces: List<String>, max: Int) = buildString { repeat(r.nextInt(max + 1)) { append(pieces[r.nextInt(pieces.size)]) } }

    private companion object {
        const val SEED = 20261122L

        const val MAX_BRACE_VARIANTS = 64

        val SPAN_PIECES = listOf("{", "}", ",", "a", "b", "{a,b}", "{a,b,c}", "{,}", "{x}", "{a{b}", "x.kt", "/", "{a,b,c,d,e,f,g,h}")

        val GLOB_PIECES = listOf("{", "}", ",", "a", "b", "aa", "[", "]", "[a,}]", "[!{]", "{a,b}", "{a,a}", "{a,aa}", "{,a}", "{a,{b,c}}", "/", "**")

        /** `expandBraces` before the fix: one recursive call per group, every alternative expanded in full before the cut. */
        fun previousExpandBraces(token: String): List<String> {
            val open = token.indexOf('{')
            val close = token.indexOf('}', open + 1)
            if (open < 0 || close < 0) return listOf(token)
            val head = token.substring(0, open)
            val tail = token.substring(close + 1)
            return token.substring(open + 1, close).split(',')
                .flatMap { previousExpandBraces(head + it + tail) }
                .take(MAX_BRACE_VARIANTS)
        }
    }
}
