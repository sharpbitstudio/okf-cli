package com.sharpbitstudio.okf.cli

import com.sharpbitstudio.okf.Frontmatter
import com.sharpbitstudio.okf.OkfConfig
import com.sharpbitstudio.okf.OkfFrontmatter
import com.sharpbitstudio.okf.OkfGit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

/** `okf init` end to end through [OkfCli] in a throwaway git work tree (real git, like [OkfCliTest]). */
class OkfInitTest {
    @TempDir
    lateinit var home: File

    @TempDir
    lateinit var repo: File

    private lateinit var globalConfig: File

    @BeforeEach
    fun setUp() {
        globalConfig = home.resolve("gitconfig").apply { writeText("") }
        gitInit(repo)
    }

    // --- a repository without documentation ---

    @Test
    fun `empty repo - writes okf yaml and the bundle root, and the strict check passes without a commit`() {
        val run = run("init", "--repo=${repo.path}", "--index-command=java -jar okf.jar index")
        assertEquals(Run(0, "[created] okf.yaml\n[created] docs/index.md\nokf init OK: 2 file(s) created.\n", ""), run)
        assertEquals(
            "# OKF (Open Knowledge Format) settings of this repository: https://github.com/sharpbitstudio/okf-cli/blob/main/docs/reference.md\n" +
                "schema_version: 1\n" +
                "repo: ${repo.canonicalFile.name}\n" +
                "index_language: en\n" +
                "index_command: java -jar okf.jar index\n" +
                "# ref_extensions: [ts, tsx]    # file extensions (no dot) that code references in the docs may end with, besides the built-in ones\n" +
                "# source_roots: [app/src]      # extra repo-relative code roots\n",
            read("okf.yaml"),
        )
        assertEquals(
            "---\n" +
                "okf_version: \"0.2\"\n" +
                "---\n" +
                "# Documentation\n" +
                "\n" +
                "This directory is an [OKF](https://github.com/GoogleCloudPlatform/open-knowledge-format) bundle, checked by\n" +
                "[okf-cli](https://github.com/sharpbitstudio/okf-cli).\n",
            read("docs/index.md"),
        )
        assertStrictCheckPasses()
    }

    @Test
    fun `the files are UTF-8 with LF line ends and no BOM`() {
        assertEquals(0, init().code)
        for (path in listOf("okf.yaml", "docs/index.md")) {
            val bytes = repo.resolve(path).readBytes()
            assertFalse('\r'.code.toByte() in bytes, path)
            assertFalse(bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte(), path)
            assertEquals(String(bytes, Charsets.UTF_8), read(path), path)
        }
    }

    @Test
    fun `nested docs root - docs_root is written and the parent directories are created`() {
        val run = run("init", "--docs-root=handbook/docs", "--index-command", "okf index")
        assertEquals(Run(0, "[created] okf.yaml\n[created] handbook/docs/index.md\nokf init OK: 2 file(s) created.\n", ""), run)
        assertEquals(config(repo.canonicalFile.name, docsRoot = "handbook/docs"), read("okf.yaml"))
        assertTrue(repo.resolve("handbook/docs/index.md").isFile)
        assertStrictCheckPasses()
    }

    @Test
    fun `a docs root without Markdown files and without adr or features is no conflict`() {
        write("docs/images/logo.png", "png")
        val run = init()
        assertEquals(Run(0, "[created] okf.yaml\n[created] docs/index.md\nokf init OK: 2 file(s) created.\n", ""), run)
        assertEquals("png", read("docs/images/logo.png"))
        assertStrictCheckPasses()
    }

    // --- name ---

    @Test
    fun `name - --name is written as repo`() {
        assertEquals(0, init("--name=my-repo.v2_x").code)
        assertEquals(config("my-repo.v2_x"), read("okf.yaml"))
    }

    @Test
    fun `name - YAML-typed names are written quoted and read back equal`() {
        for (name in listOf("123", "true", "null", "1.5", ".inf", "0x1F")) {
            reset()
            val run = init("--name=$name")
            assertEquals(0, run.code, "$name: $run")
            assertEquals(config("\"$name\""), read("okf.yaml"), name)
            assertEquals(name, OkfConfig.load(repo).repo, name)
        }
    }

