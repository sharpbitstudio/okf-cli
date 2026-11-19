package com.sharpbitstudio.okf.launcher

import com.sharpbitstudio.okf.cli.Launcher
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * This build's `all` jar (`okf.allJar`, passed by the build) end to end with the launcher it embeds and a real Java (the
 * test JVM's `java.home`, through `JAVA_HOME`): `okf wrapper` writes the launcher, the cache is seeded with the jar, and
 * `./okfw` runs it. The `curl` stub fails if called. Version-agnostic: under a release-like `-Pversion` the jar writes
 * the pin itself, otherwise the test writes it.
 */
class LauncherJarTest {
    @TempDir
    lateinit var tmp: File

    private lateinit var box: LauncherSandbox
    private val allJar = File(checkNotNull(System.getProperty("okf.allJar")) { "the build passes okf.allJar" })
    private val javaHome = File(System.getProperty("java.home"))

    /** The real git in the repo, also for the jar's own git calls (a link in the stub directory on POSIX). */
    private lateinit var git: SandboxGit

    @BeforeEach
    fun setUp() {
        box = LauncherSandbox(tmp)
        box.curl(serve = null)
        git = SandboxGit(box)
        git.init()
    }

    @Test
    fun `wrapper writes the launcher, and okfw runs this build's jar - version, init, check, impact`() {
        val version = jar("version").lf().out.removePrefix("okf-cli ").trim()
        val release = version != "unknown" && !version.endsWith("-SNAPSHOT")
        val wrapper = jar("wrapper", "--repo=${box.repo.path}").lf()
        val pinLine = if (release) "[created] okf-wrapper.properties\n" else ""
        val notice = if (release) "" else "${Launcher.noPinNotice(version)}\n"
        assertEquals(
            Run(
                0,
                "[created] okfw\n[created] okfw.cmd\n$pinLine[created] .gitattributes\n$notice${Launcher.CHMOD_NOTICE}\n" +
                    "okf wrapper OK: ${if (release) 4 else 3} file(s) written.\n",
                "",
            ),
            wrapper,
        )
        val bytes = allJar.readBytes()
        if (release) {
            assertEquals(box.pinFor(version, bytes), box.repo.resolve(Launcher.PIN).readText())
        } else {
            box.pin(box.pinFor(version, bytes))
        }
        box.seed(version, bytes)

        assertEquals(Run(0, "okf-cli $version\n", ""), okfw("version").lf())
        assertEquals(
            Run(
                0,
                "[created] okf.yaml\n[created] docs/index.md\n[kept] okfw\n[kept] okfw.cmd\n[kept] okf-wrapper.properties\n" +
                    "okf init OK: 2 file(s) created.\n",
                "",
            ),
            okfw("init", "--name=fixture").lf(),
        )
        assertTrue(box.repo.resolve("okf.yaml").readText().contains("index_command: ./okfw index\n"))
        val strict = arrayOf("check", "--strict", "--refs-strict", "--code-paths-required")
        assertEquals(Run(0, "okf check OK: 0 documents with frontmatter validated, 0 code references resolved.\n", ""), okfw(*strict).lf())
        // An absolute --repo in the form each shell passes it (MSYS must not rewrite it).
        assertEquals(0, okfw(*strict, "--repo=${with(box) { box.repo.shell }}").code)

        git("add", "-A")
        git("commit", "-q", "-m", "init")
        val base = git("rev-parse", "HEAD")
        box.repo.resolve("src").mkdirs()
        box.repo.resolve("src/App.kt").writeText("class App\n")
        assertEquals(
            Run(
                0,
                "okf impact: 0 documents with code_paths — nothing to match (1 changed files outside docs/ since merge-base ${base.take(7)} with ${base.take(7)}).\n",
                "",
            ),
            okfw("impact", "--base=$base").lf(),
        )
        assertEquals(Run(0, "", ""), okfw("index").lf())
        assertEquals(emptyList<List<String>>(), box.calls(), "no download")

        if (WINDOWS) {
            for (args in listOf("version", "check --strict --refs-strict --code-paths-required --repo=\"${box.repo.path}\"")) {
                val run = shim(args).lf()
                assertEquals(0, run.code, "okfw.cmd $args: $run")
                assertEquals("", run.err, "okfw.cmd $args")
            }
            assertEquals("okf-cli $version\n", shim("version").lf().out)
        }
    }

