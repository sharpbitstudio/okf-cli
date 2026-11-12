package com.sharpbitstudio.okf.cli

import com.sharpbitstudio.okf.OkfGit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

/**
 * The exact findings, summary lines, setup errors and `--report` Markdown of `okf xcheck`, through [OkfCli] on a
 * workspace with a hub and two satellites as real git repos. Case labels (`<SourceFile>#<n>`) are stable names
 * assigned once to each message template; they are not line numbers. Text passed through from the operating system
 * is asserted only up to the tool's own frame.
 */
class OkfXcheckMessagesTest {
    @TempDir
    lateinit var home: File

    /** The workspace: `hub-repo` plus the satellites `sat-a` (docs_root `docs`) and `sat-b` (docs_root `doc`). */
    @TempDir
    lateinit var ws: File

    private lateinit var globalConfig: File
    private val hub get() = ws.resolve("hub-repo")
    private val satA get() = ws.resolve("sat-a")
    private val satB get() = ws.resolve("sat-b")
    private val report get() = home.resolve("out/report.md")

    @BeforeEach
    fun setUp() {
        globalConfig = home.resolve("gitconfig").apply { writeText("") }
        write(hub, "okf.yaml", HUB_YAML)
        write(hub, "docs/index.md", "---\nokf_version: \"0.2\"\n---\n# Docs\n\n## Repos\n\n<!-- okf:repos-start -->\n<!-- okf:repos-end -->\n")
        write(hub, "src/main/kotlin/hub/Hub.kt", "package hub\n")
        write(hub, "docs/features/example.md", record(repos = "[sat-a, sat-b]", body = "`sat-a/lib/a.kt:1-2` `other-repo/x.kt` `hub/Hub.kt`\n"))
        write(satA, "okf.yaml", "schema_version: 1\nrepo: sat-a\nhub_repo: hub-repo\nexternal_repos: [hub-repo, sat-b]\n")
        write(satA, "docs/index.md", "---\nokf_version: \"0.2\"\n---\n# A\n")
        write(satA, "lib/a.kt", "line 1\nline 2\n")
        write(satA, "docs/example/NOTE.md", note(body = SAT_A_NOTE_BODY))
        write(satB, "okf.yaml", SAT_B_YAML)
        write(satB, "doc/index.md", "---\nokf_version: \"0.2\"\n---\n# B\n")
        write(satB, "doc/NOTE.md", note())
        listOf(hub, satA, satB).forEach(::initRepo)
        assertEquals(0, run("index", "--repo=${hub.path}").code)
        listOf(hub, satA, satB).forEach { commit(it, "init", DAY_0) }
    }

    @Test
    fun `clean federation`() {
        val run = xcheck()
        assertEquals(0, run.code, run.toString())
        assertEquals(
            "okfXcheck OK: 3 repos checked, 4 cross-repo references resolved, 1 references to non-federated repos not checked, " +
                "2 Satellite Notes linked to 1 Feature Records.\n",
            run.out,
            "OkfXcheck#13",
        )

        val dirReport = home.resolve("report-dir").apply { mkdirs() }
        val undeletable = run("xcheck", "--repo=${hub.path}", "--workspace=${ws.path}", "--report=${dirReport.path}")
        assertEquals(1, undeletable.code, "OkfCli#41")
        assertEquals("okf xcheck: cannot delete report ${parsed(dirReport).path}: it is a directory\n", undeletable.err, "OkfCli#41")
    }

