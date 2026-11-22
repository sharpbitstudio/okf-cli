package com.sharpbitstudio.okf

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import java.io.File
import java.util.Random

/**
 * [OkfMarkdown.links] against the inline-link pattern as it was before the link target repetitions became possessive
 * (the oracle, copied literally), on this repository's docs and sources, generated lines and long lines. The oracle
 * runs on a thread with a 256 MiB stack, where the old pattern's recursion fits; [OkfMarkdown.links] runs on the test
 * thread. The repository files are sample input, not declared as inputs of the `test` task: after a change to them
 * alone, an incremental build does not re-run this test (a clean build does).
 */
class OkfMarkdownLinksDifferentialTest {
    @Test
    fun `links agree with the previous pattern on repository, generated and long lines`() {
        val inputs = repositoryLines() + generatedLines()
        var result: Result<List<List<MdLink>>>? = null
        val thread = Thread(null, { result = runCatching { inputs.map(::oracle) } }, "oracle", 256L shl 20)
        thread.start()
        thread.join()
        val expected = checkNotNull(result).getOrElse {
            throw AssertionError("the previous pattern (the oracle) failed on its 256 MiB thread", it)
        }
        inputs.forEachIndexed { i, input ->
            val actual = OkfMarkdown.links(input)
            if (actual != expected[i]) {
                fail("input $i differs: <${cut(input)}>\n  previous pattern: ${cut(expected[i].toString())}\n  links(): ${cut(actual.toString())}")
            }
        }
    }

    /**
     * Every line of `docs` (`*.md`) and `src` (`*.kt`), each sorted by the path relative to the repository root, then
     * `README.md` and `CHANGELOG.md`; then every `*.kt` line once more with its Kotlin string escapes decoded ([decode]).
     * Read from the file system (no git).
     */
    private fun repositoryLines(): List<String> {
        val root = File(checkNotNull(System.getProperty("okf.repoRoot")) { "the build passes okf.repoRoot" })
        fun walk(dir: String, suffix: String) = root.resolve(dir).walkTopDown()
            .filter { it.isFile && it.name.endsWith(suffix) }
            .sortedBy { it.relativeTo(root).invariantSeparatorsPath }
            .toList()
        val md = walk("docs", ".md")
        val kt = walk("src", ".kt")
        val named = listOf("README.md", "CHANGELOG.md").map { root.resolve(it) }
        assertTrue(md.isNotEmpty(), "no *.md under ${root.resolve("docs")}")
        assertTrue(kt.isNotEmpty(), "no *.kt under ${root.resolve("src")}")
        named.forEach { assertTrue(it.isFile, "$it is missing") }
        fun lines(file: File) = file.readText(Charsets.UTF_8).split('\n')
        val raw = (md + kt + named).flatMap(::lines)
        return raw + kt.flatMap(::lines).flatMap(::decode)
    }

