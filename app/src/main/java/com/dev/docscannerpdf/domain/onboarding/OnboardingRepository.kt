package com.dev.docscannerpdf.domain.onboarding

import android.content.Context

class OnboardingRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isOnboardingCompleted(): Boolean {
        return preferences.getBoolean(KEY_ONBOARDING_COMPLETED, false)
    }

    fun markOnboardingCompleted() {
        preferences.edit()
            .putBoolean(KEY_ONBOARDING_COMPLETED, true)
            .apply()
    }

    fun resetOnboarding() {
        preferences.edit()
            .putBoolean(KEY_ONBOARDING_COMPLETED, false)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "first_run_onboarding"
        const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
    }
}
