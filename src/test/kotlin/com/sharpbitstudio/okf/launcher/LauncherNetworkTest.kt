package com.sharpbitstudio.okf.launcher

import com.sharpbitstudio.okf.cli.Launcher
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files

/**
 * The launcher against a published release over the network (`./gradlew networkTest`, not part of `check`): real `sh`,
 * real `curl` (linked into the toolbox), real Java (the test JVM's, through `JAVA_HOME`), an empty cache. The release is
 * `1.0.0-rc.1`, whose version and SHA-256 are public and immutable; the `--update` case of a release without
 * `okf wrapper` uses `1.0.0-alpha.1`, likewise published. GitHub tokens are removed from every child. The `--update`
 * and `--verify` cases also use the real `gh`, which must be 2.102.0 or later (the launcher's floor): where the `gh` on
 * the PATH is missing or older, they are skipped, except on GitHub Actions (`GITHUB_ACTIONS=true`), where that fails
 * them. The full `--update` case also needs the real `git` on the PATH (the jar's `okf wrapper` runs it).
 */
@Tag("network")
class LauncherNetworkTest {
    @TempDir
    lateinit var tmp: File

    private val box by lazy { LauncherSandbox(tmp) }

    /** `JAVA_HOME` = the test JVM, and an empty gh configuration directory (the launcher sets its own anyway). */
    private val env by lazy {
        mapOf(
            "JAVA_HOME" to with(box) { File(System.getProperty("java.home")).shell },
            "GH_CONFIG_DIR" to with(box) { tmp.resolve("empty gh config").apply { mkdirs() }.shell },
            "GH_HOST" to null,
        )
    }

    /** An empty global git configuration (the system one is off): git's isolation in [git] and [okfwWithGit]. */
    private val globalConfig by lazy { tmp.resolve("gitconfig").apply { writeText("") } }

    @Test
    fun `an empty cache downloads the pinned release, which then runs offline`() {
        box.launcher()
        box.pin("version=$VERSION\nsha256=$SHA256\n")

        box.toolbox(LauncherSandbox.BASE_TOOLS + Host.shaTool + "curl")
        val first = box.okfw("version", env = env).lf()
        assertEquals(Run(0, "okf-cli $VERSION\n", "okfw: downloading okf-cli $VERSION\n"), first)
        assertEquals(SHA256, LauncherSandbox.sha256(box.cachedJar(VERSION).readBytes()))

        // Offline: curl now fails if called, and is not called.
        box.toolbox(LauncherSandbox.BASE_TOOLS + Host.shaTool)
        box.curl(serve = null)
        assertEquals(Run(0, "okf-cli $VERSION\n", ""), box.okfw("version", env = env).lf())
        assertEquals(emptyList<List<String>>(), box.calls())
    }

    @Test
    fun `okfw --verify checks the build provenance of the pinned release with the real gh`() {
        realGh()
        box.launcher()
        box.pin("version=$VERSION\nsha256=$SHA256\n")
        box.toolbox(LauncherSandbox.BASE_TOOLS + Host.shaTool + "curl" + "gh")
        val run = box.okfw("--verify", env = env).lf()
        assertEquals(0, run.code, run.toString())
        assertEquals("okfw: okf-cli $VERSION verified (built by .github/workflows/release.yml on refs/heads/main)\n", run.out)
    }

