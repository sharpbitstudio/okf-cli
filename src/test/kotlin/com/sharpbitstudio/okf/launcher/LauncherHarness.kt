package com.sharpbitstudio.okf.launcher

import com.sharpbitstudio.okf.cli.Launcher
import org.junit.jupiter.api.Assertions.assertEquals
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

internal val WINDOWS = System.getProperty("os.name").startsWith("Windows")

/** [this] as the shell sees it given in an argument or variable: with `/` on Windows (MSYS also reads `C:/…`). */
internal val File.shell: String get() = if (WINDOWS) invariantSeparatorsPath else path

/** A child process's exit code and output (UTF-8, as written). */
internal data class Run(val code: Int, val out: String, val err: String) {
    /** The same with CRLF line ends as LF (Java writes CRLF on Windows). */
    fun lf() = Run(code, out.replace("\r\n", "\n"), err.replace("\r\n", "\n"))
}

/** The real tools on this machine, found once. */
internal object Host {
    /** Git for Windows' MSYSTEM prefixes: `<git>/<prefix>` holds `bin/curl.exe` and `libexec/git-core`. */
    val MSYSTEM_PREFIXES = listOf("mingw64", "ucrt64", "clang64", "clangarm64", "mingw32")

    /** `git --exec-path`, e.g. `<git>/mingw64/libexec/git-core` or `<git>/ucrt64/libexec/git-core` on Windows. */
    private val gitExecPath: File? by lazy {
        runCatching { File(run(listOf("git", "--exec-path"), File("."), emptyMap()).out.trim()) }.getOrNull()
    }

    /**
     * `sh.exe` as `okfw.cmd` finds it (Windows): `<git --exec-path>/../../../bin/sh.exe`, else `sh.exe` on the PATH. This
     * Git for Windows launcher puts Git's own `<prefix>/bin` (see [gitPrefix]) and `usr/bin` in front of the PATH.
     */
    val shimSh: File by lazy {
        check(WINDOWS) { "Windows only" }
        gitExecPath?.resolve("../../../bin/sh.exe")?.canonicalFile?.takeIf { it.isFile }
            ?: onPath("sh.exe")
            ?: error("sh.exe not found (Git for Windows)")
    }

    /**
     * The `sh` that runs `okfw` in the hermetic tests: `/bin/sh`; on Windows the MSYS `usr/bin/sh.exe` next to the
     * [shimSh] installation, which runs with exactly the PATH it is given (no Git directories in front of the stubs).
     */
    val sh: File by lazy {
        if (WINDOWS) {
            shimSh.parentFile.parentFile.resolve("usr/bin/sh.exe").also {
                check(it.isFile) {
                    "$it not found: the launcher tests need Git for Windows' bin/sh.exe " +
                        "(from git --exec-path, else from the PATH; found $shimSh)"
                }
            }
        } else {
            File("/bin/sh")
        }
    }

    /** Git for Windows' `usr/bin` (holds `sh.exe`, the coreutils, `sha256sum`; no `curl`, `gh`, `java` or `shasum`). */
    val usrBin: File get() = sh.parentFile

    /** The Git for Windows installation that holds [sh] (`<git>/usr/bin/sh.exe`). */
    private val gitRoot: File get() = usrBin.parentFile.parentFile

    /**
     * The MSYSTEM prefix of the [gitRoot] installation (Windows), e.g. `mingw64` or `ucrt64`: `<prefix>` of
     * `git --exec-path` (`<git>/<prefix>/libexec/git-core`) when that lies in this installation, else `null` (unknown).
     */
    val gitPrefix: String? by lazy {
        check(WINDOWS) { "Windows only" }
        val root = gitRoot.canonicalFile
        gitExecPath?.canonicalFile
            ?.takeIf { it.name == "git-core" && it.parentFile?.name == "libexec" }
            ?.parentFile?.parentFile
            ?.takeIf { it.parentFile == root }
            ?.name
    }

