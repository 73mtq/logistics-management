package com.logisticsmanagement.app.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.logisticsmanagement.app.LogisticsApplication
import com.logisticsmanagement.app.model.AppFailure
import kotlinx.coroutines.CancellationException

class AccountSyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as LogisticsApplication).container
        container.ready.await()
        val id = checkNotNull(inputData.getString("accountId"))
        Log.i("AccountSyncWorker", "start account=$id")
        return try {
            container.syncCoordinator.syncAccount(id, automatic = true)
            Log.i("AccountSyncWorker", "success account=$id")
            Result.success()
        } catch (error: AppFailure) {
            Log.i("AccountSyncWorker", "failure kind=${error.kind} account=$id")
            Result.failure(workDataOf("error" to error.message))
        } catch (error: CancellationException) {
            Log.i("AccountSyncWorker", "stopped account=$id")
            throw error
        } finally {
            Log.i("AccountSyncWorker", "finished account=$id")
        }
    }
}
