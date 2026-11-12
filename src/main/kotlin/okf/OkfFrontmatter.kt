package okf

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.error.YAMLException
import org.yaml.snakeyaml.nodes.Tag
import org.yaml.snakeyaml.representer.Representer
import org.yaml.snakeyaml.resolver.Resolver
import java.io.File
import java.util.regex.Pattern

/**
 * Thrown when bundle content cannot be turned into an index (e.g. broken frontmatter), git cannot
 * run, or `okf.yaml` is invalid ([OkfConfigException]).
 */
open class OkfException(message: String) : RuntimeException(message)

/** Result of reading the YAML frontmatter block of one Markdown file. */
sealed interface Frontmatter {
    /** File has no `---` line before its first content — not (yet) migrated to OKF. */
    data object Absent : Frontmatter

    data class Invalid(val reason: String) : Frontmatter

    data class Present(val data: Map<String, Any?>) : Frontmatter {
        fun string(key: String): String? = data[key]?.toString()?.trim()?.takeIf { it.isNotEmpty() }

        /** Tags as written: a YAML list, or a comma-separated scalar. */
        fun tags(): List<String> =
            when (val raw = data["tags"]) {
                null -> emptyList()
                is List<*> -> raw.mapNotNull { it?.toString()?.trim() }
                else -> raw.toString().split(',').map { it.trim() }
            }.filter { it.isNotEmpty() }
    }
}

object OkfFrontmatter {
    /** Closed `type` taxonomy for this bundle. */
    val TYPES = setOf("ADR", "Feature Record", "Plan", "Guide", "Risk Register", "Convention", "Product Spec", "Satellite Note")

    /** Reserved OKF file names — never carry document frontmatter (except the bundle-root index.md). */
    val RESERVED = setOf("index.md", "log.md")

    private const val BOM = "\uFEFF"

    fun read(file: File): Frontmatter = parse(file.readText(Charsets.UTF_8))

    /** Strips a UTF-8 BOM and converts CRLF to LF, so CRLF checkouts (core.autocrlf) compare equal. */
    fun normalize(text: String): String = text.removePrefix(BOM).replace("\r\n", "\n")

    /** Markdown body after the closing `---` of the frontmatter (the whole text when there is none), LF-normalized. */
    fun body(text: String): String {
        val lines = normalize(text).split('\n')
        if (lines.first().trimEnd() != "---") return lines.joinToString("\n")
        val close = (1 until lines.size).firstOrNull { lines[it].trimEnd() == "---" } ?: return ""
        return lines.drop(close + 1).joinToString("\n")
    }

    fun parse(text: String): Frontmatter {
        val lines = normalize(text).split('\n')
        if (lines.first().trimEnd() != "---") {
            // A `---` as the first non-blank line is clearly intended frontmatter, just misplaced.
            val misplaced = lines.firstOrNull { it.isNotBlank() }?.trimEnd() == "---"
            return if (misplaced) {
                Frontmatter.Invalid("frontmatter must start on line 1 (found `---` after blank line(s))")
            } else {
                Frontmatter.Absent
            }
        }
        val close = (1 until lines.size).firstOrNull { lines[it].trimEnd() == "---" }
            ?: return Frontmatter.Invalid("frontmatter opened with '---' on line 1 but never closed")
        val yamlText = lines.subList(1, close).joinToString("\n")
        val loaded = try {
            yaml().load<Any?>(yamlText)
        } catch (e: YAMLException) {
            return Frontmatter.Invalid("frontmatter YAML does not parse: ${parseError(e).replace(Regex("\\s+"), " ")}")
        }
        return when (loaded) {
            null -> Frontmatter.Present(emptyMap())
            is Map<*, *> -> Frontmatter.Present(loaded.entries.associate { (k, v) -> k.toString() to v })
            else -> Frontmatter.Invalid("frontmatter must be a YAML mapping, got ${loaded::class.simpleName}")
        }
    }

    /**
     * A construction error wrapped by [yaml] (`isWrappedToRootException`) carries `cause.toString()` as its
     * message — the Java class name; report the cause's own message instead.
     */
    private fun parseError(e: YAMLException): String {
        val cause = e.cause
        return if (cause != null && e.message == cause.toString()) {
            "value does not match its explicit tag (${cause.message ?: cause::class.simpleName})"
        } else {
            e.message.orEmpty()
        }
    }

    // Yaml instances are not thread-safe — build one per parse. Duplicate keys are rejected
    // (DuplicateKeyException is a YAMLException, so it surfaces as Frontmatter.Invalid). A value that does
    // not fit its explicit tag (`!!int bogus` → NumberFormatException) is wrapped into a YAMLException too.
    private fun yaml(): Yaml {
        val loaderOptions = LoaderOptions().apply {
            isAllowDuplicateKeys = false
            isWrappedToRootException = true
        }
        val dumperOptions = DumperOptions()
        return Yaml(SafeConstructor(loaderOptions), Representer(dumperOptions), dumperOptions, loaderOptions, NoTimestampResolver())
    }

    /**
     * Keeps dates/timestamps as the literal strings the author wrote (no java.util.Date
     * coercion), so format checks (`adr_date` = YYYY-MM-DD, ISO-8601 `at`) see the raw value.
     */
    private class NoTimestampResolver : Resolver() {
        override fun addImplicitResolver(tag: Tag, regexp: Pattern, first: String?, limit: Int) {
            if (tag != Tag.TIMESTAMP) super.addImplicitResolver(tag, regexp, first, limit)
        }
    }
}
