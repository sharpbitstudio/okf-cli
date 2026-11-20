package com.sharpbitstudio.okf.action

import com.sharpbitstudio.okf.launcher.Host
import com.sharpbitstudio.okf.launcher.shell
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * `action/impact-args.sh` (the committed copy, from `okf.repoRoot`) sourced by a real `sh` with an empty `PATH`, as
 * check.sh sources it: the selection table of docs/github-actions.md, one case per row, plus the Dependabot and
 * override cases. The script reads only the `OKF_*` variables action.yml sets; every one of them is set in each run.
 */
class ImpactArgsTest {
    @TempDir
    lateinit var tmp: File

    private val script = File(checkNotNull(System.getProperty("okf.repoRoot")) { "the build passes okf.repoRoot" })
        .resolve("action/impact-args.sh")

    /**
     * The arguments check.sh passes to `okf impact` for the event facts and inputs [facts] (the rest empty, the inputs
     * `auto`): a driver sources the script and prints the three variables it sets, each on its own line.
     */
    private fun args(vararg facts: Pair<String, String>): List<String> {
        val env: Map<String, String?> = UNSET + DEFAULTS + facts.toMap() + ("PATH" to tmp.resolve("empty").apply { mkdirs() }.path)
        val driver = tmp.resolve("driver.sh").apply {
            writeText(". \"\$1\"\nprintf '%s\n' \"\$okf_impact_base_set\" \"\$okf_impact_strict\" \"\$okf_impact_base\"\n")
        }
        val run = Host.run(listOf(Host.sh.path, driver.shell, script.shell), tmp, env).lf()
        assertEquals(0, run.code, run.toString())
        assertEquals("", run.err, run.toString())
        val (baseSet, strict) = run.out.lines()
        // The base is the rest of the output, without the line end printf added: it may hold anything.
        val base = run.out.substringAfter("\n").substringAfter("\n").removeSuffix("\n")
        assertTrue(baseSet == "1" || (baseSet.isEmpty() && base.isEmpty()), "a base is only set when passed: $run")
        assertTrue(strict == "true" || strict == "false", run.toString())
        return (if (baseSet == "1") listOf("--base=$base") else emptyList()) + "--impact-strict=$strict"
    }

    private val sha = "1b2c3d4e5f60718293a4b5c6d7e8f90123456789"

    @Test
    fun `pull_request - the base branch on origin, strict`() {
        assertEquals(
            listOf("--base=origin/main", "--impact-strict=true"),
            args(EVENT to "pull_request", REF to "refs/pull/7/merge", PR_BASE_REF to "main", PR_AUTHOR to "alice", ACTOR to "alice"),
        )
        assertEquals(
            listOf("--base=origin/release/2.x", "--impact-strict=true"),
            args(EVENT to "pull_request", REF to "refs/pull/8/merge", PR_BASE_REF to "release/2.x", PR_AUTHOR to "alice", ACTOR to "bob"),
        )
    }

    @Test
    fun `pull_request - by Dependabot, not strict`() {
        assertEquals(
            listOf("--base=origin/main", "--impact-strict=false"),
            args(
                EVENT to "pull_request", REF to "refs/pull/9/merge", PR_BASE_REF to "main", PR_AUTHOR to "dependabot[bot]",
                ACTOR to "dependabot[bot]",
            ),
        )
    }

    @Test
    fun `merge_group - the queue's base SHA, not strict`() {
        assertEquals(
            listOf("--base=$sha", "--impact-strict=false"),
            args(EVENT to "merge_group", REF to "refs/heads/gh-readonly-queue/main/pr-7-$sha", MERGE_GROUP_BASE_SHA to sha, ACTOR to "alice"),
        )
    }

    @Test
    fun `push to the default branch - the commit it moved from, not strict`() {
        assertEquals(
            listOf("--base=$sha", "--impact-strict=false"),
            args(EVENT to "push", REF to "refs/heads/main", DEFAULT_BRANCH to "main", BEFORE to sha, ACTOR to "alice"),
        )
        assertEquals(
            listOf("--base=$sha", "--impact-strict=false"),
            args(EVENT to "push", REF to "refs/heads/trunk", DEFAULT_BRANCH to "trunk", BEFORE to sha, ACTOR to "alice"),
        )
    }

    @Test
    fun `push to the default branch - a new branch passes the all-zero before, which okf impact skips`() {
        val zeros = "0".repeat(40)
        assertEquals(
            listOf("--base=$zeros", "--impact-strict=false"),
            args(EVENT to "push", REF to "refs/heads/main", DEFAULT_BRANCH to "main", BEFORE to zeros, ACTOR to "alice"),
        )
    }