    /**
     * The curl an `okfw.cmd` user gets: Git for Windows' own, which Git's sh.exe puts first on the PATH
     * (`<git>/<prefix>/bin/curl.exe` for [gitPrefix], else for the first of [MSYSTEM_PREFIXES] that has one); when this
     * installation has none, Windows' own `System32\curl.exe`.
     */
    private val windowsCurl: File by lazy {
        (listOfNotNull(gitPrefix) + MSYSTEM_PREFIXES).map { gitRoot.resolve("$it/bin/curl.exe") }.firstOrNull { it.isFile }
            ?: File(System.getenv("SystemRoot") ?: "C:\\Windows", "System32\\curl.exe")
    }

    /**
     * [dir] by its long name on Windows: the JVM's temp directory may be given in its 8.3 short form, which `cmd` keeps
     * while PowerShell shows its location in the long form, so tests that compare a printed path work in the long form.
     * Unchanged elsewhere (`toRealPath` would also resolve links, e.g. macOS `/var` to `/private/var`).
     */
    fun longName(dir: File): File = if (WINDOWS) dir.toPath().toRealPath().toFile() else dir

    /** The real executable [name] the harness links into a toolbox, or `null` when this machine has none. */
    fun tool(name: String): File? =
        if (WINDOWS) {
            when (name) {
                "shasum" -> usrBin.resolve("core_perl/shasum")
                // The GitHub CLI is installed on its own, not with Git.
                "gh" -> return onPath("gh.exe")
                "curl" -> windowsCurl
                else -> usrBin.resolve("$name.exe")
            }.takeIf { it.isFile }
        } else {
            onPath(name)
        }

    /** `sha256sum` where the machine has it, else `shasum` (macOS before 15 has only the latter). */
    val shaTool: String by lazy { if (tool("sha256sum") != null) "sha256sum" else "shasum" }

    /** The first executable [name] on this JVM's `PATH`, or `null`. */
    fun onPath(name: String): File? =
        System.getenv("PATH").orEmpty().split(File.pathSeparator).filter { it.isNotEmpty() }
            .map { File(it, name) }
            .firstOrNull { it.isFile && it.canExecute() }

    /** Runs [command] in [dir] with the inherited environment changed by [env] (`null` removes a variable). */
    fun run(command: List<String>, dir: File, env: Map<String, String?>, timeoutSeconds: Long = 120): Run {
        val out = Files.createTempFile("okfw-out", ".txt").toFile()
        val err = Files.createTempFile("okfw-err", ".txt").toFile()
        try {
            val builder = ProcessBuilder(command).directory(dir).redirectOutput(out).redirectError(err)
            val environment = builder.environment()
            env.forEach { (key, value) -> if (value == null) environment.remove(key) else environment[key] = value }
            val process = builder.start()
            process.outputStream.close()
            check(process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                "timed out: $command"
            }
            return Run(process.exitValue(), out.readText(Charsets.UTF_8), err.readText(Charsets.UTF_8))
        } finally {
            out.delete()
            err.delete()
        }
    }
}

/**
 * A sandbox for running the launcher: a repository directory, a cache, a stub directory (`curl`, `java` as `sh` scripts
 * that log their argv) and a toolbox holding only the tools the script may use. Every path holds a space.
 * Environment of each run: the inherited one without `PATH`, `JAVA_HOME`, the GitHub tokens, `OKFW_*`, `XDG_CACHE_HOME`
 * and `GITHUB_ACTIONS`; `PATH` = stub directory + toolbox; `OKFW_CACHE_DIR` = the cache.
 */
internal class LauncherSandbox(val root: File) {
    val repo: File = root.resolve("repo dir").apply { mkdirs() }
    val cache: File = root.resolve("cache dir")
    val stub: File = root.resolve("stub dir").apply { mkdirs() }
    val toolbox: File = root.resolve("tool box").apply { mkdirs() }
    private val log: File = root.resolve("calls.log")
    private val ghEnvLog: File = root.resolve("gh-env.log")
    private val toolboxTools = mutableSetOf<String>()

