package com.sharpbitstudio.okf

import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * A repo-root path glob (`code_paths`, [OkfCodePaths]) compiled to java.util.regex patterns over `/`-separated
 * repo-relative paths — no `java.nio` `PathMatcher`, so matching is identical on every platform.
 * A glob matches whole paths only.
 *
 * Syntax: `*` = any characters within one segment, `?` = one character except `/`, `**` as a whole
 * segment = any number of segments (`server/**` = every file under `server/`, `**/package-info.java`
 * = that file in any directory, the root included), `{a,b}` = alternatives (may nest and hold
 * wildcards; expanded first, so the `**` rule applies to each alternative), `[abc]` / `[a-z]` /
 * `[!abc]` = one character from (not from) the set. Everything else is literal; `\` is rejected (paths use `/`).
 *
 * Each alternative is translated to one regex, cut into chunks at every `**` that ends in `/` ([Alternative]): a
 * chunk is matched by java.util.regex on a part of the path, and the `**` + `/` steps between the chunks are a loop
 * over the path's segments. java.util.regex repeats a group by recursion, one level per repetition, so a regex
 * `(?:[^/]+/)*` for such a `**` overflowed the stack on a path some thousand directories deep; the loop does not
 * recurse per directory. A glob matches the paths the one regex `C0(?:[^/]+/)*C1…` of its chunks matched.
 */
class OkfGlob private constructor(val pattern: String, private val alternatives: List<Alternative>) {
    fun matches(path: String): Boolean = alternatives.any { it.matches(path) }

    override fun toString() = pattern

    /**
     * One brace-free alternative: its regex cut at every `**` + `/` into chunks. [first] is the chunk before the first
     * such step (`null` when it is empty: the alternative starts with a `**` segment or is empty), [rest] the chunks
     * after each step (a run of steps is one step: an empty chunk between two is dropped, as zero-or-more segments
     * twice is zero-or-more segments), [slashes] the literal `/` count of every chunk, [trailing] whether the last
     * chunk ends with a final `**` (`.+`).
     *
     * Every chunk but the last is empty or ends with a literal `/`, and a chunk consumes `/` only through its literal
     * ones (wildcards and classes never match `/`), except the `.+` of a final `**`. So a chunk that starts at a segment
     * start ends after its `slashes`-th `/` from there (the last chunk: at the path's end), and a `**` + `/` step from
     * a segment start reaches the starts of the following segments up to the first empty one or the last one.
     *
     * [matches] searches depth first in the order of the single regex `C0(?:[^/]+/)*C1…`, whose greedy loop tried the
     * farthest reachable segment first, then one fewer, down to where the step began: the first candidate on which a
     * chunk matches its region leads on to the next chunk, and a failure falls back to the next candidate; candidates
     * that leave fewer `/` than the remaining chunks need are skipped, as they cannot succeed. A failed
     * (chunk, segment) pair is remembered, per chunk and run of reachable segments, as the lowest segment explored, so
     * no pair is tested twice. The search thus tests only pairs the single regex tested before its answer, each once,
     * and a region test costs what the regex spent on that chunk at that start (a chunk always ends after its own
     * slashes, so the regex's further attempts there only repeated the same continuation): at most one region test per
     * chunk and segment. The frames live in arrays indexed by chunk, so a glob of thousands of chunks needs no
     * recursion.
     */
    private class Alternative(
        private val first: Pattern?,
        private val rest: List<Pattern>,
        private val slashes: IntArray,
        private val trailing: Boolean,
    ) {
        /** The literal `/` chunks c..last need together, by c: a candidate that leaves fewer cannot succeed. */
        private val need = IntArray(rest.size + 2)

        init {
            for (chunk in rest.size downTo 1) need[chunk] = need[chunk + 1] + slashes[chunk]
        }

        fun matches(path: String): Boolean {
            if (rest.isEmpty()) return first?.matcher(path)?.matches() ?: path.isEmpty()
            // The first chunk can only end after its own `/` count; checked before anything is allocated.
            var firstEnd = 0
            repeat(slashes[0]) {
                val slash = path.indexOf('/', firstEnd)
                if (slash < 0) return false
                firstEnd = slash + 1
            }
            if (first != null && !first.matcher(path).region(0, firstEnd).matches()) return false
            // Segment j of the path starts at 0 or after its j-th `/` and ends at its (j + 1)-th `/` or the path's end.
            var count = slashes[0]
            for (i in firstEnd until path.length) if (path[i] == '/') count++
            val slashAt = IntArray(count)
            var found = 0
            for (i in path.indices) if (path[i] == '/') slashAt[found++] = i
            fun start(segment: Int) = if (segment == 0) 0 else slashAt[segment - 1] + 1
            // The farthest segment a `**` + `/` step from each segment reaches: it passes a segment that is not empty and
            // not the last one.
            val far = IntArray(count + 1)
            far[count] = count
            for (j in count - 1 downTo 0) far[j] = if (start(j) == slashAt[j]) j else far[j + 1]
            val last = rest.size
            // The frame of chunk c (frames of the search path hold chunks 1..c): the segment its step began at, and its
            // candidates left, next down to low.
            val from = IntArray(last + 1)
            val next = IntArray(last + 1)
            val low = IntArray(last + 1)
            val matchers = arrayOfNulls<Matcher>(last + 1)
            // Per chunk and run (keyed by the run's far end): every candidate from this segment to the run's end failed.
            var failedFrom: HashMap<Long, Int>? = null
            fun runOf(c: Int, segment: Int) = (c.toLong() shl 32) or far[segment].toLong()
            fun enter(c: Int, segment: Int) {
                val k = slashes[c]
                from[c] = segment
                // A chunk holds exactly k slashes of the path; the last one without a trailing `**` ends at the path's end.
                low[c] = if (c == last && !trailing) maxOf(segment, count - k) else segment
                next[c] = minOf(far[segment], count - need[c], (failedFrom?.get(runOf(c, segment)) ?: Int.MAX_VALUE) - 1)
            }
            enter(1, slashes[0])
            var c = 1
            while (c > 0) {
                val j = next[c]
                if (j < low[c]) {
                    // Every candidate of this frame failed: so did every segment from its start to the run's end.
                    // Chunk 1 is entered once, so its frame needs no record.
                    if (c > 1) {
                        val failed = failedFrom ?: HashMap<Long, Int>().also { failedFrom = it }
                        failed.merge(runOf(c, from[c]), from[c]) { a, b -> minOf(a, b) }
                    }
                    c--
                    continue
                }
                next[c] = j - 1
                val end = if (c == last) path.length else start(j + slashes[c])
                val matcher = matchers[c] ?: rest[c - 1].matcher(path).also { matchers[c] = it }
                if (!matcher.region(start(j), end).matches()) continue
                if (c == last) return true
                c++
                enter(c, j + slashes[c - 1])
            }
            return false
        }
    }

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
            val alternatives = expandAlternatives(glob)
            // Every alternative is translated before any chunk is compiled, as every one was translated before the one
            // regex of them all was compiled.
            val translated = alternatives.map { alternative ->
                try {
                    translate(alternative)
                } catch (e: IllegalArgumentException) {
                    if (alternatives.size == 1) throw e
                    throw IllegalArgumentException("${e.message} in alternative '$alternative'", e)
                }
            }
            return OkfGlob(
                glob,
                translated.map { (chunks, slashes, trailing) ->
                    Alternative(
                        chunks.first().takeIf { it.isNotEmpty() }?.let(::compileChunk),
                        chunks.drop(1).map(::compileChunk),
                        slashes.toIntArray(),
                        trailing,
                    )
                },
            )
        }

        /**
         * Compiles one chunk behind a `\A` root. With that root java.util.regex (JDK 17 to 25) uses the chunk itself
         * as the match root: it neither studies the node chain recursively (which overflowed the stack on a chunk of
         * thousands of wildcards) nor builds a Boyer-Moore table for a chunk led by literal text (quadratic on a long
         * run of one character).
         * [Alternative] only calls `matches()`, on the whole path or on a region with the default anchoring bounds,
         * where `\A` holds at the start, so the anchor changes no answer.
         *
         * [translate] escapes every regex metacharacter except `-` inside a `[...]` class and rejects a reversed range
         * itself, so the regex engine has nothing left to reject; the catch is only a fallback.
         */
        private fun compileChunk(regex: String): Pattern =
            try {
                Pattern.compile("""\A""" + regex)
            } catch (e: PatternSyntaxException) {
                throw IllegalArgumentException(RANGE_OUT_OF_ORDER, e)
            }

        /**
         * The brace-free alternatives of [glob] ([BraceExpansion]), each once, in order; throws [IllegalArgumentException] on
         * a brace syntax error or when there are more than [MAX_ALTERNATIVES]. Internal for the differential tests.
         */
        internal fun expandAlternatives(glob: String): List<String> {
            val alternatives = BraceExpansion(glob).expand().distinct()
            require(alternatives.size <= MAX_ALTERNATIVES) { "too many `{}` combinations (max $MAX_ALTERNATIVES)" }
            return alternatives
        }

        /**
         * One brace-free alternative translated to regex chunks: the regex of the whole alternative, cut at every `**`
         * that ends in `/` ([Alternative]); [slashes] holds the literal `/` count of each chunk, [trailing] whether the
         * alternative ends with `**`.
         */
        private data class Translation(val chunks: List<String>, val slashes: List<Int>, val trailing: Boolean)

        /** The regex chunks of one brace-free alternative. */
        private fun translate(glob: String): Translation {
            require(!glob.endsWith('/')) { "globs match files — use `dir/**`" }
            val chunks = mutableListOf<String>()
            val slashes = mutableListOf<Int>()
            val out = StringBuilder()
            var slashCount = 0
            var trailing = false
            var i = 0
            while (i < glob.length) {
                val c = glob[i]
                when {
                    c == '*' && glob.startsWith("**", i) -> {
                        val wholeSegment = (i == 0 || glob[i - 1] == '/') && (i + 2 == glob.length || glob[i + 2] == '/')
                        require(wholeSegment) { "`**` must be a whole path segment (`a/**`, `**/b`, `a/**/b`)" }
                        if (i + 2 == glob.length) {
                            out.append(".+")
                            trailing = true
                            i += 2
                        } else {
                            // Ends the chunk; a run of `**/` is one step (no empty chunk between two of them).
                            if (chunks.isEmpty() || out.isNotEmpty()) {
                                chunks += out.toString()
                                slashes += slashCount
                            }
                            out.clear()
                            slashCount = 0
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
                    else -> {
                        out.append(c)
                        if (c == '/') slashCount++
                    }
                }
                i++
            }
            chunks += out.toString()
            slashes += slashCount
            return Translation(chunks, slashes, trailing)
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
