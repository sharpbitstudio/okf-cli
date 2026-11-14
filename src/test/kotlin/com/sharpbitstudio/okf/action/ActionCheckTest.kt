package com.sharpbitstudio.okf.action

import com.sharpbitstudio.okf.launcher.Host
import com.sharpbitstudio.okf.launcher.Run
import com.sharpbitstudio.okf.launcher.WINDOWS
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.nio.file.Files

/**
 * `action/check.sh` (the committed copy, from `okf.repoRoot`) run by a real `sh` as action.yml runs it: in a git work
 * tree, with `GITHUB_ACTION_PATH` pointing to a temporary tree that holds a copy of `action/` and a STUB
 * `launcher/okfw`, which logs its argv with `OKFW_PIN` and `OKFW_CACHE_DIR` and exits with the codes a case gives it.
 * Before each run a guard checks that the stub is the launcher at that path and that `java`, `curl` and `gh` are on no
 * `PATH` entry of the run, so nothing but the stub can run a jar. Also checks action.yml against the contract.
 */
class ActionCheckTest {
    @TempDir
    lateinit var tmp: File

    private val repoRoot = File(checkNotNull(System.getProperty("okf.repoRoot")) { "the build passes okf.repoRoot" })

    /** `GITHUB_ACTION_PATH`: the action's files as a consumer's runner has them, with the stub as the launcher. */
    private lateinit var actionPath: File
    private lateinit var stubFile: File
    private lateinit var stubText: String
    private lateinit var log: File
    private lateinit var output: File

    /** The workspace: a git work tree, the working directory of every run. */
    private lateinit var work: File
    /** The run's whole `PATH`: `sh`, `grep`, `tr` and `git`, the tools check.sh uses. */
    private lateinit var toolbox: File
    private lateinit var gitConfig: File

    @BeforeEach
    fun setUp() {
        actionPath = tmp.resolve("action path")
        repoRoot.resolve("action").listFiles()!!.forEach { it.copyTo(actionPath.resolve("action/${it.name}")) }
        stubFile = actionPath.resolve("launcher/okfw").apply { parentFile.mkdirs() }
        log = tmp.resolve("calls.log")
        output = tmp.resolve("github output.txt")
        work = tmp.resolve("work space").apply { mkdirs() }
        gitConfig = tmp.resolve("gitconfig").apply { writeText("") }
        toolbox = tmp.resolve("tool box").apply { mkdirs() }
        link("sh", checkNotNull(Host.tool("sh")) { "this machine has no sh" })
        link("grep", checkNotNull(Host.tool("grep")) { "this machine has no grep" })
        link("tr", checkNotNull(Host.tool("tr")) { "this machine has no tr" })
        link("git", checkNotNull(onPath(if (WINDOWS) "git.exe" else "git")) { "git on the PATH" })
        git(work, "init", "-q")
        stub()
    }

    // --- argv per input combination ---

    @Test
    fun `defaults - check with every strict flag, impact with the table's arguments, then the version output`() {
        val run = action()
        assertEquals(Run(0, "stub check\nstub impact\n", line(CHECK_DEFAULT) + line(IMPACT_DEFAULT)), run)
        assertEquals(
            listOf(
                listOf("check", "--repo=.", "--strict=true", "--refs-strict=true", "--code-paths-required=true"),
                listOf("impact", "--repo=.", "--impact-strict=true"),
                listOf("version"),
            ),
            calls(),
        )
        assertEquals("version=1.2.3\n", output.readText())
    }

    @Test
    fun `every call gets the pin of the repo input and the cache in the runner's temporary directory`() {
        work.resolve("my repo").mkdirs()
        action(inputs = mapOf("repo" to "my repo"))
        val cache = "${tmp.shell}/runner temp/okf-cli-cache"
        assertEquals(
            listOf("my repo/okf-wrapper.properties" to cache),
            rawCalls().map { it.pin to it.cache }.distinct(),
        )
        assertEquals(listOf("--repo=my repo", "--repo=my repo"), rawCalls().flatMap { call -> call.args.filter { it.startsWith("--repo") } })
    }