    init {
        toolbox(BASE_TOOLS + Host.shaTool)
    }

    /** Sets the toolbox to exactly [tools] (all of them, or the scenario fails to set up). */
    fun toolbox(tools: Collection<String>) {
        toolbox.listFiles()!!.forEach { it.delete() }
        toolboxTools.clear()
        tools.forEach { name ->
            val real = checkNotNull(Host.tool(name)) { "this machine has no $name" }
            val link = toolbox.resolve(name)
            if (WINDOWS) {
                link.writeText("#!/bin/sh\nexec '${real.invariantSeparatorsPath}' \"\$@\"\n")
            } else {
                Files.createSymbolicLink(link.toPath(), real.toPath())
            }
            toolboxTools += name
        }
    }

    /** Writes the launcher as `okf wrapper` writes it. */
    fun launcher() {
        Launcher.files.forEach { file ->
            repo.resolve(file.name).writeBytes(file.bytes)
            if (file.executable) repo.resolve(file.name).setExecutable(true, false)
        }
    }

    /** Writes the pin file [text] (bytes as given, ISO-8859-1) at [file]. */
    fun pin(text: String, file: File = repo.resolve(Launcher.PIN)) {
        file.parentFile.mkdirs()
        file.writeBytes(text.toByteArray(Charsets.ISO_8859_1))
    }

    /** The pin a writer emits for [version] and [bytes]. */
    fun pinFor(version: String, bytes: ByteArray) = String(Launcher.pin(version, sha256(bytes)), Charsets.US_ASCII)

    /** The cache path of [version]'s jar. */
    fun cachedJar(version: String): File = cache.resolve("okf-cli/$version/okf-cli-$version-all.jar")

    /** Places [bytes] at [version]'s cache path. */
    fun seed(version: String, bytes: ByteArray) {
        cachedJar(version).apply { parentFile.mkdirs() }.writeBytes(bytes)
    }

    /**
     * A `curl` stub that logs its argv, then copies [serve] to its `-o` target, or fails when [serve] is `null`; with
     * [alsoTo], it copies [serve] there too (another run that placed the same file meanwhile). A URL ending in
     * `.sigstore.json` gets [bundle] instead, or fails when [bundle] is `null`.
     */
    fun curl(serve: File?, alsoTo: File? = null, bundle: File? = null) {
        val fail = "echo 'curl: (22) The requested URL returned error: 404' >&2\nexit 22\n"
        script(
            stub.resolve("curl"),
            "log curl \"\$@\"\n" +
                "for a in \"\$@\"; do url=\$a; done\n" +
                "out=\nwhile [ \$# -gt 0 ]; do case \$1 in -o) out=\$2; shift ;; esac; shift; done\n" +
                "case \$url in *.sigstore.json)\n" +
                (bundle?.let { "$cat ${quote(it.shell)} > \"\$out\"\nexit 0\n" } ?: fail) +
                ";;\nesac\n" +
                (alsoTo?.let { "$cat ${quote(serve!!.shell)} > ${quote(it.shell)}\n" } ?: "") +
                (serve?.let { "$cat ${quote(it.shell)} > \"\$out\"\n" } ?: fail),
        )
    }

