package com.sharpbitstudio.okf.action

import com.sharpbitstudio.okf.cli.Launcher
import com.sharpbitstudio.okf.launcher.Host
import com.sharpbitstudio.okf.launcher.LauncherSandbox
import com.sharpbitstudio.okf.launcher.Run
import com.sharpbitstudio.okf.launcher.WINDOWS
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Base64
import java.util.Collections

/**
 * `xcheck/xcheck.sh` (the committed copy: `GITHUB_ACTION_PATH` is `okf.repoRoot`/xcheck, so the launcher is the
 * committed `launcher/okfw`) run by a real `sh` as xcheck/action.yml runs it, in the hub `hub-repo`. The satellites
 * `sat-a` and `sat-b` are real git repositories with history, published as bare repositories under a server directory
 * that `GITHUB_SERVER_URL` names (`file://`, or over dumb HTTP with Basic auth for the token). The launcher runs this
 * build's jar (`okf.allJar`) with a real Java from a seeded cache, against a pin generated for it; the `curl` stub fails
 * if called. A `git` wrapper first on the `PATH` logs every argv (and the variables a clone gets) before it runs the
 * real git. Also checks the askpass helper and xcheck/action.yml against the contract.
 */
class XcheckActionTest {
    @TempDir
    lateinit var tmp: File

    private val repoRoot = File(checkNotNull(System.getProperty("okf.repoRoot")) { "the build passes okf.repoRoot" })
    private val allJar = File(checkNotNull(System.getProperty("okf.allJar")) { "the build passes okf.allJar" })
    private val javaHome = File(System.getProperty("java.home"))
    private val actionPath get() = repoRoot.resolve("xcheck")

    private lateinit var box: LauncherSandbox

    /** The hub `hub-repo` and the working directory of every run (`GITHUB_WORKSPACE`), so the input `hub` is `.`. */
    private val hub get() = box.repo
    private val sources get() = tmp.resolve("sources")

    /** What `GITHUB_SERVER_URL` names: `<server>/owner/<repo>.git` are bare repositories. */
    private val server get() = tmp.resolve("server")
    private val workspace get() = tmp.resolve("work space")
    private val report get() = tmp.resolve("runner temp/okf-xcheck-report.md")
    private val summary get() = tmp.resolve("step summary.md")
    private val output get() = tmp.resolve("github output.txt")
    private val gitConfig get() = tmp.resolve("gitconfig")
    private val gitLog get() = tmp.resolve("git-calls.log")

    /**
     * The real git, and on Windows the `PATH` entries after the stub directory and toolbox: the directory of `git.exe`
     * (Java finds it there; `sh` finds the wrapper first) and Git's `usr/bin`, whose `sh.exe` the script runs the
     * launcher with and git runs the askpass helper with (git cannot spawn a toolbox script as `sh`).
     */
    private lateinit var realGit: File
    private lateinit var gitPath: List<File>

    @BeforeEach
    fun setUp() {
        box = LauncherSandbox(tmp.resolve("sandbox"))
        box.toolbox(LauncherSandbox.BASE_TOOLS + Host.shaTool + "cat" + (if (WINDOWS) emptyList() else listOf("sh")))
        box.curl(serve = null)
        gitConfig.writeText("")
        realGit = checkNotNull(onPath(if (WINDOWS) "git.exe" else "git")) { "git on the PATH" }
        gitPath = if (WINDOWS) listOf(realGit.parentFile, Host.usrBin) else emptyList()
        gitWrapper()

        write(
            hub, "okf.yaml",
            "schema_version: 1\nrepo: hub-repo\nindex_language: en\nindex_command: ./okfw index\nexternal_repos: [sat-a, sat-b]\n" +
                "satellites:\n" +
                "  - repo: sat-a\n    description: Satellite A\n    index: https://example.invalid/sat-a/blob/main/docs/index.md\n" +
                "  - repo: sat-b\n    description: Satellite B\n    index: https://example.invalid/sat-b/blob/main/docs/index.md\n",
        )
        write(hub, "docs/index.md", "---\nokf_version: \"0.2\"\n---\n# Docs\n\n## Repos\n\n<!-- okf:repos-start -->\n<!-- okf:repos-end -->\n")
        write(hub, "docs/features/invite.md", record("`sat-a/lib/a.kt:1-2`\n"))
        write(satA, "okf.yaml", "schema_version: 1\nrepo: sat-a\nhub_repo: hub-repo\nexternal_repos: [hub-repo]\n")
        write(satA, "docs/index.md", "---\nokf_version: \"0.2\"\n---\n# A\n")
        write(satA, "lib/a.kt", "line 1\nline 2\n")
        write(satA, "docs/invite/NOTE.md", note("`hub-repo/docs/features/invite.md`\n"))
        write(satB, "okf.yaml", "schema_version: 1\nrepo: sat-b\nhub_repo: hub-repo\nexternal_repos: [hub-repo]\n")
        write(satB, "docs/index.md", "---\nokf_version: \"0.2\"\n---\n# B\n")
        write(satB, "docs/NOTE.md", note(""))
        for (repo in listOf(hub, satA, satB)) {
            git(repo, "init", "-q", "-b", "main")
            commit(repo, "init")
        }
        // A tag that --no-tags must leave behind.
        git(satA, "tag", "v1")

        val bytes = allJar.readBytes()
        box.pin(box.pinFor(VERSION, bytes), hub.resolve(Launcher.PIN))
        box.seed(VERSION, bytes)
    }

