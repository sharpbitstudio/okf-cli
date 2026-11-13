package com.sharpbitstudio.okf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant

class OkfCheckerTest {
    @TempDir
    lateinit var root: File

    @Test
    fun `valid ADR passes`() {
        bundle("adr/ADR-001-test.md" to adr())
        val result = OkfChecker.check(root, TestConfigs.SR, strict = true, now = NOW)
        assertEquals(emptyList<String>(), result.problems)
        assertEquals(1, result.validated)
    }

    @Test
    fun `missing type is reported`() {
        bundle("guide.md" to "---\ntitle: \"Guide\"\n---\n# Guide\n", "adr/ADR-001-test.md" to adr(changes = arrayOf("type" to null)))
        val problems = problems()
        assertProblem(problems, "guide.md: `type` is missing or empty")
        assertProblem(problems, "adr/ADR-001-test.md: `type` is missing or empty (files named adr/ADR-NNN-*.md must have `type: ADR`)")
    }

    @Test
    fun `unknown type is reported`() {
        bundle("guide.md" to "---\ntype: Blog\n---\n")
        assertProblem(problems(), "guide.md: type 'Blog' is not in the taxonomy")
    }

    @Test
    fun `ADR-named file must be type ADR and type ADR must be ADR-named`() {
        bundle(
            "adr/ADR-001-test.md" to "---\ntype: Guide\ntitle: \"Not an ADR\"\n---\n",
            "adr/ADR-1-short.md" to adr(),
            "notes.md" to adr(),
        )
        val problems = problems()
        assertProblem(problems, "adr/ADR-001-test.md: files named adr/ADR-NNN-*.md must have `type: ADR`, got 'Guide'")
        assertProblem(problems, "adr/ADR-1-short.md: `type: ADR` is only allowed on files named adr/ADR-NNN-<slug>.md")
        assertProblem(problems, "notes.md: `type: ADR` is only allowed on files named adr/ADR-NNN-<slug>.md")
    }

    @Test
    fun `ADR requires non-empty title, description and tags list`() {
        bundle(
            "adr/ADR-001-empty-tags.md" to adr(changes = arrayOf("tags" to "[]", "title" to "\"\"", "description" to null)),
            "adr/ADR-002-scalar-tags.md" to adr(changes = arrayOf("tags" to "\"a, b\"")),
            "adr/ADR-003-blank-tag.md" to adr(changes = arrayOf("tags" to "[ok, \"\"]")),
        )
        val problems = problems()
        assertProblem(problems, "adr/ADR-001-empty-tags.md: ADR requires `tags` as a non-empty YAML list of strings")
        assertProblem(problems, "adr/ADR-001-empty-tags.md: ADR requires a non-empty `title`")
        assertProblem(problems, "adr/ADR-001-empty-tags.md: ADR requires a non-empty `description`")
        assertProblem(problems, "adr/ADR-002-scalar-tags.md: ADR requires `tags` as a non-empty YAML list of strings")
        assertProblem(problems, "adr/ADR-003-blank-tag.md: tags[1] must be a non-empty string")
    }

    @Test
    fun `status inconsistent with adr_status is reported`() {
        bundle("adr/ADR-001-test.md" to adr(changes = arrayOf("status" to "draft")))
        assertProblem(problems(), "status 'draft' is inconsistent with adr_status 'Accepted' (expected status: stable)")
    }

    @Test
    fun `superseded_by must point to an existing file`() {
        bundle(
            "adr/ADR-001-test.md" to adr(
                changes = arrayOf("status" to "deprecated", "adr_status" to "Superseded", "superseded_by" to "./ADR-002-missing.md"),
            ),
        )
        assertProblem(problems(), "superseded_by target './ADR-002-missing.md' does not exist")
    }

    @Test
    fun `superseded_by is rejected on an Accepted ADR`() {
        bundle(
            "adr/ADR-001-test.md" to adr(changes = arrayOf("superseded_by" to "./ADR-002-new.md")),
            "adr/ADR-002-new.md" to adr(),
        )
        assertProblem(problems(), "superseded_by is only allowed with adr_status Superseded (got adr_status 'Accepted')")
    }