    @Test
    fun `the check flags follow their inputs`() {
        val run = action(inputs = mapOf("strict" to "false", "refs-strict" to "true", "code-paths-required" to "false"))
        assertEquals(0, run.code, run.toString())
        assertEquals(
            listOf("check", "--repo=.", "--strict=false", "--refs-strict=true", "--code-paths-required=false"),
            calls().first(),
        )
        action(inputs = mapOf("strict" to "true", "refs-strict" to "false", "code-paths-required" to "true"))
        assertEquals(
            listOf("check", "--repo=.", "--strict=true", "--refs-strict=false", "--code-paths-required=true"),
            calls().first(),
        )
    }

    @Test
    fun `impact gets the table's base and strictness for the event, or the explicit inputs, as separate arguments`() {
        val pr = mapOf("OKF_EVENT" to "pull_request", "OKF_REF" to "refs/pull/4/merge", "OKF_PR_BASE_REF" to "main", "OKF_PR_AUTHOR" to "alice")
        val cases = listOf(
            Triple(pr, emptyMap<String, String>(), listOf("--base=origin/main", "--impact-strict=true")),
            Triple(pr + ("OKF_PR_AUTHOR" to "dependabot[bot]"), emptyMap<String, String>(), listOf("--base=origin/main", "--impact-strict=false")),
            Triple(PUSH_FEATURE, mapOf("base" to "origin/a branch; echo x", "impact-strict" to "false"), listOf("--base=origin/a branch; echo x", "--impact-strict=false")),
            Triple(PUSH_FEATURE, mapOf("base" to ""), listOf("--impact-strict=true")),
            Triple(PUSH_FEATURE, mapOf("base" to "   "), listOf("--impact-strict=true")),
            Triple(PUSH_FEATURE, mapOf("base" to " origin/main "), listOf("--base= origin/main ", "--impact-strict=true")),
            // A push to the default branch without a `before`: an empty base, which okf impact skips.
            Triple(PUSH_FEATURE + mapOf("OKF_REF" to "refs/heads/main", "OKF_BEFORE" to ""), emptyMap<String, String>(), listOf("--base=", "--impact-strict=false")),
            Triple(PUSH_FEATURE, mapOf("base" to "\$HOME", "impact-strict" to "auto"), listOf("--base=\$HOME", "--impact-strict=true")),
            Triple(PUSH_FEATURE, mapOf("base" to "a \"b'c `id` *"), listOf("--base=a \"b'c `id` *", "--impact-strict=true")),
        )
        for ((event, inputs, expected) in cases) {
            val run = action(inputs = inputs, event = event)
            assertEquals(0, run.code, run.toString())
            assertEquals(listOf("impact", "--repo=.") + expected, calls()[1], "$event $inputs")
            assertTrue(run.err.endsWith(line("okf impact --repo=. ${expected.joinToString(" ")}")), run.err)
        }
    }

    // --- invalid inputs ---

    @Test
    fun `an invalid input value is one error annotation and exit 2, nothing runs`() {
        val bools = listOf("strict", "refs-strict", "code-paths-required", "impact", "verify-provenance")
        for (input in bools) {
            for (value in listOf("", "yes", "TRUE", "true ", "auto")) {
                val run = action(inputs = mapOf(input to value))
                assertEquals(Run(2, "::error title=okf-cli::input '$input' must be true or false\n", ""), run, "$input='$value'")
                assertEquals(emptyList<List<String>>(), calls(), "$input='$value'")
                assertFalse(output.exists(), "$input='$value'")
            }
        }
        val emptyRepo = action(inputs = mapOf("repo" to ""))
        assertEquals(Run(2, "::error title=okf-cli::input 'repo' must not be empty\n", ""), emptyRepo)
        assertEquals(emptyList<List<String>>(), calls())
        assertFalse(output.exists())
        for (value in listOf("", "yes", "False", "auto ")) {
            val run = action(inputs = mapOf("impact-strict" to value))
            assertEquals(Run(2, "::error title=okf-cli::input 'impact-strict' must be auto, true or false\n", ""), run, "impact-strict='$value'")
            assertEquals(emptyList<List<String>>(), calls())
        }
    }

