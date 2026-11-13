package com.sharpbitstudio.okf

import java.io.File

/**
 * One repository of the federation (the hub or a satellite) as `okf xcheck` reads it: its work tree and `okf.yaml`.
 * Everything comes from the checkout; nothing is fetched.
 */
class FederatedRepo(val dir: File, val config: OkfConfig) {
    val name: String get() = config.repo
    val bundle: File = config.bundleDir(dir)

    /**
     * The documents `okf check` validates and scans for references: non-reserved Markdown files of the bundle with
     * frontmatter, by bundle-relative path.
     */
    internal val docs: List<Pair<RefDoc, Frontmatter.Present>> by lazy {
        bundle.walkTopDown()
            .filter { it.isFile && it.extension == "md" && it.name !in OkfFrontmatter.RESERVED }
            .map { it.relativeTo(bundle).invariantSeparatorsPath to it.readText(Charsets.UTF_8) }
            .sortedBy { it.first }
            .mapNotNull { (path, text) -> (OkfFrontmatter.parse(text) as? Frontmatter.Present)?.let { RefDoc(path, text) to it } }
            .toList()
    }

    /** Reference checker over the repo's files (`git ls-files`), as `okf check` builds it; created on first use. */
    internal val refs: OkfRefChecker by lazy {
        val index = RepoIndex.fromDisk(dir, OkfGit.listFiles(dir), config.sourceRoots)
        OkfRefChecker(index, config.docsRoot, config.externalRepos, config.refExtensions) { OkfGit.ignored(dir, it) }
    }

    /** `<repo>/<docs_root>/<bundle-relative path>`: how findings name a document. */
    fun docLocation(path: String): String = "$name/${config.docsRoot}/$path"
}

/** The checks of `okf xcheck`, in report order; [title] heads the check's section of the Markdown report. */
enum class XcheckKind(val title: String) {
    SETUP("Setup"),
    REFS("(a) Cross-repo references"),
    HUB("(b) `hub:` of Satellite Notes"),
    REPOS("(c) `repos:` ↔ Satellite Notes"),
    DRIFT("(d) Drift between Satellite Notes and hub records (warnings)"),
    INDEX_URL("(e) Satellite index URLs"),
}

/** One `okf xcheck` finding at [location] (`<repo>/<repo-relative path>:<line>`, or `<repo>` for a repo-wide one). */
data class XcheckFinding(val kind: XcheckKind, val location: String, val message: String) {
    override fun toString() = "$location: $message"
}

/** Outcome of [OkfXcheck.check]. */
data class XcheckResult(
    /** Failures: (a), (b), (c), (e) and shallow clones ([XcheckKind.SETUP]). */
    val problems: List<XcheckFinding>,
    /** Drift warnings (d). */
    val warnings: List<XcheckFinding>,
    /** Repositories checked: the hub and its satellites. */
    val repos: Int,
    /** Code references into federated repositories that were resolved there (a). */
    val refsChecked: Int,
    /** Code references into repositories outside the federation (`external_repos` only): counted, not checked. */
    val refsUnchecked: Int,
    /** Satellite Notes whose `hub:` names an existing hub Feature Record. */
    val notesLinked: Int,
    /** Distinct hub Feature Records named by those notes. */
    val recordsLinked: Int,
) {
    /** One line with the counts; `okf xcheck` prints it last and the report ends with it. */
    fun summary(): String {
        val brokenRefs = problems.count { it.kind == XcheckKind.REFS }
        val refs = if (brokenRefs == 0) "$refsChecked cross-repo references resolved" else "$refsChecked cross-repo references checked ($brokenRefs broken)"
        val counts = "$repos repos checked, $refs, $refsUnchecked references to non-federated repos not checked, " +
            "$notesLinked Satellite Notes linked to $recordsLinked Feature Records"
        return when {
            problems.isNotEmpty() -> "okf xcheck: $counts; ${problems.size} problem(s), ${warnings.size} warning(s)."
            warnings.isNotEmpty() -> "okf xcheck OK: $counts; ${warnings.size} warning(s)."
            else -> "okf xcheck OK: $counts."
        }
    }
}

