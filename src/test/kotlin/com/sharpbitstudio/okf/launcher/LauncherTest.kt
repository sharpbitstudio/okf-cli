package com.sharpbitstudio.okf.launcher

import com.sharpbitstudio.okf.cli.Launcher
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * `launcher/okfw` (the copy `okf wrapper` writes) run by a real `sh` against stubs: `curl` and `java` are `sh` scripts
 * that log their argv, the cache is seeded with fake jars named by generated pins, and the PATH holds nothing else but a
 * toolbox of the tools the script may use (see [LauncherSandbox]).
 */
class LauncherTest {
    @TempDir
    lateinit var tempDir: File

    /** [tempDir] by its long name on Windows ([Host.longName]): every shell then prints the paths in one form. */
    private val tmp: File by lazy { Host.longName(tempDir) }

    private lateinit var box: LauncherSandbox

    @BeforeEach
    fun setUp() {
        box = LauncherSandbox(tmp)
        box.launcher()
        box.curl(serve = null)
        box.java()
    }

    private val jarBytes = "not really a jar\n".toByteArray()

    private fun jar(version: String = VERSION) = box.cachedJar(version).let { with(box) { it.shell } }

    /** A valid pin for [jarBytes], seeded into the cache. */
    private fun ready(version: String = VERSION) {
        box.pin(box.pinFor(version, jarBytes))
        box.seed(version, jarBytes)
    }

    private fun javaCall(vararg args: String, version: String = VERSION, tag: String = "java") = listOf(tag, "-jar", jar(version)) + args

    // --- pin file ---

    @Test
    fun `pin - every accepted form runs the pinned jar`() {
        val sha = LauncherSandbox.sha256(jarBytes)
        val bom = "\u00EF\u00BB\u00BF"
        val forms = mapOf(
            "writer bytes" to box.pinFor(VERSION, jarBytes),
            "CRLF" to "# c\r\nversion=$VERSION\r\nsha256=$sha\r\n",
            "BOM" to "${bom}version=$VERSION\nsha256=$sha\n",
            "BOM and CRLF" to "$bom# c\r\nversion=$VERSION\r\nsha256=$sha\r\n",
            "trailing blanks, blank lines, no final line end" to "\n  \t\n# c \nsha256=$sha \t \r\n\nversion=$VERSION\t",
            "uppercase hex" to "version=$VERSION\nsha256=${sha.uppercase()}\n",
            "pre-release version" to null,
        )
        box.seed(VERSION, jarBytes)
        for ((name, text) in forms) {
            box.clearCalls()
            if (text == null) {
                box.pin("version=1.0.0-rc.1\nsha256=$sha\n")
                box.seed("1.0.0-rc.1", jarBytes)
            } else {
                box.pin(text)
            }
            val run = box.okfw("check")
            val version = if (text == null) "1.0.0-rc.1" else VERSION
            assertEquals(Run(0, "jar output\n", ""), run, name)
            assertEquals(listOf(javaCall("check", version = version)), box.calls(), name)
        }
    }

    @Test
    fun `pin - malformed pins exit 2 with the exact message and run nothing`() {
        val sha = LauncherSandbox.sha256(jarBytes)
        val path = "./okf-wrapper.properties"
        val cases = listOf(
            "version = $VERSION\nsha256=$sha\n" to "$path: line 1 is not key=value",
            "# c\n\njust text\n" to "$path: line 3 is not key=value",
            "=x\n" to "$path: line 1 is not key=value",
            " version=$VERSION\n" to "$path: line 1 is not key=value",
            "version= $VERSION\n" to "$path: line 1 is not key=value",
            "ver\tsion=$VERSION\n" to "$path: line 1 is not key=value",
            "  # an indented comment\n" to "$path: line 1 is not key=value",
            "version=$VERSION\nsha256=$sha\nchannel=stable\n" to "$path: unknown key 'channel'",
            "version=$VERSION\nversion=$VERSION\nsha256=$sha\n" to "$path: 'version' is given more than once",
            "sha256=$sha\nversion=$VERSION\nsha256=$sha\n" to "$path: 'sha256' is given more than once",
            "" to "$path: 'version' is missing",
            "sha256=$sha\n" to "$path: 'version' is missing",
            "version=$VERSION\n" to "$path: 'sha256' is missing",
            "version=v$VERSION\nsha256=$sha\n" to "$path: version 'v$VERSION' is not a version such as 1.2.3 or 1.2.3-rc.1",
            "version=1.2.3+build.5\nsha256=$sha\n" to "$path: version '1.2.3+build.5' is not a version such as 1.2.3 or 1.2.3-rc.1",
            "version=01.2.3\nsha256=$sha\n" to "$path: version '01.2.3' is not a version such as 1.2.3 or 1.2.3-rc.1",
            "version=1.2\nsha256=$sha\n" to "$path: version '1.2' is not a version such as 1.2.3 or 1.2.3-rc.1",
            "version=1.2.3-rc..1\nsha256=$sha\n" to "$path: version '1.2.3-rc..1' is not a version such as 1.2.3 or 1.2.3-rc.1",
            "version=\nsha256=$sha\n" to "$path: version '' is not a version such as 1.2.3 or 1.2.3-rc.1",
            "version=$VERSION\nsha256=${sha.drop(1)}\n" to "$path: sha256 must be 64 hexadecimal digits",
            "version=$VERSION\nsha256=${sha.dropLast(1)}g\n" to "$path: sha256 must be 64 hexadecimal digits",
            "version=$VERSION\nsha256=\n" to "$path: sha256 must be 64 hexadecimal digits",
            // Untrusted values are echoed only as printable ASCII of at most 64 characters.
            "# c\nk\u0001ey=x\n" to "$path: line 2: unknown key",
            "${"k".repeat(65)}=x\n" to "$path: line 1: unknown key",
            "${"k".repeat(64)}=x\n" to "$path: unknown key '${"k".repeat(64)}'",
            "sha256=$sha\nversion=1.2.3-\u001b[31mred\n" to "$path: line 2: version is not a version such as 1.2.3 or 1.2.3-rc.1",
            "version=1.2.3-${"x".repeat(60)}\u00e9\nsha256=$sha\n" to "$path: line 1: version is not a version such as 1.2.3 or 1.2.3-rc.1",
            "version=1.2.3-${"x".repeat(60)}+\nsha256=$sha\n" to "$path: line 1: version is not a version such as 1.2.3 or 1.2.3-rc.1",
        )
        box.seed(VERSION, jarBytes)
        for ((text, message) in cases) {
            box.clearCalls()
            box.pin(text)
            assertEquals(Run(2, "", "okfw: $message\n"), box.okfw("check"), text)
            assertEquals(emptyList<List<String>>(), box.calls(), text)
        }
    }

    @Test
    fun `pin - a missing pin file names the path it looked for`() {
        assertEquals(
            Run(2, "", "okfw: pin file not found: ./okf-wrapper.properties — run okfw --update <version>\n"),
            box.okfw("check"),
        )
        val elsewhere = tmp.resolve("other dir/my pin.properties")
        val shown = with(box) { elsewhere.shell }
        assertEquals(
            Run(2, "", "okfw: pin file not found: $shown — run okfw --update <version>\n"),
            box.okfw("check", env = mapOf("OKFW_PIN" to shown)),
        )
        assertEquals(emptyList<List<String>>(), box.calls())
    }

    @Test
    fun `pin - a NUL byte anywhere makes it no text file`() {
        val sha = LauncherSandbox.sha256(jarBytes)
        box.seed(VERSION, jarBytes)
        val cases = listOf(
            "in a key" to "ver\u0000sion=$VERSION\nsha256=$sha\n",
            "in a value" to "version=$VERSION\nsha256=${sha.take(10)}\u0000${sha.drop(10)}\n",
            "in a comment" to "# \u0000\nversion=$VERSION\nsha256=$sha\n",
        )
        for ((name, text) in cases) {
            box.pin(text)
            assertEquals(
                Run(2, "", "okfw: ./okf-wrapper.properties: is not a text file (it contains a NUL byte)\n"),
                box.okfw("check"),
                name,
            )
        }
        assertEquals(emptyList<List<String>>(), box.calls())
    }

    /** A byte that is no UTF-8 (0xE9, Latin-1 `é`) must not trip the tools, whatever the locale of the child. */
    @Test
    fun `pin - a Latin-1 byte in a comment is fine, in the version it is not a version`() {
        val sha = LauncherSandbox.sha256(jarBytes)
        box.seed(VERSION, jarBytes)
        // A UTF-8 locale through LC_ALL, and through LANG alone (LC_ALL and LC_CTYPE unset).
        val locales = listOf(
            mapOf("LANG" to "en_US.UTF-8", "LC_ALL" to "en_US.UTF-8"),
            mapOf("LANG" to "en_US.UTF-8", "LC_ALL" to null, "LC_CTYPE" to null),
        )
        for (utf8 in locales) {
            box.pin("# caf\u00e9\nversion=$VERSION\nsha256=$sha\n")
            assertEquals(Run(0, "jar output\n", ""), box.okfw("check", env = utf8), utf8.toString())
            box.pin("# c\nversion=1.2.3-caf\u00e9\nsha256=$sha\n")
            assertEquals(
                Run(2, "", "okfw: ./okf-wrapper.properties: line 2: version is not a version such as 1.2.3 or 1.2.3-rc.1\n"),
                box.okfw("check", env = utf8),
                utf8.toString(),
            )
        }
    }

