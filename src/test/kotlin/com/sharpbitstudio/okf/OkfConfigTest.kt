package com.sharpbitstudio.okf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

class OkfConfigTest {
    @Test
    fun `full config is read`() {
        val config = OkfConfig.parse(
            """
            schema_version: 1
            repo: plaza-mobile-app
            docs_root: documentation
            index_command: "tool/run-okf.sh index"
            external_repos: [plaza-server-app, plaza-mobile-protos]
            ref_extensions: [dart, arb, dart]
            source_roots: [plaza_mobile_app/lib, "plaza_mobile_app/test", plaza_mobile_app/lib]
            hub_repo: plaza-server-app
            """.trimIndent(),
        )
        assertEquals(
            OkfConfig(
                repo = "plaza-mobile-app",
                docsRoot = "documentation",
                indexCommand = "tool/run-okf.sh index",
                externalRepos = setOf("plaza-server-app", "plaza-mobile-protos"),
                refExtensions = listOf("dart", "arb"),
                sourceRoots = listOf("plaza_mobile_app/lib", "plaza_mobile_app/test"),
                hubRepo = "plaza-server-app",
            ),
            config,
        )
    }

    @Test
    fun `defaults when only the required keys are set`() {
        val config = OkfConfig.parse("schema_version: 1\nrepo: plaza-server-app\n")
        assertEquals("docs", config.docsRoot)
        assertEquals("./gradlew okfIndex", config.indexCommand)
        assertEquals(emptySet<String>(), config.externalRepos)
        assertEquals(emptyList<String>(), config.refExtensions)
        assertEquals(emptyList<String>(), config.sourceRoots)
    }

    @Test
    fun `unknown key is an error`() {
        assertError("schema_version: 1\nrepo: r\nexternal_repo: [x]\n", "unknown key(s) [external_repo]")
    }

    @Test
    fun `schema_version is required, an integer and exactly 1`() {
        assertError("repo: r\n", "`schema_version` is required")
        assertError("schema_version: 2\nrepo: r\n", "unsupported schema_version 2")
        assertError("schema_version: \"1\"\nrepo: r\n", "`schema_version` must be an integer, got '1'")
        assertError("schema_version: 1.0\nrepo: r\n", "`schema_version` must be an integer")
    }

    @Test
    fun `repo is required and non-empty`() {
        assertError("schema_version: 1\n", "`repo` is required")
        assertError("schema_version: 1\nrepo: \"\"\n", "`repo` must be a non-empty string")
        assertError("schema_version: 1\nrepo:\n", "`repo` must be a non-empty string")
    }

    @Test
    fun `wrong types are errors`() {
        assertError("schema_version: 1\nrepo: [a, b]\n", "`repo` must be a non-empty string")
        assertError("schema_version: 1\nrepo: r\nexternal_repos: plaza-server-app\n", "`external_repos` must be a YAML list of strings")
        assertError("schema_version: 1\nrepo: r\nref_extensions: [dart, 3]\n", "ref_extensions[1] must be a non-empty string, got '3'")
        assertError("schema_version: 1\nrepo: r\nsource_roots: [{a: b}]\n", "source_roots[0] must be a non-empty string")
        assertError("schema_version: 1\nrepo: r\nindex_command: true\n", "`index_command` must be a non-empty string")
        assertError("schema_version: 1\nrepo: r\ndocs_root: 5\n", "`docs_root` must be a non-empty string")
    }

    @Test
    fun `entry values are validated`() {
        assertError("schema_version: 1\nrepo: r\nref_extensions: [.dart]\n", "ref_extensions entry '.dart' must be a file extension without the dot")
        assertError("schema_version: 1\nrepo: r\nexternal_repos: [a/b]\n", "external_repos entry 'a/b' must be a repository name")
        assertError("schema_version: 1\nrepo: r\nsource_roots: [../lib]\n", "source_roots '../lib' must not contain `..` segments")
        assertError("schema_version: 1\nrepo: r\ndocs_root: /docs\n", "docs_root '/docs' must be relative to the repo root")
        assertError("schema_version: 1\nrepo: r\ndocs_root: .\n", "docs_root '.' must name a directory below the repo root")
    }

    @Test
    fun `docs_root and source_roots are plain repo-relative paths, no dot or empty segments`() {
        for (key in listOf("docs_root", "source_roots")) {
            fun yaml(path: String) =
                if (key == "docs_root") "schema_version: 1\nrepo: r\n$key: '$path'\n" else "schema_version: 1\nrepo: r\n$key: ['$path']\n"
            for (path in listOf("./docs", "a/./b", "a/.", "docs/.")) assertError(yaml(path), "$key '$path' must not contain `.` segments")
            for (path in listOf("a//b", "docs/", "a/b//")) assertError(yaml(path), "$key '$path' must not contain empty segments")
            assertError(yaml("a\\b"), "$key 'a\\b' must use `/` as separator")
            assertError(yaml("a/../b"), "$key 'a/../b' must not contain `..` segments")
            assertError(yaml("./"), "$key './' must name a directory below the repo root")
        }
        assertEquals("a/.b/c", OkfConfig.parse("schema_version: 1\nrepo: r\ndocs_root: a/.b/c\n").docsRoot)
        assertEquals(listOf("a/b.c"), OkfConfig.parse("schema_version: 1\nrepo: r\nsource_roots: [a/b.c]\n").sourceRoots)
    }