/** A satellite cannot be checked (checkout, git or `okf.yaml`); [findings] lists every such satellite with the reason. */
class XcheckSetupException(val findings: List<XcheckFinding>) :
    OkfException(if (findings.size == 1) "${findings.single()}" else "${findings.size} setup problems:\n" + findings.joinToString("\n") { "  - $it" })

/**
 * The cross-repo check of the federation: the hub ([OkfConfig.satellites]) and its
 * satellites, each checked out under one workspace as `<workspace>/<repo>`. Reads work trees and their git history only:
 *
 * - (a) code references written as `<repo>/<path>` into a federated repo resolve there ([OkfRefChecker.siblingRefs],
 *   [OkfRefChecker.checkSiblingRefs]); references into other `external_repos` are only counted;
 * - (b) every Satellite Note's `hub: features/<slug>` names a hub Feature Record;
 * - (c) a Feature Record's `repos:` and the Satellite Notes pointing at it agree in both directions;
 * - (d) a warning when a Satellite Note was last committed more than [OkfConfig.driftDays] days after its hub record
 *   (a shallow clone is a failure: per-file history would be wrong);
 * - (e) each `satellites[].index` URL ends with the satellite's `/<docs_root>/index.md`.
 */
object OkfXcheck {
    private const val SECONDS_PER_DAY = 86_400L
    private const val REPOS_KEY = OkfChecker.REPOS_KEY

    /**
     * Loads every satellite of [hub] from `<workspace>/<repo>`: the checkout must be a git work-tree root with a valid
     * `okf.yaml` whose `repo` is the directory name and whose `hub_repo` is the hub, and its `docs_root` must exist.
     * Throws [XcheckSetupException] listing every satellite that fails.
     */
    fun loadSatellites(workspace: File, hub: OkfConfig): List<FederatedRepo> {
        val problems = mutableListOf<XcheckFinding>()
        val loaded = hub.satellites.mapNotNull { satellite ->
            val dir = workspace.resolve(satellite.repo)
            val reason = try {
                val config = load(dir, satellite.repo)
                when {
                    config.repo != satellite.repo -> "okf.yaml repo '${config.repo}' does not match its checkout directory '${satellite.repo}'"
                    config.hubRepo != hub.repo -> "okf.yaml hub_repo '${config.hubRepo ?: "<absent>"}' is not the hub '${hub.repo}'"
                    !config.bundleDir(dir).isDirectory -> "docs_root '${config.docsRoot}' is not a directory in ${dir.path}"
                    else -> return@mapNotNull FederatedRepo(dir, config)
                }
            } catch (e: OkfException) {
                e.message
            }
            problems += XcheckFinding(XcheckKind.SETUP, satellite.repo, reason.orEmpty())
            null
        }
        if (problems.isNotEmpty()) throw XcheckSetupException(problems)
        return loaded
    }

    private fun load(dir: File, repo: String): OkfConfig {
        if (!dir.isDirectory) throw OkfConfigException("checkout ${dir.path} not found — the workspace must hold every satellite as <workspace>/$repo")
        val root = OkfGit.workTreeRoot(dir) ?: throw OkfConfigException("${dir.path} is not a git work tree")
        if (root.canonicalFile != dir.canonicalFile) throw OkfConfigException("${dir.path} is not a git work-tree root (it is inside ${root.path})")
        return OkfConfig.load(dir)
    }