    @Test
    fun `OKFW_PIN names a pin elsewhere, used instead of the one next to the script`() {
        box.pin("version=9.9.9\nsha256=${"0".repeat(64)}\n")
        val elsewhere = tmp.resolve("other dir/my pin.properties")
        box.pin(box.pinFor("2.0.0", jarBytes), elsewhere)
        box.seed("2.0.0", jarBytes)
        val run = box.okfw("index", env = mapOf("OKFW_PIN" to with(box) { elsewhere.shell }))
        assertEquals(Run(0, "jar output\n", ""), run)
        assertEquals(listOf(javaCall("index", version = "2.0.0")), box.calls())
    }

    @Test
    fun `--update and --verify are launcher options only as the first argument`() {
        ready()
        assertEquals(Run(0, "jar output\n", ""), box.okfw("check", "--update", "--verify"))
        assertEquals(listOf(javaCall("check", "--update", "--verify")), box.calls())
    }

    // --- okfw --update ---

    /** The jar `--update` downloads: another than the one [ready] seeds. */
    private val releaseJar = "the release jar\n".toByteArray()
    private val releaseSha = LauncherSandbox.sha256(releaseJar)
    private val bundleBytes = "{\"mediaType\":\"a Sigstore bundle\"}\n".toByteArray()

    /** GitHub tokens, a host and a configuration directory of the caller's: none of them may reach `gh`. */
    private val callerGhEnv by lazy {
        mapOf(
            "GH_TOKEN" to "token-1", "GITHUB_TOKEN" to "token-2", "GH_ENTERPRISE_TOKEN" to "token-3",
            "GITHUB_ENTERPRISE_TOKEN" to "token-4", "GH_HOST" to "example.invalid",
            "GH_CONFIG_DIR" to with(box) { tmp.resolve("caller gh").apply { mkdirs() }.shell },
        )
    }

    /** `curl` serves [releaseJar] and [bundleBytes] (or fails for the bundle), `java` reports [jarVersion], `gh` exits [ghExit]. */
    private fun release(
        jarVersion: String = "okf-cli $VERSION\n",
        ghExit: Int = 0,
        bundle: Boolean = true,
        wrapperExit: Int = 0,
        jarVersionExit: Int = 0,
    ) {
        box.curl(
            serve = tmp.resolve("served.jar").apply { writeBytes(releaseJar) },
            bundle = if (bundle) tmp.resolve("served.sigstore.json").apply { writeBytes(bundleBytes) } else null,
        )
        box.java(jarVersion = jarVersion, exitCode = wrapperExit, jarVersionExit = jarVersionExit)
        box.gh(exitCode = ghExit)
    }

    private fun curlCall(target: String, asset: String) = listOf(
        "curl", "-q", "--fail", "--silent", "--show-error", "--location", "--proto", "=https", "--proto-redir", "=https",
        "--tlsv1.2", "--retry", "3", "-o", target, "https://github.com/sharpbitstudio/okf-cli/releases/download/v$VERSION/$asset",
    )

    /** The provenance policy, as `gh` must receive it. */
    private fun policy(jar: String, bundle: String) = listOf(
        "gh", "attestation", "verify", jar, "--bundle", bundle, "--repo", "sharpbitstudio/okf-cli",
        "--predicate-type", "https://slsa.dev/provenance/v1",
        "--cert-identity", "https://github.com/sharpbitstudio/okf-cli/.github/workflows/release.yml@refs/heads/main",
        "--source-ref", "refs/heads/main", "--deny-self-hosted-runners",
    )

    private val cacheDir get() = with(box) { box.cache.shell } + "/okf-cli/$VERSION"
    private val pinFile get() = box.repo.resolve(Launcher.PIN)

    /**
     * Each `gh` call saw no token and no host, the two settings that keep it quiet, and its own new empty configuration
     * directory in the cache, which is gone afterwards.
     */
    private fun assertGhIsolated(calls: Int) {
        val seen = box.ghEnv()
        assertEquals(calls, seen.size, seen.toString())
        val configs = seen.map { env ->
            val config = env.single { it.startsWith("GH_CONFIG_DIR=") }.removePrefix("GH_CONFIG_DIR=")
            assertEquals(
                listOf(
                    "GH_TOKEN unset", "GITHUB_TOKEN unset", "GH_ENTERPRISE_TOKEN unset", "GITHUB_ENTERPRISE_TOKEN unset",
                    "GH_HOST unset", "GH_CONFIG_DIR=$config", "GH_NO_UPDATE_NOTIFIER=1", "GH_PROMPT_DISABLED=1", "config=empty",
                ),
                env,
            )
            assertTrue(Regex(Regex.escape("$cacheDir/.gh-config.") + "[A-Za-z0-9]{6}").matches(config), config)
            assertFalse(File(config).exists(), "$config is removed")
            config
        }
        assertEquals(configs.distinct(), configs, "a new directory per call")
    }

    /** The bundle goes under the asset's own name (gh needs `.json`) into a new temporary directory in the cache. */
    private fun assertBundlePath(bundle: String) {
        val name = "okf-cli-$VERSION-all.jar.sigstore.json"
        assertTrue(bundle.endsWith(".sigstore.json"), bundle)
        assertTrue(Regex(Regex.escape("$cacheDir/.$name.") + "[A-Za-z0-9]{6}/" + Regex.escape(name)).matches(bundle), bundle)
    }

    private fun assertCacheHoldsOnly(vararg names: String) {
        assertEquals(names.toList(), box.cachedJar(VERSION).parentFile.list().orEmpty().sorted(), "cache directory")
    }

    @Test
    fun `--update - downloads, verifies provenance and version, pins and refreshes the launcher (LF and CRLF version output)`() {
        for (lineEnd in listOf("\n", "\r\n")) {
            box.clearCalls()
            box.cache.deleteRecursively()
            box.pin(box.pinFor("1.0.0", jarBytes))
            release(jarVersion = "okf-cli $VERSION$lineEnd")
            val run = box.okfw("--update", VERSION, env = callerGhEnv)
            assertEquals(
                Run(
                    0,
                    "okfw: pinned okf-cli $VERSION (sha256 $releaseSha)\njar output\n",
                    "okfw: downloading okf-cli $VERSION\ngh stdout\ngh stderr\n",
                ),
                run,
                lineEnd,
            )
            val calls = box.calls()
            assertEquals(listOf("gh", "curl", "curl", "gh", "java", "java"), calls.map { it.first() }, lineEnd)
            assertEquals(listOf("gh", "--version"), calls[0])
            val jarTmp = calls[1][14]
            val bundleTmp = calls[2][14]
            assertEquals(curlCall(jarTmp, "okf-cli-$VERSION-all.jar"), calls[1])
            assertEquals(curlCall(bundleTmp, "okf-cli-$VERSION-all.jar.sigstore.json"), calls[2])
            assertTrue(Regex(Regex.escape("$cacheDir/.okf-cli-$VERSION-all.jar.") + "[A-Za-z0-9]{6}").matches(jarTmp), jarTmp)
            assertBundlePath(bundleTmp)
            assertEquals(policy(jarTmp, bundleTmp), calls[3])
            assertEquals(listOf("java", "-jar", jarTmp, "version"), calls[4], "the version tie runs the verified download")
            assertEquals(javaCall("wrapper", "--repo=."), calls[5], "okf wrapper of the new jar, for the pin's directory")

            assertArrayEquals(Launcher.pin(VERSION, releaseSha), pinFile.readBytes(), "the bytes okf-cli writes")
            assertCacheHoldsOnly("okf-cli-$VERSION-all.jar")
            assertArrayEquals(releaseJar, box.cachedJar(VERSION).readBytes())
            assertEquals(listOf(Launcher.PIN, "okfw", "okfw.cmd"), box.repo.list()!!.sorted(), "no temporary pin left")
            assertGhIsolated(calls = 2)
        }
    }

    @Test
    fun `--update - the new pin runs`() {
        release()
        assertEquals(0, box.okfw("--update", VERSION).code)
        box.clearCalls()
        box.java()
        assertEquals(Run(0, "jar output\n", ""), box.okfw("check"))
        assertEquals(listOf(javaCall("check")), box.calls())
    }

