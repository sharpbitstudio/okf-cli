package com.sharpbitstudio.okf

import java.util.regex.PatternSyntaxException

/**
 * A repo-root path glob (`code_paths`, [OkfCodePaths]) compiled to a [Regex] over `/`-separated
 * repo-relative paths — no `java.nio` `PathMatcher`, so matching is identical on every platform.
 * A glob matches whole paths only.
 *
 * Syntax: `*` = any characters within one segment, `?` = one character except `/`, `**` as a whole
 * segment = any number of segments (`server/**` = every file under `server/`, `**/package-info.java`
 * = that file in any directory, the root included), `{a,b}` = alternatives (may nest and hold
 * wildcards; expanded first, so the `**` rule applies to each alternative), `[abc]` / `[a-z]` /
 * `[!abc]` = one character from (not from) the set. Everything else is literal; `\` is rejected (paths use `/`).
 */
class OkfGlob private constructor(val pattern: String, private val regex: Regex) {
    fun matches(path: String): Boolean = regex.matches(path)

    override fun toString() = pattern

    companion object {
        /** Upper bound on brace-expanded alternatives (nested groups multiply). */
        private const val MAX_ALTERNATIVES = 1000

        private const val REGEX_META ="""\.^$|()[]{}+*?"""

        private const val RANGE_OUT_OF_ORDER = "a `[...]` character class has a range whose start comes after its end (e.g. `[z-a]`)"

        /** Compiles [glob]; throws [IllegalArgumentException] whose message says what is wrong with it. */
        fun compile(glob: String): OkfGlob {
            require(glob.isNotEmpty()) { "empty glob" }
            require('\\' !in glob) { "`\\` is not allowed — paths use `/`" }
            // Braces are expanded first, so `**` is checked as a whole segment in each alternative (`a/{b/**,c/**}`).
            val alternatives = BraceExpansion(glob).expand().distinct()
            require(alternatives.size <= MAX_ALTERNATIVES) { "too many `{}` combinations (max $MAX_ALTERNATIVES)" }
            val regex = alternatives.joinToString("|") { alternative ->
                try {
                    "(?:${translate(alternative)})"
                } catch (e: IllegalArgumentException) {
                    if (alternatives.size == 1) throw e
                    throw IllegalArgumentException("${e.message} in alternative '$alternative'", e)
                }
            }
            // [translate] escapes every regex metacharacter except `-` inside a `[...]` class and rejects a reversed
            // range itself, so the regex engine has nothing left to reject; the catch is only a fallback.
            val compiled = try {
                Regex(regex)
            } catch (e: PatternSyntaxException) {
                throw IllegalArgumentException(RANGE_OUT_OF_ORDER, e)
            }
            return OkfGlob(glob, compiled)
        }

        /** Regex for one brace-free alternative. */
        private fun translate(glob: String): String {
            require(!glob.endsWith('/')) { "globs match files — use `dir/**`" }
            val out = StringBuilder()
            var i = 0
            while (i < glob.length) {
                val c = glob[i]
                when {
                    c == '*' && glob.startsWith("**", i) -> {
                        val wholeSegment = (i == 0 || glob[i - 1] == '/') && (i + 2 == glob.length || glob[i + 2] == '/')
                        require(wholeSegment) { "`**` must be a whole path segment (`a/**`, `**/b`, `a/**/b`)" }
                        if (i + 2 == glob.length) {
                            out.append(".+")
                            i += 2
                        } else {
                            out.append("(?:[^/]+/)*")
                            i += 3
                        }
                        continue
                    }
                    c == '*' -> out.append("[^/]*")
                    c == '?' -> out.append("[^/]")
                    c == '[' -> {
                        val close = classEnd(glob, i)
                        require(close > 0) { "unclosed `[` character class" }
                        val negated = glob[i + 1] in "!^"
                        val members = glob.substring(if (negated) i + 2 else i + 1, close)
                        require('/' !in members) { "a `[...]` character class cannot match `/`" }
                        require(rangesInOrder(members)) { RANGE_OUT_OF_ORDER }
                        val escaped = members.mapIndexed { k, it ->
                            // A leading `-` is a literal member (POSIX); in a negated class it would follow the
                            // inserted `/` and form a range starting at `/`, so it is escaped there.
                            if (it in """\[]^&""" || (negated && k == 0 && it == '-')) "\\$it" else "$it"
                        }.joinToString("")
                        // A range may span `/` (`[+-0]`); the lookahead keeps a class off the separator without
                        // touching the class text (an `&&[^/]` intersection would turn a trailing `-` into a range).
                        out.append(if (negated) "[^/$escaped]" else "(?!/)[$escaped]")
                        i = close + 1
                        continue
                    }
                    c in REGEX_META -> out.append('\\').append(c)
                    else -> out.append(c)
                }
                i++
            }
            return out.toString()
        }

        /**
         * Whether every range in the [members] of a class (the text after `[`, `[!` or `[^` up to the closing `]`)
         * runs upwards, read as the emitted Java class reads them: code point by code point, a member followed by
         * `-` and another member is a range; a `-` first or last is a member (the leading `-` of a negated class is
         * escaped, so it does not form a range with the inserted `/`), and escaped members count as themselves.
         */
        private fun rangesInOrder(members: String): Boolean {
            val points = members.codePoints().toArray()
            var k = 0
            while (k < points.size) {
                if (k + 2 < points.size && points[k + 1] == '-'.code) {
                    if (points[k] > points[k + 2]) return false
                    k += 3
                } else {
                    k++
                }
            }
            return true
        }

        /**
         * Index of the `]` closing the `[` character class at [open], `-1` when unclosed. `]` right after
         * the opener (or after `!` / `^`) is a member, not the closer (as in POSIX globs).
         */
        private fun classEnd(glob: String, open: Int): Int {
            val negated = open + 1 < glob.length && glob[open + 1] in "!^"
            val start = if (negated) open + 2 else open + 1
            val close = glob.indexOf(']', start + 1)
            return if (start < glob.length && close > start) close else -1
        }

        /** `{a,b}` alternatives (nested, next to wildcards) expanded into brace-free globs; braces inside `[...]` are literal. */
        private class BraceExpansion(private val glob: String) {
            private var pos = 0

            fun expand(): List<String> {
                val result = sequence(inBraces = false)
                require(pos == glob.length) { "`}` without a matching `{`" }
                return result
            }

            /** A run up to `,` / `}` (inside braces) or the end: every combination of its brace groups. */
            private fun sequence(inBraces: Boolean): List<String> {
                var results = listOf("")
                val literal = StringBuilder()
                fun flush() {
                    if (literal.isNotEmpty()) results = results.map { it + literal }
                    literal.clear()
                }
                while (pos < glob.length) {
                    val c = glob[pos]
                    when {
                        c == '{' -> {
                            flush()
                            pos++
                            val group = alternatives()
                            results = results.flatMap { prefix -> group.map { prefix + it } }
                            continue
                        }
                        c == '}' || (c == ',' && inBraces) -> {
                            require(inBraces) { "`}` without a matching `{`" }
                            break
                        }
                        c == '[' && classEnd(glob, pos) > 0 -> {
                            val close = classEnd(glob, pos)
                            literal.append(glob, pos, close + 1)
                            pos = close + 1
                            continue
                        }
                        else -> literal.append(c)
                    }
                    pos++
                }
                flush()
                return results
            }

            /** The alternatives of a `{...}` group, [pos] just past `{`; leaves [pos] past the closing `}`. */
            private fun alternatives(): List<String> {
                val all = mutableListOf<String>()
                while (true) {
                    all += sequence(inBraces = true)
                    require(pos < glob.length) { "unclosed `{` alternatives" }
                    val separator = glob[pos++]
                    if (separator == '}') return all
                }
            }
        }
    }
}
