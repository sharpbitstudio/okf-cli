package okf

import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/** Outcome of [OkfChecker.check]; paths are relative to the bundle root. */
data class OkfCheckResult(
    /** Failures (`path: reason`); in strict mode they include files without frontmatter. */
    val problems: List<String>,
    /** Non-fatal findings (`path: reason`), e.g. `stale_after` in the past. */
    val warnings: List<String>,
    /** Non-reserved files without frontmatter (reported regardless of strict mode). */
    val unmigrated: List<String>,
    /** Number of documents whose frontmatter was validated. */
    val validated: Int,
    /** Code-reference findings ([OkfRefChecker]); also listed in [problems] (refs strict) or [warnings]. */
    val refFindings: List<RefFinding> = emptyList(),
    /** Number of code references checked (0 when reference checks did not run). */
    val refsChecked: Int = 0,
    /** Accepted/Resolved ADRs, Feature Records and Satellite Notes without `code_paths` (reported regardless of `codePathsRequired`). */
    val codePathsMissing: List<String> = emptyList(),
    /**
     * [codePathsMissing] counted per kind: [OkfChecker.CODE_PATHS_ADRS], [OkfChecker.CODE_PATHS_FEATURE_RECORDS],
     * [OkfChecker.CODE_PATHS_SATELLITE_NOTES] (a kind with nothing missing is absent).
     */
    val codePathsMissingByKind: Map<String, Int> = emptyMap(),
)

/**
 * OKF bundle validation (unit-testable against fixture directories): frontmatter of every migrated
 * document, the bundle-root index.md, freshness of the generated sub-indexes and — when [check] gets
 * an [OkfRefChecker] — the code references in the bodies of migrated documents. `okf check`
 * ([okf.cli.OkfCli]) is the entry point.
 */
object OkfChecker {
    private val LIFECYCLE = listOf("draft", "stable", "deprecated")
    private val ADR_STATUS_TO_LIFECYCLE = linkedMapOf(
        "Proposed" to "draft",
        "Accepted" to "stable",
        "Resolved" to "stable",
        "Superseded" to "deprecated",
        "Deprecated" to "deprecated",
    )
    /** ADR statuses on which `code_paths` is mandatory once [check] runs with `codePathsRequired`. */
    private val CODE_PATHS_ADR_STATUSES = setOf("Accepted", "Resolved")
    private val ISO_DATE = Regex("""\d{4}-\d{2}-\d{2}""")

    /** Kinds of document on which `code_paths` is mandatory, as findings and [OkfCheckResult.codePathsMissingByKind] name them. */
    const val CODE_PATHS_ADRS = "Accepted/Resolved ADRs"
    const val CODE_PATHS_FEATURE_RECORDS = "Feature Records"
    const val CODE_PATHS_SATELLITE_NOTES = "Satellite Notes"

    internal const val FEATURE_RECORD = "Feature Record"
    internal const val SATELLITE_NOTE = "Satellite Note"

    /** Problem for a missing bundle-root index.md (also `okf index` in the hub, which writes its "Repos" section). */
    const val MISSING_ROOT_INDEX = "index.md (bundle root) is missing — required, must carry okf_version \"0.2\""

    /** Satellite Note → its hub Feature Record, in the `Docs-Impact:` ID form (`features/<slug>`). */
    internal const val HUB_KEY = "hub"
    internal val HUB_ID = Regex("""features/[a-z0-9]+(?:-[a-z0-9]+)*""")

    /** Feature Record → the sibling repos (`external_repos`) whose Satellite Notes belong to it. */
    internal const val REPOS_KEY = "repos"

    /** Bundle-relative path of an ADR file; ADR identity is decided by this name, not by `type`. */
    private val ADR_PATH = Regex("""${OkfIndexRenderer.ADR_DIR}/${OkfIndexRenderer.ADR_FILE.pattern}""")