    private val satA get() = sources.resolve("sat-a")
    private val satB get() = sources.resolve("sat-b")

    // --- cloning and okf xcheck ---

    @Test
    fun `clean federation - both satellites cloned with full history and no tags, okf xcheck exit 0, no report`() {
        publish()
        val run = action("owner/sat-a sat-b", workspace = "", env = mapOf("OKF_DEFAULT_WORKSPACE" to workspace.shell))
        assertEquals(0, run.code, run.toString())
        assertTrue(run.out.startsWith("okf xcheck OK: 3 repos checked, "), run.out)
        assertEquals(
            listOf(
                "okf-cli xcheck action: git -c credential.helper= clone --no-tags -- $fileServer/owner/sat-a.git ${workspace.shell}/sat-a",
                "okf-cli xcheck action: git -c credential.helper= clone --no-tags -- $fileServer/owner/sat-b.git ${workspace.shell}/sat-b",
                "okf-cli xcheck action: okf xcheck --repo=. --workspace=${workspace.shell} --report=${report.shell}",
            ),
            actionLines(run),
        )
        assertEquals(
            listOf(
                cloneArgs("$fileServer/owner/sat-a.git", "${workspace.shell}/sat-a"),
                cloneArgs("$fileServer/owner/sat-b.git", "${workspace.shell}/sat-b"),
            ),
            clones().map { it.args },
        )
        assertEquals("false", git(workspace.resolve("sat-a"), "rev-parse", "--is-shallow-repository"))
        assertEquals(git(satA, "rev-parse", "HEAD"), git(workspace.resolve("sat-a"), "rev-parse", "HEAD"))
        assertEquals("", git(workspace.resolve("sat-a"), "tag"))
        assertFalse(report.exists())
        assertFalse(summary.exists())
        assertEquals("report=${report.shell}\nexit-code=0\n", output.readText())
    }

    @Test
    fun `every clone runs with the askpass helper, no prompts, no LFS smudge, and the token in its own variable`() {
        publish()
        assertEquals(0, action("owner/sat-a owner/sat-b").code)
        val calls = gitCalls()
        assertEquals(2, clones().size)
        for (clone in clones()) {
            assertEquals(
                listOf(
                    "GIT_ASKPASS=${actionPath.shell}/askpass.sh",
                    "GIT_TERMINAL_PROMPT=0",
                    "GCM_INTERACTIVE=never",
                    "GIT_LFS_SKIP_SMUDGE=1",
                    "OKF_XCHECK_TOKEN",
                ),
                clone.env,
            )
        }
        // The jar's own git calls (through the wrapper where the jar finds it, on POSIX) get none of these.
        if (!WINDOWS) assertTrue((calls - clones().toSet()).isNotEmpty(), "no git call of the jar seen: $calls")
        for (call in calls - clones().toSet()) {
            assertEquals(emptyList<String>(), call.env, call.args.toString())
        }
    }

