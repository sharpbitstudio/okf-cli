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

/**
 * The launcher against a published release over the network (`./gradlew networkTest`, not part of `check`): real `sh`,
 * real `curl` (linked into the toolbox), real Java (the test JVM's, through `JAVA_HOME`), an empty cache. The release is
 * `1.0.0-alpha.1`, whose version and SHA-256 are public and immutable. GitHub tokens are removed from every child. The
 * `--update` and `--verify` cases also use the real `gh`, which must be 2.102.0 or later (the launcher's floor): where
 * the `gh` on the PATH is missing or older, they are skipped, except on GitHub Actions (`GITHUB_ACTIONS=true`), where
 * that fails them.
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

    /** alpha.1 has no `okf wrapper`: the pin is written, then the launcher reports that the launcher files were not. */
    @Test
    fun `okfw --update writes the pin of the release, then exits 3 because that release has no okf wrapper`() {
        realGh()
        box.launcher()
        val git = Host.run(listOf("git", "init", "-q"), box.repo, emptyMap())
        assertEquals(0, git.code, git.toString())
        box.toolbox(LauncherSandbox.BASE_TOOLS + Host.shaTool + "curl" + "gh")
        val run = box.okfw("--update", VERSION, env = env).lf()
        assertEquals(3, run.code, run.toString())
        assertEquals("okfw: pinned okf-cli $VERSION (sha256 $SHA256)\n", run.out)
        assertTrue(
            Regex("okfw: okf-cli ${Regex.escape(VERSION)} is pinned, but its launcher files could not be written \\(okf wrapper exited [0-9]+\\)\n")
                .containsMatchIn(run.err),
            run.err,
        )
        assertArrayEquals(Launcher.pin(VERSION, SHA256), box.repo.resolve(Launcher.PIN).readBytes())
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

    private companion object {
        const val VERSION = "1.0.0-alpha.1"
        const val SHA256 = "cadfdb7cb0d55e1b615e2662cca6cd48c554463f8c3e212753ceafea0303503b"
    }
}