    @Test
    fun `amendment marker without amended entry is reported`() {
        bundle("adr/ADR-001-test.md" to adr(body = "# ADR\n\n**Update 2026-07-05:** changed.\n"))
        assertProblem(problems(), "body has an amendment marker dated 2026-07-05 (e.g. `**Update 2026-07-05:**`) but `amended` does not list 2026-07-05")
    }

    @Test
    fun `amended entry without marker is reported`() {
        bundle("adr/ADR-001-test.md" to adr(changes = arrayOf("amended" to "[2026-07-05]")))
        assertProblem(problems(), "amended lists 2026-07-05 but the body has no amendment marker for it")
    }

    @Test
    fun `amended must be strictly ascending`() {
        val body = "# ADR\n\n**Update 2026-07-05:** a\n\n**Update 2026-07-06:** b\n"
        bundle("adr/ADR-001-test.md" to adr(body = body, changes = arrayOf("amended" to "[2026-07-06, 2026-07-05]")))
        assertProblem(problems(), "amended dates must be strictly ascending (2026-07-06, then 2026-07-05)")
    }

    @Test
    fun `same-day marker is rejected with a dedicated message`() {
        bundle("adr/ADR-001-test.md" to adr(body = "# ADR\n\n**Update 2026-07-01:** same day.\n"))
        val problems = problems()
        assertProblem(problems, "same day as adr_date — same-day changes are part of the original decision: drop the marker")
        assertTrue(problems.none { "does not list" in it }, "generic message must not be emitted too: $problems")
    }

    @Test
    fun `bare dated heading is not an amendment marker, explicit headings are`() {
        bundle(
            "adr/ADR-001-bare.md" to adr(body = "# ADR\n\n## Updates\n\n### 2026-07-05 — bump\n"),
            "adr/ADR-002-explicit.md" to adr(
                body = "# ADR\n\n### Update 2026-07-05 — bump\n\n## Amendment — 2026-07-06: more\n\n> **Naknadna napomena (2026-07-07):** x\n",
                changes = arrayOf("amended" to "[2026-07-05, 2026-07-06, 2026-07-07]"),
            ),
        )
        assertEquals(emptyList<String>(), problems())
    }

    @Test
    fun `generated and verified at must be a datetime, not a date`() {
        bundle(
            "guide.md" to "---\ntype: Guide\ngenerated:\n  by: \"claude-code/test\"\n  at: 2026-09-25\nverified:\n  - by: \"human:y\"\n    at: 2026-09-25T10:00:00Z\n---\n",
            "plan.md" to "---\ntype: Plan\nverified:\n  by: \"human:y\"\n  at: 2026-09-25\n---\n",
        )
        val problems = problems()
        assertProblem(problems, "guide.md: generated.at '2026-09-25' must be an ISO-8601 datetime with offset or Z")
        assertProblem(problems, "plan.md: verified.at '2026-09-25' must be an ISO-8601 datetime with offset or Z")
        assertEquals(2, problems.size, "the full datetime in verified[0] must pass: $problems")
    }

    @Test
    fun `duplicate key is a per-file parse error`() {
        bundle("guide.md" to "---\ntype: Guide\ntype: Plan\n---\n")
        val problems = problems()
        assertEquals(1, problems.size, problems.toString())
        assertProblem(problems, "guide.md: frontmatter YAML does not parse")
        assertProblem(problems, "duplicate key")
    }

    @Test
    fun `BOM and CRLF input parses`() {
        val text = "\uFEFF" + adr(body = "# ADR\r\n\r\n**Update 2026-07-05:** x\r\n", changes = arrayOf("amended" to "[2026-07-05]"))
            .replace("\r\n", "\n").replace("\n", "\r\n")
        bundle("adr/ADR-001-test.md" to text)
        val result = OkfChecker.check(root, TestConfigs.SR, strict = true, now = NOW)
        assertEquals(emptyList<String>(), result.problems)
        assertEquals(1, result.validated)
    }