    @Test
    fun `failures of every check, drift, and the report`() {
        // (a) broken references into the hub and a satellite; the gitignored one is skipped.
        write(satA, "docs/example/NOTE.md", note(body = "`hub-repo/docs/gone.md`\n`sat-b/doc/index.md:99`\n`hub-repo/docs/gone2.md`\n`hub-repo/build/out.kt`\n"))
        commit(satA, "note changed", DAY_0 + 15 * DAY) // (d) 15 days after its hub record
        write(hub, ".gitignore", "build/\n")
        // (b) a malformed hub, a missing one, one that does not resolve, two that are not a Feature Record (one has no frontmatter).
        write(satB, "doc/BAD.md", note(hub = "Features/Bad"))
        write(satB, "doc/NO_HUB.md", "---\ntype: Satellite Note\ncode_paths: []\n---\n# Note\n")
        write(satB, "doc/OTHER.md", note(hub = "features/gone"))
        write(satB, "doc/GUIDE_LINK.md", note(hub = "features/guide"))
        write(hub, "docs/features/guide.md", "---\ntype: Guide\n---\n# Guide\n")
        write(satB, "doc/UNTYPED_LINK.md", note(hub = "features/untyped"))
        write(hub, "docs/features/untyped.md", "# Untyped\n")
        // (c) repos lists a satellite without a note and a non-satellite; a note whose record does not list its repo.
        write(hub, "docs/features/solo.md", record(repos = "[sat-a, other-repo]"))
        write(hub, "docs/features/unlisted.md", record(repos = null))
        write(satA, "docs/UNLISTED.md", note(hub = "features/unlisted"))
        // (e) the index URL of sat-b names docs/, its docs_root is doc/.
        write(hub, "okf.yaml", HUB_YAML.replace("sat-b/blob/main/doc/", "sat-b/blob/main/docs/"))

        val run = xcheck()
        val notFound = "— fix the path, or opt the line out with `<!-- okf: ignore-refs -->`"
        val refs = listOf(
            "sat-a/docs/example/NOTE.md:8: `hub-repo/docs/gone.md`: file not found in hub-repo $notFound",
            "sat-a/docs/example/NOTE.md:9: `sat-b/doc/index.md:99`: line 99 is past the end of doc/index.md (4 lines)",
            "sat-a/docs/example/NOTE.md:10: `hub-repo/docs/gone2.md`: file not found in hub-repo $notFound",
        )
        val badHub = "sat-b/doc/BAD.md:3: Satellite Note has no valid `hub: features/<slug>` (got 'Features/Bad')"
        val notRecord = "sat-b/doc/GUIDE_LINK.md:3: hub 'features/guide' resolves to hub-repo/docs/features/guide.md, " +
            "which is not a Feature Record (type 'Guide')"
        val noHub = "sat-b/doc/NO_HUB.md:1: Satellite Note has no valid `hub: features/<slug>` (got '<missing>')"
        val unresolved = "sat-b/doc/OTHER.md:3: hub 'features/gone' does not resolve: hub-repo/docs/features/gone.md does not exist"
        val untyped = "sat-b/doc/UNTYPED_LINK.md:3: hub 'features/untyped' resolves to hub-repo/docs/features/untyped.md, " +
            "which is not a Feature Record (type '<none>')"
        val notSatellite = "hub-repo/docs/features/solo.md:4: repos lists 'other-repo', which is not a satellite (`satellites` in hub-repo/okf.yaml)"
        val noNote = "hub-repo/docs/features/solo.md:4: repos lists 'sat-a', but sat-a has no Satellite Note with `hub: features/solo`"
        val notListed = "sat-a/docs/UNLISTED.md:3: hub record hub-repo/docs/features/unlisted.md does not list sat-a in `repos:`"
        val indexUrl = "hub-repo/okf.yaml:11: satellites[1].index 'https://example.org/sat-b/blob/main/docs/index.md' " +
            "must end with /doc/index.md (docs_root of sat-b)"
        val drift = "hub-repo/docs/features/example.md:1: hub record docs/features/example.md is 15 days older than " +
            "sat-a/docs/example/NOTE.md — distill the satellite changes into the record"
        val problems = refs + listOf(badHub, notRecord, noHub, unresolved, untyped, noNote, notSatellite, notListed, indexUrl)
        val summary = "okfXcheck: 3 repos checked, 5 cross-repo references checked (3 broken), 1 references to non-federated repos not checked, " +
            "3 Satellite Notes linked to 2 Feature Records; 12 problem(s), 1 warning(s)."
        val block = "okf xcheck found 12 problem(s):\n" + problems.joinToString("\n") { "  - $it" }
        val errLines = run.err.lines()
        assertAll(
            { assertEquals(1, run.code, run.toString()) },
            { assertEquals("OKF XCHECK WARNING: $drift\n$block\n", run.err, "OkfCli#38") },
            { assertEquals("  - $badHub", pick(errLines, "  - $badHub"), "OkfXcheck#24") },
            { assertEquals("  - $notRecord", pick(errLines, "  - $notRecord"), "OkfXcheck#25") },
            { assertEquals("  - $noHub", pick(errLines, "  - $noHub"), "OkfXcheck#24") },
            { assertEquals("  - $untyped", pick(errLines, "  - $untyped"), "OkfXcheck#25") },
            { assertEquals("  - $unresolved", pick(errLines, "  - $unresolved"), "OkfXcheck#26") },
            { assertEquals("  - $notSatellite", pick(errLines, "  - $notSatellite"), "OkfXcheck#27") },
            { assertEquals("  - $noNote", pick(errLines, "  - $noNote"), "OkfXcheck#28") },
            { assertEquals("  - $notListed", pick(errLines, "  - $notListed"), "OkfXcheck#29") },
            { assertEquals("  - $indexUrl", pick(errLines, "  - $indexUrl"), "OkfXcheck#31") },
            { assertEquals("$summary\n", run.out, "OkfXcheck#11") },
        )

        val text = report.readText()
        val lines = text.lines()
        val headings = lines.filter { it.startsWith("## ") }
        assertAll(
            {
                assertEquals(
                    "# okf xcheck: 12 problem(s), 1 warning(s)\n\n" +
                        "Cross-repo check of the OKF federation of `hub-repo` (hub + `satellites` in its okf.yaml).\n\n" +
                        "## (a) Cross-repo references\n\n" + refs.joinToString("\n") { "- $it" } + "\n\n" +
                        "## (b) `hub:` of Satellite Notes\n\n- $badHub\n- $notRecord\n- $noHub\n- $unresolved\n- $untyped\n\n" +
                        "## (c) `repos:` ↔ Satellite Notes\n\n- $noNote\n- $notSatellite\n- $notListed\n\n" +
                        "## (d) Drift between Satellite Notes and hub records (warnings)\n\n- $drift\n\n" +
                        "## (e) Satellite index URLs\n\n- $indexUrl\n\n" +
                        "---\n\n$summary\n",
                    text,
                    "OkfXcheck#32",
                )
            },
            { assertEquals("# okf xcheck: 12 problem(s), 1 warning(s)", lines[0], "OkfXcheck#33") },
            {
                assertEquals(
                    "Cross-repo check of the OKF federation of `hub-repo` (hub + `satellites` in its okf.yaml).",
                    lines[2],
                    "OkfXcheck#34",
                )
            },
            { assertEquals("---", lines[lines.size - 4], "OkfXcheck#35") },
            { assertEquals("## (a) Cross-repo references", headings[0], "OkfXcheck#3") },
            { assertEquals("## (b) `hub:` of Satellite Notes", headings[1], "OkfXcheck#4") },
            { assertEquals("## (c) `repos:` ↔ Satellite Notes", headings[2], "OkfXcheck#5") },
            { assertEquals("## (d) Drift between Satellite Notes and hub records (warnings)", headings[3], "OkfXcheck#6") },
            { assertEquals("## (e) Satellite index URLs", headings[4], "OkfXcheck#7") },
        )
    }

