package com.sharpbitstudio.okf.cli

import com.sharpbitstudio.okf.OkfException
import com.sharpbitstudio.okf.OkfGit
import com.sharpbitstudio.okf.OkfGithubActions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.time.Instant

/**
 * The exact console lines and annotations of `okf index`, `check`, `impact` and `version`, the usage text and every
 * command-line and setup error as printed, through [OkfCli] against a throwaway git repo (real git). Case labels
 * (`<SourceFile>#<n>`) are stable names assigned once to each message template; they are not line numbers. Text
 * passed through from git or the operating system is asserted only up to the tool's own frame.
 */
class OkfCliMessagesTest {
    @TempDir
    lateinit var home: File

    @TempDir
    lateinit var repo: File

    private lateinit var globalConfig: File

    @BeforeEach
    fun setUp() {
        globalConfig = home.resolve("gitconfig").apply { writeText("") }
        val empty = home.resolve("empty").apply { writeText("") }
        git("init", "-q", "-b", "main")
        listOf(
            "user.name" to "t",
            "user.email" to "t@t",
            "commit.gpgsign" to "false",
            "core.autocrlf" to "false",
            "core.fsmonitor" to "false",
            "core.hooksPath" to home.resolve("no-hooks").path,
            // The tool's own git calls inherit the global config: keep ignore rules and attributes (filters) repo-only.
            "core.excludesFile" to empty.path,
            "core.attributesFile" to empty.path,
        ).forEach { (key, value) -> git("config", key, value) }
        write("okf.yaml", CONFIG)
        write("docs/index.md", ROOT_INDEX)
        write("src/main/kotlin/app/App.kt", "package app\n")
        write("docs/adr/ADR-001-example.md", adr())
    }

    // --- usage and version ---

    @Test
    fun `usage text`() {
        assertEquals(
            "Usage: okf <command> [options]\n" +
                "\n" +
                "Commands:\n" +
                "  index     regenerate <docs_root>/adr/index.md and <docs_root>/features/index.md from frontmatter\n" +
                "            (each only when its directory exists; an unchanged index is not rewritten); in the hub\n" +
                "            also the Repos section of <docs_root>/index.md from okf.yaml satellites\n" +
                "  check     validate frontmatter, generated indexes and code references in doc bodies\n" +
                "  impact    report docs whose code_paths match changed code but were neither updated nor acknowledged\n" +
                "  xcheck    check the hub and its satellites together (run in the hub): cross-repo code references,\n" +
                "            hub:/repos: links, drift of hub records, satellite index URLs\n" +
                "  version   print the tool version\n" +
                "\n" +
                "Options (boolean flags: bare = true, or =true|false):\n" +
                "  --repo=<dir>                git work-tree root holding okf.yaml                           [index, check, impact, xcheck]\n" +
                "                              (default: working directory)\n" +
                "  --strict                    files without frontmatter are failures                        [check]\n" +
                "  --refs-strict               broken code references are failures                           [check]\n" +
                "  --code-paths-required       Accepted/Resolved ADRs, Feature Records and Satellite Notes    [check]\n" +
                "                              need code_paths\n" +
                "  --base=<ref>                compare against merge-base with <ref> (default\n" +
                "                              origin/<base_ref>, fallback <base_ref>; base_ref in okf.yaml\n" +
                "                              defaults to main; empty or all-zero SHA = skip)                [impact]\n" +
                "  --impact-strict             impact findings are failures                                   [impact]\n" +
                "  --workspace=<dir>           directory holding every satellite checkout as <dir>/<repo>    [xcheck, required]\n" +
                "  --report=<file>             Markdown report, written when there are findings and deleted  [xcheck]\n" +
                "                              after a clean run\n" +
                "\n" +
                "Exit codes: 0 OK, 1 findings (or the command could not complete), 2 usage or okf.yaml error\n" +
                "(xcheck: 0 also with drift warnings only; 2 also for a missing or misconfigured satellite checkout).",
            OkfCli.USAGE,
            "OkfCli#47",
        )
    }

