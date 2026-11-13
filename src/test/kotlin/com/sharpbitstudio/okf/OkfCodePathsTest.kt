package com.sharpbitstudio.okf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class OkfCodePathsTest {
    private val index = RepoIndex(
        listOf(
            "build.gradle.kts",
            "docs/adr/ADR-026-expiry.md",
            "$OTP/api/OtpService.java",
            "$OTP/internal/jobs/OtpExpiryJob.java",
        ) + (1..101).map { "$MAIN/sport/internal/Gen$it.java" },
    )

    @Test
    fun `valid globs that match files pass, an empty list is allowed`() {
        assertEquals(CodePathsResult(emptyList(), emptyList()), validate(listOf("$OTP/**", "build.gradle.kts")))
        assertEquals(CodePathsResult(emptyList(), emptyList()), validate(emptyList<String>()))
    }

    @Test
    fun `value must be a list of non-empty strings`() {
        assertProblem(validate("$OTP/**").problems, "code_paths must be a YAML list of repo-root globs")
        assertProblem(validate(null).problems, "code_paths must be a YAML list")
        val problems = validate(listOf("$OTP/**", 42, " ")).problems
        assertProblem(problems, "code_paths[1] must be a non-empty string, got '42'")
        assertProblem(problems, "code_paths[2] must be a non-empty string")
        assertEquals(2, problems.size, problems.toString())
    }

    @Test
    fun `absolute paths, backslashes, dot-dot, dot-slash and docs paths are forbidden`() {
        val problems = validate(listOf("/server/**", "C:/repo/**", "server\\src\\**", "server/../docs/**", "./server/**", "docs/adr/**", "docs")).problems
        assertProblem(problems, "code_paths[0] '/server/**' must be relative to the repo root, not absolute")
        assertProblem(problems, "code_paths[1] 'C:/repo/**' must be relative to the repo root, not absolute")
        assertProblem(problems, "code_paths[2] 'server\\src\\**' must use `/` as separator")
        assertProblem(problems, "code_paths[3] 'server/../docs/**' must not contain `..` segments")
        assertProblem(problems, "code_paths[4] './server/**' must not start with `./`")
        assertProblem(problems, "code_paths[5] 'docs/adr/**' must not point into the docs bundle (docs/)")
        assertProblem(problems, "code_paths[6] 'docs' must not point into the docs bundle")
        assertEquals(7, problems.size, problems.toString())
    }

    @Test
    fun `duplicates and invalid globs are problems`() {
        val problems = validate(listOf("$OTP/**", "$OTP/**", "$OTP/{api", "$OTP/")).problems
        assertProblem(problems, "code_paths[1] '$OTP/**' duplicates an earlier entry")
        assertProblem(problems, "code_paths[2] '$OTP/{api' is not a valid glob: unclosed `{` alternatives")
        assertProblem(problems, "code_paths[3] '$OTP/' is not a valid glob: globs match files — use `dir/**`")
        assertEquals(3, problems.size, problems.toString())
    }

    @Test
    fun `a glob matching no file outside the bundle is a problem`() {
        val problems = validate(listOf("$OTP/gone/**", "**/ADR-026-expiry.md")).problems
        assertProblem(problems, "code_paths[0] '$OTP/gone/**' matches no file in the repo")
        assertProblem(problems, "code_paths[1] '**/ADR-026-expiry.md' matches no file in the repo")
    }

    @Test
    fun `more than 100 matches is an over-broad warning, not a problem`() {
        val result = validate(listOf("$MAIN/**"))
        assertEquals(emptyList<String>(), result.problems)
        assertEquals(1, result.warnings.size, result.warnings.toString())
        assertProblem(result.warnings, "code_paths[0] '$MAIN/**' is over-broad (103 files, more than 100)")
        assertEquals(emptyList<String>(), validate(listOf("$MAIN/sport/internal/Gen1*.java")).warnings, "13 matches are fine")
    }

    @Test
    fun `without a repo index only the syntax is checked`() {
        assertEquals(CodePathsResult(emptyList(), emptyList()), OkfCodePaths.validate(listOf("nowhere/**"), null))
    }

    @Test
    fun `globs are read only from a list of strings`() {
        assertEquals(listOf("a/**"), OkfCodePaths.globs(Frontmatter.Present(mapOf("code_paths" to listOf("a/**")))))
        assertNull(OkfCodePaths.globs(Frontmatter.Present(mapOf("code_paths" to "a/**"))))
        assertNull(OkfCodePaths.globs(Frontmatter.Present(mapOf("code_paths" to listOf("a/**", 1)))))
        assertNull(OkfCodePaths.globs(Frontmatter.Present(emptyMap())))
    }

    @Test
    fun `okf check validates code_paths against the repo and requires it only when asked`(@TempDir root: File) {
        val bundle = root.resolve("docs").apply { mkdirs() }
        bundle.resolve("index.md").writeText("---\nokf_version: \"0.2\"\n---\n# Docs\n")
        bundle.resolve("adr").mkdirs()
        bundle.resolve("features").mkdirs()
        val adr = "type: ADR\ntitle: \"T\"\ndescription: \"D\"\ntags: [t]\nadr_date: 2026-07-01\n"
        bundle.resolve("adr/ADR-001-a.md").writeText("---\n${adr}status: stable\nadr_status: Accepted\ncode_paths: [\"$OTP/gone/**\"]\n---\n# A\n")
        bundle.resolve("adr/ADR-002-b.md").writeText("---\n${adr}status: stable\nadr_status: Accepted\n---\n# B\n")
        bundle.resolve("adr/ADR-003-c.md").writeText("---\n${adr}status: draft\nadr_status: Proposed\n---\n# C\n")
        bundle.resolve("features/f.md").writeText("---\ntype: Feature Record\n---\n# F\n")
        bundle.resolve("guide.md").writeText("---\ntype: Guide\n---\n# G\n")
        bundle.resolve("adr/index.md").writeText(OkfIndexRenderer.renderAdrIndex(bundle.resolve("adr"), TestConfigs.INDEX_COMMAND, IndexLanguage.SR, TestConfigs.SR.adrReadmeLink))
        bundle.resolve("features/index.md").writeText(OkfIndexRenderer.renderFeaturesIndex(bundle.resolve("features"), TestConfigs.INDEX_COMMAND, IndexLanguage.SR))
        val refs = OkfRefChecker(index, "docs")

        val lenient = OkfChecker.check(bundle, TestConfigs.SR, strict = true, refs = refs).problems
        assertEquals(listOf("adr/ADR-001-a.md: code_paths[0] '$OTP/gone/**' matches no file in the repo"), lenient)

        val required = OkfChecker.check(bundle, TestConfigs.SR, strict = true, refs = refs, codePathsRequired = true).problems
        assertProblem(required, "adr/ADR-002-b.md: `code_paths` is required on Accepted/Resolved ADRs")
        assertProblem(required, "features/f.md: `code_paths` is required on Feature Records")
        assertEquals(3, required.size, "Proposed ADRs and Guides stay optional: $required")
    }

    @Test
    fun `code_paths is required on Resolved ADRs, not on Superseded or Deprecated ones or Conventions, and an empty list satisfies it`(
        @TempDir bundle: File,
    ) {
        bundle.resolve("adr").mkdirs()
        fun adr(name: String, adrStatus: String, extra: String = "") =
            bundle.resolve("adr/$name.md").writeText("---\ntype: ADR\nadr_status: $adrStatus\n$extra---\n# $name\n")
        adr("ADR-001-resolved", "Resolved")
        adr("ADR-002-superseded", "Superseded")
        adr("ADR-003-deprecated", "Deprecated")
        adr("ADR-004-accepted-empty", "Accepted", "code_paths: []\n")
        bundle.resolve("features").mkdirs()
        bundle.resolve("features/README.md").writeText("---\ntype: Convention\n---\n# Conventions\n")
        bundle.resolve("features/f.md").writeText("---\ntype: Feature Record\ncode_paths: []\n---\n# F\n")

        val result = OkfChecker.check(bundle, TestConfigs.SR, strict = true, codePathsRequired = true)
        val required = result.problems.filter { "`code_paths` is required" in it }
        assertEquals(1, required.size, "only the Resolved ADR lacks code_paths: $required")
        assertProblem(required, "adr/ADR-001-resolved.md: `code_paths` is required on Accepted/Resolved ADRs")
        assertEquals(listOf("adr/ADR-001-resolved.md"), result.codePathsMissing)
        assertEquals(mapOf(OkfChecker.CODE_PATHS_ADRS to 1), result.codePathsMissingByKind)
        assertEquals(
            listOf("adr/ADR-001-resolved.md"),
            OkfChecker.check(bundle, TestConfigs.SR, strict = true).codePathsMissing,
            "missing code_paths is reported even when not required",
        )
    }

    private fun validate(raw: Any?) = OkfCodePaths.validate(raw, index)

    private fun assertProblem(problems: List<String>, expected: String) =
        assertTrue(problems.any { expected in it }, "expected a message containing <$expected>, got:\n${problems.joinToString("\n")}")

    private companion object {
        const val MAIN = "server/src/main/java/com/example/plaza"
        const val OTP = "$MAIN/otp"
    }
}
