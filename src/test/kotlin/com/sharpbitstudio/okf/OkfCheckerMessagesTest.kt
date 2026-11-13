package com.sharpbitstudio.okf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant

/**
 * The exact text of every finding and warning of [OkfChecker.check]: its own, the frontmatter reasons
 * ([OkfFrontmatter]), the `code_paths` findings ([OkfCodePaths]) and the glob diagnostics they carry ([OkfGlob]).
 * Case labels (`<SourceFile>#<n>`) are stable names assigned once to each message template; they are not line
 * numbers. A suffix `F` or `P` separates two variants of one template: the tool's own full text (`F`) and the variant
 * ending in passed-through text, asserted up to the tool's frame (`P`). Text passed through from the YAML parser is
 * asserted only up to the tool's own frame.
 */
class OkfCheckerMessagesTest {
    @TempDir
    lateinit var root: File

    // --- OkfChecker ---

    @Test
    fun `bundle-root index md missing`() {
        assertEquals(
            "index.md (bundle root) is missing — required, must carry okf_version \"0.2\"",
            pick(check().problems, "index.md (bundle root) is missing — required, must carry okf_version \"0.2\""),
            "OkfChecker#1",
        )
    }

    @Test
    fun `non-root index md with frontmatter`() {
        bundle("guides/index.md" to "---\ntype: Guide\n---\n# Guides\n")
        val expected = "guides/index.md: non-root index.md is reserved and must not have frontmatter"
        assertEquals(expected, pick(check().problems, expected), "OkfChecker#5")
    }

    @Test
    fun `per-document finding is prefixed with the path`() {
        bundle("guide.md" to "---\ntitle: \"Guide\"\n---\n# Guide\n")
        val expected = "guide.md: `type` is missing or empty"
        assertEquals(expected, pick(check().problems, expected), "OkfChecker#7")
    }

    @Test
    fun `no frontmatter in strict mode`() {
        bundle("notes.md" to "# Notes\n")
        val expected = "notes.md: no frontmatter (strict mode: every non-reserved .md must be migrated)"
        assertEquals(expected, pick(check(strict = true).problems, expected), "OkfChecker#8")
    }

    @Test
    fun `bundle-root index md frontmatter`() {
        bundle()
        root.resolve("index.md").writeText("# Docs\n")
        val absent = "index.md: bundle-root index.md must start with frontmatter `okf_version: \"0.2\"`"
        assertEquals(absent, pick(check().problems, absent), "OkfChecker#9")

        root.resolve("index.md").writeText("---\nokf_version: \"0.1\"\n---\n# Docs\n")
        val version = "index.md: okf_version must be the string \"0.2\" (quoted), got '0.1'"
        assertEquals(version, pick(check().problems, version), "OkfChecker#10")

        root.resolve("index.md").writeText("---\nokf_version: \"0.2\"\ntitle: Docs\nauthor: someone\n---\n# Docs\n")
        val extra = "index.md: bundle-root index.md frontmatter may only hold okf_version, found also: [author, title]"
        assertEquals(extra, pick(check().problems, extra), "OkfChecker#11")
    }

    @Test
    fun `type missing on an ADR file`() {
        bundle(ADR to adr(changes = arrayOf("type" to null)))
        val expected = "$ADR: `type` is missing or empty (files named adr/ADR-NNN-*.md must have `type: ADR`)"
        assertEquals(expected, pick(check().problems, expected), "OkfChecker#12")
    }

    @Test
    fun `type not in the taxonomy`() {
        bundle("guide.md" to "---\ntype: Blog\n---\n# Guide\n")
        val expected =
            "guide.md: type 'Blog' is not in the taxonomy [ADR, Convention, Feature Record, Guide, Plan, Product Spec, Risk Register, Satellite Note]"
        assertEquals(expected, pick(check().problems, expected), "OkfChecker#13")
    }

    @Test
    fun `ADR file name and type ADR must agree`() {
        bundle(ADR to adr(changes = arrayOf("type" to "Guide")), "notes.md" to adr())
        val problems = check().problems
        val notAdr = "$ADR: files named adr/ADR-NNN-*.md must have `type: ADR`, got 'Guide'"
        assertEquals(notAdr, pick(problems, notAdr), "OkfChecker#14")
        val notNamed = "notes.md: `type: ADR` is only allowed on files named adr/ADR-NNN-<slug>.md (three-digit number)"
        assertEquals(notNamed, pick(problems, notNamed), "OkfChecker#15")
    }

