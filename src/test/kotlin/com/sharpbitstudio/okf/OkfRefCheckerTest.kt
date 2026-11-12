package com.sharpbitstudio.okf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant

class OkfRefCheckerTest {
    private val lines = mapOf(
        "build.gradle.kts" to 10,
        "server/build.gradle.kts" to 200,
        "$MAIN/otp/api/OtpService.java" to 30,
    )

    private val index = RepoIndex(
        listOf(
            "build.gradle.kts",
            "server/build.gradle.kts",
            "buildSrc/build.gradle.kts",
            "docs/index.md",
            "docs/DEVELOPMENT.md",
            "docs/Plaza Notes.md",
            "docs/adr/ADR-001-test.md",
            "docs/features/otp-login.md",
            "$MAIN/App.java",
            "$MAIN/otp/package-info.java",
            "$MAIN/sport/package-info.java",
            "$MAIN/otp/api/OtpService.java",
            "$MAIN/otp/internal/jobs/OtpExpiryJob.java",
            "$MAIN/otp/internal/jobs/OtpExpirySafetyNetJob.java",
            "$MAIN/otp/api/package-info.java",
            "$MAIN/sport/api/package-info.java",
            "$MAIN/sport/api/events/package-info.java",
            "$MAIN/sport/internal/jobs/SportJob.java",
            "$TEST/BaseIntegrationTest.java",
            "$TEST/otp/OtpServiceTest.java",
            "$TEST/otp/api/OtpServiceContractTest.java",
            "server/src/main/resources/application.yml",
            "server/src/main/resources/db/migration/otp/V1__otp.sql",
            "compose/grafana/provisioning/datasources/datasources.yaml",
        ),
    ) { lines[it] ?: 50 }

    @Test
    fun `repo-root path resolves, a missing one is reported at its file line`() {
        val findings = findings("# T\n\nSee `server/build.gradle.kts` and `server/src/Gone.java`.\n")
        assertEquals(1, findings.size, findings.toString())
        assertEquals("adr/ADR-001-test.md", findings[0].doc)
        assertEquals(6, findings[0].line, "3 frontmatter lines + 2 body lines before it")
        assertContains(findings[0].message, "`server/src/Gone.java`: file not found in the repo")
    }

    @Test
    fun `doc-relative path resolves, a path leaving the repo is skipped`() {
        val body = "`../features/otp-login.md`, `./ADR-001-test.md`, `../../../elsewhere/Foo.java`, `../features/nope.md`\n"
        assertMessages(findings(body), "`../features/nope.md`: file not found")
    }

    @Test
    fun `package roots resolve main, test and resources relative paths`() {
        val body = "`otp/api/OtpService.java` `otp/OtpServiceTest.java` `db/migration/otp/V1__otp.sql` " +
            "`com/example/plaza/App.java` `otp/api/Nope.java`\n"
        assertMessages(findings(body), "`otp/api/Nope.java`: file not found")
    }

    @Test
    fun `unique suffix resolves, including legacy src paths, on whole segments only`() {
        val body = "`internal/jobs/OtpExpiryJob.java` `src/main/java/com/example/plaza/otp/api/OtpService.java` " +
            "`jobs/ExpiryJob.java`\n"
        assertMessages(findings(body), "`jobs/ExpiryJob.java`: file not found")
    }

    @Test
    fun `single-segment file resolves when the basename exists anywhere`() {
        assertMessages(findings("`application.yml` `package-info.java` `OtpExpiryJob.java` `V2__gone.sql`\n"), "`V2__gone.sql`: file not found")
    }

    @Test
    fun `directories resolve like files, single-segment directories are skipped`() {
        val body = "`api/` `internal/` `otp/internal/jobs/` `compose/grafana/` `docs/features/` `grpcweb/otp/`\n"
        assertMessages(findings(body), "`grpcweb/otp/`: directory not found")
    }

    @Test
    fun `line numbers must lie within the file`() {
        val body = "`otp/api/OtpService.java:30` `otp/api/OtpService.java:10-30` `otp/api/OtpService.java:31`\n" +
            "`otp/api/OtpService.java:20-31` `otp/api/OtpService.java:5–40` `otp/api/OtpService.java:0` `otp/api/OtpService.java:9-3`\n"
        assertMessages(
            findings(body),
            "`otp/api/OtpService.java:31`: line 31 is past the end of $MAIN/otp/api/OtpService.java (30 lines)",
            "`otp/api/OtpService.java:20-31`: line 31 is past the end",
            "`otp/api/OtpService.java:5–40`: line 40 is past the end",
            "`otp/api/OtpService.java:0`: invalid line range",
            "`otp/api/OtpService.java:9-3`: invalid line range",
        )
    }

