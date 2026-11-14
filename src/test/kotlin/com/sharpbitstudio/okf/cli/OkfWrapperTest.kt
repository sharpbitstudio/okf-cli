package com.sharpbitstudio.okf.cli

import com.sharpbitstudio.okf.OkfGit
import com.sharpbitstudio.okf.launcher.Host
import com.sharpbitstudio.okf.launcher.LauncherSandbox
import com.sharpbitstudio.okf.launcher.Run as ShRun
import com.sharpbitstudio.okf.launcher.WINDOWS
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

/**
 * `okf wrapper` and `okf init` with the launcher, through [OkfCli] in a throwaway git work tree (real git). The code
 * source and version are injected, so both pin branches run: a release `all` jar writes the pin, anything else prints
 * the notice.
 */
class OkfWrapperTest {
    @TempDir
    lateinit var home: File

    @TempDir
    lateinit var repo: File

    private lateinit var globalConfig: File

    /** What [OkfCli] sees as its code source and version unless a test says otherwise: a classes directory, a snapshot. */
    private lateinit var classes: File

    @BeforeEach
    fun setUp() {
        globalConfig = home.resolve("gitconfig").apply { writeText("") }
        classes = home.resolve("classes").apply { mkdirs() }
        git("init", "-q", "-b", "main")
        listOf(
            "user.name" to "t",
            "user.email" to "t@t",
            "commit.gpgsign" to "false",
            "core.autocrlf" to "false",
            "core.hooksPath" to home.resolve("no-hooks").path,
            "core.excludesFile" to home.resolve("empty").apply { writeText("") }.path,
            "core.attributesFile" to home.resolve("empty").path,
        ).forEach { (key, value) -> git("config", key, value) }
    }

    // --- okf wrapper ---

    @Test
    fun `wrapper - writes okfw, okfw cmd and gitattributes, and no pin from a build that is not a release`() {
        val run = wrapper()
        assertEquals(
            Run(
                0,
                "[created] okfw\n[created] okfw.cmd\n[created] .gitattributes\n" +
                    "OKF INFO: no pin written: okf-cli 1.0.0-SNAPSHOT is not a released build; run ./okfw --update <version> to pin a release.\n" +
                    "OKF INFO: commit the launcher as executable: git add --chmod=+x okfw\n" +
                    "okf wrapper OK: 3 file(s) written.\n",
                "",
            ),
            run,
        )
        assertArrayEquals(OKFW, bytes("okfw"))
        assertArrayEquals(OKFW_CMD, bytes("okfw.cmd"))
        assertEquals("/okfw text eol=lf\n/okfw.cmd text eol=crlf\n/okf-wrapper.properties text eol=lf\n", read(".gitattributes"))
        assertFalse(repo.resolve(Launcher.PIN).exists())
        assertFalse(repo.resolve("okf.yaml").exists(), "wrapper needs no okf.yaml and writes none")
    }

    @Test
    fun `wrapper - okfw is executable`() {
        assumeTrue(!WINDOWS, "POSIX file modes")
        assertEquals(0, wrapper().code)
        val mode = Files.getPosixFilePermissions(repo.resolve("okfw").toPath()).map { it.name }.toSet()
        assertTrue(setOf("OWNER_EXECUTE", "GROUP_EXECUTE", "OTHERS_EXECUTE").all { it in mode }, mode.toString())
        // A replaced okfw is executable and readable too.
        repo.resolve("okfw").writeText("#!/bin/sh\n# okf-cli launcher 0\n")
        assertEquals(0, wrapper().code)
        val replaced = Files.getPosixFilePermissions(repo.resolve("okfw").toPath()).map { it.name }.toSet()
        assertTrue(setOf("OWNER_READ", "OWNER_EXECUTE", "OTHERS_READ", "OTHERS_EXECUTE").all { it in replaced }, replaced.toString())
    }