    @Test
    fun `status not a lifecycle value`() {
        bundle("guide.md" to "---\ntype: Guide\nstatus: final\n---\n# Guide\n")
        val expected = "guide.md: status 'final' must be one of [draft, stable, deprecated]"
        assertEquals(expected, pick(check().problems, expected), "OkfChecker#16")
    }

    @Test
    fun `hub and repos on the wrong type`() {
        bundle("guide.md" to "---\ntype: Guide\nhub: features/example\nrepos: [sat-a]\n---\n# Guide\n")
        val problems = check(externalRepos = setOf("sat-a")).problems
        val hub = "guide.md: `hub` is only allowed on Satellite Notes (got type 'Guide')"
        assertEquals(hub, pick(problems, hub), "OkfChecker#18")
        val repos = "guide.md: `repos` is only allowed on Feature Records (got type 'Guide')"
        assertEquals(repos, pick(problems, repos), "OkfChecker#19")

        bundle("guide.md" to "---\ntitle: \"Guide\"\nhub: features/example\nrepos: [sat-a]\n---\n# Guide\n")
        val untyped = check(externalRepos = setOf("sat-a")).problems
        val hubUntyped = "guide.md: `hub` is only allowed on Satellite Notes (got type '<missing>')"
        assertEquals(hubUntyped, pick(untyped, hubUntyped), "OkfChecker#17")
        val reposUntyped = "guide.md: `repos` is only allowed on Feature Records (got type '<missing>')"
        assertEquals(reposUntyped, pick(untyped, reposUntyped), "OkfChecker#17")
    }

    @Test
    fun `verified is neither a mapping nor a non-empty list`() {
        val expected = "guide.md: verified must be a mapping with `by` and `at`, or a non-empty list of them"
        bundle("guide.md" to "---\ntype: Guide\nverified: []\n---\n# Guide\n")
        assertEquals(expected, pick(check().problems, expected), "OkfChecker#20")

        bundle("guide.md" to "---\ntype: Guide\nverified: someone\n---\n# Guide\n")
        assertEquals(expected, pick(check().problems, expected), "OkfChecker#21")

        bundle("guide.md" to "---\ntype: Guide\nverified: [someone]\n---\n# Guide\n")
        val entry = "guide.md: verified[0] must be a mapping with `by` and `at`"
        assertEquals(entry, pick(check().problems, entry), "OkfChecker#58")
    }

    @Test
    fun `stale_after invalid or in the past`() {
        bundle("guide.md" to "---\ntype: Guide\nstale_after: soon\n---\n# Guide\n")
        val invalid = "guide.md: stale_after 'soon' is not a valid ISO-8601 instant (e.g. 2026-12-31T00:00:00Z)"
        assertEquals(invalid, pick(check().problems, invalid), "OkfChecker#22")

        bundle("guide.md" to "---\ntype: Guide\nstale_after: 2026-01-01T00:00:00Z\n---\n# Guide\n")
        assertEquals(
            listOf("guide.md: stale_after 2026-01-01T00:00:00Z is in the past — review and re-verify the content."),
            check().warnings,
            "OkfChecker#23",
        )
    }

    @Test
    fun `code_paths required`() {
        bundle(ADR to adr())
        val expected = "$ADR: `code_paths` is required on Accepted/Resolved ADRs — list the repo-root globs of the code it describes, " +
            "or `code_paths: []` with the reason in the body"
        assertEquals(expected, pick(check(codePathsRequired = true).problems, expected), "OkfChecker#24")
    }

