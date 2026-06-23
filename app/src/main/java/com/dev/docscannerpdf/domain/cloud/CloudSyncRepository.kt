package com.dev.docscannerpdf.domain.cloud

import android.content.Context
import android.accounts.Account
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.work.ExistingWorkPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.dev.docscannerpdf.data.local.AppDatabase
import com.dev.docscannerpdf.domain.analytics.AnalyticsRepository
import com.dev.docscannerpdf.domain.billing.BillingRepository
import com.google.android.gms.auth.GoogleAuthUtil
import java.security.KeyStore
import java.net.HttpURLConnection
import java.net.URL
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class CloudSyncState(
    val syncEnabled: Boolean = false,
    val isSignedIn: Boolean = false,
    val accountEmail: String? = null,
    val lastSyncAt: Long? = null,
    val statusMessage: String = "Cloud Sync is off.",
    val queuedPayloads: Int = 0,
    val isSyncing: Boolean = false
)

class CloudSyncRepository(
    context: Context,
    private val db: AppDatabase,
    private val billingRepository: BillingRepository,
    private val analyticsRepository: AnalyticsRepository
) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(loadState())
    val state: StateFlow<CloudSyncState> = _state.asStateFlow()

    fun setAccount(email: String?) {
        preferences.edit()
            .putString(KEY_ACCOUNT_EMAIL, email)
            .apply()
        _state.update {
            it.copy(
                isSignedIn = email != null,
                accountEmail = email,
                statusMessage = if (email == null) "Signed out." else "Signed in as $email."
            )
        }
    }

    fun setSyncEnabled(enabled: Boolean): Boolean {
        if (enabled && !billingRepository.premiumState.value.isPremium) {
            _state.update { it.copy(statusMessage = "Cloud Sync is a Premium feature.") }
            return false
        }
        if (enabled && !_state.value.isSignedIn) {
            _state.update { it.copy(statusMessage = "Sign in with Google before enabling sync.") }
            return false
        }
        preferences.edit().putBoolean(KEY_SYNC_ENABLED, enabled).apply()
        _state.update {
            it.copy(
                syncEnabled = enabled,
                statusMessage = if (enabled) "Cloud Sync enabled." else "Cloud Sync disabled."
            )
        }
        if (enabled) enqueueSync()
        return true
    }

    fun enqueueSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequest.Builder(CloudSyncWorker::class.java)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        _state.update { it.copy(statusMessage = "Sync queued.") }
    }

    suspend fun syncNow(): CloudSyncResult = withContext(Dispatchers.IO) {
        val current = loadState()
        if (!billingRepository.premiumState.value.isPremium) {
            updateStatus("Cloud Sync requires Premium.")
            return@withContext CloudSyncResult.RequiresPremium
        }
        if (!current.isSignedIn || !current.syncEnabled) {
            updateStatus("Sign in and enable Cloud Sync first.")
            return@withContext CloudSyncResult.NotReady
        }

        _state.update { it.copy(isSyncing = true, statusMessage = "Preparing encrypted metadata sync...") }
        analyticsRepository.trackEvent(AnalyticsRepository.EVENT_CLOUD_SYNC_STARTED)
        return@withContext try {
            val payload = buildMetadataPayload()
            val encrypted = encrypt(payload.toString().toByteArray(Charsets.UTF_8))
            queueEncryptedPayload(encrypted)
            uploadEncryptedMetadata(encrypted)
            markSynced()
            analyticsRepository.trackEvent(
                AnalyticsRepository.EVENT_CLOUD_SYNC_COMPLETED,
                mapOf("queued_payloads" to 0)
            )
            CloudSyncResult.Success
        } catch (throwable: Throwable) {
            updateStatus(throwable.message ?: "Sync failed. It will retry later.")
            analyticsRepository.recordNonFatal(
                throwable = throwable,
                area = "cloud_sync",
                metadata = mapOf("stage" to "sync_now")
            )
            CloudSyncResult.Failed
        } finally {
            _state.update { it.copy(isSyncing = false, queuedPayloads = getQueuedPayloadCount()) }
        }
    }

    private suspend fun buildMetadataPayload(): JSONObject {
        val documents = db.documentDao().getAllForBackup()
        val folders = db.folderDao().getAllForBackup()
        val tags = db.tagDao().getAllForBackup()
        val crossRefs = db.tagDao().getAllCrossRefsForBackup()
        return JSONObject()
            .put("schemaVersion", CLOUD_SYNC_SCHEMA_VERSION)
            .put("createdAt", System.currentTimeMillis())
            .put("account", _state.value.accountEmail)
            .put("documents", JSONArray().also { array ->
                documents.forEach { document ->
                    array.put(
                        JSONObject()
                            .put("id", document.id)
                            .put("title", document.title)
                            .put("timestamp", document.timestamp)
                            .put("pageCount", document.pageCount)
                            .put("extractedText", document.extractedText)
                            .put("folderId", document.folderId)
                            .put("isFavorite", document.isFavorite)
                            .put("isPinned", document.isPinned)
                            .put("tags", document.tags)
                            .put("isDeleted", document.isDeleted)
                            .put("deletedAt", document.deletedAt)
                    )
                }
            })
            .put("folders", JSONArray().also { array ->
                folders.forEach { folder ->
                    array.put(
                        JSONObject()
                            .put("id", folder.id)
                            .put("name", folder.name)
                            .put("createdAt", folder.createdAt)
                            .put("isDefault", folder.isDefault)
                            .put("sortOrder", folder.sortOrder)
                    )
                }
            })
            .put("tags", JSONArray().also { array ->
                tags.forEach { tag ->
                    array.put(
                        JSONObject()
                            .put("id", tag.id)
                            .put("name", tag.name)
                            .put("createdAt", tag.createdAt)
                            .put("isDefault", tag.isDefault)
                            .put("sortOrder", tag.sortOrder)
                    )
                }
            })
            .put("documentTagCrossRefs", JSONArray().also { array ->
                crossRefs.forEach { ref ->
                    array.put(JSONObject().put("documentId", ref.documentId).put("tagId", ref.tagId))
                }
            })
    }

    private fun queueEncryptedPayload(payload: ByteArray) {
        preferences.edit()
            .putString(KEY_LAST_ENCRYPTED_PAYLOAD, android.util.Base64.encodeToString(payload, android.util.Base64.NO_WRAP))
            .putInt(KEY_QUEUED_PAYLOADS, 1)
            .apply()
    }

    private fun uploadEncryptedMetadata(payload: ByteArray) {
        val email = _state.value.accountEmail ?: error("Google account is not signed in.")
        val token = GoogleAuthUtil.getToken(
            appContext,
            Account(email, "com.google"),
            "oauth2:$DRIVE_APPDATA_SCOPE"
        )
        try {
            val boundary = "docscanner-${UUID.randomUUID()}"
            val metadata = JSONObject()
                .put("name", "docscanner-metadata-sync.json.enc")
                .put("parents", JSONArray().put("appDataFolder"))
                .toString()
            val body = buildMultipartBody(boundary, metadata, payload)
            val connection = (URL(DRIVE_UPLOAD_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
                setRequestProperty("Content-Length", body.size.toString())
            }
            connection.outputStream.use { output -> output.write(body) }
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val errorText = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                error("Cloud upload failed ($responseCode). $errorText")
            }
        } finally {
            GoogleAuthUtil.clearToken(appContext, token)
        }
    }

    private fun buildMultipartBody(boundary: String, metadata: String, payload: ByteArray): ByteArray {
        val prefix = buildString {
            append("--").append(boundary).append("\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            append(metadata).append("\r\n")
            append("--").append(boundary).append("\r\n")
            append("Content-Type: application/octet-stream\r\n\r\n")
        }.toByteArray(Charsets.UTF_8)
        val suffix = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        return prefix + payload + suffix
    }

    private fun markSynced() {
        val now = System.currentTimeMillis()
        preferences.edit()
            .putLong(KEY_LAST_SYNC_AT, now)
            .putInt(KEY_QUEUED_PAYLOADS, 0)
            .apply()
        _state.update {
            it.copy(
                lastSyncAt = now,
                queuedPayloads = 0,
                statusMessage = "Encrypted metadata synced. PDFs were not uploaded."
            )
        }
    }

    private fun updateStatus(message: String) {
        _state.update { it.copy(statusMessage = message, queuedPayloads = getQueuedPayloadCount()) }
    }

    private fun loadState(): CloudSyncState {
        val email = preferences.getString(KEY_ACCOUNT_EMAIL, null)
        return CloudSyncState(
            syncEnabled = preferences.getBoolean(KEY_SYNC_ENABLED, false),
            isSignedIn = email != null,
            accountEmail = email,
            lastSyncAt = preferences.getLong(KEY_LAST_SYNC_AT, 0L).takeIf { it > 0L },
            queuedPayloads = getQueuedPayloadCount(),
            statusMessage = if (email == null) "Sign in to enable Cloud Sync." else "Ready."
        )
    }

    private fun getQueuedPayloadCount(): Int = preferences.getInt(KEY_QUEUED_PAYLOADS, 0)

    private fun encrypt(plainText: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plainText)
        return iv + encrypted
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keyStore.getKey(KEYSTORE_ALIAS, null)?.let { return it as SecretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    companion object {
        const val CLOUD_SYNC_SCHEMA_VERSION = 1
        const val WORK_NAME = "cloud_metadata_sync"

        private const val PREFS_NAME = "cloud_sync"
        private const val KEY_SYNC_ENABLED = "sync_enabled"
        private const val KEY_ACCOUNT_EMAIL = "account_email"
        private const val KEY_LAST_SYNC_AT = "last_sync_at"
        private const val KEY_LAST_ENCRYPTED_PAYLOAD = "last_encrypted_payload"
        private const val KEY_QUEUED_PAYLOADS = "queued_payloads"
        private const val KEYSTORE_ALIAS = "docscanner_cloud_sync_key"
        private const val DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"
        private const val DRIVE_UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart"
    }
}

enum class CloudSyncResult {
    Success,
    RequiresPremium,
    NotReady,
    Failed
}
