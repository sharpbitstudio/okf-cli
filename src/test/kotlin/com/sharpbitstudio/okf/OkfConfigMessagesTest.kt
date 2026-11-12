package com.sharpbitstudio.okf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The exact text of every `okf.yaml` error ([OkfConfig.parse], [OkfConfig.load]). Case labels (`<SourceFile>#<n>`)
 * are stable names assigned once to each message template; they are not line numbers. Text passed through from
 * the YAML parser is asserted only up to the tool's own frame.
 */
class OkfConfigMessagesTest {
    @Test
    fun `okf yaml missing`(@TempDir dir: File) {
        val e = assertThrows<OkfConfigException> { OkfConfig.load(dir) }
        assertEquals(
            "okf.yaml not found in ${dir.path} — every repo under OKF needs one " +
                "(see https://github.com/sharpbitstudio/okf-cli/blob/main/docs/reference.md)",
            e.message,
            "OkfConfig#1",
        )
    }

    @Test
    fun `okf yaml does not parse`() {
        val frame = "okf.yaml does not parse: "
        assertEquals(frame, configError("schema_version: 1\nrepo: [\n").take(frame.length), "OkfConfig#2")
    }

    @Test
    fun `okf yaml is not a mapping`() {
        val frame = "okf.yaml must be a YAML mapping, got "
        assertEquals(frame, configError("- schema_version\n- repo\n").take(frame.length), "OkfConfig#3")
        assertEquals("okf.yaml must be a YAML mapping, got an empty document", configError(""), "OkfConfig#3")
    }

    @Test
    fun `unknown keys`() {
        assertEquals(
            "okf.yaml: unknown key(s) [bogus, extra] — allowed: [docs_root, external_repos, hub_repo, index_command, index_language, " +
                "ref_extensions, repo, satellites, schema_version, source_roots]",
            configError("schema_version: 1\nrepo: test-repo\nextra: 1\nbogus: 2\n"),
            "OkfConfig#4",
        )
    }

    @Test
    fun `schema_version missing, not an integer, unsupported`() {
        assertEquals("okf.yaml: `schema_version` is required (supported: 1)", configError("repo: test-repo\n"), "OkfConfig#5")
        assertEquals(
            "okf.yaml: `schema_version` must be an integer, got '1'",
            configError("schema_version: \"1\"\nrepo: test-repo\n"),
            "OkfConfig#6",
        )
        assertEquals(
            "okf.yaml: unsupported schema_version 2 — this okf-cli supports 1",
            configError("schema_version: 2\nrepo: test-repo\n"),
            "OkfConfig#7",
        )
    }

    @Test
    fun `repo missing`() {
        assertEquals("okf.yaml: `repo` is required (the repository name)", configError("schema_version: 1\n"), "OkfConfig#8")
    }

    @Test
    fun `external_repos entry is a path`() {
        assertEquals(
            "okf.yaml: external_repos entry 'org/sibling-repo' must be a repository name, not a path",
            configError(BASE + "external_repos: [org/sibling-repo]\n"),
            "OkfConfig#9",
        )
    }

    @Test
    fun `ref_extensions entry with a dot`() {
        assertEquals(
            "okf.yaml: ref_extensions entry '.dart' must be a file extension without the dot (letters and digits)",
            configError(BASE + "ref_extensions: [.dart]\n"),
            "OkfConfig#10",
        )
    }

    @Test
    fun `hub_repo is a path, this repo, or not in external_repos`() {
        assertEquals(
            "okf.yaml: hub_repo 'org/hub-repo' must be a repository name, not a path",
            configError(BASE + "hub_repo: org/hub-repo\n"),
            "OkfConfig#11",
        )
        assertEquals(
            "okf.yaml: hub_repo 'test-repo' is this repository — the hub does not point at itself",
            configError(BASE + "hub_repo: test-repo\n"),
            "OkfConfig#12",
        )
        assertEquals(
            "okf.yaml: hub_repo 'hub-repo' must also be listed in external_repos (references to it are sibling-repo references)",
            configError(BASE + "hub_repo: hub-repo\n"),
            "OkfConfig#13",
        )
    }

    @Test
    fun `index_language not allowed`() {
        assertEquals("okf.yaml: index_language 'de' must be one of [sr, en]", configError(BASE + "index_language: de\n"), "OkfConfig#14")
    }

    @Test
    fun `satellites outside the hub or not a list`() {
        assertEquals(
            "okf.yaml: `satellites` is only allowed in the hub (a repo without hub_repo) — this repo's hub is 'hub-repo'",
            configError(BASE + "external_repos: [hub-repo]\nhub_repo: hub-repo\nsatellites: []\n"),
            "OkfConfig#15",
        )
        assertEquals(
            "okf.yaml: `satellites` must be a YAML list of mappings (repo, description, index), got 'sat-a'",
            configError(HUB + "satellites: sat-a\n"),
            "OkfConfig#16",
        )
    }

    @Test
    fun `satellite entry shape`() {
        assertEquals(
            "okf.yaml: satellites[0] must be a mapping with repo, description, index, got 'sat-a'",
            configError(HUB + "satellites: [sat-a]\n"),
            "OkfConfig#17",
        )
        assertEquals(
            "okf.yaml: satellites[0]: unknown key(s) [url] — allowed: [repo, description, index]",
            configError(satellites(SAT_A + "    url: x\n")),
            "OkfConfig#18",
        )
        assertEquals(
            "okf.yaml: satellites[0].description is required",
            configError(satellites("  - repo: sat-a\n    index: $SAT_A_INDEX\n")),
            "OkfConfig#19",
        )
        assertEquals(
            "okf.yaml: satellites[0].description must be a non-empty string, got ''",
            configError(satellites("  - repo: sat-a\n    description: \"\"\n    index: $SAT_A_INDEX\n")),
            "OkfConfig#20",
        )
    }