    @Test
    fun `stale or missing generated index is detected`() {
        bundle("adr/ADR-001-test.md" to adr())
        root.resolve("adr/ADR-002-new.md").writeText(adr())
        root.resolve("features/index.md").delete()
        val problems = problems()
        assertProblem(problems, "adr/index.md: out of date or edited by hand — run ./gradlew okfIndex")
        assertProblem(problems, "features/index.md: missing — run ./gradlew okfIndex")
    }

    @Test
    fun `unmigrated file fails only in strict mode`() {
        bundle("notes.md" to "# Notes without frontmatter\n")
        val lenient = OkfChecker.check(root, TestConfigs.SR, strict = false, now = NOW)
        assertEquals(emptyList<String>(), lenient.problems)
        assertEquals(listOf("notes.md"), lenient.unmigrated)
        val strict = OkfChecker.check(root, TestConfigs.SR, strict = true, now = NOW)
        assertProblem(strict.problems, "notes.md: no frontmatter (strict mode")
    }

    @Test
    fun `missing bundle-root index md is reported`() {
        bundle("guide.md" to "---\ntype: Guide\n---\n")
        root.resolve("index.md").delete()
        assertProblem(problems(), "index.md (bundle root) is missing — required, must carry okf_version \"0.2\"")
    }

    @Test
    fun `frontmatter after blank lines is reported in strict and lenient mode`() {
        bundle("guide.md" to "\n\n---\ntype: Guide\n---\n")
        listOf(false, true).forEach { strict ->
            val result = OkfChecker.check(root, TestConfigs.SR, strict = strict, now = NOW)
            assertProblem(result.problems, "guide.md: frontmatter must start on line 1")
            assertEquals(emptyList<String>(), result.unmigrated, "strict=$strict")
        }
    }

    @Test
    fun `missing ADR status names the expected value`() {
        bundle("adr/ADR-001-test.md" to adr(changes = arrayOf("status" to null)))
        assertProblem(problems(), "adr/ADR-001-test.md: status is missing (expected: stable for adr_status Accepted)")
    }

    @Test
    fun `superseded_by must point to an ADR file inside adr`() {
        bundle(
            "adr/ADR-001-outside.md" to adr(changes = superseded("../guide.md")),
            "adr/ADR-002-not-adr-name.md" to adr(changes = superseded("./notes.md")),
            "adr/ADR-003-ok.md" to adr(changes = superseded("./ADR-004-new.md")),
            "adr/ADR-004-new.md" to adr(),
            "adr/notes.md" to "---\ntype: Guide\n---\n",
            "guide.md" to "---\ntype: Guide\n---\n",
        )
        val problems = problems()
        assertProblem(problems, "adr/ADR-001-outside.md: superseded_by '../guide.md' must point to an ADR file in adr/")
        assertProblem(problems, "adr/ADR-002-not-adr-name.md: superseded_by './notes.md' must point to an ADR file in adr/")
        assertEquals(2, problems.size, problems.toString())
    }

    @Test
    fun `present but empty or non-mapping stamps are reported`() {
        bundle(
            "a.md" to "---\ntype: Guide\ngenerated:\n---\n",
            "b.md" to "---\ntype: Guide\ngenerated: \"\"\nverified: []\n---\n",
            "c.md" to "---\ntype: Guide\nverified:\n  - null\n  - \"human:y\"\n---\n",
            "d.md" to "---\ntype: Guide\nverified:\n---\n",
        )
        val problems = problems()
        assertProblem(problems, "a.md: generated must be a mapping with `by` and `at`")
        assertProblem(problems, "b.md: generated must be a mapping with `by` and `at`")
        assertProblem(problems, "b.md: verified must be a mapping with `by` and `at`, or a non-empty list of them")
        assertProblem(problems, "c.md: verified[0] must be a mapping with `by` and `at`")
        assertProblem(problems, "c.md: verified[1] must be a mapping with `by` and `at`")
        assertProblem(problems, "d.md: verified must be a mapping with `by` and `at`, or a non-empty list of them")
        assertEquals(6, problems.size, problems.toString())
    }

    @Test
    fun `amendment marker only inside a fenced block is not a marker`() {
        val body = "# ADR\n\n```markdown\n**Update 2026-07-05:** example\n## Update 2026-07-06\n```\n\n~~~\n**Update 2026-07-07:** x\n~~~\n"
        bundle("adr/ADR-001-test.md" to adr(body = body))
        assertEquals(emptyList<String>(), problems())
    }

