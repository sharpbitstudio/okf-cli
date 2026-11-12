package com.sharpbitstudio.okf

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.error.YAMLException
import org.yaml.snakeyaml.representer.Representer
import java.io.File
import java.net.URI
import java.net.URISyntaxException

/** `okf.yaml` is missing or invalid; the CLI exits with the usage/config code. */
class OkfConfigException(message: String) : OkfException(message)

/** Language of the fixed text of the generated sub-indexes (`index_language`, [OkfIndexRenderer]); [value] is the `okf.yaml` spelling. */
enum class IndexLanguage(val value: String) {
    SR("sr"),
    EN("en"),
}

/**
 * One satellite repository listed in the hub's `satellites` ([OkfConfig.satellites]): the hub's
 * `docs/index.md` links to its bundle ("Repos" section, [OkfIndexRenderer.renderReposBlock]) and
 * `okf xcheck` checks it together with the hub.
 */
data class Satellite(
    /** Repository name; also listed in `external_repos`, and the checkout directory name under the `xcheck` workspace. */
    val repo: String,
    /** One-line description shown next to the link. */
    val description: String,
    /** `https://` URL of the satellite's bundle-root `index.md`. */
    val index: String,
)

/**
 * Per-repo settings from `<repo>/okf.yaml` (schema v1). Every repo-specific setting is here; a repo that
 * keeps the defaults only needs `schema_version` and `repo`.
 */
