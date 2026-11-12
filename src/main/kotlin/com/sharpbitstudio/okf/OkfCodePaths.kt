package com.sharpbitstudio.okf

/** Outcome of [OkfCodePaths.validate]: messages without the document path prefix. */
data class CodePathsResult(val problems: List<String>, val warnings: List<String>)

/**
 * The `code_paths` frontmatter field: a YAML list of repo-root globs ([OkfGlob]) naming the code a
 * document describes, so [OkfImpact] can tell which documents a code change may have made stale.
 * `code_paths: []` = the document explicitly covers no code (say why in the body); no key = not yet
 * backfilled. [OkfChecker] validates the field ([validate]); Deprecated documents keep it but do not
 * take part in the impact check.
 */
object OkfCodePaths {
    const val KEY = "code_paths"

    /** More matched files than this is reported as over-broad (a warning, not a problem). */
    const val OVER_BROAD = 100

    private val WINDOWS_DRIVE = Regex("""^[A-Za-z]:""")

    /** The globs of a document — `null` when `code_paths` is absent or not a list of strings ([validate] reports that). */
    fun globs(fm: Frontmatter.Present): List<String>? {
        val raw = fm.data[KEY] as? List<*> ?: return null
        return raw.map { it as? String ?: return null }
    }

    /**
     * Validates a `code_paths` value: a list of strings, each a relative repo-root glob (no leading
     * `/` or drive letter, no `\`, no `..` segment, no leading `./`, not inside the docs bundle at
     * [bundlePath]) that compiles and appears once. With an [index], each glob must match at least
     * one file outside the bundle; more than [OVER_BROAD] matches is a warning.
     */
    fun validate(raw: Any?, index: RepoIndex?, bundlePath: String = "docs"): CodePathsResult {
        val problems = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        if (raw !is List<*>) {
            problems += "$KEY must be a YAML list of repo-root globs (e.g. $KEY: [\"src/main/java/com/example/feature/**\"]) " +
                "or [] for a document that covers no code, got '$raw'"
            return CodePathsResult(problems, warnings)
        }
        val code = index?.files?.filterNot { it == bundlePath || it.startsWith("$bundlePath/") }
        val seen = mutableSetOf<String>()
        raw.forEachIndexed { i, entry ->
            val label = "$KEY[$i]"
            if (entry !is String || entry.isBlank()) {
                problems += "$label must be a non-empty string, got '$entry'"
                return@forEachIndexed
            }
            val reason = when {
                entry.startsWith('/') || WINDOWS_DRIVE.containsMatchIn(entry) -> "must be relative to the repo root, not absolute"
                '\\' in entry -> "must use `/` as separator, not `\\`"
                entry.split('/').any { it == ".." } -> "must not contain `..` segments"
                entry.startsWith("./") -> "must not start with `./` (globs are repo-root relative already)"
                entry == bundlePath || entry.startsWith("$bundlePath/") -> "must not point into the docs bundle ($bundlePath/) — it names code"
                !seen.add(entry) -> "duplicates an earlier entry"
                else -> null
            }
            if (reason != null) {
                problems += "$label '$entry' $reason"
                return@forEachIndexed
            }
            val glob = try {
                OkfGlob.compile(entry)
            } catch (e: IllegalArgumentException) {
                problems += "$label '$entry' is not a valid glob: ${e.message}"
                return@forEachIndexed
            }
            if (code != null) {
                val matched = code.count(glob::matches)
                when {
                    matched == 0 -> problems += "$label '$entry' matches no file in the repo"
                    matched > OVER_BROAD ->
                        warnings += "$label '$entry' is over-broad ($matched files, more than $OVER_BROAD) — narrow it to the code the document describes"
                }
            }
        }
        return CodePathsResult(problems, warnings)
    }
}