    @Test
    fun `push to the default branch - an empty before is passed as an empty base, which okf impact skips`() {
        assertEquals(
            listOf("--base=", "--impact-strict=false"),
            args(EVENT to "push", REF to "refs/heads/main", DEFAULT_BRANCH to "main", BEFORE to "", ACTOR to "alice"),
        )
    }

    @Test
    fun `an empty default branch means main`() {
        assertEquals(
            listOf("--base=$sha", "--impact-strict=false"),
            args(EVENT to "push", REF to "refs/heads/main", DEFAULT_BRANCH to "", BEFORE to sha, ACTOR to "alice"),
        )
        assertEquals(
            listOf("--impact-strict=true"),
            args(EVENT to "push", REF to "refs/heads/trunk", DEFAULT_BRANCH to "", BEFORE to sha, ACTOR to "alice"),
        )
    }

    @Test
    fun `push or workflow_dispatch on another branch - okf impact's own base, strict`() {
        assertEquals(
            listOf("--impact-strict=true"),
            args(EVENT to "push", REF to "refs/heads/feature/x", DEFAULT_BRANCH to "main", BEFORE to sha, ACTOR to "alice"),
        )
        // The default branch of another repository is not this one's: main is just another branch here.
        assertEquals(
            listOf("--impact-strict=true"),
            args(EVENT to "push", REF to "refs/heads/main", DEFAULT_BRANCH to "trunk", BEFORE to sha, ACTOR to "alice"),
        )
        assertEquals(
            listOf("--impact-strict=true"),
            args(EVENT to "workflow_dispatch", REF to "refs/heads/feature/x", DEFAULT_BRANCH to "main", ACTOR to "alice"),
        )
    }

    @Test
    fun `push to a merge queue branch - okf impact's own base, not strict`() {
        assertEquals(
            listOf("--impact-strict=false"),
            args(
                EVENT to "push", REF to "refs/heads/gh-readonly-queue/main/pr-7-$sha", DEFAULT_BRANCH to "main", BEFORE to sha,
                ACTOR to "github-merge-queue[bot]",
            ),
        )
        // A manual run on such a branch is a run on another branch.
        assertEquals(
            listOf("--impact-strict=true"),
            args(EVENT to "workflow_dispatch", REF to "refs/heads/gh-readonly-queue/main/pr-7-$sha", DEFAULT_BRANCH to "main", ACTOR to "alice"),
        )
    }

    @Test
    fun `the base is one value whatever it holds`() {
        val pr = arrayOf(EVENT to "pull_request", REF to "refs/pull/7/merge", PR_BASE_REF to "main", PR_AUTHOR to "alice", ACTOR to "alice")
        for (base in listOf("origin/a branch; echo x", "\$HOME `id` *", "a\"b'c")) {
            assertEquals(listOf("--base=$base", "--impact-strict=true"), args(*pr, INPUT_BASE to base), base)
        }
    }

    @Test
    fun `push by Dependabot - not strict`() {
        assertEquals(
            listOf("--impact-strict=false"),
            args(
                EVENT to "push", REF to "refs/heads/dependabot/gradle/junit-6.1.0", DEFAULT_BRANCH to "main", BEFORE to sha,
                ACTOR to "dependabot[bot]",
            ),
        )
    }

    @Test
    fun `a human's branch named dependabot-x stays strict - the branch name never decides`() {
        assertEquals(
            listOf("--impact-strict=true"),
            args(EVENT to "push", REF to "refs/heads/dependabot/x", DEFAULT_BRANCH to "main", BEFORE to sha, ACTOR to "mallory"),
        )
        assertEquals(
            listOf("--base=origin/main", "--impact-strict=true"),
            args(EVENT to "pull_request", REF to "refs/pull/3/merge", PR_BASE_REF to "main", PR_AUTHOR to "mallory", ACTOR to "mallory"),
        )
    }