    /** Runs (a)–(e) over [hub] and [satellites] — exactly the repos of the hub's `satellites` ([loadSatellites]). */
    fun check(hub: FederatedRepo, satellites: List<FederatedRepo>): XcheckResult {
        require(satellites.map { it.name }.sorted() == hub.config.satellites.map { it.repo }.sorted()) {
            "satellites ${satellites.map { it.name }} do not match the hub's okf.yaml satellites ${hub.config.satellites.map { it.repo }}"
        }
        val federation = listOf(hub) + satellites
        val byName = federation.associateBy { it.name }
        val problems = mutableListOf<XcheckFinding>()
        val warnings = mutableListOf<XcheckFinding>()

        val shallow = federation.filter { OkfGit.isShallow(it.dir) }.map { it.name }.toSet()
        shallow.forEach { problems += XcheckFinding(XcheckKind.SETUP, it, "shallow clone — xcheck needs full history: fetch-depth: 0") }

        // (a) Cross-repo references.
        var refsChecked = 0
        var refsUnchecked = 0
        for (source in federation) {
            val (federated, outside) = source.refs.siblingRefs(source.docs.map { it.first })
                .partition { it.repo != source.name && it.repo in byName }
            refsUnchecked += outside.size
            // Resolved per target repo; reported in document and line order.
            val findings = federated.groupBy { it.repo }.flatMap { (target, refs) ->
                byName.getValue(target).refs.checkSiblingRefs(refs).also { refsChecked += it.checked }.findings
            }
            findings.sortedWith(compareBy({ it.first.doc }, { it.first.line })).forEach { (ref, message) ->
                problems += XcheckFinding(XcheckKind.REFS, "${source.docLocation(ref.doc)}:${ref.line}", message)
            }
        }

        // (b) `hub:` of every Satellite Note → a hub Feature Record.
        val records = hub.docs.filter { (_, fm) -> fm.string("type") == OkfChecker.FEATURE_RECORD }.associate { (doc, fm) -> doc.path to (doc to fm) }
        val links = mutableListOf<Link>()
        for (satellite in satellites) {
            for ((doc, fm) in satellite.docs.filter { (_, fm) -> fm.string("type") == OkfChecker.SATELLITE_NOTE }) {
                val location = "${satellite.docLocation(doc.path)}:${keyLine(doc.text, OkfChecker.HUB_KEY)}"
                val hubId = fm.string(OkfChecker.HUB_KEY)
                val recordPath = "$hubId.md"
                val message = when {
                    hubId == null || !OkfChecker.HUB_ID.matches(hubId) ->
                        "Satellite Note has no valid `${OkfChecker.HUB_KEY}: features/<slug>` (got '${hubId ?: "<missing>"}')"
                    recordPath in records -> {
                        links += Link(satellite, doc, location, hubId, records.getValue(recordPath))
                        null
                    }
                    hub.bundle.resolve(recordPath).isFile -> {
                        val type = (OkfFrontmatter.read(hub.bundle.resolve(recordPath)) as? Frontmatter.Present)?.string("type")
                        "${OkfChecker.HUB_KEY} '$hubId' resolves to ${hub.docLocation(recordPath)}, which is not a Feature Record " +
                            "(type '${type ?: "<none>"}')"
                    }
                    else -> "${OkfChecker.HUB_KEY} '$hubId' does not resolve: ${hub.docLocation(recordPath)} does not exist"
                }
                if (message != null) problems += XcheckFinding(XcheckKind.HUB, location, message)
            }
        }

        // (c) `repos:` of each hub Feature Record ↔ the Satellite Notes pointing at it, both directions.
        val satelliteNames = satellites.map { it.name }.toSet()
        for ((doc, fm) in records.values) {
            if (!fm.data.containsKey(REPOS_KEY)) continue
            val location = "${hub.docLocation(doc.path)}:${keyLine(doc.text, REPOS_KEY)}"
            val id = doc.path.removeSuffix(".md")
            for (repo in repos(fm)) {
                val message = when {
                    repo !in satelliteNames -> "$REPOS_KEY lists '$repo', which is not a satellite (`satellites` in ${hub.name}/okf.yaml)"
                    links.none { it.satellite.name == repo && it.hubId == id } ->
                        "$REPOS_KEY lists '$repo', but $repo has no Satellite Note with `${OkfChecker.HUB_KEY}: $id`"
                    else -> null
                }
                if (message != null) problems += XcheckFinding(XcheckKind.REPOS, location, message)
            }
        }
        for (link in links) {
            val (recordDoc, recordFm) = link.record
            if (link.satellite.name !in repos(recordFm)) {
                problems += XcheckFinding(
                    XcheckKind.REPOS,
                    link.location,
                    "hub record ${hub.docLocation(recordDoc.path)} does not list ${link.satellite.name} in `$REPOS_KEY:`",
                )
            }
        }

        // (d) Drift: the note's last commit vs the record's (committer time); shallow clones are already failures.
        val recordTimes = mutableMapOf<String, Long?>()
        for (link in links.filter { it.satellite.name !in shallow && hub.name !in shallow }) {
            val recordPath = "${hub.config.docsRoot}/${link.record.first.path}"
            val notePath = "${link.satellite.config.docsRoot}/${link.note.path}"
            // `null` (record never committed) is cached too: getOrPut would run git again for it.
            if (recordPath !in recordTimes) recordTimes[recordPath] = OkfGit.lastCommitTime(hub.dir, recordPath)
            val recordTime = recordTimes[recordPath] ?: continue
            val noteTime = OkfGit.lastCommitTime(link.satellite.dir, notePath) ?: continue
            if (noteTime - recordTime > hub.config.driftDays * SECONDS_PER_DAY) {
                warnings += XcheckFinding(
                    XcheckKind.DRIFT,
                    "${hub.name}/$recordPath:1",
                    "hub record $recordPath is ${(noteTime - recordTime) / SECONDS_PER_DAY} days older than " +
                        "${link.satellite.name}/$notePath — distill the satellite changes into the record",
                )
            }
        }

        // (e) Each `satellites[].index` URL points at the satellite's bundle-root index.md.
        val hubYaml = hub.dir.resolve(OkfConfig.FILE_NAME).readText(Charsets.UTF_8).let(OkfFrontmatter::normalize).split('\n')
        hub.config.satellites.forEachIndexed { i, entry ->
            val satellite = satellites.first { it.name == entry.repo }
            val suffix = "/${satellite.config.docsRoot}/index.md"
            if (!entry.index.endsWith(suffix)) {
                val line = hubYaml.indexOfFirst { entry.index in it } + 1
                problems += XcheckFinding(
                    XcheckKind.INDEX_URL,
                    "${hub.name}/${OkfConfig.FILE_NAME}:${line.coerceAtLeast(1)}",
                    "satellites[$i].index '${entry.index}' must end with $suffix (docs_root of ${satellite.name})",
                )
            }
        }

        return XcheckResult(
            problems.sortedBy { it.kind },
            warnings,
            federation.size,
            refsChecked,
            refsUnchecked,
            links.size,
            links.map { it.hubId }.distinct().size,
        )
    }