    @Test
    fun `a broken cross-repo reference - exit 1, the report in the step summary and the outputs`() {
        write(satA, "docs/invite/NOTE.md", note("`hub-repo/docs/gone.md`\n"))
        commit(satA, "broken reference")
        publish()
        val run = action("owner/sat-a owner/sat-b")
        assertEquals(1, run.code, run.toString())
        assertTrue("file not found in hub-repo" in run.err, run.err)
        val text = report.readText()
        assertTrue(text.startsWith("# okf xcheck: 1 problem(s), 0 warning(s)\n"), text)
        assertTrue("sat-a/docs/invite/NOTE.md:8: `hub-repo/docs/gone.md`: file not found in hub-repo" in text, text)
        assertEquals(text, summary.readText())
        assertEquals("report=${report.shell}\nexit-code=1\n", output.readText())

        // Appended to what the step summary holds (the satellites are present now, so nothing is cloned).
        summary.writeText("earlier\n")
        assertEquals(1, action("owner/sat-a owner/sat-b", keepSummary = true).code)
        assertEquals("earlier\n$text", summary.readText())
        assertEquals(emptyList<GitCall>(), clones())

        // A run that writes no report (the launcher's exit 3: the pinned SHA-256 matches no jar it can get) leaves none
        // from the earlier run, and nothing is appended.
        box.pin(box.pinFor(VERSION, "another jar".toByteArray()), hub.resolve(Launcher.PIN))
        summary.writeText("earlier\n")
        val noJar = action("owner/sat-a owner/sat-b", keepSummary = true)
        assertEquals(3, noJar.code, noJar.toString())
        assertFalse(report.exists())
        assertEquals("earlier\n", summary.readText())
        assertEquals("report=${report.shell}\nexit-code=3\n", output.readText())
    }

    @Test
    fun `a missing satellite - exit 2 from okf xcheck, and one that cannot be cloned - exit 2 before it runs`() {
        publish()
        val missing = action("owner/sat-a")
        assertEquals(2, missing.code, missing.toString())
        assertTrue(missing.err.lines().any { it.startsWith("okf xcheck: sat-b: checkout ") }, missing.err)
        assertTrue(report.readText().startsWith("# okf xcheck: 1 problem(s), 0 warning(s)\n"), report.readText())
        assertEquals("report=${report.shell}\nexit-code=2\n", output.readText())

        wipe(workspace)
        val failed = action("owner/sat-a owner/sat-c owner/sat-b")
        assertEquals(2, failed.code, failed.toString())
        assertEquals("::error title=okf-cli::cannot clone owner/sat-c: git clone exited with 128\n", failed.out)
        assertTrue(failed.err.lines().any { it.startsWith("fatal: ") }, failed.err)
        assertEquals(
            listOf(
                "okf-cli xcheck action: git -c credential.helper= clone --no-tags -- $fileServer/owner/sat-a.git ${workspace.shell}/sat-a",
                "okf-cli xcheck action: git -c credential.helper= clone --no-tags -- $fileServer/owner/sat-c.git ${workspace.shell}/sat-c",
            ),
            actionLines(failed),
        )
        assertFalse(workspace.resolve("sat-c").exists())
        assertFalse(workspace.resolve("sat-b").exists())
        assertFalse(output.exists())
        assertFalse(report.exists(), "the report of the earlier run is removed")
    }

    @Test
    fun `an invalid satellite name - exit 2 and one error annotation, nothing cloned, nothing run`() {
        publish()
        val invalid = listOf(
            ".", "..", "owner/.", "owner/..", "../sat-a", "./sat-a", "a/b/c", "owner/", "/sat-a", "/", "sat~a", "sat\$a",
            "sät", "sat-a:x", "sat-a@x", "*", "owner/*", "a\rb", "a\u0001b",
        )
        // Each after a valid entry (validation comes before the first clone); some also first.
        val cases = invalid.map { "owner/sat-a $it" to 2 } + listOf("..", "a/b/c", "sät").map { "$it owner/sat-b" to 1 }
        for ((satellites, entry) in cases) {
            val rejected = action(satellites)
            assertEquals(Run(2, nameError(entry), ""), rejected, "satellites=${satellites.toByteArray().toList()}")
            assertFalse(workspace.exists(), satellites)
            assertFalse(gitLog.exists(), "no git call: $satellites")
            assertFalse(output.exists(), satellites)
        }
        // `repo` alone needs the repository owner.
        assertEquals(Run(2, nameError(1), ""), action("sat-a", env = mapOf("OKF_REPOSITORY_OWNER" to "")))
        assertFalse(workspace.exists())
        // Whitespace of every kind separates entries.
        val run = action(" \towner/sat-a\n\n  sat-b\t")
        assertEquals(0, run.code, run.toString())
        assertEquals(listOf("sat-a", "sat-b"), clones().map { File(it.args.last()).name })
    }