    @Test
    fun `index_command must stay on one line and not break the HTML comment or code span it is written into`() {
        assertError(
            "schema_version: 1\nrepo: r\nindex_command: \"tool/run-okf.sh\\nindex\"\n",
            "index_command 'tool/run-okf.sh\\u000aindex' must not contain control characters",
        )
        assertError("schema_version: 1\nrepo: r\nindex_command: \"tool/run-okf.sh\\tindex\"\n", "must not contain control characters")
        assertError("schema_version: 1\nrepo: r\nindex_command: \"tool/run-okf.sh\\x01\"\n", "must not contain control characters")
        assertError("schema_version: 1\nrepo: r\nindex_command: \"okf --> index\"\n", "index_command 'okf --> index' must not contain `-->`")
        assertError("schema_version: 1\nrepo: r\nindex_command: \"`okf` index\"\n", "must not contain a backtick")
        // Surrounding whitespace (a block scalar's final newline) is trimmed, not rejected.
        assertEquals("tool/run-okf.sh index", OkfConfig.parse("schema_version: 1\nrepo: r\nindex_command: |\n  tool/run-okf.sh index\n").indexCommand)
        assertEquals("make docs-index -- ->", OkfConfig.parse("schema_version: 1\nrepo: r\nindex_command: make docs-index -- ->\n").indexCommand)
    }

    @Test
    fun `index_language is sr or en, sr when absent`() {
        assertEquals(IndexLanguage.SR, OkfConfig.parse("schema_version: 1\nrepo: r\n").indexLanguage)
        assertEquals(IndexLanguage.SR, OkfConfig.parse("schema_version: 1\nrepo: r\nindex_language: sr\n").indexLanguage)
        assertEquals(IndexLanguage.EN, OkfConfig.parse("schema_version: 1\nrepo: r\nindex_language: en\n").indexLanguage)
        assertEquals(IndexLanguage.EN, OkfConfig.parse("schema_version: 1\nrepo: r\nindex_language: \"en\"\n").indexLanguage)
    }

    @Test
    fun `index_language is case-sensitive, any other value is an error`() {
        for ((value, shown) in listOf("EN" to "EN", "fr" to "fr", "1" to "1", "\"\"" to "", "" to "null", "[en]" to "[en]")) {
            assertError(
                "schema_version: 1\nrepo: r\nindex_language: $value\n",
                "okf.yaml: index_language '$shown' must be one of [sr, en]",
            )
        }
    }

    @Test
    fun `hub_repo is a repository name listed in external_repos`() {
        val config = OkfConfig.parse("schema_version: 1\nrepo: r\nhub_repo: hub\nexternal_repos: [hub, other]\n")
        assertEquals("hub", config.hubRepo)
        assertEquals(null, OkfConfig.parse("schema_version: 1\nrepo: r\n").hubRepo)
        assertError(
            "schema_version: 1\nrepo: r\nhub_repo: hub\nexternal_repos: [other]\n",
            "hub_repo 'hub' must also be listed in external_repos (references to it are sibling-repo references)",
        )
        assertError("schema_version: 1\nrepo: r\nhub_repo: hub\n", "hub_repo 'hub' must also be listed in external_repos")
        assertError("schema_version: 1\nrepo: r\nhub_repo: a/hub\nexternal_repos: [hub]\n", "hub_repo 'a/hub' must be a repository name, not a path")
        assertError("schema_version: 1\nrepo: r\nhub_repo: [hub]\nexternal_repos: [hub]\n", "`hub_repo` must be a non-empty string")
        assertError("schema_version: 1\nrepo: r\nhub_repo: \"\"\n", "`hub_repo` must be a non-empty string")
        assertError(
            "schema_version: 1\nrepo: hub\nhub_repo: hub\nexternal_repos: [hub]\n",
            "okf.yaml: hub_repo 'hub' is this repository — the hub does not point at itself",
        )
        assertError("schema_version: 1\nrepo: hub\nhub_repo: \" hub \"\n", "hub_repo 'hub' is this repository")
    }

    @Test
    fun `malformed YAML, non-mapping and duplicate keys are errors`() {
        assertError("", "must be a YAML mapping, got an empty document")
        assertError("- a\n- b\n", "must be a YAML mapping")
        assertError("schema_version: 1\nrepo: a\nrepo: b\n", "does not parse")
        assertError("schema_version: [1\n", "does not parse")
        // A value that does not fit its explicit tag fails construction (NumberFormatException) — still a config error.
        assertError("schema_version: !!int bogus\nrepo: r\n", "does not parse")
        assertError("schema_version: 1\nrepo: !!float nope\n", "does not parse")
    }