    @Test
    fun `real amendment marker counts, fenced example does not`() {
        val body = "# ADR\n\n**Update 2026-07-05:** real.\n\n````\n```\n**Update 2026-07-08:** nested example\n```\n````\n"
        bundle(
            "adr/ADR-001-test.md" to adr(body = body, changes = arrayOf("amended" to "[2026-07-05]")),
            "adr/ADR-002-test.md" to adr(body = body, changes = arrayOf("amended" to "[2026-07-05, 2026-07-08]")),
        )
        val problems = problems()
        assertProblem(problems, "adr/ADR-002-test.md: amended lists 2026-07-08 but the body has no amendment marker for it")
        assertEquals(1, problems.size, problems.toString())
    }

    @Test
    fun `fenced example inside a block quote is not a marker, a quoted marker outside a fence is`() {
        val body = "# ADR\n\n> ```\n> **Update 2026-07-05:** example\n> ```\n\n> **Update 2026-07-06:** real\n\n" +
            "> > ~~~\n> > **Update 2026-07-07:** nested example\n> > ~~~\n\n" +
            "> ```\n> unclosed, ends with the quote\n\n**Update 2026-07-08:** real, after the quote\n"
        bundle("adr/ADR-001-test.md" to adr(body = body, changes = arrayOf("amended" to "[2026-07-06, 2026-07-08]")))
        assertEquals(emptyList<String>(), problems())
    }

    @Test
    fun `marker inside an inline code span is not a marker`() {
        val body = "# ADR\n\nWrite `**Update 2026-07-05:**` or ``**Update 2026-07-06:** `x` `` in the body.\n\n" +
            "`code` **Update 2026-07-09:** real, after a code span\n"
        bundle("adr/ADR-001-test.md" to adr(body = body, changes = arrayOf("amended" to "[2026-07-09]")))
        assertEquals(emptyList<String>(), problems())
    }

    @Test
    fun `backticks in the info string do not open a fence`() {
        // Not a fence opener: a single-line inline code span, so neither it nor the next line is fenced.
        val body = "# ADR\n\n```**Update 2026-07-05:**```\n**Update 2026-07-06:** real\n"
        bundle("adr/ADR-001-test.md" to adr(body = body, changes = arrayOf("amended" to "[2026-07-06]")))
        assertEquals(emptyList<String>(), problems())
    }

    @Test
    fun `four-space-indented backticks do not open a fence`() {
        val body = "# ADR\n\n    ```\n**Update 2026-07-07:** real\n    ```\n"
        bundle("adr/ADR-001-test.md" to adr(body = body, changes = arrayOf("amended" to "[2026-07-07]")))
        assertEquals(emptyList<String>(), problems())
    }

    @Test
    fun `superseded_by must not point to the ADR itself`() {
        bundle("adr/ADR-001-test.md" to adr(changes = superseded("./ADR-001-test.md")))
        val problems = problems()
        assertProblem(problems, "adr/ADR-001-test.md: superseded_by must not point to the ADR itself")
        assertEquals(1, problems.size, problems.toString())
    }

    @Test
    fun `non-root index md with invalid or present frontmatter is reported`() {
        bundle(
            "guides/index.md" to "---\ntype: [\n---\n",
            "plans/index.md" to "---\ntype: Guide\n---\n",
        )
        val problems = problems()
        assertProblem(problems, "guides/index.md: frontmatter YAML does not parse")
        assertProblem(problems, "plans/index.md: non-root index.md is reserved and must not have frontmatter")
        assertEquals(2, problems.size, problems.toString())
    }

    @Test
    fun `ADR-like name without a three-digit number is not an ADR for checker or index`() {
        bundle("adr/ADR-1-x.md" to "---\ntype: Guide\ntitle: \"Notes\"\n---\n", "adr/ADR-001-test.md" to adr())
        assertEquals(emptyList<String>(), problems())
    }