    @Test
    fun `line number on an ambiguous single segment asks to qualify it, without a line the basename is fine`() {
        val findings = findings("`package-info.java` `package-info.java:3`\n")
        assertMessages(findings, "`package-info.java:3`: ambiguous: qualify the path (5 candidates: ")
        assertContains(findings[0].message, "$MAIN/otp/api/package-info.java, $MAIN/otp/package-info.java, ")
        assertContains(findings[0].message, ", …)")
    }

    @Test
    fun `ambiguous multi-segment suffix is a finding with or without a line, a unique one or one package is fine`() {
        val body = "`api/package-info.java` `sport/api/package-info.java` `api/package-info.java:2` `internal/jobs/` " +
            "`otp/internal/jobs/` `otp/api/`\n"
        assertMessages(
            findings(body),
            "`api/package-info.java`: ambiguous: qualify the path " +
                "(2 candidates: $MAIN/otp/api/package-info.java, $MAIN/sport/api/package-info.java)",
            "`api/package-info.java:2`: ambiguous: qualify the path (2 candidates:",
            "`internal/jobs/`: ambiguous: qualify the path (2 candidates: $MAIN/otp/internal/jobs, $MAIN/sport/internal/jobs)",
        )
    }

    @Test
    fun `comma line lists check every element`() {
        val body = "`otp/api/OtpService.java:4,9` `otp/api/OtpService.java:23, 30` `otp/api/OtpService.java:1-3,9` " +
            "`otp/api/OtpService.java:1–3, 5-7,30`\n" +
            "`otp/api/OtpService.java:4,31` `otp/api/OtpService.java:2, 35` `otp/api/OtpService.java:1-3,0` `Gone.java:46,93`\n"
        assertMessages(
            findings(body),
            "`otp/api/OtpService.java:4,31`: line 31 is past the end",
            "`otp/api/OtpService.java:2, 35`: line 35 is past the end",
            "`otp/api/OtpService.java:1-3,0`: invalid line range",
            "`Gone.java:46,93`: file not found",
        )
    }

    @Test
    fun `line number that does not fit an Int is an invalid line reference`() {
        assertMessages(
            findings("`otp/api/OtpService.java:2147483648` `otp/api/OtpService.java:3,99999999999`\n"),
            "`otp/api/OtpService.java:2147483648`: invalid line reference",
            "`otp/api/OtpService.java:3,99999999999`: invalid line reference",
        )
    }

    @Test
    fun `malformed line part is an invalid line reference, and a missing path is still reported`() {
        val body = "`otp/api/OtpService.java:30,` `otp/api/OtpService.java:5-` `otp/api/OtpService.java:12-14a` " +
            "`otp/api/OtpService.java:1,,2` `Gone.java:5-`\n"
        assertMessages(
            findings(body),
            "`otp/api/OtpService.java:30,`: invalid line reference",
            "`otp/api/OtpService.java:5-`: invalid line reference",
            "`otp/api/OtpService.java:12-14a`: invalid line reference",
            "`otp/api/OtpService.java:1,,2`: invalid line reference",
            "`Gone.java:5-`: file not found",
        )
    }

    @Test
    fun `repo root wins over other build files, so a stale root line number overflows`() {
        assertMessages(
            findings("`build.gradle.kts:150` `server/build.gradle.kts:150`\n"),
            "`build.gradle.kts:150`: line 150 is past the end of build.gradle.kts (10 lines)",
        )
    }

    @Test
    fun `path with an identifier suffix checks the path only`() {
        assertMessages(findings("`OtpService.java:requestOtp` `Gone.java:SOME_CONSTANT`\n"), "`Gone.java:SOME_CONSTANT`: file not found")
    }

    @Test
    fun `tokens outside the grammar are not references`() {
        val body = "`mobile/v1/otp.proto` `lib/main.dart` `server/src/main/java` `spring.grpc.server.port` `ADR-034` " +
            "`:server:test` `/api/rest/` `~/.gradle/gradle.properties` `D:\\x\\Gone.java` `Gone.java#method` `v1.2.3`\n"
        assertMessages(findings(body))
    }

    @Test
    fun `placeholders and patterns are skipped`() {
        val body = "`a/.../Gone.java` `a/…/Gone.java` `ADR-<NNN>.md` `docs/adr/ADR-*.md` `\$HOME/Gone.java` " +
            "`./gradlew :server:test --tests Gone.java`\n"
        assertMessages(findings(body))
    }