    /**
     * In-place ADR amendment marker; group 1 is the date. Recognized forms: a `##`/`###` heading
     * `Update <date>` or `Amendment — <date>`, and inline `**Update <date>` or
     * `**Naknadna napomena (<date>`. The canonical form for new amendments is `**Update YYYY-MM-DD:**`.
     * Matches inside fenced code blocks or inline code spans do not count (see [OkfMarkdown.stripFencedBlocks],
     * [OkfMarkdown.stripCodeSpans]).
     */
    val AMENDMENT_MARKER = Regex(
        """(?:^#{2,3} (?:Update |Amendment — )|\*\*(?:Update |Naknadna napomena \())(\d{4}-\d{2}-\d{2})\b""",
        RegexOption.MULTILINE,
    )

    /**
     * [refs] checks code references in the bodies of documents with valid frontmatter; its findings
     * are problems when [refsStrict], warnings otherwise. Without [refs] no reference check runs, and
     * `code_paths` globs ([OkfCodePaths]) are checked for syntax only, not against the repo's files.
     * [codePathsRequired] makes `code_paths` mandatory on Accepted/Resolved ADRs, Feature Records and Satellite Notes.
     * [indexCommand] (`index_command`, [OkfConfig]) renders the expected indexes and is named in the "run …" hints.
     * [hubRepo] (`hub_repo`) must be set for a Satellite Note to be valid; a Feature Record's `repos` entries
     * must be in [externalRepos] (`external_repos`). The generated sub-indexes are checked only for the
     * sub-directories (`adr/`, `features/`) that exist. Non-empty [satellites] (`satellites`, hub only) makes the
     * bundle-root index.md carry a fresh "Repos" section ([OkfIndexRenderer.replaceReposBlock]).
     * [indexLanguage] (`index_language`) renders the expected sub-indexes.
     */
    fun check(
        root: File,
        strict: Boolean,
        now: Instant = Instant.now(),
        refs: OkfRefChecker? = null,
        refsStrict: Boolean = false,
        codePathsRequired: Boolean = false,
        indexCommand: String = OkfConfig.DEFAULT_INDEX_COMMAND,
        hubRepo: String? = null,
        externalRepos: Set<String> = emptySet(),
        satellites: List<Satellite> = emptyList(),
        indexLanguage: IndexLanguage = IndexLanguage.SR,
    ): OkfCheckResult {
        val problems = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val unmigrated = mutableListOf<String>()
        val codePathsMissing = mutableListOf<Pair<String, String>>()
        val refDocs = mutableListOf<RefDoc>()
        var validated = 0

        // Checked explicitly: the walk below only validates index.md when it exists.
        if (!root.resolve("index.md").isFile) problems += MISSING_ROOT_INDEX

        root.walkTopDown().filter { it.isFile && it.extension == "md" }.sortedBy { rel(root, it) }.forEach { file ->
            val path = rel(root, file)
            val text = file.readText(Charsets.UTF_8)
            val fm = OkfFrontmatter.parse(text)
            when {
                path == "index.md" -> checkRootIndex(fm).forEach { problems += "$path: $it" }
                file.name == "index.md" -> when (fm) {
                    Frontmatter.Absent -> Unit
                    is Frontmatter.Invalid -> problems += "$path: ${fm.reason}"
                    is Frontmatter.Present -> problems += "$path: non-root index.md is reserved and must not have frontmatter"
                }
                file.name in OkfFrontmatter.RESERVED -> Unit
                else -> when (fm) {
                    Frontmatter.Absent -> unmigrated += path
                    is Frontmatter.Invalid -> problems += "$path: ${fm.reason}"
                    is Frontmatter.Present -> {
                        validated++
                        checkDocument(root, path, file, text, fm, now, warnings, hubRepo, externalRepos).forEach { problems += "$path: $it" }
                        checkCodePaths(path, fm, refs, codePathsRequired, problems, warnings, codePathsMissing)
                        refDocs += RefDoc(path, text)
                    }
                }
            }
        }

        // A sub-index is optional: required (and kept fresh) only when its directory exists.
        if (root.resolve(OkfIndexRenderer.ADR_DIR).isDirectory) {
            problems += checkGeneratedIndex(root, OkfIndexRenderer.ADR_DIR, indexCommand) {
                OkfIndexRenderer.renderAdrIndex(it, indexCommand, indexLanguage)
            }
        }
        if (root.resolve(OkfIndexRenderer.FEATURES_DIR).isDirectory) {
            problems += checkGeneratedIndex(root, OkfIndexRenderer.FEATURES_DIR, indexCommand) {
                OkfIndexRenderer.renderFeaturesIndex(it, indexCommand, indexLanguage)
            }
        }

        if (satellites.isNotEmpty() && root.resolve("index.md").isFile) {
            problems += checkReposSection(root.resolve("index.md"), satellites, indexCommand)
        }

        if (strict) {
            unmigrated.forEach { problems += "$it: no frontmatter (strict mode: every non-reserved .md must be migrated)" }
        }
        val refResult = refs?.check(refDocs) ?: RefCheckResult(emptyList(), 0)
        (if (refsStrict) problems else warnings).addAll(refResult.findings.map(RefFinding::toString))
        return OkfCheckResult(
            problems,
            warnings,
            unmigrated,
            validated,
            refResult.findings,
            refResult.checked,
            codePathsMissing.map { it.first },
            codePathsMissing.groupingBy { it.second }.eachCount(),
        )
    }

