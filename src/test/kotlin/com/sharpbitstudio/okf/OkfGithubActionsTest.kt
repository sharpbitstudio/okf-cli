package com.sharpbitstudio.okf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OkfGithubActionsTest {
    @Test
    fun `message escapes percent and line breaks, colon and comma only in properties`() {
        assertEquals(
            "::warning file=docs/a%3Ab%2Cc.md,line=1,title=OKF%3A x%2C y::100%25 a: b, c%0Anext%0D%0Aline",
            OkfGithubActions.annotation("warning", "OKF: x, y", "100% a: b, c\nnext\r\nline", "docs/a:b,c.md", 1),
        )
    }

    @Test
    fun `file and line are optional`() {
        assertEquals("::error title=T::m", OkfGithubActions.annotation("error", "T", "m"))
    }
}