    @Test
    fun `invalid hub and workspace inputs - exit 2, nothing run`() {
        report.apply { parentFile.mkdirs() }.writeText("stale")
        assertEquals(Run(2, "::error title=okf-cli::input 'hub' must not be empty\n", ""), action("", hub = ""))
        assertFalse(report.exists(), "a report left by an earlier run is removed")
        for (value in listOf("hub\nx", "hub\t", "a\u007fb")) {
            assertEquals(Run(2, "::error title=okf-cli::input 'hub' must be one line without control characters\n", ""), action("", hub = value))
            assertEquals(
                Run(2, "::error title=okf-cli::input 'workspace' must be one line without control characters\n", ""),
                action("owner/sat-a", workspace = "${workspace.shell}$value"),
            )
        }
        assertEquals(
            Run(2, "::error title=okf-cli::input 'workspace' must not be empty\n", ""),
            action("", workspace = "", env = mapOf("OKF_DEFAULT_WORKSPACE" to "")),
        )
        assertFalse(gitLog.exists())
        assertFalse(output.exists())
    }

    @Test
    fun `a satellite directory already present is not cloned, and with no satellites nothing is`() {
        publish()
        // A checkout of sat-a with a broken reference in its work tree: the run checks this copy.
        git(tmp, "clone", "-q", fileUrl(satA), workspace.resolve("sat-a").path)
        write(workspace.resolve("sat-a"), "docs/invite/NOTE.md", note("`hub-repo/docs/gone.md`\n"))

        val run = action("owner/sat-a owner/sat-b")
        assertEquals(1, run.code, run.toString())
        assertEquals(
            listOf(
                "okf-cli xcheck action: ${workspace.shell}/sat-a exists, not cloned",
                "okf-cli xcheck action: git -c credential.helper= clone --no-tags -- $fileServer/owner/sat-b.git ${workspace.shell}/sat-b",
                "okf-cli xcheck action: okf xcheck --repo=. --workspace=${workspace.shell} --report=${report.shell}",
            ),
            actionLines(run),
        )
        assertEquals(listOf("sat-b"), clones().map { File(it.args.last()).name })

        val none = action("")
        assertEquals(1, none.code, none.toString())
        assertEquals(
            listOf("okf-cli xcheck action: okf xcheck --repo=. --workspace=${workspace.shell} --report=${report.shell}"),
            actionLines(none),
        )
        assertEquals(emptyList<GitCall>(), clones())
    }

    // --- the token ---

