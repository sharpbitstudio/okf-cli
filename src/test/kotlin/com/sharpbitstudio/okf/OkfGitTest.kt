package com.sharpbitstudio.okf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files

/** Runs real git in a throwaway repo: the history-simplification behaviour [OkfGit] relies on is git's, not ours. */
class OkfGitTest {
    /** Outside the repo: the empty global git config and the (empty) hooks directory. */
    @TempDir
    lateinit var home: File

    /** Empty global config for the setup commands, so the developer's / runner's git config cannot leak in. */
    private lateinit var globalConfig: File

    @BeforeEach
    fun emptyGlobalConfig() {
        globalConfig = home.resolve("gitconfig").apply { writeText("") }
    }

    @Test
    fun `touchers skip a merge of a file changed on both sides, list parallel commits, fall back to a merge-only edit`(@TempDir repo: File) {
        git(repo, "init", "-q")
        listOf(
            "user.name" to "t",
            "user.email" to "t@t",
            "commit.gpgsign" to "false",
            "core.autocrlf" to "false",
            "core.fsmonitor" to "false",
            "core.hooksPath" to home.resolve("no-hooks").path,
        ).forEach { (key, value) -> git(repo, "config", key, value) }
        val lines = (1..10).map { "line $it" }
        repo.resolve("F.txt").writeText(lines.joinToString("\n", postfix = "\n"))
        repo.resolve("G.txt").writeText("g\n")
        repo.resolve("x.txt").writeText("x\n")
        repo.resolve("[x].txt").writeText("bracket\n")
        val base = commit(repo, "base")
        val branch = git(repo, "rev-parse", "--abbrev-ref", "HEAD")

        repo.resolve("F.txt").writeText((listOf("branch 1") + lines.drop(1)).joinToString("\n", postfix = "\n"))
        repo.resolve("[x].txt").writeText("bracket 2\n")
        val k1 = commit(repo, "branch: hunk A")
        // "main" moves on (hunk B of the same file); the branch merges it, so the merge-base becomes main's tip.
        git(repo, "checkout", "-q", "-b", "main-side", base)
        repo.resolve("F.txt").writeText((lines.dropLast(1) + "side 10").joinToString("\n", postfix = "\n"))
        val main = commit(repo, "main: hunk B")
        git(repo, "checkout", "-q", branch)
        git(repo, "merge", "-q", "--no-ff", "--no-edit", "main-side")
        val clean = git(repo, "rev-parse", "HEAD")
        assertEquals(clean, git(repo, "log", "-1", "--topo-order", "--format=%H", "$main..HEAD", "--", "F.txt"), "plain git log lists the merge")
        assertEquals(listOf(k1), OkfGit.commitsTouching(repo, "$main..HEAD", "F.txt"), "a file changed on both sides stays the branch commit's")
        // Measured from the old base, main's commit is a parallel toucher inside the range: both are listed.
        assertEquals(setOf(k1, main), OkfGit.commitsTouching(repo, "$base..HEAD", "F.txt").toSet())

        // An edit made only in a merge (conflict-resolution style): the merge is the toucher.
        git(repo, "checkout", "-q", "-b", "side2", main)
        repo.resolve("x.txt").writeText("x side2\n")
        val side2 = commit(repo, "side2")
        git(repo, "checkout", "-q", branch)
        git(repo, "merge", "-q", "--no-ff", "--no-commit", "side2")
        repo.resolve("G.txt").writeText("g in the merge\n")
        val mergeOnly = commit(repo, "merge side2 with an edit")
        assertEquals(listOf(mergeOnly), OkfGit.commitsTouching(repo, "$side2..HEAD", "G.txt"), "only a merge touched G: the merge")
        assertEquals(emptyList<String>(), OkfGit.commitsTouching(repo, "$side2..HEAD", "nope.txt"))

        // The pathspec is literal: `[x].txt` is that file, not the glob class matching `x.txt`.
        repo.resolve("x.txt").writeText("x 3\n")
        commit(repo, "x only")
        assertEquals(listOf(k1), OkfGit.commitsTouching(repo, "$base..HEAD", "[x].txt"))

        assertTrue(OkfGit.isAncestor(repo, k1, mergeOnly))
        assertTrue(OkfGit.isAncestor(repo, k1, k1))
        assertFalse(OkfGit.isAncestor(repo, mergeOnly, k1))
        assertTrue(OkfGit.isAncestor(repo, clean, mergeOnly))
    }