    @Test
    fun `a repo or base with a control character is an error, so a value can never become another argument`() {
        val values = listOf("origin/main\n--help", "origin/main\n", "origin/main\t", "origin/main\r", "a\u0001b", "a\u001fb", "a\u007fb")
        for (input in listOf("base", "repo")) {
            for (value in values) {
                val run = action(inputs = mapOf(input to value))
                assertEquals(
                    Run(2, "::error title=okf-cli::input '$input' must be one line without control characters\n", ""),
                    run,
                    "$input=${value.toByteArray().toList()}",
                )
                assertEquals(emptyList<List<String>>(), calls())
                assertFalse(output.exists())
            }
        }
        // Text outside ASCII is no control character.
        assertEquals(0, action(inputs = mapOf("base" to "origin/café")).code)
        assertEquals("--base=origin/café", calls()[1][2])
    }

    // --- the shallow guard ---

    @Test
    fun `a shallow clone with impact on is an error, with impact off it is checked`() {
        val origin = tmp.resolve("origin").apply { mkdirs() }
        git(origin, "init", "-q")
        for (n in 1..2) {
            origin.resolve("f.txt").writeText("$n\n")
            git(origin, "add", "f.txt")
            git(origin, "commit", "-q", "-m", "c$n")
        }
        val url = "file://" + (if (WINDOWS) "/" else "") + origin.invariantSeparatorsPath
        git(work, "clone", "-q", "--depth", "1", url, "shallow clone")
        assertEquals("true", git(work.resolve("shallow clone"), "rev-parse", "--is-shallow-repository"))

        val run = action(inputs = mapOf("repo" to "shallow clone"))
        assertEquals(
            Run(2, "::error title=okf-cli::okf impact needs the full history: check out with fetch-depth: 0 (or set impact: false)\n", ""),
            run,
        )
        assertEquals(emptyList<List<String>>(), calls())

        val off = action(inputs = mapOf("repo" to "shallow clone", "impact" to "false"))
        assertEquals(0, off.code, off.toString())
        assertEquals(listOf("check", "version"), calls().map { it.first() })

        // A full clone of the same history passes the guard.
        git(work, "clone", "-q", url, "full clone")
        assertEquals(0, action(inputs = mapOf("repo" to "full clone")).code)
        assertEquals(listOf("check", "impact", "version"), calls().map { it.first() })
    }

    // --- exit codes ---

    @Test
    fun `impact is skipped after a check exit of 2 or 3, and runs after 1`() {
        for (code in listOf(2, 3)) {
            stub(check = code)
            val run = action()
            assertEquals(Run(code, "stub check\n", line(CHECK_DEFAULT)), run, "check exit $code")
            assertEquals(listOf("check", "version"), calls().map { it.first() }, "check exit $code")
        }
        stub(check = 1)
        val run = action()
        assertEquals(1, run.code)
        assertEquals(listOf("check", "impact", "version"), calls().map { it.first() })
    }

    @Test
    fun `the exit code is the first non-zero of check and impact`() {
        val cases = listOf(
            Triple(0, 0, 0),
            Triple(1, 0, 1),
            Triple(0, 1, 1),
            Triple(1, 4, 1),
            Triple(0, 4, 4),
            Triple(5, 1, 5),
        )
        for ((check, impact, expected) in cases) {
            stub(check = check, impact = impact)
            assertEquals(expected, action().code, "check $check, impact $impact")
        }
    }

    @Test
    fun `impact false runs check only`() {
        stub(impact = 1)
        val run = action(inputs = mapOf("impact" to "false"))
        assertEquals(Run(0, "stub check\n", line(CHECK_DEFAULT)), run)
        assertEquals(listOf("check", "version"), calls().map { it.first() })
    }

    // --- verify-provenance ---

    @Test
    fun `verify-provenance runs okfw --verify first`() {
        val run = action(inputs = mapOf("verify-provenance" to "true"))
        assertEquals(Run(0, "stub verify\nstub check\nstub impact\n", line("okfw --verify") + line(CHECK_DEFAULT) + line(IMPACT_DEFAULT)), run)
        assertEquals(listOf(listOf("--verify"), listOf("check"), listOf("impact"), listOf("version")), calls().map { it.take(1) })
    }