    @Test
    fun `malformed explicit YAML tag is a per-file parse error, not an exception`() {
        bundle("guide.md" to "---\ntype: Guide\ntitle: !!int x\n---\n", "plan.md" to "---\ntype: Plan\nstatus: !!float nope\n---\n")
        val problems = problems()
        assertProblem(problems, "guide.md: frontmatter YAML does not parse: value does not match its explicit tag (For input string: \"x\")")
        assertProblem(problems, "plan.md: frontmatter YAML does not parse: value does not match its explicit tag (")
        assertEquals(2, problems.size, problems.toString())
        assertTrue(problems.none { "java." in it }, problems.toString())
        assertTrue(OkfFrontmatter.parse("---\ntitle: !!int x\n---\n") is Frontmatter.Invalid)
    }

    @Test
    fun `satellite note with a well-formed hub passes when hub_repo is configured`() {
        bundle(
            "invite-respond/API_PLAN.md" to satellite(),
            "evening-digest/API_LOG.md" to satellite(hub = "features/evening-digest", codePaths = "[]"),
        )
        val result = OkfChecker.check(root, TestConfigs.SR.copy(hubRepo = HUB), strict = true, now = NOW, codePathsRequired = true)
        assertEquals(emptyList<String>(), result.problems)
        assertEquals(2, result.validated)
    }

    @Test
    fun `satellite note hub is required and must be features slash slug`() {
        bundle(
            "a.md" to satellite(hub = null),
            "b.md" to satellite(hub = "\"\""),
            "c.md" to satellite(hub = "features/Invite-Respond"),
            "d.md" to satellite(hub = "features/invite--respond"),
            "e.md" to satellite(hub = "adr/ADR-001-test"),
            "f.md" to satellite(hub = "features/invite-respond.md"),
            "g.md" to satellite(hub = "[features/invite-respond]"),
            "h.md" to satellite(hub = "features/a/b"),
            "i.md" to satellite(hub = "features/-x"),
            "ok.md" to satellite(hub = "features/otp2-login"),
        )
        val problems = OkfChecker.check(root, TestConfigs.SR.copy(hubRepo = HUB), strict = false, now = NOW).problems
        assertProblem(problems, "a.md: Satellite Note requires `hub: features/<slug>` — the hub Feature Record it belongs to")
        assertProblem(problems, "b.md: Satellite Note requires `hub: features/<slug>`")
        assertProblem(problems, "c.md: hub 'features/Invite-Respond' must be `features/<slug>` (slug: lowercase letters and digits")
        assertProblem(problems, "d.md: hub 'features/invite--respond' must be `features/<slug>`")
        assertProblem(problems, "e.md: hub 'adr/ADR-001-test' must be `features/<slug>`")
        assertProblem(problems, "f.md: hub 'features/invite-respond.md' must be `features/<slug>`")
        assertProblem(problems, "g.md: hub '[features/invite-respond]' must be `features/<slug>`")
        assertProblem(problems, "h.md: hub 'features/a/b' must be `features/<slug>`")
        assertProblem(problems, "i.md: hub 'features/-x' must be `features/<slug>`")
        assertEquals(9, problems.size, problems.toString())
    }

    @Test
    fun `satellite note hub is trimmed like every other scalar`() {
        bundle(
            "ok.md" to satellite(hub = "\"features/x \""),
            "ok2.md" to satellite(hub = "\" features/invite-respond\""),
            "blank.md" to satellite(hub = "\"  \""),
            "bad.md" to satellite(hub = "\" features/X \""),
        )
        val problems = OkfChecker.check(root, TestConfigs.SR.copy(hubRepo = HUB), strict = false, now = NOW).problems
        assertEquals(
            listOf(
                "bad.md: hub 'features/X' must be `features/<slug>` (slug: lowercase letters and digits, words joined by single hyphens)",
                "blank.md: Satellite Note requires `hub: features/<slug>` — the hub Feature Record it belongs to",
            ),
            problems,
        )
    }

    @Test
    fun `hub and repos on a document without type name the type as missing`() {
        bundle("x.md" to "---\nhub: features/x\nrepos: [mobile-proto]\n---\n")
        val problems = OkfChecker.check(root, TestConfigs.SR.copy(hubRepo = HUB, externalRepos = setOf("mobile-proto")), strict = false, now = NOW).problems
        assertProblem(problems, "x.md: `hub` is only allowed on Satellite Notes (got type '<missing>')")
        assertProblem(problems, "x.md: `repos` is only allowed on Feature Records (got type '<missing>')")
        assertTrue(problems.none { "'null'" in it }, problems.toString())
    }

