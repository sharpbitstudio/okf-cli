package okf

/**
 * GitHub Actions workflow commands: printed on stdout during a run, `::warning …::` / `::error …::`
 * lines become annotations on the offending file line (and in the PR diff). Shared by the OKF commands.
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
