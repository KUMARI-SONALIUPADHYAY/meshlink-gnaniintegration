package com.meshlink.app.core.cloud

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.meshlink.app.core.mesh.CloudSyncCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** WorkManager retry worker: network-constrained and safe to run after the Activity is gone. */
class CloudSyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val mode = applicationContext.getSharedPreferences("meshlink_settings", Context.MODE_PRIVATE).getString("app_mode", null)
        if (mode != "online") return@withContext Result.success()
        val api = CloudApiClient(applicationContext)
        if (!api.isConfigured() || !api.isAuthenticated()) return@withContext Result.success()
        val result = CloudSyncCoordinator(applicationContext).syncPending(api)
        result.fold(
            onSuccess = { Result.success() },
            onFailure = { error ->
                if ((error is CloudApiException && error.statusCode in setOf(401, 403, 409)) || error.message.orEmpty().contains("different cloud account", ignoreCase = true)) Result.success()
                else Result.retry()
            }
        )
    }
}