    @Test
    fun `name - an invalid --name is a usage-class error and writes nothing`() {
        val before = snapshot()
        for (name in listOf("a/b", "my repo", "..", ".", "", "a #b", "x:y")) {
            val run = init("--name=$name")
            assertEquals(
                Run(2, "", "okf init: --name '$name' must be a repository name (letters, digits, '.', '_' or '-')\n"),
                run,
                name,
            )
        }
        assertEquals(before, snapshot())
    }

    @Test
    fun `name - a directory name that is no repository name needs --name`() {
        val dir = home.resolve("my repo").apply { mkdirs() }
        gitInit(dir)
        val run = run("init", "--repo=${dir.path}", INDEX_COMMAND)
        assertEquals(
            Run(2, "", "okf init: the directory name 'my repo' cannot be the repository name (letters, digits, '.', '_' or '-') — pass --name=<repo>\n"),
            run,
        )
        assertEquals(listOf(".git"), dir.list()!!.toList())
        assertEquals(0, run("init", "--repo=${dir.path}", "--name=my-repo", INDEX_COMMAND).code)
        assertTrue(dir.resolve("docs/index.md").isFile)
    }

    // --- docs root ---

    @Test
    fun `docs root - invalid per the docs_root rules`() {
        val before = snapshot()
        val cases = listOf(
            "/abs" to "okf.yaml: docs_root '/abs' must be relative to the repo root, not absolute",
            "../up" to "okf.yaml: docs_root '../up' must not contain `..` segments",
            "a\\b" to "okf.yaml: docs_root 'a\\b' must use `/` as separator, not `\\`",
            "./docs" to "okf.yaml: docs_root './docs' must not contain `.` segments (write the path from the repo root, without `./`)",
            "docs/" to "okf.yaml: docs_root 'docs/' must not contain empty segments (no `//`, no trailing `/`)",
            "." to "okf.yaml: docs_root '.' must name a directory below the repo root",
            "a: b" to "okf.yaml: docs_root 'a: b' must be relative to the repo root, not absolute",
            "" to "okf.yaml: `docs_root` must be a non-empty string, got ''",
            " docs" to "--docs-root ' docs' cannot be written to okf.yaml as given",
        )
        assertAll(
            cases.map { (value, message) ->
                { assertEquals(Run(2, "", "okf init: $message\n"), init("--docs-root=$value"), value) }
            },
        )
        assertEquals(before, snapshot())
    }

    @Test
    fun `docs root - control characters and segments ending in a dot or a blank`() {
        val before = snapshot()
        val cases = listOf(
            "a\nb" to "a${BACKSLASH}u000ab",
            "a\tb" to "a${BACKSLASH}u0009b",
            "a${char(0x2028)}b" to "a${BACKSLASH}u2028b",
            "docs." to "docs.",
            "docs/sub." to "docs/sub.",
            "a /b" to "a /b",
            "docs " to "docs ",
            ".Git./docs" to ".Git./docs",
        )
        assertAll(
            cases.map { (value, shown) ->
                {
                    assertEquals(
                        Run(2, "", "okf init: --docs-root '$shown' must not contain control characters or a segment ending in '.' or a blank\n"),
                        init("--docs-root=$value"),
                        shown,
                    )
                }
            },
        )
        assertEquals(before, snapshot())
    }

    @Test
    fun `docs root - colliding with okf yaml or git`() {
        val before = snapshot()
        val cases = listOf(
            "okf.yaml" to "okf.yaml",
            "okf.yaml/docs" to "okf.yaml",
            "OKF.YAML/docs" to "okf.yaml",
            ".git" to ".git",
            ".git/docs" to ".git",
            ".GIT/docs" to ".git",
        )
        assertAll(
            cases.map { (value, path) ->
                { assertEquals(Run(2, "", "okf init: --docs-root '$value' collides with $path\n"), init("--docs-root=$value"), value) }
            },
        )
        assertEquals(before, snapshot())
    }