    private fun checkRootIndex(fm: Frontmatter): List<String> =
        when (fm) {
            Frontmatter.Absent -> listOf("bundle-root index.md must start with frontmatter `okf_version: \"0.2\"`")
            is Frontmatter.Invalid -> listOf(fm.reason)
            is Frontmatter.Present -> buildList {
                if (fm.data["okf_version"] != "0.2") add("okf_version must be the string \"0.2\" (quoted), got '${fm.data["okf_version"]}'")
                val extra = fm.data.keys - "okf_version"
                if (extra.isNotEmpty()) add("bundle-root index.md frontmatter may only hold okf_version, found also: ${extra.sorted()}")
            }
        }

    private fun checkDocument(
        root: File,
        path: String,
        file: File,
        text: String,
        fm: Frontmatter.Present,
        now: Instant,
        warnings: MutableList<String>,
        hubRepo: String?,
        externalRepos: Set<String>,
    ): List<String> = buildList {
        val type = fm.string("type")
        val adrFile = ADR_PATH.matches(path)
        when {
            type == null ->
                add("`type` is missing or empty" + if (adrFile) " (files named adr/ADR-NNN-*.md must have `type: ADR`)" else "")
            type !in OkfFrontmatter.TYPES -> add("type '$type' is not in the taxonomy ${OkfFrontmatter.TYPES.sorted()}")
        }
        if (type != null && adrFile && type != "ADR") add("files named adr/ADR-NNN-*.md must have `type: ADR`, got '$type'")
        if (type == "ADR" && !adrFile) {
            add("`type: ADR` is only allowed on files named adr/ADR-NNN-<slug>.md (three-digit number)")
        }
        val status = fm.string("status")
        if (status != null && status !in LIFECYCLE) add("status '$status' must be one of $LIFECYCLE")

        if (type == "ADR") addAll(checkAdr(root, file, text, fm, status))
        val gotType = "got type '${type ?: "<missing>"}'"
        if (type == SATELLITE_NOTE) {
            addAll(checkHub(fm.data[HUB_KEY], hubRepo))
        } else if (fm.data.containsKey(HUB_KEY)) {
            add("`$HUB_KEY` is only allowed on Satellite Notes ($gotType)")
        }
        if (fm.data.containsKey(REPOS_KEY)) {
            when (type) {
                FEATURE_RECORD -> addAll(checkRepos(fm.data[REPOS_KEY], externalRepos))
                else -> add("`$REPOS_KEY` is only allowed on Feature Records ($gotType)")
            }
        }

        // Stamps are validated by key presence: `generated:` with an empty value is an error, not "absent".
        if (fm.data.containsKey("generated")) addAll(checkActorStamp("generated", fm.data["generated"]))
        if (fm.data.containsKey("verified")) {
            when (val verified = fm.data["verified"]) {
                is List<*> ->
                    if (verified.isEmpty()) {
                        add("verified must be a mapping with `by` and `at`, or a non-empty list of them")
                    } else {
                        verified.forEachIndexed { i, v -> addAll(checkActorStamp("verified[$i]", v)) }
                    }
                is Map<*, *> -> addAll(checkActorStamp("verified", verified))
                else -> add("verified must be a mapping with `by` and `at`, or a non-empty list of them")
            }
        }

        fm.string("stale_after")?.let { raw ->
            val instant = parseInstant(raw)
            when {
                instant == null -> add("stale_after '$raw' is not a valid ISO-8601 instant (e.g. 2026-12-31T00:00:00Z)")
                instant.isBefore(now) -> warnings += "$path: stale_after $raw is in the past — review and re-verify the content."
            }
        }
    }

