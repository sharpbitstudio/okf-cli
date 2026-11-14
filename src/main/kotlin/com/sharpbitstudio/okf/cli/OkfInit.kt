package com.sharpbitstudio.okf.cli

import com.sharpbitstudio.okf.Frontmatter
import com.sharpbitstudio.okf.IndexLanguage
import com.sharpbitstudio.okf.OkfChecker
import com.sharpbitstudio.okf.OkfConfig
import com.sharpbitstudio.okf.OkfConfigException
import com.sharpbitstudio.okf.OkfException
import com.sharpbitstudio.okf.OkfFrontmatter
import com.sharpbitstudio.okf.OkfIndexRenderer
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.error.YAMLException
import java.io.File
import java.io.IOException
import java.io.PrintStream
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * `okf init`: writes `okf.yaml` and the bundle-root `<docs_root>/index.md` for a new bundle, and unless
 * `--launcher=false` the launcher files, the pin and `.gitattributes` as `okf wrapper` writes them ([OkfWrapper]), all or
 * nothing. It never changes an existing file: anything in the way is reported as a conflict (exit
 * [OkfCli.EXIT_FINDINGS]) before a file is written, and a write that fails midway removes what this run created; an
 * existing launcher file identical to the embedded one, an existing pin and an existing `.gitattributes` are kept. An
 * invalid `--name`, `--docs-root` or `--index-command` is an [OkfConfigException] (exit [OkfCli.EXIT_USAGE]); the values
 * are validated by parsing the `okf.yaml` text about to be written ([OkfConfig.parse]) and must read back unchanged.
 * With `--adopt`, an existing docs root holding documents becomes the bundle; its files stay as they are, and a valid
 * bundle-root `index.md` is kept.
 */