    @Test
    fun `brace groups expand to every variant`() {
        assertEquals(listOf("a/x.kt", "a/y.kt", "b/x.kt", "b/y.kt"), OkfRefChecker.expandBraces("{a,b}/{x,y}.kt"))
        assertEquals(listOf("{a.kt"), OkfRefChecker.expandBraces("{a.kt"))
        val body = "`otp/internal/jobs/{OtpExpiryJob,OtpExpirySafetyNetJob}.java` `otp/internal/jobs/{OtpExpiryJob,OtpGoneJob}.java`\n"
        assertMessages(
            findings(body),
            "`otp/internal/jobs/OtpGoneJob.java (from otp/internal/jobs/{OtpExpiryJob,OtpGoneJob}.java)`: file not found",
        )
    }

    @Test
    fun `sibling-repo paths are skipped`() {
        val body = "`spotlight-server-app/src/main/java/Legacy.java` `plaza-mobile-app/README.md` " +
            "`../plaza-mobile-protos/docs/versions.md` `fiord-server/build.gradle.kts:900` `other-repo/README.md`\n"
        assertMessages(findings(body), "`other-repo/README.md`: file not found")
    }

    @Test
    fun `extra extensions must be plain letters and digits, they end up in a regex`() {
        for (bad in listOf("", ".dart", "d|art", "da.rt", "(x)", "a*")) {
            val e = assertThrows<IllegalArgumentException> { OkfRefChecker(index, "docs", refExtensions = listOf("dart", bad)) }
            assertTrue("ref extension '$bad' must match" in e.message!!, e.message)
        }
        OkfRefChecker(index, "docs", refExtensions = listOf("dart", "arb", "proto3"))
    }

    @Test
    fun `ref_extensions proto makes proto paths references that resolve against the repo`() {
        val protoIndex = RepoIndex(listOf("proto/plaza/mobile/otp/v1/otp.proto", "docs/index.md", "build.gradle.kts")) { 40 }
        val body = "`proto/plaza/mobile/otp/v1/otp.proto` `proto/plaza/mobile/otp/v1/otp.proto:12` `otp/v1/otp.proto` " +
            "`proto/plaza/mobile/otp/v1/gone.proto` `proto/plaza/mobile/otp/v1/otp.proto:41`\n"
        val withProto = OkfRefChecker(protoIndex, "docs", refExtensions = listOf("proto")).check(listOf(doc("guide.md", body)))
        assertEquals(5, withProto.checked)
        assertMessages(
            withProto.findings,
            "`proto/plaza/mobile/otp/v1/otp.proto:41`: line 41 is past the end",
            "`proto/plaza/mobile/otp/v1/gone.proto`: file not found",
        )
        // Without the extension a .proto path is not a reference at all.
        val without = OkfRefChecker(protoIndex, "docs").check(listOf(doc("guide.md", body)))
        assertEquals(RefCheckResult(emptyList(), 0), without)
    }

    @Test
    fun `gitignored paths are skipped, with one batched ignore lookup`() {
        val calls = mutableListOf<Collection<String>>()
        val checker = OkfRefChecker(index, "docs") { paths ->
            calls += paths
            paths.filter { it.startsWith("server/build/") }.toSet()
        }
        val result = checker.check(
            listOf(
                doc("adr/ADR-001-test.md", "`server/build/generated/Gen.java` `server/build/reports/`\n"),
                doc("features/otp-login.md", "`server/gone/Gone.java`\n"),
            ),
        )
        assertMessages(result.findings, "`server/gone/Gone.java`: file not found")
        assertEquals(1, calls.size, "one ignore lookup for all documents")
        assertTrue("server/build/reports/okf-dir-probe" in calls.single(), "directory probed through a child: ${calls.single()}")
    }

    @Test
    fun `fenced blocks and frontmatter are not scanned`() {
        val text = "---\ntype: Guide\ndescription: \"see `Gone.java`\"\n---\n# T\n\n```\n`Gone2.java`\n```\n\n    `indented` is prose\n"
        assertMessages(checker().check(listOf(RefDoc("DEVELOPMENT.md", text))).findings)
    }

    @Test
    fun `opt-out markers skip a line or a block, quoted markers do not`() {
        val body = "`Gone1.java` <!-- okf: ignore-refs -->\n" +
            "<!-- okf: ignore-refs-start -->\n`Gone2.java`\n<!-- okf: ignore-refs-end -->\n" +
            "`Gone3.java` and the marker as code: `<!-- okf: ignore-refs -->`\n" +
            "<!-- okf: ignore-refs-start -->\n`Gone4.java` (unclosed block runs to the end)\n"
        assertMessages(findings(body), "`Gone3.java`: file not found")
    }