    @Test
    fun `wrapper - an unchanged okfw gets its executable bit back`() {
        assumeTrue(!WINDOWS, "POSIX file modes")
        assertEquals(0, wrapper().code)
        repo.resolve("okfw").setExecutable(false, false)
        val run = wrapper()
        assertTrue(run.out.startsWith("[unchanged] okfw\n[unchanged] okfw.cmd\n"), run.out)
        assertTrue(run.out.endsWith("okf wrapper OK: 0 file(s) written.\n"), run.out)
        assertTrue(repo.resolve("okfw").canExecute())
    }

    @Test
    fun `wrapper - a launcher file that cannot be read is an error, and nothing is written`() {
        assumeTrue(!WINDOWS, "POSIX file modes")
        write("okfw", "#!/bin/sh\n# okf-cli launcher 0\n")
        repo.resolve("okfw").setReadable(false, false)
        try {
            assumeTrue(!repo.resolve("okfw").canRead(), "running as root")
            val before = repo.list()!!.sorted()
            val run = wrapper()
            assertEquals(1, run.code, run.toString())
            assertEquals("", run.out)
            assertTrue(run.err.startsWith("okf wrapper: cannot read okfw: "), run.err)
            assertEquals(before, repo.list()!!.sorted())
            val init = run("init", "--name=x")
            assertEquals(1, init.code, init.toString())
            assertTrue(init.err.startsWith("okf init: cannot read okfw: "), init.err)
            assertEquals(before, repo.list()!!.sorted())
        } finally {
            repo.resolve("okfw").setReadable(true, false)
        }
    }

    @Test
    fun `wrapper - a second run changes nothing`() {
        assertEquals(0, wrapper().code)
        val before = snapshot()
        assertEquals(
            Run(
                0,
                "[unchanged] okfw\n[unchanged] okfw.cmd\n" +
                    "OKF INFO: no pin written: okf-cli 1.0.0-SNAPSHOT is not a released build; run ./okfw --update <version> to pin a release.\n" +
                    "OKF INFO: commit the launcher as executable: git add --chmod=+x okfw\n" +
                    "okf wrapper OK: 0 file(s) written.\n",
                "",
            ),
            wrapper(),
        )
        assertEquals(before, snapshot())
    }

    @Test
    fun `wrapper - launcher files with the marker but other bytes are updated`() {
        write(".gitattributes", ATTRIBUTES)
        write("okfw", "#!/bin/sh\n# okf-cli launcher 0\necho old\n")
        write("okfw.cmd", "@echo off\r\nrem okf-cli launcher 0\r\necho old\r\n")
        assertEquals(
            Run(
                0,
                "[updated] okfw\n[updated] okfw.cmd\n" +
                    "OKF INFO: no pin written: okf-cli 1.0.0-SNAPSHOT is not a released build; run ./okfw --update <version> to pin a release.\n" +
                    "OKF INFO: commit the launcher as executable: git add --chmod=+x okfw\n" +
                    "okf wrapper OK: 2 file(s) written.\n",
                "",
            ),
            wrapper(),
        )
        assertArrayEquals(OKFW, bytes("okfw"))
        assertArrayEquals(OKFW_CMD, bytes("okfw.cmd"))
        assertEquals(listOf(".git", ".gitattributes", "okfw", "okfw.cmd"), repo.list()!!.sorted(), "no temporary file is left")
    }

    @Test
    fun `wrapper - a CRLF copy of okfw is updated, and the repaired launcher runs under sh`() {
        write(".gitattributes", ATTRIBUTES)
        repo.resolve("okfw").writeBytes(String(OKFW, Charsets.US_ASCII).replace("\n", "\r\n").toByteArray())
        repo.resolve("okfw.cmd").writeBytes(OKFW_CMD)
        val run = wrapper()
        assertEquals(0, run.code, run.toString())
        assertTrue(run.out.startsWith("[updated] okfw\n[unchanged] okfw.cmd\n"), run.out)
        assertArrayEquals(OKFW, bytes("okfw"))
        // It reaches its first check: no pin next to it.
        val sh = Host.run(listOf(Host.sh.path, "./okfw", "version"), repo, LauncherSandbox(home.resolve("box")).environment())
        assertEquals(ShRun(2, "", "okfw: pin file not found: ./okf-wrapper.properties — run okfw --update <version>\n"), sh)
    }

