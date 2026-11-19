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
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.yaml.snakeyaml.Yaml
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.Collections

/**
 * `xcheck/xcheck.sh` (the committed copy: `GITHUB_ACTION_PATH` is `okf.repoRoot`/xcheck, so the launcher is the
 * committed `launcher/okfw`) run by a real `sh` as xcheck/action.yml runs it, in the hub `hub-repo`. The satellites
 * `sat-a` and `sat-b` are real git repositories with history, published as bare repositories under a server directory
 * that `GITHUB_SERVER_URL` names (`file://`, or over dumb HTTP with Basic auth for the token). The launcher runs this
 * build's jar (`okf.allJar`) with a real Java from a seeded cache, against a pin generated for it; the `curl` stub fails
 * if called. A `git` wrapper first on the `PATH` logs every argv (and the variables a clone gets) before it runs the
 * real git. Also checks the credential helper, git's matching of it, and xcheck/action.yml against the contract.
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
     * launcher with and git runs the credential helper with (git cannot spawn a toolbox script as `sh`).
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
                cloneLine("$fileServer/owner/sat-a.git", "${workspace.shell}/sat-a"),
                cloneLine("$fileServer/owner/sat-b.git", "${workspace.shell}/sat-b"),
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
    fun `every clone runs with the credential helper, no askpass, no prompts, no LFS smudge, and the token in its own variable`() {
        publish()
        assertEquals(0, action("owner/sat-a owner/sat-b").code)
        val calls = gitCalls()
        assertEquals(2, clones().size)
        for (clone in clones()) {
            assertEquals(
                listOf(
                    "GIT_ASKPASS=",
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
                cloneLine("$fileServer/owner/sat-a.git", "${workspace.shell}/sat-a"),
                cloneLine("$fileServer/owner/sat-c.git", "${workspace.shell}/sat-c"),
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
                cloneLine("$fileServer/owner/sat-b.git", "${workspace.shell}/sat-b"),
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
     * git only through the action's credential helper. Sentinels in the global git configuration and the environment
     * (another credential helper, one scoped to the server, a `core.askPass` and an `SSH_ASKPASS` program) are never
     * called; a plain clone shows that each of them would be.
     */
    @Test
    fun `the token reaches git only through the action's credential helper - HTTP Basic auth, no other helper, no askpass, no prompt`() {
        publish()
        DumbHttpServer(server, "x-access-token", TOKEN).use { http ->
            val sentinels = Sentinels()
            val withAskPass = sentinels.config(listOf(http.url))
            val withoutAskPass = sentinels.config(listOf(http.url), withAskPass = false)
            val sshAskPass = mapOf("SSH_ASKPASS" to sentinels.sshAskPass.script.shell)

            // Each sentinel is wired: a plain clone without the action's settings asks them (and then fails, no prompt).
            // GIT_ASKPASS comes before core.askPass, which comes before SSH_ASKPASS, so each has its own control.
            fun control(name: String, env: Map<String, String?>) = Host.run(
                listOf(realGit.path, "clone", "-q", "${http.url}/owner/sat-a.git", tmp.resolve(name).path),
                tmp,
                gitEnv() + HERMETIC.associateWith { null } + mapOf("GIT_TERMINAL_PROMPT" to "0", "NO_PROXY" to "127.0.0.1") + env,
                timeoutSeconds = 60,
            )
            gitConfig.writeText(withAskPass)
            val first = control("control-1", emptyMap())
            assertTrue(first.code != 0, first.toString())
            for (sentinel in listOf(sentinels.helper, sentinels.scoped, sentinels.askPass)) {
                assertTrue(sentinels.log(sentinel).isNotEmpty(), "${sentinel.name} not called: $first ${sentinels.called()}")
            }
            sentinels.clear()
            gitConfig.writeText(withoutAskPass)
            val second = control("control-2", sshAskPass)
            assertTrue(second.code != 0, second.toString())
            assertTrue(sentinels.log(sentinels.sshAskPass).isNotEmpty(), "ssh-askpass not called: $second ${sentinels.called()}")
            sentinels.clear()

            // The empty token is the only case where git asks beyond the helper: once with core.askPass, once with
            // SSH_ASKPASS alone.
            for ((token, config, env) in listOf(
                Triple("", withAskPass, emptyMap<String, String>()),
                Triple("", withoutAskPass, sshAskPass),
                Triple("wrong-token", withAskPass, sshAskPass),
            )) {
                gitConfig.writeText(config)
                http.authorizations.clear()
                val denied = action("owner/sat-a owner/sat-b", token = token, server = http.url, env = env, timeoutSeconds = 60)
                assertEquals(2, denied.code, "token '$token': $denied")
                assertEquals("::error title=okf-cli::cannot clone owner/sat-a: git clone exited with 128\n", denied.out, token)
                assertFalse(workspace.resolve("sat-a").exists(), token)
                assertEquals(emptyMap<String, String>(), sentinels.called(), "token '$token', $env")
                assertFalse(output.exists(), token)
            }
            assertTrue(http.authorizations.contains(basic("x-access-token", "wrong-token")), http.authorizations.toString())

            gitConfig.writeText(withAskPass)
            http.authorizations.clear()
            val run = action("owner/sat-a owner/sat-b", server = http.url, env = sshAskPass, timeoutSeconds = 60)
            assertEquals(0, run.code, run.toString())
            assertTrue(run.out.startsWith("okf xcheck OK: 3 repos checked, "), run.out)
            assertTrue(http.authorizations.contains(basic("x-access-token", TOKEN)), http.authorizations.toString())
            assertEquals(setOf(basic("x-access-token", TOKEN)), http.authorizations.filterNotNull().toSet())
            assertEquals(emptyMap<String, String>(), sentinels.called())

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

            // A user name in the runner's git configuration does not change the user the server sees.
            wipe(workspace)
            gitConfig.writeText(withAskPass + "[credential]\n\tusername = someone\n")
            http.authorizations.clear()
            val named = action("owner/sat-a owner/sat-b", server = http.url, timeoutSeconds = 60)
            assertEquals(0, named.code, named.toString())
            assertEquals(setOf(basic("x-access-token", TOKEN)), http.authorizations.filterNotNull().toSet())
            assertEquals(emptyMap<String, String>(), sentinels.called())
        }
    }

    @Test
    fun `a redirect within the server keeps the token - a renamed or transferred repository`() {
        publish()
        // The same repository at its new place.
        val moved = server.resolve("moved/sat-a.git")
        git(tmp, "clone", "-q", "--bare", satA.path, moved.path)
        git(moved, "update-server-info")
        DumbHttpServer(server, "x-access-token", TOKEN).use { http ->
            http.redirect = DumbHttpServer.Redirect("/owner/sat-a.git/", "${http.url}/moved/sat-a.git/")
            val run = action("owner/sat-a owner/sat-b", server = http.url, timeoutSeconds = 60)
            assertEquals(0, run.code, run.toString())
            assertTrue(
                http.requests.any { it.path.startsWith("/moved/sat-a.git/") && it.authorization == basic("x-access-token", TOKEN) },
                http.requests.toString(),
            )
            assertEquals(git(satA, "rev-parse", "HEAD"), git(workspace.resolve("sat-a"), "rev-parse", "HEAD"))
        }
    }

    @Test
    fun `another origin never gets the token - a redirect to another host before authentication`() =
        anotherOrigin { first, second ->
            first.redirect = DumbHttpServer.Redirect("/owner/sat-a.git/", "${second.url}/owner/sat-a.git/")
            ""
        }

    @Test
    fun `another origin never gets the token - a redirect to another host after authentication`() =
        anotherOrigin(afterAuth = true) { first, second ->
            first.redirect = DumbHttpServer.Redirect("/owner/sat-a.git/", "${second.url}/owner/sat-a.git/", afterAuth = true)
            ""
        }

    @Test
    fun `another origin never gets the token - a URL rewritten by the git configuration`() =
        anotherOrigin { first, second -> "[url \"${second.url}/\"]\n\tinsteadOf = ${first.url}/\n" }

    /**
     * Two servers on the loopback interface, each demanding the token: the action clones from the first, and [setup]
     * (which returns extra global git configuration) sends git to the second, another origin (another port). The second
     * server must be reached and never see a credential; the clone fails. With [afterAuth], the first server redirects
     * only a request that carried the token.
     */
    private fun anotherOrigin(afterAuth: Boolean = false, setup: (DumbHttpServer, DumbHttpServer) -> String) {
        publish()
        DumbHttpServer(server, "x-access-token", TOKEN).use { first ->
            DumbHttpServer(server, "x-access-token", TOKEN).use { second ->
                val sentinels = Sentinels()
                gitConfig.writeText(sentinels.config(listOf(first.url, second.url)) + setup(first, second))
                val run = action("owner/sat-a owner/sat-b", server = first.url, timeoutSeconds = 60)
                assertTrue(second.requests.isNotEmpty(), "the second server was not reached: $run")
                assertEquals(
                    emptyList<String>(),
                    decoded(second.requests.map { it.authorization }),
                    "credentials sent to the second server (${second.url})",
                )
                assertEquals(2, run.code, run.toString())
                assertEquals("::error title=okf-cli::cannot clone owner/sat-a: git clone exited with 128\n", run.out)
                assertFalse(output.exists())
                assertFalse(workspace.resolve("sat-a").exists())
                if (afterAuth) {
                    // Whether the credentials follow this redirect is the HTTP library's decision; git reports the original URL.
                    assertTrue(
                        first.requests.any { it.path.startsWith("/owner/sat-a.git/") && it.authorization == basic("x-access-token", TOKEN) },
                        first.requests.toString(),
                    )
                } else {
                    assertTrue("127.0.0.1:${second.port}" in run.err, run.err)
                }
                assertEquals(emptyMap<String, String>(), sentinels.called())
            }
        }
    }

    @Test
    fun `a proxy never gets the token, even one whose configured URL names a user`() {
        publish()
        DumbHttpServer(server, "x-access-token", TOKEN).use { http ->
            RecordingProxy().use { proxy ->
                val sentinels = Sentinels()
                val proxyUrl = "http://someone@127.0.0.1:${proxy.port}"
                gitConfig.writeText(sentinels.config(listOf(http.url)) + "[http]\n\tproxy = $proxyUrl\n")

                // The proxy is reached and its decoding works: a plain clone whose askpass answers `proxy-control`.
                val askpass = tmp.resolve("proxy-control.sh").apply {
                    writeText("#!/bin/sh\necho proxy-control\n")
                    setExecutable(true, false)
                }
                val control = Host.run(
                    listOf(realGit.path, "clone", "-q", "${http.url}/owner/sat-a.git", tmp.resolve("control").path),
                    tmp,
                    gitEnv() + HERMETIC.associateWith { null } + mapOf("GIT_TERMINAL_PROMPT" to "0", "GIT_ASKPASS" to askpass.shell),
                    timeoutSeconds = 60,
                )
                assertTrue(control.code != 0, control.toString())
                assertTrue("someone:proxy-control" in proxy.credentials, "${proxy.credentials} $control")
                proxy.credentials.clear()
                sentinels.clear()

                val run = action(
                    "owner/sat-a owner/sat-b",
                    server = http.url,
                    env = mapOf("NO_PROXY" to null, "no_proxy" to null),
                    timeoutSeconds = 60,
                )
                assertEquals(emptyList<String>(), proxy.credentials.filterNotNull().filter { TOKEN in it }, "the proxy received the token")
                assertEquals(2, run.code, run.toString())
                assertEquals("::error title=okf-cli::cannot clone owner/sat-a: git clone exited with 128\n", run.out)
                assertFalse(output.exists())
                assertFalse(workspace.resolve("sat-a").exists())
                assertTrue("someone@127.0.0.1:${proxy.port}" in run.err, run.err)
                assertEquals(emptyMap<String, String>(), sentinels.called())
            }
        }
    }

    /**
     * git's own matching, with the action's own `-c` settings (from [cloneArgs]): `git credential fill` for a URL gives
     * the token only for the server's scheme, host and port and a repository path; for anything else git finds no
     * helper that answers and, with askpass and prompts off, fails.
     */
    @Test
    fun `git asks the credential helper only for the server's scheme, host and port, and only for a repository path`() {
        val github = "https://github.com"
        val given = listOf(
            "https://github.com/owner/x.git",
            "https://github.com:443/owner/x.git",
            "https://GitHub.COM/owner/x.git",
            "https://someone@github.com/owner/x.git",
            // As a URL this is github.com with the path `@evil.example/x`; only its decoded display in a prompt looked
            // like another host.
            "https://github.com/@evil.example/x",
        )
        val withheld = listOf(
            "https://github.com%2F@evil.example/x",
            "https://x%40github.com@evil.example/x",
            "https://x@github.com@evil.example/x",
            "https://github.com.evil/x",
            "https://evil.example/x",
            "http://github.com/x",
            "https://github.com:8443/x",
            "cert:///etc/x.p12",
            // No repository path.
            "https://github.com",
            "https://someone@github.com",
        )
        for (url in given) assertGiven(fill(url, github), url)
        for (url in withheld) assertWithheld(fill(url, github), url)

        val local = "http://127.0.0.1:8080"
        assertGiven(fill("http://127.0.0.1:8080/x.git", local), local)
        for (url in listOf("http://127.0.0.1:80801/x.git", "http://127.0.0.1/x.git", "https://127.0.0.1:8080/x.git")) {
            assertWithheld(fill(url, local), url)
        }

        // An action path with a space, and on Windows with backslashes.
        val spaced = tmp.resolve("action path/xcheck").apply { mkdirs() }
        actionPath.resolve("credential.sh").copyTo(spaced.resolve("credential.sh"))
        assertGiven(fill("https://github.com/owner/x.git", github, if (WINDOWS) spaced.path else spaced.shell), spaced.path)
    }

    private fun assertGiven(run: Run, url: String) {
        assertEquals(0, run.code, "$url: $run")
        assertEquals(
            listOf("username=x-access-token", "password=$TOKEN"),
            run.out.lines().filter { it.startsWith("username=") || it.startsWith("password=") },
            url,
        )
    }

    private fun assertWithheld(run: Run, url: String) {
        assertEquals(128, run.code, "$url: $run")
        assertFalse(TOKEN in run.out, "$url: stdout holds the token")
        assertFalse(TOKEN in run.err, "$url: stderr holds the token")
    }

    /**
     * The action's `-c` settings alone keep askpass off: with `GIT_ASKPASS` unset (not empty), neither a `core.askPass`
     * program in the global git configuration nor an `SSH_ASKPASS` program is called for a request the helper does not
     * answer. Without the action's settings, each of them is (the controls).
     */
    @Test
    fun `no askpass program is called - GIT_ASKPASS unset, git credential fill with the action's settings`() {
        val github = "https://github.com"
        val sentinels = Sentinels()
        val withAskPass = sentinels.config(emptyList())
        val withoutAskPass = sentinels.config(emptyList(), withAskPass = false)
        val sshAskPass = mapOf("SSH_ASKPASS" to sentinels.sshAskPass.script.shell)

        gitConfig.writeText(withAskPass)
        val first = fill("https://evil.example/x", github, settings = false, askPass = null)
        assertTrue(sentinels.log(sentinels.askPass).isNotEmpty(), "core-askpass not called: $first ${sentinels.called()}")
        sentinels.clear()
        gitConfig.writeText(withoutAskPass)
        val second = fill("https://evil.example/x", github, settings = false, askPass = null, env = sshAskPass)
        assertTrue(sentinels.log(sentinels.sshAskPass).isNotEmpty(), "ssh-askpass not called: $second ${sentinels.called()}")
        sentinels.clear()

        for ((config, env) in listOf(withAskPass to emptyMap<String, String>(), withoutAskPass to sshAskPass)) {
            gitConfig.writeText(config)
            for (url in listOf("https://evil.example/x", "https://github.com")) {
                val run = fill(url, github, askPass = null, env = env)
                assertEquals(emptyMap<String, String>(), sentinels.called(), "$url, $env: $run")
                assertWithheld(run, url)
            }
        }
    }

    /**
     * The action's `-c credential.helper=` and `-c core.askPass=` also override a credential helper and a `core.askPass`
     * program from the environment (`GIT_CONFIG_COUNT`, `GIT_CONFIG_KEY_<n>` and `GIT_CONFIG_VALUE_<n>`, which git reads
     * before a command's own `-c`): with `GIT_ASKPASS` unset, neither is called for a request the action's helper does
     * not answer. Without the action's settings, both are (the control).
     */
    @Test
    fun `no credential helper or askpass program from GIT_CONFIG_COUNT is called - git credential fill with the action's settings`() {
        val github = "https://github.com"
        val sentinels = Sentinels()
        val fromEnvironment = sentinels.environment()

        val control = fill("https://evil.example/x", github, settings = false, askPass = null, env = fromEnvironment)
        for (sentinel in listOf(sentinels.helper, sentinels.askPass)) {
            assertTrue(sentinels.log(sentinel).isNotEmpty(), "${sentinel.name} not called: $control ${sentinels.called()}")
        }
        sentinels.clear()

        for (url in listOf("https://evil.example/x", "https://github.com")) {
            val run = fill(url, github, askPass = null, env = fromEnvironment)
            assertEquals(emptyMap<String, String>(), sentinels.called(), "$url: $run")
            assertWithheld(run, url)
        }
    }

    /**
     * Windows only: `MSYS=noemptyenvvalues` is an option of the MSYS runtime of Git for Windows, whose `sh` then drops
     * every variable with an empty value when it starts `git.exe`, the action's empty `GIT_ASKPASS` included. With an
     * empty token (git asks beyond the helper) no askpass program is called.
     */
    @Test
    fun `no askpass program is called - on Windows with MSYS dropping the empty GIT_ASKPASS`() {
        assumeTrue(WINDOWS, "an option of the MSYS runtime of Git for Windows")
        val msys = mapOf("MSYS" to "noemptyenvvalues")
        // The option is in effect: an empty GIT_ASKPASS set in sh does not reach git.
        val probe = tmp.resolve("msys-probe.sh").apply {
            writeText("GIT_ASKPASS='' ${quote(realGit.shell)} -c 'alias.probe=!echo \"askpass:\${GIT_ASKPASS+set}\"' probe\n")
        }
        val environment = { env: Map<String, String?> ->
            box.environment(path = listOf(box.toolbox) + gitPath, env = gitEnv() + HERMETIC.associateWith { null } + env)
        }
        assertEquals("askpass:set", Host.run(listOf(Host.sh.path, probe.shell), tmp, environment(emptyMap())).lf().out.trim())
        assertEquals("askpass:", Host.run(listOf(Host.sh.path, probe.shell), tmp, environment(msys)).lf().out.trim())

        publish()
        DumbHttpServer(server, "x-access-token", TOKEN).use { http ->
            val sentinels = Sentinels()
            val sshAskPass = mapOf("SSH_ASKPASS" to sentinels.sshAskPass.script.shell)
            for ((config, env) in listOf(
                sentinels.config(listOf(http.url)) to emptyMap<String, String>(),
                sentinels.config(listOf(http.url), withAskPass = false) to sshAskPass,
            )) {
                gitConfig.writeText(config)
                val denied = action("owner/sat-a owner/sat-b", token = "", server = http.url, env = env + msys, timeoutSeconds = 60)
                assertEquals(emptyMap<String, String>(), sentinels.called(), "$env: $denied")
                assertEquals(2, denied.code, "$env: $denied")
                assertEquals("::error title=okf-cli::cannot clone owner/sat-a: git clone exited with 128\n", denied.out, env.toString())
                assertFalse(output.exists())
            }
        }
    }

    @Test
    fun `the credential helper answers only a get for a repository path, with the token up to its first line break`() {
        val request = "protocol=https\nhost=github.com\npath=owner/x.git\n\n"
        val answer = Run(0, "username=x-access-token\npassword=$TOKEN\n", "")
        val nothing = Run(0, "", "")
        val withToken = mapOf("OKF_XCHECK_TOKEN" to TOKEN)
        assertEquals(answer, credential(request, withToken, "get"))
        // The input may end without an empty line.
        assertEquals(answer, credential("protocol=https\nhost=github.com\npath=owner/x.git\n", withToken, "get"))
        assertEquals(answer, credential(request, mapOf("OKF_XCHECK_TOKEN" to "$TOKEN\nsecond line"), "get"))
        assertEquals(answer, credential(request, mapOf("OKF_XCHECK_TOKEN" to "$TOKEN\rrest"), "get"))
        for (input in listOf(
            "protocol=https\nhost=github.com\n\n",
            "protocol=https\nhost=github.com\npath=\n\n",
            "protocol=https\nhost=github.com\nusername=someone\n\n",
            "protocol=https\nhost=github.com\n\npath=owner/x.git\n",
            "",
        )) {
            assertEquals(nothing, credential(input, withToken, "get"), input)
        }
        for (token in listOf(null, "", "\n$TOKEN", "\r$TOKEN")) {
            val env = token?.let { mapOf("OKF_XCHECK_TOKEN" to it) } ?: emptyMap()
            assertEquals(nothing, credential(request, env, "get"), env.toString())
        }
        for (operation in listOf(listOf("store"), listOf("erase"), listOf("fill"), emptyList())) {
            assertEquals(nothing, credential(request, withToken, *operation.toTypedArray()), operation.toString())
        }
    }

    @Test
    fun `an empty or unset GITHUB_SERVER_URL means https github com`() {
        publish()
        // The clones stay local: the git configuration rewrites that server to the published repositories.
        gitConfig.writeText("[url \"$fileServer/\"]\n\tinsteadOf = https://github.com/\n")
        val github = "https://github.com"
        for (value in listOf("", null)) {
            wipe(workspace)
            val run = action("owner/sat-a owner/sat-b", env = mapOf("GITHUB_SERVER_URL" to value))
            assertEquals(0, run.code, "GITHUB_SERVER_URL=$value: $run")
            assertEquals(
                listOf(
                    cloneLine("$github/owner/sat-a.git", "${workspace.shell}/sat-a", github),
                    cloneLine("$github/owner/sat-b.git", "${workspace.shell}/sat-b", github),
                ),
                actionLines(run).take(2),
                "GITHUB_SERVER_URL=$value",
            )
            assertEquals(
                listOf(
                    cloneArgs("$github/owner/sat-a.git", "${workspace.shell}/sat-a", github),
                    cloneArgs("$github/owner/sat-b.git", "${workspace.shell}/sat-b", github),
                ),
                clones().map { it.args },
                "GITHUB_SERVER_URL=$value",
            )
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

    /** A program that must never be called: [script] appends its arguments to [log]. */
    private class Sentinel(val name: String, val script: File, val log: File)

    /**
     * Programs no action run may call, each logging to its own file: a credential helper in the global git
     * configuration, one scoped to given URLs there, a `core.askPass` program and an `SSH_ASKPASS` program; the first
     * helper and the `core.askPass` program also through the environment ([environment]). None of them prints anything.
     */
    private inner class Sentinels {
        private val dir = tmp.resolve("sentinels").apply { mkdirs() }
        val helper = sentinel("helper")
        val scoped = sentinel("scoped-helper")
        val askPass = sentinel("core-askpass")
        val sshAskPass = sentinel("ssh-askpass")
        private val all = listOf(helper, scoped, askPass, sshAskPass)

        private fun sentinel(name: String): Sentinel {
            val log = dir.resolve("$name.log")
            val script = dir.resolve("$name.sh").apply {
                writeText("#!/bin/sh\nprintf '%s\\n' \"\$*\" >> ${quote(log.shell)}\n")
                setExecutable(true, false)
            }
            return Sentinel(name, script, log)
        }

        /**
         * The global git configuration: [helper], [scoped] for each of [urls], and with [withAskPass] `core.askPass`.
         * A helper's `!` form runs the value through the shell, so a temp path with spaces stays one word (a bare
         * absolute path would be split; a quoted one would no longer count as absolute). Backslashes doubled for git
         * config. git runs the askpass program without a shell: its path is the whole value.
         */
        fun config(urls: List<String>, withAskPass: Boolean = true): String =
            "[credential]\n\thelper = ${helperValue(helper)}\n" +
                urls.joinToString("") { "[credential \"$it\"]\n\thelper = ${helperValue(scoped)}\n" } +
                (if (withAskPass) "[core]\n\taskPass = \"${askPass.script.shell.replace("\\", "\\\\")}\"\n" else "")

        private fun helperValue(sentinel: Sentinel) = "!${quote(sentinel.script.shell).replace("\\", "\\\\")}"

        /**
         * [helper] as `credential.helper` and [askPass] as `core.askPass` through the environment (`GIT_CONFIG_COUNT`),
         * whose values git takes as they are: no escaping for git config.
         */
        fun environment(): Map<String, String> = mapOf(
            "GIT_CONFIG_COUNT" to "2",
            "GIT_CONFIG_KEY_0" to "credential.helper",
            "GIT_CONFIG_VALUE_0" to "!${quote(helper.script.shell)}",
            "GIT_CONFIG_KEY_1" to "core.askPass",
            "GIT_CONFIG_VALUE_1" to askPass.script.shell,
        )

        /** What the sentinels that were called logged, by name; empty when none was. */
        fun called(): Map<String, String> =
            all.filter { it.log.isFile && it.log.length() > 0 }.associate { it.name to it.log.readText() }

        fun log(sentinel: Sentinel): String = sentinel.log.takeIf { it.isFile }?.readText().orEmpty()

        fun clear() = all.forEach { it.log.delete() }
    }

    /** The decoded credentials of the HTTP Basic [authorizations] that are present (`user:password`). */
    private fun decoded(authorizations: List<String?>): List<String> =
        authorizations.filterNotNull().map { value ->
            if (value.startsWith("Basic ")) String(Base64.getDecoder().decode(value.removePrefix("Basic "))) else value
        }

    /** The `git clone` calls of the last run. */
    private fun clones(): List<GitCall> = gitCalls().filter { "clone" in it.args }

    /** The argv of the action's clone of [url] into [dir], with the credential helper scoped to [server]. */
    private fun cloneArgs(url: String, dir: String, server: String = fileServer) =
        listOf("-c", "credential.helper=", "-c", "core.askPass=", "-c", "credential.useHttpPath=true") +
            listOf("-c", "credential.$server.helper=$HELPER") +
            listOf("clone", "--no-tags", "--", url, dir)

    /** The action's log line for that clone. */
    private fun cloneLine(url: String, dir: String, server: String = fileServer) =
        "okf-cli xcheck action: git -c credential.helper= -c core.askPass= -c credential.useHttpPath=true " +
            "-c 'credential.$server.helper=$HELPER' " +
            "clone --no-tags -- $url $dir"

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

    /**
     * `sh xcheck/credential.sh [args]`, as git runs it, with [input] on standard input and only [env] beyond a `PATH` of
     * the toolbox. Through a generated script, which feeds the input.
     */
    private fun credential(input: String, env: Map<String, String>, vararg args: String): Run {
        val stdin = tmp.resolve("credential input.txt").apply { writeBytes(input.toByteArray()) }
        val script = tmp.resolve("credential-run.sh").apply {
            writeText("exec ${quote(Host.sh.shell)} ${quote("${actionPath.shell}/credential.sh")} \"\$@\" < ${quote(stdin.shell)}\n")
        }
        return Host.run(
            listOf(Host.sh.path, script.shell) + args,
            tmp,
            box.environment(path = listOf(box.toolbox), env = HERMETIC.associateWith { null } + env),
        ).lf()
    }

    /**
     * `git credential fill` for [url] with the `-c` settings of a clone from [server] (taken from [cloneArgs], so they
     * cannot drift from the action's), as the action runs git: askpass and prompts off, the token in OKF_XCHECK_TOKEN,
     * `GITHUB_ACTION_PATH` = [actionPath]. Through a generated script: Java on Windows does not escape the quotes of an
     * argument. Without [settings], plain `git credential fill`; with [askPass] `null`, `GIT_ASKPASS` stays unset; [env]
     * adds to the environment.
     */
    private fun fill(
        url: String,
        server: String,
        actionPath: String = this.actionPath.shell,
        settings: Boolean = true,
        askPass: String? = "",
        env: Map<String, String?> = emptyMap(),
    ): Run {
        val args = if (settings) cloneArgs("", "", server).takeWhile { it != "clone" } else emptyList()
        val script = tmp.resolve("fill.sh").apply {
            writeText(
                "printf 'url=%s\\n\\n' \"\$1\" |\n" +
                    "  ${askPass?.let { "GIT_ASKPASS=${quote(it)} " } ?: ""}GIT_TERMINAL_PROMPT=0 ${quote(realGit.shell)} " +
                    "${args.joinToString("") { quote(it) + " " }}credential fill\n",
            )
        }
        val environment = box.environment(
            path = listOf(box.toolbox) + gitPath,
            env = gitEnv() + HERMETIC.associateWith { null } +
                mapOf("OKF_XCHECK_TOKEN" to TOKEN, "GITHUB_ACTION_PATH" to actionPath) + env,
        )
        return Host.run(listOf(Host.sh.path, script.shell, url), tmp, environment, timeoutSeconds = 60).lf()
    }

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
     * gets `401` with a Basic challenge. Records the `Authorization` of every request (`null` when absent), also with
     * its path in [requests]. With a [redirect], a matching request gets `302` instead.
     */
    private class DumbHttpServer(private val root: File, user: String, password: String) : AutoCloseable {
        /**
         * A `302` for every request whose path starts with [from], to [to] followed by the rest of the path and the
         * query; decided before the auth check, or after it with [afterAuth] (the redirect of an authenticated request).
         */
        data class Redirect(val from: String, val to: String, val afterAuth: Boolean = false)

        data class Request(val path: String, val authorization: String?)

        private val expected = "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray())
        val authorizations: MutableList<String?> = Collections.synchronizedList(mutableListOf())
        val requests: MutableList<Request> = Collections.synchronizedList(mutableListOf())

        @Volatile
        var redirect: Redirect? = null
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
        val port: Int = server.address.port
        val url = "http://127.0.0.1:$port"

        private fun handle(exchange: HttpExchange) {
            val authorization = exchange.requestHeaders.getFirst("Authorization")
            authorizations += authorization
            requests += Request(exchange.requestURI.rawPath, authorization)
            if (redirected(exchange, afterAuth = false)) return
            if (authorization != expected) {
                exchange.responseHeaders.add("WWW-Authenticate", "Basic realm=\"okf-test\"")
                exchange.sendResponseHeaders(401, -1)
                return
            }
            if (redirected(exchange, afterAuth = true)) return
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

        /** Answers `302` when the [redirect] rule decided at this point ([afterAuth]) matches the request. */
        private fun redirected(exchange: HttpExchange, afterAuth: Boolean): Boolean {
            val rule = redirect?.takeIf { it.afterAuth == afterAuth } ?: return false
            val path = exchange.requestURI.rawPath
            if (!path.startsWith(rule.from)) return false
            val query = exchange.requestURI.rawQuery?.let { "?$it" } ?: ""
            exchange.responseHeaders.add("Location", rule.to + path.removePrefix(rule.from) + query)
            exchange.sendResponseHeaders(302, -1)
            return true
        }

        override fun close() = server.stop(0)
    }

    /**
     * An HTTP proxy on the loopback interface that forwards nothing: per connection it reads one request head, records
     * its `Proxy-Authorization` in [credentials] (HTTP Basic decoded to `user:password`, any other scheme as sent,
     * `null` when absent) and answers `407` with a Basic challenge. A raw socket: the JDK's HTTP server does not take
     * the proxy form of a request target (`GET http://host/path`).
     */
    private class RecordingProxy : AutoCloseable {
        private val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val port: Int = socket.localPort
        val credentials: MutableList<String?> = Collections.synchronizedList(mutableListOf())

        init {
            Thread {
                while (true) {
                    val client = try {
                        socket.accept()
                    } catch (_: IOException) {
                        break
                    }
                    serve(client)
                }
            }.apply { isDaemon = true }.start()
        }

        private fun serve(client: Socket) {
            try {
                client.use {
                    it.soTimeout = 10_000
                    val head = readHead(it.getInputStream())
                    if (head.isEmpty()) return
                    val header = head.split("\r\n").drop(1)
                        .firstOrNull { line -> line.substringBefore(':').trim().equals("Proxy-Authorization", ignoreCase = true) }
                        ?.substringAfter(':')?.trim()
                    credentials += header?.let { value ->
                        if (value.startsWith("Basic ")) String(Base64.getDecoder().decode(value.removePrefix("Basic ").trim())) else value
                    }
                    it.getOutputStream().apply {
                        write(
                            ("HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"okf-test\"\r\n" +
                                "Content-Length: 0\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII),
                        )
                        flush()
                    }
                }
            } catch (_: IOException) {
                // A client that went away: nothing to record.
            }
        }

        /** The request line and headers, up to the empty line (or the end of the stream), as ISO-8859-1. */
        private fun readHead(input: InputStream): String {
            val bytes = ByteArrayOutputStream()
            while (bytes.size() < 65_536) {
                val b = input.read()
                if (b < 0) break
                bytes.write(b)
                if (bytes.size() >= 4 && bytes.toString(Charsets.ISO_8859_1).endsWith("\r\n\r\n")) break
            }
            return bytes.toString(Charsets.ISO_8859_1).removeSuffix("\r\n\r\n")
        }

        override fun close() = socket.close()
    }

    private companion object {
        /** A test value, never a real credential. */
        const val TOKEN = "not-a-real-token-value"

        /** The value of the action's credential helper setting; git's shell expands the variable. */
        const val HELPER = "!sh \"\$GITHUB_ACTION_PATH/credential.sh\""

        /**
         * Removed from every child: git prompts and helpers, configuration from the environment, the action's token
         * variable, proxies. `no_proxy` comes before `NO_PROXY`: Windows names are case-insensitive, and a run that sets
         * `NO_PROXY` overrides it in place, after the removal of `no_proxy`.
         */
        val HERMETIC = listOf(
            "GIT_ASKPASS", "SSH_ASKPASS", "GIT_TERMINAL_PROMPT", "GCM_INTERACTIVE", "GIT_LFS_SKIP_SMUDGE", "OKF_XCHECK_TOKEN",
            "OKF_INPUT_TOKEN", "GIT_DIR", "GIT_WORK_TREE", "GIT_CONFIG_PARAMETERS", "GIT_CONFIG_COUNT", "http_proxy",
            "https_proxy", "HTTP_PROXY", "HTTPS_PROXY", "all_proxy", "ALL_PROXY", "no_proxy", "NO_PROXY", "GITHUB_OUTPUT",
            "GITHUB_STEP_SUMMARY",
        )

        /** The version of this build's jar, as it reports it. */
        val VERSION: String by lazy {
            val java = File(System.getProperty("java.home")).resolve("bin/java").path
            val jar = checkNotNull(System.getProperty("okf.allJar")) { "the build passes okf.allJar" }
            Host.run(listOf(java, "-jar", jar, "version"), File("."), emptyMap()).lf().out.removePrefix("okf-cli ").trim()
        }
    }
}