    @Test
    fun `amendment paragraphs are checked like any other prose`() {
        assertMessages(findings("**Update 2026-07-05:** moved to `grpcweb/otp/OtpProtoMapper.java`.\n"), "`grpcweb/otp/OtpProtoMapper.java`: file not found")
    }

    @Test
    fun `relative Markdown links resolve against the document`() {
        val body = "[a](../features/otp-login.md#section) [b](../index.md) [c](../features/) [spec](../Plaza%20Notes.md) " +
            "[web](https://example.org/x.md) [mail](mailto:a@b.c) [anchor](#top) [out](../../../../sibling/README.md) " +
            "[broken](../features/gone.md) [root](/docs/index.md)\n"
        assertMessages(findings(body), "link target `../features/gone.md` not found (resolved against the document: docs/features/gone.md)")
    }

    @Test
    fun `a code span used as link text is a label, only the link target is checked`() {
        val body = "[`docs/invite-respond/API_PLAN.md`](../../../plaza-mobile-protos/docs/invite-respond/API_PLAN.md) " +
            "[`Gone.java`](../features/gone.md)\n"
        assertMessages(findings(body), "link target `../features/gone.md` not found")
    }

    @Test
    fun `the same reference twice on one line is reported once`() {
        assertEquals(listOf(4, 5), findings("`Gone.java` and again `Gone.java`\n`Gone.java`\n").map { it.line })
    }

    @Test
    fun `okfCheck reports reference findings as warnings, or as problems in refs strict mode`(@TempDir root: File) {
        root.resolve("index.md").writeText("---\nokf_version: \"0.2\"\n---\n# Docs `Gone0.java`\n")
        root.resolve("adr").mkdirs()
        root.resolve("features").mkdirs()
        root.resolve("guide.md").writeText("---\ntype: Guide\n---\n# G\n\n`Gone.java`\n")
        root.resolve("log.md").writeText("# Log\n\n`Gone2.java`\n")
        root.resolve("notes.md").writeText("# Unmigrated\n\n`Gone3.java`\n")
        root.resolve("adr/index.md").writeText(OkfIndexRenderer.renderAdrIndex(root.resolve("adr")))
        root.resolve("features/index.md").writeText(OkfIndexRenderer.renderFeaturesIndex(root.resolve("features")))
        val now = Instant.parse("2026-09-25T00:00:00Z")

        val lenient = OkfChecker.check(root, strict = false, now = now, refs = checker(), refsStrict = false)
        assertEquals(emptyList<String>(), lenient.problems)
        assertEquals(listOf("guide.md:6: `Gone.java`: file not found"), lenient.warnings.map { it.substringBefore(" in the repo") })
        assertEquals(1, lenient.refsChecked)

        val strict = OkfChecker.check(root, strict = false, now = now, refs = checker(), refsStrict = true)
        assertEquals(listOf("guide.md:6: `Gone.java`: file not found"), strict.problems.map { it.substringBefore(" in the repo") })
        assertEquals(listOf(RefFinding("guide.md", 6, strict.refFindings.single().message)), strict.refFindings)
    }

    @Test
    fun `siblingRefs yields exactly the sibling-repo references check skips, with repo and repo-relative path`() {
        // `../../` from docs/guide.md leaves the repo: skipped by check, so not a sibling reference either.
        val body = "`plaza-mobile-app/docs/index.md` `server/build.gradle.kts` `../spotlight-server-app/src/X.java:12` `../../a/B.kt`\n" +
            "`plaza-mobile-app/{a,b}.yaml` `plaza-mobile-app/lib/`\n`plaza-mobile-app/x.kt` <!-- okf: ignore-refs -->\n" +
            "```\n`plaza-mobile-app/fenced.kt`\n```\n`./plaza-mobile-app/Foo.kt:1-3`\n"
        val docs = listOf(doc("guide.md", body))
        assertEquals(emptyList<RefFinding>(), checker().check(docs).findings)
        assertEquals(
            listOf(
                Triple(4, "plaza-mobile-app", "docs/index.md"),
                Triple(4, "spotlight-server-app", "src/X.java"),
                Triple(5, "plaza-mobile-app", "a.yaml"),
                Triple(5, "plaza-mobile-app", "b.yaml"),
                Triple(5, "plaza-mobile-app", "lib"),
                Triple(10, "plaza-mobile-app", "Foo.kt"),
            ),
            checker().siblingRefs(docs).map { Triple(it.line, it.repo, it.path) },
        )
    }