    @Test
    fun `ADR title, description and adr_status`() {
        bundle(ADR to adr(changes = arrayOf("title" to "\"\"", "description" to null, "adr_status" to null)))
        val problems = check().problems
        val title = "$ADR: ADR requires a non-empty `title`"
        assertEquals(title, pick(problems, title), "OkfChecker#27")
        val description = "$ADR: ADR requires a non-empty `description` (\"when to read\")"
        assertEquals(description, pick(problems, description), "OkfChecker#28")
        val adrStatus = "$ADR: ADR requires a non-empty `adr_status` (one of [Proposed, Accepted, Resolved, Superseded, Deprecated, Rejected])"
        assertEquals(adrStatus, pick(problems, adrStatus), "OkfChecker#29")
    }

    @Test
    fun `adr_status and status`() {
        bundle(ADR to adr(changes = arrayOf("adr_status" to "Bogus")))
        val unknown = "$ADR: adr_status 'Bogus' must be one of [Proposed, Accepted, Resolved, Superseded, Deprecated, Rejected]"
        assertEquals(unknown, pick(check().problems, unknown), "OkfChecker#30")

        bundle(ADR to adr(changes = arrayOf("status" to null)))
        val missing = "$ADR: status is missing (expected: stable for adr_status Accepted)"
        assertEquals(missing, pick(check().problems, missing), "OkfChecker#31")

        bundle(ADR to adr(changes = arrayOf("status" to "draft")))
        val inconsistent = "$ADR: status 'draft' is inconsistent with adr_status 'Accepted' (expected status: stable)"
        assertEquals(inconsistent, pick(check().problems, inconsistent), "OkfChecker#32")
    }

    @Test
    fun `adr_date missing or invalid`() {
        bundle(ADR to adr(changes = arrayOf("adr_date" to null)))
        val missing = "$ADR: ADR requires `adr_date` as a valid YYYY-MM-DD date"
        assertEquals(missing, pick(check().problems, missing), "OkfChecker#33")

        bundle(ADR to adr(changes = arrayOf("adr_date" to "2026-13-01")))
        val invalid = "$ADR: adr_date '2026-13-01' must be a valid YYYY-MM-DD date"
        assertEquals(invalid, pick(check().problems, invalid), "OkfChecker#34")
    }

    @Test
    fun `superseded_by`() {
        bundle(ADR to adr(changes = superseded(null)))
        val missing = "$ADR: adr_status Superseded requires superseded_by"
        assertEquals(missing, pick(check().problems, missing), "OkfChecker#35")

        bundle(ADR to adr(changes = superseded("/ADR-002-other.md")))
        val absolute = "$ADR: superseded_by '/ADR-002-other.md' must be a relative path (e.g. ./ADR-NNN-....md)"
        assertEquals(absolute, pick(check().problems, absolute), "OkfChecker#36")

        bundle(ADR to adr(changes = superseded("../guide.md")))
        val notAdr = "$ADR: superseded_by '../guide.md' must point to an ADR file in adr/ (ADR-NNN-<slug>.md)"
        assertEquals(notAdr, pick(check().problems, notAdr), "OkfChecker#37")

        bundle(ADR to adr(changes = superseded("./ADR-001-example.md")))
        val itself = "$ADR: superseded_by must not point to the ADR itself"
        assertEquals(itself, pick(check().problems, itself), "OkfChecker#38")

        bundle(ADR to adr(changes = superseded("./ADR-002-missing.md")))
        val gone = "$ADR: superseded_by target './ADR-002-missing.md' does not exist"
        assertEquals(gone, pick(check().problems, gone), "OkfChecker#39")

        bundle(ADR to adr(changes = arrayOf("superseded_by" to "./ADR-002-other.md")))
        val notSuperseded = "$ADR: superseded_by is only allowed with adr_status Superseded (got adr_status 'Accepted')"
        assertEquals(notSuperseded, pick(check().problems, notSuperseded), "OkfChecker#40")
    }

    @Test
    fun `ADR tags`() {
        bundle(ADR to adr(changes = arrayOf("tags" to "[]")))
        val empty = "$ADR: ADR requires `tags` as a non-empty YAML list of strings (e.g. tags: [grpc, security]), got '[]'"
        assertEquals(empty, pick(check().problems, empty), "OkfChecker#41")

        bundle(ADR to adr(changes = arrayOf("tags" to "[testing, \"\"]")))
        val blank = "$ADR: tags[1] must be a non-empty string, got ''"
        assertEquals(blank, pick(check().problems, blank), "OkfChecker#42")
    }

