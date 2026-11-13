package com.sharpbitstudio.okf.cli

import com.sharpbitstudio.okf.FederatedRepo
import com.sharpbitstudio.okf.ImpactFinding
import com.sharpbitstudio.okf.ImpactState
import com.sharpbitstudio.okf.OkfChecker
import com.sharpbitstudio.okf.OkfConfig
import com.sharpbitstudio.okf.OkfConfigException
import com.sharpbitstudio.okf.OkfException
import com.sharpbitstudio.okf.OkfFrontmatter
import com.sharpbitstudio.okf.OkfGit
import com.sharpbitstudio.okf.OkfGithubActions
import com.sharpbitstudio.okf.OkfImpact
import com.sharpbitstudio.okf.OkfIndexRenderer
import com.sharpbitstudio.okf.OkfRefChecker
import com.sharpbitstudio.okf.OkfXcheck
import com.sharpbitstudio.okf.RefDoc
import com.sharpbitstudio.okf.RepoIndex
import com.sharpbitstudio.okf.XcheckSetupException
import java.io.File
import java.io.IOException
import java.io.PrintStream
import java.time.Instant
import java.util.Properties

/**
 * The `okf` command line: `index` regenerates the bundle's sub-indexes, `check` validates the bundle
 * ([OkfChecker] + [OkfRefChecker]), `impact` reports docs made stale by code changes ([OkfImpact]),
 * `xcheck` checks the hub and its satellites together ([OkfXcheck]).
 * Console lines and GitHub Actions annotations are part of the output contract: info lines
 * and annotations on [out], warnings and failures on [err]. Exit codes: [EXIT_OK], [EXIT_FINDINGS]
 * (findings that fail under the given strictness, or an operation that cannot complete — broken
 * frontmatter for the index, git not runnable), [EXIT_USAGE] (bad command line or `okf.yaml`).
 */