    @Test
    fun `docs root - YAML-special values are quoted and read back equal`() {
        val cases = listOf("docs #1", "&docs", "!docs", "%docs", "@docs", "123", "true", "null", "docs/sub dir")
        for (value in cases) {
            reset()
            val run = init("--docs-root=$value")
            assertEquals(Run(0, "[created] okf.yaml\n[created] $value/index.md\nokf init OK: 2 file(s) created.\n", ""), run, value)
            val written = if (value == "docs/sub dir") value else "\"$value\""
            assertEquals(config(repo.canonicalFile.name, docsRoot = written), read("okf.yaml"), value)
            assertEquals(value, OkfConfig.load(repo).docsRoot, value)
            assertTrue(repo.resolve("$value/index.md").isFile, value)
        }
    }

    // --- index command ---

    @Test
    fun `index command - required in this version`() {
        val before = snapshot()
        assertEquals(
            Run(2, "", "okf init: --index-command=<command> is required: the one-line command that regenerates the indexes in this repository\n"),
            run("init"),
        )
        assertEquals(before, snapshot())
    }

    @Test
    fun `index command - rejected by the okf yaml rules`() {
        val before = snapshot()
        val newline = "okf\nindex"
        val cases = listOf(
            "run `okf`" to "okf.yaml: index_command 'run `okf`' must not contain a backtick (it is written into inline code)",
            "a --> b" to "okf.yaml: index_command 'a --> b' must not contain `-->` (it is written into an HTML comment)",
            newline to "okf.yaml: index_command 'okf${BACKSLASH}u000aindex' must not contain control characters (newline, tab, …)",
            "" to "okf.yaml: `index_command` must be a non-empty string, got ''",
            " okf index" to "--index-command ' okf index' cannot be written to okf.yaml as given",
            "okf index " to "--index-command 'okf index ' cannot be written to okf.yaml as given",
        )
        assertAll(
            cases.map { (value, message) ->
                { assertEquals(Run(2, "", "okf init: $message\n"), init("--index-command=$value"), value) }
            },
        )
        assertEquals(before, snapshot())
    }

    @Test
    fun `index command - YAML-special values are quoted and read back equal`() {
        val plain = listOf("okf index", "./gradlew okfIndex", "java -jar tools/okf.jar index --repo=.")
        val quoted = listOf(
            "okf index #x", "okf: index", "*okf index", "&okf index", "!okf index", "|okf index", ">okf index", "%okf index",
            "@okf index", "123", "true", "null", "'okf index'", "\"okf index\"", "okf \\ index", "- okf index", "[okf]", "{okf}",
        )
        for (value in plain + quoted) {
            reset()
            val run = run("init", "--index-command=$value")
            assertEquals(0, run.code, "$value: $run")
            val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
            val written = if (value in plain) value else "\"$escaped\""
            assertEquals(config(repo.canonicalFile.name, indexCommand = written), read("okf.yaml"), value)
            assertEquals(value, OkfConfig.load(repo).indexCommand, value)
        }
    }

