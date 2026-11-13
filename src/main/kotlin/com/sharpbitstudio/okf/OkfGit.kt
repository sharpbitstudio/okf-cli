package com.sharpbitstudio.okf

import java.io.File
import java.io.IOException

/**
 * The only place the OKF tooling runs external processes.
 */
object OkfGit {
    /** Environment removed from every git call (plus `GIT_TRACE*`): repo selection and injected config. */
    private val ISOLATED_ENV = setOf("GIT_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE", "GIT_CONFIG_COUNT", "GIT_CONFIG_PARAMETERS")

    /** `git rev-parse` stderr fragments meaning "[workTreeRoot] has no work tree here" (outside a repository, inside `.git`). */
    private val NOT_A_WORK_TREE = listOf("not a git repository", "must be run in a work tree")

    /**
     * Root of the work tree that contains [dir] (`git rev-parse --show-toplevel`), `null` when [dir] is
     * not inside a work tree (not a repository, or inside `.git`). Any other failure — exit 128 also
     * covers e.g. `detected dubious ownership` (`safe.directory`) — throws with git's message.
     */
    fun workTreeRoot(dir: File): File? {
        val args = listOf("rev-parse", "--show-toplevel")
        // C locale: the "not a work tree" diagnosis below matches git's untranslated messages.
        val result = run(dir, args, okExit = setOf(0, 128), env = mapOf("LC_ALL" to "C"))
        if (result.exit == 0) return result.out.trim().ifEmpty { null }?.let(::File)
        if (NOT_A_WORK_TREE.any { it in result.err }) return null
        throw OkfException("git ${args.joinToString(" ")} failed (exit ${result.exit}) in $dir: ${result.err.trim()}")
    }

    /** Repo-relative paths of tracked and untracked-but-not-ignored files (`git ls-files`), `/`-separated. */
    fun listFiles(repoDir: File): List<String> =
        git(repoDir, listOf("ls-files", "-z", "--cached", "--others", "--exclude-standard")).split('\u0000').filter { it.isNotEmpty() }

    /** The subset of [paths] that `.gitignore` rules exclude — one batched `git check-ignore --stdin` call. */
    fun ignored(repoDir: File, paths: Collection<String>): Set<String> {
        if (paths.isEmpty()) return emptySet()
        // Exit code 1 means "none of the paths is ignored", not a failure.
        val stdin = paths.joinToString("\u0000", postfix = "\u0000")
        val out = git(repoDir, listOf("check-ignore", "--stdin", "-z"), input = stdin, okExit = setOf(0, 1))
        return out.split('\u0000').filter { it.isNotEmpty() }.toSet()
    }

    /** Full SHA of the commit [ref] names, `null` when it does not exist (`git rev-parse --verify --quiet`). */
    fun resolveCommit(repoDir: File, ref: String): String? =
        git(repoDir, listOf("rev-parse", "--verify", "--quiet", "$ref^{commit}"), okExit = setOf(0, 1)).trim().ifEmpty { null }

    /** Best common ancestor of [ref] and `HEAD`, `null` when there is none (unrelated or shallow history). */
    fun mergeBase(repoDir: File, ref: String): String? =
        git(repoDir, listOf("merge-base", ref, "HEAD"), okExit = setOf(0, 1)).trim().ifEmpty { null }

    /**
     * Files that differ between [commit] and the work tree (committed, staged and unstaged changes;
     * deletions included, a rename as old and new path) plus untracked-but-not-ignored files — `/`-separated.
     */
    fun changedFiles(repoDir: File, commit: String): Set<String> {
        val diff = git(repoDir, listOf("diff", "--name-only", "--no-renames", "-z", commit, "--"))
        val untracked = git(repoDir, listOf("ls-files", "-z", "--others", "--exclude-standard"))
        return (diff.split('\u0000') + untracked.split('\u0000')).filter { it.isNotEmpty() }.toSortedSet()
    }

