package com.dev.docscannerpdf.security

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * The backup / data-extraction exclusion contract for scanned document content.
 *
 * ## The disclosure this suite exists to make unreachable
 *
 * Scanned ID cards, passports and their OCR text are the most sensitive data this app holds, and
 * Android's default is to upload app-private storage to the user's cloud backup and to copy it onto
 * a new device. Nothing in the Kotlin sources can prevent that; the only control is two XML policy
 * files, and a policy file is exactly the kind of artifact that gets "tidied up" by someone who
 * cannot see what it protects.
 *
 * Three specific ways that protection has historically been lost, each asserted below:
 *
 * 1. **One policy generation is updated and the other is not.** `backup_rules.xml` governs devices
 *    below API 31; `data_extraction_rules.xml` governs API 31+. Neither is a fallback for the other.
 * 2. **`<device-transfer>` is forgotten.** Cloud backup and device-to-device transfer are separate
 *    transports with separate rulesets, and an omitted section transfers everything. A scan withheld
 *    from the cloud but copied onto a new phone is still disclosed, so the two sections are asserted
 *    to be exactly equal rather than merely non-empty.
 * 3. **A new scanner directory is added and the rules are not.** [productionFilesDirDirectories]
 *    reads the directory names back out of the production sources, so a directory that exists in
 *    Kotlin but is uncovered by the policy fails here rather than in a stranger's Google account.
 *
 * The suite parses the XML structurally - the attributes of `<exclude>` elements - rather than
 * matching substrings, so reformatting the policy files is free and weakening them is not.
 */
class BackupExclusionPolicyTest {

    // -----------------------------------------------------------------------------------------
    // The protected set
    // -----------------------------------------------------------------------------------------

    private companion object {

        /**
         * Raw captures and authoritative post-crop artifacts of the main scanner. Held as literals
         * because literals are what the XML contains; [main scan constants still match the excluded
         * directory names] pins them to the production constants so a rename cannot silently orphan
         * these entries.
         */
        val MAIN_SCAN_DIRECTORIES = listOf(
            "main_scan_capture",
            "main_scan_cropped",
            "main_scan_enhanced"
        )

        /** ID-card and passport capture and output directories. */
        val IDENTITY_DOCUMENT_DIRECTORIES = listOf(
            "id_card_guided_capture",
            "id_card_filtered",
            "id_card_combined",
            "id_scan_preview",
            "id_scan_preview_back",
            "id_scan_enhanced",
            "id_scan_rotated",
            "passport_guided_capture",
            "passport_cropped",
            "passport_filtered",
            "passport_rotated",
            "passport_watermarked"
        )

        /** Other scanner-derived image content, and user markup over scanned pages. */
        val DERIVED_IMAGE_DIRECTORIES = listOf(
            "cropped_images",
            "imported_images",
            "annotations"
        )

        /** Document artifacts rendered from scans, including the searchable OCR text layer. */
        val DOCUMENT_OUTPUT_DIRECTORIES = listOf(
            "searchable_pdfs",
            "generated_pdfs",
            "edited_pdfs",
            "split_pdfs",
            "merged_pdfs",
            "locked_pdfs",
            "signed_pdfs",
            "watermarked_pdfs",
            "word_exports",
            "pdf_images",
            "exports"
        )

        val REQUIRED_FILE_PATHS: List<String> =
            MAIN_SCAN_DIRECTORIES +
                IDENTITY_DOCUMENT_DIRECTORIES +
                DERIVED_IMAGE_DIRECTORIES +
                DOCUMENT_OUTPUT_DIRECTORIES

        /**
         * The Room database and its SQLite sidecars. Database-domain excludes match a single file
         * rather than a prefix, and a `-wal` file holds committed page images - excluding only the
         * `.db` would back up the very rows that exclusion claims to protect.
         */
        val REQUIRED_DATABASE_PATHS = listOf(
            "docscanner_pdf.db",
            "docscanner_pdf.db-wal",
            "docscanner_pdf.db-shm",
            "docscanner_pdf.db-journal"
        )

        /** Excluding the file-domain root keeps a future scanner directory private by default. */
        const val FILE_DOMAIN_ROOT = "."
    }