    @Test
    fun `Satellite Note hub`() {
        bundle("note.md" to satelliteNote(hub = null))
        val missing = "note.md: Satellite Note requires `hub: features/<slug>` — the hub Feature Record it belongs to"
        assertEquals(missing, pick(check(hubRepo = "hub-repo").problems, missing), "OkfChecker#43")

        bundle("note.md" to satelliteNote(hub = "Features/Example"))
        val malformed = "note.md: hub 'Features/Example' must be `features/<slug>` " +
            "(slug: lowercase letters and digits, words joined by single hyphens)"
        assertEquals(malformed, pick(check(hubRepo = "hub-repo").problems, malformed), "OkfChecker#44")

        bundle("note.md" to satelliteNote(hub = "features/example"))
        val noHubRepo = "note.md: Satellite Note requires hub_repo in okf.yaml (the repository that holds its Feature Record)"
        assertEquals(noHubRepo, pick(check().problems, noHubRepo), "OkfChecker#45")
    }

    @Test
    fun `Feature Record repos`() {
        bundle("record.md" to featureRecord("repos: sat-a\n"))
        val scalar = "record.md: repos must be a YAML list of repository names from external_repos (e.g. repos: [sibling-repo]), got 'sat-a'"
        assertEquals(scalar, pick(check(externalRepos = setOf("sat-a")).problems, scalar), "OkfChecker#46")

        bundle("record.md" to featureRecord("repos: [sat-a, \"\", sat-a, sat-x]\n"))
        val problems = check(externalRepos = setOf("sat-a")).problems
        val blank = "record.md: repos[1] must be a non-empty string, got ''"
        assertEquals(blank, pick(problems, blank), "OkfChecker#47")
        val duplicate = "record.md: repos[2] 'sat-a' duplicates an earlier entry"
        assertEquals(duplicate, pick(problems, duplicate), "OkfChecker#48")
        val unknown = "record.md: repos[3] 'sat-x' is not listed in external_repos of okf.yaml"
        assertEquals(unknown, pick(problems, unknown), "OkfChecker#49")
    }

    @Test
    fun `amended list`() {
        bundle(ADR to adr(changes = arrayOf("amended" to "soon")))
        val notList = "$ADR: amended must be a YAML list of YYYY-MM-DD dates (e.g. amended: [2026-07-21]), got 'soon'"
        assertEquals(notList, pick(check().problems, notList), "OkfChecker#50")

        bundle(ADR to adr(changes = arrayOf("amended" to "[2026-02-30]")))
        val invalid = "$ADR: amended entry '2026-02-30' must be a valid YYYY-MM-DD date"
        assertEquals(invalid, pick(check().problems, invalid), "OkfChecker#51")

        bundle(ADR to adr(body = "# ADR\n\n**Update 2026-07-01:** same day.\n", changes = arrayOf("amended" to "[2026-07-01]")))
        val sameDay = "$ADR: amended date 2026-07-01 is the same day as adr_date — same-day changes are part of the original decision: " +
            "remove 2026-07-01 from `amended` and drop its marker"
        assertEquals(sameDay, pick(check().problems, sameDay), "OkfChecker#52")

        bundle(ADR to adr(body = "# ADR\n\n**Update 2026-06-01:** earlier.\n", changes = arrayOf("amended" to "[2026-06-01]")))
        val before = "$ADR: amended date 2026-06-01 must be after adr_date 2026-07-01"
        assertEquals(before, pick(check().problems, before), "OkfChecker#53")

        bundle(
            ADR to adr(
                body = "# ADR\n\n**Update 2026-08-01:** one.\n\n**Update 2026-07-15:** two.\n",
                changes = arrayOf("amended" to "[2026-08-01, 2026-07-15]"),
            ),
        )
        val order = "$ADR: amended dates must be strictly ascending (2026-08-01, then 2026-07-15)"
        assertEquals(order, pick(check().problems, order), "OkfChecker#54")
    }