    /**
     * Over dumb HTTP with Basic auth (`x-access-token:<token>`): the clone succeeds only with the token, which reaches
     * git only through the askpass helper; a sentinel credential helper in the global git configuration is never called.
     */
    @Test
    fun `the token reaches git only through the askpass helper - HTTP Basic auth, no credential helper, no prompt`() {
        publish()
        DumbHttpServer(server, "x-access-token", TOKEN).use { http ->
            val sentinelLog = tmp.resolve("sentinel.log")
            val sentinel = tmp.resolve("sentinel-helper.sh").apply {
                writeText("#!/bin/sh\nprintf '%s\\n' \"\$*\" >> '${sentinelLog.shell}'\n")
                setExecutable(true, false)
            }
            // The `!` form runs the value through the shell, so a temp path with spaces stays one word (a bare absolute
            // path would be split; a quoted one would no longer count as absolute). Backslashes doubled for git config.
            gitConfig.writeText("[credential]\n\thelper = !${quote(sentinel.shell).replace("\\", "\\\\")}\n")

            // The sentinel is wired: a plain clone without the action's settings asks it (and then fails, no prompt).
            val control = Host.run(
                listOf(realGit.path, "clone", "-q", "${http.url}/owner/sat-a.git", tmp.resolve("control").path),
                tmp,
                // An askpass that fails (no token): Git for Windows could otherwise offer its own.
                gitEnv() + HERMETIC.associateWith { null } +
                    mapOf("GIT_TERMINAL_PROMPT" to "0", "GIT_ASKPASS" to "${actionPath.shell}/askpass.sh", "NO_PROXY" to "127.0.0.1"),
                timeoutSeconds = 60,
            )
            assertTrue(control.code != 0, control.toString())
            assertTrue(sentinelLog.readText().startsWith("get"), sentinelLog.readText())
            sentinelLog.delete()

            for (token in listOf("", "wrong-token")) {
                http.authorizations.clear()
                val denied = action("owner/sat-a owner/sat-b", token = token, server = http.url, timeoutSeconds = 60)
                assertEquals(2, denied.code, "token '$token': $denied")
                assertEquals("::error title=okf-cli::cannot clone owner/sat-a: git clone exited with 128\n", denied.out, token)
                assertFalse(workspace.resolve("sat-a").exists(), token)
                assertFalse(sentinelLog.exists(), "token '$token': the credential helper was called: ${sentinelLog.takeIf { it.exists() }?.readText()}")
                assertFalse(output.exists(), token)
            }
            assertTrue(http.authorizations.contains(basic("x-access-token", "wrong-token")), http.authorizations.toString())

            http.authorizations.clear()
            val run = action("owner/sat-a owner/sat-b", server = http.url, timeoutSeconds = 60)
            assertEquals(0, run.code, run.toString())
            assertTrue(run.out.startsWith("okf xcheck OK: 3 repos checked, "), run.out)
            assertTrue(http.authorizations.contains(basic("x-access-token", TOKEN)), http.authorizations.toString())
            assertEquals(setOf(basic("x-access-token", TOKEN)), http.authorizations.filterNotNull().toSet())
            assertFalse(sentinelLog.exists(), "the credential helper was called: ${sentinelLog.takeIf { it.exists() }?.readText()}")

            for (repo in listOf("sat-a", "sat-b")) {
                val config = workspace.resolve("$repo/.git/config").readText()
                assertTrue("\turl = ${http.url}/owner/$repo.git\n" in config, config)
                assertFalse(TOKEN in config, "$repo/.git/config holds the token")
            }
            assertEquals(2, clones().size)
            assertFalse(TOKEN in gitLog.readText(), "a git argv holds the token")
            assertFalse(TOKEN in run.out, "stdout holds the token")
            assertFalse(TOKEN in run.err, "stderr holds the token")
            assertFalse(TOKEN in output.readText(), "the outputs hold the token")
        }
    }

    @Test
    fun `askpass answers git's username and password prompts, and nothing else`() {
        val withToken = mapOf("OKF_XCHECK_TOKEN" to TOKEN)
        assertEquals(Run(0, "x-access-token\n", ""), askpass("Username for 'https://github.com': ", withToken))
        assertEquals(Run(0, "$TOKEN\n", ""), askpass("Password for 'https://x-access-token@github.com': ", withToken))
        assertEquals(Run(0, "x-access-token\n", ""), askpass("Username for 'http://127.0.0.1:8080': ", withToken))
        for (prompt in listOf("Enter passphrase for key '/home/u/.ssh/id_ed25519': ", "username for 'x': ", "Password", "")) {
            assertEquals(Run(1, "", ""), askpass(prompt, withToken), prompt)
        }
        for (env in listOf(emptyMap(), mapOf("OKF_XCHECK_TOKEN" to ""))) {
            assertEquals(Run(1, "", ""), askpass("Username for 'https://github.com': ", env), env.toString())
            assertEquals(Run(1, "", ""), askpass("Password for 'https://x-access-token@github.com': ", env), env.toString())
        }
    }

    // --- xcheck/action.yml ---

