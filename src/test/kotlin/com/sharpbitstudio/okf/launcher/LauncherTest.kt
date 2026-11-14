package com.sharpbitstudio.okf.launcher

import com.sharpbitstudio.okf.cli.Launcher
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

/**
 * `launcher/okfw` (the copy `okf wrapper` writes) run by a real `sh` against stubs: `curl` and `java` are `sh` scripts
 * that log their argv, the cache is seeded with fake jars named by generated pins, and the PATH holds nothing else but a
 * toolbox of the tools the script may use (see [LauncherSandbox]).
 */
class LauncherTest {
    @TempDir
    lateinit var tmp: File

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
        val utf8 = mapOf("LANG" to "en_US.UTF-8", "LC_ALL" to "en_US.UTF-8")
        box.pin("# caf\u00e9\nversion=$VERSION\nsha256=$sha\n")
        assertEquals(Run(0, "jar output\n", ""), box.okfw("check", env = utf8))
        box.pin("# c\nversion=1.2.3-caf\u00e9\nsha256=$sha\n")
        assertEquals(
            Run(2, "", "okfw: ./okf-wrapper.properties: line 2: version is not a version such as 1.2.3 or 1.2.3-rc.1\n"),
            box.okfw("check", env = utf8),
        )
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
    fun `--update and --verify are reserved as the first argument`() {
        for (option in listOf("--update", "--verify")) {
            assertEquals(Run(2, "", "okfw: --update and --verify are not available in this launcher revision\n"), box.okfw(option, "1.2.3"), option)
        }
        ready()
        assertEquals(Run(0, "jar output\n", ""), box.okfw("check", "--update"))
        assertEquals(listOf(javaCall("check", "--update")), box.calls())
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
        for (code in 0..3) {
            val shells = shells(dir, "$code", "a b", "--base=")
            for ((name, run) in shells) {
                assertEquals(code, run.code, "$name: $run")
                assertEquals("[$code]\n[a b]\n[--base=]\n", run.lf().out, name)
                assertTrue(run.err.contains("0=${dir.invariantSeparatorsPath}/okfw\n"), "$name: $run")
                assertTrue(run.err.contains("PATH=/mingw64/bin:/usr/bin:"), "$name: Git's bin/sh.exe sets the PATH: $run")
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
