package com.meshlink.app.core.ai

import com.meshlink.app.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton

/** Build-time view of the developer-local credential. Never log or expose the value. */
@Singleton
class GnaniApiCredentials @Inject constructor() {
    private val key: String = BuildConfig.GNANI_API_KEY.trim()

    fun isConfigured(): Boolean = key.isNotEmpty()

    internal fun authenticationHeaderValue(): String {
        if (key.isBlank()) throw GnaniApiException(
            "Gnani is not configured. Add gnani.api.key to the project-root local.properties and rebuild.",
            error = GnaniApiError.MISSING_CREDENTIAL
        )
        return key
    }
}