    @Test
    fun `round trip - every vector reads back from the written okf yaml as given`() {
        val vectors = listOf(
            " #", "a #b", ": ", "a: b", "*a", "&a", "!a", "|", ">", "%a", "@a", "a\nb", "a\tb", "a\rb", " a", "a ", "", "123", "true",
            "null", "~", "-", "- a", "? a", "a\\b", "\"a\"", "'a'", "[a]", "{a}", "a,b", "`a`", "#a", "2026-01-01", "1e3", "yes", "off",
            "<<", "=", "é", "a${CONTROL}b",
            // NEL, line and paragraph separators, BOM, DEL, a non-character, a character outside the BMP.
            "a${char(0x85)}b", "a${char(0x2028)}b", "a${char(0x2029)}b", "a${char(0xFEFF)}b", "a${char(0x7F)}b", "a${char(0xFFFE)}b",
            "a${String(Character.toChars(0x1F600))}b",
        )
        for (value in vectors) {
            val text = OkfInit.renderConfig("name", value, value)
            // Read as okf.yaml is read (dates stay text), without the trimming and checks of OkfConfig.
            val data = (OkfFrontmatter.parse("---\n$text---\n") as Frontmatter.Present).data
            assertEquals(value, data["docs_root"], "docs_root <$value> in\n$text")
            assertEquals(value, data["index_command"], "index_command <$value> in\n$text")
            assertFalse('\r' in text, value)
        }
        assertEquals("\"a\\nb\"", OkfInit.scalar("a\nb"))
        assertEquals("\"a\\tb\\rc\\x01d\"", OkfInit.scalar("a\tb\rc${CONTROL}d"))
        assertEquals("\"a\\\\b \\\"c\\\"\"", OkfInit.scalar("a\\b \"c\""))
        assertEquals("\"a${BACKSLASH}u2028b\"", OkfInit.scalar("a${char(0x2028)}b"))
        assertEquals("\"a${BACKSLASH}x85b\"", OkfInit.scalar("a${char(0x85)}b"))
        assertEquals("\"a${BACKSLASH}x7fb\"", OkfInit.scalar("a${char(0x7F)}b"))
        assertEquals("\"a${BACKSLASH}ufeffb\"", OkfInit.scalar("a${char(0xFEFF)}b"))
        // A date-like value is quoted: a stock YAML 1.1 reader would read a date.
        assertEquals("\"2026-01-01\"", OkfInit.scalar("2026-01-01"))
        assertEquals("okf index", OkfInit.scalar("okf index"))
        // What a YAML 1.2 core-schema reader takes for a number is quoted too.
        for (number in listOf("1e3", "0o17", "0x1F", "1.", ".5", "+1", "-2.5E-3", ".inf", "-.Inf", ".NaN")) {
            assertEquals("\"$number\"", OkfInit.scalar(number), number)
        }
    }

    @Test
    fun `round trip - a lone surrogate cannot be written to okf yaml`() {
        val before = snapshot()
        // UTF-8 has no form for it: the written byte reads back as `?`; the message shows it the same way.
        assertEquals(
            Run(2, "", "okf init: --index-command 'a?b' cannot be written to okf.yaml as given\n"),
            init("--index-command=a${char(0xD800)}b"),
        )
        assertEquals(before, snapshot())
    }

    // --- conflicts: exit 1, nothing written ---

    @Test
    fun `conflict - okf yaml already exists, even an invalid one`() {
        write("okf.yaml", "not: [valid\n")
        assertConflict(listOf("okf.yaml already exists — this repository is already set up"))
    }

    @Test
    fun `conflict - the docs root is a file`() {
        write("docs", "a file\n")
        assertConflict(listOf("docs exists and is not a directory"))
    }

    @Test
    fun `conflict - an ancestor of the docs root is a file`() {
        write("handbook", "a file\n")
        assertConflict(listOf("handbook exists and is not a directory"), "--docs-root=handbook/docs")
    }

    @Test
    fun `conflict - Markdown files in the docs root, counted by frontmatter`() {
        write("docs/notes.md", "# Notes\n")
        write("docs/sub/other/index.md", "# Sub index without frontmatter\n")
        write("docs/guide.md", "---\ntype: Guide\n---\n# Guide\n")
        write("docs/index.md", ROOT_INDEX)
        write("docs/broken.md", "---\nkey: [unclosed\n---\n# Broken\n")
        write("docs/sub/index.md", "---\ntitle: x\n---\n# Sub\n")
        write("docs/readme.txt", "not Markdown\n")
        assertConflict(
            listOf(
                "docs/ already holds 6 Markdown file(s) (2 without frontmatter, 2 with frontmatter, 1 with invalid frontmatter, " +
                    "1 non-root index.md with frontmatter) — pass --adopt to use it as the bundle (no existing file is changed), " +
                    "or --docs-root=<dir> for a separate bundle",
            ),
        )
    }

    @Test
    fun `conflict - empty adr and features directories`() {
        write("docs/adr/.gitkeep", "")
        write("docs/features/.gitkeep", "")
        assertConflict(
            listOf(
                "docs/adr/ already exists — pass --adopt to use docs/ as the bundle, or --docs-root=<dir> for a separate bundle",
                "docs/features/ already exists — pass --adopt to use docs/ as the bundle, or --docs-root=<dir> for a separate bundle",
            ),
        )
    }