class OkfCli(
    private val out: PrintStream,
    private val err: PrintStream,
    /** Whether workflow-command annotations are printed ([OkfGithubActions.enabled] in production). */
    private val githubActions: Boolean = OkfGithubActions.enabled(),
    private val now: () -> Instant = Instant::now,
) {
    /** Runs one command line; returns the process exit code. */
    fun run(args: List<String>, cwd: File = File("").absoluteFile): Int {
        if (args.isEmpty()) {
            err.println(USAGE)
            return EXIT_USAGE
        }
        // `--help` / `-h` anywhere (`okf check --help`), `help` as the command.
        if (args.first() == "help" || args.any { it in HELP_FLAGS }) {
            out.println(USAGE)
            return EXIT_OK
        }
        val parsed = try {
            CliArgs.parse(args, cwd)
        } catch (e: UsageException) {
            err.println("okf: ${e.message}")
            err.println(USAGE)
            return EXIT_USAGE
        }
        if (parsed.command == Command.VERSION) {
            out.println("okf-cli ${version()}")
            return EXIT_OK
        }
        return try {
            requireWorkTreeRoot(parsed.repo)
            val config = OkfConfig.load(parsed.repo)
            val bundle = config.bundleDir(parsed.repo)
            if (!bundle.isDirectory) throw OkfConfigException("docs_root '${config.docsRoot}' is not a directory in ${parsed.repo.path}")
            when (parsed.command) {
                Command.INDEX -> index(bundle, config)
                Command.CHECK -> check(parsed, bundle, config)
                Command.IMPACT -> impact(parsed, bundle, config)
                Command.XCHECK -> xcheck(parsed, config)
                Command.VERSION -> error("handled above")
            }
        } catch (e: OkfConfigException) {
            err.println("okf ${parsed.command.cliName}: ${e.message}")
            EXIT_USAGE
        } catch (e: Failure) {
            err.println(e.message)
            EXIT_FINDINGS
        } catch (e: OkfException) {
            err.println("okf ${parsed.command.cliName}: ${e.message}")
            EXIT_FINDINGS
        }
    }

    /**
     * `--repo` must be the git work-tree root: repo-relative paths (`git ls-files`, `code_paths`, refs) are
     * computed against it. Compared as canonical files, so drive-letter case, `/` vs `\` and Windows short
     * names do not matter.
     */
    private fun requireWorkTreeRoot(repo: File) {
        if (!repo.isDirectory) throw OkfConfigException("${CliArgs.REPO} '${repo.path}' is not a directory")
        val root = OkfGit.workTreeRoot(repo)
            ?: throw OkfConfigException("${CliArgs.REPO} must be the git work-tree root (${repo.path} is not inside a git work tree)")
        if (root.canonicalFile != repo.canonicalFile) {
            throw OkfConfigException("${CliArgs.REPO} must be the git work-tree root (${repo.path} is inside ${root.path})")
        }
    }

    /** Fails the command with [message] on [err]. */
    private class Failure(message: String) : RuntimeException(message)

    /**
     * Regenerates the sub-index of each existing sub-directory (`adr/`, `features/`); a missing one is skipped.
     * In the hub (`satellites`), also the "Repos" section of the bundle-root index.md.
     */
    private fun index(bundle: File, config: OkfConfig): Int {
        val renderers = listOf<Pair<String, (File, String) -> String>>(
            OkfIndexRenderer.ADR_DIR to { dir, command -> OkfIndexRenderer.renderAdrIndex(dir, command, config.indexLanguage, config.adrReadmeLink) },
            OkfIndexRenderer.FEATURES_DIR to { dir, command -> OkfIndexRenderer.renderFeaturesIndex(dir, command, config.indexLanguage) },
        )
        // Render all before writing any, so broken frontmatter leaves the indexes untouched.
        val rendered = renderers.map { (dir, render) -> bundle.resolve(dir) to render }
            .filter { (dir, _) -> dir.isDirectory }
            .map { (dir, render) -> dir.resolve("index.md") to render(dir, config.indexCommand) }
        val rootIndex = bundle.resolve("index.md")
        val repos = if (config.satellites.isEmpty()) {
            null
        } else {
            if (!rootIndex.isFile) throw OkfException(OkfChecker.MISSING_ROOT_INDEX)
            val text = OkfFrontmatter.normalize(rootIndex.readText(Charsets.UTF_8))
            OkfIndexRenderer.replaceReposBlock(text, OkfIndexRenderer.renderReposBlock(config.satellites, config.indexCommand))
        }
        rendered.forEach { (file, text) -> OkfIndexRenderer.write(file, text) }
        repos?.let { OkfIndexRenderer.write(rootIndex, it, keepBom = true) }
        return EXIT_OK
    }

    private fun check(args: CliArgs, bundle: File, config: OkfConfig): Int {
        val repo = args.repo
        val index = RepoIndex.fromDisk(repo, OkfGit.listFiles(repo), config.sourceRoots)
        val refs = OkfRefChecker(index, bundle.relativeTo(repo).invariantSeparatorsPath, config.externalRepos, config.refExtensions) {
            OkfGit.ignored(repo, it)
        }
        val result = OkfChecker.check(
            bundle,
            config,
            args.strict,
            now = now(),
            refs = refs,
            refsStrict = args.refsStrict,
            codePathsRequired = args.codePathsRequired,
        )
        result.warnings.forEach { err.println("OKF WARNING: $it") }
        // Workflow commands on stdout become PR annotations on the offending doc line.
        if (githubActions) {
            val level = if (args.refsStrict) "error" else "warning"
            result.refFindings.forEach {
                val file = "${refs.bundlePath}/${it.doc}".removePrefix("/")
                out.println(OkfGithubActions.annotation(level, "OKF ref", it.message, file, it.line))
            }
        }
        if (!args.strict && result.unmigrated.isNotEmpty()) {
            out.println(
                "OKF INFO: ${result.unmigrated.size} files not yet migrated (no frontmatter); ${CliArgs.STRICT} makes them failures.",
            )
        }
        if (!args.codePathsRequired && result.codePathsMissing.isNotEmpty()) {
            val kinds = result.codePathsMissingByKind.keys
            // ADRs and Feature Records are named together whichever is missing, so without a Satellite Note
            // the line is the same whichever of the two is missing.
            val named = listOfNotNull(
                "${OkfChecker.CODE_PATHS_ADRS} / ${OkfChecker.CODE_PATHS_FEATURE_RECORDS}"
                    .takeIf { kinds.any { it != OkfChecker.CODE_PATHS_SATELLITE_NOTES } },
                OkfChecker.CODE_PATHS_SATELLITE_NOTES.takeIf { it in kinds },
            ).joinToString(" / ")
            out.println(
                "OKF INFO: ${result.codePathsMissing.size} $named have no `code_paths`; ${CliArgs.CODE_PATHS_REQUIRED} makes them failures.",
            )
        }
        if (result.problems.isNotEmpty()) {
            throw Failure("okfCheck found ${result.problems.size} problem(s):\n" + result.problems.joinToString("\n") { "  - $it" })
        }
        val refSummary = when {
            result.refFindings.isEmpty() -> ", ${result.refsChecked} code references resolved"
            else ->
                ", ${result.refsChecked} code references checked " +
                    "(${result.refFindings.size} warnings; ${CliArgs.REFS_STRICT} fails on them)"
        }
        out.println("okfCheck OK: ${result.validated} documents with frontmatter validated$refSummary.")
        return EXIT_OK
    }

    /**
     * Lists the documents whose `code_paths` match code changed since the merge-base with `--base` (absent:
     * `origin/<base_ref>`, fallback `<base_ref>`, [OkfConfig.baseRef]), and whether each was updated or
     * acknowledged by a `Docs-Impact:` trailer ([OkfImpact] has the coverage rules). Changed =
     * `git diff <merge-base>` (work tree, so uncommitted edits count locally) plus untracked files; trailers
     * come from the commits in `<merge-base>..HEAD`.
     */
    private fun impact(args: CliArgs, bundle: File, config: OkfConfig): Int {
        val repo = args.repo
        val bundlePath = bundle.relativeTo(repo).invariantSeparatorsPath
        val strict = args.impactStrict

        val requested = args.base
        if (requested != null && (requested.isBlank() || ZERO_SHA.matches(requested.trim()))) {
            out.println("okfImpact skipped: no base to compare against (${CliArgs.BASE}='$requested').")
            return EXIT_OK
        }
        val candidates = if (requested == null) listOf("origin/${config.baseRef}", config.baseRef) else listOf(requested.trim())
        val baseRef = candidates.firstOrNull { OkfGit.resolveCommit(repo, it) != null }
            ?: return impactUnavailable(strict, "base ${candidates.joinToString(" / ") { "'$it'" }} not found — fetch it or pass ${CliArgs.BASE}=<ref>")
        val mergeBase = OkfGit.mergeBase(repo, baseRef)
            ?: return impactUnavailable(strict, "no merge-base between '$baseRef' and HEAD (shallow clone? CI needs fetch-depth: 0)")

        val docs = bundle.walkTopDown()
            .filter { it.isFile && it.extension == "md" && it.name !in OkfFrontmatter.RESERVED }
            .map { RefDoc(it.relativeTo(bundle).invariantSeparatorsPath, it.readText(Charsets.UTF_8)) }
            .toList()
        val changed = OkfGit.changedFiles(repo, mergeBase)
        val commits = OkfGit.commits(repo, "$mergeBase..HEAD")
        // A trailer or doc update at commit K covers what K could see (every toucher of a file K or an
        // ancestor of K); the work tree and untracked files are not covered (except by a doc update there).
        val uncommitted = OkfGit.changedFiles(repo, "HEAD")
        val touchers = mutableMapOf<String, List<String>>()
        val ancestry = mutableMapOf<Pair<String, String>, Boolean>()
        val result = OkfImpact.evaluate(
            docs,
            bundlePath,
            changed,
            commits,
            uncommitted,
            touchers = { path -> touchers.getOrPut(path) { OkfGit.commitsTouching(repo, "$mergeBase..HEAD", path) } },
            isAncestorOrSelf = { a, b -> a == b || ancestry.getOrPut(a to b) { OkfGit.isAncestor(repo, a, b) } },
        )

        val against = "merge-base ${mergeBase.take(7)} with ${if (FULL_SHA.matches(baseRef)) baseRef.take(7) else baseRef}"
        result.entries.forEach { if (it.state == ImpactState.NOT_UPDATED) err.println(it.line()) else out.println(it.line()) }
        reportImpact(strict, result.findings)
        out.println(
            when {
                changed.isEmpty() && commits.isEmpty() -> "okfImpact: nothing changed since $against."
                result.documents == 0 ->
                    "okfImpact: 0 documents with code_paths — nothing to match (${result.changedCode.size} changed files outside $bundlePath/ since $against)."
                else -> {
                    val counts = ImpactState.entries.joinToString(", ") { state -> "${result.entries.count { it.state == state }} ${state.label}" }
                    "okfImpact: ${result.changedCode.size} changed files outside $bundlePath/ since $against; " +
                        "${result.documents} documents with code_paths, ${result.entries.size} impacted ($counts)" +
                        if (result.deprecatedSkipped > 0) "; ${result.deprecatedSkipped} deprecated skipped." else "."
                }
            },
        )
        return EXIT_OK
    }

    private fun reportImpact(strict: Boolean, findings: List<ImpactFinding>) {
        if (findings.isEmpty()) return
        val level = if (strict) "error" else "warning"
        if (githubActions) {
            findings.forEach { out.println(OkfGithubActions.annotation(level, IMPACT_TITLE, it.message, it.doc, it.doc?.let { 1 })) }
        }
        if (strict) {
            throw Failure("okfImpact found ${findings.size} problem(s):\n" + findings.joinToString("\n") { "  - $it" })
        }
        // Under GitHub Actions the annotations carry the doc findings; locally the hint (trailer / update) must be in the log.
        findings.filter { it.doc == null || !githubActions }.forEach { err.println("OKF IMPACT WARNING: $it") }
        err.println("OKF IMPACT: ${findings.size} finding(s); ${CliArgs.IMPACT_STRICT} fails on them.")
    }

    /**
     * Checks the federation — this hub and every satellite under `--workspace` ([OkfXcheck]). Warnings go to [err] one
     * per line, failures as one block, the summary line to [out]. `--report` gets the Markdown report when there is
     * anything to report (setup problems included) and is deleted after a clean run, so a CI step can test for it.
     */
    private fun xcheck(args: CliArgs, config: OkfConfig): Int {
        if (config.satellites.isEmpty()) throw OkfConfigException("xcheck runs in the hub; okf.yaml has no satellites")
        val workspace = checkNotNull(args.workspace) { "parse requires ${CliArgs.WORKSPACE} for xcheck" }
        if (!workspace.isDirectory) throw OkfConfigException("${CliArgs.WORKSPACE} '${workspace.path}' is not a directory")
        val satellites = try {
            OkfXcheck.loadSatellites(workspace, config)
        } catch (e: XcheckSetupException) {
            // A report that cannot be written is reported, but the setup error decides the exit code.
            args.report?.let { report ->
                try {
                    writeReport(report, OkfXcheck.report(config.repo, e.findings, emptyList(), "okfXcheck: setup failed — no check ran."))
                } catch (reportError: OkfException) {
                    err.println("okf ${Command.XCHECK.cliName}: ${reportError.message}")
                }
            }
            throw OkfConfigException(e.message.orEmpty())
        }
        val result = OkfXcheck.check(FederatedRepo(args.repo, config), satellites)
        result.warnings.forEach { err.println("OKF XCHECK WARNING: $it") }
        if (result.problems.isNotEmpty()) {
            err.println("okf xcheck found ${result.problems.size} problem(s):\n" + result.problems.joinToString("\n") { "  - $it" })
        }
        out.println(result.summary())
        args.report?.let { report ->
            if (result.problems.isEmpty() && result.warnings.isEmpty()) {
                deleteReport(report)
            } else {
                writeReport(report, OkfXcheck.report(config.repo, result.problems, result.warnings, result.summary()))
            }
        }
        return if (result.problems.isEmpty()) EXIT_OK else EXIT_FINDINGS
    }

    /** Writes the `xcheck` report; a failure is an [OkfException] (`cannot write report …`), not a stack trace. */
    private fun writeReport(file: File, text: String) {
        try {
            if (file.isDirectory) throw IOException("it is a directory")
            file.absoluteFile.parentFile?.mkdirs()
            file.writeText(text, Charsets.UTF_8)
        } catch (e: IOException) {
            throw OkfException("cannot write report ${file.path}: ${e.message}")
        }
    }

    /** Removes a report left by an earlier run, so a clean run leaves no file at that path. */
    private fun deleteReport(file: File) {
        when {
            file.isDirectory -> throw OkfException("cannot delete report ${file.path}: it is a directory")
            file.exists() && !file.delete() -> throw OkfException("cannot delete report ${file.path}")
        }
    }

    /** The impact check cannot run (no base / merge-base): a failure under `--impact-strict`, else a skipped-check warning. */
    private fun impactUnavailable(strict: Boolean, message: String): Int {
        if (strict) throw Failure("okfImpact: $message")
        err.println("OKF IMPACT WARNING: $message — impact check skipped.")
        if (githubActions) out.println(OkfGithubActions.annotation("warning", IMPACT_TITLE, message))
        return EXIT_OK
    }

    companion object {
        const val EXIT_OK = 0
        const val EXIT_FINDINGS = 1
        const val EXIT_USAGE = 2

        private const val IMPACT_TITLE = "OKF docs impact"
        private val HELP_FLAGS = setOf("--help", "-h")
        private val ZERO_SHA = Regex("0+")
        private val FULL_SHA = Regex("[0-9a-f]{40}", RegexOption.IGNORE_CASE)

        val USAGE =
            """
            |Usage: okf <command> [options]
            |
            |Commands:
            |  index     regenerate <docs_root>/adr/index.md and <docs_root>/features/index.md from frontmatter
            |            (each only when its directory exists; an unchanged index is not rewritten); in the hub
            |            also the Repos section of <docs_root>/index.md from okf.yaml satellites
            |  check     validate frontmatter, generated indexes and code references in doc bodies
            |  impact    report docs whose code_paths match changed code but were neither updated nor acknowledged
            |  xcheck    check the hub and its satellites together (run in the hub): cross-repo code references,
            |            hub:/repos: links, drift of hub records, satellite index URLs
            |  version   print the tool version
            |
            |Options (boolean flags: bare = true, or =true|false):
            |  --repo=<dir>                git work-tree root holding okf.yaml                           [index, check, impact, xcheck]
            |                              (default: working directory)
            |  --strict                    files without frontmatter are failures                        [check]
            |  --refs-strict               broken code references are failures                           [check]
            |  --code-paths-required       Accepted/Resolved ADRs, Feature Records and Satellite Notes    [check]
            |                              need code_paths
            |  --base=<ref>                compare against merge-base with <ref> (default
            |                              origin/<base_ref>, fallback <base_ref>; base_ref in okf.yaml
            |                              defaults to main; empty or all-zero SHA = skip)                [impact]
            |  --impact-strict             impact findings are failures                                   [impact]
            |  --workspace=<dir>           directory holding every satellite checkout as <dir>/<repo>    [xcheck, required]
            |  --report=<file>             Markdown report, written when there are findings and deleted  [xcheck]
            |                              after a clean run
            |
            |Exit codes: 0 OK, 1 findings (or the command could not complete), 2 usage or okf.yaml error
            |(xcheck: 0 also with drift warnings only; 2 also for a missing or misconfigured satellite checkout).
            """.trimMargin()

        /** The version the jar was built with (`com/sharpbitstudio/okf/version.properties`, expanded at build time). */
        fun version(): String {
            val properties = Properties()
            OkfCli::class.java.getResourceAsStream("/com/sharpbitstudio/okf/version.properties")?.use(properties::load)
            return properties.getProperty("version")?.takeUnless { it.startsWith("$") } ?: "unknown"
        }
    }
}