    @Test
    fun `action yml - inputs, defaults, outputs and one run step that passes everything through env`() {
        @Suppress("UNCHECKED_CAST")
        val action = Yaml().load<Map<String, Any?>>(actionPath.resolve("action.yml").readText()) as Map<String, Map<String, Any?>>
        val inputs = action.getValue("inputs").mapValues { (_, spec) -> (spec as Map<*, *>)["default"] }
        assertEquals(mapOf("hub" to ".", "satellites" to "", "token" to "\${{ github.token }}", "workspace" to ""), inputs)
        assertEquals(
            mapOf("report" to "\${{ steps.okf.outputs.report }}", "exit-code" to "\${{ steps.okf.outputs.exit-code }}"),
            action.getValue("outputs").mapValues { (_, spec) -> (spec as Map<*, *>)["value"] },
        )

        val runs = action.getValue("runs")
        assertEquals("composite", runs["using"])
        val steps = runs["steps"] as List<*>
        assertEquals(1, steps.size, "one step, no other action")
        val step = steps.single() as Map<*, *>
        assertEquals(setOf("id", "shell", "env", "run"), step.keys)
        assertEquals("bash", step["shell"])
        assertEquals("sh \"\$GITHUB_ACTION_PATH/xcheck.sh\"", step["run"])
        assertEquals(
            inputs.keys.associate { "OKF_INPUT_" + it.uppercase() to "\${{ inputs.$it }}" } +
                mapOf(
                    "OKF_REPOSITORY_OWNER" to "\${{ github.repository_owner }}",
                    "OKF_DEFAULT_WORKSPACE" to "\${{ runner.temp }}/okf-xcheck-workspace",
                    "OKF_REPORT" to "\${{ runner.temp }}/okf-xcheck-report.md",
                    "OKFW_CACHE_DIR" to "\${{ runner.temp }}/okf-cli-cache",
                ),
            step["env"],
        )
    }

    // --- harness ---

    /** One call of the `git` wrapper: the variables it saw (see [gitWrapper]) and its argv. */
    private data class GitCall(val env: List<String>, val args: List<String>)

    /**
     * Writes the `git` wrapper into the stub directory: it logs, per call, the clone variables it sees
     * (`GIT_ASKPASS`, `GIT_TERMINAL_PROMPT`, `GCM_INTERACTIVE`, `GIT_LFS_SKIP_SMUDGE` with their values; whether
     * `OKF_XCHECK_TOKEN` and `OKF_INPUT_TOKEN` are set, never their values) and its argv, then runs the real git.
     */
    private fun gitWrapper() {
        val values = listOf("GIT_ASKPASS", "GIT_TERMINAL_PROMPT", "GCM_INTERACTIVE", "GIT_LFS_SKIP_SMUDGE").joinToString("") { name ->
            "if [ -n \"\${$name+x}\" ]; then printf 'env=%s=%s\\n' $name \"\$$name\"; fi\n"
        }
        val presence = listOf("OKF_XCHECK_TOKEN", "OKF_INPUT_TOKEN").joinToString("") { name ->
            "if [ -n \"\${$name+x}\" ]; then printf 'env=%s\\n' $name; fi\n"
        }
        box.stub.resolve("git").apply {
            writeText(
                "#!/bin/sh\n" +
                    "# okf-cli xcheck action test: logs, then runs the real git\n" +
                    "{ printf 'call\\n'\n$values$presence" +
                    "for a in \"\$@\"; do printf 'arg=%s\\n' \"\$a\"; done; printf 'end\\n'; } >> ${quote(gitLog.shell)}\n" +
                    "exec ${quote(realGit.shell)} \"\$@\"\n",
            )
            setExecutable(true, false)
        }
    }

    private fun gitCalls(): List<GitCall> {
        if (!gitLog.isFile) return emptyList()
        val calls = mutableListOf<GitCall>()
        var env = mutableListOf<String>()
        var args = mutableListOf<String>()
        gitLog.readLines(Charsets.UTF_8).forEach { line ->
            when {
                line == "call" -> {
                    env = mutableListOf()
                    args = mutableListOf()
                }
                line.startsWith("env=") -> env += line.removePrefix("env=")
                line.startsWith("arg=") -> args += line.removePrefix("arg=")
                line == "end" -> calls += GitCall(env.toList(), args.toList())
            }
        }
        return calls
    }

