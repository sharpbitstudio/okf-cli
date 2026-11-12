package com.sharpbitstudio.okf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class OkfGlobTest {
    @Test
    fun `literal glob matches only the exact path`() {
        val glob = OkfGlob.compile("server/build.gradle.kts")
        assertTrue(glob.matches("server/build.gradle.kts"))
        assertFalse(glob.matches("server/buildXgradle.kts"), "`.` is literal, not a regex wildcard")
        assertFalse(glob.matches("x/server/build.gradle.kts"), "globs are anchored at the repo root")
        assertFalse(glob.matches("server/build.gradle.kts.bak"))
    }

    @Test
    fun `star and question mark stay within one segment`() {
        val star = OkfGlob.compile("server/*.kts")
        assertTrue(star.matches("server/build.gradle.kts"))
        assertFalse(star.matches("server/sub/build.gradle.kts"))
        val question = OkfGlob.compile("db/V?__init.sql")
        assertTrue(question.matches("db/V1__init.sql"))
        assertFalse(question.matches("db/V12__init.sql"))
        assertFalse(OkfGlob.compile("a?b").matches("a/b"))
    }

    @Test
    fun `trailing double star matches everything below the directory`() {
        val glob = OkfGlob.compile("server/src/main/java/otp/**")
        assertTrue(glob.matches("server/src/main/java/otp/OtpService.java"))
        assertTrue(glob.matches("server/src/main/java/otp/internal/jobs/OtpExpiryJob.java"))
        assertFalse(glob.matches("server/src/main/java/otp"))
        assertFalse(glob.matches("server/src/main/java/otpx/Foo.java"))
    }

    @Test
    fun `leading and inner double star match zero or more segments`() {
        val leading = OkfGlob.compile("**/package-info.java")
        assertTrue(leading.matches("package-info.java"))
        assertTrue(leading.matches("a/b/c/package-info.java"))
        assertFalse(leading.matches("a/xpackage-info.java"))
        val inner = OkfGlob.compile("server/**/jobs/*Job.java")
        assertTrue(inner.matches("server/jobs/OtpExpiryJob.java"))
        assertTrue(inner.matches("server/src/main/java/otp/internal/jobs/OtpExpiryJob.java"))
        assertFalse(inner.matches("server/src/jobs/nested/OtpExpiryJob.java"))
        assertTrue(OkfGlob.compile("**").matches("any/path/at/all.txt"))
    }

    @Test
    fun `braces are alternatives, may nest and hold wildcards`() {
        val glob = OkfGlob.compile("server/src/{main,test}/java/**/{Otp*,Sms{Device,Gateway}}.java")
        assertTrue(glob.matches("server/src/main/java/otp/OtpService.java"))
        assertTrue(glob.matches("server/src/test/java/sms/SmsGateway.java"))
        assertTrue(glob.matches("server/src/test/java/SmsDevice.java"))
        assertFalse(glob.matches("server/src/it/java/otp/OtpService.java"))
        assertFalse(glob.matches("server/src/main/java/sms/SmsOther.java"))
        assertTrue(OkfGlob.compile("a,b").matches("a,b"), "a comma outside braces is literal")
    }

    @Test
    fun `braces are expanded before the double-star check, which applies to every alternative`() {
        val glob = OkfGlob.compile("server/{otp/**,auth/**}")
        assertTrue(glob.matches("server/otp/x/y.java"))
        assertTrue(glob.matches("server/auth/z.kt"))
        assertFalse(glob.matches("server/sport/z.kt"))
        assertTrue(OkfGlob.compile("{**/a.txt,b/**}").matches("x/y/a.txt"))
        assertTrue(OkfGlob.compile("a/[{]b").matches("a/{b"), "a brace inside a character class is literal")
        listOf("a/{**b,c/**}", "a/{c/**,**b}", "{a/**b}", "a/{b,c{d**,e}}").forEach { bad ->
            val error = assertThrows<IllegalArgumentException>(bad) { OkfGlob.compile(bad) }
            assertTrue("`**` must be a whole path segment" in error.message.orEmpty(), "'$bad': ${error.message}")
        }
    }

    @Test
    fun `an error in one of several alternatives names it`() {
        val error = assertThrows<IllegalArgumentException> { OkfGlob.compile("a/{c/**,**b}") }
        assertTrue(error.message.orEmpty().endsWith("in alternative 'a/**b'"), error.message)
        val single = assertThrows<IllegalArgumentException> { OkfGlob.compile("a/**b") }
        assertFalse("in alternative" in single.message.orEmpty(), single.message)
    }

    @Test
    fun `more than 1000 brace combinations are rejected`() {
        val ten = "{0,1,2,3,4,5,6,7,8,9}"
        assertEquals(1000, (0..999).count { OkfGlob.compile("$ten$ten$ten").matches("%03d".format(it)) })
        val error = assertThrows<IllegalArgumentException> { OkfGlob.compile("$ten$ten$ten{a,b}") }
        assertTrue("too many `{}` combinations (max 1000)" in error.message.orEmpty(), error.message)
    }

    @Test
    fun `character classes support sets, ranges and negation but never match a slash`() {
        assertTrue(OkfGlob.compile("V[12]__x.sql").matches("V2__x.sql"))
        assertFalse(OkfGlob.compile("V[12]__x.sql").matches("V3__x.sql"))
        assertTrue(OkfGlob.compile("V[0-9]__x.sql").matches("V7__x.sql"))
        assertTrue(OkfGlob.compile("a[!b]c").matches("axc"))
        assertFalse(OkfGlob.compile("a[!b]c").matches("abc"))
        assertFalse(OkfGlob.compile("a[!b]c").matches("a/c"), "a negated class must not match the separator")
        assertTrue(OkfGlob.compile("a[]]c").matches("a]c"), "`]` right after the opener is a member")
        assertTrue(OkfGlob.compile("a[&^]c").matches("a&c"), "regex class metacharacters are literal members")
    }

    @Test
    fun `regex metacharacters in literal parts are escaped`() {
        val glob = OkfGlob.compile("a+b/(x)|y\$.md")
        assertTrue(glob.matches("a+b/(x)|y\$.md"))
        assertFalse(glob.matches("aab/x.md"))
    }

    @Test
    fun `invalid globs are rejected with a reason`() {
        mapOf(
            "" to "empty glob",
            "a\\b" to "`\\` is not allowed",
            "a/b/" to "globs match files — use `dir/**`",
            "a**" to "`**` must be a whole path segment",
            "**b/c" to "`**` must be a whole path segment",
            "a/[bc" to "unclosed `[`",
            "a/[b/c]" to "cannot match `/`",
            "a/{b,c" to "unclosed `{`",
            "a/b}" to "`}` without a matching `{`",
        ).forEach { (glob, reason) ->
            val error = assertThrows<IllegalArgumentException>("'$glob'") { OkfGlob.compile(glob) }
            assertTrue(reason in error.message.orEmpty(), "'$glob': expected <$reason>, got <${error.message}>")
        }
        assertEquals("a/**", OkfGlob.compile("a/**").toString())
    }
}