    /** An older okfw, shorter and longer than the embedded one, is replaced while `sh` runs it. */
    @Test
    fun `okfw wrapper refreshes an older okfw while that script runs`() {
        prepare()
        for ((name, older) in olderRevisions("okfw", "\n")) {
            box.repo.resolve("okfw").writeText(older)
            val run = okfw("wrapper").lf()
            assertEquals(0, run.code, "$name: $run")
            assertTrue(run.out.startsWith("[updated] okfw\n[unchanged] okfw.cmd\n"), "$name: ${run.out}")
            assertArrayEquals(launcherBytes("okfw"), box.repo.resolve("okfw").readBytes(), name)
            assertEquals(Run(0, "okf-cli ${version()}\n", ""), okfw("version").lf(), name)
        }
    }

    /** An older okfw.cmd, shorter and longer than the embedded one, is replaced while `cmd` runs it. */
    @Test
    fun `Windows - okfw cmd wrapper refreshes an older okfw cmd while cmd runs it`() {
        assumeTrue(WINDOWS, "okfw.cmd")
        prepare()
        for ((name, older) in olderRevisions("okfw.cmd", "\r\n")) {
            box.repo.resolve("okfw.cmd").writeText(older)
            val run = shim("wrapper").lf()
            assertEquals(0, run.code, "$name: $run")
            assertTrue(run.out.startsWith("[unchanged] okfw\n[updated] okfw.cmd\n"), "$name: ${run.out}")
            assertArrayEquals(launcherBytes("okfw.cmd"), box.repo.resolve("okfw.cmd").readBytes(), name)
            assertEquals("okf-cli ${version()}\n", shim("version").lf().out, name)
        }
    }

    /**
     * `okfw --update <this build's version>` with a real Java: the `curl` stub serves this build's jar and a dummy
     * bundle, the `gh` stub accepts them. The version tie runs the jar, the pin is written, and the jar's `okf wrapper`
     * replaces an older `okfw` (shorter) and `okfw.cmd` (longer) while they run: through `sh`, and on Windows through
     * `cmd /c okfw.cmd`, Windows PowerShell and pwsh.
     */
    @Test
    fun `okfw --update pins this build's jar and refreshes the launcher files while they run`() {
        val version = version()
        val sha = LauncherSandbox.sha256(allJar.readBytes())
        box.curl(serve = allJar, bundle = tmp.resolve("bundle.sigstore.json").apply { writeText("{}\n") })
        box.gh()
        assertEquals(0, jar("wrapper", "--repo=${box.repo.path}").code)
        val okfw = olderRevisions("okfw", "\n").single { it.first == "shorter" }.second
        val okfwCmd = olderRevisions("okfw.cmd", "\r\n").single { it.first == "longer" }.second
        val runs = buildList<Pair<String, () -> Run>> {
            add("sh" to { okfw("--update", version) })
            if (WINDOWS) {
                // The shim must take sh.exe from the PATH: Git's bin/sh.exe would put Git's own curl before the stub.
                box.stub.resolve("git.cmd").writeText("@echo off\r\necho not-an-exec-path\r\n")
                val env = box.environment(path = listOf(box.stub, box.toolbox, Host.usrBin) + git.path, env = env() + ("NoDefaultCurrentDirectoryInExePath" to null))
                val cmd = System.getenv("ComSpec") ?: "cmd.exe"
                val powershell = File(System.getenv("SystemRoot") ?: "C:\\Windows", "System32\\WindowsPowerShell\\v1.0\\powershell.exe")
                add("cmd /c" to { box.guard(env); Host.run(listOf(cmd, "/d", "/c", "okfw.cmd --update $version"), box.repo, env) })
                val ps = { exe: String -> Host.run(listOf(exe, "-NoProfile", "-NonInteractive", "-Command", "./okfw --update '$version'; exit \$LASTEXITCODE"), box.repo, env) }
                add("powershell" to { ps(powershell.path) })
                Host.onPath("pwsh.exe")?.let { pwsh -> add("pwsh" to { ps(pwsh.path) }) }
            }
        }
        for ((name, run) in runs) {
            box.repo.resolve(Launcher.PIN).delete()
            box.repo.resolve("okfw").writeText(okfw)
            box.repo.resolve("okfw.cmd").writeText(okfwCmd)
            box.cache.deleteRecursively()
            box.clearCalls()
            val result = run().lf()
            assertEquals(0, result.code, "$name: $result")
            assertTrue(
                result.out.startsWith(
                    "okfw: pinned okf-cli $version (sha256 $sha)\n[updated] okfw\n[updated] okfw.cmd\n[kept] ${Launcher.PIN}\n",
                ),
                "$name: ${result.out}",
            )
            assertArrayEquals(Launcher.pin(version, sha), box.repo.resolve(Launcher.PIN).readBytes(), name)
            assertArrayEquals(launcherBytes("okfw"), box.repo.resolve("okfw").readBytes(), name)
            assertArrayEquals(launcherBytes("okfw.cmd"), box.repo.resolve("okfw.cmd").readBytes(), name)
            assertEquals(listOf("gh", "curl", "curl", "gh"), box.calls().map { it.first() }, name)
            assertEquals(Run(0, "okf-cli $version\n", ""), okfw("version").lf(), name)
        }
    }