internal class OkfInit(private val out: PrintStream, private val err: PrintStream, private val self: SelfJar) {
    /** Runs `okf init` for [args] in the work-tree root `args.repo` (already checked); returns the exit code. */
    fun run(args: CliArgs): Int {
        val repo = args.repo
        val launcher = args.launcher
        val defaultName = args.name == null
        val name = args.name ?: repo.canonicalFile.name
        val docsRoot = args.docsRoot ?: OkfConfig.DEFAULT_DOCS_ROOT
        val indexCommand = args.indexCommand ?: if (launcher) {
            LAUNCHER_INDEX_COMMAND
        } else {
            throw OkfConfigException(
                "${CliArgs.INDEX_COMMAND}=<command> is required: the one-line command that regenerates the indexes in this repository " +
                    "(with ${CliArgs.LAUNCHER}=false)",
            )
        }
        if (!REPO_NAME.matches(name) || name == "." || name == "..") {
            throw OkfConfigException(
                if (defaultName) {
                    "the directory name '${name.escapeControls()}' cannot be the repository name (letters, digits, '.', '_' or '-') " +
                        "— pass ${CliArgs.NAME}=<repo>"
                } else {
                    "${CliArgs.NAME} '${name.escapeControls()}' must be a repository name (letters, digits, '.', '_' or '-')"
                },
            )
        }
        docsRootName(docsRoot)
        val config = renderConfig(name, docsRoot, indexCommand).toByteArray(Charsets.UTF_8)
        readBack(String(config, Charsets.UTF_8), name, docsRoot, indexCommand)
        preflight(docsRoot, launcher)

        val bundle = repo.resolve(docsRoot)
        val rootIndex = bundle.resolve(ROOT_INDEX_NAME)
        val markdown = if (bundle.isDirectory) bundle.walkTopDown().filter { it.isFile && it.extension == "md" }.toList() else emptyList()
        val subDirs = if (bundle.isDirectory) SUB_DIRS.filter { bundle.resolve(it).isDirectory } else emptyList()
        val launcherStates = if (launcher) Launcher.files.map { it to Launcher.state(repo, it) } else emptyList()
        val problems = conflicts(repo, docsRoot, bundle, markdown, subDirs, args.adopt) + launcherConflicts(launcherStates)
        if (problems.isNotEmpty()) {
            err.println("okf init found ${problems.size} problem(s); nothing was written:\n" + problems.joinToString("\n") { "  - $it" })
            return OkfCli.EXIT_FINDINGS
        }

        val keepIndex = args.adopt && rootIndex.isFile
        val pinPresent = launcher && repo.resolve(Launcher.PIN).present()
        val pin = if (launcher && !pinPresent) self.pin() else null
        val attributesPresent = launcher && repo.resolve(Launcher.ATTRIBUTES).present()
        val lines = mutableListOf<String>()
        writeAll(repo) { created ->
            created.file(OkfConfig.FILE_NAME, config)
            lines += "[created] ${OkfConfig.FILE_NAME}"
            if (keepIndex) {
                lines += "[kept] $docsRoot/$ROOT_INDEX_NAME"
            } else {
                created.directories(docsRoot)
                created.file("$docsRoot/$ROOT_INDEX_NAME", ROOT_INDEX.toByteArray(Charsets.UTF_8))
                lines += "[created] $docsRoot/$ROOT_INDEX_NAME"
            }
            // The launcher as `okf wrapper` writes it, create-only: a differing launcher file is a conflict (above).
            launcherStates.forEach { (file, state) ->
                if (state == Launcher.State.ABSENT) {
                    created.file(file.name, file.bytes, file.executable)
                    lines += "[created] ${file.name}"
                } else {
                    lines += "[kept] ${file.name}"
                }
            }
            when {
                pinPresent -> lines += "[kept] ${Launcher.PIN}"
                pin != null -> {
                    created.file(Launcher.PIN, pin)
                    lines += "[created] ${Launcher.PIN}"
                }
            }
            if (launcher && !attributesPresent) {
                created.file(Launcher.ATTRIBUTES, Launcher.ATTRIBUTES_TEXT.toByteArray(Charsets.US_ASCII))
                lines += "[created] ${Launcher.ATTRIBUTES}"
            }
        }
        lines.forEach(out::println)
        if (args.adopt) {
            val unchanged = markdown.size - if (keepIndex) 1 else 0
            if (unchanged > 0) {
                out.println("OKF INFO: $docsRoot/ holds $unchanged Markdown file(s) that were not changed; okf check ${CliArgs.STRICT} lists what to migrate.")
            }
            subDirs.forEach { out.println("OKF INFO: $docsRoot/$it/$ROOT_INDEX_NAME is generated: ${indexCommand.escapeControls()} creates or replaces it.") }
        }
        if (launcher) {
            if (!pinPresent && pin == null) out.println(Launcher.noPinNotice(self.version))
            if (attributesPresent && Launcher.attributesMissing(repo)) out.println(Launcher.ATTRIBUTES_NOTICE)
            if (launcherStates.any { (file, state) -> file.executable && state == Launcher.State.ABSENT }) out.println(Launcher.CHMOD_NOTICE)
        }
        out.println("okf init OK: ${lines.count { it.startsWith("[created]") }} file(s) created.")
        return OkfCli.EXIT_OK
    }

    /**
     * The values must survive the trip through `okf.yaml`: [OkfConfig.parse] accepts [text] (else its message), reads
     * back exactly the requested values (it trims, so surrounding blanks are lost) and leaves everything else at the
     * default of a config that sets only `repo`, `docs_root`, `index_command` and `index_language: en`.
     */
    private fun readBack(text: String, name: String, docsRoot: String, indexCommand: String) {
        val config = OkfConfig.parse(text)
        listOf(
            Triple(CliArgs.NAME, name, config.repo),
            Triple(CliArgs.DOCS_ROOT, docsRoot, config.docsRoot),
            Triple(CliArgs.INDEX_COMMAND, indexCommand, config.indexCommand),
        ).firstOrNull { (_, requested, read) -> requested != read }?.let { (option, requested, _) ->
            throw OkfConfigException("$option '${requested.escapeControls()}' cannot be written to ${OkfConfig.FILE_NAME} as given")
        }
        val expected = OkfConfig(repo = name, docsRoot = docsRoot, indexCommand = indexCommand, indexLanguage = IndexLanguage.EN)
        check(config == expected) { "okf.yaml written by init reads back as $config, expected $expected" }
    }

    /**
     * A docs root that `init` creates must name the same directory on every file system: no control characters or line
     * separators, and no segment ending in `.` or a blank (Windows drops those). The segments `.` and `..` are left to
     * the `docs_root` rules of [OkfConfig.parse], which name them.
     */
    private fun docsRootName(docsRoot: String) {
        val control = docsRoot.any { it.isLineBreakOrControl() }
        val trailing = docsRoot.split('/').any { it != "." && it != ".." && (it.endsWith('.') || it.lastOrNull()?.isWhitespace() == true) }
        if (control || trailing) {
            throw OkfConfigException(
                "${CliArgs.DOCS_ROOT} '${docsRoot.escapeControls()}' must not contain control characters or a segment ending in '.' or a blank",
            )
        }
    }