    @Test
    fun `--update - a pin that already names the version and its SHA-256 is kept as it is`() {
        val handWritten = "version=$VERSION\r\nsha256=${releaseSha.uppercase()}\r\n"
        box.pin(handWritten)
        release()
        assertEquals(
            Run(0, "okfw: okf-cli $VERSION is already pinned\njar output\n", "okfw: downloading okf-cli $VERSION\ngh stdout\ngh stderr\n"),
            box.okfw("--update", VERSION),
        )
        assertEquals(handWritten, pinFile.readText(Charsets.ISO_8859_1))
        assertEquals(javaCall("wrapper", "--repo=."), box.calls().last(), "the launcher files are refreshed all the same")
    }

    @Test
    fun `--update - a pin of the same version with another SHA-256, a malformed pin or none is rewritten`() {
        val before = listOf(
            "same version, other SHA-256" to box.pinFor(VERSION, jarBytes),
            "malformed" to "version=$VERSION\nsha256=xyz\n",
            "none" to null,
        )
        for ((name, text) in before) {
            if (text == null) pinFile.delete() else box.pin(text)
            release()
            assertEquals(Run(0, "okfw: pinned okf-cli $VERSION (sha256 $releaseSha)\njar output\n", "gh stdout\ngh stderr\n"), box.okfw("--update", VERSION).let {
                it.copy(err = it.err.removePrefix("okfw: downloading okf-cli $VERSION\n"))
            }, name)
            assertArrayEquals(Launcher.pin(VERSION, releaseSha), pinFile.readBytes(), name)
        }
    }

    @Test
    fun `--update - usage errors exit 2 before anything runs, the pin unchanged`() {
        val pin = box.pinFor("1.0.0", jarBytes)
        box.pin(pin)
        release()
        val needsOne = "okfw: --update needs one version, such as okfw --update 1.2.3 (releases: https://github.com/sharpbitstudio/okf-cli/releases)\n"
        val notAVersion = { v: String -> "okfw: '$v' is not a version such as 1.2.3 or 1.2.3-rc.1\n" }
        val unprintable = "okfw: --update: the argument is not a version such as 1.2.3 or 1.2.3-rc.1\n"
        val cases = buildList {
            add(listOf("--update") to needsOne)
            add(listOf("--update", VERSION, "extra") to needsOne)
            add(listOf("--update", "", VERSION) to needsOne)
            add(listOf("--update", "v$VERSION") to notAVersion("v$VERSION"))
            add(listOf("--update", "") to notAVersion(""))
            add(listOf("--update", "1.2") to notAVersion("1.2"))
            add(listOf("--update", "1.2.3+build.5") to notAVersion("1.2.3+build.5"))
            add(listOf("--update", "1.2.3/../x") to notAVersion("1.2.3/../x"))
            add(listOf("--update", "01.2.3") to notAVersion("01.2.3"))
            add(listOf("--update", "1.2.3_${"x".repeat(60)}") to unprintable)
            add(listOf("--update", "1.2.3-café") to unprintable)
            // Each line is a version, so a line-by-line grep alone would pass it. (Windows splits an argument at a newline.)
            if (!WINDOWS) add(listOf("--update", "1.2.3\n1.2.4") to unprintable)
        }
        for ((args, message) in cases) {
            assertEquals(Run(2, "", message), box.okfw(*args.toTypedArray()), args.toString())
        }
        // Set, even to an empty value.
        for (value in listOf(with(box) { pinFile.shell }, "")) {
            assertEquals(
                Run(2, "", "okfw: --update cannot be used with OKFW_PIN set\n"),
                box.okfw("--update", VERSION, env = mapOf("OKFW_PIN" to value)),
                "OKFW_PIN='$value'",
            )
        }
        assertEquals(emptyList<List<String>>(), box.calls())
        assertEquals(pin, pinFile.readText())
        assertFalse(box.cache.exists(), "nothing written to the cache")
    }

    @Test
    fun `--update - gh missing, too old or of an unknown version exits 3, the pin unchanged`() {
        val pin = box.pinFor("1.0.0", jarBytes)
        box.pin(pin)
        release()
        box.stub.resolve("gh").delete()
        assertEquals(
            Run(3, "", "okfw: gh not found — --update and --verify check the build provenance with the GitHub CLI 2.102.0 or later (https://cli.github.com)\n"),
            box.okfw("--update", VERSION),
        )
        val tooOld = { v: String -> "okfw: gh $v is too old — 2.102.0 or later is required\n" }
        val unknown = "okfw: gh (unknown version) is too old — 2.102.0 or later is required\n"
        val cases = listOf(
            "gh version 2.101.0 (2026-09-01)\n" to tooOld("2.101.0"),
            "gh version 2.96.0 (2026-07-02)\nhttps://github.com/cli/cli/releases/tag/v2.96.0\n" to tooOld("2.96.0"),
            "gh version 1.999.9 (2020-01-01)\n" to tooOld("1.999.9"),
            "gh version 2.102.0-rc.1 (2026-09-20)\n" to tooOld("2.102.0-rc.1"),
            "gh version DEV\n" to unknown,
            "gh version 2.102 (2026-09-30)\n" to unknown,
            "GitHub CLI 2.102.0\n" to unknown,
            "\ngh version 2.102.0 (2026-09-30)\n" to unknown,
            "gh version 2.102.0\u001b[0m (2026-09-30)\n" to unknown,
            "" to unknown,
        )
        for ((text, message) in cases) {
            box.clearCalls()
            box.gh(version = text)
            assertEquals(Run(3, "", message), box.okfw("--update", VERSION), text)
            assertEquals(listOf(listOf("gh", "--version")), box.calls(), text)
        }
        assertEquals(pin, pinFile.readText())
        assertCacheHoldsOnly()
    }

    @Test
    fun `--update - gh at or above the floor is used`() {
        for (version in listOf("2.102.0", "2.102.1", "2.103.3", "2.110.0", "3.0.0", "10.0.0", "2.103.0-rc.1")) {
            box.clearCalls()
            pinFile.delete()
            release()
            box.gh(version = "gh version $version (2026-11-01)\r\n")
            val run = box.okfw("--update", VERSION)
            assertEquals(0, run.code, "$version: $run")
        }
    }

    @Test
    fun `--update - provenance that gh rejects exits 3, the pin unchanged, nothing left in the cache`() {
        val pin = box.pinFor("1.0.0", jarBytes)
        box.pin(pin)
        release(ghExit = 1)
        assertEquals(
            Run(
                3,
                "",
                "okfw: downloading okf-cli $VERSION\ngh stdout\ngh stderr\n" +
                    "okfw: the build provenance of okf-cli-$VERSION-all.jar could not be verified (gh attestation verify exited 1); the pin was not changed\n",
            ),
            box.okfw("--update", VERSION, env = callerGhEnv),
        )
        assertEquals(listOf("gh", "curl", "curl", "gh"), box.calls().map { it.first() })
        assertEquals(pin, pinFile.readText())
        assertCacheHoldsOnly()
        assertGhIsolated(calls = 2)
    }

    @Test
    fun `--update - a jar that does not report the version asked for exits 3, the pin unchanged`() {
        val pin = box.pinFor("1.0.0", jarBytes)
        val unchanged = "the pin was not changed"
        val cases = listOf(
            "okf-cli 1.2.2\n" to "okfw: the verified jar reports 'okf-cli 1.2.2', not 'okf-cli $VERSION'; $unchanged",
            "okf-cli $VERSION-rc.1\r\n" to "okfw: the verified jar reports 'okf-cli $VERSION-rc.1', not 'okf-cli $VERSION'; $unchanged",
            "\n" to "okfw: the verified jar reports '', not 'okf-cli $VERSION'; $unchanged",
            "okf-cli $VERSION\r\r\n" to "okfw: the verified jar does not report 'okf-cli $VERSION'; $unchanged",
            "okf-cli $VERSION\n\n" to "okfw: the verified jar does not report 'okf-cli $VERSION'; $unchanged",
            "okf-cli $VERSION\nokf-cli $VERSION\n" to "okfw: the verified jar does not report 'okf-cli $VERSION'; $unchanged",
            "\u001b[31mokf-cli $VERSION\n" to "okfw: the verified jar does not report 'okf-cli $VERSION'; $unchanged",
            "okf-cli ${"9".repeat(60)}\n" to "okfw: the verified jar does not report 'okf-cli $VERSION'; $unchanged",
        )
        for ((output, message) in cases) {
            box.clearCalls()
            box.pin(pin)
            release(jarVersion = output)
            val run = box.okfw("--update", VERSION)
            assertEquals(Run(3, "", "okfw: downloading okf-cli $VERSION\ngh stdout\ngh stderr\n$message\n"), run, output)
            assertEquals(listOf("gh", "curl", "curl", "gh", "java"), box.calls().map { it.first() }, output)
            assertEquals(pin, pinFile.readText(), output)
            assertCacheHoldsOnly()
        }
    }