    @Test
    fun `conflict - all problems are reported together, in order`() {
        write("okf.yaml", "schema_version: 1\nrepo: x\n")
        write("docs/notes.md", "# Notes\n")
        write("docs/features/.gitkeep", "")
        assertConflict(
            listOf(
                "okf.yaml already exists — this repository is already set up",
                "docs/ already holds 1 Markdown file(s) (1 without frontmatter, 0 with frontmatter, 0 with invalid frontmatter, " +
                    "0 non-root index.md with frontmatter) — pass --adopt to use it as the bundle (no existing file is changed), " +
                    "or --docs-root=<dir> for a separate bundle",
                "docs/features/ already exists — pass --adopt to use docs/ as the bundle, or --docs-root=<dir> for a separate bundle",
            ),
        )
    }

    @Test
    fun `conflict - with --adopt, a root index md that is no bundle root`() {
        write("docs/index.md", "# Docs without frontmatter\n")
        assertConflict(
            listOf("docs/index.md exists but is not a bundle root: bundle-root index.md must start with frontmatter `okf_version: \"0.2\"`"),
            "--adopt",
        )
        // Only the first problem is named.
        write("docs/index.md", "---\nokf_version: 0.2\ntitle: x\n---\n# Docs\n")
        assertConflict(
            listOf("docs/index.md exists but is not a bundle root: okf_version must be the string \"0.2\" (quoted), got '0.2'"),
            "--adopt",
        )
    }

    // --- --adopt ---

    @Test
    fun `adopt - documents stay unchanged and are named in an info line`() {
        write("docs/notes.md", "# Notes\n")
        write("docs/guide.md", "---\ntype: Guide\n---\n# Guide\n")
        val before = snapshot()
        val run = init("--adopt")
        assertEquals(
            Run(
                0,
                "[created] okf.yaml\n[created] docs/index.md\n" +
                    "OKF INFO: docs/ holds 2 Markdown file(s) that were not changed; okf check --strict lists what to migrate.\n" +
                    "okf init OK: 2 file(s) created.\n",
                "",
            ),
            run,
        )
        assertEquals(before, snapshot().filterKeys { it !in setOf("okf.yaml", "docs/index.md") })
    }

    @Test
    fun `adopt - a valid root index md is kept`() {
        write("docs/index.md", ROOT_INDEX)
        assertEquals(Run(0, "[created] okf.yaml\n[kept] docs/index.md\nokf init OK: 1 file(s) created.\n", ""), init("--adopt"))
        assertEquals(ROOT_INDEX, read("docs/index.md"))
        assertStrictCheckPasses()

        reset()
        write("docs/index.md", ROOT_INDEX)
        write("docs/notes.md", "# Notes\n")
        assertEquals(
            Run(
                0,
                "[created] okf.yaml\n[kept] docs/index.md\n" +
                    "OKF INFO: docs/ holds 1 Markdown file(s) that were not changed; okf check --strict lists what to migrate.\n" +
                    "okf init OK: 1 file(s) created.\n",
                "",
            ),
            init("--adopt=true"),
        )
    }

    @Test
    fun `adopt - an existing adr directory gets an info line on its generated index`() {
        write("docs/adr/.gitkeep", "")
        write("docs/features/.gitkeep", "")
        val run = init("--adopt", "--index-command=./gradlew okfIndex")
        assertEquals(
            Run(
                0,
                "[created] okf.yaml\n[created] docs/index.md\n" +
                    "OKF INFO: docs/adr/index.md is generated: ./gradlew okfIndex creates or replaces it.\n" +
                    "OKF INFO: docs/features/index.md is generated: ./gradlew okfIndex creates or replaces it.\n" +
                    "okf init OK: 2 file(s) created.\n",
                "",
            ),
            run,
        )
    }