    @Test
    fun `amendment markers and amended agree`() {
        bundle(ADR to adr(body = "# ADR\n\n**Update 2026-07-01:** same day.\n"))
        val sameDay = "$ADR: body has an amendment marker dated 2026-07-01, the same day as adr_date — same-day changes are part of " +
            "the original decision: drop the marker (do not add 2026-07-01 to `amended`)"
        assertEquals(sameDay, pick(check().problems, sameDay), "OkfChecker#55")

        bundle(ADR to adr(body = "# ADR\n\n**Update 2026-08-01:** later.\n"))
        val unlisted = "$ADR: body has an amendment marker dated 2026-08-01 (e.g. `**Update 2026-08-01:**`) but `amended` does not list 2026-08-01"
        assertEquals(unlisted, pick(check().problems, unlisted), "OkfChecker#56")

        bundle(ADR to adr(changes = arrayOf("amended" to "[2026-08-01]")))
        val unmarked = "$ADR: amended lists 2026-08-01 but the body has no amendment marker for it " +
            "(add `**Update 2026-08-01:**` where the ADR was changed)"
        assertEquals(unmarked, pick(check().problems, unmarked), "OkfChecker#57")
    }

    @Test
    fun `actor stamps`() {
        bundle("guide.md" to "---\ntype: Guide\ngenerated: someone\n---\n# Guide\n")
        val notMapping = "guide.md: generated must be a mapping with `by` and `at`"
        assertEquals(notMapping, pick(check().problems, notMapping), "OkfChecker#58")

        bundle("guide.md" to "---\ntype: Guide\ngenerated: {at: 2026-01-01T00:00:00Z}\n---\n# Guide\n")
        val noBy = "guide.md: generated.by is missing"
        assertEquals(noBy, pick(check().problems, noBy), "OkfChecker#59")

        bundle("guide.md" to "---\ntype: Guide\nverified: {by: someone, at: 2026-01-01}\n---\n# Guide\n")
        val dateOnly = "guide.md: verified.at '2026-01-01' must be an ISO-8601 datetime with offset or Z (e.g. 2026-09-25T00:00:00Z); " +
            "a date alone is not accepted"
        assertEquals(dateOnly, pick(check().problems, dateOnly), "OkfChecker#60")
    }

    @Test
    fun `generated sub-indexes`() {
        bundle(ADR to adr(changes = arrayOf("adr_status" to "Bogus")))
        val unrenderable = "adr/index.md: cannot be rendered (adr/ADR-001-example.md: unknown or missing adr_status 'Bogus') " +
            "— fix that, then run $INDEX_COMMAND"
        assertEquals(unrenderable, pick(check().problems, unrenderable), "OkfChecker#61")

        bundle()
        root.resolve(OkfIndexRenderer.FEATURES_DIR).mkdirs()
        val missing = "features/index.md: missing — run $INDEX_COMMAND"
        assertEquals(missing, pick(check().problems, missing), "OkfChecker#62")

        root.resolve("features/index.md").writeText("# Edited by hand\n")
        val stale = "features/index.md: out of date or edited by hand — run $INDEX_COMMAND"
        assertEquals(stale, pick(check().problems, stale), "OkfChecker#63")
    }

    @Test
    fun `Repos section of the hub`() {
        val satellites = listOf(Satellite("sat-a", "Satellite A", "https://example.org/sat-a/docs/index.md"))
        bundle()
        val noMarkers = "index.md: the Repos section needs one `<!-- okf:repos-start -->` line followed by one `<!-- okf:repos-end -->` line " +
            "(found 0 start / 0 end marker line(s)) — add them, then run $INDEX_COMMAND"
        assertEquals(noMarkers, pick(check(satellites = satellites).problems, noMarkers), "OkfChecker#64")

        root.resolve("index.md").writeText(ROOT_INDEX + "\n## Repos\n\n<!-- okf:repos-start -->\n<!-- okf:repos-end -->\n")
        val stale = "index.md: Repos section out of date or edited by hand — run $INDEX_COMMAND"
        assertEquals(stale, pick(check(satellites = satellites).problems, stale), "OkfChecker#65")
    }

    // --- OkfFrontmatter ---