    @Test
    fun `drift only`() {
        write(satA, "docs/example/NOTE.md", note(body = SAT_A_NOTE_BODY + "Changed.\n"))
        commit(satA, "note changed", DAY_0 + 15 * DAY)
        val run = xcheck()
        val drift = "hub-repo/docs/features/example.md:1: hub record docs/features/example.md is 15 days older than " +
            "sat-a/docs/example/NOTE.md — distill the satellite changes into the record"
        assertAll(
            { assertEquals(0, run.code, run.toString()) },
            { assertEquals("OKF XCHECK WARNING: $drift\n", run.err, "OkfCli#37") },
            { assertEquals(drift, run.err.trim().removePrefix("OKF XCHECK WARNING: "), "OkfXcheck#30") },
            {
                assertEquals(
                    "okfXcheck OK: 3 repos checked, 4 cross-repo references resolved, 1 references to non-federated repos not checked, " +
                        "2 Satellite Notes linked to 1 Feature Records; 1 warning(s).\n",
                    run.out,
                    "OkfXcheck#12",
                )
            },
        )
    }

    @Test
    fun `several setup problems`() {
        val moved = ws.resolve("elsewhere")
        check(satB.renameTo(moved))
        write(satA, "okf.yaml", "schema_version: 1\nrepo: sat-a\nhub_repo: other-hub\nexternal_repos: [other-hub]\n")
        val run = xcheck()
        val hubMismatch = "sat-a: okf.yaml hub_repo 'other-hub' is not the hub 'hub-repo'"
        val missing = "sat-b: checkout ${workspace().resolve("sat-b").path} not found — the workspace must hold every satellite as <workspace>/sat-b"
        val text = report.readText()
        assertAll(
            { assertEquals(2, run.code, run.toString()) },
            { assertEquals("okf xcheck: 2 setup problems:\n  - $hubMismatch\n  - $missing\n", run.err, "OkfXcheck#14") },
            { assertEquals("  - $hubMismatch", run.err.lines()[1], "OkfXcheck#16") },
            { assertEquals("  - $missing", run.err.lines()[2], "OkfXcheck#18") },
            {
                assertEquals(
                    "# okf xcheck: 2 problem(s), 0 warning(s)\n\n" +
                        "Cross-repo check of the OKF federation of `hub-repo` (hub + `satellites` in its okf.yaml).\n\n" +
                        "## Setup\n\n- $hubMismatch\n- $missing\n\n" +
                        "---\n\nokfXcheck: setup failed — no check ran.\n",
                    text,
                    "OkfCli#35",
                )
            },
            { assertEquals("## Setup", text.lines()[4], "OkfXcheck#2") },
        )

        write(satA, "okf.yaml", "schema_version: 1\nrepo: sat-a\nexternal_repos: [hub-repo]\n")
        val absent = xcheck()
        assertEquals(2, absent.code, absent.toString())
        assertEquals("  - sat-a: okf.yaml hub_repo '<absent>' is not the hub 'hub-repo'", absent.err.lines()[1], "OkfXcheck#16")
    }