    @Test
    fun `load reads okf yaml from the repo and fails when it is missing`(@TempDir repo: File) {
        val missing = assertThrows<OkfConfigException> { OkfConfig.load(repo) }
        assertTrue("okf.yaml not found" in missing.message!!, missing.message)
        repo.resolve("okf.yaml").writeText("﻿schema_version: 1\r\nrepo: okf-cli\r\n")
        assertEquals(OkfConfig("okf-cli"), OkfConfig.load(repo))
        assertEquals(repo.resolve("docs"), OkfConfig.load(repo).bundleDir(repo))
    }

    @Test
    fun `satellites are read in order, absent is empty`() {
        val config = OkfConfig.parse(
            """
            schema_version: 1
            repo: hub
            external_repos: [sat-b, sat-a, other]
            satellites:
              - repo: sat-b
                description: Flutter mobile app
                index: https://github.com/org/sat-b/blob/main/docs/index.md
              - {repo: sat-a, description: " Proto API ", index: "https://github.com/org/sat-a/blob/main/doc/index.md"}
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                Satellite("sat-b", "Flutter mobile app", "https://github.com/org/sat-b/blob/main/docs/index.md"),
                Satellite("sat-a", "Proto API", "https://github.com/org/sat-a/blob/main/doc/index.md"),
            ),
            config.satellites,
        )
        assertEquals(emptyList<Satellite>(), OkfConfig.parse("schema_version: 1\nrepo: r\n").satellites)
    }

    @Test
    fun `satellites are validated strictly`() {
        val head = "schema_version: 1\nrepo: hub\nexternal_repos: [sat, other]\n"
        fun entry(repo: String = "sat", description: String = "d", index: String = "https://x/docs/index.md", extra: String = "") =
            "satellites:\n  - repo: $repo\n    description: $description\n    index: $index\n$extra"
        assertError(head + "satellites: sat\n", "`satellites` must be a YAML list of mappings (repo, description, index)")
        assertError(head + "satellites: [sat]\n", "satellites[0] must be a mapping with repo, description, index, got 'sat'")
        assertError(head + entry(extra = "    url: x\n"), "satellites[0]: unknown key(s) [url] — allowed: [repo, description, index]")
        assertError(head + "satellites:\n  - repo: sat\n    index: https://x\n", "satellites[0].description is required")
        assertError(head + entry(description = "\"\""), "satellites[0].description must be a non-empty string")
        assertError(head + entry(index = "42"), "satellites[0].index must be a non-empty string, got '42'")
        assertError(head + entry(repo = "a/sat"), "satellites[0].repo 'a/sat' must be a repository name, not a path")
        assertError(head + entry(repo = "hub"), "satellites[0].repo 'hub' is this repository")
        assertError(head + entry(repo = "nope"), "satellites[0].repo 'nope' must also be listed in external_repos")
        assertError(
            head + entry() + "  - {repo: sat, description: d, index: \"https://y/docs/index.md\"}\n",
            "satellites[1].repo 'sat' duplicates an earlier entry",
        )
        assertError(head + entry(description = "\"two\\nlines\""), "satellites[0].description 'two\\u000alines' must be one line")
        assertError(head + entry(index = "http://x/docs/index.md"), "satellites[0].index 'http://x/docs/index.md' must be an https:// URL")
        // Valid URIs that would break the rendered `[repo](<index>)` link.
        for (url in listOf("https://example.org/x/blob/release)next/docs/index.md", "https://x/a(b/docs/index.md", "\"https://x/<a>/docs/index.md\"")) {
            assertError(head + entry(index = url), "or any of ( ) < > (it is written as a Markdown link target)")
        }
        assertError(head + entry(index = "\"https://x/my docs/index.md\""), "must be an https:// URL with a host, on one line, without whitespace")
        assertError(head + entry(index = "\"https://[broken/docs/index.md\""), "satellites[0].index 'https://[broken/docs/index.md' must be an https:// URL")
        assertError(head + entry(index = "https:///docs/index.md"), "satellites[0].index 'https:///docs/index.md' must be an https:// URL with a host")
        assertError(head + entry(index = "HTTPS-ish://x/docs/index.md"), "must be an https:// URL")
        for (dots in listOf(".", "..")) assertError(head + entry(repo = "'$dots'"), "satellites[0].repo '$dots' must be a repository name, not a path")
        assertError(head + entry(repo = "'a\\b'"), "satellites[0].repo 'a\\b' must be a repository name, not a path")
    }

    @Test
    fun `satellites are only allowed in the hub`() {
        assertError(
            "schema_version: 1\nrepo: r\nexternal_repos: [hub, sat]\nhub_repo: hub\n" +
                "satellites: [{repo: sat, description: d, index: \"https://x/docs/index.md\"}]\n",
            "`satellites` is only allowed in the hub (a repo without hub_repo) — this repo's hub is 'hub'",
        )
        // Checked before the value's shape.
        assertError(
            "schema_version: 1\nrepo: r\nexternal_repos: [hub]\nhub_repo: hub\nsatellites: nope\n",
            "`satellites` is only allowed in the hub (a repo without hub_repo)",
        )
    }

    private fun assertError(yaml: String, fragment: String) {
        val e = assertThrows<OkfConfigException> { OkfConfig.parse(yaml) }
        assertTrue(fragment in e.message!!, "expected <$fragment> in <${e.message}>")
    }
}