    /**
     * The Markdown report `okf xcheck --report` writes when there is a problem or a warning: the findings grouped by
     * check, one bullet each, and the [summary] as footer.
     */
    fun report(hub: String, problems: List<XcheckFinding>, warnings: List<XcheckFinding>, summary: String): String {
        val findings = (problems + warnings).groupBy { it.kind }
        val sections = XcheckKind.entries.filter { it in findings }.map { kind ->
            "## ${kind.title}\n\n" + findings.getValue(kind).joinToString("\n") { "- $it" }
        }
        return (
            listOf(
                "# okf xcheck: ${problems.size} problem(s), ${warnings.size} warning(s)",
                "Cross-repo check of the OKF federation of `$hub` (hub + `satellites` in its okf.yaml).",
            ) + sections + listOf("---", summary)
            ).joinToString("\n\n") + "\n"
    }

    /** A Satellite Note ([note] in [satellite], `hub:` at [location]) and the hub Feature Record its [hubId] names. */
    private data class Link(
        val satellite: FederatedRepo,
        val note: RefDoc,
        val location: String,
        val hubId: String,
        val record: Pair<RefDoc, Frontmatter.Present>,
    )

    /** The `repos:` entries of a Feature Record; a malformed value counts as none (`okf check` reports its format). */
    private fun repos(fm: Frontmatter.Present): List<String> =
        (fm.data[REPOS_KEY] as? List<*>).orEmpty().mapNotNull { (it as? String)?.trim()?.takeIf(String::isNotEmpty) }

    /** 1-based line of the top-level frontmatter key [key] in [text]; 1 when it is not there. */
    private fun keyLine(text: String, key: String): Int {
        val lines = OkfFrontmatter.normalize(text).split('\n')
        if (lines.first().trimEnd() != "---") return 1
        val pattern = Regex("""^${Regex.escape(key)}\s*:""")
        for (i in 1 until lines.size) {
            if (lines[i].trimEnd() == "---") break
            if (pattern.containsMatchIn(lines[i])) return i + 1
        }
        return 1
    }
}
