package com.sharpbitstudio.okf.cli

import com.sharpbitstudio.okf.OkfException
import java.io.File
import java.io.IOException
import java.io.PrintStream
import java.net.URISyntaxException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.jar.JarFile

/**
 * `okf wrapper`: writes the launcher (`okfw`, a POSIX `sh` script, and `okfw.cmd`, its Windows shim) into the
 * work-tree root, the pin `okf-wrapper.properties` when there is none and this is a release `all` jar, and
 * `.gitattributes` when there is none. A launcher file that carries the marker ([Launcher.hasMarker]) but differs from
 * the embedded copy is replaced; one without the marker is a problem, and then nothing is written (exit
 * [OkfCli.EXIT_FINDINGS]). An existing pin or `.gitattributes` is never read for the pin or changed. `okf.yaml` is not
 * needed. `okf init` writes the same files, create-only ([OkfInit]).
 */
internal class OkfWrapper(private val out: PrintStream, private val err: PrintStream, private val self: SelfJar) {
    /** Runs `okf wrapper` in the work-tree root `args.repo` (already checked); returns the exit code. */
    fun run(args: CliArgs): Int {
        val repo = args.repo
        val states = Launcher.files.map { it to Launcher.state(repo, it) }
        val problems = states.filter { (_, state) -> state == Launcher.State.FOREIGN }
            .map { (file, _) -> "${file.name} exists and is not an okf-cli launcher — move it away and run okf ${Command.WRAPPER.cliName} again" }
        if (problems.isNotEmpty()) {
            err.println("okf wrapper found ${problems.size} problem(s); nothing was written:\n" + problems.joinToString("\n") { "  - $it" })
            return OkfCli.EXIT_FINDINGS
        }
        val pinPresent = repo.resolve(Launcher.PIN).present()
        val pin = if (pinPresent) null else self.pin()
        val attributesPresent = repo.resolve(Launcher.ATTRIBUTES).present()

        val lines = mutableListOf<String>()
        writeAll(repo) { files ->
            // Files are created before any is replaced: a write that fails while creating leaves every file as it was.
            states.filter { (_, state) -> state == Launcher.State.ABSENT }.forEach { (file, _) -> files.file(file.name, file.bytes, file.executable) }
            pin?.let { files.file(Launcher.PIN, it) }
            if (!attributesPresent) files.file(Launcher.ATTRIBUTES, Launcher.ATTRIBUTES_TEXT.toByteArray(Charsets.US_ASCII))
            states.filter { (_, state) -> state == Launcher.State.DIFFERENT }.forEach { (file, _) -> files.replace(file.name, file.bytes, file.executable) }
        }
        states.forEach { (file, state) ->
            lines += when (state) {
                Launcher.State.ABSENT -> "[created] ${file.name}"
                Launcher.State.IDENTICAL -> "[unchanged] ${file.name}"
                Launcher.State.DIFFERENT -> "[updated] ${file.name}"
                Launcher.State.FOREIGN -> error("reported above")
            }
        }
        when {
            pinPresent -> lines += "[kept] ${Launcher.PIN}"
            pin != null -> lines += "[created] ${Launcher.PIN}"
        }
        if (!attributesPresent) lines += "[created] ${Launcher.ATTRIBUTES}"
        // An unchanged okfw may have lost its executable bit (a copy, a checkout with core.filemode=false): set it again.
        states.filter { (file, state) -> file.executable && state == Launcher.State.IDENTICAL }
            .map { (file, _) -> repo.resolve(file.name) }
            .forEach { if (!it.canExecute()) it.setExecutable(true, false) }
        lines.forEach(out::println)
        if (!pinPresent && pin == null) out.println(Launcher.noPinNotice(self.version))
        if (attributesPresent && Launcher.attributesMissing(repo)) out.println(Launcher.ATTRIBUTES_NOTICE)
        out.println(Launcher.CHMOD_NOTICE)
        out.println("okf wrapper OK: ${lines.count { it.startsWith("[created]") || it.startsWith("[updated]") }} file(s) written.")
        return OkfCli.EXIT_OK
    }
}

