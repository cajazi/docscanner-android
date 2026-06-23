package com.dev.docscannerpdf

import android.app.Application
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import com.dev.docscannerpdf.domain.ads.AdManager
import com.dev.docscannerpdf.domain.analytics.AnalyticsRepository
import com.dev.docscannerpdf.domain.backup.BackupRepository
import com.dev.docscannerpdf.domain.billing.BillingRepository
import com.dev.docscannerpdf.domain.cloud.CloudSyncRepository
import com.dev.docscannerpdf.domain.onboarding.OnboardingRepository
import com.dev.docscannerpdf.data.local.AppDatabase
import com.dev.docscannerpdf.data.repository.DocumentRepository
import com.dev.docscannerpdf.data.repository.FolderRepository
import com.dev.docscannerpdf.data.repository.TagRepository

class DocScannerPdfApplication : Application() {
    lateinit var repository: DocumentRepository
        private set
    lateinit var folderRepository: FolderRepository
        private set
    lateinit var tagRepository: TagRepository
        private set
    lateinit var backupRepository: BackupRepository
        private set
    lateinit var analyticsRepository: AnalyticsRepository
        private set
    lateinit var billingRepository: BillingRepository
        private set
    lateinit var cloudSyncRepository: CloudSyncRepository
        private set
    lateinit var onboardingRepository: OnboardingRepository
        private set

    override fun onCreate() {
        super.onCreate()

        val db = Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java,
            "docscanner_pdf.db"
        )
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
            .build()

        repository = DocumentRepository(db.documentDao())
        folderRepository = FolderRepository(db.folderDao())
        tagRepository = TagRepository(db, db.tagDao(), db.documentDao())
        backupRepository = BackupRepository(this, db)
        analyticsRepository = AnalyticsRepository(this)
        billingRepository = BillingRepository(this, analyticsRepository)
        cloudSyncRepository = CloudSyncRepository(this, db, billingRepository, analyticsRepository)
        onboardingRepository = OnboardingRepository(this)

        // Initialize Ads SDK (keeps Room DB initialization unchanged)
        AdManager.initialize(this)
        AdManager.setPremiumProvider { billingRepository.premiumState.value.isPremium }
        billingRepository.start()
    }

    private companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE documents ADD COLUMN folderId INTEGER")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS folders (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        isDefault INTEGER NOT NULL DEFAULT 0,
                        sortOrder INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_folders_name ON folders(name)")
                val now = System.currentTimeMillis()
                listOf("Receipts", "ID Cards", "Notes", "Business").forEachIndexed { index, name ->
                    db.execSQL(
                        "INSERT OR IGNORE INTO folders (name, createdAt, isDefault, sortOrder) VALUES (?, ?, 1, ?)",
                        arrayOf<Any>(name, now, index + 1)
                    )
                }
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE documents ADD COLUMN isFavorite INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE documents ADD COLUMN isPinned INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE documents ADD COLUMN tags TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE documents ADD COLUMN searchableText TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    """
                    UPDATE documents
                    SET searchableText = lower(
                        trim(
                            title || ' ' ||
                            coalesce(extractedText, '') || ' ' ||
                            tags || ' ' ||
                            CASE WHEN isFavorite = 1 THEN 'favorite starred' ELSE '' END || ' ' ||
                            CASE WHEN isPinned = 1 THEN 'pinned important' ELSE '' END
                        )
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_documents_title ON documents(title)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_documents_folderId ON documents(folderId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_documents_tags ON documents(tags)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_documents_searchableText ON documents(searchableText)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_documents_isFavorite_isPinned_timestamp ON documents(isFavorite, isPinned, timestamp)")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS tags (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        isDefault INTEGER NOT NULL DEFAULT 0,
                        sortOrder INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS document_tag_cross_ref (
                        documentId INTEGER NOT NULL,
                        tagId INTEGER NOT NULL,
                        PRIMARY KEY(documentId, tagId),
                        FOREIGN KEY(documentId) REFERENCES documents(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(tagId) REFERENCES tags(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_tags_name ON tags(name)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_document_tag_cross_ref_documentId ON document_tag_cross_ref(documentId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_document_tag_cross_ref_tagId ON document_tag_cross_ref(tagId)")

                val now = System.currentTimeMillis()
                val defaultTags = listOf("Work", "Receipt", "Invoice", "School", "Personal", "Medical")
                defaultTags.forEachIndexed { index, name ->
                    db.execSQL(
                        "INSERT OR IGNORE INTO tags (name, createdAt, isDefault, sortOrder) VALUES (?, ?, 1, ?)",
                        arrayOf<Any>(name, now, index + 1)
                    )
                }

                val legacyDocumentTags = mutableListOf<Pair<Long, List<String>>>()
                db.query("SELECT id, tags FROM documents WHERE tags <> ''").use { cursor ->
                    while (cursor.moveToNext()) {
                        val documentId = cursor.getLong(0)
                        val tagNames = cursor.getString(1)
                            .split(',', '#')
                            .mapNotNull { tag -> normalizeMigratedTagName(tag) }
                            .distinctBy { tag -> tag.lowercase() }
                        if (tagNames.isNotEmpty()) {
                            legacyDocumentTags += documentId to tagNames
                        }
                    }
                }

                legacyDocumentTags.forEach { (documentId, tagNames) ->
                    tagNames.forEach { tagName ->
                        db.execSQL(
                            "INSERT OR IGNORE INTO tags (name, createdAt, isDefault, sortOrder) VALUES (?, ?, 0, 100)",
                            arrayOf<Any>(tagName, now)
                        )
                        db.query(
                            SimpleSQLiteQuery(
                                "SELECT id FROM tags WHERE name = ? COLLATE NOCASE LIMIT 1",
                                arrayOf(tagName)
                            )
                        ).use { cursor ->
                            if (cursor.moveToFirst()) {
                                db.execSQL(
                                    "INSERT OR IGNORE INTO document_tag_cross_ref (documentId, tagId) VALUES (?, ?)",
                                    arrayOf<Any>(documentId, cursor.getLong(0))
                                )
                            }
                        }
                    }
                }
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE documents ADD COLUMN isDeleted INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE documents ADD COLUMN deletedAt INTEGER")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_documents_isDeleted_deletedAt ON documents(isDeleted, deletedAt)")
            }
        }

        private fun normalizeMigratedTagName(tag: String): String? {
            return tag.trim()
                .trimStart('#')
                .replace(Regex("\\s+"), " ")
                .takeIf { it.isNotBlank() }
                ?.take(40)
        }
    }
}