    @Test
    fun `work-tree root is the same from the root and from a subdirectory, none inside the git dir`(@TempDir repo: File) {
        git(repo, "init", "-q")
        val sub = repo.resolve("a/b").apply { mkdirs() }
        assertEquals(repo.canonicalFile, OkfGit.workTreeRoot(repo)?.canonicalFile)
        assertEquals(repo.canonicalFile, OkfGit.workTreeRoot(sub)?.canonicalFile)
        assertEquals(null, OkfGit.workTreeRoot(repo.resolve(".git")))
    }

    @Test
    fun `work-tree root - any other git failure throws with git's message, not 'no work tree'`(@TempDir repo: File) {
        // Exit 128 like "not a git repository", but a broken repo (as is `detected dubious ownership`): not a null.
        git(repo, "init", "-q")
        git(repo, "config", "core.repositoryformatversion", "99")
        val e = assertThrows<OkfException> { OkfGit.workTreeRoot(repo) }
        assertTrue(e.message!!.startsWith("git rev-parse --show-toplevel failed (exit 128) in "), e.message)
        assertTrue("found 99" in e.message!!, e.message)
    }

    @Test
    fun `ignored answers the other paths when git rejects one of them`(@TempDir repo: File) {
        ignoreRepo(repo)
        // An escaped `!` (`\!bang`) must keep its meaning when git rejects a path (negation: the version-neutral test).
        repo.resolve(".gitignore").writeText("/gen\n*.log\n\\!bang\n")
        val paths = listOf(
            "gen/a.kt", ":!foo", "docs/x.log", "!bang", ":(literal)foo", ":(prefix:100)x", ":!docs/app/Gone.kt", "C:/x",
            "a:b", "docs/..\\..\\x", "\\x", "docs/gone.md",
        )
        assertEquals(setOf("gen/a.kt", "docs/x.log", "!bang"), OkfGit.ignored(repo, paths))
    }

    @Test
    fun `a path containing NUL is never asked and never ignored`(@TempDir repo: File) {
        ignoreRepo(repo)
        // `/gen` excludes `gen` as a plain path, so asking the NUL path's part before the NUL would answer "ignored".
        repo.resolve(".gitignore").writeText("/gen\n")
        assertEquals(emptySet<String>(), OkfGit.ignored(repo, listOf("gen/x\u0000y")))
        assertEquals(setOf("gen/a.kt"), OkfGit.ignored(repo, listOf("gen/x\u0000y", "gone\u0000", "a\u0000/b", "gen/a.kt")))
    }

    @Test
    fun `paths that git reads with pathspec magic keep git's answer`(@TempDir repo: File) {
        ignoreRepo(repo)
        repo.resolve(".gitignore").writeText("foo\n")
        val paths = listOf(":foo", "::foo", ":/foo", ":(top)foo", "foo")
        assertEquals(paths.toSet(), OkfGit.ignored(repo, paths))
        // The same paths after a path git rejects, so they are answered by the resumed lookup.
        assertEquals(paths.toSet(), OkfGit.ignored(repo, listOf(":!reject") + paths))
    }

    @Test
    fun `a match on an empty pattern gets the same answer when git rejects another path`(@TempDir repo: File) {
        ignoreRepo(repo)
        // A blank line of a CRLF .gitignore is an empty pattern that matches a path ending in a separator (`\` too on
        // Windows); git reports the match with an empty pattern.
        repo.resolve(".gitignore").writeText("a\r\n\r\nb\r\n")
        val paths = listOf("docs/missing/", "docs/missing\\", "docs/x.md")
        val plain = OkfGit.ignored(repo, paths)
        assertTrue("docs/missing/" in plain, "$plain")
        assertEquals(plain, OkfGit.ignored(repo, listOf(":!foo") + paths))
    }

    @Test
    fun `a path that does not survive UTF-8 encoding gets the same answer when git rejects another path`(@TempDir repo: File) {
        ignoreRepo(repo)
        repo.resolve(".gitignore").writeText("/gen\n")
        // A lone surrogate reaches git as `?`, and git answers for that name, as in the plain call.
        val paths = listOf("gen/a\uD800.kt", "gen/b.kt")
        val plain = OkfGit.ignored(repo, paths)
        assertEquals(setOf("gen/a?.kt", "gen/b.kt"), plain)
        assertEquals(plain, OkfGit.ignored(repo, listOf(paths[0], ":!foo", paths[1])))
    }