    @Test
    fun `version line`() {
        val run = run("version")
        assertEquals(0, run.code, "OkfCli#4")
        assertEquals("okf-cli ${OkfCli.version()}\n", run.out, "OkfCli#4")
    }

    @Test
    fun `usage error is printed with the usage text`() {
        val run = run("--strict")
        assertEquals(2, run.code, "OkfCli#3")
        assertEquals("okf: no command given\n${OkfCli.USAGE}\n", run.err, "OkfCli#3")
    }

    @Test
    fun `usage errors`() {
        assertAll(
            { assertEquals("okf: unexpected argument 'extra'", usageError("check", "extra"), "CliArgs#1") },
            {
                assertEquals(
                    "okf: unknown command 'bogus' (expected one of index, check, impact, xcheck, version)",
                    usageError("bogus"),
                    "CliArgs#2",
                )
            },
            { assertEquals("okf: --repo requires a value (--repo=<value>)", usageError("check", "--repo"), "CliArgs#3") },
            { assertEquals("okf: unknown option '--bogus'", usageError("check", "--bogus"), "CliArgs#4") },
            { assertEquals("okf: --strict given more than once", usageError("check", "--strict", "--strict"), "CliArgs#5") },
            { assertEquals("okf: no command given", usageError("--strict"), "CliArgs#6") },
            { assertEquals("okf: --strict does not apply to `okf index`", usageError("index", "--strict"), "CliArgs#7") },
            { assertEquals("okf: --repo must not be empty", usageError("check", "--repo="), "CliArgs#8") },
            { assertEquals("okf: --workspace=<dir> is required for `okf xcheck`", usageError("xcheck"), "CliArgs#9") },
            { assertEquals("okf: --strict must be 'true' or 'false', got 'yes'", usageError("check", "--strict=yes"), "CliArgs#10") },
        )
    }

    // --- setup errors ---

    @Test
    fun `okf yaml error is framed with the command`() {
        write("okf.yaml", "schema_version: 2\nrepo: test-repo\n")
        val run = run("check", "--repo=${repo.path}")
        assertEquals(2, run.code, "OkfCli#6")
        assertEquals("okf check: okf.yaml: unsupported schema_version 2 — this okf-cli supports 1\n", run.err, "OkfCli#6")
    }

    @Test
    fun `docs_root is not a directory`() {
        write("okf.yaml", CONFIG + "docs_root: documentation\n")
        val run = run("check", "--repo=${repo.path}")
        assertEquals(2, run.code, "OkfCli#5")
        assertEquals("okf check: docs_root 'documentation' is not a directory in ${parsed(repo).path}\n", run.err, "OkfCli#5")
    }

    @Test
    fun `repo is not a directory`() {
        val missing = repo.resolve("nope")
        val run = run("check", "--repo=${missing.path}")
        assertEquals(2, run.code, "OkfCli#9")
        assertEquals("okf check: --repo '${parsed(missing).path}' is not a directory\n", run.err, "OkfCli#9")
    }

    @Test
    fun `repo is not inside a git work tree`() {
        assumeTrue(OkfGit.workTreeRoot(home) == null, "the temporary directory lies inside a git work tree")
        val run = run("check", "--repo=${home.path}")
        assertEquals(2, run.code, "OkfCli#10")
        assertEquals(
            "okf check: --repo must be the git work-tree root (${parsed(home).path} is not inside a git work tree)\n",
            run.err,
            "OkfCli#10",
        )
    }

    @Test
    fun `repo is below the work-tree root`() {
        val sub = repo.resolve("sub").apply { mkdirs() }
        val run = run("check", "--repo=${sub.path}")
        assertEquals(2, run.code, "OkfCli#11")
        assertEquals(
            "okf check: --repo must be the git work-tree root (${parsed(sub).path} is inside ${OkfGit.workTreeRoot(sub)!!.path})\n",
            run.err,
            "OkfCli#11",
        )
    }