    /**
     * The docs root must not be, or lie below, `okf.yaml` (written by this run), `.git` or, with the launcher, a path the
     * launcher writes ([Launcher.ROOT_PATHS]). Compared ignoring case, so the check does not depend on the file system.
     * The check is by spelling, not by file-system identity: short names and other aliases are not resolved.
     */
    private fun preflight(docsRoot: String, launcher: Boolean) {
        val first = docsRoot.substringBefore('/')
        val reserved = listOf(OkfConfig.FILE_NAME, GIT_DIR) + if (launcher) Launcher.ROOT_PATHS else emptyList()
        val collision = reserved.firstOrNull { it.equals(first, ignoreCase = true) } ?: return
        throw OkfConfigException("${CliArgs.DOCS_ROOT} '${docsRoot.escapeControls()}' collides with $collision")
    }

    /** Every reason not to write, in report order; empty when the run may write. */
    private fun conflicts(repo: File, docsRoot: String, bundle: File, markdown: List<File>, subDirs: List<String>, adopt: Boolean): List<String> =
        buildList {
            if (repo.resolve(OkfConfig.FILE_NAME).present()) {
                add("${OkfConfig.FILE_NAME} already exists — this repository is already set up (okf ${Command.WRAPPER.cliName} adds or refreshes the launcher)")
            }
            val segments = docsRoot.split('/')
            segments.indices.map { segments.subList(0, it + 1).joinToString("/") }
                .firstOrNull { repo.resolve(it).let { file -> file.present() && !file.isDirectory } }
                ?.let { add("$it exists and is not a directory") }
            val alternative = "or ${CliArgs.DOCS_ROOT}=<dir> for a separate bundle"
            if (!adopt) {
                if (markdown.isNotEmpty()) add(markdownConflict(repo, docsRoot, bundle, markdown, alternative))
                subDirs.forEach { add("$docsRoot/$it/ already exists — pass ${CliArgs.ADOPT} to use $docsRoot/ as the bundle, $alternative") }
            } else {
                val rootIndex = bundle.resolve(ROOT_INDEX_NAME)
                if (rootIndex.isFile) {
                    OkfChecker.checkRootIndex(frontmatter(repo, rootIndex)).firstOrNull()?.let {
                        add("$docsRoot/$ROOT_INDEX_NAME exists but is not a bundle root: $it")
                    }
                }
            }
        }

    /** A launcher file in the way: `init` never replaces one ([OkfWrapper] does, when it carries the marker). */
    private fun launcherConflicts(states: List<Pair<Launcher.LauncherFile, Launcher.State>>): List<String> =
        states.mapNotNull { (file, state) ->
            when (state) {
                Launcher.State.DIFFERENT ->
                    "${file.name} already exists — init never replaces it; run okf ${Command.WRAPPER.cliName} to refresh an okf-cli launcher, " +
                        "or pass ${CliArgs.LAUNCHER}=false"
                Launcher.State.FOREIGN -> "${file.name} exists and is not an okf-cli launcher — move it away, or pass ${CliArgs.LAUNCHER}=false"
                Launcher.State.ABSENT, Launcher.State.IDENTICAL -> null
            }
        }

    /** The conflict for Markdown files already in the docs root, counted by the state of their frontmatter. */
    private fun markdownConflict(repo: File, docsRoot: String, bundle: File, markdown: List<File>, alternative: String): String {
        var absent = 0
        var present = 0
        var invalid = 0
        var subIndex = 0
        markdown.forEach { file ->
            when (frontmatter(repo, file)) {
                Frontmatter.Absent -> absent++
                is Frontmatter.Invalid -> invalid++
                is Frontmatter.Present ->
                    if (file.name == ROOT_INDEX_NAME && file.relativeTo(bundle).invariantSeparatorsPath != ROOT_INDEX_NAME) subIndex++ else present++
            }
        }
        return "$docsRoot/ already holds ${markdown.size} Markdown file(s) ($absent without frontmatter, $present with frontmatter, " +
            "$invalid with invalid frontmatter, $subIndex non-root $ROOT_INDEX_NAME with frontmatter) — pass ${CliArgs.ADOPT} to use it " +
            "as the bundle (no existing file is changed), $alternative"
    }