data class OkfConfig(
    /** Repository name (`repo`), e.g. `plaza-server-app`. */
    val repo: String,
    /** Repo-relative path of the OKF bundle (`docs_root`). */
    val docsRoot: String = DEFAULT_DOCS_ROOT,
    /** Command that regenerates the indexes (`index_command`): shown in the generated marker and in every "run …" hint. */
    val indexCommand: String = DEFAULT_INDEX_COMMAND,
    /** Sibling repositories (`external_repos`); a code reference written as `<repo>/<path>` points there and is not checked. */
    val externalRepos: Set<String> = emptySet(),
    /** File extensions (`ref_extensions`, without the dot) added to [OkfRefChecker.EXTENSIONS]. */
    val refExtensions: List<String> = emptyList(),
    /** Extra repo-relative code roots (`source_roots`) next to the `src/{main,test}/{java,kotlin,resources}` convention ([RepoIndex]). */
    val sourceRoots: List<String> = emptyList(),
    /**
     * The hub repository (`hub_repo`): the repo whose Feature Records the `hub:` field of a `Satellite Note`
     * points to. A repository name that is also in [externalRepos]; `null` in the hub itself.
     */
    val hubRepo: String? = null,
    /**
     * The satellite repositories (`satellites`), in `okf.yaml` order; only in the hub (no [hubRepo]).
     * Non-empty turns on the generated "Repos" section of the bundle-root `index.md` and enables `okf xcheck`.
     */
    val satellites: List<Satellite> = emptyList(),
    /** Language of the fixed text of the generated `adr/index.md` and `features/index.md` (`index_language`). */
    val indexLanguage: IndexLanguage = IndexLanguage.SR,
) {
    /** The bundle directory under [repoDir]. */
    fun bundleDir(repoDir: File): File = repoDir.resolve(docsRoot)

    companion object {
        const val FILE_NAME = "okf.yaml"
        const val SCHEMA_VERSION = 1
        const val DEFAULT_DOCS_ROOT = "docs"
        const val DEFAULT_INDEX_COMMAND = "./gradlew okfIndex"

        private val KEYS = setOf(
            "schema_version", "repo", "docs_root", "index_command", "external_repos", "ref_extensions", "source_roots", "hub_repo",
            "satellites", "index_language",
        )
        private val SATELLITE_KEYS = listOf("repo", "description", "index")

        /** Characters that would end or break the `(<index>)` target of a rendered Markdown link. */
        private const val LINK_BREAKERS = "()<>"
        private val WINDOWS_DRIVE = Regex("""^[A-Za-z]:""")

        /** Reads `<repoDir>/okf.yaml`; throws [OkfConfigException] when it is missing or invalid. */
        fun load(repoDir: File): OkfConfig {
            val file = repoDir.resolve(FILE_NAME)
            if (!file.isFile) throw OkfConfigException("$FILE_NAME not found in ${repoDir.path} — every repo under OKF needs one (see https://github.com/sharpbitstudio/okf-cli/blob/main/docs/reference.md)")
            return parse(file.readText(Charsets.UTF_8))
        }

        /** Parses `okf.yaml` text strictly: unknown keys, wrong types and an unsupported `schema_version` are errors. */
        fun parse(text: String): OkfConfig {
            val loaded = try {
                yaml().load<Any?>(OkfFrontmatter.normalize(text))
            } catch (e: YAMLException) {
                throw OkfConfigException("$FILE_NAME does not parse: ${e.message?.replace(Regex("\\s+"), " ")}")
            }
            val data = loaded as? Map<*, *> ?: throw OkfConfigException("$FILE_NAME must be a YAML mapping, got ${loaded?.let { it::class.simpleName } ?: "an empty document"}")
            val unknown = data.keys.map { it.toString() }.filter { it !in KEYS }.sorted()
            if (unknown.isNotEmpty()) throw OkfConfigException("$FILE_NAME: unknown key(s) $unknown — allowed: ${KEYS.sorted()}")

            val version = data["schema_version"]
            when {
                !data.containsKey("schema_version") -> throw OkfConfigException("$FILE_NAME: `schema_version` is required (supported: $SCHEMA_VERSION)")
                version !is Int -> throw OkfConfigException("$FILE_NAME: `schema_version` must be an integer, got '$version'")
                version != SCHEMA_VERSION -> throw OkfConfigException(
                    "$FILE_NAME: unsupported schema_version $version — this okf-cli supports $SCHEMA_VERSION",
                )
            }
            val repo = string(data, "repo") ?: throw OkfConfigException("$FILE_NAME: `repo` is required (the repository name)")
            val docsRoot = string(data, "docs_root")?.let { relativePath("docs_root", it) } ?: DEFAULT_DOCS_ROOT
            val indexCommand = string(data, "index_command")?.also(::indexCommand) ?: DEFAULT_INDEX_COMMAND
            val externalRepos = strings(data, "external_repos").onEach {
                if ('/' in it || '\\' in it) throw OkfConfigException("$FILE_NAME: external_repos entry '$it' must be a repository name, not a path")
            }
            val refExtensions = strings(data, "ref_extensions").onEach {
                if (!OkfRefChecker.EXTENSION.matches(it)) {
                    throw OkfConfigException("$FILE_NAME: ref_extensions entry '$it' must be a file extension without the dot (letters and digits)")
                }
            }
            val sourceRoots = strings(data, "source_roots").map { relativePath("source_roots", it) }
            val hubRepo = string(data, "hub_repo")?.also {
                when {
                    '/' in it || '\\' in it -> throw OkfConfigException("$FILE_NAME: hub_repo '$it' must be a repository name, not a path")
                    it == repo -> throw OkfConfigException("$FILE_NAME: hub_repo '$it' is this repository — the hub does not point at itself")
                    it !in externalRepos -> throw OkfConfigException(
                        "$FILE_NAME: hub_repo '$it' must also be listed in external_repos (references to it are sibling-repo references)",
                    )
                }
            }
            val satellites = satellites(data, repo, externalRepos.toSet(), hubRepo)
            val indexLanguage = indexLanguage(data)
            return OkfConfig(
                repo,
                docsRoot,
                indexCommand,
                externalRepos.toSet(),
                refExtensions.distinct(),
                sourceRoots.distinct(),
                hubRepo,
                satellites,
                indexLanguage,
            )
        }

        /** `index_language`: exactly one of the [IndexLanguage] values (case-sensitive), [IndexLanguage.SR] when absent. */
        private fun indexLanguage(data: Map<*, *>): IndexLanguage {
            if (!data.containsKey("index_language")) return IndexLanguage.SR
            val value = data["index_language"]
            return IndexLanguage.entries.firstOrNull { it.value == value }
                ?: throw OkfConfigException("$FILE_NAME: index_language '$value' must be one of ${IndexLanguage.entries.map { it.value }}")
        }

        /**
         * `satellites` (hub only): a list of mappings with exactly `repo`, `description` and `index`. `repo` is a
         * repository name from `external_repos` other than this repo, listed once; `description` is one line;
         * `index` is a one-line `https://` URL.
         */
        private fun satellites(data: Map<*, *>, repo: String, externalRepos: Set<String>, hubRepo: String?): List<Satellite> {
            if (!data.containsKey("satellites")) return emptyList()
            if (hubRepo != null) {
                throw OkfConfigException("$FILE_NAME: `satellites` is only allowed in the hub (a repo without hub_repo) — this repo's hub is '$hubRepo'")
            }
            val raw = data["satellites"] as? List<*>
                ?: throw OkfConfigException("$FILE_NAME: `satellites` must be a YAML list of mappings (repo, description, index), got '${data["satellites"]}'")
            val seen = mutableSetOf<String>()
            return raw.mapIndexed { i, entry ->
                val key = "satellites[$i]"
                val map = entry as? Map<*, *>
                    ?: throw OkfConfigException("$FILE_NAME: $key must be a mapping with ${SATELLITE_KEYS.joinToString(", ")}, got '$entry'")
                val unknown = map.keys.map { it.toString() }.filter { it !in SATELLITE_KEYS }.sorted()
                if (unknown.isNotEmpty()) throw OkfConfigException("$FILE_NAME: $key: unknown key(s) $unknown — allowed: $SATELLITE_KEYS")
                fun value(name: String): String {
                    if (!map.containsKey(name)) throw OkfConfigException("$FILE_NAME: $key.$name is required")
                    val value = map[name]
                    if (value !is String || value.isBlank()) throw OkfConfigException("$FILE_NAME: $key.$name must be a non-empty string, got '$value'")
                    return value.trim()
                }
                val name = value("repo")
                val description = value("description")
                val index = value("index")
                val reason = when {
                    '/' in name || '\\' in name || name == "." || name == ".." -> "repo '$name' must be a repository name, not a path"
                    name == repo -> "repo '$name' is this repository — the hub is not its own satellite"
                    name !in externalRepos -> "repo '$name' must also be listed in external_repos (references to it are sibling-repo references)"
                    !seen.add(name) -> "repo '$name' duplicates an earlier entry"
                    description.any(Char::isISOControl) -> "description '${description.escapeControls()}' must be one line (no control characters)"
                    !isHttpsUrl(index) -> "index '${index.escapeControls()}' must be an https:// URL with a host, on one line, without whitespace " +
                        "or any of ( ) < > (it is written as a Markdown link target)"
                    else -> null
                }
                if (reason != null) throw OkfConfigException("$FILE_NAME: $key.$reason")
                Satellite(name, description, index)
            }
        }

        /**
         * An absolute `https://` URL with a host that [URI] parses, without whitespace, control characters or
         * [LINK_BREAKERS] (the URL becomes the target of a Markdown link in the "Repos" section).
         */
        private fun isHttpsUrl(value: String): Boolean {
            if (value.any { it.isWhitespace() || it.isISOControl() || it in LINK_BREAKERS }) return false
            val uri = try {
                URI(value)
            } catch (_: URISyntaxException) {
                return false
            }
            return uri.scheme == "https" && !uri.host.isNullOrEmpty()
        }

        /** A non-empty string value, `null` when the key is absent. */
        private fun string(data: Map<*, *>, key: String): String? {
            if (!data.containsKey(key)) return null
            val value = data[key]
            if (value !is String || value.isBlank()) throw OkfConfigException("$FILE_NAME: `$key` must be a non-empty string, got '$value'")
            return value.trim()
        }

        /** A list of non-empty strings, empty when the key is absent. */
        private fun strings(data: Map<*, *>, key: String): List<String> {
            if (!data.containsKey(key)) return emptyList()
            val value = data[key] as? List<*> ?: throw OkfConfigException("$FILE_NAME: `$key` must be a YAML list of strings, got '${data[key]}'")
            return value.mapIndexed { i, entry ->
                if (entry !is String || entry.isBlank()) throw OkfConfigException("$FILE_NAME: $key[$i] must be a non-empty string, got '$entry'")
                entry.trim()
            }
        }

        /**
         * The `index_command` is written into an HTML comment (the generated marker), an inline code span
         * (the ADR index header) and one-line "run …" hints: it must stay on one line and must not close either.
         */
        private fun indexCommand(value: String) {
            val reason = when {
                value.any(Char::isISOControl) -> "must not contain control characters (newline, tab, …)"
                "-->" in value -> "must not contain `-->` (it is written into an HTML comment)"
                '`' in value -> "must not contain a backtick (it is written into inline code)"
                else -> null
            }
            if (reason != null) throw OkfConfigException("$FILE_NAME: index_command '${value.escapeControls()}' $reason")
        }

        private fun String.escapeControls() = map { if (it.isISOControl()) "\\u%04x".format(it.code) else it.toString() }.joinToString("")

        /** A canonical repo-relative directory: `/`-separated plain segments, no `.` / `..` / empty segment, no leading or trailing `/`. */
        private fun relativePath(key: String, value: String): String {
            val segments = value.split('/')
            val reason = when {
                value.startsWith('/') || WINDOWS_DRIVE.containsMatchIn(value) -> "must be relative to the repo root, not absolute"
                '\\' in value -> "must use `/` as separator, not `\\`"
                segments.any { it == ".." } -> "must not contain `..` segments"
                segments.all { it == "." || it.isEmpty() } -> "must name a directory below the repo root"
                segments.any { it == "." } -> "must not contain `.` segments (write the path from the repo root, without `./`)"
                segments.any { it.isEmpty() } -> "must not contain empty segments (no `//`, no trailing `/`)"
                else -> null
            }
            if (reason != null) throw OkfConfigException("$FILE_NAME: $key '$value' $reason")
            return value
        }

        // Duplicate keys are rejected, like in frontmatter. A value that does not fit its explicit tag
        // (`!!int bogus` → NumberFormatException) is wrapped into a YAMLException, so it is a config error too.
        private fun yaml(): Yaml {
            val loaderOptions = LoaderOptions().apply {
                isAllowDuplicateKeys = false
                isWrappedToRootException = true
            }
            val dumperOptions = DumperOptions()
            return Yaml(SafeConstructor(loaderOptions), Representer(dumperOptions), dumperOptions, loaderOptions)
        }
    }
}