    @Test
    fun `--update - a bundle that cannot be downloaded exits 3, the pin unchanged`() {
        val pin = box.pinFor("1.0.0", jarBytes)
        box.pin(pin)
        release(bundle = false)
        val url = "https://github.com/sharpbitstudio/okf-cli/releases/download/v$VERSION/okf-cli-$VERSION-all.jar.sigstore.json"
        assertEquals(
            Run(3, "", "okfw: downloading okf-cli $VERSION\ncurl: (22) The requested URL returned error: 404\nokfw: download failed: $url\n"),
            box.okfw("--update", VERSION),
        )
        assertEquals(listOf("gh", "curl", "curl"), box.calls().map { it.first() })
        assertEquals(pin, pinFile.readText())
        assertCacheHoldsOnly()
    }

    @Test
    fun `--update - the version tie accepts no line end, and rejects a jar that exits non-zero`() {
        release(jarVersion = "okf-cli $VERSION")
        assertEquals(0, box.okfw("--update", VERSION).code)
        assertArrayEquals(Launcher.pin(VERSION, releaseSha), pinFile.readBytes())

        val pin = box.pinFor("1.0.0", jarBytes)
        box.pin(pin)
        box.clearCalls()
        box.cache.deleteRecursively()
        release(jarVersionExit = 1)
        assertEquals(
            Run(
                3,
                "",
                "okfw: downloading okf-cli $VERSION\ngh stdout\ngh stderr\n" +
                    "okfw: the verified jar does not report 'okf-cli $VERSION'; the pin was not changed\n",
            ),
            box.okfw("--update", VERSION),
        )
        assertEquals(listOf("gh", "curl", "curl", "gh", "java"), box.calls().map { it.first() })
        assertEquals(pin, pinFile.readText())
        assertCacheHoldsOnly()
    }

    @Test
    fun `--update - a directory at the pin path is left alone - exit 3`() {
        pinFile.mkdirs()
        pinFile.resolve("inside").writeText("x")
        release()
        assertEquals(
            Run(
                3,
                "",
                "okfw: downloading okf-cli $VERSION\ngh stdout\ngh stderr\n" +
                    "okfw: cannot write the pin: ./okf-wrapper.properties is not a file\n",
            ),
            box.okfw("--update", VERSION),
        )
        assertEquals(listOf("inside"), pinFile.list()!!.toList())
        assertEquals(listOf("gh", "curl", "curl", "gh", "java"), box.calls().map { it.first() }, "okf wrapper does not run")
        assertEquals(listOf(Launcher.PIN, "okfw", "okfw.cmd"), box.repo.list()!!.sorted())
    }

    /**
     * Any symbolic link at the pin path is refused before the pin is read: a dangling one, one to another pin, and one to
     * exactly the pin `--update` would write (never "already pinned"). `okf wrapper` does not run.
     */
    @Test
    fun `--update - a symbolic link at the pin path is left alone - exit 3`() {
        assumeTrue(!WINDOWS, "symbolic links")
        val target = tmp.resolve("pin target")
        val targets = listOf("dangling" to null, "another pin" to box.pinFor("1.0.0", jarBytes), "the same pin" to box.pinFor(VERSION, releaseJar))
        for ((name, text) in targets) {
            target.delete()
            text?.let { target.writeText(it) }
            pinFile.delete()
            Files.createSymbolicLink(pinFile.toPath(), target.toPath())
            box.clearCalls()
            release()
            val run = box.okfw("--update", VERSION)
            assertEquals(
                Run(
                    3,
                    "",
                    "okfw: downloading okf-cli $VERSION\ngh stdout\ngh stderr\n" +
                        "okfw: cannot write the pin: ./okf-wrapper.properties is not a file\n",
                ),
                run,
                name,
            )
            assertTrue(Files.isSymbolicLink(pinFile.toPath()), "$name: the link stays")
            assertEquals(target.toPath(), Files.readSymbolicLink(pinFile.toPath()), name)
            assertEquals(text, if (target.exists()) target.readText() else null, "$name: the target is unchanged")
            assertEquals(listOf("gh", "curl", "curl", "gh", "java"), box.calls().map { it.first() }, "$name: okf wrapper does not run")
            assertEquals("version", box.calls().last().last(), name)
        }
    }

    /** The new pin goes to a fresh temporary name: files and links that already sit next to the pin are never touched. */
    @Test
    fun `--update - files next to the pin that look like its temporary file are left alone`() {
        val lookalike = box.repo.resolve(".okf-wrapper.properties.keep").apply { writeText("someone else's\n") }
        val pid = box.repo.resolve(".okf-wrapper.properties.12345").apply { writeText("another one\n") }
        val target = tmp.resolve("link target").apply { writeText("link target\n") }
        val link = box.repo.resolve(".okf-wrapper.properties.link")
        if (!WINDOWS) Files.createSymbolicLink(link.toPath(), target.toPath())
        release()
        assertEquals(0, box.okfw("--update", VERSION).code)
        assertArrayEquals(Launcher.pin(VERSION, releaseSha), pinFile.readBytes())
        assertEquals("someone else's\n", lookalike.readText())
        assertEquals("another one\n", pid.readText())
        if (!WINDOWS) {
            assertTrue(Files.isSymbolicLink(link.toPath()))
            assertEquals("link target\n", target.readText())
        }
        val expected = listOf(Launcher.PIN, ".okf-wrapper.properties.12345", ".okf-wrapper.properties.keep", "okfw", "okfw.cmd") +
            if (WINDOWS) emptyList() else listOf(".okf-wrapper.properties.link")
        assertEquals(expected.sorted(), box.repo.list()!!.sorted(), "no temporary pin left")
    }

    @Test
    fun `--update - a pin that cannot be moved into place exits 3 after the tool's message, the pin unchanged`() {
        val pin = box.pinFor("1.0.0", jarBytes)
        box.pin(pin)
        release()
        val mv = with(box) { checkNotNull(Host.tool("mv")) { "this machine has no mv" }.shell }
        box.stub(
            "mv",
            "for a in \"\$@\"; do last=\$a; done\n" +
                "case \$last in */okf-wrapper.properties) echo 'mv: cannot move: Permission denied' >&2; exit 1 ;; esac\n" +
                "exec ${LauncherSandbox.quote(mv)} \"\$@\"\n",
        )
        assertEquals(
            Run(
                3,
                "",
                "okfw: downloading okf-cli $VERSION\ngh stdout\ngh stderr\n" +
                    "mv: cannot move: Permission denied\nokfw: cannot write the pin: ./okf-wrapper.properties\n",
            ),
            box.okfw("--update", VERSION),
        )
        assertEquals(pin, pinFile.readText())
        assertEquals(listOf(Launcher.PIN, "okfw", "okfw.cmd"), box.repo.list()!!.sorted(), "no temporary pin left")
        assertCacheHoldsOnly("okf-cli-$VERSION-all.jar")
    }

    /** A `mktemp` stub first on the PATH: logs its argv, fails for the pin's template when [failPin], else runs the real one. */
    private fun mktempStub(failPin: Boolean) {
        val mktemp = with(box) { checkNotNull(Host.tool("mktemp")) { "this machine has no mktemp" }.shell }
        box.stub(
            "mktemp",
            "for a in \"\$@\"; do last=\$a; done\n" +
                (if (failPin) "case \$last in */.okf-wrapper.properties.XXXXXX) echo 'mktemp: cannot create: Permission denied' >&2; exit 1 ;; esac\n" else "") +
                "exec ${LauncherSandbox.quote(mktemp)} \"\$@\"\n",
        )
    }

    @Test
    fun `--update - the new pin is written in a new temporary directory next to it`() {
        box.pin(box.pinFor("1.0.0", jarBytes))
        release()
        mktempStub(failPin = false)
        assertEquals(0, box.okfw("--update", VERSION).code)
        val pinCalls = box.calls().filter { it.first() == "mktemp" && it.last().contains(".okf-wrapper.properties.") }
        assertEquals(listOf(listOf("mktemp", "-d", "./.okf-wrapper.properties.XXXXXX")), pinCalls)
        assertArrayEquals(Launcher.pin(VERSION, releaseSha), pinFile.readBytes())
        assertEquals(listOf(Launcher.PIN, "okfw", "okfw.cmd"), box.repo.list()!!.sorted(), "the temporary directory is removed")
    }

    @Test
    fun `--update - a temporary pin directory that cannot be created exits 3 after mktemp's message, nothing changed`() {
        val pin = box.pinFor("1.0.0", jarBytes)
        box.pin(pin)
        release()
        mktempStub(failPin = true)
        val before = box.repo.list()!!.sorted()
        assertEquals(
            Run(
                3,
                "",
                "okfw: downloading okf-cli $VERSION\ngh stdout\ngh stderr\n" +
                    "mktemp: cannot create: Permission denied\nokfw: cannot write the pin: ./okf-wrapper.properties\n",
            ),
            box.okfw("--update", VERSION),
        )
        assertEquals(pin, pinFile.readText())
        assertEquals(before, box.repo.list()!!.sorted())
        assertTrue(box.calls().none { it.first() == "java" && "wrapper" in it }, "okf wrapper does not run")
    }