    @Test
    fun `operation error is framed with the command`() {
        write("okf.yaml", HUB_CONFIG)
        repo.resolve("docs/index.md").delete()
        val run = run("index", "--repo=${repo.path}")
        assertEquals(1, run.code, "OkfCli#8")
        assertEquals("okf index: index.md (bundle root) is missing — required, must carry okf_version \"0.2\"\n", run.err, "OkfCli#8")
    }

    // --- index ---

    @Test
    fun `ADR that cannot be indexed`() {
        write("docs/adr/ADR-002-other.md", "---\ntype: ADR\nadr_status: Bogus\n---\n# ADR\n")
        val unknownStatus = run("index", "--repo=${repo.path}")
        assertEquals(1, unknownStatus.code, "OkfIndexRenderer#17")
        assertEquals("okf index: adr/ADR-002-other.md: unknown or missing adr_status 'Bogus'\n", unknownStatus.err, "OkfIndexRenderer#17")

        write("docs/adr/ADR-002-other.md", "---\ntype: ADR\nadr_status: Accepted\n# ADR\n")
        val brokenFrontmatter = run("index", "--repo=${repo.path}")
        assertEquals(1, brokenFrontmatter.code, "OkfIndexRenderer#27")
        assertEquals(
            "okf index: adr/ADR-002-other.md: frontmatter opened with '---' on line 1 but never closed\n",
            brokenFrontmatter.err,
            "OkfIndexRenderer#27",
        )
    }

    @Test
    fun `Repos section markers`() {
        write("okf.yaml", HUB_CONFIG)
        val none = run("index", "--repo=${repo.path}")
        assertEquals(1, none.code, "OkfIndexRenderer#26")
        assertEquals(
            "okf index: index.md: the Repos section needs one `<!-- okf:repos-start -->` line followed by one `<!-- okf:repos-end -->` line " +
                "(found 0 start / 0 end marker line(s))\n",
            none.err,
            "OkfIndexRenderer#26",
        )

        write("docs/index.md", ROOT_INDEX + "\n## Repos\n\n<!-- okf:repos-end -->\n<!-- okf:repos-start -->\n")
        val reversed = run("index", "--repo=${repo.path}")
        assertEquals(1, reversed.code, "OkfIndexRenderer#26")
        assertEquals(
            "okf index: index.md: the Repos section needs one `<!-- okf:repos-start -->` line followed by one `<!-- okf:repos-end -->` line " +
                "(found 1 start / 1 end marker line(s), end before start)\n",
            reversed.err,
            "OkfIndexRenderer#26",
        )
    }

    // --- git ---

    @Test
    fun `git rev-parse fails`() {
        git("config", "core.repositoryformatversion", "99")
        val run = run("check", "--repo=${repo.path}")
        val frame = "okf check: git rev-parse --show-toplevel failed (exit 128) in ${parsed(repo)}: "
        assertEquals(1, run.code, "OkfGit#1")
        assertEquals(frame, run.err.take(frame.length), "OkfGit#1")
    }

    @Test
    fun `git cannot be started`() {
        val e = assertThrows<OkfException> { OkfGit.workTreeRoot(home.resolve("nope")) }
        val frame = "cannot run git (okf needs git on the PATH): "
        assertEquals(frame, e.message.orEmpty().take(frame.length), "OkfGit#2")
    }

    @Test
    fun `git command fails`() {
        repo.resolve(".git/index").writeText("not an index")
        val run = run("check", "--repo=${repo.path}")
        val frame = "okf check: git ls-files failed (exit 128) in ${parsed(repo)}: "
        assertEquals(1, run.code, "OkfGit#3")
        assertEquals(frame, run.err.take(frame.length), "OkfGit#3")
    }

    @Test
    fun `annotation escaping`() {
        assertEquals(
            "::error file=docs/a%2Cb.md,line=3,title=OKF%3A ref%2C x::100%25 done%0D%0Anext",
            OkfGithubActions.annotation("error", "OKF: ref, x", "100% done\r\nnext", "docs/a,b.md", 3),
            "OkfGithubActions#4",
        )
    }