    @Test
    fun `wrapper - a file in the way without the marker is a problem, and nothing is written`() {
        write("okfw", "#!/bin/sh\necho my own script\n")
        repo.resolve("okfw.cmd").mkdirs()
        val before = snapshot()
        assertEquals(
            Run(
                1,
                "",
                "okf wrapper found 2 problem(s); nothing was written:\n" +
                    "  - okfw exists and is not an okf-cli launcher — move it away and run okf wrapper again\n" +
                    "  - okfw.cmd exists and is not an okf-cli launcher — move it away and run okf wrapper again\n",
            ),
            wrapper(),
        )
        assertEquals(before, snapshot())
    }

    @Test
    fun `wrapper - the marker counts only within the first three lines`() {
        write("okfw", "#!/bin/sh\n#\n#\n# okf-cli launcher 1\n")
        assertEquals(1, wrapper().code)
        write("okfw", "#!/bin/sh\r\n#\r\n# okf-cli launcher 1\r\n")
        assertEquals(0, wrapper().code)
    }

    @Test
    fun `wrapper - Windows - a launcher held open is rewritten in place`() {
        assumeTrue(WINDOWS, "Windows refuses to replace a file another process holds open")
        write("okfw", "#!/bin/sh\n# okf-cli launcher 0\n")
        FileInputStream(repo.resolve("okfw")).use {
            // Precondition: the move onto the open file is refused, so only the in-place rewrite can succeed.
            val probe = repo.resolve("probe.tmp").apply { writeText("x") }
            assertThrows(IOException::class.java) {
                Files.move(probe.toPath(), repo.resolve("okfw").toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }
            probe.delete()
            val run = wrapper()
            assertEquals(0, run.code, run.toString())
            assertTrue(run.out.startsWith("[updated] okfw\n"), run.out)
        }
        assertArrayEquals(OKFW, bytes("okfw"))
        assertFalse(repo.list()!!.any { it.endsWith(".tmp") }, repo.list()!!.toList().toString())
    }

    @Test
    fun `wrapper - the pin is written only by a release all jar, with that jar's SHA-256`() {
        val all = jar("okf-cli-1.2.3-all.jar", SelfJar.MAIN_CLASS)
        assertEquals(
            Run(
                0,
                "[created] okfw\n[created] okfw.cmd\n[created] okf-wrapper.properties\n[created] .gitattributes\n" +
                    "OKF INFO: commit the launcher as executable: git add --chmod=+x okfw\n" +
                    "okf wrapper OK: 4 file(s) written.\n",
                "",
            ),
            wrapper(self = all, version = "1.2.3"),
        )
        assertEquals(
            "# okf-cli version pinned for this repository; read by ./okfw and the okf-cli GitHub Action.\n" +
                "# Docs: https://github.com/sharpbitstudio/okf-cli/blob/main/docs/launcher.md\n" +
                "version=1.2.3\n" +
                "sha256=${sha256(all)}\n",
            read(Launcher.PIN),
        )
    }

    @Test
    fun `wrapper - no pin from a thin jar, a classes directory, a snapshot or an unknown version`() {
        val cases = listOf(
            Triple("thin jar", jar("okf-cli-1.2.3.jar", null) as File?, "1.2.3"),
            Triple("other main class", jar("other.jar", "com.example.Main"), "1.2.3"),
            Triple("classes directory", classes, "1.2.3"),
            Triple("no code source", null, "1.2.3"),
            Triple("snapshot", jar("okf-cli-1.2.3-SNAPSHOT-all.jar", SelfJar.MAIN_CLASS), "1.2.3-SNAPSHOT"),
            Triple("unknown version", jar("okf-cli-all.jar", SelfJar.MAIN_CLASS), "unknown"),
            Triple("no patch number", jar("okf-cli-1.2-all.jar", SelfJar.MAIN_CLASS), "1.2"),
            Triple("build metadata", jar("okf-cli-1.2.3+meta-all.jar", SelfJar.MAIN_CLASS), "1.2.3+meta"),
            Triple("leading v", jar("okf-cli-v1.2.3-all.jar", SelfJar.MAIN_CLASS), "v1.2.3"),
            Triple("a directory named like a jar", home.resolve("x.jar").apply { mkdirs() }, "1.2.3"),
        )
        for ((name, self, version) in cases) {
            reset()
            val run = wrapper(self = self, version = version)
            assertEquals(0, run.code, "$name: $run")
            assertTrue(
                run.out.contains(
                    "OKF INFO: no pin written: okf-cli $version is not a released build; run ./okfw --update <version> to pin a release.\n",
                ),
                "$name: ${run.out}",
            )
            assertFalse(repo.resolve(Launcher.PIN).exists(), name)
        }
    }

    @Test
    fun `wrapper and init - a release self jar that cannot be read is an error, and nothing is written`() {
        val corrupt = home.resolve("okf-cli-1.2.3-all.jar").apply { writeText("not a zip") }
        val run = wrapper(self = corrupt, version = "1.2.3")
        assertEquals(1, run.code, run.toString())
        assertEquals("", run.out)
        assertTrue(run.err.startsWith("okf wrapper: cannot read ${corrupt.path}: "), run.err)
        assertEquals(listOf(".git"), repo.list()!!.toList())
        val init = run("init", "--name=x", self = corrupt, version = "1.2.3")
        assertEquals(1, init.code, init.toString())
        assertTrue(init.err.startsWith("okf init: cannot read ${corrupt.path}: "), init.err)
        assertEquals(listOf(".git"), repo.list()!!.toList())
        // A snapshot never opens its jar: the notice, as for any build that is not a release.
        assertEquals(0, wrapper(self = corrupt, version = "1.2.3-SNAPSHOT").code)
    }

    @Test
    fun `wrapper - an existing pin is kept as it is, whatever it holds`() {
        write(Launcher.PIN, "not even a pin\r\n")
        val run = wrapper(self = jar("okf-cli-1.2.3-all.jar", SelfJar.MAIN_CLASS), version = "1.2.3")
        assertEquals(
            Run(
                0,
                "[created] okfw\n[created] okfw.cmd\n[kept] okf-wrapper.properties\n[created] .gitattributes\n" +
                    "OKF INFO: commit the launcher as executable: git add --chmod=+x okfw\n" +
                    "okf wrapper OK: 3 file(s) written.\n",
                "",
            ),
            run,
        )
        assertEquals("not even a pin\r\n", read(Launcher.PIN))
        // Without a pin of its own to write, too.
        assertEquals(0, wrapper().code)
        assertEquals("not even a pin\r\n", read(Launcher.PIN))
    }

    @Test
    fun `wrapper - an existing gitattributes is never changed - a notice when it lacks a line, silence when complete`() {
        write(".gitattributes", "*.png binary\n/okfw text eol=lf\n")
        val notice = "OKF INFO: add to .gitattributes: /okfw text eol=lf, /okfw.cmd text eol=crlf, /okf-wrapper.properties text eol=lf\n"
        val run = wrapper()
        assertEquals(
            "[created] okfw\n[created] okfw.cmd\n" +
                "OKF INFO: no pin written: okf-cli 1.0.0-SNAPSHOT is not a released build; run ./okfw --update <version> to pin a release.\n" +
                notice +
                "OKF INFO: commit the launcher as executable: git add --chmod=+x okfw\n" +
                "okf wrapper OK: 2 file(s) written.\n",
            run.out,
        )
        assertEquals("*.png binary\n/okfw text eol=lf\n", read(".gitattributes"))

        write(".gitattributes", "\uFEFF*.png binary\r\n  /okfw text eol=lf  \r\n/okfw.cmd text eol=crlf\r\n\t/okf-wrapper.properties text eol=lf\r\n")
        assertFalse(wrapper().out.contains("add to .gitattributes"))
        write(".gitattributes", "/okfw  text eol=lf\n/okfw.cmd text eol=crlf\n/okf-wrapper.properties text eol=lf\n")
        assertTrue(wrapper().out.contains(notice), "lines match exactly, after trimming only")
    }

    @Test
    fun `wrapper - the work-tree root is required, okf yaml is not, and init's options do not apply`() {
        val sub = repo.resolve("sub").apply { mkdirs() }
        assertEquals(
            Run(2, "", "okf wrapper: --repo must be the git work-tree root (${sub.absoluteFile.normalize().path} is inside ${OkfGit.workTreeRoot(sub)!!.path})\n"),
            run("wrapper", "--repo=${sub.path}"),
        )
        for (option in listOf("--launcher", "--name=x", "--index-command=x", "--strict")) {
            val run = run("wrapper", option)
            assertEquals(2, run.code, option)
            assertEquals("okf: ${option.substringBefore('=')} does not apply to `okf wrapper`\n${OkfCli.USAGE}\n", run.err, option)
        }
        assertEquals(listOf(".git", "sub"), repo.list()!!.sorted())
    }

    @Test
    fun `wrapper - a first write that fails names the path and leaves the repository as it was`() {
        assumeTrue(!WINDOWS, "a read-only directory")
        repo.setWritable(false, false)
        try {
            assumeTrue(!repo.resolve("probe").let { runCatching { it.createNewFile() }.getOrDefault(false) }, "running as root")
            val run = wrapper()
            assertEquals(1, run.code, run.toString())
            assertTrue(run.err.startsWith("okf wrapper: cannot write okfw: "), run.err)
            assertTrue(run.err.endsWith("; the files created by this run were removed\n"), run.err)
            assertEquals(listOf(".git"), repo.list()!!.toList())
        } finally {
            repo.setWritable(true, false)
        }
    }

    // --- the embedded launcher ---

    @Test
    fun `the embedded launcher - okfw is LF and okfw cmd CRLF, both ASCII, both carry the revision marker`() {
        val okfw = String(OKFW, Charsets.US_ASCII)
        val shim = String(OKFW_CMD, Charsets.US_ASCII)
        assertAll(
            { assertTrue(OKFW.all { it.toInt() in 0x20..0x7E || it.toInt() in setOf(0x09, 0x0A) }, "okfw: printable ASCII, tab and LF only") },
            { assertTrue(OKFW_CMD.all { it.toInt() in 0x20..0x7E || it.toInt() in setOf(0x09, 0x0A, 0x0D) }, "okfw.cmd: printable ASCII, tab, CR, LF") },
            { assertFalse('\r' in okfw, "okfw: no CR") },
            { assertEquals(shim.count { it == '\n' }, Regex("\r\n").findAll(shim).count(), "okfw.cmd: every line ends in CRLF") },
            { assertEquals(shim.count { it == '\r' }, Regex("\r\n").findAll(shim).count(), "okfw.cmd: no lone CR") },
            { assertTrue(okfw.startsWith("#!/bin/sh\n# okf-cli launcher 1\n"), "okfw: shebang and marker") },
            { assertTrue(okfw.endsWith("\nmain \"\$@\"; exit\n"), "okfw: the last line runs main and exits") },
            { assertTrue(shim.startsWith("@echo off\r\nrem okf-cli launcher 1\r\n"), "okfw.cmd: marker") },
            { assertTrue(shim.endsWith("\r\n\"%OKFW_SH%\" \"%OKFW_SCRIPT:\\=/%\" %* & call exit /b %%errorlevel%%\r\n"), "okfw.cmd: last line") },
            { assertTrue(Launcher.hasMarker(OKFW) && Launcher.hasMarker(OKFW_CMD)) },
        )
    }

    @Test
    fun `the embedded launcher is the committed launcher directory`() {
        val root = File(checkNotNull(System.getProperty("okf.repoRoot")) { "the build passes okf.repoRoot" })
        for ((name, embedded) in listOf("okfw" to OKFW, "okfw.cmd" to OKFW_CMD)) {
            val committed = String(root.resolve("launcher/$name").readBytes(), Charsets.US_ASCII).replace("\r\n", "\n")
            assertEquals(committed, String(embedded, Charsets.US_ASCII).replace("\r\n", "\n"), name)
        }
    }

    // --- okf init with the launcher ---

    @Test
    fun `init - writes the launcher by default with okfw index as the index command, and the strict check passes`() {
        val run = run("init", "--name=my-repo")
        assertEquals(
            Run(
                0,
                "[created] okf.yaml\n[created] docs/index.md\n[created] okfw\n[created] okfw.cmd\n[created] .gitattributes\n" +
                    "OKF INFO: no pin written: okf-cli 1.0.0-SNAPSHOT is not a released build; run ./okfw --update <version> to pin a release.\n" +
                    "OKF INFO: commit the launcher as executable: git add --chmod=+x okfw\n" +
                    "okf init OK: 5 file(s) created.\n",
                "",
            ),
            run,
        )
        assertTrue(read("okf.yaml").contains("\nindex_command: ./okfw index\n"), read("okf.yaml"))
        assertArrayEquals(OKFW, bytes("okfw"))
        assertArrayEquals(OKFW_CMD, bytes("okfw.cmd"))
        assertStrictCheckPasses()
    }

    @Test
    fun `init - a release all jar also writes the pin, and the strict check passes`() {
        val all = jar("okf-cli-2.0.0-all.jar", SelfJar.MAIN_CLASS)
        val run = run("init", "--name=my-repo", self = all, version = "2.0.0")
        assertEquals(
            Run(
                0,
                "[created] okf.yaml\n[created] docs/index.md\n[created] okfw\n[created] okfw.cmd\n[created] okf-wrapper.properties\n" +
                    "[created] .gitattributes\nOKF INFO: commit the launcher as executable: git add --chmod=+x okfw\n" +
                    "okf init OK: 6 file(s) created.\n",
                "",
            ),
            run,
        )
        assertTrue(read(Launcher.PIN).endsWith("version=2.0.0\nsha256=${sha256(all)}\n"))
        assertStrictCheckPasses()
    }

    @Test
    fun `init - identical launcher files, a pin and gitattributes are kept`() {
        repo.resolve("okfw").writeBytes(OKFW)
        repo.resolve("okfw.cmd").writeBytes(OKFW_CMD)
        write(Launcher.PIN, "version=1.0.0\n")
        write(".gitattributes", "*.png binary\n")
        val run = run("init", "--name=my-repo", self = jar("okf-cli-2.0.0-all.jar", SelfJar.MAIN_CLASS), version = "2.0.0")
        assertEquals(
            Run(
                0,
                "[created] okf.yaml\n[created] docs/index.md\n[kept] okfw\n[kept] okfw.cmd\n[kept] okf-wrapper.properties\n" +
                    "OKF INFO: add to .gitattributes: /okfw text eol=lf, /okfw.cmd text eol=crlf, /okf-wrapper.properties text eol=lf\n" +
                    "okf init OK: 2 file(s) created.\n",
                "",
            ),
            run,
        )
        assertEquals("version=1.0.0\n", read(Launcher.PIN))
        assertEquals("*.png binary\n", read(".gitattributes"))
    }

    @Test
    fun `init - never replaces a launcher file - conflicts, nothing written`() {
        write("okfw", "#!/bin/sh\n# okf-cli launcher 0\n")
        write("okfw.cmd", "@echo off\r\necho mine\r\n")
        val before = snapshot()
        assertEquals(
            Run(
                1,
                "",
                "okf init found 2 problem(s); nothing was written:\n" +
                    "  - okfw already exists — init never replaces it; run okf wrapper to refresh an okf-cli launcher, or pass --launcher=false\n" +
                    "  - okfw.cmd exists and is not an okf-cli launcher — move it away, or pass --launcher=false\n",
            ),
            run("init", "--name=my-repo"),
        )
        assertEquals(before, snapshot())
        // Without the launcher the same repository is fine.
        assertEquals(0, run("init", "--name=my-repo", "--launcher=false", "--index-command=okf index").code)
        assertEquals(before.keys + setOf("okf.yaml", "docs", "docs/index.md"), snapshot().keys)
    }

    @Test
    fun `init - the docs root must stay off the launcher's paths`() {
        val before = snapshot()
        val cases = listOf(
            "okfw" to "okfw",
            "OKFW.cmd/docs" to "okfw.cmd",
            "okf-wrapper.properties/docs" to "okf-wrapper.properties",
            ".gitattributes" to ".gitattributes",
        )
        assertAll(
            cases.map { (value, path) ->
                { assertEquals(Run(2, "", "okf init: --docs-root '$value' collides with $path\n"), run("init", "--name=x", "--docs-root=$value"), value) }
            },
        )
        assertEquals(before, snapshot())
        val run = run("init", "--name=x", "--docs-root=okfw", "--launcher=false", "--index-command=okf index")
        assertEquals(Run(0, "[created] okf.yaml\n[created] okfw/index.md\nokf init OK: 2 file(s) created.\n", ""), run)
    }

    // --- helpers ---

    private data class Run(val code: Int, val out: String, val err: String)

    private fun run(vararg args: String, self: File? = classes, version: String = "1.0.0-SNAPSHOT"): Run {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val cli = OkfCli(
            PrintStream(out, true, Charsets.UTF_8),
            PrintStream(err, true, Charsets.UTF_8),
            githubActions = false,
            selfJar = self,
            toolVersion = version,
        )
        val code = cli.run(args.toList(), cwd = repo)
        fun text(stream: ByteArrayOutputStream) = stream.toString(Charsets.UTF_8).replace("\r\n", "\n")
        return Run(code, text(out), text(err))
    }

    private fun wrapper(self: File? = classes, version: String = "1.0.0-SNAPSHOT") = run("wrapper", self = self, version = version)

    private fun assertStrictCheckPasses() {
        assertEquals(
            Run(0, "okf check OK: 0 documents with frontmatter validated, 0 code references resolved.\n", ""),
            run("check", "--strict", "--refs-strict", "--code-paths-required"),
        )
    }

    /** A jar in [home] whose manifest names [mainClass] (none when `null`), with one entry. */
    private fun jar(name: String, mainClass: String?): File {
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainClass?.let { mainAttributes[Attributes.Name.MAIN_CLASS] = it }
        }
        val file = home.resolve(name)
        JarOutputStream(file.outputStream(), manifest).use { jar ->
            jar.putNextEntry(java.util.jar.JarEntry("a.txt"))
            jar.write(name.toByteArray())
            jar.closeEntry()
        }
        return file
    }