    /**
     * A `gh` stub: logs its argv, and in a second log the variables [GH_ENV] as it sees them plus the state of its
     * `GH_CONFIG_DIR` (`config=empty`, `missing`, `nonempty` or `unset`; see [ghEnv]). `--version` prints [version] to
     * stdout and exits 0; a `--bundle` file not named `*.json` or `*.jsonl` fails with the real gh's message and exit 1;
     * anything else prints `gh stdout` to stdout and `gh stderr` to stderr and exits with [exitCode].
     */
    fun gh(version: String = "gh version 2.102.0 (2026-09-30)\nhttps://github.com/cli/cli/releases/tag/v2.102.0\n", exitCode: Int = 0) {
        val versionFile = root.resolve("gh-version.txt").apply { writeBytes(version.toByteArray(Charsets.UTF_8)) }
        val env = GH_ENV.joinToString("") { name ->
            "if [ -n \"\${$name+x}\" ]; then printf '%s=%s\\n' $name \"\$$name\"; else printf '%s unset\\n' $name; fi\n"
        }
        script(
            stub.resolve("gh"),
            "log gh \"\$@\"\n" +
                "{\nprintf 'call\\n'\n$env" +
                "d=\${GH_CONFIG_DIR-}\n" +
                "if [ -z \"\$d\" ]; then s=unset; elif [ ! -d \"\$d\" ]; then s=missing; else s=empty; " +
                "for f in \"\$d\"/* \"\$d\"/.[!.]* \"\$d\"/..?*; do if [ -e \"\$f\" ]; then s=nonempty; fi; done; fi\n" +
                "printf 'config=%s\\n' \"\$s\"\n" +
                "} >> ${quote(ghEnvLog.shell)}\n" +
                "if [ \"\${1-}\" = --version ]; then $cat ${quote(versionFile.shell)}; exit 0; fi\n" +
                // As the real gh: a bundle file must be named *.json or *.jsonl.
                "bundle=; prev=\nfor a in \"\$@\"; do if [ \"\$prev\" = --bundle ]; then bundle=\$a; fi; prev=\$a; done\n" +
                "case \$bundle in '' | *.json | *.jsonl) ;; *) echo 'Error: bundle file extension not supported, must be json or jsonl' >&2; exit 1 ;; esac\n" +
                "echo 'gh stdout'\necho 'gh stderr' >&2\nexit $exitCode\n",
        )
    }

    /** Per `gh` call: the variables it saw (`<name>=<value>` or `<name> unset`), then `config=<state>`. */
    fun ghEnv(): List<List<String>> {
        if (!ghEnvLog.isFile) return emptyList()
        val calls = mutableListOf<MutableList<String>>()
        ghEnvLog.readLines(Charsets.UTF_8).forEach { line -> if (line == "call") calls += mutableListOf<String>() else calls.last() += line }
        return calls
    }

    /**
     * A `java` stub at [file]: `-version` writes [version] to stderr as given (CRLF and all) and exits with [versionExit];
     * otherwise it logs its argv as `<tag>`. With [jarVersion], `-jar <jar> version` writes it to stdout as given and
     * exits with [jarVersionExit]; anything else writes `jar output` to stdout and exits with [exitCode].
     */
    fun java(
        version: String = "openjdk version \"21.0.2\" 2024-01-16\n",
        exitCode: Int = 0,
        tag: String = "java",
        file: File = stub.resolve("java"),
        versionExit: Int = 0,
        jarVersion: String? = null,
        jarVersionExit: Int = 0,
    ) {
        val versionFile = root.resolve("$tag-version.txt").apply { writeBytes(version.toByteArray(Charsets.UTF_8)) }
        val jarVersionFile = jarVersion?.let { root.resolve("$tag-jar-version.txt").apply { writeBytes(it.toByteArray(Charsets.UTF_8)) } }
        file.parentFile.mkdirs()
        script(
            file,
            "if [ \"\${1-}\" = -version ]; then $cat ${quote(versionFile.shell)} >&2; exit $versionExit; fi\n" +
                "log $tag \"\$@\"\n" +
                (jarVersionFile?.let { "if [ \"\${3-}\" = version ]; then $cat ${quote(it.shell)}; exit $jarVersionExit; fi\n" } ?: "") +
                "echo 'jar output'\n" +
                "exit $exitCode\n",
        )
    }