    @Test
    fun `anything else - okf impact's own base, not strict`() {
        val cases = listOf(
            "tag push" to arrayOf(EVENT to "push", REF to "refs/tags/v1.2.3", DEFAULT_BRANCH to "main", BEFORE to sha, ACTOR to "alice"),
            "workflow_dispatch on the default branch" to arrayOf(EVENT to "workflow_dispatch", REF to "refs/heads/main", DEFAULT_BRANCH to "main", ACTOR to "alice"),
            "workflow_dispatch on a tag" to arrayOf(EVENT to "workflow_dispatch", REF to "refs/tags/v1.2.3", DEFAULT_BRANCH to "main", ACTOR to "alice"),
            "schedule" to arrayOf(EVENT to "schedule", REF to "refs/heads/main", DEFAULT_BRANCH to "main", ACTOR to "alice"),
            "pull_request_target" to arrayOf(
                EVENT to "pull_request_target", REF to "refs/heads/main", DEFAULT_BRANCH to "main", PR_BASE_REF to "main",
                PR_AUTHOR to "alice", ACTOR to "alice",
            ),
            "no event" to arrayOf<Pair<String, String>>(),
        )
        for ((name, facts) in cases) {
            assertEquals(listOf("--impact-strict=false"), args(*facts), name)
        }
    }

    @Test
    fun `an explicit base replaces the table's base column, an empty or blank one keeps the table`() {
        val pr = arrayOf(EVENT to "pull_request", REF to "refs/pull/7/merge", PR_BASE_REF to "main", PR_AUTHOR to "alice", ACTOR to "alice")
        assertEquals(listOf("--base=origin/develop", "--impact-strict=true"), args(*pr, INPUT_BASE to "origin/develop"))
        assertEquals(listOf("--base=origin/main", "--impact-strict=true"), args(*pr, INPUT_BASE to ""))
        assertEquals(listOf("--base=origin/main", "--impact-strict=true"), args(*pr, INPUT_BASE to "   "))
        assertEquals(listOf("--base= x", "--impact-strict=true"), args(*pr, INPUT_BASE to " x"))
        assertEquals(listOf("--base=origin/main", "--impact-strict=true"), args(*pr, INPUT_BASE to "auto"))
        // A row that passes no base gets the explicit one.
        assertEquals(
            listOf("--base=$sha", "--impact-strict=false"),
            args(EVENT to "schedule", REF to "refs/heads/main", DEFAULT_BRANCH to "main", INPUT_BASE to sha),
        )
        assertEquals(
            listOf("--base=origin/main", "--impact-strict=true"),
            args(EVENT to "push", REF to "refs/heads/feature/x", DEFAULT_BRANCH to "main", ACTOR to "alice", INPUT_BASE to "origin/main"),
        )
    }

    @Test
    fun `an explicit impact-strict replaces the table's strictness column`() {
        assertEquals(
            listOf("--base=$sha", "--impact-strict=true"),
            args(EVENT to "push", REF to "refs/heads/main", DEFAULT_BRANCH to "main", BEFORE to sha, INPUT_IMPACT_STRICT to "true"),
        )
        assertEquals(
            listOf("--base=origin/main", "--impact-strict=false"),
            args(
                EVENT to "pull_request", REF to "refs/pull/7/merge", PR_BASE_REF to "main", PR_AUTHOR to "alice", ACTOR to "alice",
                INPUT_IMPACT_STRICT to "false",
            ),
        )
        assertEquals(
            listOf("--base=origin/main", "--impact-strict=true"),
            args(
                EVENT to "pull_request", REF to "refs/pull/9/merge", PR_BASE_REF to "main", PR_AUTHOR to "dependabot[bot]",
                INPUT_IMPACT_STRICT to "true",
            ),
        )
    }

    companion object {
        const val EVENT = "OKF_EVENT"
        const val REF = "OKF_REF"
        const val DEFAULT_BRANCH = "OKF_DEFAULT_BRANCH"
        const val BEFORE = "OKF_BEFORE"
        const val MERGE_GROUP_BASE_SHA = "OKF_MERGE_GROUP_BASE_SHA"
        const val PR_BASE_REF = "OKF_PR_BASE_REF"
        const val PR_AUTHOR = "OKF_PR_AUTHOR"
        const val ACTOR = "OKF_ACTOR"
        const val INPUT_BASE = "OKF_INPUT_BASE"
        const val INPUT_IMPACT_STRICT = "OKF_INPUT_IMPACT_STRICT"

        /** Every variable the script reads, empty: what action.yml passes for a fact the event does not have. */
        private val UNSET: Map<String, String?> =
            listOf(EVENT, REF, DEFAULT_BRANCH, BEFORE, MERGE_GROUP_BASE_SHA, PR_BASE_REF, PR_AUTHOR, ACTOR).associateWith { "" }

        private val DEFAULTS: Map<String, String?> = mapOf(INPUT_BASE to "auto", INPUT_IMPACT_STRICT to "auto")
    }
}
