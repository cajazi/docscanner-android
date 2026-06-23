package com.dev.docscannerpdf.domain.cloud

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.dev.docscannerpdf.DocScannerPdfApplication
import kotlinx.coroutines.runBlocking

class CloudSyncWorker(
    context: Context,
    workerParams: WorkerParameters
) : Worker(context, workerParams) {
    override fun doWork(): Result {
        val app = applicationContext as? DocScannerPdfApplication ?: return Result.failure()
        return when (runBlocking { app.cloudSyncRepository.syncNow() }) {
            CloudSyncResult.Success -> Result.success()
            CloudSyncResult.RequiresPremium,
            CloudSyncResult.NotReady -> Result.failure()
            CloudSyncResult.Failed -> Result.retry()
        }
    }
}