    /**
     * Two older revisions of the launcher file [name] that still carry the marker: one shorter (its third line, a
     * comment, dropped) and one longer (a comment line added before the last line).
     */
    private fun olderRevisions(name: String, lineEnd: String): List<Pair<String, String>> {
        val lines = String(launcherBytes(name), Charsets.US_ASCII).split(lineEnd)
        check(lines[2].startsWith("#") || lines[2].startsWith("rem")) { "line 3 of $name is a comment" }
        val shorter = (lines.take(2) + lines.drop(3)).joinToString(lineEnd)
        val comment = if (name == "okfw") "# an older revision" else "rem an older revision"
        val longer = (lines.dropLast(2) + comment + lines.takeLast(2)).joinToString(lineEnd)
        check(shorter.length < launcherBytes(name).size && longer.length > launcherBytes(name).size)
        return listOf("shorter" to shorter, "longer" to longer)
    }

    // --- helpers ---

    private fun version() = jar("version").lf().out.removePrefix("okf-cli ").trim()

    /** The launcher written by this jar, a pin for it and the seeded cache. */
    private fun prepare() {
        assertEquals(0, jar("wrapper", "--repo=${box.repo.path}").code)
        val bytes = allJar.readBytes()
        box.repo.resolve(Launcher.PIN).delete()
        box.pin(box.pinFor(version(), bytes))
        box.seed(version(), bytes)
    }

    private fun launcherBytes(name: String) = Launcher.files.single { it.name == name }.bytes

    /** The child environment of every run: the sandbox's, plus git's isolation and `JAVA_HOME` = the test JVM. */
    private fun env(): Map<String, String?> = mapOf("JAVA_HOME" to with(box) { javaHome.shell }) + git.env

    /** `java -jar <all jar> [args]` in the repo. */
    private fun jar(vararg args: String): Run =
        Host.run(listOf(javaHome.resolve("bin/java").path, "-jar", allJar.path) + args, box.repo, box.environment(path = sandboxPath(), env = env()))

    private fun okfw(vararg args: String): Run {
        val environment = box.environment(path = sandboxPath(), env = env())
        box.guard(environment)
        return Host.run(listOf(Host.sh.path, "./okfw") + args, box.repo, environment)
    }

    /** `cmd /c okfw.cmd [args]` (Windows): the shim finds `sh.exe` through `git --exec-path`, as it does for users. */
    private fun shim(args: String): Run =
        Host.run(
            listOf(System.getenv("ComSpec") ?: "cmd.exe", "/d", "/c", "okfw.cmd $args"),
            box.repo,
            box.environment(path = sandboxPath(), env = env() + ("NoDefaultCurrentDirectoryInExePath" to null)),
        )

    private fun sandboxPath() = listOf(box.stub, box.toolbox) + git.path
}
