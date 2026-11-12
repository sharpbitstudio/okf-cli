package okf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OkfImpactTest {
    private val docs = listOf(
        doc("adr/ADR-026-expiry.md", "type: ADR\nstatus: stable\ncode_paths: [\"$OTP/**\"]"),
        doc("adr/ADR-010-otp.md", "type: ADR\nstatus: deprecated\ncode_paths: [\"$OTP/**\"]"),
        doc("features/otp-login.md", "type: Feature Record\ncode_paths: [\"$OTP/api/**\", \"$MIGRATIONS/otp/*.sql\"]"),
        doc("features/evening-digest.md", "type: Feature Record\ncode_paths: []"),
        doc("RUNTIME.md", "type: Guide"),
        doc("adr/README.md", "type: Convention"),
        doc("features/README.md", "type: Convention"),
    )

    @Test
    fun `doc whose own file changed is updated`() {
        val result = evaluate(listOf("$OTP/api/OtpService.java", "docs/features/otp-login.md"))
        assertEquals(listOf("[updated] docs/features/otp-login.md <- $OTP/api/OtpService.java"), lines(result, "otp-login"))
        assertEquals(1, result.findings.size, "only ADR-026 is not updated: ${result.findings}")
    }

    @Test
    fun `impacted doc without update or trailer is NOT UPDATED and a finding`() {
        val result = evaluate(listOf("$OTP/internal/jobs/OtpExpiryJob.java"))
        assertEquals(listOf("[NOT UPDATED] docs/adr/ADR-026-expiry.md <- $OTP/internal/jobs/OtpExpiryJob.java"), lines(result))
        val finding = result.findings.single()
        assertEquals("docs/adr/ADR-026-expiry.md", finding.doc)
        assertContains(finding.message, "`Docs-Impact: ADR-026 — <why it stays valid>`")
    }

    @Test
    fun `trailer naming the doc acknowledges it with the short sha`() {
        val result = evaluate(
            listOf("$OTP/api/OtpService.java"),
            commit("1234567890abcdef", "fix: otp\n\nDocs-Impact: ADR-026, features/otp-login — rename only"),
        )
        assertEquals(
            listOf(
                "[acknowledged: 1234567] docs/adr/ADR-026-expiry.md <- $OTP/api/OtpService.java",
                "[acknowledged: 1234567] docs/features/otp-login.md <- $OTP/api/OtpService.java",
            ),
            lines(result),
        )
        assertEquals(emptyList<ImpactFinding>(), result.findings)
    }

    @Test
    fun `none with a reason acknowledges every impacted doc, the newest commit wins`() {
        val result = evaluate(
            listOf("$OTP/api/OtpService.java"),
            commit("bbbbbbbbbb", "chore\n\ndocs-impact: none -- formatting only"),
            commit("aaaaaaaaaa", "older\n\nDocs-Impact: none — older"),
        )
        assertTrue(result.entries.all { it.state == ImpactState.ACKNOWLEDGED && it.commit == "bbbbbbbbbb" }, result.entries.toString())
        assertEquals(emptyList<ImpactFinding>(), result.findings)
    }

    @Test
    fun `the newest valid trailer that applies wins, a named ID has no precedence over none`() {
        val result = evaluate(
            listOf("$OTP/api/OtpService.java"),
            commit("bbbbbbbbbb", "newer\n\nDocs-Impact: none — x"),
            commit("aaaaaaaaaa", "older\n\nDocs-Impact: ADR-026 — y"),
        )
        assertEquals("bbbbbbbbbb", result.entries.single { "ADR-026" in it.doc }.commit)
        val reversed = evaluate(
            listOf("$OTP/api/OtpService.java"),
            commit("bbbbbbbbbb", "newer\n\nDocs-Impact: ADR-026 — y"),
            commit("aaaaaaaaaa", "older\n\nDocs-Impact: none — x"),
        )
        assertEquals("bbbbbbbbbb", reversed.entries.single { "ADR-026" in it.doc }.commit)
        assertEquals("aaaaaaaaaa", reversed.entries.single { "otp-login" in it.doc }.commit)
    }

    @Test
    fun `none without a reason is a finding`() {
        val result = evaluate(listOf("README.md"), commit("cccccccccc", "x\n\nDocs-Impact: none"))
        assertContains(result.findings.single().message, "commit ccccccc: `Docs-Impact: none`: `none` requires a reason")
        assertEquals(null, result.findings.single().doc)
    }

    @Test
    fun `an invalid none trailer acknowledges nothing`() {
        listOf("x\n\nDocs-Impact: none", "x\n\nDocs-Impact: none, ADR-026 — both").forEach { message ->
            val result = evaluate(listOf("$OTP/internal/jobs/OtpExpiryJob.java"), commit("cccccccccc", message))
            assertEquals(listOf("[NOT UPDATED] docs/adr/ADR-026-expiry.md <- $OTP/internal/jobs/OtpExpiryJob.java"), lines(result), message)
            assertEquals(listOf(null, "docs/adr/ADR-026-expiry.md"), result.findings.map { it.doc }, result.findings.toString())
        }
    }

    @Test
    fun `unknown, ambiguous and mixed trailer IDs are findings, known IDs still count`() {
        val result = evaluate(
            listOf("$OTP/api/OtpService.java"),
            commit("dddddddddd", "move\n\nDocs-Impact: ADR-999, README, features/nope, otp-login - moved\nDocs-Impact: none, ADR-026 — both"),
        )
        val messages = result.findings.filter { it.doc == null }.map { it.message }
        assertContains(messages[0], "unknown document ID 'ADR-999'")
        assertContains(messages[1], "document ID 'README' is ambiguous (adr/README.md, features/README.md)")
        assertContains(messages[2], "unknown document ID 'features/nope'")
        assertContains(messages[3], "combines `none` with document IDs")
        assertEquals(4, messages.size, messages.toString())
        assertEquals(ImpactState.ACKNOWLEDGED, result.entries.single { "otp-login" in it.doc }.state)
        assertEquals(ImpactState.NOT_UPDATED, result.entries.single { "ADR-026" in it.doc }.state, "the mixed trailer acknowledges nothing")
    }

    @Test
    fun `a trailer covers a file only when its last change is the trailer commit or an ancestor of it`() {
        listOf("ADR-026 — y", "none — y").forEach { value ->
            // JOB changed in commit 1 (with the trailer) and again in commit 2: the same path, but a later change.
            val sameFileTwice = evaluate(
                listOf(JOB),
                listOf(commit(C2, "more code"), commit(C1, "code\n\nDocs-Impact: $value")),
                touched = mapOf(JOB to C2),
            )
            assertEquals(ImpactState.NOT_UPDATED, sameFileTwice.entries.single().state, value)
            val finding = sameFileTwice.findings.single()
            assertContains(finding.message, "acknowledged by commit 1111111 only for the changes it could see")
            assertContains(finding.message, "changed after it or outside its history ($JOB)")

            val ackLast = evaluate(
                listOf(JOB, SAFETY_NET),
                listOf(commit(C2, "more code\n\nDocs-Impact: $value"), commit(C1, "code")),
                touched = mapOf(JOB to C1, SAFETY_NET to C2),
            )
            assertEquals(listOf("[acknowledged: 2222222] $ADR <- $JOB, $SAFETY_NET"), lines(ackLast), value)
            assertEquals(emptyList<ImpactFinding>(), ackLast.findings)
        }
    }

    @Test
    fun `a merged-in side commit is covered only by a trailer that descends from it`() {
        // K1 (trailer) -> MERGE (K1 + side commit M) -> K3; M does not descend from K1.
        val parents = mapOf(K3 to listOf(MERGE), MERGE to listOf(K1, M))
        val history = listOf(commit(K3, "more code"), commit(MERGE, "merge"), commit(M, "side"), commit(K1, "code\n\nDocs-Impact: ADR-026 — y"))
        val sideOnly = evaluate(listOf(SAFETY_NET), history, touched = mapOf(SAFETY_NET to M), parents = parents)
        assertContains(sideOnly.findings.single().message, "outside its history ($SAFETY_NET)")
        val sideAndLater = evaluate(listOf(JOB, SAFETY_NET), history, touched = mapOf(JOB to K3, SAFETY_NET to M), parents = parents)
        assertEquals(ImpactState.NOT_UPDATED, sideAndLater.entries.single().state)
        assertContains(sideAndLater.findings.single().message, "($JOB, $SAFETY_NET)")

        val ackAfterMerge = listOf(commit(K3, "more code\n\nDocs-Impact: ADR-026 — y"), commit(MERGE, "merge"), commit(M, "side"), commit(K1, "code"))
        val covered = evaluate(listOf(JOB, SAFETY_NET), ackAfterMerge, touched = mapOf(JOB to K1, SAFETY_NET to M), parents = parents)
        assertEquals(listOf("[acknowledged: ${K3.take(7)}] $ADR <- $JOB, $SAFETY_NET"), lines(covered))
    }

    @Test
    fun `after merging main a file stays its branch commit's, a merge-only edit is the merge's`() {
        // K1 (trailer) -> MERGE (K1 + a main commit outside the range). The touchers are the non-merge ones
        // (OkfGit.commitsTouching, see OkfGitTest), falling back to the merge only when no other commit touched F.
        val parents = mapOf(MERGE to listOf(K1))
        val history = listOf(commit(MERGE, "merge origin/main"), commit(K1, "code\n\nDocs-Impact: ADR-026 — y"))
        val bothSides = evaluate(listOf(JOB), history, touched = mapOf(JOB to K1), parents = parents)
        assertEquals(listOf("[acknowledged: ${K1.take(7)}] $ADR <- $JOB"), lines(bothSides))
        val mergeOnly = evaluate(listOf(JOB, SAFETY_NET), history, touched = mapOf(JOB to K1, SAFETY_NET to MERGE), parents = parents)
        assertEquals(ImpactState.NOT_UPDATED, mergeOnly.entries.single().state)
        assertContains(mergeOnly.findings.single().message, "outside its history ($SAFETY_NET)")
    }

    @Test
    fun `every parallel toucher of a file must be covered`() {
        // K1 (trailer) and S1 both change JOB on parallel lines; MERGE joins them, K3 follows.
        val parents = mapOf(K3 to listOf(MERGE), MERGE to listOf(K1, M))
        listOf(listOf(K1, M), listOf(M, K1)).forEach { order ->
            val ackInK1 = listOf(commit(K3, "more"), commit(MERGE, "merge"), commit(M, "side"), commit(K1, "code\n\nDocs-Impact: ADR-026 — y"))
            val result = evaluate(listOf(JOB), ackInK1, parents = parents, touchers = mapOf(JOB to order))
            assertEquals(ImpactState.NOT_UPDATED, result.entries.single().state, order.toString())
            assertContains(result.findings.single().message, "outside its history ($JOB)")

            listOf(MERGE, K3).forEach { ackAt ->
                val history = listOf(K3, MERGE, M, K1).map { sha -> commit(sha, if (sha == ackAt) "x\n\nDocs-Impact: ADR-026 — y" else "x") }
                val covered = evaluate(listOf(JOB), history, parents = parents, touchers = mapOf(JOB to order))
                assertEquals(listOf("[acknowledged: ${ackAt.take(7)}] $ADR <- $JOB"), lines(covered), "$order, ack in $ackAt")
            }
        }
    }

    @Test
    fun `a doc update covers only what its commit could see, one in the work tree covers everything`() {
        val changed = listOf(ADR, JOB, SAFETY_NET)
        val commits = listOf(commit(C3, "more code"), commit(C2, "code"), commit(C1, "docs + code"))

        val early = evaluate(changed, commits, touched = mapOf(ADR to C1, JOB to C1, SAFETY_NET to C3))
        assertEquals(ImpactState.NOT_UPDATED, early.entries.single().state)
        assertContains(
            early.findings.single().message,
            "updated in commit 1111111 only for the changes it could see, but code it covers changed after it or outside its history ($SAFETY_NET)",
        )

        val last = evaluate(changed, commits, touched = mapOf(ADR to C3, JOB to C1, SAFETY_NET to C3))
        assertEquals(listOf("[updated] $ADR <- $JOB, $SAFETY_NET"), lines(last))
        assertEquals(emptyList<ImpactFinding>(), last.findings)

        val dirty = evaluate(changed, commits, uncommitted = setOf(ADR, SAFETY_NET), touched = mapOf(ADR to C1, JOB to C1, SAFETY_NET to C3))
        assertEquals(listOf("[updated] $ADR <- $JOB, $SAFETY_NET"), lines(dirty))

        val noTouchingCommit = evaluate(changed, commits, touched = mapOf(JOB to C1, SAFETY_NET to C3))
        assertEquals(listOf("[updated] $ADR <- $JOB, $SAFETY_NET"), lines(noTouchingCommit), "a changed doc without a touching commit is a work-tree update")
    }

    @Test
    fun `a doc changed in a commit but reverted in the work tree is not updated`() {
        val result = evaluate(listOf(JOB), listOf(commit(C1, "docs + code")), uncommitted = setOf(ADR), touched = mapOf(ADR to C1, JOB to C1))
        assertEquals(ImpactState.NOT_UPDATED, result.entries.single().state)
        assertContains(result.findings.single().message, "not updated although code it covers changed")
    }

    @Test
    fun `the newer of doc update and trailer decides`() {
        val changed = listOf(ADR, JOB)
        val ackNewer = evaluate(
            changed,
            listOf(commit(C2, "code\n\nDocs-Impact: ADR-026 — y"), commit(C1, "docs")),
            touched = mapOf(ADR to C1, JOB to C2),
        )
        assertEquals(listOf("[acknowledged: 2222222] $ADR <- $JOB"), lines(ackNewer))
        val updateNewer = evaluate(
            changed,
            listOf(commit(C2, "docs"), commit(C1, "code\n\nDocs-Impact: ADR-026 — y")),
            touched = mapOf(ADR to C2, JOB to C1),
        )
        assertEquals(listOf("[updated] $ADR <- $JOB"), lines(updateNewer))
        val staleNewest = evaluate(
            changed,
            listOf(commit(C3, "code"), commit(C2, "docs"), commit(C1, "code\n\nDocs-Impact: ADR-026 — y")),
            touched = mapOf(ADR to C2, JOB to C3),
        )
        assertContains(staleNewest.findings.single().message, "updated in commit 2222222 only for the changes it could see")
        val sameCommit = evaluate(changed, listOf(commit(C1, "docs + code\n\nDocs-Impact: ADR-026 — y")), touched = mapOf(ADR to C1, JOB to C1))
        assertEquals(listOf("[updated] $ADR <- $JOB"), lines(sameCommit), "update and trailer in the same commit: updated")
        // Parallel histories (neither is an ancestor of the other): the --topo-order rank decides.
        val parallel = evaluate(
            changed,
            listOf(commit(MERGE, "merge"), commit(K1, "code\n\nDocs-Impact: ADR-026 — y"), commit(M, "docs")),
            touched = mapOf(ADR to M, JOB to K1),
            parents = mapOf(MERGE to listOf(K1, M)),
        )
        assertEquals(listOf("[acknowledged: ${K1.take(7)}] $ADR <- $JOB"), lines(parallel))
    }

    @Test
    fun `every applicable update and trailer can cover a file, the newest names the entry`() {
        // Parallel: the doc update in M covers M's file, the trailer in K1 covers K1's file; MERGE sees both.
        val history = listOf(commit(MERGE, "merge"), commit(K1, "code\n\nDocs-Impact: ADR-026 — y"), commit(M, "docs + code"))
        val parents = mapOf(MERGE to listOf(K1, M))
        val both = evaluate(listOf(ADR, JOB, SAFETY_NET), history, touched = mapOf(ADR to M, JOB to K1, SAFETY_NET to M), parents = parents)
        assertEquals(listOf("[acknowledged: ${K1.take(7)}] $ADR <- $JOB, $SAFETY_NET"), lines(both))
        assertEquals(emptyList<ImpactFinding>(), both.findings)

        val third = evaluate(
            listOf(ADR, JOB, SAFETY_NET, LISTENER),
            history,
            touched = mapOf(ADR to M, JOB to K1, SAFETY_NET to M, LISTENER to MERGE),
            parents = parents,
        )
        assertEquals(ImpactState.NOT_UPDATED, third.entries.single().state)
        val message = third.findings.single().message
        assertContains(message, "acknowledged by commit ${K1.take(7)} only for the changes it could see")
        assertContains(message, "outside its history ($LISTENER)")
    }

    @Test
    fun `uncommitted and untracked changes are never covered by a trailer`() {
        val untracked = "$OTP/internal/jobs/NewJob.java"
        val result = evaluate(
            listOf(JOB, untracked),
            listOf(commit(C1, "code\n\nDocs-Impact: ADR-026 — y")),
            uncommitted = setOf(JOB, untracked),
            touched = mapOf(JOB to C1),
        )
        assertEquals(ImpactState.NOT_UPDATED, result.entries.single().state)
        assertContains(result.findings.single().message, "($untracked, $JOB)")
    }

    @Test
    fun `document IDs accept ADR numbers, bundle paths with or without docs prefix and extension, unique names`() {
        val paths = docs.map { it.path }.toSet()
        listOf("ADR-026", "adr/ADR-026-expiry", "docs/adr/ADR-026-expiry.md", "ADR-026-expiry").forEach {
            assertEquals(OkfImpact.IdResolution.Doc("adr/ADR-026-expiry.md"), OkfImpact.resolveId(it, paths), it)
        }
        assertEquals(OkfImpact.IdResolution.Doc("RUNTIME.md"), OkfImpact.resolveId("RUNTIME", paths))
        assertEquals(OkfImpact.IdResolution.Doc("features/otp-login.md"), OkfImpact.resolveId("otp-login", paths))
        assertEquals(OkfImpact.IdResolution.Unknown, OkfImpact.resolveId("runtime", paths), "IDs are case-sensitive")
        assertEquals(OkfImpact.IdResolution.Unknown, OkfImpact.resolveId("adr/otp-login", paths))
    }

    @Test
    fun `deprecated docs and empty code_paths do not take part`() {
        val result = evaluate(listOf("$OTP/api/OtpService.java"))
        assertTrue(result.entries.none { "ADR-010" in it.doc || "evening-digest" in it.doc }, result.entries.toString())
        assertEquals(2, result.documents)
        assertEquals(1, result.deprecatedSkipped)
    }

    @Test
    fun `changes under docs are not code, but a doc change marks its doc updated`() {
        val result = evaluate(listOf("docs/adr/ADR-026-expiry.md", "docs/RUNTIME.md"))
        assertEquals(emptyList<String>(), result.changedCode)
        assertEquals(emptyList<ImpactEntry>(), result.entries)
        assertEquals(emptyList<ImpactFinding>(), result.findings)
    }

    @Test
    fun `entry lists at most three files, then the remainder count`() {
        val files = (1..5).map { "$OTP/api/F$it.java" }
        val entry = evaluate(files).entries.single { "ADR-026" in it.doc }
        assertEquals(5, entry.files.size)
        assertEquals(
            "[NOT UPDATED] docs/adr/ADR-026-expiry.md <- $OTP/api/F1.java, $OTP/api/F2.java, $OTP/api/F3.java (+2 more)",
            entry.line(),
        )
    }

    @Test
    fun `globs of one doc are combined, unrelated changes impact nothing`() {
        val result = evaluate(listOf("$MIGRATIONS/otp/V1__otp.sql", "$MIGRATIONS/sport/V1__sport.sql", "build.gradle.kts"))
        assertEquals(listOf("[NOT UPDATED] docs/features/otp-login.md <- $MIGRATIONS/otp/V1__otp.sql"), lines(result))
        assertEquals(3, result.changedCode.size)
    }

    @Test
    fun `trailers are read from the last paragraph, case-insensitive key, CRLF tolerated`() {
        assertEquals(
            listOf("ADR-026 — a", "none – b"),
            OkfImpact.trailers(
                "subject\r\n\r\nbody\r\nDocs-Impact: none — earlier paragraph\r\n\r\n" +
                    "Signed-off-by: A <a@b>\r\nDocs-Impact: ADR-026 — a\r\nDOCS-IMPACT:none – b\r\n\r\n",
            ),
        )
    }

    @Test
    fun `only a block made of trailer lines counts, a subject may precede it without a blank line`() {
        assertEquals(listOf("none — x"), OkfImpact.trailers("fix: otp\nDocs-Impact: none — x"))
        assertEquals(emptyList<String>(), OkfImpact.trailers("Docs-Impact: none — the subject is no trailer"))
        assertEquals(emptyList<String>(), OkfImpact.trailers("subject\n\nProse, then\nDocs-Impact: none — x"))
        assertEquals(listOf("ADR-026, RUNTIME — long reason"), OkfImpact.trailers("s\n\nDocs-Impact: ADR-026,\n  RUNTIME — long\n\treason"))
    }

    @Test
    fun `a fenced example in the body does not acknowledge, the trailer block does`() {
        val body = "docs: explain\n\nExample:\n\n```\nDocs-Impact: none — example\n```"
        assertEquals(emptyList<String>(), OkfImpact.trailers(body))
        assertEquals(emptyList<String>(), OkfImpact.trailers("$body\n"))
        val job = listOf("$OTP/internal/jobs/OtpExpiryJob.java")
        assertEquals(ImpactState.NOT_UPDATED, evaluate(job, commit("eeeeeeeeee", body)).entries.single().state)
        val acknowledged = evaluate(job, commit("eeeeeeeeee", "$body\n\nDocs-Impact: ADR-026 — rename only")).entries.single()
        assertEquals(ImpactState.ACKNOWLEDGED, acknowledged.state)
    }

    @Test
    fun `a fence with a blank line and an indented closing fence is no trailer block`() {
        val body = "docs: explain\n\nExample:\n```\n\nDocs-Impact: none — example\n  ```"
        assertEquals(emptyList<String>(), OkfImpact.trailers(body))
        assertEquals(emptyList<String>(), OkfImpact.trailers("$body\n"))
        assertEquals(emptyList<String>(), OkfImpact.trailers(body.replace("```", "~~~")))
        assertEquals(listOf("ADR-026 — y"), OkfImpact.trailers("$body\n\nDocs-Impact: ADR-026 — y"))
        val job = listOf("$OTP/internal/jobs/OtpExpiryJob.java")
        assertEquals(ImpactState.NOT_UPDATED, evaluate(job, commit("eeeeeeeeee", body)).entries.single().state)
    }

    /** No path has a touching commit, so every file counts as covered (as if the trailer sat in the last commit). */
    private fun evaluate(changed: List<String>, vararg commits: ImpactCommit) = evaluate(changed, commits.toList())

    /**
     * [touched] = the newest commit that touched each path (enough in a linear history, where older
     * touchers are its ancestors), [touchers] = all touchers of a path (parallel histories); [parents] =
     * a fake commit DAG (default: [commits] as a linear history, each commit's parent the next older one).
     */
    private fun evaluate(
        changed: List<String>,
        commits: List<ImpactCommit>,
        uncommitted: Set<String> = emptySet(),
        touched: Map<String, String> = emptyMap(),
        parents: Map<String, List<String>> = commits.zipWithNext { newer, older -> newer.sha to listOf(older.sha) }.toMap(),
        touchers: Map<String, List<String>> = emptyMap(),
    ): ImpactResult {
        fun isAncestorOrSelf(a: String, b: String): Boolean = a == b || parents[b].orEmpty().any { isAncestorOrSelf(a, it) }
        val touchersOf = { path: String -> touchers[path] ?: listOfNotNull(touched[path]) }
        return OkfImpact.evaluate(docs, "docs", changed, commits, uncommitted, touchersOf, ::isAncestorOrSelf)
    }

    private fun lines(result: ImpactResult, filter: String = "") = result.entries.filter { filter in it.doc }.map(ImpactEntry::line)

    private fun doc(path: String, frontmatter: String) = RefDoc(path, "---\n$frontmatter\n---\n# Doc\n")

    private fun commit(sha: String, message: String) = ImpactCommit(sha, message)

    private fun assertContains(actual: String, fragment: String) =
        assertTrue(fragment in actual, "expected <$fragment> in <$actual>")

    private companion object {
        const val OTP = "server/src/main/java/com/example/plaza/otp"
        const val MIGRATIONS = "server/src/main/resources/db/migration"
        const val ADR = "docs/adr/ADR-026-expiry.md"
        const val JOB = "$OTP/internal/jobs/OtpExpiryJob.java"
        const val SAFETY_NET = "$OTP/internal/jobs/OtpExpirySafetyNetJob.java"
        const val LISTENER = "$OTP/internal/jobs/OtpExpirySchedulingListener.java"
        const val C1 = "1111111111"
        const val C2 = "2222222222"
        const val C3 = "3333333333"
        const val K1 = "aaaa111111"
        const val M = "bbbb222222"
        const val MERGE = "cccc333333"
        const val K3 = "dddd444444"
    }
}