    /** The `git clone` calls of the last run. */
    private fun clones(): List<GitCall> = gitCalls().filter { "clone" in it.args }

    private fun cloneArgs(url: String, dir: String) = listOf("-c", "credential.helper=", "clone", "--no-tags", "--", url, dir)

    /** The action's own lines on standard error. */
    private fun actionLines(run: Run) = run.err.lines().filter { it.startsWith("okf-cli xcheck action: ") }

    private fun nameError(entry: Int) =
        "::error title=okf-cli::input 'satellites': entry $entry is not owner/repo or repo " +
            "(each name only letters, digits, '.', '_' and '-', and not '.' or '..')\n"

    /**
     * Runs xcheck.sh as xcheck/action.yml does, in the hub: the inputs, the event's owner `owner`, the default
     * workspace and the report under the runner's temporary directory; [env] overrides the rest. Clears the git log,
     * the outputs and, unless [keepSummary], the step summary first.
     */
    private fun action(
        satellites: String,
        token: String = TOKEN,
        server: String = fileServer,
        hub: String = ".",
        workspace: String = this.workspace.shell,
        env: Map<String, String?> = emptyMap(),
        timeoutSeconds: Long = 120,
        keepSummary: Boolean = false,
    ): Run {
        gitLog.delete()
        output.delete()
        if (!keepSummary) summary.delete()
        val environment = box.environment(
            path = listOf(box.stub, box.toolbox) + gitPath,
            env = gitEnv() + HERMETIC.associateWith { null } +
                mapOf(
                    "JAVA_HOME" to javaHome.shell,
                    "GITHUB_ACTION_PATH" to actionPath.shell,
                    "GITHUB_SERVER_URL" to server,
                    "GITHUB_OUTPUT" to output.shell,
                    "GITHUB_STEP_SUMMARY" to summary.shell,
                    "OKF_INPUT_HUB" to hub,
                    "OKF_INPUT_SATELLITES" to satellites,
                    "OKF_INPUT_TOKEN" to token,
                    "OKF_INPUT_WORKSPACE" to workspace,
                    "OKF_REPOSITORY_OWNER" to "owner",
                    "OKF_DEFAULT_WORKSPACE" to tmp.resolve("runner temp/okf-xcheck-workspace").shell,
                    "OKF_REPORT" to report.shell,
                    "NO_PROXY" to "127.0.0.1",
                ) + env,
        )
        box.guard(environment)
        return Host.run(listOf(Host.sh.path, "${actionPath.shell}/xcheck.sh"), this.hub, environment, timeoutSeconds).lf()
    }

    /** `sh xcheck/askpass.sh <prompt>`, as git runs it, with only [env] beyond a `PATH` of the toolbox. */
    private fun askpass(prompt: String, env: Map<String, String>): Run =
        Host.run(
            listOf(Host.sh.path, "${actionPath.shell}/askpass.sh", prompt),
            tmp,
            box.environment(path = listOf(box.toolbox), env = HERMETIC.associateWith { null } + env),
        ).lf()

    /** Publishes each source satellite as the bare repository `<server>/owner/<name>.git`, readable over dumb HTTP. */
    private fun publish() {
        wipe(server)
        for (source in listOf(satA, satB)) {
            val bare = server.resolve("owner/${source.name}.git")
            git(tmp, "clone", "-q", "--bare", source.path, bare.path)
            git(bare, "update-server-info")
        }
    }

    /** Deletes [dir] and everything below it; git writes its object files read-only, which Windows will not delete. */
    private fun wipe(dir: File) {
        if (!dir.exists()) return
        dir.walkBottomUp().forEach { file ->
            file.setWritable(true)
            check(file.delete()) { "cannot delete $file" }
        }
    }

    private val fileServer get() = fileUrl(server)

    private fun fileUrl(dir: File) = "file://" + (if (WINDOWS) "/" else "") + dir.invariantSeparatorsPath

    /** A hub Feature Record listing both satellites. */
    private fun record(body: String) =
        "---\ntype: Feature Record\ntitle: \"Record\"\nrepos: [sat-a, sat-b]\ncode_paths: []\n---\n# Record\n\n$body"