    @Test
    fun `checkSiblingRefs resolves in the target repo - root, package roots, suffix, lines, gitignored, explicitly relative`() {
        val target = RepoIndex(
            listOf("docs/index.md", "docs/features/x.md", "app/src/main/kotlin/com/x/App.kt", "a/Dup.kt", "b/Dup.kt"),
        ) { if (it == "docs/index.md") 20 else 5 }
        val ignoredProbes = mutableListOf<Collection<String>>()
        val targetChecker = OkfRefChecker(target, "docs") { paths -> ignoredProbes += paths; paths.filter { it.startsWith("build/") }.toSet() }
        val body = "`t/docs/index.md:20` `t/docs/index.md:21` `t/App.kt` `t/com/x/App.kt:1-5` `t/features/x.md` `t/Dup.kt`\n" +
            "`t/Dup.kt:1` `t/gone/Nope.kt` `t/build/gen.kt` `t/docs/` `t/docs/features/` `t/nope/` `t/docs/index.md:3,`\n" +
            // `.` / `..` after the repo name: explicitly relative, so no package-root or suffix fallback (as in check);
            // a path climbing out of the target repo is not looked up.
            "`t/./App.kt` `t/x/../App.kt` `t/./docs/index.md` `t/../x.kt`\n"
        val source = OkfRefChecker(index, "docs", externalRepos = setOf("t"))
        val refs = source.siblingRefs(listOf(doc("guide.md", body)))
        assertEquals(17, refs.size)
        val result = targetChecker.checkSiblingRefs(refs)
        assertEquals(16, result.checked)
        assertEquals(
            listOf(
                "4: `t/docs/index.md:21`: line 21 is past the end of docs/index.md (20 lines)",
                "5: `t/Dup.kt:1`: ambiguous: qualify the path (2 candidates: a/Dup.kt, b/Dup.kt)",
                "5: `t/docs/index.md:3,`: invalid line reference",
                "5: `t/gone/Nope.kt`: file not found in t — fix the path, or opt the line out with `<!-- okf: ignore-refs -->`",
                "5: `t/nope/`: directory not found in t — fix the path, or opt the line out with `<!-- okf: ignore-refs -->`",
                "6: `t/./App.kt`: file not found in t — fix the path, or opt the line out with `<!-- okf: ignore-refs -->`",
                "6: `t/x/../App.kt`: file not found in t — fix the path, or opt the line out with `<!-- okf: ignore-refs -->`",
            ),
            result.findings.map { (ref, message) -> "${ref.line}: $message" }.sorted(),
        )
        assertEquals(
            listOf(listOf("gone/Nope.kt", "build/gen.kt", "nope/okf-dir-probe", "App.kt")),
            ignoredProbes.map { it.toList() },
            "one batched lookup",
        )
    }

    private fun checker(ignored: Set<String> = emptySet()) =
        OkfRefChecker(index, "docs", externalRepos = HUB_EXTERNAL_REPOS) { paths -> paths.filter { it in ignored }.toSet() }

    private fun doc(path: String, body: String) = RefDoc(path, "---\ntype: Guide\n---\n$body")

    private fun findings(body: String, path: String = "adr/ADR-001-test.md"): List<RefFinding> =
        checker().check(listOf(doc(path, body))).findings

    /** Exactly one finding per expected fragment, in line/column order. */
    private fun assertMessages(findings: List<RefFinding>, vararg expected: String) {
        assertEquals(expected.size, findings.size, "findings:\n${findings.joinToString("\n")}")
        expected.zip(findings).forEach { (fragment, finding) -> assertContains(finding.message, fragment) }
    }

    private fun assertContains(actual: String, fragment: String) =
        assertTrue(fragment in actual, "expected <$fragment> in <$actual>")

    private companion object {
        const val MAIN = "server/src/main/java/com/example/plaza"
        const val TEST = "server/src/test/java/com/example/plaza"

        /** A sample hub `external_repos` list (okf.yaml) for these fixtures. */
        val HUB_EXTERNAL_REPOS = setOf(
            "plaza-mobile-app",
            "plaza-mobile-protos",
            "plaza-alpha-protos",
            "plaza-alpha-app",
            "plaza-sigma-api",
            "plaza-beta-api",
            "snow-app-android",
            "spotlight-server-app",
            "fiord-server",
        )
    }
}