    /** The pin gets the mode the umask gives, as `okf wrapper` writes it: never the owner-only mode of mktemp. */
    @Test
    fun `--update - the pin gets the umask's mode`() {
        assumeTrue(!WINDOWS, "POSIX permissions")
        val sh = LauncherSandbox.quote(Host.sh.path)
        val script = tmp.resolve("with umask.sh").apply { writeText("umask \"\$1\"\nshift\nexec $sh ./okfw \"\$@\"\n") }
        val environment = box.environment()
        box.guard(environment)
        fun update(umask: String) = Host.run(listOf(Host.sh.path, script.path, umask, "--update", VERSION), box.repo, environment)
        val cases = listOf(
            Triple("no pin, umask 022", "022", "rw-r--r--"),
            Triple("an existing 0644 pin, umask 022", "022", "rw-r--r--"),
            Triple("no pin, umask 027", "027", "rw-r-----"),
        )
        for ((name, umask, mode) in cases) {
            pinFile.delete()
            if (name.startsWith("an existing")) {
                box.pin(box.pinFor("1.0.0", jarBytes))
                Files.setPosixFilePermissions(pinFile.toPath(), PosixFilePermissions.fromString("rw-r--r--"))
            }
            box.cache.deleteRecursively()
            release()
            val run = update(umask)
            assertEquals(0, run.code, "$name: $run")
            assertArrayEquals(Launcher.pin(VERSION, releaseSha), pinFile.readBytes(), name)
            assertEquals(mode, PosixFilePermissions.toString(Files.getPosixFilePermissions(pinFile.toPath())), name)
        }
    }

    @Test
    fun `--update - a repository directory that cannot be written exits 3, the pin unchanged`() {
        assumeTrue(!WINDOWS, "POSIX permissions")
        val pin = box.pinFor("1.0.0", jarBytes)
        box.pin(pin)
        release()
        box.repo.setWritable(false, false)
        try {
            assumeTrue(!box.repo.canWrite(), "the directory is still writable (root)")
            val run = box.okfw("--update", VERSION)
            assertEquals(3, run.code, run.toString())
            assertEquals("", run.out)
            assertTrue(run.err.endsWith("okfw: cannot write the pin: ./okf-wrapper.properties\n"), run.err)
            assertTrue(run.err.lines().dropLast(2).last().isNotEmpty() && !run.err.lines().dropLast(2).last().startsWith("okfw:"), "the tool's message first: ${run.err}")
        } finally {
            box.repo.setWritable(true, true)
        }
        assertEquals(pin, pinFile.readText())
        assertEquals(listOf(Launcher.PIN, "okfw", "okfw.cmd"), box.repo.list()!!.sorted())
    }

    /** A gh configuration directory that cannot be created: mktemp's own message and exit 3, before gh runs. */
    @Test
    fun `--update - a cache directory that cannot be written exits 3 with the tool's message`() {
        assumeTrue(!WINDOWS, "POSIX permissions")
        box.pin(box.pinFor("1.0.0", jarBytes))
        release()
        val dir = box.cachedJar(VERSION).parentFile.apply { mkdirs() }
        dir.setWritable(false, false)
        try {
            assumeTrue(!dir.canWrite(), "the directory is still writable (root)")
            val run = box.okfw("--update", VERSION)
            assertEquals(3, run.code, run.toString())
            assertEquals("", run.out)
            assertTrue(run.err.isNotEmpty() && !run.err.contains("okfw:"), "only mktemp's message: ${run.err}")
            assertEquals(emptyList<List<String>>(), box.calls(), "gh does not run")
        } finally {
            dir.setWritable(true, true)
        }
    }

    @Test
    fun `--update - a failing okf wrapper exits 3 after the pin is written`() {
        box.pin(box.pinFor("1.0.0", jarBytes))
        release(wrapperExit = 2)
        assertEquals(
            Run(
                3,
                "okfw: pinned okf-cli $VERSION (sha256 $releaseSha)\njar output\n",
                "okfw: downloading okf-cli $VERSION\ngh stdout\ngh stderr\n" +
                    "okfw: okf-cli $VERSION is pinned, but its launcher files could not be written (okf wrapper exited 2)\n",
            ),
            box.okfw("--update", VERSION),
        )
        assertArrayEquals(Launcher.pin(VERSION, releaseSha), pinFile.readBytes())
    }

    @Test
    fun `--update - okf wrapper gets the caller's LC_ALL`() {
        release()
        box.stub(
            "java",
            "if [ \"\$1\" = -version ]; then echo 'openjdk version \"21\"' >&2; exit 0; fi\n" +
                "if [ \"\$3\" = version ]; then echo 'okf-cli $VERSION'; exit 0; fi\n" +
                "printf 'LC_ALL=%s\\n' \"\${LC_ALL-unset}\"\n",
        )
        assertEquals("LC_ALL=unset\n", box.okfw("--update", VERSION, env = mapOf("LC_ALL" to null)).out.lines().drop(1).joinToString("\n"))
        pinFile.delete()
        assertEquals("LC_ALL=en_US.UTF-8\n", box.okfw("--update", VERSION, env = mapOf("LC_ALL" to "en_US.UTF-8")).out.lines().drop(1).joinToString("\n"))
    }

    // --- okfw --verify ---

    @Test
    fun `--verify - checks the provenance and version of the pinned jar and writes nothing but the cache`() {
        ready()
        val pin = pinFile.readText()
        release()
        val run = box.okfw("--verify", env = callerGhEnv)
        assertEquals(
            Run(
                0,
                "okfw: okf-cli $VERSION verified (built by .github/workflows/release.yml on refs/heads/main)\n",
                "okfw: downloading okf-cli $VERSION\ngh stdout\ngh stderr\n",
            ),
            run,
        )
        val calls = box.calls()
        assertEquals(listOf("gh", "curl", "gh", "java"), calls.map { it.first() })
        val bundleTmp = calls[1][14]
        assertEquals(curlCall(bundleTmp, "okf-cli-$VERSION-all.jar.sigstore.json"), calls[1])
        assertBundlePath(bundleTmp)
        assertEquals(policy(jar(), bundleTmp), calls[2])
        assertEquals(javaCall("version"), calls[3])
        assertEquals(pin, pinFile.readText())
        assertCacheHoldsOnly("okf-cli-$VERSION-all.jar")
        assertArrayEquals(jarBytes, box.cachedJar(VERSION).readBytes())
        assertEquals(listOf(Launcher.PIN, "okfw", "okfw.cmd"), box.repo.list()!!.sorted())
        assertGhIsolated(calls = 2)
    }

    @Test
    fun `--verify - downloads the pinned jar first when it is not cached, and reads OKFW_PIN`() {
        val elsewhere = tmp.resolve("other dir/my pin.properties")
        box.pin(box.pinFor(VERSION, releaseJar), elsewhere)
        release()
        val run = box.okfw("--verify", env = mapOf("OKFW_PIN" to with(box) { elsewhere.shell }))
        assertEquals(0, run.code, run.toString())
        assertEquals("okfw: downloading okf-cli $VERSION\ngh stdout\ngh stderr\n", run.err, "one progress line")
        assertEquals(listOf("gh", "curl", "curl", "gh", "java"), box.calls().map { it.first() })
        assertEquals(policy(jar(), box.calls()[2][14]), box.calls()[3])
        assertCacheHoldsOnly("okf-cli-$VERSION-all.jar")
        assertFalse(pinFile.exists())
    }

    @Test
    fun `--verify - failures exit 3 and change nothing`() {
        ready()
        val pin = pinFile.readText()
        val nothing = "nothing was changed"
        release(ghExit = 4)
        assertEquals(
            Run(
                3,
                "",
                "okfw: downloading okf-cli $VERSION\ngh stdout\ngh stderr\n" +
                    "okfw: the build provenance of okf-cli-$VERSION-all.jar could not be verified (gh attestation verify exited 4); $nothing\n",
            ),
            box.okfw("--verify"),
        )
        release(jarVersion = "okf-cli 1.2.4\n")
        assertEquals(
            Run(3, "", "okfw: downloading okf-cli $VERSION\ngh stdout\ngh stderr\nokfw: the verified jar reports 'okf-cli 1.2.4', not 'okf-cli $VERSION'; $nothing\n"),
            box.okfw("--verify"),
        )
        assertEquals(pin, pinFile.readText())
        assertCacheHoldsOnly("okf-cli-$VERSION-all.jar")
    }

    @Test
    fun `--verify - takes no arguments and needs a pin`() {
        ready()
        release()
        assertEquals(Run(2, "", "okfw: --verify takes no arguments\n"), box.okfw("--verify", VERSION))
        pinFile.delete()
        assertEquals(Run(2, "", "okfw: pin file not found: ./okf-wrapper.properties — run okfw --update <version>\n"), box.okfw("--verify"))
        assertEquals(emptyList<List<String>>(), box.calls())
    }

