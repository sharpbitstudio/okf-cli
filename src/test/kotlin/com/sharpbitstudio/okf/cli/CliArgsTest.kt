package com.sharpbitstudio.okf.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

class CliArgsTest {
    @TempDir
    lateinit var cwd: File

    @Test
    fun `defaults - repo is the working directory, flags false, base absent`() {
        val args = parse("check")
        assertEquals(Command.CHECK, args.command)
        assertEquals(cwd.absoluteFile.normalize(), args.repo)
        assertFalse(args.strict || args.refsStrict || args.codePathsRequired || args.impactStrict)
        assertNull(parse("impact").base)
    }

    @Test
    fun `bare flags are true, explicit values are parsed strictly`() {
        val bare = parse("check", "--strict", "--refs-strict", "--code-paths-required")
        assertTrue(bare.strict && bare.refsStrict && bare.codePathsRequired)
        val explicit = parse("check", "--strict=true", "--refs-strict=false", "--code-paths-required=true")
        assertTrue(explicit.strict)
        assertFalse(explicit.refsStrict)
        assertTrue(explicit.codePathsRequired)
        assertTrue(parse("impact", "--impact-strict").impactStrict)
        assertFalse(parse("impact", "--impact-strict=false").impactStrict)
    }

    @Test
    fun `invalid boolean values are usage errors`() {
        assertUsage("--strict must be 'true' or 'false', got 'ture'", "check", "--strict=ture")
        assertUsage("--impact-strict must be 'true' or 'false', got 'TRUE'", "impact", "--impact-strict=TRUE")
        assertUsage("--refs-strict must be 'true' or 'false', got ''", "check", "--refs-strict=")
    }

    @Test
    fun `base - absent is null, empty is kept (skip), a value is kept as given`() {
        assertNull(parse("impact").base)
        assertEquals("", parse("impact", "--base=").base)
        assertEquals("", parse("impact", "--base", "").base)
        assertEquals("origin/main", parse("impact", "--base=origin/main").base)
        assertEquals("0000000000000000000000000000000000000000", parse("impact", "--base", "0000000000000000000000000000000000000000").base)
        assertUsage("--base requires a value", "impact", "--base")
    }

    @Test
    fun `space form of a value option does not swallow a following option`() {
        assertUsage("--repo requires a value (--repo=<value>)", "check", "--repo", "--strict")
        assertUsage("--base requires a value (--base=<value>)", "impact", "--base", "--impact-strict")
        assertEquals("", parse("impact", "--base=", "--impact-strict").base)
    }

    @Test
    fun `repo resolves against the working directory, either form`() {
        assertEquals(cwd.resolve("sub").absoluteFile.normalize(), parse("index", "--repo=sub").repo)
        assertEquals(cwd.resolve("sub").absoluteFile.normalize(), parse("index", "--repo", "sub/./").repo)
        val absolute = cwd.resolve("abs").absoluteFile
        assertEquals(absolute, parse("--repo=${absolute.path}", "check").repo)
        assertUsage("--repo must not be empty", "check", "--repo=")
    }

    @Test
    fun `xcheck requires a workspace, report is optional, both resolve against the working directory`() {
        val args = parse("xcheck", "--workspace=ws", "--report", "out/report.md")
        assertEquals(Command.XCHECK, args.command)
        assertEquals(cwd.resolve("ws").absoluteFile.normalize(), args.workspace)
        assertEquals(cwd.resolve("out/report.md").absoluteFile.normalize(), args.report)
        assertNull(parse("xcheck", "--workspace=ws").report)
        assertUsage("--workspace=<dir> is required for `okf xcheck`", "xcheck")
        assertUsage("--workspace must not be empty", "xcheck", "--workspace=")
        assertUsage("--report must not be empty", "xcheck", "--workspace=ws", "--report=")
        assertUsage("--workspace does not apply to `okf check`", "check", "--workspace=ws")
        assertUsage("--report does not apply to `okf impact`", "impact", "--report=r.md")
    }

    @Test
    fun `bad command lines are usage errors`() {
        assertUsage("no command given", "--strict")
        assertUsage("unknown command 'lint'", "lint")
        assertUsage("unexpected argument 'extra'", "check", "extra")
        assertUsage("unknown option '--stric'", "check", "--stric")
        assertUsage("--strict given more than once", "check", "--strict", "--strict=false")
        assertUsage("--base does not apply to `okf check`", "check", "--base=main")
        assertUsage("--strict does not apply to `okf impact`", "impact", "--strict")
        assertUsage("--strict does not apply to `okf index`", "index", "--strict")
        assertUsage("--repo does not apply to `okf version`", "version", "--repo=.")
    }

    private fun parse(vararg args: String) = CliArgs.parse(args.toList(), cwd)

    private fun assertUsage(fragment: String, vararg args: String) {
        val e = assertThrows<UsageException> { parse(*args) }
        assertTrue(fragment in e.message!!, "expected <$fragment> in <${e.message}>")
    }
}