/**
 * The code that is running: [file] is the jar or classes directory it was loaded from (`null` when unknown), [version]
 * the version it reports. Decides whether `okf wrapper` and `okf init` may write a pin.
 */
internal class SelfJar(val file: File?, val version: String) {
    /**
     * Whether this is the `all` jar of a release build: a jar whose manifest names the CLI main class (the thin jar
     * has none, and its SHA-256 matches no release asset), with a release version: SemVer 2.0 without build metadata or
     * a leading `v`, as `release.yml` accepts it, and not a `-SNAPSHOT`. A jar that cannot be opened (a corrupt zip, an
     * unreadable file) is an [OkfException] (`cannot read <jar>: <reason>`).
     */
    fun isReleaseAllJar(): Boolean {
        val jar = file?.takeIf { it.isFile } ?: return false
        if (!RELEASE_VERSION.matches(version) || version.endsWith("-SNAPSHOT")) return false
        val mainClass = readJar(jar) { JarFile(it).use { jarFile -> jarFile.manifest?.mainAttributes?.getValue("Main-Class") } }
        return mainClass == MAIN_CLASS
    }

    /**
     * The pin naming this jar by its own SHA-256 when it is a release `all` jar ([isReleaseAllJar]), else `null`. A jar
     * that cannot be read is an [OkfException] (`cannot read <jar>: <reason>`).
     */
    fun pin(): ByteArray? {
        if (!isReleaseAllJar()) return null
        val jar = checkNotNull(file)
        return Launcher.pin(version, readJar(jar, Launcher::sha256))
    }

    private fun <T> readJar(jar: File, read: (File) -> T): T =
        try {
            read(jar)
        } catch (e: IOException) {
            throw OkfException("cannot read ${jar.path}: ${reason(e)}")
        }

    companion object {
        /** The `Main-Class` of the `all` jar (`build.gradle.kts`). */
        const val MAIN_CLASS = "com.sharpbitstudio.okf.cli.Main"

        /** A release version: SemVer 2.0 without build metadata or a leading `v` (the regex of `release.yml`). */
        private val RELEASE_VERSION = Regex(
            "(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)" +
                "(-(0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*)(\\.(0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*))*)?",
        )

        /** Where [OkfCli] was loaded from: the jar or the classes directory; `null` when the JVM does not say. */
        fun codeSource(): File? =
            try {
                OkfCli::class.java.protectionDomain?.codeSource?.location?.toURI()?.let(::File)
            } catch (_: URISyntaxException) {
                null
            } catch (_: IllegalArgumentException) {
                // Not a `file:` URI.
                null
            } catch (_: SecurityException) {
                null
            }
    }
}

/** The launcher files, the pin and `.gitattributes` as `okf wrapper` and `okf init` write them. */
internal object Launcher {
    /** The pin file at the repository root: `version` and `sha256` of the okf-cli release `okfw` runs. */
    const val PIN = "okf-wrapper.properties"
    const val ATTRIBUTES = ".gitattributes"

    /** A launcher file carries this within its first three lines (`# okf-cli launcher <revision>`, `rem` in `okfw.cmd`). */
    const val MARKER = "okf-cli launcher"

    /** The lines `.gitattributes` needs, so `okfw` keeps LF and `okfw.cmd` CRLF on every checkout. */
    val ATTRIBUTE_LINES = listOf("/okfw text eol=lf", "/okfw.cmd text eol=crlf", "/$PIN text eol=lf")
    val ATTRIBUTES_TEXT = ATTRIBUTE_LINES.joinToString("") { "$it\n" }
    val ATTRIBUTES_NOTICE = "OKF INFO: add to $ATTRIBUTES: ${ATTRIBUTE_LINES.joinToString(", ")}"
    const val CHMOD_NOTICE = "OKF INFO: commit the launcher as executable: git add --chmod=+x okfw"

    fun noPinNotice(version: String) =
        "OKF INFO: no pin written: okf-cli $version is not a released build; run ./okfw --update <version> to pin a release."

    /** A launcher file as written: its bytes are ASCII, with LF line ends for `okfw` and CRLF for `okfw.cmd`. */
    class LauncherFile(val name: String, val bytes: ByteArray, val executable: Boolean)