    @Test
    fun `frontmatter misplaced or unclosed`() {
        bundle("guide.md" to "\n---\ntype: Guide\n---\n# Guide\n")
        val misplaced = "guide.md: frontmatter must start on line 1 (found `---` after blank line(s))"
        assertEquals(misplaced, pick(check().problems, misplaced), "OkfFrontmatter#1")

        bundle("guide.md" to "---\ntype: Guide\n# Guide\n")
        val unclosed = "guide.md: frontmatter opened with '---' on line 1 but never closed"
        assertEquals(unclosed, pick(check().problems, unclosed), "OkfFrontmatter#2")
    }

    @Test
    fun `frontmatter YAML errors`() {
        bundle("guide.md" to "---\ntype: [Guide\n---\n# Guide\n")
        val parse = "guide.md: frontmatter YAML does not parse: "
        assertEquals(parse, frame(check().problems, parse), "OkfFrontmatter#3")

        bundle("guide.md" to "---\n- Guide\n---\n# Guide\n")
        val notMapping = "guide.md: frontmatter must be a YAML mapping, got "
        assertEquals(notMapping, frame(check().problems, notMapping), "OkfFrontmatter#4")

        bundle("guide.md" to "---\ntype: Guide\ntitle: !!int bogus\n---\n# Guide\n")
        val tag = "guide.md: frontmatter YAML does not parse: value does not match its explicit tag ("
        assertEquals(tag, frame(check().problems, tag), "OkfFrontmatter#5")
    }

    // --- OkfCodePaths ---

    @Test
    fun `code_paths not a list, blank entry`() {
        bundle("guide.md" to guide("code_paths: src/main/**"))
        val notList = "guide.md: code_paths must be a YAML list of repo-root globs (e.g. code_paths: [\"src/main/java/com/example/feature/**\"]) " +
            "or [] for a document that covers no code, got 'src/main/**'"
        assertEquals(notList, pick(check().problems, notList), "OkfCodePaths#1")

        bundle("guide.md" to guide("code_paths: [\"\"]"))
        val blank = "guide.md: code_paths[0] must be a non-empty string, got ''"
        assertEquals(blank, pick(check().problems, blank), "OkfCodePaths#2")
    }

    @Test
    fun `code_paths entry shape`() {
        bundle("guide.md" to guide("code_paths: [\"/src/**\"]"))
        val absolute = "guide.md: code_paths[0] '/src/**' must be relative to the repo root, not absolute"
        assertEquals(absolute, pick(check().problems, absolute), "OkfCodePaths#3")

        bundle("guide.md" to guide("code_paths: ['src\\**']"))
        val backslash = "guide.md: code_paths[0] 'src\\**' must use `/` as separator, not `\\`"
        assertEquals(backslash, pick(check().problems, backslash), "OkfCodePaths#4")

        bundle("guide.md" to guide("code_paths: [\"src/../lib/**\"]"))
        val dotDot = "guide.md: code_paths[0] 'src/../lib/**' must not contain `..` segments"
        assertEquals(dotDot, pick(check().problems, dotDot), "OkfCodePaths#5")

        bundle("guide.md" to guide("code_paths: [\"./src/**\"]"))
        val dotSlash = "guide.md: code_paths[0] './src/**' must not start with `./` (globs are repo-root relative already)"
        assertEquals(dotSlash, pick(check().problems, dotSlash), "OkfCodePaths#6")

        bundle("guide.md" to guide("code_paths: [\"docs/**\"]"))
        val bundled = "guide.md: code_paths[0] 'docs/**' must not point into the docs bundle (docs/) — it names code"
        assertEquals(bundled, pick(check().problems, bundled), "OkfCodePaths#7")

        bundle("guide.md" to guide("code_paths: [\"src/**\", \"src/**\"]"))
        val duplicate = "guide.md: code_paths[1] 'src/**' duplicates an earlier entry"
        assertEquals(duplicate, pick(check().problems, duplicate), "OkfCodePaths#8")
    }