    // --- cache and download ---

    @Test
    fun `cache hit - no download, the jar runs`() {
        ready()
        assertEquals(Run(0, "jar output\n", ""), box.okfw("check"))
        assertEquals(listOf(javaCall("check")), box.calls())
    }

    @Test
    fun `cache miss - one download with the exact curl command line, then the jar runs`() {
        box.pin(box.pinFor(VERSION, jarBytes))
        box.curl(serve = tmp.resolve("served.jar").apply { writeBytes(jarBytes) })
        val run = box.okfw("check")
        assertEquals(Run(0, "jar output\n", "okfw: downloading okf-cli $VERSION\n"), run)
        val (curl, java) = box.calls()
        val dir = with(box) { box.cache.shell } + "/okf-cli/$VERSION"
        assertEquals(
            listOf(
                "curl", "-q", "--fail", "--silent", "--show-error", "--location", "--proto", "=https", "--proto-redir", "=https",
                "--tlsv1.2", "--retry", "3", "-o", curl[14],
                "https://github.com/sharpbitstudio/okf-cli/releases/download/v$VERSION/okf-cli-$VERSION-all.jar",
            ),
            curl,
        )
        assertTrue(Regex(Regex.escape("$dir/.okf-cli-$VERSION-all.jar.") + "[A-Za-z0-9]{6}").matches(curl[14]), curl[14])
        assertEquals(javaCall("check"), java)
        assertEquals(listOf("okf-cli-$VERSION-all.jar"), box.cachedJar(VERSION).parentFile.list()!!.toList())
        assertTrue(jarBytes.contentEquals(box.cachedJar(VERSION).readBytes()))

        box.clearCalls()
        box.curl(serve = null)
        assertEquals(Run(0, "jar output\n", ""), box.okfw("check"))
        assertEquals(listOf(javaCall("check")), box.calls())
    }

    @Test
    fun `cache miss - the cache directories are created for the owner only`() {
        assumeTrue(!WINDOWS, "POSIX permissions")
        box.pin(box.pinFor(VERSION, jarBytes))
        box.curl(serve = tmp.resolve("served.jar").apply { writeBytes(jarBytes) })
        assertEquals(0, box.okfw("check").code)
        val ownerOnly = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
        for (dir in listOf(box.cache, box.cache.resolve("okf-cli"), box.cache.resolve("okf-cli/$VERSION"))) {
            assertEquals(ownerOnly, Files.getPosixFilePermissions(dir.toPath()), dir.path)
        }
    }

    @Test
    fun `a cached jar that does not match the pin is downloaded again, once`() {
        box.pin(box.pinFor(VERSION, jarBytes))
        box.seed(VERSION, "corrupt".toByteArray())
        box.curl(serve = tmp.resolve("served.jar").apply { writeBytes(jarBytes) })
        assertEquals(Run(0, "jar output\n", "okfw: downloading okf-cli $VERSION\n"), box.okfw("check"))
        assertEquals(listOf("curl", "java"), box.calls().map { it.first() })
        assertTrue(jarBytes.contentEquals(box.cachedJar(VERSION).readBytes()))
    }

    @Test
    fun `a download that does not match the pin exits 3 and leaves nothing behind`() {
        box.pin(box.pinFor(VERSION, jarBytes))
        val other = "another jar".toByteArray()
        box.curl(serve = tmp.resolve("served.jar").apply { writeBytes(other) })
        val run = box.okfw("check")
        assertEquals(
            Run(
                3,
                "",
                "okfw: downloading okf-cli $VERSION\n" +
                    "okfw: SHA-256 mismatch for okf-cli-$VERSION-all.jar: the pin says ${LauncherSandbox.sha256(jarBytes)}, " +
                    "the download has ${LauncherSandbox.sha256(other)}\n",
            ),
            run,
        )
        assertEquals(listOf("curl"), box.calls().map { it.first() })
        assertEquals(emptyList<String>(), box.cachedJar(VERSION).parentFile.list()!!.toList())
    }

    @Test
    fun `a failed download exits 3 and leaves nothing behind`() {
        box.pin(box.pinFor(VERSION, jarBytes))
        val run = box.okfw("check")
        val url = "https://github.com/sharpbitstudio/okf-cli/releases/download/v$VERSION/okf-cli-$VERSION-all.jar"
        assertEquals(
            Run(
                3,
                "",
                "okfw: downloading okf-cli $VERSION\ncurl: (22) The requested URL returned error: 404\nokfw: download failed: $url\n",
            ),
            run,
        )
        assertEquals(emptyList<String>(), box.cachedJar(VERSION).parentFile.list()!!.toList())
    }

    @Test
    fun `a cached jar that does not match the pin, then a failed download - exit 3, nothing left`() {
        box.pin(box.pinFor(VERSION, jarBytes))
        box.seed(VERSION, "corrupt".toByteArray())
        val run = box.okfw("check")
        assertEquals(3, run.code, run.toString())
        assertEquals("", run.out)
        assertTrue(run.err.endsWith("okfw: download failed: https://github.com/sharpbitstudio/okf-cli/releases/download/v$VERSION/okf-cli-$VERSION-all.jar\n"), run.err)
        assertEquals(listOf("curl"), box.calls().map { it.first() })
        assertEquals(emptyList<String>(), box.cachedJar(VERSION).parentFile.list()!!.toList(), "the cached jar is gone, no temporary file")
    }

    /** A cached jar that does not match the pin and cannot be deleted: `rm`'s message, then the launcher's, exit 3. */
    @Test
    fun `a mismatching cached jar that cannot be deleted - exit 3 before any download`() {
        box.pin(box.pinFor(VERSION, jarBytes))
        box.seed(VERSION, "corrupt".toByteArray())
        box.curl(serve = tmp.resolve("served.jar").apply { writeBytes(jarBytes) })
        box.stub("rm", "echo 'rm: cannot remove: Permission denied' >&2\nexit 1\n")
        val jar = with(box) { box.cachedJar(VERSION).shell }
        assertEquals(
            Run(3, "", "rm: cannot remove: Permission denied\nokfw: cannot replace the cached jar $jar\n"),
            box.okfw("check"),
        )
        assertEquals(listOf("rm"), box.calls().map { it.first() }, "no download")
        assertEquals("corrupt", box.cachedJar(VERSION).readText())
    }

    @Test
    fun `a directory at the cache path of the jar - exit 3 before any download, nothing left`() {
        box.pin(box.pinFor(VERSION, jarBytes))
        box.cachedJar(VERSION).mkdirs()
        box.curl(serve = tmp.resolve("served.jar").apply { writeBytes(jarBytes) })
        val jar = with(box) { box.cachedJar(VERSION).shell }
        assertEquals(Run(3, "", "okfw: cannot use the cache: $jar is not a file\n"), box.okfw("check"))
        assertEquals(emptyList<List<String>>(), box.calls())
        assertEquals(listOf("okf-cli-$VERSION-all.jar"), box.cachedJar(VERSION).parentFile.list()!!.toList())
        assertEquals(emptyList<String>(), box.cachedJar(VERSION).list()!!.toList())
    }

    /**
     * The move of a download onto the cache path fails (as on Windows while a parallel run's JVM holds the jar open):
     * a matching jar already there is used. A `mv` stub first on the PATH fails; the `curl` stub places the jar meanwhile.
     */
    @Test
    fun `a move that fails onto a matching cached jar - that jar runs, no temporary file left`() {
        box.pin(box.pinFor(VERSION, jarBytes))
        val served = tmp.resolve("served.jar").apply { writeBytes(jarBytes) }
        box.cachedJar(VERSION).parentFile.mkdirs()
        box.curl(serve = served, alsoTo = box.cachedJar(VERSION))
        box.stub("mv", "echo 'mv: cannot move: Device or resource busy' >&2\nexit 1\n")
        val run = box.okfw("check")
        assertEquals(Run(0, "jar output\n", "okfw: downloading okf-cli $VERSION\nmv: cannot move: Device or resource busy\n"), run)
        assertEquals(listOf("curl", "mv", "java"), box.calls().map { it.first() })
        assertEquals(listOf("okf-cli-$VERSION-all.jar"), box.cachedJar(VERSION).parentFile.list()!!.toList())

        // Another jar placed meanwhile, not matching: exit 3, the tool's own message only.
        box.clearCalls()
        box.cachedJar(VERSION).delete()
        box.curl(serve = served)
        box.stub("mv", "printf other > \"\$3\"\necho 'mv: cannot move: Device or resource busy' >&2\nexit 1\n")
        assertEquals(Run(3, "", "okfw: downloading okf-cli $VERSION\nmv: cannot move: Device or resource busy\n"), box.okfw("check"))
        assertEquals(listOf("okf-cli-$VERSION-all.jar"), box.cachedJar(VERSION).parentFile.list()!!.toList())
    }