    /**
     * The whole `--update` in a git work tree with the real `git`: the pin is written, then the release's `okf wrapper`
     * writes its launcher files (the box holds this commit's, so each is unchanged or updated) and `.gitattributes`.
     * After a commit, a second `--update` of the same release finds it pinned and leaves the work tree clean.
     */
    @Test
    fun `okfw --update pins the release and writes its launcher files, and a second run changes nothing`() {
        realGh()
        box.launcher()
        val path = realGit()
        box.toolbox(LauncherSandbox.BASE_TOOLS + Host.shaTool + "curl" + "gh")

        val first = okfwWithGit(path, "--update", VERSION).lf()
        assertEquals(0, first.code, first.toString())
        assertTrue(
            Regex(
                "okfw: pinned okf-cli ${Regex.escape(VERSION)} \\(sha256 $SHA256\\)\n" +
                    "\\[(unchanged|updated)] okfw\n\\[(unchanged|updated)] okfw\\.cmd\n" +
                    "\\[kept] ${Regex.escape(Launcher.PIN)}\n\\[created] ${Regex.escape(Launcher.ATTRIBUTES)}\n" +
                    "${Regex.escape(Launcher.CHMOD_NOTICE)}\nokf wrapper OK: [0-9]+ file\\(s\\) written\\.\n",
            ).matches(first.out),
            first.out,
        )
        assertArrayEquals(Launcher.pin(VERSION, SHA256), box.repo.resolve(Launcher.PIN).readBytes())

        git("add", "-A")
        git("commit", "-q", "-m", "pin")
        val second = okfwWithGit(path, "--update", VERSION).lf()
        assertEquals(0, second.code, second.toString())
        assertEquals(
            "okfw: okf-cli $VERSION is already pinned\n[unchanged] okfw\n[unchanged] okfw.cmd\n[kept] ${Launcher.PIN}\n" +
                "${Launcher.CHMOD_NOTICE}\nokf wrapper OK: 0 file(s) written.\n",
            second.out,
        )
        assertArrayEquals(Launcher.pin(VERSION, SHA256), box.repo.resolve(Launcher.PIN).readBytes())
        assertEquals("", git("status", "--porcelain"))
    }

    /**
     * `1.0.0-alpha.1` has no `okf wrapper`: the pin is written, then the launcher reports that the launcher files were
     * not. The only check of that message against a published release.
     */
    @Test
    fun `okfw --update of a release without okf wrapper writes the pin, then exits 3`() {
        realGh()
        box.launcher()
        val init = Host.run(listOf("git", "init", "-q"), box.repo, emptyMap())
        assertEquals(0, init.code, init.toString())
        box.toolbox(LauncherSandbox.BASE_TOOLS + Host.shaTool + "curl" + "gh")
        val run = box.okfw("--update", ALPHA_VERSION, env = env).lf()
        assertEquals(3, run.code, run.toString())
        assertEquals("okfw: pinned okf-cli $ALPHA_VERSION (sha256 $ALPHA_SHA256)\n", run.out)
        assertTrue(
            Regex("okfw: okf-cli ${Regex.escape(ALPHA_VERSION)} is pinned, but its launcher files could not be written \\(okf wrapper exited [0-9]+\\)\n")
                .containsMatchIn(run.err),
            run.err,
        )
        assertArrayEquals(Launcher.pin(ALPHA_VERSION, ALPHA_SHA256), box.repo.resolve(Launcher.PIN).readBytes())
    }

    @Test
    fun `okfw --update fails the provenance check of a download changed by one byte, the pin unchanged`() {
        realGh()
        box.launcher()
        val pin = "version=$VERSION\nsha256=${"0".repeat(64)}\n"
        box.pin(pin)
        val curl = with(box) { checkNotNull(Host.tool("curl")) { "this machine has no curl" }.shell }
        box.stub(
            "curl",
            "for a in \"\$@\"; do url=\$a; done\n" +
                "out=\nfor a in \"\$@\"; do if [ \"\$prev\" = -o ]; then out=\$a; fi; prev=\$a; done\n" +
                "${LauncherSandbox.quote(curl)} \"\$@\" || exit \$?\n" +
                "case \$url in *.jar) printf x >> \"\$out\" ;; esac\n",
        )
        box.toolbox(LauncherSandbox.BASE_TOOLS + Host.shaTool + "gh")
        val run = box.okfw("--update", VERSION, env = env).lf()
        assertEquals(3, run.code, run.toString())
        assertEquals("", run.out)
        // gh got as far as the signature check (the untampered jar passes the same policy in the --verify case): not a
        // rejected bundle file name or flag. gh 2.104.0 reports a changed artifact as `Error: verifying with issuer …`.
        for (other in listOf("bundle file extension", "unknown flag", "Usage:", "if any flags in the group")) {
            assertFalse(run.err.contains(other), "gh failed for another reason ($other): ${run.err}")
        }
        assertTrue(run.err.contains("Error: verifying with issuer"), run.err)
        assertTrue(
            Regex(
                "okfw: the build provenance of okf-cli-${Regex.escape(VERSION)}-all\\.jar could not be verified " +
                    "\\(gh attestation verify exited [0-9]+\\); the pin was not changed\n$",
            ).containsMatchIn(run.err),
            run.err,
        )
        assertEquals(pin, box.repo.resolve(Launcher.PIN).readText())
    }

