package com.sharpbitstudio.okf.cli

import java.io.File

/** The CLI commands; [options] are the options each one accepts. */
enum class Command(val cliName: String, val options: Set<String>) {
    INDEX("index", setOf(CliArgs.REPO)),
    CHECK("check", setOf(CliArgs.REPO, CliArgs.STRICT, CliArgs.REFS_STRICT, CliArgs.CODE_PATHS_REQUIRED)),
    IMPACT("impact", setOf(CliArgs.REPO, CliArgs.BASE, CliArgs.IMPACT_STRICT)),
    XCHECK("xcheck", setOf(CliArgs.REPO, CliArgs.WORKSPACE, CliArgs.REPORT)),
    VERSION("version", emptySet()),
    INIT("init", setOf(CliArgs.REPO, CliArgs.NAME, CliArgs.DOCS_ROOT, CliArgs.INDEX_COMMAND, CliArgs.ADOPT)),
}

/** Bad command line; the CLI prints the message and the usage and exits with [OkfCli.EXIT_USAGE]. */
class UsageException(message: String) : RuntimeException(message)

/**
 * A parsed command line: `okf <index|check|impact|xcheck|version|init> [options]`. Boolean flags are `--flag`
 * (= true) or `--flag=true|false` (strictly — a typo such as `--strict=ture` is a usage error).
 * Value options take `--opt=<value>` or `--opt <value>`
 * (the space form never takes a value starting with `--`; an empty value needs the `=` form or an empty argument).
 */
data class CliArgs(
    val command: Command,
    /** Repo root holding `okf.yaml` (`--repo`, default the working directory). */
    val repo: File,
    val strict: Boolean = false,
    val refsStrict: Boolean = false,
    val codePathsRequired: Boolean = false,
    val impactStrict: Boolean = false,
    /** `--base`: `null` when absent (the `base_ref` bases), the value as given otherwise — empty means "skip". */
    val base: String? = null,
    /** `--workspace` (required by `xcheck`): the directory holding every satellite checkout as `<workspace>/<repo>`. */
    val workspace: File? = null,
    /** `--report` (`xcheck`): where the Markdown report goes when there are findings; `null` = no report. */
    val report: File? = null,
    /** `--name` (`init`): the repository name to write as `repo`; `null` = the work-tree root's directory name. */
    val name: String? = null,
    /** `--docs-root` (`init`): the repo-relative bundle directory as given; `null` = the default `docs`. */
    val docsRoot: String? = null,
    /** `--index-command` (`init`): the command written as `index_command`, as given. */
    val indexCommand: String? = null,
    /** `--adopt` (`init`): an existing docs root holding documents becomes the bundle. */
    val adopt: Boolean = false,
) {
    companion object {
        const val REPO = "--repo"
        const val BASE = "--base"
        const val STRICT = "--strict"
        const val REFS_STRICT = "--refs-strict"
        const val CODE_PATHS_REQUIRED = "--code-paths-required"
        const val IMPACT_STRICT = "--impact-strict"
        const val WORKSPACE = "--workspace"
        const val REPORT = "--report"
        const val NAME = "--name"
        const val DOCS_ROOT = "--docs-root"
        const val INDEX_COMMAND = "--index-command"
        const val ADOPT = "--adopt"

        private val VALUE_OPTIONS = setOf(REPO, BASE, WORKSPACE, REPORT, NAME, DOCS_ROOT, INDEX_COMMAND)
        private val FLAGS = setOf(STRICT, REFS_STRICT, CODE_PATHS_REQUIRED, IMPACT_STRICT, ADOPT)

        /** Parses [args]; a relative `--repo`, `--workspace` or `--report` resolves against [cwd]. Throws [UsageException]. */
        fun parse(args: List<String>, cwd: File): CliArgs {
            var command: Command? = null
            val values = linkedMapOf<String, String>()
            var i = 0
            while (i < args.size) {
                val arg = args[i++]
                if (!arg.startsWith("--")) {
                    if (command != null) throw UsageException("unexpected argument '$arg'")
                    command = Command.entries.firstOrNull { it.cliName == arg }
                        ?: throw UsageException("unknown command '$arg' (expected one of ${Command.entries.joinToString(", ") { it.cliName }})")
                    continue
                }
                val name = arg.substringBefore('=')
                val value = when {
                    name in FLAGS -> if ('=' in arg) arg.substringAfter('=') else "true"
                    name in VALUE_OPTIONS -> when {
                        '=' in arg -> arg.substringAfter('=')
                        // The space form never takes an option as its value (`--repo --strict`).
                        i < args.size && !args[i].startsWith("--") -> args[i++]
                        else -> throw UsageException("$name requires a value ($name=<value>)")
                    }
                    else -> throw UsageException("unknown option '$name'")
                }
                if (values.put(name, value) != null) throw UsageException("$name given more than once")
            }
            val cmd = command ?: throw UsageException("no command given")
            values.keys.firstOrNull { it !in cmd.options }?.let { throw UsageException("$it does not apply to `okf ${cmd.cliName}`") }

            fun file(name: String): File? = values[name]?.let { path ->
                if (path.isEmpty()) throw UsageException("$name must not be empty")
                File(path).let { if (it.isAbsolute) it else cwd.resolve(path) }.absoluteFile.normalize()
            }
            val repo = file(REPO) ?: cwd
            val workspace = file(WORKSPACE)
            if (cmd == Command.XCHECK && workspace == null) throw UsageException("$WORKSPACE=<dir> is required for `okf ${cmd.cliName}`")
            fun flag(name: String): Boolean {
                val raw = values[name] ?: return false
                return raw.toBooleanStrictOrNull() ?: throw UsageException("$name must be 'true' or 'false', got '$raw'")
            }
            return CliArgs(
                command = cmd,
                repo = repo.absoluteFile.normalize(),
                strict = flag(STRICT),
                refsStrict = flag(REFS_STRICT),
                codePathsRequired = flag(CODE_PATHS_REQUIRED),
                impactStrict = flag(IMPACT_STRICT),
                base = values[BASE],
                workspace = workspace,
                report = file(REPORT),
                name = values[NAME],
                docsRoot = values[DOCS_ROOT],
                indexCommand = values[INDEX_COMMAND],
                adopt = flag(ADOPT),
            )
        }
    }
}