    private fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    /** Every path below [repo] except `.git`: directories map to `null`, files to their bytes (as ISO-8859-1 text). */
    private fun snapshot(): Map<String, String?> =
        repo.walkTopDown().onEnter { it.name != ".git" }.filter { it != repo }
            .associate { it.relativeTo(repo).invariantSeparatorsPath to if (it.isFile) String(it.readBytes(), Charsets.ISO_8859_1) else null }

    private fun reset() {
        repo.listFiles()!!.filter { it.name != ".git" }.forEach { it.deleteRecursively() }
    }

    private fun bytes(path: String) = repo.resolve(path).readBytes()

    private fun read(path: String) = repo.resolve(path).readText(Charsets.UTF_8)

    private fun write(path: String, text: String) {
        repo.resolve(path).apply { parentFile.mkdirs() }.writeText(text)
    }

    private fun git(vararg args: String) {
        val process = ProcessBuilder(listOf("git") + args).directory(repo).redirectErrorStream(true)
            .apply {
                environment()["GIT_CONFIG_GLOBAL"] = globalConfig.path
                environment()["GIT_CONFIG_NOSYSTEM"] = "1"
            }
            .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed: $output" }
    }

    private companion object {
        val OKFW = Launcher.files.single { it.name == "okfw" }.bytes
        val OKFW_CMD = Launcher.files.single { it.name == "okfw.cmd" }.bytes
        const val ATTRIBUTES = "/okfw text eol=lf\n/okfw.cmd text eol=crlf\n/okf-wrapper.properties text eol=lf\n"
    }
}