    @Test
    fun `curl is needed only for a download`() {
        box.stub.resolve("curl").delete()
        box.pin(box.pinFor(VERSION, jarBytes))
        assertEquals(Run(3, "", "okfw: curl not found\n"), box.okfw("check"))
        box.seed(VERSION, jarBytes)
        assertEquals(Run(0, "jar output\n", ""), box.okfw("check"))
    }

    @Test
    fun `the SHA-256 tool - sha256sum, else shasum, else exit 3`() {
        ready()
        box.toolbox(LauncherSandbox.BASE_TOOLS)
        assertEquals(Run(3, "", "okfw: no SHA-256 tool found (sha256sum or shasum)\n"), box.okfw("check"))
        for (tool in listOf("sha256sum", "shasum").filter { Host.tool(it) != null }) {
            box.clearCalls()
            box.toolbox(LauncherSandbox.BASE_TOOLS + tool)
            assertEquals(Run(0, "jar output\n", ""), box.okfw("check"), tool)
            assertEquals(listOf(javaCall("check")), box.calls(), tool)
        }
    }

    @Test
    fun `the cache is under XDG_CACHE_HOME, else HOME, when OKFW_CACHE_DIR is not set`() {
        box.pin(box.pinFor(VERSION, jarBytes))
        val xdg = tmp.resolve("xdg cache")
        val home = tmp.resolve("home dir")
        val shown = { file: File -> with(box) { file.shell } }
        for (cache in listOf(xdg, home.resolve(".cache"))) {
            cache.resolve("okf-cli/$VERSION/okf-cli-$VERSION-all.jar").apply { parentFile.mkdirs() }.writeBytes(jarBytes)
        }
        val xdgJar = shown(xdg) + "/okf-cli/$VERSION/okf-cli-$VERSION-all.jar"
        // sh reads HOME in its own form (MSYS converts it on Windows).
        val homeJar = box.shPath(home) + "/.cache/okf-cli/$VERSION/okf-cli-$VERSION-all.jar"

        assertEquals(0, box.okfw("check", env = mapOf("OKFW_CACHE_DIR" to null, "XDG_CACHE_HOME" to shown(xdg))).code)
        assertEquals(listOf("java", "-jar", xdgJar, "check"), box.calls().single())
        box.clearCalls()
        assertEquals(0, box.okfw("check", env = mapOf("OKFW_CACHE_DIR" to null, "HOME" to shown(home))).code)
        assertEquals(listOf("java", "-jar", homeJar, "check"), box.calls().single())
    }

    @Test
    fun `no cache directory when OKFW_CACHE_DIR, XDG_CACHE_HOME and HOME are all unset or empty`() {
        // MSYS gives a process without HOME one of its own (the user's profile): the launcher would leave the sandbox.
        assumeTrue(!WINDOWS, "MSYS always sets HOME")
        ready()
        val env = mapOf("OKFW_CACHE_DIR" to "", "XDG_CACHE_HOME" to "", "HOME" to null)
        val run = box.okfw("check", env = env)
        assertEquals(Run(3, "", "okfw: no cache directory: set OKFW_CACHE_DIR, XDG_CACHE_HOME or HOME\n"), run)
        assertEquals(emptyList<List<String>>(), box.calls())
    }

    // --- Java ---

    @Test
    fun `Java - missing`() {
        ready()
        box.stub.resolve("java").delete()
        assertEquals(
            Run(3, "", "okfw: Java not found — okf-cli needs Java 17 or later (set JAVA_HOME or put java on the PATH)\n"),
            box.okfw("check"),
        )
    }

    @Test
    fun `Java - the version from java -version`() {
        ready()
        val java = box.shPath(box.stub) + "/java"
        val old = { major: String -> Run(3, "", "okfw: Java 17 or later is required; $java is version $major\n") }
        val ok = Run(0, "jar output\n", "")
        val cases = listOf(
            "openjdk version \"11.0.2\" 2019-01-15\nOpenJDK Runtime Environment 18.9 (build 11.0.2+9)\n" to old("11"),
            "java version \"1.8.0_412\"\nJava(TM) SE Runtime Environment (build 1.8.0_412-b08)\n" to old("8"),
            "openjdk version \"16\" 2021-03-16\r\nOpenJDK Runtime Environment (build 16+36-2231)\r\n" to old("16"),
            "Picked up JAVA_TOOL_OPTIONS: -Dfile.encoding=UTF-8 -Dx=\"1\"\nopenjdk version \"11.0.1\" 2018-10-16\n" to old("11"),
            "Picked up JAVA_TOOL_OPTIONS: -Dx=\"1\"\r\nopenjdk version \"17.0.2\" 2022-01-18\r\n" to ok,
            "openjdk version \"17\" 2021-09-14\n" to ok,
            "openjdk version \"25-ea\" 2025-09-16\n" to ok,
            "openjdk version \"21.0.1\" 2023-10-17 LTS\r\nOpenJDK Runtime Environment Temurin-21.0.1+12 (build 21.0.1+12-LTS)\r\n" to ok,
            // Unreadable: the launcher proceeds and lets the jar fail if it must.
            "something else entirely\n" to ok,
            "openjdk version \"\" 2021-09-14\n" to ok,
            "" to ok,
        )
        for ((text, expected) in cases) {
            box.clearCalls()
            box.java(version = text)
            val run = box.okfw("check")
            assertEquals(expected, run, text)
            assertEquals(if (expected.code == 0) listOf(javaCall("check")) else emptyList(), box.calls(), text)
        }
    }

    @Test
    fun `Java - a java -version that fails exits 3, whatever it printed`() {
        ready()
        val java = box.shPath(box.stub) + "/java"
        for (text in listOf("No Java runtime present, requesting install.\n", "openjdk version \"21.0.2\" 2024-01-16\n")) {
            box.java(version = text, versionExit = 1)
            assertEquals(
                Run(3, "", "okfw: Java not usable: $java -version exited 1 — okf-cli needs Java 17 or later (set JAVA_HOME or put java on the PATH)\n"),
                box.okfw("check"),
                text,
            )
        }
        assertEquals(emptyList<List<String>>(), box.calls())
    }

    @Test
    fun `Java - JAVA_HOME wins over the PATH, when its bin java is executable`() {
        ready()
        val home = tmp.resolve("jdk home")
        box.java(tag = "java-home", file = home.resolve("bin/java"))
        assertEquals(Run(0, "jar output\n", ""), box.okfw("check", env = mapOf("JAVA_HOME" to with(box) { home.shell })))
        assertEquals(listOf(javaCall("check", tag = "java-home")), box.calls())

        box.clearCalls()
        val empty = tmp.resolve("empty home").apply { mkdirs() }
        assertEquals(Run(0, "jar output\n", ""), box.okfw("check", env = mapOf("JAVA_HOME" to with(box) { empty.shell })))
        assertEquals(listOf(javaCall("check")), box.calls())
    }

    @Test
    fun `Java - a JAVA_HOME below 17 is named by its path`() {
        ready()
        val home = tmp.resolve("jdk home")
        box.java(version = "openjdk version \"11.0.2\"\n", tag = "java-home", file = home.resolve("bin/java"))
        val shown = with(box) { home.shell }
        assertEquals(
            Run(3, "", "okfw: Java 17 or later is required; $shown/bin/java is version 11\n"),
            box.okfw("check", env = mapOf("JAVA_HOME" to shown)),
        )
    }

    // --- pass-through ---

    @Test
    fun `arguments reach the jar exactly`() {
        ready()
        // On Windows an MSYS program parses its command line itself, expanding `*` and removing single quotes (the
        // launcher never sees them as given); see docs/launcher.md.
        val posixOnly = if (WINDOWS) emptyArray<String>() else arrayOf("*", "'q'")
        val args = arrayOf("impact", "--base=", "", "a b", "--repo=dir with spaces/x", "\$HOME", "a\\b", *posixOnly, "--verify")
        assertEquals(Run(0, "jar output\n", ""), box.okfw(*args))
        assertEquals(listOf(javaCall(*args)), box.calls())
        box.clearCalls()
        assertEquals(Run(0, "jar output\n", ""), box.okfw())
        assertEquals(listOf(javaCall()), box.calls())
    }

    @Test
    fun `Java gets the caller's LC_ALL, or none`() {
        ready()
        box.stub(
            "java",
            "if [ \"\$1\" = -version ]; then echo 'openjdk version \"21\"' >&2; exit 0; fi\n" +
                "printf 'LC_ALL=%s\\n' \"\${LC_ALL-unset}\"\n",
        )
        assertEquals(Run(0, "LC_ALL=unset\n", ""), box.okfw("check", env = mapOf("LC_ALL" to null)))
        assertEquals(Run(0, "LC_ALL=en_US.UTF-8\n", ""), box.okfw("check", env = mapOf("LC_ALL" to "en_US.UTF-8")))
    }