    @Test
    fun `code_paths glob does not compile`() {
        bundle("guide.md" to guide("code_paths: [\"src/\"]"))
        val own = "guide.md: code_paths[0] 'src/' is not a valid glob: globs match files — use `dir/**`"
        assertEquals(own, pick(check().problems, own), "OkfCodePaths#10F")

        // Once regex-engine text (hence `P`); since the message cleanup the tool's own full text. Labels are assigned once.
        bundle("guide.md" to guide("code_paths: [\"src/[z-a].kt\"]"))
        val range = "guide.md: code_paths[0] 'src/[z-a].kt' is not a valid glob: " +
            "a `[...]` character class has a range whose start comes after its end (e.g. `[z-a]`)"
        assertEquals(range, pick(check().problems, range), "OkfCodePaths#10P")
    }

    @Test
    fun `code_paths matched against the repo`() {
        bundle("guide.md" to guide("code_paths: [\"lib/**\"]"))
        val none = "guide.md: code_paths[0] 'lib/**' matches no file in the repo"
        assertEquals(none, pick(check(refs = refs(listOf("src/App.kt"))).problems, none), "OkfCodePaths#11")

        bundle("guide.md" to guide("code_paths: [\"src/**\"]"))
        val overBroad = "guide.md: code_paths[0] 'src/**' is over-broad (101 files, more than 100) — narrow it to the code the document describes"
        assertEquals(
            listOf(overBroad),
            check(refs = refs((0..100).map { "src/F$it.kt" })).warnings,
            "OkfCodePaths#12",
        )
    }

    // --- OkfGlob ---

    @Test
    fun `glob diagnostics the checker cannot reach`() {
        assertEquals("empty glob", assertThrows<IllegalArgumentException> { OkfGlob.compile("") }.message, "OkfGlob#1")
        assertEquals(
            "`\\` is not allowed — paths use `/`",
            assertThrows<IllegalArgumentException> { OkfGlob.compile("a\\b") }.message,
            "OkfGlob#2",
        )
    }

    @Test
    fun `glob diagnostics as code_paths findings`() {
        val tooMany = "src/" + "{a,b}".repeat(10) + ".kt"
        assertEquals(
            "guide.md: code_paths[0] '$tooMany' is not a valid glob: too many `{}` combinations (max 1000)",
            globProblem(tooMany),
            "OkfGlob#3",
        )
        assertEquals(
            "guide.md: code_paths[0] '{src/,lib/**}' is not a valid glob: globs match files — use `dir/**` in alternative 'src/'",
            globProblem("{src/,lib/**}"),
            "OkfGlob#4",
        )
        assertEquals(
            "guide.md: code_paths[0] 'lib/' is not a valid glob: globs match files — use `dir/**`",
            globProblem("lib/"),
            "OkfGlob#5",
        )
        assertEquals(
            "guide.md: code_paths[0] 'src/a**' is not a valid glob: `**` must be a whole path segment (`a/**`, `**/b`, `a/**/b`)",
            globProblem("src/a**"),
            "OkfGlob#6",
        )
        assertEquals(
            "guide.md: code_paths[0] 'src/[ab' is not a valid glob: unclosed `[` character class",
            globProblem("src/[ab"),
            "OkfGlob#7",
        )
        assertEquals(
            "guide.md: code_paths[0] 'src/[a/b]' is not a valid glob: a `[...]` character class cannot match `/`",
            globProblem("src/[a/b]"),
            "OkfGlob#8",
        )
        assertEquals(
            "guide.md: code_paths[0] 'src/a}' is not a valid glob: `}` without a matching `{`",
            globProblem("src/a}"),
            "OkfGlob#9",
        )
        assertEquals(
            "guide.md: code_paths[0] 'src/{a,b' is not a valid glob: unclosed `{` alternatives",
            globProblem("src/{a,b"),
            "OkfGlob#11",
        )
    }

    /** The checker's findings for `guide.md` holding `code_paths: [<glob>]`. */
    private fun globProblem(glob: String): String {
        bundle("guide.md" to guide("code_paths: [\"$glob\"]"))
        return check().problems.joinToString("\n")
    }

    /** Every checker call of this class goes through here. */
    private fun check(
        strict: Boolean = false,
        refs: OkfRefChecker? = null,
        codePathsRequired: Boolean = false,
        hubRepo: String? = null,
        externalRepos: Set<String> = emptySet(),
        satellites: List<Satellite> = emptyList(),
    ): OkfCheckResult =
        OkfChecker.check(
            root,
            // The repo name and any further setting come from TestConfigs; command and language are this suite's own.
            TestConfigs.SR.copy(
                indexCommand = INDEX_COMMAND,
                indexLanguage = LANGUAGE,
                hubRepo = hubRepo,
                externalRepos = externalRepos,
                satellites = satellites,
            ),
            strict,
            NOW,
            refs,
            false,
            codePathsRequired,
        )

