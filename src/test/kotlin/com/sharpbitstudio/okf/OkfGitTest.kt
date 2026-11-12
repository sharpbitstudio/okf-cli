package com.sharpbitstudio.okf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

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