    /** The frontmatter of [file]; a file that cannot be read is an [OkfException] naming its repo-relative path. */
    private fun frontmatter(repo: File, file: File): Frontmatter =
        try {
            OkfFrontmatter.read(file)
        } catch (e: IOException) {
            throw OkfException("cannot read ${file.relativeTo(repo).invariantSeparatorsPath}: ${reason(e)}")
        }

    internal companion object {
        /** The `index_command` written when the launcher is written and `--index-command` is absent. */
        const val LAUNCHER_INDEX_COMMAND = "./okfw index"

        /** The bundle-root index file, the only file of the bundle that `init` writes. */
        private const val ROOT_INDEX_NAME = "index.md"
        private const val GIT_DIR = ".git"
        private val SUB_DIRS = listOf(OkfIndexRenderer.ADR_DIR, OkfIndexRenderer.FEATURES_DIR)

        /** What a repository name may hold (`--name`, or the work-tree root's directory name). */
        private val REPO_NAME = Regex("[A-Za-z0-9._-]+")

        /** A scalar written plain only when it matches this and reads back as itself ([scalar]); otherwise it is quoted. */
        private val SAFE_PLAIN = Regex("[A-Za-z0-9_./]([A-Za-z0-9_./+=@~ -]*[A-Za-z0-9_./+=@~-])?")

        /** What a YAML 1.2 core-schema reader takes for a number or a special float; such a value is quoted ([scalar]). */
        private val YAML12_NUMBERS = listOf(
            Regex("[-+]?(\\.[0-9]+|[0-9]+(\\.[0-9]*)?)([eE][-+]?[0-9]+)?"),
            Regex("0o[0-7]+"),
            Regex("0x[0-9a-fA-F]+"),
            Regex("[-+]?\\.(inf|Inf|INF)"),
            Regex("\\.(nan|NaN|NAN)"),
        )

        /** The starter bundle-root `index.md`. */
        const val ROOT_INDEX =
            "---\n" +
                "okf_version: \"0.2\"\n" +
                "---\n" +
                "# Documentation\n" +
                "\n" +
                "This directory is an [OKF](https://github.com/GoogleCloudPlatform/open-knowledge-format) bundle, checked by\n" +
                "[okf-cli](https://github.com/sharpbitstudio/okf-cli).\n"

        /** The starter `okf.yaml`; `docs_root` is written only when it is not the default. */
        fun renderConfig(name: String, docsRoot: String, indexCommand: String): String =
            buildString {
                append("# OKF (Open Knowledge Format) settings of this repository: https://github.com/sharpbitstudio/okf-cli/blob/main/docs/reference.md\n")
                append("schema_version: ${OkfConfig.SCHEMA_VERSION}\n")
                append("repo: ${scalar(name)}\n")
                if (docsRoot != OkfConfig.DEFAULT_DOCS_ROOT) append("docs_root: ${scalar(docsRoot)}\n")
                append("index_language: ${IndexLanguage.EN.value}\n")
                append("index_command: ${scalar(indexCommand)}\n")
                append("# ref_extensions: [ts, tsx]    # file extensions (no dot) that code references in the docs may end with, besides the built-in ones\n")
                append("# source_roots: [app/src]      # extra repo-relative code roots\n")
            }

        /**
         * [value] as a YAML scalar: plain when that is safe, reads back as the same string and is no YAML 1.2 number,
         * else double-quoted.
         */
        fun scalar(value: String): String =
            if (SAFE_PLAIN.matches(value) && YAML12_NUMBERS.none { it.matches(value) } && readsAsItself(value)) value else quoted(value)

        private fun readsAsItself(value: String): Boolean =
            try {
                (yaml().load<Any?>("value: $value") as? Map<*, *>)?.get("value") == value
            } catch (_: YAMLException) {
                false
            }

        /** A double-quoted YAML scalar; `\`, `"`, control characters and the characters YAML reads as line breaks are escaped. */
        private fun quoted(value: String): String =
            buildString {
                append('"')
                value.forEach { c ->
                    when {
                        c == '\\' -> append("\\\\")
                        c == '"' -> append("\\\"")
                        c == '\n' -> append("\\n")
                        c == '\t' -> append("\\t")
                        c == '\r' -> append("\\r")
                        c.code < 0x100 && c.isISOControl() -> append("\\x%02x".format(c.code))
                        c.isISOControl() || c in "\u2028\u2029\uFEFF\uFFFE\uFFFF" -> append("\\u%04x".format(c.code))
                        else -> append(c)
                    }
                }
                append('"')
            }

        // SnakeYAML's stock resolver, as other YAML 1.1 readers of okf.yaml see it: a date-like value is quoted, although
        // OkfConfig itself would read it as text.
        private fun yaml(): Yaml = Yaml(SafeConstructor(LoaderOptions()))

        /** A control character, or a line or paragraph separator (U+2028, U+2029). */
        private fun Char.isLineBreakOrControl() = isISOControl() || code == 0x2028 || code == 0x2029

        private fun String.escapeControls() = map { if (it.isLineBreakOrControl()) "\\u%04x".format(it.code) else it.toString() }.joinToString("")
    }
}