    // --- check ---

    @Test
    fun `check passes`() {
        run("index", "--repo=${repo.path}")
        val run = run("check", "--repo=${repo.path}")
        assertEquals(0, run.code, "OkfCli#19")
        assertEquals("okf check OK: 1 documents with frontmatter validated, 1 code references resolved.\n", run.out, "OkfCli#19")
    }

    @Test
    fun `check warnings, info lines and annotations`() {
        write("docs/guide.md", "---\ntype: Guide\nstale_after: 2026-01-01T00:00:00Z\n---\n# Guide\n\n`app/Gone.kt`\n")
        write("docs/notes.md", "# Notes\n")
        write("docs/adr/ADR-002-other.md", adr(codePaths = null))
        run("index", "--repo=${repo.path}")
        val run = run("check", "--repo=${repo.path}", githubActions = true)
        val notFound = "`app/Gone.kt`: file not found in the repo — fix the path, write a sibling-repo path as `<repo>/<path>`, " +
            "or opt the line out with `<!-- okf: ignore-refs -->`"
        val err = run.err.lines()
        val out = run.out.lines()
        assertAll(
            { assertEquals(0, run.code, run.toString()) },
            {
                assertEquals(
                    "OKF WARNING: guide.md: stale_after 2026-01-01T00:00:00Z is in the past — review and re-verify the content.",
                    err[0],
                    "OkfCli#12",
                )
            },
            { assertEquals("::warning file=docs/guide.md,line=7,title=OKF ref::$notFound", out[0], "OkfCli#13") },
            { assertEquals("OKF INFO: 1 files not yet migrated (no frontmatter); --strict makes them failures.", out[1], "OkfCli#14") },
            {
                assertEquals(
                    "OKF INFO: 1 Accepted/Resolved ADRs / Feature Records have no `code_paths`; --code-paths-required makes them failures.",
                    out[2],
                    "OkfCli#15",
                )
            },
            {
                assertEquals(
                    "okf check OK: 3 documents with frontmatter validated, 3 code references checked (1 warnings; --refs-strict fails on them).",
                    out[3],
                    "OkfCli#19",
                )
            },
        )
    }

    @Test
    fun `check failures`() {
        write("docs/guide.md", "---\ntype: Guide\n---\n# Guide\n\n`app/Gone.kt`\n")
        write("docs/notes.md", "# Notes\n")
        run("index", "--repo=${repo.path}")
        val run = run("check", "--repo=${repo.path}", "--strict", "--refs-strict")
        assertEquals(1, run.code, "OkfCli#16")
        assertEquals(
            "okf check found 2 problem(s):\n" +
                "  - notes.md: no frontmatter (strict mode: every non-reserved .md must be migrated)\n" +
                "  - guide.md:6: `app/Gone.kt`: file not found in the repo — fix the path, write a sibling-repo path as `<repo>/<path>`, " +
                "or opt the line out with `<!-- okf: ignore-refs -->`\n",
            run.err,
            "OkfCli#16",
        )
    }

    // --- impact ---

    @Test
    fun `impact skipped without a base`() {
        val run = run("impact", "--repo=${repo.path}", "--base=")
        assertEquals(0, run.code, "OkfCli#20")
        assertEquals("okf impact skipped: no base to compare against (--base='').\n", run.out, "OkfCli#20")
    }

    @Test
    fun `impact base not found`() {
        val defaults = run("impact", "--repo=${repo.path}")
        assertEquals(
            "OKF IMPACT WARNING: base 'origin/main' / 'main' not found — fetch it or pass --base=<ref> — impact check skipped.\n",
            defaults.err,
            "OkfCli#21",
        )

        val lenient = run("impact", "--repo=${repo.path}", "--base=nope", githubActions = true)
        assertAll(
            { assertEquals(0, lenient.code, "OkfCli#44") },
            {
                assertEquals(
                    "OKF IMPACT WARNING: base 'nope' not found — fetch it or pass --base=<ref> — impact check skipped.\n",
                    lenient.err,
                    "OkfCli#44",
                )
            },
            {
                assertEquals(
                    "::warning title=OKF docs impact::base 'nope' not found — fetch it or pass --base=<ref>\n",
                    lenient.out,
                    "OkfCli#45",
                )
            },
        )

        val strict = run("impact", "--repo=${repo.path}", "--base=nope", "--impact-strict")
        assertEquals(1, strict.code, "OkfCli#43")
        assertEquals("okf impact: base 'nope' not found — fetch it or pass --base=<ref>\n", strict.err, "OkfCli#43")
    }