    // -----------------------------------------------------------------------------------------
    // Pre-Android-12: backup_rules.xml
    // -----------------------------------------------------------------------------------------

    @Test
    fun `pre-31 backup rules exclude every sensitive scanner directory`() {
        assertExcludes(preThirtyOneRules())
    }

    // -----------------------------------------------------------------------------------------
    // Android 12+: data_extraction_rules.xml, both transports
    // -----------------------------------------------------------------------------------------

    @Test
    fun `cloud backup excludes every sensitive scanner directory`() {
        assertExcludes(cloudBackupRules())
    }

    @Test
    fun `device transfer excludes every sensitive scanner directory`() {
        assertExcludes(deviceTransferRules())
    }

    /**
     * The two API 31+ transports must not diverge. Android imposes no difference in what they may
     * carry for this app, so any asymmetry is an omission rather than a decision.
     */
    @Test
    fun `cloud backup and device transfer protect exactly the same data`() {
        val cloud = cloudBackupRules().excludes
        val transfer = deviceTransferRules().excludes
        assertEquals(
            "data_extraction_rules.xml: <cloud-backup> and <device-transfer> must exclude the " +
                "same data.\n" +
                "  Excluded from cloud backup only: " + describe(cloud - transfer) + "\n" +
                "  Excluded from device transfer only: " + describe(transfer - cloud),
            cloud,
            transfer
        )
    }

    /**
     * Both policy generations must agree. `backup_rules.xml` is not a fallback for
     * `data_extraction_rules.xml`; a device below API 31 reads only the former.
     */
    @Test
    fun `both policy generations protect exactly the same data`() {
        val preThirtyOne = preThirtyOneRules().excludes
        val cloud = cloudBackupRules().excludes
        assertEquals(
            "backup_rules.xml and data_extraction_rules.xml must exclude the same data; a device " +
                "below API 31 reads only backup_rules.xml.\n" +
                "  Excluded pre-31 only: " + describe(preThirtyOne - cloud) + "\n" +
                "  Excluded on API 31+ only: " + describe(cloud - preThirtyOne),
            preThirtyOne,
            cloud
        )
    }

    // -----------------------------------------------------------------------------------------
    // Policy shape
    // -----------------------------------------------------------------------------------------

    /**
     * A single `<include>` flips its section from a denylist to an allowlist, at which point every
     * exclusion above becomes decoration and unrelated app data silently stops being backed up.
     */
    @Test
    fun `no section declares an include`() {
        allSections().forEach { rules ->
            assertTrue(
                rules.label + " declares <include> " + describe(rules.includes) + ". An <include> " +
                    "turns the section into an allowlist, so the <exclude> rules protecting " +
                    "scanned documents would no longer be consulted.",
                rules.includes.isEmpty()
            )
        }
    }

    /** An `<exclude>` missing either attribute is silently ignored by the platform parser. */
    @Test
    fun `every exclude declares both a domain and a path`() {
        allSections().forEach { rules ->
            assertTrue(
                rules.label + " has an <exclude> missing a domain or path attribute; the platform " +
                    "ignores such a rule. Incomplete: " + describe(rules.malformed),
                rules.malformed.isEmpty()
            )
        }
    }

    /**
     * The rules are inert unless the manifest points at them. Read-only assertion - this test never
     * writes to the manifest.
     */
    @Test
    fun `manifest wires both policy generations`() {
        val manifest = locate("app/src/main/AndroidManifest.xml", "src/main/AndroidManifest.xml")
            .readText()
        listOf(
            "android:fullBackupContent=\"@xml/backup_rules\"" to "backup_rules.xml (pre-31)",
            "android:dataExtractionRules=\"@xml/data_extraction_rules\"" to
                "data_extraction_rules.xml (API 31+)"
        ).forEach { (attribute, what) ->
            assertTrue(
                "AndroidManifest.xml no longer declares " + attribute + ", so " + what + " is " +
                    "never consulted and scanned documents become backup eligible.",
                manifest.contains(attribute)
            )
        }
    }