    @Test
    fun `adopt - the info line shows line separators in the index command escaped, on one line`() {
        for (separator in listOf(0x2028, 0x2029)) {
            reset()
            write("docs/adr/.gitkeep", "")
            val command = "okf${char(separator)}index"
            val run = init("--adopt", "--index-command=$command")
            val shown = "okf${BACKSLASH}u%04xindex".format(separator)
            assertEquals(
                Run(
                    0,
                    "[created] okf.yaml\n[created] docs/index.md\n" +
                        "OKF INFO: docs/adr/index.md is generated: $shown creates or replaces it.\n" +
                        "okf init OK: 2 file(s) created.\n",
                    "",
                ),
                run,
                shown,
            )
            assertFalse(run.out.any { it.code == 0x2028 || it.code == 0x2029 }, shown)
            assertEquals(command, OkfConfig.load(repo).indexCommand, shown)
        }
    }

    // --- a write that fails midway ---

    @Test
    fun `a write failure removes the files this run created`() {
        // A directory where index.md goes: no conflict (it holds no Markdown file), but the file cannot be created.
        repo.resolve("docs/index.md").mkdirs()
        val before = snapshot()
        val run = init()
        assertEquals(1, run.code, run.toString())
        assertEquals("", run.out)
        assertTrue(run.err.startsWith("okf init: cannot write docs/index.md: "), run.err)
        assertTrue(run.err.endsWith("; the files created by this run were removed\n"), run.err)
        assertEquals(before, snapshot())
    }