    @Test
    fun `satellite okf yaml names another repo, report cannot be written`() {
        write(satA, "okf.yaml", "schema_version: 1\nrepo: sat-x\nhub_repo: hub-repo\nexternal_repos: [hub-repo]\n")
        val dirReport = home.resolve("report-dir").apply { mkdirs() }
        val run = run("xcheck", "--repo=${hub.path}", "--workspace=${ws.path}", "--report=${dirReport.path}")
        assertAll(
            { assertEquals(2, run.code, run.toString()) },
            {
                assertEquals(
                    "okf xcheck: cannot write report ${parsed(dirReport).path}: it is a directory",
                    run.err.lines()[0],
                    "OkfCli#36",
                )
            },
            {
                assertEquals(
                    "okf xcheck: sat-a: okf.yaml repo 'sat-x' does not match its checkout directory 'sat-a'",
                    run.err.lines()[1],
                    "OkfXcheck#15",
                )
            },
        )
    }

    @Test
    fun `satellite docs_root is not a directory`() {
        write(satB, "okf.yaml", SAT_B_YAML.replace("docs_root: doc\n", ""))
        val run = xcheck()
        assertEquals(2, run.code, run.toString())
        assertEquals(
            "okf xcheck: sat-b: docs_root 'docs' is not a directory in ${workspace().resolve("sat-b").path}\n",
            run.err,
            "OkfXcheck#17",
        )
    }

