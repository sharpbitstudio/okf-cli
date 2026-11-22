package com.sharpbitstudio.okf

/**
 * GitHub Actions workflow commands: printed on stdout during a run, `::warning …::` / `::error …::`
 * lines become annotations. [annotation] writes the optional `file` (repo-relative) and `line`
 * properties: a code-reference finding of `okf check` names the document and the line of the
 * reference, an `okf impact` finding the document and line 1; an impact finding without a document and
 * the warning that the impact check was skipped (no base or merge-base) name neither. GitHub places an
 * annotation on a file only when that path matches the checkout. Shared by the OKF commands.
 */
object OkfGithubActions {
    /** Whether the tool runs under GitHub Actions. */
    fun enabled(): Boolean = System.getenv("GITHUB_ACTIONS") == "true"

    /** `::<level> file=<file>,line=<line>,title=<title>::<message>`; [file] (repo-relative) and [line] are optional. */
    fun annotation(level: String, title: String, message: String, file: String? = null, line: Int? = null): String {
        val properties = listOfNotNull(
            file?.let { "file=${escapeProperty(it)}" },
            line?.let { "line=$it" },
            "title=${escapeProperty(title)}",
        )
        return "::$level ${properties.joinToString(",")}::${escapeData(message)}"
    }

    // Workflow-command escaping (message data vs. key=value properties).
    private fun escapeData(value: String) = value.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")

    private fun escapeProperty(value: String) = escapeData(value).replace(":", "%3A").replace(",", "%2C")
}