    private fun checkCodePaths(
        path: String,
        fm: Frontmatter.Present,
        refs: OkfRefChecker?,
        required: Boolean,
        problems: MutableList<String>,
        warnings: MutableList<String>,
        missing: MutableList<Pair<String, String>>,
    ) {
        if (!fm.data.containsKey(OkfCodePaths.KEY)) {
            val type = fm.string("type")
            val kind = when {
                type == "ADR" && fm.string("adr_status") in CODE_PATHS_ADR_STATUSES -> CODE_PATHS_ADRS
                type == FEATURE_RECORD -> CODE_PATHS_FEATURE_RECORDS
                type == SATELLITE_NOTE -> CODE_PATHS_SATELLITE_NOTES
                else -> null
            } ?: return
            missing += path to kind
            if (required) {
                problems += "$path: `${OkfCodePaths.KEY}` is required on $kind — " +
                    "list the repo-root globs of the code it describes, or `${OkfCodePaths.KEY}: []` with the reason in the body"
            }
            return
        }
        val result = OkfCodePaths.validate(fm.data[OkfCodePaths.KEY], refs?.index, refs?.bundlePath ?: "docs")
        result.problems.forEach { problems += "$path: $it" }
        result.warnings.forEach { warnings += "$path: $it" }
    }

    private fun checkAdr(root: File, file: File, text: String, fm: Frontmatter.Present, status: String?): List<String> = buildList {
        if (fm.string("title") == null) add("ADR requires a non-empty `title`")
        if (fm.string("description") == null) add("ADR requires a non-empty `description` („kada čitati\")")
        addAll(checkAdrTags(fm.data["tags"]))

        val adrStatus = fm.string("adr_status")
        val expectedStatus = ADR_STATUS_TO_LIFECYCLE[adrStatus]
        when {
            adrStatus == null -> add("ADR requires a non-empty `adr_status` (one of ${ADR_STATUS_TO_LIFECYCLE.keys})")
            expectedStatus == null -> add("adr_status '$adrStatus' must be one of ${ADR_STATUS_TO_LIFECYCLE.keys}")
            status == null -> add("status is missing (expected: $expectedStatus for adr_status $adrStatus)")
            status != expectedStatus ->
                add("status '$status' is inconsistent with adr_status '$adrStatus' (expected status: $expectedStatus)")
        }

        val adrDate = fm.string("adr_date")
        when {
            adrDate == null -> add("ADR requires `adr_date` as a valid YYYY-MM-DD date")
            !ISO_DATE.matches(adrDate) || parseDate(adrDate) == null -> add("adr_date '$adrDate' must be a valid YYYY-MM-DD date")
        }

        addAll(checkAmended(text, fm, adrDate?.takeIf(ISO_DATE::matches)?.let(::parseDate)))

        if (adrStatus == "Superseded") {
            val target = fm.string("superseded_by")
            val resolved = target?.let { file.absoluteFile.parentFile.resolve(it).normalize() }
            when {
                target == null || resolved == null -> add("adr_status Superseded requires superseded_by")
                target.startsWith("/") -> add("superseded_by '$target' must be a relative path (e.g. ./ADR-NNN-....md)")
                resolved.parentFile != root.absoluteFile.resolve(OkfIndexRenderer.ADR_DIR).normalize() ||
                    !OkfIndexRenderer.ADR_FILE.matches(resolved.name) ->
                    add("superseded_by '$target' must point to an ADR file in ${OkfIndexRenderer.ADR_DIR}/ (ADR-NNN-<slug>.md)")
                resolved == file.absoluteFile.normalize() -> add("superseded_by must not point to the ADR itself")
                !resolved.isFile -> add("superseded_by target '$target' does not exist")
            }
        } else if (fm.data.containsKey("superseded_by")) {
            add("superseded_by is only allowed with adr_status Superseded (got adr_status '$adrStatus')")
        }
    }