    @Test
    fun `satellite checkout is not a git work tree`() {
        assumeTrue(OkfGit.workTreeRoot(ws) == null, "the temporary directory lies inside a git work tree")
        plainSatB()
        val run = xcheck()
        assertEquals(2, run.code, run.toString())
        assertEquals("okf xcheck: sat-b: ${workspace().resolve("sat-b").path} is not a git work tree\n", run.err, "OkfXcheck#19")
    }

    @Test
    fun `satellite checkout is inside another work tree`() {
        plainSatB()
        git(ws, "init", "-q")
        val run = xcheck()
        val satellite = workspace().resolve("sat-b")
        assertEquals(2, run.code, run.toString())
        assertEquals(
            "okf xcheck: sat-b: ${satellite.path} is not a git work-tree root (it is inside ${OkfGit.workTreeRoot(satellite)!!.path})\n",
            run.err,
            "OkfXcheck#20",
        )
    }

    @Test
    fun `shallow clone`() {
        write(satB, "doc/extra.md", "---\ntype: Guide\n---\n# Extra\n")
        commit(satB, "second", DAY_0 + DAY)
        val full = ws.resolve("sat-b-full")
        check(satB.renameTo(full))
        git(ws, "clone", "-q", "--depth", "1", "file:///" + full.absolutePath.replace('\\', '/'), "sat-b")
        val run = xcheck()
        assertEquals(1, run.code, run.toString())
        assertEquals(
            "okf xcheck found 1 problem(s):\n  - sat-b: shallow clone — xcheck needs full history: fetch-depth: 0\n",
            run.err,
            "OkfXcheck#22",
        )
    }

    @Test
    fun `xcheck outside the hub, workspace not a directory`() {
        val notHub = run("xcheck", "--repo=${satB.path}", "--workspace=${ws.path}")
        assertEquals(2, notHub.code, "OkfCli#32")
        assertEquals("okf xcheck: xcheck runs in the hub; okf.yaml has no satellites\n", notHub.err, "OkfCli#32")

        val nowhere = ws.resolve("nope")
        val noWorkspace = run("xcheck", "--repo=${hub.path}", "--workspace=${nowhere.path}")
        assertEquals(2, noWorkspace.code, "OkfCli#34")
        assertEquals("okf xcheck: --workspace '${parsed(nowhere).path}' is not a directory\n", noWorkspace.err, "OkfCli#34")
    }

    @Test
    fun `report cannot be written`() {
        write(satA, "docs/example/NOTE.md", note(body = "`hub-repo/docs/gone.md`\n"))
        val dirReport = home.resolve("report-dir").apply { mkdirs() }
        val run = run("xcheck", "--repo=${hub.path}", "--workspace=${ws.path}", "--report=${dirReport.path}")
        assertEquals(1, run.code, "OkfCli#40")
        assertEquals(
            "okf xcheck: cannot write report ${parsed(dirReport).path}: it is a directory",
            run.err.trim().lines().last(),
            "OkfCli#40",
        )
    }

    private data class Run(val code: Int, val out: String, val err: String)

    private fun xcheck() = run("xcheck", "--repo=${hub.path}", "--workspace=${ws.path}", "--report=${report.path}")

    private fun run(vararg args: String): Run {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = OkfCli(PrintStream(out, true, Charsets.UTF_8), PrintStream(err, true, Charsets.UTF_8), githubActions = false)
            .run(args.toList(), cwd = ws)
        fun text(stream: ByteArrayOutputStream) = stream.toString(Charsets.UTF_8).replace("\r\n", "\n")
        return Run(code, text(out), text(err))
    }