    @Test
    fun `a rejected path with dot segments does not take the answer of a directory it is not below`(@TempDir repo: File) {
        ignoreRepo(repo)
        repo.resolve(".gitignore").writeText("/gen\n")
        // git rejects the first two (outside the repository); their first segment `gen` is excluded but not above them.
        val paths = listOf("gen/../../x", "gen/./../../x", "gen/../outside/x.md", "gen/a.kt")
        assertEquals(setOf("gen/a.kt"), OkfGit.ignored(repo, paths))
    }

    @Test
    fun `a path inside a submodule is not ignored, and the other paths are still answered`(@TempDir repo: File) {
        ignoreRepo(repo)
        repo.resolve(".gitignore").writeText("/gen\n")
        val lib = repo.resolve("vendor/lib").apply { mkdirs() }
        git(lib, "init", "-q")
        lib.resolve("README.md").writeText("lib\n")
        git(lib, "add", "README.md")
        git(lib, "-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false", "commit", "-q", "-m", "inner")
        git(repo, "add", "vendor/lib")
        assertEquals(setOf("gen/a.kt"), OkfGit.ignored(repo, listOf("vendor/lib/a/b/c/x.md", "vendor/lib/okf-dir-probe", "gen/a.kt")))
    }

    @Test
    fun `a path below a symbolic link takes the answer of the first directory above it that git excludes or rejects`(@TempDir repo: File) {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"), "symbolic links need a privilege on Windows")
        ignoreRepo(repo)
        // `/dlink/` ends in `/`: such a pattern does not match a symbolic link.
        repo.resolve(".gitignore").writeText("/gen\n/gen2/\n/glink\n/dlink/\n")
        repo.resolve("real/a.md").apply { parentFile.mkdirs() }.writeText("real\n")
        repo.resolve("gen2").mkdirs()
        listOf("tlink", "ulink", "glink", "dlink").forEach { Files.createSymbolicLink(repo.resolve(it).toPath(), File("real").toPath()) }
        Files.createSymbolicLink(repo.resolve("gen2/link").toPath(), File("../real").toPath())
        git(repo, "add", "real", "tlink")
        assertEquals(
            setOf("glink/a.md", "glink/sub/a.md", "gen2/link/a.md", "gen/a.kt"),
            OkfGit.ignored(repo, listOf("tlink/a.md", "ulink/a.md", "glink/a.md", "glink/sub/a.md", "dlink/a.md", "gen2/link/a.md", "gen/a.kt")),
        )
    }

    @Test
    fun `a rejected path ahead of a batch larger than the pipe is still answered`(@TempDir repo: File) {
        ignoreRepo(repo)
        repo.resolve(".gitignore").writeText("/gen\n")
        // About 127 KB of input, every path component shorter than 80 characters.
        val paths = (0 until 1500).map { "gen/" + "p".repeat(70) + "/f$it.kt" }
        assertEquals(paths.toSet(), OkfGit.ignored(repo, listOf(":!foo") + paths))
    }

    @Test
    fun `a repository-wide failure of the ignore lookup still fails with git's message`(@TempDir repo: File) {
        ignoreRepo(repo)
        git(repo, "config", "core.ignoreCase", "notabool")
        val e = assertThrows<OkfException> { OkfGit.ignored(repo, listOf("gen/x", ":!foo")) }
        assertTrue(e.message!!.startsWith("git check-ignore failed (exit 128) in "), e.message)
        assertTrue("bad boolean config value" in e.message!!, e.message)
    }

    @Test
    fun `on Windows, a backslash path git rejects is not ignored below an ignored directory`(@TempDir repo: File) {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"), "Git for Windows reads a backslash as a separator")
        ignoreRepo(repo)
        repo.resolve(".gitignore").writeText("/docs/\n")
        repo.resolve("docs/a.md").apply { parentFile.mkdirs() }.writeText("a\n")
        assertEquals(setOf("docs/gone.md"), OkfGit.ignored(repo, listOf("docs/..\\..\\other\\README.md", "docs/gone.md")))
    }