    @Test
    fun `a failing okfw --verify stops the run with its exit code`() {
        stub(verify = 3)
        val run = action(inputs = mapOf("verify-provenance" to "true"))
        assertEquals(Run(3, "stub verify\n", line("okfw --verify")), run)
        assertEquals(listOf(listOf("--verify")), calls())
        assertFalse(output.exists())
    }

    // --- the version output ---

    @Test
    fun `the version output is written only for a version such as release yml accepts`() {
        val cases = listOf(
            "okf-cli 1.2.3\n" to "1.2.3",
            "okf-cli 1.2.3\r\n" to "1.2.3",
            "okf-cli 1.0.0-alpha.1\n" to "1.0.0-alpha.1",
            "okf-cli 0.0.0-e2e.1" to "0.0.0-e2e.1",
            "okf-cli v1.2.3\n" to null,
            "okf-cli 1.2\n" to null,
            "okf-cli 01.2.3\n" to null,
            "okf-cli 1.2.3+build.5\n" to null,
            "okf-cli 1.2.3\nokf-cli 1.2.4\n" to null,
            "okf-cli 1.2.3\r\r\n" to "1.2.3",
            "okf-cli 1.2.3\r\nx\n" to null,
            "okf-cli 1.2.3\rx\n" to null,
            "okf-cli  1.2.3\n" to null,
            "1.2.3\n" to null,
            "" to null,
        )
        for ((printed, expected) in cases) {
            stub(version = printed)
            output.delete()
            assertEquals(0, action().code, printed)
            assertEquals(expected?.let { "version=$it\n" }, output.takeIf { it.exists() }?.readText(), printed)
        }
    }

    @Test
    fun `no version output when okf version fails, appended to what GITHUB_OUTPUT holds, none without GITHUB_OUTPUT`() {
        stub(versionExit = 1)
        assertEquals(0, action().code)
        assertFalse(output.exists())

        stub()
        output.writeText("earlier=1\n")
        assertEquals(0, action().code)
        assertEquals("earlier=1\nversion=1.2.3\n", output.readText())

        output.delete()
        assertEquals(0, action(env = mapOf("GITHUB_OUTPUT" to null)).code)
        assertFalse(output.exists())
    }

    // --- action.yml ---

    @Test
    fun `action yml - inputs, defaults, output and one run step that passes everything through env`() {
        @Suppress("UNCHECKED_CAST")
        val action = Yaml().load<Map<String, Any?>>(repoRoot.resolve("action.yml").readText()) as Map<String, Map<String, Any?>>
        val inputs = action.getValue("inputs").mapValues { (_, spec) -> (spec as Map<*, *>)["default"] }
        assertEquals(
            mapOf(
                "repo" to ".", "strict" to "true", "refs-strict" to "true", "code-paths-required" to "true", "impact" to "true",
                "base" to "auto", "impact-strict" to "auto", "verify-provenance" to "false",
            ),
            inputs,
        )
        assertEquals(setOf("version"), action.getValue("outputs").keys)
        assertEquals("\${{ steps.okf.outputs.version }}", (action.getValue("outputs")["version"] as Map<*, *>)["value"])

        val runs = action.getValue("runs")
        assertEquals("composite", runs["using"])
        val steps = runs["steps"] as List<*>
        assertEquals(1, steps.size, "one step, no other action")
        val step = steps.single() as Map<*, *>
        assertEquals(setOf("id", "shell", "env", "run"), step.keys)
        assertEquals("bash", step["shell"])
        assertEquals("sh \"\$GITHUB_ACTION_PATH/action/check.sh\"", step["run"])
        val env = step["env"] as Map<*, *>
        val expected = inputs.keys.associate { "OKF_INPUT_" + it.uppercase().replace('-', '_') to "\${{ inputs.$it }}" } +
            mapOf(
                "OKF_EVENT" to "\${{ github.event_name }}",
                "OKF_REF" to "\${{ github.ref }}",
                "OKF_DEFAULT_BRANCH" to "\${{ github.event.repository.default_branch }}",
                "OKF_BEFORE" to "\${{ github.event.before }}",
                "OKF_MERGE_GROUP_BASE_SHA" to "\${{ github.event.merge_group.base_sha }}",
                "OKF_PR_BASE_REF" to "\${{ github.base_ref }}",
                "OKF_PR_AUTHOR" to "\${{ github.event.pull_request.user.login }}",
                "OKF_ACTOR" to "\${{ github.actor }}",
                "OKFW_PIN" to "\${{ inputs.repo }}/okf-wrapper.properties",
                "OKFW_CACHE_DIR" to "\${{ runner.temp }}/okf-cli-cache",
            )
        assertEquals(expected, env)
    }