    @Test
    fun `impact without a merge-base`() {
        commit("base")
        git("checkout", "-q", "--orphan", "unrelated")
        commit("unrelated root")
        val run = run("impact", "--repo=${repo.path}", "--base=main")
        assertEquals(
            "OKF IMPACT WARNING: no merge-base between 'main' and HEAD (shallow clone? CI needs fetch-depth: 0) — impact check skipped.\n",
            run.err,
            "OkfCli#22",
        )
    }

    @Test
    fun `impact with nothing changed`() {
        run("index", "--repo=${repo.path}")
        commit("base")
        val run = run("impact", "--repo=${repo.path}", "--base=main")
        assertEquals("okf impact: nothing changed since merge-base ${sha7("main")} with main.\n", run.out, "OkfCli#24")
    }

    @Test
    fun `impact without documents that have code_paths`() {
        write("docs/adr/ADR-001-example.md", adr(codePaths = "[]"))
        run("index", "--repo=${repo.path}")
        commit("base")
        write("src/main/kotlin/app/App.kt", "package app\n\nfun x() = 1\n")
        val run = run("impact", "--repo=${repo.path}", "--base=main")
        assertEquals(
            "okf impact: 0 documents with code_paths — nothing to match (1 changed files outside docs/ since merge-base ${sha7("main")} with main).\n",
            run.out,
            "OkfCli#25",
        )
    }

    @Test
    fun `impact findings`() {
        run("index", "--repo=${repo.path}")
        commit("base")
        val base = sha7("main")
        git("checkout", "-q", "-b", "feature/x")
        write("src/main/kotlin/app/App.kt", "package app\n\nfun x() = 1\n")
        commit("change app")
        val entry = "[NOT UPDATED] docs/adr/ADR-001-example.md <- src/main/kotlin/app/App.kt"
        val finding = "not updated although code it covers changed (src/main/kotlin/app/App.kt) — update it (a `verified.at` bump counts " +
            "once the content is re-checked) or add a commit trailer `Docs-Impact: ADR-001 — <why it stays valid>`"

        val lenient = run("impact", "--repo=${repo.path}", "--base=main")
        assertAll(
            { assertEquals(0, lenient.code, lenient.toString()) },
            {
                assertEquals(
                    "$entry\nOKF IMPACT WARNING: docs/adr/ADR-001-example.md: $finding\n" +
                        "OKF IMPACT: 1 finding(s); --impact-strict fails on them.\n",
                    lenient.err,
                    "OkfCli#30",
                )
            },
            { assertEquals("OKF IMPACT: 1 finding(s); --impact-strict fails on them.", lenient.err.lines()[2], "OkfCli#31") },
            {
                assertEquals(
                    "okf impact: 1 changed files outside docs/ since merge-base $base with main; 1 documents with code_paths, " +
                        "1 impacted (0 updated, 0 acknowledged, 1 NOT UPDATED).\n",
                    lenient.out,
                    "OkfCli#27",
                )
            },
        )

        val annotated = run("impact", "--repo=${repo.path}", "--base=main", githubActions = true)
        assertEquals("::warning file=docs/adr/ADR-001-example.md,line=1,title=OKF docs impact::$finding", annotated.out.lines()[0], "OkfCli#28")

        val strict = run("impact", "--repo=${repo.path}", "--base=main", "--impact-strict", githubActions = true)
        assertAll(
            { assertEquals(1, strict.code, strict.toString()) },
            {
                assertEquals(
                    "::error file=docs/adr/ADR-001-example.md,line=1,title=OKF docs impact::$finding\n",
                    strict.out,
                    "OkfCli#28",
                )
            },
            {
                assertEquals(
                    "$entry\nokf impact found 1 problem(s):\n  - docs/adr/ADR-001-example.md: $finding\n",
                    strict.err,
                    "OkfCli#29",
                )
            },
        )

        write("docs/adr/ADR-002-old.md", adr(changes = arrayOf("status" to "deprecated", "adr_status" to "Deprecated")))
        run("index", "--repo=${repo.path}")
        commit("deprecated ADR")
        val withDeprecated = run("impact", "--repo=${repo.path}", "--base=main")
        assertEquals(
            "okf impact: 1 changed files outside docs/ since merge-base $base with main; 1 documents with code_paths, " +
                "1 impacted (0 updated, 0 acknowledged, 1 NOT UPDATED); 1 deprecated skipped.\n",
            withDeprecated.out,
            "OkfCli#27",
        )
    }

