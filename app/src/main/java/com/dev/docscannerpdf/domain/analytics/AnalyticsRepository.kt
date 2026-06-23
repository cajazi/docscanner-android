package com.dev.docscannerpdf.domain.analytics

import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ObservabilitySettings(
    val analyticsEnabled: Boolean = false,
    val crashReportingEnabled: Boolean = false
)

class AnalyticsRepository(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val firebaseAnalytics by lazy { FirebaseAnalytics.getInstance(appContext) }
    private val firebaseCrashlytics by lazy { FirebaseCrashlytics.getInstance() }

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<ObservabilitySettings> = _settings.asStateFlow()

    init {
        applyCollectionSettings(_settings.value)
    }

    fun setAnalyticsEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_ANALYTICS_ENABLED, enabled).apply()
        _settings.update { it.copy(analyticsEnabled = enabled) }
        runCatching { firebaseAnalytics.setAnalyticsCollectionEnabled(enabled) }
    }

    fun setCrashReportingEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_CRASH_REPORTING_ENABLED, enabled).apply()
        _settings.update { it.copy(crashReportingEnabled = enabled) }
        runCatching { firebaseCrashlytics.setCrashlyticsCollectionEnabled(enabled) }
    }

    fun trackEvent(name: String, metadata: Map<String, Any?> = emptyMap()) {
        if (!_settings.value.analyticsEnabled) return
        runCatching { firebaseAnalytics.logEvent(name, metadata.toPrivacySafeBundle()) }
    }

    fun recordNonFatal(
        throwable: Throwable,
        area: String,
        metadata: Map<String, String> = emptyMap()
    ) {
        if (!_settings.value.crashReportingEnabled) return
        runCatching {
            firebaseCrashlytics.setCustomKey("area", area.take(MAX_VALUE_LENGTH))
            metadata.forEach { (key, value) ->
                firebaseCrashlytics.setCustomKey(key.take(MAX_KEY_LENGTH), value.take(MAX_VALUE_LENGTH))
            }
            firebaseCrashlytics.recordException(throwable)
        }
    }

    private fun applyCollectionSettings(settings: ObservabilitySettings) {
        runCatching { firebaseAnalytics.setAnalyticsCollectionEnabled(settings.analyticsEnabled) }
        runCatching { firebaseCrashlytics.setCrashlyticsCollectionEnabled(settings.crashReportingEnabled) }
    }

    private fun loadSettings(): ObservabilitySettings {
        return ObservabilitySettings(
            analyticsEnabled = preferences.getBoolean(KEY_ANALYTICS_ENABLED, false),
            crashReportingEnabled = preferences.getBoolean(KEY_CRASH_REPORTING_ENABLED, false)
        )
    }

    private fun Map<String, Any?>.toPrivacySafeBundle(): Bundle {
        val bundle = Bundle()
        forEach { (rawKey, value) ->
            val key = rawKey.take(MAX_KEY_LENGTH)
            when (value) {
                null -> Unit
                is Int -> bundle.putInt(key, value)
                is Long -> bundle.putLong(key, value)
                is Float -> bundle.putFloat(key, value)
                is Double -> bundle.putDouble(key, value)
                is Boolean -> bundle.putString(key, value.toString())
                is String -> bundle.putString(key, value.take(MAX_VALUE_LENGTH))
                else -> bundle.putString(key, value.toString().take(MAX_VALUE_LENGTH))
            }
        }
        return bundle
    }

    companion object {
        const val EVENT_SCAN_CREATED = "scan_created"
        const val EVENT_PDF_OPENED = "pdf_opened"
        const val EVENT_PDF_SHARED = "pdf_shared"
        const val EVENT_OCR_EXTRACTED = "ocr_extracted"
        const val EVENT_PDF_MERGED = "pdf_merged"
        const val EVENT_PDF_SPLIT = "pdf_split"
        const val EVENT_PDF_COMPRESSED = "pdf_compressed"
        const val EVENT_PREMIUM_OPENED = "premium_opened"
        const val EVENT_PREMIUM_PURCHASED = "premium_purchased"
        const val EVENT_BACKUP_CREATED = "backup_created"
        const val EVENT_BACKUP_RESTORED = "backup_restored"
        const val EVENT_CLOUD_SYNC_STARTED = "cloud_sync_started"
        const val EVENT_CLOUD_SYNC_COMPLETED = "cloud_sync_completed"

        private const val PREFS_NAME = "observability_settings"
        private const val KEY_ANALYTICS_ENABLED = "analytics_enabled"
        private const val KEY_CRASH_REPORTING_ENABLED = "crash_reporting_enabled"
        private const val MAX_KEY_LENGTH = 40
        private const val MAX_VALUE_LENGTH = 100
    }
}