    private fun checkAdrTags(raw: Any?): List<String> {
        if (raw !is List<*> || raw.isEmpty()) {
            return listOf("ADR requires `tags` as a non-empty YAML list of strings (e.g. tags: [grpc, security]), got '$raw'")
        }
        return raw.mapIndexedNotNull { i, tag ->
            if (tag is String && tag.isNotBlank()) null else "tags[$i] must be a non-empty string, got '$tag'"
        }
    }

    /**
     * `hub` (required on a Satellite Note): the hub Feature Record as `features/<slug>`. Only the format is
     * checked here; whether the record exists in the hub repo is a cross-repo check.
     */
    private fun checkHub(raw: Any?, hubRepo: String?): List<String> = buildList {
        // Trimmed like every other scalar ([Frontmatter.Present.string]).
        val hub = if (raw is String) raw.trim() else raw
        when {
            hub == null || hub == "" ->
                add("Satellite Note requires `$HUB_KEY: features/<slug>` — the hub Feature Record it belongs to")
            hub !is String || !HUB_ID.matches(hub) ->
                add("$HUB_KEY '$hub' must be `features/<slug>` (slug: lowercase letters and digits, words joined by single hyphens)")
        }
        if (hubRepo == null) add("Satellite Note requires hub_repo in okf.yaml (the repository that holds its Feature Record)")
    }

    /** `repos` (optional on a Feature Record): the sibling repos holding its Satellite Notes, each from `external_repos`. */
    private fun checkRepos(raw: Any?, externalRepos: Set<String>): List<String> {
        if (raw !is List<*>) {
            return listOf("$REPOS_KEY must be a YAML list of repository names from external_repos (e.g. $REPOS_KEY: [sibling-repo]), got '$raw'")
        }
        val seen = mutableSetOf<String>()
        return raw.mapIndexedNotNull { i, entry ->
            val name = (entry as? String)?.trim()
            when {
                name.isNullOrEmpty() -> "$REPOS_KEY[$i] must be a non-empty string, got '$entry'"
                !seen.add(name) -> "$REPOS_KEY[$i] '$name' duplicates an earlier entry"
                name !in externalRepos -> "$REPOS_KEY[$i] '$name' is not listed in external_repos of okf.yaml"
                else -> null
            }
        }
    }

