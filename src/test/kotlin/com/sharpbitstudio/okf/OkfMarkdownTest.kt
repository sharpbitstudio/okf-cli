package com.sharpbitstudio.okf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll

class OkfMarkdownTest {
    @Test
    fun `code spans pair equal backtick runs and trim one space on both sides`() {
        val spans = OkfMarkdown.codeSpans("a `x.java` and `` `y` `` then ` z ` and a lone ` tick")
        assertEquals(listOf("x.java", "`y`", "z"), spans.map { it.content })
        assertEquals(2, spans.first().column)
    }

    @Test
    fun `stripping code spans blanks them in place`() {
        val line = "see `a.md` and ``b `c` d``!"
        val stripped = OkfMarkdown.stripCodeSpans(line)
        assertEquals(line.length, stripped.length)
        assertEquals("see " + " ".repeat(6) + " and " + " ".repeat(11) + "!", stripped)
    }

    @Test
    fun `links cover links and images, unwrap angle brackets, and skip code spans`() {
        val line = "[a](../x.md#top) ![img](pic.png \"title\") [b](<with space.md>) `[c](in-code.md)` [`d`](https://e.org)"
        val links = OkfMarkdown.links(line)
        assertEquals(listOf("../x.md#top", "pic.png", "with space.md", "https://e.org"), links.map { it.target })
        assertEquals("[`d`]", line.substring(links.last().text))
    }

    @Test
    fun `bare link destinations may contain balanced parentheses, titles still work`() {
        val line = "[spec](foo(bar).md) [x](a.md) [t](a.md \"title\") [n](a(b(c)).md#s) [u](a(b.md) [i](p.png) (y)"
        val links = OkfMarkdown.links(line)
        assertEquals(listOf("foo(bar).md", "a.md", "a.md", "a(b(c)).md#s", "p.png"), links.map { it.target })
    }

    @Test
    fun `fenced blocks are blanked and the line structure is kept`() {
        val text = "a\n```kotlin\n`x.kt`\n```\nb\n~~~\n`y.kt`\n"
        val stripped = OkfMarkdown.stripFencedBlocks(text)
        assertEquals(text.split('\n').size, stripped.split('\n').size)
        assertEquals(listOf("a", "", "", "", "b", "", "", ""), stripped.split('\n'))
    }

    // Long link targets: a megabyte (MIB) or 100,000 groups, far beyond what a recursive regex repetition survives on a
    // default thread stack. Each input is its own assertAll executable, so every failing input is recorded.

    @Test
    fun `a bare destination of a megabyte is one link, at top level and inside one and two levels of parentheses`() {
        assertAll(
            { assertSameText(listOf(MdLink(MIB, 0..2)), OkfMarkdown.links("[a]($MIB)")) },
            { assertSameText(listOf(MdLink("p($MIB)q", 0..2)), OkfMarkdown.links("[b](p($MIB)q)")) },
            { assertSameText(listOf(MdLink("p(q($MIB)r)s", 0..2)), OkfMarkdown.links("[c](p(q($MIB)r)s)")) },
        )
    }

    @Test
    fun `an inline base64 image of a megabyte is one link, and the links after it on the line are found`() {
        val line = "![logo](data:image/png;base64,$MIB) [next](next.md \"t\")"
        val next = line.indexOf("[next]")
        assertSameText(
            listOf(MdLink("data:image/png;base64,$MIB", 1..6), MdLink("next.md", next..next + 5)),
            OkfMarkdown.links(line),
        )
    }

    @Test
    fun `many balanced groups in a long destination, at top level and one level deep`() {
        val top = "(b)".repeat(100_000)
        val nested = "p(" + "(d)".repeat(100_000) + ")q"
        assertAll(
            { assertSameText(listOf(MdLink(top, 0..2)), OkfMarkdown.links("[a]($top)")) },
            { assertSameText(listOf(MdLink(nested, 0..2)), OkfMarkdown.links("[c]($nested)")) },
        )
    }

    @Test
    fun `long destinations three or four levels deep, in angle brackets, and long titles and labels parse as before`() {
        assertAll(
            { assertSameText(listOf(MdLink("p(q(r($MIB)s)t)u", 0..2)), OkfMarkdown.links("[a](p(q(r($MIB)s)t)u)")) },
            // Four levels of parentheses: not a link.
            { assertSameText(emptyList<MdLink>(), OkfMarkdown.links("[b](p(q(r(s($MIB)t)u)v)w)")) },
            { assertSameText(listOf(MdLink("$MIB y", 0..2)), OkfMarkdown.links("[c](<$MIB y>)")) },
            { assertSameText(listOf(MdLink("d.md", 0..2)), OkfMarkdown.links("[d](d.md \"$MIB\")")) },
            { assertSameText(listOf(MdLink("e.md", 0..MIB.length + 1)), OkfMarkdown.links("[$MIB](e.md)")) },
        )
    }

    @Test
    fun `an unclosed angle bracket or a failing title after a long destination`() {
        val angleTitle = "[b](<$MIB> \"t) [c](c.md)"
        val c = angleTitle.indexOf("[c]")
        val bareTitle = "[d]($MIB \"t) [e](e.md)"
        val e = bareTitle.indexOf("[e]")
        assertAll(
            // `<` without `>` is read as the first character of a bare target.
            { assertSameText(listOf(MdLink("<$MIB", 0..2)), OkfMarkdown.links("[a](<$MIB)")) },
            { assertSameText(listOf(MdLink("c.md", c..c + 2)), OkfMarkdown.links(angleTitle)) },
            { assertSameText(listOf(MdLink("e.md", e..e + 2)), OkfMarkdown.links(bareTitle)) },
            { assertSameText(emptyList<MdLink>(), OkfMarkdown.links("[f]($MIB")) },
        )
    }
}