    @Test
    fun `satellite note requires hub_repo in okf yaml`() {
        bundle("note.md" to satellite())
        assertProblem(problems(), "note.md: Satellite Note requires hub_repo in okf.yaml")
    }

    @Test
    fun `hub is only allowed on satellite notes`() {
        bundle("guide.md" to "---\ntype: Guide\nhub: features/invite-respond\n---\n", "features/x.md" to feature("hub: features/x\n"))
        val problems = OkfChecker.check(root, TestConfigs.SR.copy(hubRepo = HUB), strict = false, now = NOW).problems
        assertProblem(problems, "guide.md: `hub` is only allowed on Satellite Notes (got type 'Guide')")
        assertProblem(problems, "features/x.md: `hub` is only allowed on Satellite Notes (got type 'Feature Record')")
        assertEquals(2, problems.size, problems.toString())
    }

    @Test
    fun `code_paths required covers satellite notes`() {
        bundle("note.md" to satellite(codePaths = null))
        val lenient = OkfChecker.check(root, TestConfigs.SR.copy(hubRepo = HUB), strict = true, now = NOW)
        assertEquals(emptyList<String>(), lenient.problems)
        assertEquals(listOf("note.md"), lenient.codePathsMissing)
        assertEquals(mapOf(OkfChecker.CODE_PATHS_SATELLITE_NOTES to 1), lenient.codePathsMissingByKind)
        val required = OkfChecker.check(root, TestConfigs.SR.copy(hubRepo = HUB), strict = true, now = NOW, codePathsRequired = true).problems
        assertEquals(
            listOf(
                "note.md: `code_paths` is required on Satellite Notes — list the repo-root globs of the code it describes, " +
                    "or `code_paths: []` with the reason in the body",
            ),
            required,
        )
    }

    @Test
    fun `feature record repos must list distinct external repos`() {
        bundle(
            "features/ok.md" to feature("repos: [mobile-proto, mobile-app]\n"),
            "features/empty.md" to feature("repos: []\n"),
            "features/scalar.md" to feature("repos: mobile-proto\n"),
            "features/bad.md" to feature("repos: [mobile-proto, \"\", 3, mobile-proto, unknown-repo]\n"),
        )
        val problems = OkfChecker.check(root, TestConfigs.SR.copy(externalRepos = setOf("mobile-proto", "mobile-app")), strict = false, now = NOW).problems
        assertProblem(problems, "features/scalar.md: repos must be a YAML list of repository names from external_repos")
        assertProblem(problems, "features/bad.md: repos[1] must be a non-empty string, got ''")
        assertProblem(problems, "features/bad.md: repos[2] must be a non-empty string, got '3'")
        assertProblem(problems, "features/bad.md: repos[3] 'mobile-proto' duplicates an earlier entry")
        assertProblem(problems, "features/bad.md: repos[4] 'unknown-repo' is not listed in external_repos of okf.yaml")
        assertEquals(5, problems.size, problems.toString())
    }

    @Test
    fun `repos is only allowed on feature records`() {
        bundle("guide.md" to "---\ntype: Guide\nrepos: [mobile-proto]\n---\n", "note.md" to satellite(extra = "repos: [mobile-proto]\n"))
        val problems = OkfChecker.check(root, TestConfigs.SR.copy(hubRepo = HUB, externalRepos = setOf("mobile-proto")), strict = false, now = NOW).problems
        assertProblem(problems, "guide.md: `repos` is only allowed on Feature Records (got type 'Guide')")
        assertProblem(problems, "note.md: `repos` is only allowed on Feature Records (got type 'Satellite Note')")
        assertEquals(2, problems.size, problems.toString())
    }