    /**
     * Skips the test unless the `gh` on this machine's PATH is 2.102.0 or later. On GitHub Actions (`GITHUB_ACTIONS=true`
     * in the test JVM), whose hosted runners ship a newer `gh`, a missing or older one fails the test instead.
     */
    private fun realGh() {
        val ci = System.getenv("GITHUB_ACTIONS") == "true"
        val assume = { condition: Boolean, message: String -> if (ci) assertTrue(condition, message) else assumeTrue(condition, message) }
        val gh = Host.tool("gh")
        assume(gh != null, "no gh on the PATH")
        val run = Host.run(
            listOf(gh!!.path, "--version"),
            tmp,
            LauncherSandbox.CLEARED.filter { it != "PATH" }.associateWith<String, String?> { null } +
                mapOf("GH_CONFIG_DIR" to tmp.resolve("gh probe").apply { mkdirs() }.path, "GH_NO_UPDATE_NOTIFIER" to "1"),
        )
        val parts = Regex("^gh version ([0-9]+)\\.([0-9]+)\\.([0-9]+)").find(run.out)?.groupValues?.drop(1)?.map { it.toInt() }
        val atFloor = parts != null && (parts[0] > 2 || parts[0] == 2 && parts[1] >= 102)
        assume(atFloor, "${run.out.lineSequence().first()} is below the launcher's floor 2.102.0")
    }

    // --- git, as in LauncherJarTest ---

    /**
     * Makes the repo a git work tree with an isolated commit identity and returns the `PATH` of [okfwWithGit]: the stub
     * directory and the toolbox, plus `git` for the jar's own git calls (a link in the stub directory on POSIX, the
     * directory of `git.exe` on Windows).
     */
    private fun realGit(): List<File> {
        val gitPath = if (WINDOWS) {
            listOf(checkNotNull(onPath("git.exe")) { "git.exe on the PATH" }.parentFile)
        } else {
            Files.createSymbolicLink(box.stub.resolve("git").toPath(), checkNotNull(onPath("git")) { "git on the PATH" }.toPath())
            emptyList()
        }
        git("init", "-q", "-b", "main")
        return listOf(box.stub, box.toolbox) + gitPath
    }

    /** `sh ./okfw [args]` in the repo, after the PATH guard, with [path] as the `PATH` and git's isolation. */
    private fun okfwWithGit(path: List<File>, vararg args: String): Run {
        val environment = box.environment(path = path, env = env + mapOf("GIT_CONFIG_GLOBAL" to globalConfig.path, "GIT_CONFIG_NOSYSTEM" to "1"))
        box.guard(environment)
        return Host.run(listOf(Host.sh.path, "./okfw") + args, box.repo, environment)
    }

    private fun git(vararg args: String): String {
        val run = Host.run(
            listOf("git") + args,
            box.repo,
            mapOf("GIT_CONFIG_GLOBAL" to globalConfig.path, "GIT_CONFIG_NOSYSTEM" to "1"),
        )
        check(run.code == 0) { "git ${args.joinToString(" ")}: $run" }
        if (args.first() == "init") {
            listOf("user.name" to "t", "user.email" to "t@t", "commit.gpgsign" to "false", "core.autocrlf" to "false")
                .forEach { (key, value) -> git("config", key, value) }
        }
        return run.out.trim()
    }

    private fun onPath(name: String): File? =
        System.getenv("PATH").orEmpty().split(File.pathSeparator).filter { it.isNotEmpty() }.map { File(it, name) }.firstOrNull { it.isFile }

    private companion object {
        const val VERSION = "1.0.0-rc.1"
        const val SHA256 = "5ce703dada54b6a802ce2309ffc13aa8cffb456e7f4d27dbe20be0b7848d6983"

        /** A published release without `okf wrapper`. */
        const val ALPHA_VERSION = "1.0.0-alpha.1"
        const val ALPHA_SHA256 = "cadfdb7cb0d55e1b615e2662cca6cd48c554463f8c3e212753ceafea0303503b"
    }
}