    // -----------------------------------------------------------------------------------------
    // Future scanner directories
    // -----------------------------------------------------------------------------------------

    /**
     * Reads every app-private directory name the production sources build under `filesDir` and
     * requires the policy to cover it - either by the file-domain root exclusion or by name. This is
     * what makes a scanner directory added next year fail here rather than in a cloud backup.
     */
    @Test
    fun `every private directory used by production code is covered`() {
        val discovered = productionFilesDirDirectories()
        assertTrue(
            "Found no File(filesDir, \"...\") directories in the production sources. The scan is " +
                "broken, not the policy - fix this test before trusting it.",
            discovered.isNotEmpty()
        )
        allSections().forEach { rules ->
            val filePaths = rules.pathsFor("file")
            if (FILE_DOMAIN_ROOT in filePaths) return@forEach
            val uncovered = discovered - filePaths
            assertTrue(
                rules.label + " does not cover " + uncovered.sorted() + ". These directories are " +
                    "created under filesDir by production code and would be backed up. Either " +
                    "exclude each by name, or restore the file-domain root exclusion " +
                    "<exclude domain=\"file\" path=\".\" />.",
                uncovered.isEmpty()
            )
        }
    }

    /**
     * The main-scan directory names live in the XML as literals; this pins those literals to the
     * production constants so renaming a constant cannot leave the policy pointing at nothing.
     */
    @Test
    fun `main scan constants still match the excluded directory names`() {
        val source = locate(
            "app/src/main/java/com/dev/docscannerpdf/domain/mainscan/MainScanAuthoritativeRender.kt",
            "src/main/java/com/dev/docscannerpdf/domain/mainscan/MainScanAuthoritativeRender.kt"
        ).readText()
        mapOf(
            "CAPTURE_DIRECTORY_NAME" to "main_scan_capture",
            "CROPPED_DIRECTORY_NAME" to "main_scan_cropped",
            "ENHANCED_DIRECTORY_NAME" to "main_scan_enhanced"
        ).forEach { (constant, expected) ->
            val actual = Regex("const\\s+val\\s+" + constant + "\\s*=\\s*\"([^\"]*)\"")
                .find(source)
                ?.groupValues
                ?.get(1)
            assertEquals(
                "MainScanAuthoritativeRender." + constant + " no longer resolves to the directory " +
                    "name excluded from backup. Update backup_rules.xml, " +
                    "data_extraction_rules.xml and MAIN_SCAN_DIRECTORIES together.",
                expected,
                actual
            )
        }
    }

    // -----------------------------------------------------------------------------------------
    // Shared assertions
    // -----------------------------------------------------------------------------------------

    private fun assertExcludes(rules: BackupSection) {
        val missingFiles = REQUIRED_FILE_PATHS - rules.pathsFor("file")
        assertTrue(
            rules.label + " no longer excludes " + missingFiles.sorted() + " from domain \"file\". " +
                "These directories hold scanner captures or content derived from them; without the " +
                "exclusion they are uploaded to the user's backup.",
            missingFiles.isEmpty()
        )

        val missingDatabases = REQUIRED_DATABASE_PATHS - rules.pathsFor("database")
        assertTrue(
            rules.label + " no longer excludes " + missingDatabases.sorted() + " from domain " +
                "\"database\". The document database holds every scan's title, OCR text and " +
                "searchable metadata, and the SQLite sidecars hold committed copies of the same rows.",
            missingDatabases.isEmpty()
        )

        assertTrue(
            rules.label + " no longer excludes the file-domain root " +
                "<exclude domain=\"file\" path=\".\" />. Without it a scanner directory added " +
                "later is backup eligible until someone remembers to amend the policy.",
            FILE_DOMAIN_ROOT in rules.pathsFor("file")
        )
    }

    // -----------------------------------------------------------------------------------------
    // Parsing
    // -----------------------------------------------------------------------------------------

