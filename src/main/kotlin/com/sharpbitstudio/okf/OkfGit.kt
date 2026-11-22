package com.sharpbitstudio.okf

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * The only place the OKF tooling runs external processes.
 */
object OkfGit {
    /**
     * Environment removed from every git call (plus `GIT_TRACE*`): repo selection, injected config, and the pathspec
     * variables (`GIT_LITERAL_PATHSPECS`, `GIT_GLOB_PATHSPECS`, `GIT_NOGLOB_PATHSPECS`, `GIT_ICASE_PATHSPECS`), which
     * make `git check-ignore` reject every path and can change which commits `git log` finds for okf's `:(literal)`
     * paths. Names are matched ignoring case, as Windows treats them (Git for Windows reads `git_literal_pathspecs`).
     */
    private val ISOLATED_ENV = setOf(
        "GIT_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE", "GIT_CONFIG_COUNT", "GIT_CONFIG_PARAMETERS",
        "GIT_LITERAL_PATHSPECS", "GIT_GLOB_PATHSPECS", "GIT_NOGLOB_PATHSPECS", "GIT_ICASE_PATHSPECS",
    )

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

    /**
     * The subset of [paths] that `.gitignore` rules exclude — one batched `git check-ignore --stdin` call. When git
     * refuses a path of the batch (pathspec magic it does not support here, such as `:!x`, or magic it stops on, such
     * as `:(prefix:100)x`; a path it reads as absolute, such as `C:/x` on Windows; a path inside a submodule or below a
     * symbolic link), the paths are asked again and git's answer is taken up to each refused path. A refused path made
     * of plain segments (no `\`, no `:`, no empty, `.` or `..` segment) is excluded when, asked from the top down, a
     * directory above it is excluded before one is refused; any other refused path is not excluded. A path containing
     * U+0000 is never asked and never excluded: no file name holds one, and the `-z` framing would split it. When git
     * refuses `.` as well, the failure is not about a path and throws. A path git answers is returned as git echoes it
     * (its UTF-8: a lone surrogate comes back as `?`); a path excluded through a directory above it is returned as
     * given.
     */
    fun ignored(repoDir: File, paths: Collection<String>): Set<String> {
        val askable = paths.filter { '\u0000' !in it }
        if (askable.isEmpty()) return emptySet()
        val batch = checkIgnore(repoDir, askable)
        batch.ignored?.let { return it }
        if (checkIgnore(repoDir, listOf(".")).ignored == null) throw OkfException(batch.error)
        val rejected = mutableListOf<String>()
        val negated = NegatedMatch(repoDir)
        val ignored = resumed(repoDir, askable, rejected, negated).toMutableSet()
        // Inside a submodule or below a symbolic link the directories above decide, from the top down. Other paths
        // (magic, drive letters, Windows separators, `.` / `..` / empty segments) have no reliable directories above
        // them.
        var pending = rejected.filter(::plainPath)
        var depth = 1
        while (pending.isNotEmpty()) {
            // Each pending path deep enough for this level, with its directory at this depth as git reads and echoes
            // it (UTF-8); the result keeps the paths as given.
            val above = pending.filter { path -> path.count { it == '/' } >= depth }
                .associateWith { utf8(it.split('/').take(depth).joinToString("/")) }
            if (above.isEmpty()) break
            val dirs = above.values.distinct()
            val dirsRejected = mutableListOf<String>()
            val dirsIgnored = checkIgnore(repoDir, dirs).ignored ?: resumed(repoDir, dirs, dirsRejected, negated)
            ignored += above.filterValues { it in dirsIgnored }.keys
            pending = above.filterValues { it !in dirsIgnored && it !in dirsRejected }.keys.toList()
            depth++
        }
        return ignored
    }

    /** [text] as git reads it from okf and echoes it back: its UTF-8 (a lone surrogate becomes `?`). */
    private fun utf8(text: String) = String(text.toByteArray(Charsets.UTF_8), Charsets.UTF_8)

    /** A path whose `/`-separated parts are its directories: no `\` or `:`, and no empty, `.` or `..` segment. */
    private fun plainPath(path: String) =
        '\\' !in path && ':' !in path && path.split('/').none { it.isEmpty() || it == "." || it == ".." }

    /**
     * [paths] asked with `--verbose --non-matching`: one record (source, line, pattern, path) per path, in order, until
     * git refuses a path (any exit code other than 0 or 1); that path goes to [rejected] and the lookup resumes after
     * it. A record with a source and line is a match, also when its pattern is empty (a blank line of a CRLF ignore
     * file matches a path that ends in a separator); a match whose pattern starts with `!` is negated, and is ignored
     * only when this git's plain call lists such a path ([NegatedMatch]).
     * The path of a record is git's echo of the UTF-8 sent, the name the plain call reports. The calls run with
     * `GIT_FLUSH=1`: git then writes each record out before it reads the next path, so the records before a path it
     * stops on with a crash (`BUG:`, which aborts without flushing) are not lost.
     */
    private fun resumed(
        repoDir: File,
        paths: List<String>,
        rejected: MutableList<String>,
        negated: NegatedMatch,
    ): Set<String> {
        val ignored = mutableSetOf<String>()
        val echoes = paths.map(::utf8)
        var from = 0
        while (from < paths.size) {
            val rest = paths.subList(from, paths.size)
            val args = listOf("check-ignore", "--stdin", "-z", "--verbose", "--non-matching")
            val result = run(repoDir, args, input = framed(rest), okExit = null, env = mapOf("GIT_FLUSH" to "1"))
            val refused = result.exit != 0 && result.exit != 1
            val records = result.out.split('\u0000').dropLast(1).chunked(4)
            // git answers every path it handles, NUL-terminated and as sent, and stops only at a path it refuses.
            val unexpected = (result.out.isNotEmpty() && !result.out.endsWith('\u0000')) || records.size > rest.size ||
                records.withIndex().any { (i, r) -> r.size != 4 || r[3] != echoes[from + i] } ||
                refused == (records.size == rest.size)
            if (unexpected) {
                val detail = result.err.trim().let { if (it.isEmpty()) "" else ": $it" }
                throw OkfException("git ${args.first()} gave an unexpected answer in $repoDir$detail")
            }
            records.forEach { (source, line, pattern, path) ->
                val match = source.isNotEmpty() || line.isNotEmpty()
                if (match && (!pattern.startsWith('!') || negated.listed(path))) ignored += path
            }
            if (!refused) break
            rejected += rest[records.size]
            from += records.size + 1
        }
        return ignored
    }