    @Test
    fun `the jar's exit code passes through and the launcher writes nothing to stdout`() {
        ready()
        for (code in listOf(0, 1, 2, 3, 42)) {
            box.java(exitCode = code)
            assertEquals(Run(code, "jar output\n", ""), box.okfw("check"), "exit $code")
        }
    }

    @Test
    fun `the launcher runs from another directory, finding the pin next to itself`() {
        ready()
        val sub = box.repo.resolve("sub dir").apply { mkdirs() }
        val environment = box.environment()
        box.guard(environment)
        val run = Host.run(listOf(Host.sh.path, "../okfw", "check"), sub, environment)
        assertEquals(Run(0, "jar output\n", ""), run)
        assertEquals(listOf(javaCall("check")), box.calls())
    }

    // --- Windows: okfw.cmd ---

    /** The shim's own route: `git --exec-path` → `bin/sh.exe`; a probe stands in for `okfw` and reports what it got. */
    @Test
    fun `Windows - okfw cmd finds sh from git, passes the script with forward slashes and keeps the exit code`() {
        assumeTrue(WINDOWS, "okfw.cmd")
        val dir = tmp.resolve("shim dir").apply { mkdirs() }
        dir.resolve("okfw.cmd").writeBytes(Launcher.files.single { it.name == "okfw.cmd" }.bytes)
        dir.resolve("okfw").writeText(
            "#!/bin/sh\nprintf '0=%s\\n' \"\$0\" >&2\nprintf 'PATH=%s\\n' \"\$PATH\" >&2\n" +
                "for a in \"\$@\"; do printf '[%s]\\n' \"\$a\"; done\nexit \"\$1\"\n",
        )
        val env = LauncherSandbox.CLEARED.filter { it != "PATH" }.associateWith<String, String?> { null } +
            ("NoDefaultCurrentDirectoryInExePath" to null)
        // Git's bin/sh.exe puts its installation's `/<prefix>/bin` first (only a known prefix when that is unknown).
        val gitPath = Host.gitPrefix?.let { Regex.escape("PATH=/$it/bin:/usr/bin:") }
            ?: Host.MSYSTEM_PREFIXES.joinToString("|", "PATH=/(", ")/bin:/usr/bin:")
        for (code in 0..3) {
            val shells = shells(dir, "$code", "a b", "--base=")
            for ((name, run) in shells) {
                assertEquals(code, run.code, "$name: $run")
                assertEquals("[$code]\n[a b]\n[--base=]\n", run.lf().out, name)
                assertTrue(run.err.contains("0=${dir.invariantSeparatorsPath}/okfw\n"), "$name: $run")
                assertTrue(Regex(gitPath).containsMatchIn(run.err), "$name: Git's bin/sh.exe sets the PATH: $run")
            }
        }
        // cmd keeps an empty argument (PowerShell drops one before okfw.cmd sees it).
        val run = Host.run(listOf(cmd(), "/d", "/c", "okfw.cmd 0 \"\" x"), dir, env)
        assertEquals("[0]\n[]\n[x]\n", run.lf().out, run.toString())
    }

    /** The real `okfw` through the shim; `sh.exe` is found on the PATH (no `git` there), so the stubs stay first. */
    @Test
    fun `Windows - okfw through okfw cmd, cmd, PowerShell and pwsh - exit codes 0 to 3, stdout clean`() {
        assumeTrue(WINDOWS, "okfw.cmd")
        ready()
        val environment = box.environment(path = listOf(box.stub, box.toolbox, Host.usrBin))
        box.guard(environment)
        for (code in 0..2) {
            box.java(exitCode = code)
            for ((name, run) in shells(box.repo, "check", env = environment)) {
                assertEquals(Run(code, "jar output\n", ""), run.lf(), "$name, jar exit $code")
            }
        }
        box.java(version = "openjdk version \"11.0.2\"\n")
        for ((name, run) in shells(box.repo, "check", env = environment)) {
            assertEquals(3, run.code, "$name: $run")
            assertEquals("", run.out, name)
            assertTrue(run.err.startsWith("okfw: Java 17 or later is required; "), "$name: $run")
        }
        box.repo.resolve(Launcher.PIN).delete()
        for ((name, run) in shells(box.repo, "check", env = environment)) {
            assertEquals(Run(2, "", "okfw: pin file not found: ${box.repo.invariantSeparatorsPath}/okf-wrapper.properties — run okfw --update <version>\n"), run.lf(), name)
        }
    }

    @Test
    fun `Windows - okfw cmd without sh exe exits 3 with one line on stderr`() {
        assumeTrue(WINDOWS, "okfw.cmd")
        val dir = tmp.resolve("shim dir").apply { mkdirs() }
        dir.resolve("okfw.cmd").writeBytes(Launcher.files.single { it.name == "okfw.cmd" }.bytes)
        val empty = tmp.resolve("empty dir").apply { mkdirs() }
        val env = LauncherSandbox.CLEARED.associateWith<String, String?> { null } + ("PATH" to empty.path)
        for ((name, run) in shells(dir, "version", env = env)) {
            assertEquals(
                Run(3, "", "okfw: sh.exe not found - okfw.cmd runs okfw with Git for Windows' sh (install Git for Windows)\n"),
                run.lf(),
                name,
            )
        }
    }

    /** A `git` that prints no absolute Windows path is not Git for Windows: the shim takes `sh.exe` from the PATH. */
    @Test
    fun `Windows - okfw cmd ignores an exec path that is not absolute`() {
        assumeTrue(WINDOWS, "okfw.cmd")
        val dir = tmp.resolve("shim dir").apply { mkdirs() }
        dir.resolve("okfw.cmd").writeBytes(Launcher.files.single { it.name == "okfw.cmd" }.bytes)
        dir.resolve("okfw").writeText("#!/bin/sh\nprintf 'probe %s\\n' \"\$1\"\nexit \"\$1\"\n")
        // Were `x/y/z\..\..\..\bin\sh.exe` followed, it would reach this impostor, which prints no probe line.
        val impostor = File(System.getenv("SystemRoot") ?: "C:\\Windows", "System32\\hostname.exe")
        impostor.copyTo(dir.resolve("bin/sh.exe"))
        val fakeGit = tmp.resolve("fake git").apply { mkdirs() }
        val env = LauncherSandbox.CLEARED.associateWith<String, String?> { null } +
            ("PATH" to listOf(fakeGit, Host.usrBin).joinToString(File.pathSeparator) { it.path })
        for (execPath in listOf("/usr/lib/git-core", "x/y/z")) {
            fakeGit.resolve("git.cmd").writeText("@echo $execPath\r\n")
            for ((name, run) in shells(dir, "2", env = env)) {
                assertEquals(Run(2, "probe 2\n", ""), run.lf(), "$execPath, $name")
            }
        }
    }

    // --- helpers ---

    private fun cmd() = System.getenv("ComSpec") ?: "C:\\Windows\\System32\\cmd.exe"

    /**
     * Runs `okfw.cmd [args]` in [dir] through `cmd /c`, `call` from another batch file, Windows PowerShell 5.1 and pwsh 7
     * (`./okfw`, when installed); args must not need quoting beyond spaces.
     */
    private fun shells(dir: File, vararg args: String, env: Map<String, String?> = emptyMap()): List<Pair<String, Run>> {
        // cmd searches the current directory for okfw.cmd unless this is set (as it may be in a sandboxed shell).
        val env = env + ("NoDefaultCurrentDirectoryInExePath" to null)
        val cmdArgs = args.joinToString(" ") { if (' ' in it) "\"$it\"" else it }
        val psArgs = args.joinToString(" ") { "'$it'" }
        dir.resolve("caller.cmd").writeText("@echo off\r\ncall okfw.cmd $cmdArgs\r\nexit /b %errorlevel%\r\n")
        val powershell = File(System.getenv("SystemRoot") ?: "C:\\Windows", "System32\\WindowsPowerShell\\v1.0\\powershell.exe")
        val pwsh = System.getenv("PATH").orEmpty().split(File.pathSeparator).map { File(it, "pwsh.exe") }.firstOrNull { it.isFile }
        return buildList {
            add("cmd /c" to Host.run(listOf(cmd(), "/d", "/c", "okfw.cmd $cmdArgs"), dir, env))
            add("call" to Host.run(listOf(cmd(), "/d", "/c", "caller.cmd"), dir, env))
            add("powershell" to Host.run(listOf(powershell.path, "-NoProfile", "-NonInteractive", "-Command", "./okfw $psArgs; exit \$LASTEXITCODE"), dir, env))
            pwsh?.let { add("pwsh" to Host.run(listOf(it.path, "-NoProfile", "-NonInteractive", "-Command", "./okfw $psArgs; exit \$LASTEXITCODE"), dir, env)) }
        }.also { dir.resolve("caller.cmd").delete() }
    }

    private companion object {
        const val VERSION = "1.2.3"
    }
}