    /** One `<full-backup-content>`, `<cloud-backup>` or `<device-transfer>` section. */
    private data class BackupSection(
        val label: String,
        /** Every complete `<exclude>` as a (domain, path) pair. */
        val excludes: Set<Pair<String, String>>,
        /** Every `<include>`; any at all defeats the policy. */
        val includes: Set<Pair<String, String>>,
        /** `<exclude>` elements missing a domain or a path, which the platform ignores. */
        val malformed: Set<Pair<String, String>>
    ) {
        fun pathsFor(domain: String): Set<String> =
            excludes.filter { it.first == domain }.map { it.second }.toSet()
    }

    private fun allSections(): List<BackupSection> =
        listOf(preThirtyOneRules(), cloudBackupRules(), deviceTransferRules())

    private fun preThirtyOneRules(): BackupSection = parseSection(
        fileName = "backup_rules.xml",
        sectionTag = "full-backup-content"
    )

    private fun cloudBackupRules(): BackupSection = parseSection(
        fileName = "data_extraction_rules.xml",
        sectionTag = "cloud-backup"
    )

    private fun deviceTransferRules(): BackupSection = parseSection(
        fileName = "data_extraction_rules.xml",
        sectionTag = "device-transfer"
    )

    private fun parseSection(fileName: String, sectionTag: String): BackupSection {
        val label = fileName + " <" + sectionTag + ">"
        val file = locate("app/src/main/res/xml/" + fileName, "src/main/res/xml/" + fileName)
        val document = DocumentBuilderFactory.newInstance()
            .also { it.isNamespaceAware = false }
            .newDocumentBuilder()
            .parse(file)

        val sections = document.getElementsByTagName(sectionTag)
        assertEquals(
            fileName + " must declare exactly one <" + sectionTag + "> section. A missing section " +
                "backs up everything through that transport; a duplicated one makes the effective " +
                "policy ambiguous.",
            1,
            sections.length
        )

        val rules = childElements(sections.item(0))
        val excludes = mutableSetOf<Pair<String, String>>()
        val malformed = mutableSetOf<Pair<String, String>>()
        rules.filter { it.tagName == "exclude" }.forEach { element ->
            val domain = element.getAttribute("domain")
            val path = element.getAttribute("path")
            if (domain.isBlank() || path.isBlank()) {
                malformed += domain to path
            } else {
                excludes += domain to path
            }
        }
        val includes = rules.filter { it.tagName == "include" }
            .map { it.getAttribute("domain") to it.getAttribute("path") }
            .toSet()

        return BackupSection(label, excludes, includes, malformed)
    }

    private fun childElements(node: Node): List<Element> =
        (0 until node.childNodes.length)
            .map { node.childNodes.item(it) }
            .filterIsInstance<Element>()

    /**
     * Every directory name the production sources create under `filesDir`. Scans the lines that
     * build such a `File` and takes the snake_case literals on them, which also catches the
     * conditional `File(filesDir, if (...) "a" else "b")` form. Lines that use a constant instead of
     * a literal contribute nothing here and are pinned by
     * [main scan constants still match the excluded directory names] instead.
     */
    private fun productionFilesDirDirectories(): Set<String> {
        val sourceRoot = locate(
            "app/src/main/java/com/dev/docscannerpdf",
            "src/main/java/com/dev/docscannerpdf"
        )
        val construction = Regex("File\\(\\s*(?:context\\.)?filesDir\\s*,")
        val literal = Regex("\"([a-z][a-z0-9_]*)\"")
        return sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { it.readLines().asSequence() }
            .filter { construction.containsMatchIn(it) }
            .flatMap { line -> literal.findAll(line).map { it.groupValues[1] } }
            .toSet()
    }

    /** Resolves a repository path whether the test runs from the module or the repository root. */
    private fun locate(vararg candidates: String): File =
        candidates.map(::File).firstOrNull { it.exists() }
            ?: throw AssertionError(
                "Could not resolve any of " + candidates.toList() + " from " +
                    File(".").absolutePath + ". The backup policy could not be read, so it cannot " +
                    "be verified."
            )

    private fun describe(rules: Set<Pair<String, String>>): String =
        if (rules.isEmpty()) {
            "(none)"
        } else {
            rules.sortedWith(compareBy({ it.first }, { it.second }))
                .joinToString(", ") { "domain=" + it.first + " path=" + it.second }
        }
}