    @Test
    fun `sub-indexes are required only for existing directories`() {
        root.resolve("index.md").writeText("---\nokf_version: \"0.2\"\n---\n# Docs\n")
        root.resolve("guide.md").writeText("---\ntype: Guide\n---\n")
        assertEquals(emptyList<String>(), problems())

        root.resolve(OkfIndexRenderer.FEATURES_DIR).mkdirs()
        assertEquals(listOf("features/index.md: missing — run ./gradlew okfIndex"), problems())
    }

    @Test
    fun `CRLF generated index is fresh`() {
        bundle("adr/ADR-001-test.md" to adr(), "features/x.md" to feature())
        for (dir in listOf(OkfIndexRenderer.ADR_DIR, OkfIndexRenderer.FEATURES_DIR)) {
            val index = root.resolve("$dir/index.md")
            index.writeText(index.readText().replace("\n", "\r\n"))
            assertTrue("\r\n" in index.readText())
        }
        assertEquals(emptyList<String>(), problems())
    }

    private fun problems(): List<String> = OkfChecker.check(root, TestConfigs.SR, strict = false, now = NOW).problems

    /** Satellite Note frontmatter; `null` drops [hub] / [codePaths]. */
    private fun satellite(hub: String? = "features/invite-respond", codePaths: String? = "[]", extra: String = ""): String =
        "---\ntype: Satellite Note\ntitle: \"API plan\"\nstatus: draft\n" +
            (hub?.let { "hub: $it\n" } ?: "") + (codePaths?.let { "code_paths: $it\n" } ?: "") + extra + "---\n# Note\n"

    private fun feature(extra: String = ""): String =
        "---\ntype: Feature Record\ntitle: \"Feature\"\nstatus: stable\ncode_paths: []\n$extra---\n# Feature\n"

    private fun superseded(target: String): Array<Pair<String, String?>> =
        arrayOf("status" to "deprecated", "adr_status" to "Superseded", "superseded_by" to target)

    @Test
    fun `hub Repos section must be fresh when satellites are configured`() {
        val satellites = listOf(Satellite("sat", "Satellite", "https://x/sat/blob/main/docs/index.md"))
        val head = "---\nokf_version: \"0.2\"\n---\n# Docs\n\n## Repos\n\n"
        bundle()
        root.resolve("index.md").writeText(head + "<!-- okf:repos-start -->\n<!-- okf:repos-end -->\n")
        assertEquals(
            listOf("index.md: Repos section out of date or edited by hand — run ./gradlew okfIndex"),
            OkfChecker.check(root, TestConfigs.SR.copy(satellites = satellites), strict = true, now = NOW).problems,
        )

        val fresh = head + "<!-- okf:repos-start -->\n" + OkfIndexRenderer.renderReposBlock(satellites, TestConfigs.INDEX_COMMAND) + "\n<!-- okf:repos-end -->\n"
        root.resolve("index.md").writeText(fresh.replace("\n", "\r\n"))
        assertEquals(emptyList<String>(), OkfChecker.check(root, TestConfigs.SR.copy(satellites = satellites), strict = true, now = NOW).problems)

        root.resolve("index.md").writeText(head)
        assertEquals(
            listOf(
                "index.md: the Repos section needs one `<!-- okf:repos-start -->` line followed by one `<!-- okf:repos-end -->` line " +
                    "(found 0 start / 0 end marker line(s)) — add them, then run ./gradlew okfIndex",
            ),
            OkfChecker.check(root, TestConfigs.SR.copy(satellites = satellites), strict = true, now = NOW).problems,
        )
    }

    @Test
    fun `without satellites the Repos section is not checked`() {
        bundle()
        assertEquals(emptyList<String>(), OkfChecker.check(root, TestConfigs.SR, strict = true, now = NOW).problems)
        root.resolve("index.md").writeText("---\nokf_version: \"0.2\"\n---\n# Docs\n<!-- okf:repos-start -->\nhand-written\n<!-- okf:repos-end -->\n")
        assertEquals(emptyList<String>(), OkfChecker.check(root, TestConfigs.SR, strict = true, now = NOW).problems)
    }