    @Test
    fun `satellite entry values`() {
        assertEquals(
            "okf.yaml: satellites[0].repo 'org/sat-a' must be a repository name, not a path",
            configError(satellites(SAT_A.replace("repo: sat-a", "repo: org/sat-a"))),
            "OkfConfig#21",
        )
        assertEquals(
            "okf.yaml: satellites[0].repo 'hub-repo' is this repository — the hub is not its own satellite",
            configError(satellites(SAT_A.replace("repo: sat-a", "repo: hub-repo"))),
            "OkfConfig#22",
        )
        assertEquals(
            "okf.yaml: satellites[0].repo 'sat-x' must also be listed in external_repos (references to it are sibling-repo references)",
            configError(satellites(SAT_A.replace("repo: sat-a", "repo: sat-x"))),
            "OkfConfig#23",
        )
        assertEquals(
            "okf.yaml: satellites[1].repo 'sat-a' duplicates an earlier entry",
            configError(satellites(SAT_A + SAT_A)),
            "OkfConfig#24",
        )
        assertEquals(
            "okf.yaml: satellites[0].description 'Satellite\\u0009A' must be one line (no control characters)",
            configError(satellites(SAT_A.replace("description: Satellite A", "description: \"Satellite\\tA\""))),
            "OkfConfig#25",
        )
        assertEquals(
            "okf.yaml: satellites[0].index 'http://example.org/sat-a/docs/index.md' must be an https:// URL with a host, on one line, " +
                "without whitespace or any of ( ) < > (it is written as a Markdown link target)",
            configError(satellites(SAT_A.replace(SAT_A_INDEX, "http://example.org/sat-a/docs/index.md"))),
            "OkfConfig#26",
        )
    }

    @Test
    fun `string and list values`() {
        assertEquals("okf.yaml: `repo` must be a non-empty string, got '[a]'", configError("schema_version: 1\nrepo: [a]\n"), "OkfConfig#27")
        assertEquals(
            "okf.yaml: `external_repos` must be a YAML list of strings, got 'sibling-repo'",
            configError(BASE + "external_repos: sibling-repo\n"),
            "OkfConfig#28",
        )
        assertEquals(
            "okf.yaml: external_repos[0] must be a non-empty string, got ''",
            configError(BASE + "external_repos: [\"\"]\n"),
            "OkfConfig#29",
        )
    }

    @Test
    fun `index_command must stay on one line and close nothing`() {
        assertEquals(
            "okf.yaml: index_command 'run\\u0009index' must not contain control characters (newline, tab, …)",
            configError(BASE + "index_command: \"run\\tindex\"\n"),
            "OkfConfig#30",
        )
        assertEquals(
            "okf.yaml: index_command 'run --> index' must not contain `-->` (it is written into an HTML comment)",
            configError(BASE + "index_command: \"run --> index\"\n"),
            "OkfConfig#31",
        )
        assertEquals(
            "okf.yaml: index_command 'run `index`' must not contain a backtick (it is written into inline code)",
            configError(BASE + "index_command: \"run `index`\"\n"),
            "OkfConfig#32",
        )
    }

    @Test
    fun `docs_root must be a canonical repo-relative directory`() {
        assertEquals(
            "okf.yaml: docs_root '/docs' must be relative to the repo root, not absolute",
            configError(BASE + "docs_root: /docs\n"),
            "OkfConfig#35",
        )
        assertEquals(
            "okf.yaml: docs_root 'docs\\sub' must use `/` as separator, not `\\`",
            configError(BASE + "docs_root: 'docs\\sub'\n"),
            "OkfConfig#36",
        )
        assertEquals(
            "okf.yaml: docs_root 'docs/../other' must not contain `..` segments",
            configError(BASE + "docs_root: docs/../other\n"),
            "OkfConfig#37",
        )
        assertEquals(
            "okf.yaml: docs_root '.' must name a directory below the repo root",
            configError(BASE + "docs_root: \".\"\n"),
            "OkfConfig#38",
        )
        assertEquals(
            "okf.yaml: docs_root './docs' must not contain `.` segments (write the path from the repo root, without `./`)",
            configError(BASE + "docs_root: ./docs\n"),
            "OkfConfig#39",
        )
        assertEquals(
            "okf.yaml: docs_root 'docs//sub' must not contain empty segments (no `//`, no trailing `/`)",
            configError(BASE + "docs_root: docs//sub\n"),
            "OkfConfig#40",
        )
    }

    private fun configError(yaml: String): String = assertThrows<OkfConfigException> { OkfConfig.parse(yaml) }.message.orEmpty()

    /** A hub `okf.yaml` whose `satellites` list is [entries]. */
    private fun satellites(entries: String) = HUB + "satellites:\n" + entries

    private companion object {
        const val BASE = "schema_version: 1\nrepo: test-repo\n"
        const val HUB = "schema_version: 1\nrepo: hub-repo\nexternal_repos: [sat-a]\n"
        const val SAT_A_INDEX = "https://example.org/sat-a/docs/index.md"
        const val SAT_A = "  - repo: sat-a\n    description: Satellite A\n    index: $SAT_A_INDEX\n"
    }
}
