package com.logisticsmanagement.app.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.logisticsmanagement.app.LogisticsApplication
import com.logisticsmanagement.app.model.AppFailure

class AccountSyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as LogisticsApplication).container
        container.ready.await()
        val id = checkNotNull(inputData.getString("accountId"))
        return try {
            container.syncCoordinator.syncAccount(id, automatic = true)
            Result.success()
        } catch (error: AppFailure) { Result.failure(workDataOf("error" to error.message)) }
    }
}