    @Test
    fun `paths with control characters get the same answer through the stdin file`(@TempDir repo: File) {
        ignoreRepo(repo)
        repo.resolve(".gitignore").writeText("/gen\n")
        val paths = listOf("gen/a\u001ab.kt", "gen/c\r\nd.kt", "docs/e\r\n.md")
        assertEquals(setOf("gen/a\u001ab.kt", "gen/c\r\nd.kt"), OkfGit.ignored(repo, paths))
        // The same paths after a path git rejects, so they are answered by the resumed lookup.
        assertEquals(setOf("gen/a\u001ab.kt", "gen/c\r\nd.kt"), OkfGit.ignored(repo, listOf(":!foo") + paths))
    }

    @Test
    fun `a crash of git on a later path keeps the answers git gave before it`(@TempDir repo: File) {
        ignoreRepo(repo)
        repo.resolve(".gitignore").writeText("*.log\n/gen/\n")
        val accepted = listOf("docs/x.log", "gen/a.kt", "docs/gone.md")
        val plain = OkfGit.ignored(repo, accepted)
        assertEquals(setOf("docs/x.log", "gen/a.kt"), plain)
        // git stops with `BUG:` on `:(prefix:100)x`; the answers it wrote before must not be lost or blamed on others.
        assertEquals(plain, OkfGit.ignored(repo, listOf("docs/x.log", "gen/a.kt", ":(prefix:100)x", "docs/gone.md")))
    }

    @Test
    fun `a crash of git after more answers than its output buffer holds keeps every answer`(@TempDir repo: File) {
        ignoreRepo(repo)
        repo.resolve(".gitignore").writeText("/gen\n")
        val paths = (0 until 1500).map { "gen/" + "p".repeat(70) + "/f$it.kt" }
        assertEquals(paths.toSet(), OkfGit.ignored(repo, paths + ":(prefix:100)x"))
    }

    @Test
    fun `a path below an ignored symbolic link gets its answer when the path does not survive UTF-8 encoding`(@TempDir repo: File) {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"), "symbolic links need a privilege on Windows")
        ignoreRepo(repo)
        // The lone surrogate reaches git as `?`: the link is named that way on disk.
        repo.resolve(".gitignore").writeText("/x?y\n")
        repo.resolve("real/a.md").apply { parentFile.mkdirs() }.writeText("real\n")
        Files.createSymbolicLink(repo.resolve("x?y").toPath(), File("real").toPath())
        assertEquals(setOf("x\uD800y/a.md"), OkfGit.ignored(repo, listOf("x\uD800y/a.md")))
    }

    @Test
    fun `a negated match gets the plain call's answer when git rejects another path`(@TempDir repo: File) {
        ignoreRepo(repo)
        // git before 2.26 lists a path whose matching pattern is negated in its plain answer, later git does not; the
        // resumed lookup follows the installed git. This git's side is checked here; the other only by reading.
        repo.resolve(".gitignore").writeText("*.log\n!keep.log\n")
        val paths = listOf("keep.log", "docs/x.log", "docs/keep.log")
        val plain = OkfGit.ignored(repo, paths)
        assertTrue("docs/x.log" in plain, "$plain")
        assertEquals(plain, OkfGit.ignored(repo, listOf(":!foo") + paths))
    }

    /** A fresh repository for [OkfGit.ignored]: no global excludes file and no fsmonitor (the lookup runs with the global config). */
    private fun ignoreRepo(repo: File) {
        git(repo, "init", "-q")
        git(repo, "config", "core.excludesFile", home.resolve("empty").apply { writeText("") }.path)
        git(repo, "config", "core.fsmonitor", "false")
    }

    private fun commit(repo: File, message: String): String {
        git(repo, "add", "-A")
        git(repo, "commit", "-q", "--no-verify", "-m", message)
        return git(repo, "rev-parse", "HEAD")
    }

    private fun git(dir: File, vararg args: String): String {
        val builder = ProcessBuilder(listOf("git") + args).directory(dir).redirectErrorStream(true)
        builder.environment().apply {
            listOf("GIT_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE").forEach { remove(it) }
            put("GIT_CONFIG_GLOBAL", globalConfig.path)
            put("GIT_CONFIG_NOSYSTEM", "1")
        }
        val process = builder.start()
        val out = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed: $out" }
        return out.trim()
    }
}