    @Test
    fun `extra_types extends the taxonomy, the finding lists the sorted union`() {
        bundle("runbook.md" to "---\ntype: Runbook\ntitle: \"Deploy\"\nstatus: stable\n---\n# Deploy\n", "guide.md" to "---\ntype: Blog\n---\n")
        val result = OkfChecker.check(root, TestConfigs.SR.copy(extraTypes = listOf("Runbook")), strict = true, now = NOW)
        assertEquals(
            listOf(
                "guide.md: type 'Blog' is not in the taxonomy " +
                    "[ADR, Convention, Feature Record, Guide, Plan, Product Spec, Risk Register, Runbook, Satellite Note]",
            ),
            result.problems,
        )
        assertEquals(2, result.validated)
    }

    @Test
    fun `an extra type has no rules of its own`() {
        bundle(
            "adr/ADR-002-runbook.md" to "---\ntype: Runbook\ntitle: \"Runbook\"\n---\n",
            "runbook.md" to "---\ntype: Runbook\nhub: features/invite-respond\nrepos: [mobile-proto]\n---\n",
            "plain.md" to "---\ntype: Runbook\nstale_after: 2026-01-01T00:00:00Z\n---\n",
        )
        val config = TestConfigs.SR.copy(extraTypes = listOf("Runbook"), hubRepo = HUB, externalRepos = setOf(HUB, "mobile-proto"))
        val result = OkfChecker.check(root, config, strict = true, now = NOW, codePathsRequired = true)
        assertEquals(
            listOf(
                "adr/ADR-002-runbook.md: files named adr/ADR-NNN-*.md must have `type: ADR`, got 'Runbook'",
                "runbook.md: `hub` is only allowed on Satellite Notes (got type 'Runbook')",
                "runbook.md: `repos` is only allowed on Feature Records (got type 'Runbook')",
            ),
            result.problems.filter { !it.startsWith("adr/index.md") },
        )
        assertEquals(emptyList<String>(), result.codePathsMissing)
        assertEquals(listOf("plain.md: stale_after 2026-01-01T00:00:00Z is in the past — review and re-verify the content."), result.warnings)
    }

    /** Writes a minimal bundle (root index.md, `adr/`, `features/`), the given files, then fresh sub-indexes. */
    private fun bundle(vararg files: Pair<String, String>) {
        root.resolve("index.md").writeText("---\nokf_version: \"0.2\"\n---\n# Docs\n")
        root.resolve(OkfIndexRenderer.ADR_DIR).mkdirs()
        root.resolve(OkfIndexRenderer.FEATURES_DIR).mkdirs()
        files.forEach { (path, text) -> root.resolve(path).apply { parentFile.mkdirs() }.writeText(text) }
        writeIndex(OkfIndexRenderer.ADR_DIR) { OkfIndexRenderer.renderAdrIndex(it, TestConfigs.INDEX_COMMAND, IndexLanguage.SR) }
        writeIndex(OkfIndexRenderer.FEATURES_DIR) { OkfIndexRenderer.renderFeaturesIndex(it, TestConfigs.INDEX_COMMAND, IndexLanguage.SR) }
    }

    // Fixtures with deliberately broken ADRs may not render; the checker then reports that itself.
    private fun writeIndex(dir: String, render: (File) -> String) {
        val text = try {
            render(root.resolve(dir))
        } catch (_: OkfException) {
            return
        }
        root.resolve("$dir/index.md").writeText(text)
    }

    private fun assertProblem(problems: List<String>, expected: String) =
        assertTrue(problems.any { expected in it }, "expected a problem containing <$expected>, got:\n${problems.joinToString("\n")}")

    /** Valid ADR frontmatter plus [body]; each change overrides a field, `null` removes it. */
    private fun adr(body: String = "# ADR\n", changes: Array<Pair<String, String?>> = emptyArray()): String {
        val fields = linkedMapOf(
            "type" to "ADR",
            "title" to "\"Test\"",
            "description" to "\"Kad testiraš\"",
            "tags" to "[testing]",
            "status" to "stable",
            "adr_status" to "Accepted",
            "adr_date" to "2026-07-01",
        )
        changes.forEach { (key, value) -> if (value == null) fields.remove(key) else fields[key] = value }
        return "---\n" + fields.entries.joinToString("") { "${it.key}: ${it.value}\n" } + "---\n" + body
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-25T00:00:00Z")
        const val HUB = "plaza-server-app"
    }
}