    @Test
    fun `a docs root Windows cannot hold fails the write and removes okf yaml again`() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"), "Windows rejects `*` in a file name")
        val before = snapshot()
        val run = init("--docs-root=a*b")
        assertEquals(1, run.code, run.toString())
        assertEquals("", run.out)
        assertTrue(run.err.startsWith("okf init: cannot write a*b: "), run.err)
        assertTrue(run.err.endsWith("; the files created by this run were removed\n"), run.err)
        assertEquals(before, snapshot())
    }

    // --- command line and work tree ---

    @Test
    fun `not a git work tree, or a directory below the root`() {
        assumeTrue(OkfGit.workTreeRoot(home) == null, "the temporary directory lies inside a git work tree")
        val outside = home.resolve("plain").apply { mkdirs() }
        assertEquals(
            Run(2, "", "okf init: --repo must be the git work-tree root (${outside.absoluteFile.normalize().path} is not inside a git work tree)\n"),
            run("init", "--repo=${outside.path}", INDEX_COMMAND),
        )
        val sub = repo.resolve("sub").apply { mkdirs() }
        assertEquals(
            Run(2, "", "okf init: --repo must be the git work-tree root (${sub.absoluteFile.normalize().path} is inside ${OkfGit.workTreeRoot(sub)!!.path})\n"),
            run("init", "--repo=${sub.path}", INDEX_COMMAND),
        )
        assertEquals(listOf<String>(), outside.list()!!.toList())
        assertEquals(listOf<String>(), sub.list()!!.toList())
    }

    @Test
    fun `options of other commands and init options elsewhere are usage errors`() {
        val before = snapshot()
        val cases = listOf(
            listOf("init", "--strict", INDEX_COMMAND) to "okf: --strict does not apply to `okf init`",
            listOf("init", "--base=main", INDEX_COMMAND) to "okf: --base does not apply to `okf init`",
            listOf("check", "--adopt") to "okf: --adopt does not apply to `okf check`",
            listOf("index", "--name=x") to "okf: --name does not apply to `okf index`",
            listOf("xcheck", "--workspace=ws", "--docs-root=x") to "okf: --docs-root does not apply to `okf xcheck`",
            listOf("version", INDEX_COMMAND) to "okf: --index-command does not apply to `okf version`",
            listOf("init", "--adopt=yes", INDEX_COMMAND) to "okf: --adopt must be 'true' or 'false', got 'yes'",
            listOf("init", "--name") to "okf: --name requires a value (--name=<value>)",
        )
        assertAll(
            cases.map { (args, message) ->
                {
                    val run = run(*args.toTypedArray())
                    assertEquals(2, run.code, "$args: $run")
                    assertEquals("$message\n${OkfCli.USAGE}\n", run.err, "$args")
                }
            },
        )
        assertEquals(before, snapshot())
    }

    // --- helpers ---

    private data class Run(val code: Int, val out: String, val err: String)

    private fun run(vararg args: String): Run {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = OkfCli(PrintStream(out, true, Charsets.UTF_8), PrintStream(err, true, Charsets.UTF_8), githubActions = false)
            .run(args.toList(), cwd = repo)
        fun text(stream: ByteArrayOutputStream) = stream.toString(Charsets.UTF_8).replace("\r\n", "\n")
        return Run(code, text(out), text(err))
    }

    /** `okf init` in [repo] with `--index-command=okf index` unless [args] name one. */
    private fun init(vararg args: String): Run =
        run("init", "--repo=${repo.path}", *(if (args.any { it.startsWith(CliArgs.INDEX_COMMAND) }) args else arrayOf(*args, INDEX_COMMAND)))

    /** Runs `init` with [args], expecting exit 1 with exactly [problems] and an unchanged tree. */
    private fun assertConflict(problems: List<String>, vararg args: String) {
        val before = snapshot()
        val run = init(*args)
        val expected = "okf init found ${problems.size} problem(s); nothing was written:\n" + problems.joinToString("") { "  - $it\n" }
        assertEquals(Run(1, "", expected), run)
        assertEquals(before, snapshot())
    }

    private fun assertStrictCheckPasses() {
        val check = run("check", "--strict", "--refs-strict", "--code-paths-required")
        assertEquals(Run(0, "okf check OK: 0 documents with frontmatter validated, 0 code references resolved.\n", ""), check)
    }

    /** Every path below [repo] except `.git`: directories map to `null`, files to their bytes (as ISO-8859-1 text). */
    private fun snapshot(): Map<String, String?> =
        repo.walkTopDown().onEnter { it.name != ".git" }.filter { it != repo }
            .associate { it.relativeTo(repo).invariantSeparatorsPath to if (it.isFile) String(it.readBytes(), Charsets.ISO_8859_1) else null }

    /** Removes everything below [repo] but `.git`. */
    private fun reset() {
        repo.listFiles()!!.filter { it.name != ".git" }.forEach { it.deleteRecursively() }
    }

    private fun config(repoValue: String, docsRoot: String? = null, indexCommand: String = "okf index"): String =
        "# OKF (Open Knowledge Format) settings of this repository: https://github.com/sharpbitstudio/okf-cli/blob/main/docs/reference.md\n" +
            "schema_version: 1\n" +
            "repo: $repoValue\n" +
            (docsRoot?.let { "docs_root: $it\n" } ?: "") +
            "index_language: en\n" +
            "index_command: $indexCommand\n" +
            "# ref_extensions: [ts, tsx]    # file extensions (no dot) that code references in the docs may end with, besides the built-in ones\n" +
            "# source_roots: [app/src]      # extra repo-relative code roots\n"

    private fun read(path: String) = repo.resolve(path).readText(Charsets.UTF_8)

    private fun write(path: String, text: String) {
        repo.resolve(path).apply { parentFile.mkdirs() }.writeText(text)
    }

    private fun gitInit(dir: File) {
        val empty = home.resolve("empty").apply { writeText("") }
        git(dir, "init", "-q", "-b", "main")
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
        ).forEach { (key, value) -> git(dir, "config", key, value) }
    }

    private fun git(dir: File, vararg args: String): String {
        val process = ProcessBuilder(listOf("git") + args).directory(dir).redirectErrorStream(true)
            .apply {
                environment()["GIT_CONFIG_GLOBAL"] = globalConfig.path
                environment()["GIT_CONFIG_NOSYSTEM"] = "1"
            }
            .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed: $output" }
        return output.trim()
    }

    private companion object {
        const val INDEX_COMMAND = "--index-command=okf index"
        const val ROOT_INDEX = "---\nokf_version: \"0.2\"\n---\n# Docs\n"
        const val BACKSLASH = "\\"
        val CONTROL = Char(1).toString()

        fun char(code: Int) = Char(code).toString()
    }
}