    /**
     * Kotlin string escapes decoded in one left-to-right pass: a backslash before `"`, a backslash, `t` or `$` gives
     * that character (a tab for `t`); before `n` it ends the current string (the line splits there); any other escape
     * stays as written.
     */
    private fun decode(line: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            if (ch == '\\' && i + 1 < line.length) {
                when (val next = line[i + 1]) {
                    '"', '\\', '$' -> { current.append(next); i += 2; continue }
                    't' -> { current.append('\t'); i += 2; continue }
                    'n' -> { parts.add(current.toString()); current.setLength(0); i += 2; continue }
                }
            }
            current.append(ch)
            i++
        }
        parts.add(current.toString())
        return parts
    }

    /**
     * Exhaustive short strings, alone and after `[a](`; random lines over [TOKENS]; structured lines ([structured]); one
     * line with supplementary characters; last, long structured lines capped at 100,000 characters (the oracle is
     * JIT-warm by then).
     */
    private fun generatedLines(): List<String> = buildList {
        exhaustive("", 5, this)
        exhaustive("[a](", 5, this)
        val random = Random(SEED)
        repeat(100_000) {
            val tokens = random.nextInt(81)
            add(buildString { repeat(tokens) { append(TOKENS[random.nextInt(TOKENS.size)]) } })
        }
        repeat(100_000) { add(structured(random, 1 + random.nextInt(30))) }
        add("[a](x\uD83D\uDE00y\uD83D\uDE00.md) \uD83D\uDE00 [b](c\uD83D\uDE00.md \"t\uD83D\uDE00\") ![\uD83D\uDE00](d.md)")
        for (n in listOf(1_000, 2_000, 5_000, 20_000, 100_000)) {
            repeat(12) { add(structured(random, n).take(100_000)) }
        }
    }

    /** Every string of length 0 to [maxLength] over [EXHAUSTIVE], after [prefix]. */
    private fun exhaustive(prefix: String, maxLength: Int, into: MutableList<String>) {
        for (length in 0..maxLength) {
            val digits = IntArray(length)
            while (true) {
                into.add(prefix + String(CharArray(length) { EXHAUSTIVE[digits[it]] }))
                var k = length - 1
                while (k >= 0 && ++digits[k] == EXHAUSTIVE.length) {
                    digits[k] = 0
                    k--
                }
                if (k < 0) break
            }
        }
    }

    /**
     * One to three link-like pieces: optional junk, `!`, a label (or a code-span label), `](`, optional whitespace, a
     * destination [dest] (in `<…>`, an unclosed `<`, `<… x>`, followed by `(` or `)`, or bare), an optional title
     * (double-quoted, single-quoted, unterminated, in parentheses), optional whitespace, `)` (mostly) and junk.
     */
    private fun structured(r: Random, n: Int): String = buildString {
        repeat(1 + r.nextInt(3)) {
            if (r.nextInt(4) == 0) append(fill(r, r.nextInt(6), "ab [](!<>\"'` "))
            if (r.nextBoolean()) append('!')
            append('[').append(if (r.nextInt(5) == 0) "`c`" else fill(r, r.nextInt(4), "ab1 ()<>\"`")).append("](")
            if (r.nextInt(3) == 0) append(fill(r, r.nextInt(3), " \t"))
            val shape = r.nextInt(10)
            val d = dest(r, r.nextInt(5), n)
            when (shape) {
                0 -> append('<').append(d).append('>')
                1 -> append('<').append(d)
                2 -> append('<').append(d).append(" x>")
                3 -> append(d).append('(')
                4 -> append(d).append(')')
                else -> append(d)
            }
            when (r.nextInt(6)) {
                0 -> append(" \"t").append(fill(r, r.nextInt(5), "ab ()")).append('"')
                1 -> append(" 't'")
                2 -> append(" \"unterminated")
                3 -> append(" (paren title)")
            }
            if (r.nextInt(3) == 0) append(fill(r, r.nextInt(3), " \t"))
            if (r.nextInt(8) != 0) append(')')
            if (r.nextInt(3) == 0) append(fill(r, r.nextInt(6), "ab )(]"))
        }
    }

    /** A destination of about [n] characters with balanced parentheses [depth] deep (0 to 4). */
    private fun dest(r: Random, depth: Int, n: Int): String {
        if (depth == 0) return fill(r, maxOf(1, n), "ab1.-_/:%#?=+")
        return buildString {
            val parts = 1 + r.nextInt(3)
            repeat(parts) {
                if (r.nextBoolean()) append(fill(r, 1 + r.nextInt(3), "ab1."))
                append('(').append(dest(r, depth - 1, n / parts)).append(')')
            }
            if (r.nextBoolean()) append(fill(r, 1 + r.nextInt(3), "ab1."))
        }
    }

    private fun fill(r: Random, n: Int, alphabet: String) = buildString(n) { repeat(n) { append(alphabet[r.nextInt(alphabet.length)]) } }

    /** The previous inline-link pattern applied the way [OkfMarkdown.links] applies its own. */
    private fun oracle(line: String): List<MdLink> =
        ORACLE.findAll(OkfMarkdown.stripCodeSpans(line))
            .map { MdLink(it.groupValues[2].removeSurrounding("<", ">"), it.groups[1]!!.range) }
            .toList()

    private fun cut(text: String) = if (text.length > 200) text.take(200) + "… (${text.length} characters)" else text

    private companion object {
        /** `INLINE_LINK` before the fix: greedy repetitions in the bare destination, three levels of parentheses. */
        val ORACLE = Regex("""!?(\[[^\]]*])\(\s*(<[^>]*>|(?:[^()\s]|\((?:[^()\s]|\((?:[^()\s]|\([^()\s]*\))*\))*\))+)(?:\s+(?:"[^"]*"|'[^']*'))?\s*\)""")

        const val SEED = 20261122L

        /** The symbols of the exhaustive strings. */
        const val EXHAUSTIVE = "[]()<>\"' a"

        /**
         * The random-line tokens: link syntax, a backslash, letters and digits, a backtick and `#`; U+1F600 (a surrogate
         * pair) as one token; a lone high and a lone low surrogate; U+000B, U+000C and U+000D (whitespace in `\s`); U+00A0,
         * U+0085 and U+2028 (not in `\s`).
         */
        val TOKENS = "[]()<>!\"' \ta1b`#".map(Char::toString) +
            listOf("\\", "\uD83D\uDE00", "\uD83D", "\uDE00", "\u000B", "\u000C", "\r", "\u00A0", "\u0085", "\u2028")
    }
}
