package okf

import java.io.File

/**
 * The repository's file tree as [OkfRefChecker] sees it, built from repo-relative `/`-separated
 * paths — in `okf check` from `git ls-files` ([OkfGit.listFiles]), in tests from a plain list,
 * so resolution is testable without git. Directories are derived from the file paths (git tracks
 * no empty directories). [sourceRoots] are the repo's extra code roots (`source_roots`, [OkfConfig]).
 */
class RepoIndex(
    paths: Collection<String>,
    private val sourceRoots: List<String> = emptyList(),
    private val lineCounter: (String) -> Int? = { null },
) {
    val files: Set<String> = paths.map { it.trim('/') }.filter { it.isNotEmpty() }.toSortedSet()
    val dirs: Set<String> = files.flatMap(::ancestors).toSortedSet()

    private val filesByBasename: Map<String, List<String>> = files.groupBy(::basename)
    private val dirsByBasename: Map<String, List<String>> = dirs.groupBy(::basename)
    private val lineCounts = mutableMapOf<String, Int?>()
    private val dirsByParent: Map<String, List<String>> = dirs.filter { '/' in it }.groupBy { it.substringBeforeLast('/') }
    private val dirsWithFiles: Set<String> = files.map { it.substringBeforeLast('/', "") }.toSet()

    /**
     * Roots a code reference may be written relative to: every `…/src/{main,test}/{java,kotlin,resources}`
     * directory, then every existing [sourceRoots] directory, plus — for code roots (not ending in
     * `/resources`) — the package directory reached by descending while a directory holds exactly one
     * subdirectory and no files (`server/src/main/java` → `…/com/example/plaza`), so
     * `otp/api/OtpService.java` resolves too.
     */
    val packageRoots: List<String> =
        (dirs.filter(SOURCE_ROOT::containsMatchIn) + sourceRoots.map { it.trim('/') }.filter(dirs::contains)).flatMap { root ->
            if (root.endsWith("/resources")) listOf(root) else listOf(root, packageRoot(root)).distinct()
        }.distinct()

    fun isFile(path: String) = path in files

    fun isDir(path: String) = path in dirs

    /** Files whose path is [suffix] or ends with `/`[suffix] (whole segments only). */
    fun filesWithSuffix(suffix: String): List<String> = withSuffix(filesByBasename, suffix)

    /** Directories whose path is [suffix] or ends with `/`[suffix] (whole segments only). */
    fun dirsWithSuffix(suffix: String): List<String> = withSuffix(dirsByBasename, suffix)

    /** Number of lines of an indexed file (`null` when it cannot be read); computed once per file. */
    fun lineCount(path: String): Int? = lineCounts.getOrPut(path) { lineCounter(path) }

    private fun withSuffix(byBasename: Map<String, List<String>>, suffix: String): List<String> =
        byBasename[basename(suffix)].orEmpty().filter { it == suffix || it.endsWith("/$suffix") }

    private fun packageRoot(root: String): String {
        var dir = root
        while (true) {
            val children = dirsByParent[dir].orEmpty()
            if (children.size != 1 || dir in dirsWithFiles) return dir
            dir = children.single()
        }
    }

    companion object {
        private val SOURCE_ROOT = Regex("""(?:^|/)src/(?:main|test)/(?:java|kotlin|resources)$""")

        /** Index of [paths] under [repoDir] that exist as files (the git index can list deleted files); lines read lazily. */
        fun fromDisk(repoDir: File, paths: Collection<String>, sourceRoots: List<String> = emptyList()): RepoIndex =
            RepoIndex(paths.filter { repoDir.resolve(it).isFile }, sourceRoots) { path ->
                try {
                    countLines(repoDir.resolve(path).readText(Charsets.UTF_8))
                } catch (_: java.io.IOException) {
                    null
                }
            }

        /** Line count as an editor shows it: a trailing newline does not start another line. */
        fun countLines(text: String): Int {
            val normalized = OkfFrontmatter.normalize(text)
            if (normalized.isEmpty()) return 0
            return normalized.count { it == '\n' } + if (normalized.endsWith('\n')) 0 else 1
        }

        private fun basename(path: String) = path.substringAfterLast('/')

        private fun ancestors(path: String): List<String> =
            generateSequence(path.substringBeforeLast('/', "")) { it.substringBeforeLast('/', "") }
                .takeWhile { it.isNotEmpty() }
                .toList()
    }
}
