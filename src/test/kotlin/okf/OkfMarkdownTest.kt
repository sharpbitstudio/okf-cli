package okf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

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
}
