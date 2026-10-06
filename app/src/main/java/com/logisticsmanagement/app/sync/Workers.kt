package com.logisticsmanagement.app.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.logisticsmanagement.app.LogisticsApplication
import com.logisticsmanagement.app.model.AppFailure
import java.io.IOException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException

class AccountSyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as LogisticsApplication).container
        val id = checkNotNull(inputData.getString("accountId"))
        return try { container.syncCoordinator.syncAccount(id); Result.success() }
        catch (error: AppFailure) { Result.failure(workDataOf("error" to error.message)) }
        catch (error: IOException) { Result.failure(workDataOf("error" to "物流查询网络请求失败")) }
    }
}
class ManualTrackingWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as LogisticsApplication).container
        return try { container.syncCoordinator.syncManual(); Result.success() }
        catch (error: AppFailure) { Result.failure(workDataOf("error" to error.message)) }
        catch (error: IOException) { Result.failure(workDataOf("error" to "物流查询网络请求失败")) }
    }
}
class DailyReportWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as LogisticsApplication).container
        return try {
            val answer = withTimeout(8 * 60 * 1000L) { container.aiService.generateDailyReport() }
            container.notifications.report(answer.text)
            container.syncCoordinator.scheduleReport(afterRun = true)
            Result.success()
        } catch (error: TimeoutCancellationException) {
            container.syncCoordinator.scheduleReport(afterRun = true)
            Result.failure(workDataOf("error" to "AI 日报超过执行期限"))
        } catch (error: AppFailure) {
            container.syncCoordinator.scheduleReport(afterRun = true)
            Result.failure(workDataOf("error" to error.message))
        } catch (error: IOException) {
            container.syncCoordinator.scheduleReport(afterRun = true)
            Result.failure(workDataOf("error" to "AI日报网络请求失败"))
        }
    }
}