    // --- harness ---

    /** One stub call: its argv and the launcher variables it saw. */
    private data class Call(val args: List<String>, val pin: String, val cache: String)

    /**
     * Writes the stub launcher: `--verify`, `check` and `impact` print `stub <command>` and exit with the given code;
     * `version` prints [version] as given and exits with [versionExit]; anything else fails the run.
     */
    private fun stub(check: Int = 0, impact: Int = 0, verify: Int = 0, version: String = "okf-cli 1.2.3\n", versionExit: Int = 0) {
        stubText = "#!/bin/sh\n" +
            "# okf-cli action test stub: never a real launcher\n" +
            "{ printf 'call\\n'; printf 'pin=%s\\n' \"\${OKFW_PIN-unset}\"; printf 'cache=%s\\n' \"\${OKFW_CACHE_DIR-unset}\"\n" +
            "  for a in \"\$@\"; do printf 'arg=%s\\n' \"\$a\"; done; printf 'end\\n'; } >> ${quote(log.shell)}\n" +
            "case \${1-} in\n" +
            "  --verify) echo 'stub verify'; exit $verify ;;\n" +
            "  check) echo 'stub check'; exit $check ;;\n" +
            "  impact) echo 'stub impact'; exit $impact ;;\n" +
            "  version) printf '%s' ${quote(version)}; exit $versionExit ;;\n" +
            "esac\n" +
            "echo \"stub: unexpected \$1\" >&2\n" +
            "exit 99\n"
        stubFile.writeText(stubText)
    }

    /**
     * Runs check.sh as action.yml does: the [inputs] over the defaults, the event facts [event], `OKFW_PIN` and
     * `OKFW_CACHE_DIR` as action.yml derives them; [env] overrides the rest. Clears the call log and the output first.
     */
    private fun action(inputs: Map<String, String> = emptyMap(), event: Map<String, String> = PUSH_FEATURE, env: Map<String, String?> = emptyMap()): Run {
        log.delete()
        val all = INPUT_DEFAULTS + inputs
        val environment: Map<String, String?> =
            CLEARED.associateWith { null } +
                all.mapKeys { (name, _) -> "OKF_INPUT_" + name.uppercase().replace('-', '_') } +
                EVENT_FACTS.associateWith { "" } + event +
                mapOf(
                    "PATH" to toolbox.path,
                    "GITHUB_ACTION_PATH" to actionPath.shell,
                    "GITHUB_OUTPUT" to output.shell,
                    "OKFW_PIN" to "${all.getValue("repo")}/okf-wrapper.properties",
                    "OKFW_CACHE_DIR" to "${tmp.shell}/runner temp/okf-cli-cache",
                    "GIT_CONFIG_GLOBAL" to gitConfig.path,
                    "GIT_CONFIG_NOSYSTEM" to "1",
                ) + env
        guard(environment)
        return Host.run(listOf(Host.sh.path, "${actionPath.shell}/action/check.sh"), work, environment).lf()
    }

    /** Aborts the test unless the launcher at `GITHUB_ACTION_PATH` is the stub and no `java`, `curl` or `gh` is found. */
    private fun guard(environment: Map<String, String?>) {
        check(stubFile.readText() == stubText) { "the launcher in GITHUB_ACTION_PATH is not the stub" }
        val probe = tmp.resolve("guard.sh").apply {
            writeText("for t in java curl gh; do command -v \"\$t\"; done; exit 0\n")
        }
        val run = Host.run(listOf(Host.sh.path, probe.shell), tmp, environment)
        check(run.code == 0 && run.out.isEmpty()) { "PATH guard: the run must find no java, curl or gh: $run" }
    }