    /**
     * Whether this git's plain `check-ignore` lists a path whose matching pattern is negated (`!keep.log`): git before
     * 2.26 does, later git does not. The resumed lookup gives the plain call's answer, so the first such path is asked
     * once in plain mode, and only when one occurs; the answer holds for the whole [ignored] call.
     */
    private class NegatedMatch(private val repoDir: File) {
        private var answer: Boolean? = null

        fun listed(path: String): Boolean =
            answer ?: (path in checkIgnore(repoDir, listOf(path)).ignored.orEmpty()).also { answer = it }
    }

    /**
     * Answer of one plain `git check-ignore --stdin -z` call: [ignored] is `null` when git refused a path (any exit
     * code other than 0 or 1), and [error] is then the message `okf` stops with when the failure is not about a path.
     */
    private class IgnoreAnswer(val ignored: Set<String>?, val error: String)

    private fun checkIgnore(repoDir: File, paths: List<String>): IgnoreAnswer {
        val args = listOf("check-ignore", "--stdin", "-z")
        // Exit code 1 means "none of the paths is ignored", not a failure. Any other code means git refused a path:
        // 128 for a path it rejects, another code when it stops on one (a `BUG:` on unusual pathspec magic).
        val result = run(repoDir, args, input = framed(paths), okExit = null)
        if (result.exit != 0 && result.exit != 1) {
            return IgnoreAnswer(null, "git ${args.first()} failed (exit ${result.exit}) in $repoDir: ${result.err.trim()}")
        }
        return IgnoreAnswer(result.out.split('\u0000').filter { it.isNotEmpty() }.toSet(), "")
    }

    private fun framed(paths: List<String>) = paths.joinToString("\u0000", postfix = "\u0000")

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

    /**
     * Runs `git <args>` with [input] (when given) as stdin; an exit code outside [okExit] throws (with [okExit] `null`,
     * every exit code is returned). Each [env] entry is set for git and replaces an inherited variable of the same name
     * in any case (Windows reads environment names ignoring case; the JVM's map does not).
     */
    // Input and output go through temp files, not pipes: git has the whole input and stops reading when it wants (a
    // write cannot fail half-way), and a full stdout/stderr pipe cannot block it. `Files.createTempFile` makes them
    // readable by their owner only on POSIX; on Windows they take the permissions of the temp directory.
    private fun run(
        repoDir: File,
        args: List<String>,
        input: String? = null,
        okExit: Set<Int>? = setOf(0),
        env: Map<String, String> = emptyMap(),
    ): GitResult {
        val temp = mutableListOf<Path>()
        fun tempFile(suffix: String): Path = Files.createTempFile("okf-git", suffix).also { temp.add(it) }
        try {
            val (out, err, stdin) = try {
                Triple(
                    tempFile(".out").toFile(),
                    tempFile(".err").toFile(),
                    input?.let { text -> tempFile(".in").also { Files.write(it, text.toByteArray(Charsets.UTF_8)) } }?.toFile(),
                )
            } catch (e: IOException) {
                throw OkfException("cannot prepare git ${args.first()}: ${e.message}")
            }
            val process = try {
                val builder = ProcessBuilder(listOf("git") + args).directory(repoDir).redirectOutput(out).redirectError(err)
                stdin?.let { builder.redirectInput(it) }
                // Repository selection follows repoDir only; injected config, trace output and pathspec modes cannot
                // leak in, and an inherited variable that [env] sets is replaced whatever the case of its name.
                val removed = ISOLATED_ENV + env.keys
                builder.environment().keys.removeIf { name ->
                    removed.any { it.equals(name, ignoreCase = true) } || name.startsWith("GIT_TRACE", ignoreCase = true)
                }
                builder.environment().putAll(env)
                builder.start()
            } catch (e: IOException) {
                throw OkfException("cannot run git (okf needs git on the PATH): ${e.message}")
            }
            if (stdin == null) process.outputStream.close() // an empty stdin
            val exit = process.waitFor()
            val stderr = err.readText(Charsets.UTF_8)
            if (okExit != null && exit !in okExit) throw OkfException("git ${args.first()} failed (exit $exit) in $repoDir: ${stderr.trim()}")
            return GitResult(exit, out.readText(Charsets.UTF_8), stderr)
        } finally {
            temp.forEach { it.toFile().delete() }
        }
    }
}