    /** Moves the sat-b checkout aside and puts a plain directory (no `.git`) with its `okf.yaml` in its place. */
    private fun plainSatB() {
        check(satB.renameTo(ws.resolve("sat-b-repo")))
        write(satB, "okf.yaml", SAT_B_YAML)
    }

    /** [expected] when [lines] holds it verbatim, else every line (so a mismatch shows them all). */
    private fun pick(lines: List<String>, expected: String): String = lines.firstOrNull { it == expected } ?: lines.joinToString("\n")

    /** [file] as the CLI resolves a `--workspace` / `--report` path. */
    private fun parsed(file: File): File = file.absoluteFile.normalize()

    /** The `--workspace` directory as the CLI resolves it; satellite checkouts are named below it. */
    private fun workspace(): File = parsed(ws)

    /** A hub Feature Record; `repos:` (when given) is on line 4. */
    private fun record(repos: String?, body: String = "") =
        "---\ntype: Feature Record\ntitle: \"Record\"\n" + (repos?.let { "repos: $it\n" } ?: "") + "code_paths: []\n---\n# Record\n\n$body"

    /** A Satellite Note; `hub:` is on line 3, the body starts on line 8. */
    private fun note(hub: String = "features/example", body: String = "") =
        "---\ntype: Satellite Note\nhub: $hub\ncode_paths: []\n---\n# Note\n\n$body"

    private fun write(repo: File, path: String, text: String) {
        repo.resolve(path).apply { parentFile.mkdirs() }.writeText(text)
    }

    private fun initRepo(repo: File) {
        git(repo, "init", "-q", "-b", "main")
        listOf(
            "user.name" to "t",
            "user.email" to "t@t",
            "commit.gpgsign" to "false",
            "core.autocrlf" to "false",
            "core.fsmonitor" to "false",
            "core.hooksPath" to home.resolve("no-hooks").path,
            // The tool's own git calls inherit the global config: keep ignore rules repo-only.
            "core.excludesFile" to home.resolve("empty").apply { writeText("") }.path,
        ).forEach { (key, value) -> git(repo, "config", key, value) }
    }

    /** Commits everything with author and committer time [epochSeconds]. */
    private fun commit(repo: File, message: String, epochSeconds: Long) {
        git(repo, "add", "-A")
        git(repo, "commit", "-q", "-m", message, env = mapOf("GIT_AUTHOR_DATE" to "@$epochSeconds +0000", "GIT_COMMITTER_DATE" to "@$epochSeconds +0000"))
    }

    private fun git(dir: File, vararg args: String, env: Map<String, String> = emptyMap()): String {
        val process = ProcessBuilder(listOf("git") + args).directory(dir).redirectErrorStream(true)
            .apply {
                environment()["GIT_CONFIG_GLOBAL"] = globalConfig.path
                environment()["GIT_CONFIG_NOSYSTEM"] = "1"
                environment().putAll(env)
            }
            .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed: $output" }
        return output.trim()
    }

    private companion object {
        const val DAY = 86_400L

        /** 2026-01-01T00:00:00Z. */
        const val DAY_0 = 1_767_225_600L

        /** The `index:` of sat-b is on line 11. */
        val HUB_YAML =
            """
            schema_version: 1
            repo: hub-repo
            index_command: tool/run-okf.sh index
            external_repos: [sat-a, sat-b, other-repo]
            satellites:
              - repo: sat-a
                description: Satellite A
                index: https://example.org/sat-a/blob/main/docs/index.md
              - repo: sat-b
                description: Satellite B
                index: https://example.org/sat-b/blob/main/doc/index.md
            """.trimIndent() + "\n"

        const val SAT_B_YAML = "schema_version: 1\nrepo: sat-b\ndocs_root: doc\nhub_repo: hub-repo\nexternal_repos: [hub-repo]\n"
        const val SAT_A_NOTE_BODY = "`hub-repo/docs/features/example.md` `hub-repo/hub/Hub.kt` `sat-b/doc/index.md`\n"
    }
}