/**
 * Runs [block], which creates and replaces files and directories below [repo] through [RepoFiles] only; when one of them
 * fails, everything created so far is deleted again (deepest first) and the failure is an [OkfException] naming the
 * path. A replaced file cannot be restored, so callers replace only after they have created.
 */
internal fun writeAll(repo: File, block: (RepoFiles) -> Unit) {
    val files = RepoFiles(repo)
    fun failed(reason: String): Nothing {
        files.created.asReversed().forEach { runCatching { Files.deleteIfExists(it.toPath()) } }
        throw OkfException("cannot write ${files.current}: $reason; the files created by this run were removed")
    }
    try {
        block(files)
    } catch (e: IOException) {
        failed(reason(e))
    } catch (e: InvalidPathException) {
        // A name the file system cannot hold (e.g. `*` on Windows).
        failed(e.reason ?: e::class.java.simpleName)
    }
}

/** Creates repo-relative files and directories, never replacing one unless asked to ([replace]), and remembers what it created. */
internal class RepoFiles(private val repo: File) {
    /** What this run created, in creation order. */
    val created = mutableListOf<File>()

    /** The repo-relative path being written. */
    var current = ""
        private set

    /** Creates [path] with [bytes]; [executable] sets the executable bit for everyone (where the file system has one). */
    fun file(path: String, bytes: ByteArray, executable: Boolean = false) {
        current = path
        val file = repo.resolve(path)
        Files.newOutputStream(file.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use {
            created += file
            it.write(bytes)
        }
        if (executable) file.setExecutable(true, false)
    }

    /**
     * Replaces the existing file [path] with [bytes]: written to a temporary file in the same directory, then moved over
     * it. Where the move is refused (Windows, while another process such as `sh` running the launcher holds the file
     * open), the file is rewritten in place instead.
     */
    fun replace(path: String, bytes: ByteArray, executable: Boolean = false) {
        current = path
        val target = repo.resolve(path).toPath()
        val temp = Files.createTempFile(target.toAbsolutePath().parent, ".${target.fileName}.", ".tmp")
        try {
            Files.write(temp, bytes)
            // createTempFile makes the file readable by its owner only.
            temp.toFile().setReadable(true, false)
            if (executable) temp.toFile().setExecutable(true, false)
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: IOException) {
                Files.write(target, bytes, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
                if (executable) target.toFile().setExecutable(true, false)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    /** Creates [path] and each missing ancestor below the repo root. */
    fun directories(path: String) {
        val segments = path.split('/')
        segments.indices.map { segments.subList(0, it + 1).joinToString("/") }.forEach { dir ->
            val file = repo.resolve(dir)
            if (!file.isDirectory) {
                current = dir
                Files.createDirectory(file.toPath())
                created += file
            }
        }
    }
}

/** The operating system's reason for [e]: [FileSystemException.reason], else the message, else the class name. */
internal fun reason(e: IOException): String = (if (e is FileSystemException) e.reason else e.message) ?: e::class.java.simpleName

/** Whether anything (a broken link too) exists at this path; a name the file system cannot hold does not exist. */
internal fun File.present(): Boolean =
    try {
        Files.exists(toPath(), LinkOption.NOFOLLOW_LINKS)
    } catch (_: InvalidPathException) {
        false
    }