    /**
     * `amended` (optional): ascending list of in-place amendment dates, each after `adr_date`,
     * kept in sync both ways with the amendment markers in the body ([AMENDMENT_MARKER]).
     */
    private fun checkAmended(text: String, fm: Frontmatter.Present, adrDate: LocalDate?): List<String> = buildList {
        val raw = fm.data["amended"]
        val declared: List<String> = when (raw) {
            null -> emptyList()
            is List<*> -> raw.map { it?.toString()?.trim().orEmpty() }
            else -> {
                add("amended must be a YAML list of YYYY-MM-DD dates (e.g. amended: [2026-07-21]), got '$raw'")
                return@buildList
            }
        }
        var previous: LocalDate? = null
        for (value in declared) {
            val date = value.takeIf(ISO_DATE::matches)?.let(::parseDate)
            if (date == null) {
                add("amended entry '$value' must be a valid YYYY-MM-DD date")
                continue
            }
            when {
                adrDate == null -> Unit
                date == adrDate -> add(
                    "amended date $value is the same day as adr_date — same-day changes are part of the original " +
                        "decision: remove $value from `amended` and drop its marker",
                )
                date.isBefore(adrDate) -> add("amended date $value must be after adr_date $adrDate")
            }
            if (previous != null && !date.isAfter(previous)) add("amended dates must be strictly ascending ($previous, then $value)")
            previous = date
        }

        val scanned = OkfMarkdown.stripFencedBlocks(OkfFrontmatter.body(text)).split('\n')
            .joinToString("\n", transform = OkfMarkdown::stripCodeSpans)
        val markers = AMENDMENT_MARKER.findAll(scanned).map { it.groupValues[1] }.toSortedSet()
        val sameDay = adrDate?.toString()
        (markers - declared.toSet()).forEach {
            if (it == sameDay) {
                add(
                    "body has an amendment marker dated $it, the same day as adr_date — same-day changes are part of " +
                        "the original decision: drop the marker (do not add $it to `amended`)",
                )
            } else {
                add("body has an amendment marker dated $it (e.g. `**Update $it:**`) but `amended` does not list $it")
            }
        }
        (declared.toSet() - markers).filter(ISO_DATE::matches).forEach {
            add("amended lists $it but the body has no amendment marker for it (add `**Update $it:**` where the ADR was changed)")
        }
    }

    private fun checkActorStamp(key: String, value: Any?): List<String> {
        val stamp = value as? Map<*, *> ?: return listOf("$key must be a mapping with `by` and `at`")
        return buildList {
            if (stamp["by"]?.toString().isNullOrBlank()) add("$key.by is missing")
            val at = stamp["at"]?.toString()
            if (at == null || parseInstant(at) == null) {
                add("$key.at '$at' must be an ISO-8601 datetime with offset or Z (e.g. 2026-09-25T00:00:00Z); a date alone is not accepted")
            }
        }
    }

    private fun checkGeneratedIndex(root: File, dir: String, indexCommand: String, render: (File) -> String): List<String> {
        val path = "$dir/index.md"
        val expected = try {
            render(root.resolve(dir))
        } catch (e: OkfException) {
            return listOf("$path: cannot be rendered (${e.message}) — fix that, then run $indexCommand")
        }
        val indexFile = root.resolve(path)
        return when {
            !indexFile.isFile -> listOf("$path: missing — run $indexCommand")
            OkfFrontmatter.normalize(indexFile.readText(Charsets.UTF_8)) != expected ->
                listOf("$path: out of date or edited by hand — run $indexCommand")
            else -> emptyList()
        }
    }

    /** The "Repos" section of the bundle-root index.md, compared normalized like the generated sub-indexes. */
    private fun checkReposSection(indexFile: File, satellites: List<Satellite>, indexCommand: String): List<String> {
        val current = OkfFrontmatter.normalize(indexFile.readText(Charsets.UTF_8))
        val expected = try {
            OkfIndexRenderer.replaceReposBlock(current, OkfIndexRenderer.renderReposBlock(satellites, indexCommand))
        } catch (e: OkfException) {
            return listOf("${e.message} — add them, then run $indexCommand")
        }
        return if (expected == current) emptyList() else listOf("index.md: Repos section out of date or edited by hand — run $indexCommand")
    }

    private fun rel(root: File, file: File) = file.relativeTo(root).invariantSeparatorsPath

    private fun parseInstant(raw: String): Instant? =
        try {
            OffsetDateTime.parse(raw).toInstant()
        } catch (_: DateTimeParseException) {
            null
        }

    private fun parseDate(raw: String): LocalDate? =
        try {
            LocalDate.parse(raw)
        } catch (_: DateTimeParseException) {
            null
        }
}