    /** A stub named [name] in the stub directory (first on the PATH), running [body] after logging its argv. */
    fun stub(name: String, body: String) {
        script(stub.resolve(name), "log $name \"\$@\"\n$body")
    }

    /** The logged calls: per call its tag and argv. */
    fun calls(): List<List<String>> {
        if (!log.isFile) return emptyList()
        val calls = mutableListOf<List<String>>()
        var current: MutableList<String>? = null
        log.readLines(Charsets.UTF_8).forEach { line ->
            when {
                line.startsWith("call:") -> current = mutableListOf(line.removePrefix("call:"))
                line.startsWith("arg:") -> current!!.add(line.removePrefix("arg:"))
                line == "end" -> calls += current!!.toList()
            }
        }
        return calls
    }

    fun clearCalls() {
        log.delete()
        ghEnvLog.delete()
    }

    /** The environment of a run: see the class comment; [env] overrides it. */
    fun environment(path: List<File> = listOf(stub, toolbox), env: Map<String, String?> = emptyMap()): Map<String, String?> =
        CLEARED.associateWith { null } +
            mapOf("PATH" to path.joinToString(File.pathSeparator) { it.path }, "OKFW_CACHE_DIR" to cache.shell) +
            env

    /**
     * Runs `sh ./okfw [args]` in [repo] after the PATH guard: `curl`, `gh` and `java` resolve inside the stub directory
     * or nowhere, `sha256sum` and `shasum` inside the toolbox or nowhere, exactly as the stubs and toolbox say.
     */
    fun okfw(vararg args: String, env: Map<String, String?> = emptyMap(), dir: File = repo): Run {
        val environment = environment(env = env)
        guard(environment)
        return Host.run(listOf(Host.sh.path, "./okfw") + args, dir, environment)
    }

    /** Asserts that the stub directory and toolbox decide which of `curl gh java sha256sum shasum` [environment] finds. */
    fun guard(environment: Map<String, String?>, expected: Map<String, String> = expectedTools()) {
        // A script file, not `sh -c`: Java does not escape the quotes of an argument on Windows.
        val script = root.resolve("guard.sh").apply { writeText(GUARD) }
        val probe = Host.run(listOf(Host.sh.path, script.shell, stub.shell, toolbox.shell), root, environment)
        assertEquals(0, probe.code, probe.toString())
        val found = probe.out.lines().filter { it.isNotEmpty() }.associate { it.substringBefore('=') to it.substringAfter('=') }
        assertEquals(expected, found, "PATH guard: the scenario must see only its stubs and toolbox")
    }

    private fun expectedTools(): Map<String, String> =
        GUARDED.associateWith { tool ->
            when {
                stub.resolve(tool).isFile -> "stub"
                tool in toolboxTools -> "toolbox"
                else -> "none"
            }
        }

    /** [dir] as `sh` prints it (`pwd`): `/c/…` or `/tmp/…` on Windows. */
    fun shPath(dir: File): String {
        val script = root.resolve("pwd.sh").apply { writeText("cd \"\$1\" && pwd\n") }
        return Host.run(listOf(Host.sh.path, script.shell, dir.shell), root, emptyMap()).out.trim()
    }

    /** The real `cat`, by absolute path: the stubs use it, the launcher does not, so it stays out of the toolbox. */
    private val cat = quote(checkNotNull(Host.tool("cat")) { "this machine has no cat" }.shell)

    private fun script(file: File, body: String) {
        file.writeText(
            "#!/bin/sh\n" +
                "log() { { printf 'call:%s\\n' \"\$1\"; shift; for a in \"\$@\"; do printf 'arg:%s\\n' \"\$a\"; done; printf 'end\\n'; } >> ${quote(log.shell)}; }\n" +
                body,
        )
        file.setExecutable(true, false)
    }