    /** What is at a launcher file's path in the repository. */
    enum class State {
        ABSENT,

        /** Byte-identical to the embedded file. */
        IDENTICAL,

        /** A regular file that carries [MARKER] but differs (an older revision, a CRLF copy of `okfw`, an edited one). */
        DIFFERENT,

        /** Anything else: a file without the marker, a directory, a link. */
        FOREIGN,
    }

    /** `okfw` and `okfw.cmd`, from this jar's resources (copied from `launcher/` by the build). */
    val files: List<LauncherFile> by lazy {
        listOf(
            LauncherFile("okfw", embedded("okfw", "\n"), executable = true),
            LauncherFile("okfw.cmd", embedded("okfw.cmd", "\r\n"), executable = false),
        )
    }

    /** The root paths the launcher writes; `init` keeps the docs root off them. */
    val ROOT_PATHS: List<String> = listOf("okfw", "okfw.cmd", PIN, ATTRIBUTES)

    /**
     * What is at [file]'s path below [repo]. A file of another size is read only as far as the marker can be
     * ([MARKER_PREFIX] bytes); a file that cannot be read is an [OkfException] (`cannot read <file>: <reason>`).
     */
    fun state(repo: File, file: LauncherFile): State {
        val path = repo.resolve(file.name)
        if (!path.present()) return State.ABSENT
        if (!Files.isRegularFile(path.toPath(), LinkOption.NOFOLLOW_LINKS)) return State.FOREIGN
        return try {
            if (Files.size(path.toPath()) != file.bytes.size.toLong()) {
                val prefix = path.inputStream().use { it.readNBytes(MARKER_PREFIX) }
                if (hasMarker(prefix)) State.DIFFERENT else State.FOREIGN
            } else {
                val bytes = path.readBytes()
                when {
                    bytes.contentEquals(file.bytes) -> State.IDENTICAL
                    hasMarker(bytes) -> State.DIFFERENT
                    else -> State.FOREIGN
                }
            }
        } catch (e: IOException) {
            throw OkfException("cannot read ${file.name}: ${reason(e)}")
        }
    }

    /** How much of a file [state] reads to look for the marker in its first three lines. */
    private const val MARKER_PREFIX = 4096

    /** Whether [bytes] carry [MARKER] within their first three lines. */
    fun hasMarker(bytes: ByteArray): Boolean = String(bytes, Charsets.ISO_8859_1).lineSequence().take(3).any { MARKER in it }

    /** Whether the existing `.gitattributes` lacks one of [ATTRIBUTE_LINES] (each compared trimmed); unreadable counts as lacking. */
    fun attributesMissing(repo: File): Boolean {
        val lines = try {
            repo.resolve(ATTRIBUTES).readText(Charsets.UTF_8).removePrefix("\uFEFF").lines().map { it.trim() }.toSet()
        } catch (_: IOException) {
            return true
        }
        return ATTRIBUTE_LINES.any { it !in lines }
    }

    /** The pin file for [version] and the lowercase hex [sha256]: two comment lines, `version=`, `sha256=`; LF, ASCII. */
    fun pin(version: String, sha256: String): ByteArray =
        (
            "# okf-cli version pinned for this repository; read by ./okfw and the okf-cli GitHub Action.\n" +
                "# Docs: https://github.com/sharpbitstudio/okf-cli/blob/main/docs/launcher.md\n" +
                "version=$version\n" +
                "sha256=$sha256\n"
            ).toByteArray(Charsets.US_ASCII)

    /** The lowercase hex SHA-256 of [file]. */
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** The resource `com/sharpbitstudio/okf/launcher/<name>` with [lineEnd] line ends, whatever the checkout gave it. */
    private fun embedded(name: String, lineEnd: String): ByteArray {
        val resource = "/com/sharpbitstudio/okf/launcher/$name"
        val text = Launcher::class.java.getResourceAsStream(resource)?.use { String(it.readBytes(), Charsets.US_ASCII) }
            ?: error("$resource is missing from the jar")
        return text.replace("\r\n", "\n").replace("\n", lineEnd).toByteArray(Charsets.US_ASCII)
    }
}