    /**
     * Full SHAs of every non-merge commit of [range] (`<base>..HEAD`) that touched [path] (a literal
     * pathspec, no glob magic), newest first (`--topo-order`); when only merge commits did (an edit made
     * in a conflict resolution), those merges; empty when none did. All of them, not just the newest:
     * parallel histories in the range can each touch the file. Merges are skipped first because git
     * lists a merge as touching a file that both sides changed (TREESAME to neither parent) — after
     * `git merge origin/main` that would hide the branch commit that actually changed it.
     */
    fun commitsTouching(repoDir: File, range: String, path: String): List<String> {
        fun touchers(vararg options: String) =
            git(repoDir, listOf("log", "--topo-order", "--no-show-signature", "--no-follow", *options, "--format=%H", range, "--", ":(literal)$path"))
                .lines()
                .map(String::trim)
                .filter(String::isNotEmpty)
        return touchers("--no-merges").ifEmpty { touchers() }
    }

    /** Whether commit [ancestor] is an ancestor of (or the same as) commit [descendant] (`git merge-base --is-ancestor`). */
    fun isAncestor(repoDir: File, ancestor: String, descendant: String): Boolean =
        run(repoDir, listOf("merge-base", "--is-ancestor", ancestor, descendant), okExit = setOf(0, 1)).exit == 0

    /** Commits of [range] (`<base>..HEAD`) in `--topo-order` (newest first, no parent before its children), with their full messages. */
    fun commits(repoDir: File, range: String): List<ImpactCommit> =
        git(repoDir, listOf("log", "-z", "--topo-order", "--no-show-signature", "--format=%H%n%B", range, "--"))
            .split('\u0000')
            .filter { it.isNotBlank() }
            .map { ImpactCommit(it.substringBefore('\n').trim(), it.substringAfter('\n', "")) }

    /** Committer time (epoch seconds) of the newest commit on `HEAD` that touched [path], `null` when none did (`git log -1 --format=%ct`). */
    fun lastCommitTime(repoDir: File, path: String): Long? =
        git(repoDir, listOf("log", "-1", "--no-show-signature", "--format=%ct", "HEAD", "--", ":(literal)$path")).trim().toLongOrNull()

    /** Whether [repoDir] is a shallow clone (`git rev-parse --is-shallow-repository`): per-file history is then truncated. */
    fun isShallow(repoDir: File): Boolean =
        git(repoDir, listOf("rev-parse", "--is-shallow-repository")).trim() == "true"

    private fun git(repoDir: File, args: List<String>, input: String? = null, okExit: Set<Int> = setOf(0)): String =
        run(repoDir, args, input, okExit).out

    /** Exit code, stdout and stderr of one git call. */
    private data class GitResult(val exit: Int, val out: String, val err: String)

    /** Runs `git <args>` with [env] added; an exit code outside [okExit] throws. */
    // Output goes to temp files, not pipes: a full stdout/stderr pipe cannot block git while stdin is written.
    private fun run(
        repoDir: File,
        args: List<String>,
        input: String? = null,
        okExit: Set<Int> = setOf(0),
        env: Map<String, String> = emptyMap(),
    ): GitResult {
        val out = File.createTempFile("okf-git", ".out")
        val err = File.createTempFile("okf-git", ".err")
        try {
            val process = try {
                val builder = ProcessBuilder(listOf("git") + args).directory(repoDir).redirectOutput(out).redirectError(err)
                // Repository selection follows repoDir only; injected config and trace output cannot leak in.
                builder.environment().keys.removeIf { it in ISOLATED_ENV || it.startsWith("GIT_TRACE") }
                builder.environment().putAll(env)
                builder.start()
            } catch (e: IOException) {
                throw OkfException("cannot run git (okf needs git on the PATH): ${e.message}")
            }
            process.outputStream.use { stdin -> input?.let { stdin.write(it.toByteArray(Charsets.UTF_8)) } }
            val exit = process.waitFor()
            val stderr = err.readText(Charsets.UTF_8)
            if (exit !in okExit) throw OkfException("git ${args.first()} failed (exit $exit) in $repoDir: ${stderr.trim()}")
            return GitResult(exit, out.readText(Charsets.UTF_8), stderr)
        } finally {
            out.delete()
            err.delete()
        }
    }
}