    /** A Satellite Note of the record `features/invite`; the body starts on line 8. */
    private fun note(body: String) = "---\ntype: Satellite Note\nhub: features/invite\ncode_paths: []\n---\n# Note\n\n$body"

    private fun write(repo: File, path: String, text: String) {
        repo.resolve(path).apply { parentFile.mkdirs() }.writeText(text)
    }

    private fun commit(repo: File, message: String) {
        git(repo, "add", "-A")
        git(repo, "-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false", "commit", "-q", "-m", message)
    }

    private fun gitEnv(): Map<String, String?> = mapOf("GIT_CONFIG_GLOBAL" to gitConfig.path, "GIT_CONFIG_NOSYSTEM" to "1")

    private fun git(dir: File, vararg args: String): String {
        val run = Host.run(listOf(realGit.path) + args, dir, gitEnv() + HERMETIC.associateWith { null })
        check(run.code == 0) { "git ${args.joinToString(" ")}: $run" }
        return run.out.trim()
    }

    private fun basic(user: String, password: String) = "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray())

    private fun onPath(name: String): File? =
        System.getenv("PATH").orEmpty().split(File.pathSeparator).filter { it.isNotEmpty() }.map { File(it, name) }.firstOrNull { it.isFile }

    /** [this] as the shell sees it in an argument or variable: with `/` on Windows. */
    private val File.shell: String get() = if (WINDOWS) invariantSeparatorsPath else path

    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"

    /**
     * Serves the files under [root] read-only over HTTP on the loopback interface, as git's dumb HTTP transport reads a
     * bare repository, to requests whose `Authorization` is HTTP Basic for [user] and [password]; any other request
     * gets `401` with a Basic challenge. Records the `Authorization` of every request (`null` when absent).
     */
    private class DumbHttpServer(private val root: File, user: String, password: String) : AutoCloseable {
        private val expected = "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray())
        val authorizations: MutableList<String?> = Collections.synchronizedList(mutableListOf())
        private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/") { exchange ->
                try {
                    handle(exchange)
                } finally {
                    exchange.close()
                }
            }
            start()
        }
        val url = "http://127.0.0.1:${server.address.port}"

        private fun handle(exchange: HttpExchange) {
            val authorization = exchange.requestHeaders.getFirst("Authorization")
            authorizations += authorization
            if (authorization != expected) {
                exchange.responseHeaders.add("WWW-Authenticate", "Basic realm=\"okf-test\"")
                exchange.sendResponseHeaders(401, -1)
                return
            }
            val base = root.canonicalFile
            val file = base.resolve(exchange.requestURI.path.removePrefix("/")).canonicalFile
            if (exchange.requestMethod != "GET" || !file.isFile || !file.path.startsWith(base.path + File.separator)) {
                exchange.sendResponseHeaders(404, -1)
                return
            }
            val bytes = file.readBytes()
            exchange.responseHeaders.add("Content-Type", "application/octet-stream")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.write(bytes)
        }

        override fun close() = server.stop(0)
    }

    private companion object {
        /** A test value, never a real credential. */
        const val TOKEN = "not-a-real-token-value"

        /** Removed from every child: git prompts and helpers, the action's token variable, proxies. */
        val HERMETIC = listOf(
            "GIT_ASKPASS", "SSH_ASKPASS", "GIT_TERMINAL_PROMPT", "GCM_INTERACTIVE", "GIT_LFS_SKIP_SMUDGE", "OKF_XCHECK_TOKEN",
            "OKF_INPUT_TOKEN", "GIT_DIR", "GIT_WORK_TREE", "http_proxy", "https_proxy", "HTTP_PROXY", "HTTPS_PROXY",
            "all_proxy", "ALL_PROXY", "GITHUB_OUTPUT", "GITHUB_STEP_SUMMARY",
        )

        /** The version of this build's jar, as it reports it. */
        val VERSION: String by lazy {
            val java = File(System.getProperty("java.home")).resolve("bin/java").path
            val jar = checkNotNull(System.getProperty("okf.allJar")) { "the build passes okf.allJar" }
            Host.run(listOf(java, "-jar", jar, "version"), File("."), emptyMap()).lf().out.removePrefix("okf-cli ").trim()
        }
    }
}
