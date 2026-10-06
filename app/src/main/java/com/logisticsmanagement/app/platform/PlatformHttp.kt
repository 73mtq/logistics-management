package com.logisticsmanagement.app.platform

import com.logisticsmanagement.app.model.AppFailure
import com.logisticsmanagement.app.model.FailureKind
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class PlatformHttpResponse(val body: String, val cookies: List<String>)

internal suspend fun platformRequest(client: OkHttpClient, request: Request): PlatformHttpResponse = suspendCancellableCoroutine { continuation ->
    val call = client.newCall(request)
    continuation.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, error: IOException) {
            if (continuation.isActive) continuation.resumeWithException(AppFailure("平台网络请求失败，请检查网络连接", FailureKind.NETWORK))
        }
        override fun onResponse(call: Call, response: Response) {
            response.use {
                if (!continuation.isActive) return
                if (!response.isSuccessful) {
                    val kind = if (response.code == 401 || response.code == 403) FailureKind.NEEDS_VERIFICATION else FailureKind.SERVICE_ERROR
                    continuation.resumeWithException(AppFailure("平台返回 HTTP ${response.code}", kind))
                    return
                }
                val body = response.body ?: error("平台 HTTP 响应缺少 body")
                val text = try { body.string() }
                catch (error: IOException) {
                    continuation.resumeWithException(AppFailure("平台响应读取失败", FailureKind.NETWORK))
                    return
                }
                if (continuation.isActive) continuation.resume(PlatformHttpResponse(text, response.headers("Set-Cookie")))
            }
        }
    })
}