    /** [expected] when [messages] holds it verbatim, else every message (so a mismatch shows them all). */
    private fun pick(messages: List<String>, expected: String): String = messages.firstOrNull { it == expected } ?: messages.joinToString("\n")

    /** The [prefix] of the first message that starts with it, else every message: for text passed through from elsewhere. */
    private fun frame(messages: List<String>, prefix: String): String =
        if (messages.any { it.startsWith(prefix) }) prefix else messages.joinToString("\n")

    /** A reference checker over [files] (repo-relative), bundle at `docs`: `code_paths` globs are then matched against them. */
    private fun refs(files: List<String>) = OkfRefChecker(RepoIndex(files), "docs")

    /**
     * Replaces the bundle with a root index.md and [files], then writes fresh sub-indexes for the sub-directories that
     * exist (none when the fixture does not render; the checker then reports that itself).
     */
    private fun bundle(vararg files: Pair<String, String>) {
        root.listFiles()?.forEach { it.deleteRecursively() }
        root.resolve("index.md").writeText(ROOT_INDEX)
        files.forEach { (path, text) -> root.resolve(path).apply { parentFile.mkdirs() }.writeText(text) }
        writeIndex(OkfIndexRenderer.ADR_DIR) { OkfIndexRenderer.renderAdrIndex(it, INDEX_COMMAND, LANGUAGE, TestConfigs.SR.adrReadmeLink) }
        writeIndex(OkfIndexRenderer.FEATURES_DIR) { OkfIndexRenderer.renderFeaturesIndex(it, INDEX_COMMAND, LANGUAGE) }
    }

    private fun writeIndex(dir: String, render: (File) -> String) {
        if (!root.resolve(dir).isDirectory) return
        val text = try {
            render(root.resolve(dir))
        } catch (_: OkfException) {
            return
        }
        root.resolve("$dir/index.md").writeText(text)
    }

    /** Valid ADR frontmatter plus [body]; each change overrides a field, `null` removes it. */
    private fun adr(body: String = "# ADR\n", changes: Array<Pair<String, String?>> = emptyArray()): String {
        val fields = linkedMapOf(
            "type" to "ADR",
            "title" to "\"Example\"",
            "description" to "\"When to read\"",
            "tags" to "[testing]",
            "status" to "stable",
            "adr_status" to "Accepted",
            "adr_date" to "2026-07-01",
        )
        changes.forEach { (key, value) -> if (value == null) fields.remove(key) else fields[key] = value }
        return "---\n" + fields.entries.joinToString("") { "${it.key}: ${it.value}\n" } + "---\n" + body
    }

    /** ADR changes for `adr_status: Superseded` with [target] as `superseded_by` (`null` = absent). */
    private fun superseded(target: String?): Array<Pair<String, String?>> =
        arrayOf("status" to "deprecated", "adr_status" to "Superseded", "superseded_by" to target)

    private fun guide(extra: String) = "---\ntype: Guide\n$extra\n---\n# Guide\n"

    /** Satellite Note frontmatter; `null` drops [hub]. */
    private fun satelliteNote(hub: String?) =
        "---\ntype: Satellite Note\ntitle: \"Note\"\nstatus: draft\n" + (hub?.let { "hub: $it\n" } ?: "") + "code_paths: []\n---\n# Note\n"

    private fun featureRecord(extra: String) = "---\ntype: Feature Record\ntitle: \"Record\"\nstatus: stable\ncode_paths: []\n$extra---\n# Record\n"

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-25T00:00:00Z")
        const val INDEX_COMMAND = "tool/run-okf.sh index"
        val LANGUAGE = IndexLanguage.EN
        const val ROOT_INDEX = "---\nokf_version: \"0.2\"\n---\n# Docs\n"
        const val ADR = "adr/ADR-001-example.md"
    }
}
