package okf

/** An inline code span on one line: its content (CommonMark-trimmed) and the 0-based column of the opener. */
data class CodeSpan(val content: String, val column: Int)

/** An inline link or image on one line: its target as written and the columns of its `[text]` (brackets included). */
data class MdLink(val target: String, val text: IntRange)

/**
 * The few Markdown structures the OKF checks need — fenced blocks, inline code spans and inline
 * links — recognized line by line, without a full CommonMark parser. Shared by [OkfChecker]
 * (amendment markers) and [OkfRefChecker] (code references).
 */
object OkfMarkdown {
    /** Opening line of a fenced code block (CommonMark: up to 3 spaces of indent, 3+ backticks or tildes). */
    private val FENCE_OPEN = Regex("""^ {0,3}(`{3,}|~{3,})(.*)$""")

    /** One block-quote container level: up to 3 spaces of indent, `>`, one optional space. */
    private val QUOTE_LEVEL = Regex("""^ {0,3}> ?""")

    /** A run of backticks (opener or closer of an inline code span). */
    private val BACKTICK_RUN = Regex("""`+""")

    /** Parenthesis nesting depth a bare link destination may use (CommonMark: balanced parentheses). */
    private const val DEST_PAREN_DEPTH = 3

    /** A bare link destination: no whitespace, parentheses only balanced (`foo(bar).md`), up to [DEST_PAREN_DEPTH] deep. */
    private val BARE_DEST = (1..DEST_PAREN_DEPTH)
        .fold("""[^()\s]""") { inner, _ -> """(?:[^()\s]|\($inner*\))""" } + "+"

    /**
     * Inline link or image `[text](target "title")`; group 1 is the `[text]`, group 2 the target, bare or in `<…>`.
     * Nested brackets in the text are not supported (not used in this bundle).
     */
    private val INLINE_LINK = Regex("""!?(\[[^\]]*])\(\s*(<[^>]*>|$BARE_DEST)(?:\s+(?:"[^"]*"|'[^']*'))?\s*\)""")

    /**
     * Blanks out fenced code blocks (``` / ~~~, fence lines included) so examples inside them are
     * not taken as content; line structure is kept, so `^`-anchored patterns and line numbers still
     * work. Fences inside block quotes (lines prefixed with `>`, possibly nested) are recognized
     * too; such a fence ends at its closing fence or where the enclosing quote ends (a line with
     * fewer `>` levels). An unclosed fence runs to the end of the text (as in CommonMark).
     */
    fun stripFencedBlocks(markdown: String): String {
        var fence: String? = null
        var fenceDepth = 0
        return markdown.split('\n').joinToString("\n") { line ->
            val open = fence
            if (open != null) {
                val (depth, content) = unquote(line, fenceDepth)
                if (depth < fenceDepth) {
                    fence = null // the enclosing quote ended, closing the fence; the line is content again
                } else {
                    if (isClosingFence(content, open)) fence = null
                    return@joinToString ""
                }
            }
            val (depth, content) = unquote(line)
            val match = FENCE_OPEN.matchEntire(content)
            // A backtick fence's info string cannot contain backticks (that would be inline code).
            if (match == null || (match.groupValues[1][0] == '`' && '`' in match.groupValues[2])) {
                line
            } else {
                fence = match.groupValues[1]
                fenceDepth = depth
                ""
            }
        }
    }

    /**
     * Inline code spans on one line (opening and closing backtick runs of equal length, as in
     * CommonMark). A run without a matching closer is literal text. The content is trimmed of one
     * leading and trailing space when it has both (CommonMark stripping).
     */
    fun codeSpans(line: String): List<CodeSpan> = spanRanges(line).map { (opener, closer) ->
        val raw = line.substring(opener.last + 1, closer.first)
        val content = if (raw.length >= 2 && raw.startsWith(' ') && raw.endsWith(' ') && raw.isNotBlank()) {
            raw.substring(1, raw.length - 1)
        } else {
            raw
        }
        CodeSpan(content, opener.first)
    }

    /** Blanks inline code spans on one line (see [codeSpans]) with spaces, so their content is not taken as prose. */
    fun stripCodeSpans(line: String): String {
        val chars = line.toCharArray()
        spanRanges(line).forEach { (opener, closer) -> chars.fill(' ', opener.first, closer.last + 1) }
        return String(chars)
    }

    /**
     * Inline links and images on one line, outside code spans (a code span may be the link text),
     * with `<…>` unwrapped. No filtering: absolute URLs and anchors are returned as written.
     */
    fun links(line: String): List<MdLink> =
        INLINE_LINK.findAll(stripCodeSpans(line))
            .map { MdLink(it.groupValues[2].removeSurrounding("<", ">"), it.groups[1]!!.range) }
            .toList()

    /** Opener/closer backtick-run ranges of the code spans on one line. */
    private fun spanRanges(line: String): List<Pair<IntRange, IntRange>> = buildList {
        var from = 0
        while (true) {
            val opener = BACKTICK_RUN.find(line, from) ?: break
            var closer = opener.next()
            while (closer != null && closer.value.length != opener.value.length) closer = closer.next()
            if (closer == null) {
                from = opener.range.last + 1
            } else {
                add(opener.range to closer.range)
                from = closer.range.last + 1
            }
        }
    }

    /** Strips up to [maxLevels] block-quote prefixes ([QUOTE_LEVEL]); returns the levels stripped and the rest. */
    private fun unquote(line: String, maxLevels: Int = Int.MAX_VALUE): Pair<Int, String> {
        var depth = 0
        var rest = line
        while (depth < maxLevels) {
            val prefix = QUOTE_LEVEL.find(rest) ?: break
            rest = rest.substring(prefix.range.last + 1)
            depth++
        }
        return depth to rest
    }

    /** Closing fence: up to 3 spaces of indent, then only the opening fence char, at least as many as opened. */
    private fun isClosingFence(line: String, open: String): Boolean {
        val unindented = line.trimStart(' ')
        val marker = unindented.trimEnd()
        return line.length - unindented.length <= 3 && marker.length >= open.length && marker.all { it == open[0] }
    }
}