    private fun rawCalls(): List<Call> {
        if (!log.isFile) return emptyList()
        val calls = mutableListOf<Call>()
        var args = mutableListOf<String>()
        var pin = ""
        var cache = ""
        log.readLines(Charsets.UTF_8).forEach { line ->
            when {
                line == "call" -> args = mutableListOf()
                line.startsWith("pin=") -> pin = line.removePrefix("pin=")
                line.startsWith("cache=") -> cache = line.removePrefix("cache=")
                line.startsWith("arg=") -> args += line.removePrefix("arg=")
                line == "end" -> calls += Call(args.toList(), pin, cache)
            }
        }
        return calls
    }

    /** The argv of each launcher call. */
    private fun calls(): List<List<String>> = rawCalls().map { it.args }

    /** An action line on standard error. */
    private fun line(text: String) = "okf-cli action: $text\n"

    private fun link(name: String, real: File) {
        val link = toolbox.resolve(name)
        if (WINDOWS) {
            link.writeText("#!/bin/sh\nexec ${quote(real.invariantSeparatorsPath)} \"\$@\"\n")
        } else {
            Files.createSymbolicLink(link.toPath(), real.toPath())
        }
    }

    private fun git(dir: File, vararg args: String): String {
        val run = Host.run(listOf("git") + args, dir, mapOf("GIT_CONFIG_GLOBAL" to gitConfig.path, "GIT_CONFIG_NOSYSTEM" to "1"))
        check(run.code == 0) { "git ${args.joinToString(" ")}: $run" }
        if (args.first() == "init") {
            listOf("user.name" to "t", "user.email" to "t@t", "commit.gpgsign" to "false", "core.autocrlf" to "false")
                .forEach { (key, value) -> git(dir, "config", key, value) }
        }
        return run.out.trim()
    }

    private fun onPath(name: String): File? =
        System.getenv("PATH").orEmpty().split(File.pathSeparator).filter { it.isNotEmpty() }.map { File(it, name) }.firstOrNull { it.isFile }

    /** [this] as the shell sees it in an argument or variable: with `/` on Windows. */
    private val File.shell: String get() = if (WINDOWS) invariantSeparatorsPath else path

    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"

    companion object {
        private val INPUT_DEFAULTS = mapOf(
            "repo" to ".", "strict" to "true", "refs-strict" to "true", "code-paths-required" to "true", "impact" to "true",
            "base" to "auto", "impact-strict" to "auto", "verify-provenance" to "false",
        )

        private val EVENT_FACTS = listOf(
            "OKF_EVENT", "OKF_REF", "OKF_DEFAULT_BRANCH", "OKF_BEFORE", "OKF_MERGE_GROUP_BASE_SHA", "OKF_PR_BASE_REF",
            "OKF_PR_AUTHOR", "OKF_ACTOR",
        )

        /** A push to a feature branch by a person: impact gets `--impact-strict=true` and no base. */
        private val PUSH_FEATURE = mapOf(
            "OKF_EVENT" to "push", "OKF_REF" to "refs/heads/feature/x", "OKF_DEFAULT_BRANCH" to "main",
            "OKF_BEFORE" to "1b2c3d4e5f60718293a4b5c6d7e8f90123456789", "OKF_ACTOR" to "alice",
        )

        /** Removed from every run: they would find a real Java or move the launcher's files. */
        private val CLEARED = listOf("PATH", "JAVA_HOME", "GH_TOKEN", "GITHUB_TOKEN", "OKFW_PIN", "OKFW_CACHE_DIR", "XDG_CACHE_HOME", "GITHUB_ACTIONS")

        private const val CHECK_DEFAULT = "okf check --repo=. --strict=true --refs-strict=true --code-paths-required=true"
        private const val IMPACT_DEFAULT = "okf impact --repo=. --impact-strict=true"
    }
}