    private data class Run(val code: Int, val out: String, val err: String)

    private fun run(vararg args: String, githubActions: Boolean = false): Run {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = OkfCli(
            PrintStream(out, true, Charsets.UTF_8),
            PrintStream(err, true, Charsets.UTF_8),
            githubActions = githubActions,
            now = { NOW },
        ).run(args.toList(), cwd = repo)
        fun text(stream: ByteArrayOutputStream) = stream.toString(Charsets.UTF_8).replace("\r\n", "\n")
        return Run(code, text(out), text(err))
    }

    /** The first stderr line of a command line that is a usage error (exit 2); the usage text follows it. */
    private fun usageError(vararg args: String): String {
        val run = run(*args)
        return if (run.code == OkfCli.EXIT_USAGE) run.err.lines().first() else run.toString()
    }

    /** [dir] as the CLI resolves a `--repo` / `--workspace` / `--report` path. */
    private fun parsed(dir: File): File = dir.absoluteFile.normalize()

    private fun sha7(ref: String) = git("rev-parse", ref).take(7)

    private fun write(path: String, text: String) {
        repo.resolve(path).apply { parentFile.mkdirs() }.writeText(text)
    }

    private fun commit(message: String) {
        git("add", "-A")
        git("commit", "-q", "-m", message)
    }

    private fun git(vararg args: String): String {
        val process = ProcessBuilder(listOf("git") + args).directory(repo).redirectErrorStream(true)
            .apply {
                environment()["GIT_CONFIG_GLOBAL"] = globalConfig.path
                environment()["GIT_CONFIG_NOSYSTEM"] = "1"
            }
            .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed: $output" }
        return output.trim()
    }

    /** An Accepted ADR with one resolvable code reference; [codePaths] `null` drops `code_paths`; each change overrides a field. */
    private fun adr(codePaths: String? = "[\"src/main/kotlin/app/**\"]", changes: Array<Pair<String, String>> = emptyArray()): String {
        val fields = linkedMapOf(
            "type" to "ADR",
            "title" to "\"Example\"",
            "description" to "\"When to read\"",
            "tags" to "[app]",
            "status" to "stable",
            "adr_status" to "Accepted",
            "adr_date" to "2026-07-01",
        )
        codePaths?.let { fields["code_paths"] = it }
        changes.forEach { (key, value) -> fields[key] = value }
        return "---\n" + fields.entries.joinToString("") { "${it.key}: ${it.value}\n" } + "---\n# ADR\n\n`app/App.kt`\n"
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-25T00:00:00Z")
        const val CONFIG = "schema_version: 1\nrepo: test-repo\nindex_command: tool/run-okf.sh index\n"
        const val HUB_CONFIG = CONFIG + "external_repos: [sat-a]\nsatellites:\n" +
            "  - repo: sat-a\n    description: Satellite A\n    index: https://example.org/sat-a/docs/index.md\n"
        const val ROOT_INDEX = "---\nokf_version: \"0.2\"\n---\n# Docs\n"
    }
}