    companion object {
        /** What the launcher calls besides the SHA-256 tool, `curl` and `java`: the tool list of docs/launcher.md. */
        val BASE_TOOLS = listOf("sed", "grep", "tr", "head", "dirname", "wc", "mkdir", "mktemp", "mv", "rm")

        val GUARDED = listOf("curl", "gh", "java", "sha256sum", "shasum")

        /** What the `gh` stub reports: the variables `okfw` removes or sets for `gh`. */
        val GH_ENV = listOf(
            "GH_TOKEN", "GITHUB_TOKEN", "GH_ENTERPRISE_TOKEN", "GITHUB_ENTERPRISE_TOKEN", "GH_HOST", "GH_CONFIG_DIR",
            "GH_NO_UPDATE_NOTIFIER", "GH_PROMPT_DISABLED",
        )

        /** Removed from every child: they would bypass the stubs, leak credentials or move the cache. */
        val CLEARED = listOf(
            "PATH", "JAVA_HOME", "GH_TOKEN", "GITHUB_TOKEN", "GH_ENTERPRISE_TOKEN", "GITHUB_ENTERPRISE_TOKEN", "OKFW_PIN",
            "OKFW_CACHE_DIR", "XDG_CACHE_HOME", "GITHUB_ACTIONS",
        )

        private val GUARD =
            """
            stub=${'$'}(cd "${'$'}1" && pwd); box=${'$'}(cd "${'$'}2" && pwd)
            for tool in curl gh java sha256sum shasum; do
              found=${'$'}(command -v "${'$'}tool") || found=
              case ${'$'}found in
                '') where=none ;;
                "${'$'}stub"/*) where=stub ;;
                "${'$'}box"/*) where=toolbox ;;
                *) where="elsewhere:${'$'}found" ;;
              esac
              printf '%s=%s\n' "${'$'}tool" "${'$'}where"
            done
            """.trimIndent()

        fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"

        fun sha256(bytes: ByteArray): String =
            java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

/**
 * The real git in [box]'s repository, isolated from this machine's configuration: [env] points `GIT_CONFIG_GLOBAL` at an
 * empty `gitconfig` in the sandbox and turns the system configuration off. With [onPath], [path] is what a run's `PATH`
 * needs besides the stub directory and toolbox so that the jar's own git calls find `git`: the directory of `git.exe` on
 * Windows; on POSIX nothing, since a `git` link is created here in the stub directory. Without it, git stays off the
 * runs' `PATH` and no link is created. Create at most one with [onPath] per sandbox: on POSIX a second one fails, since
 * the link already exists.
 */
internal class SandboxGit(private val box: LauncherSandbox, onPath: Boolean = true) {
    private val globalConfig: File = box.root.resolve("gitconfig").apply { writeText("") }

    /** git's isolation, for every git call and every run that may call git. */
    val env: Map<String, String> = mapOf("GIT_CONFIG_GLOBAL" to globalConfig.path, "GIT_CONFIG_NOSYSTEM" to "1")

    val path: List<File> =
        when {
            !onPath -> emptyList()
            WINDOWS -> listOf(checkNotNull(Host.onPath("git.exe")) { "git.exe on the PATH" }.parentFile)
            else -> {
                val git = checkNotNull(Host.onPath("git")) { "git on the PATH" }
                Files.createSymbolicLink(box.stub.resolve("git").toPath(), git.toPath())
                emptyList()
            }
        }

    /** Makes the repo a git work tree on `main` with an isolated commit identity. */
    fun init() {
        this("init", "-q", "-b", "main")
        listOf("user.name" to "t", "user.email" to "t@t", "commit.gpgsign" to "false", "core.autocrlf" to "false")
            .forEach { (key, value) -> this("config", key, value) }
    }

    /** `git [args]` in the repo; fails unless it exits 0. Returns its stdout, trimmed. */
    operator fun invoke(vararg args: String): String {
        val run = Host.run(listOf("git") + args, box.repo, env)
        check(run.code == 0) { "git ${args.joinToString(" ")}: $run" }
        return run.out.trim()
    }
}
